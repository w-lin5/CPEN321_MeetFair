import { BadRequest, Conflict, Forbidden, NotFound } from '../common/errors';
import { tuning } from '../config';
import { VOTING_EVENTS, broadcaster, votingRoom } from '../realtime/broadcaster';
import { groupService } from '../users-groups/group.service';
import { meetupService } from '../users-groups/meetup.service';
import { MeetupModel } from '../users-groups/models';
import { VoteModel, VotingSessionModel } from './models';
import type { CandidateInput } from './schemas';

type SessionDoc = InstanceType<typeof VotingSessionModel>;
type VoteDoc = InstanceType<typeof VoteModel>;

function isDuplicateKey(err: unknown): boolean {
  return typeof err === 'object' && err !== null && 'code' in err && err.code === tuning.mongoDuplicateKeyCode;
}

function sameWeight(a: number, b: number): boolean {
  return Math.abs(a - b) < tuning.voteWeightEpsilon;
}

function hasCandidate(session: SessionDoc, candidateId: string): boolean {
  return session.candidates.some((c) => String(c._id) === candidateId);
}

// Load a session the caller may access: it exists and the caller belongs to its meetup's group (404 / 403 otherwise)
async function loadForMember(sessionId: string, userId: string) {
  const session = await VotingSessionModel.findById(sessionId);
  if (!session) throw new NotFound('Voting session not found');

  const meetup = await MeetupModel.findById(session.meetupId);
  if (!meetup) throw new NotFound('Meetup not found');

  const group = await groupService.requireMembership(String(meetup.groupId), userId);

  return { session, meetup, group };
}

// Session JSON plus the caller's current choice, so the UI can highlight it
async function withMyVote(session: SessionDoc, userId: string) {
  const mine = await VoteModel.findOne({ sessionId: session._id, userId });
  return { ...session.toJSON(), myCandidateId: mine ? String(mine.candidateId) : null };
}

// Raw count and weighted total per candidate, in the session's candidate order
function tally(session: SessionDoc, votes: VoteDoc[]) {
  return session.candidates.map((candidate) => {
    const candidateId = String(candidate._id);
    const candidateVotes = votes.filter((v) => String(v.candidateId) === candidateId);

    return {
      candidateId,
      voteCount: candidateVotes.length,
      totalWeight: candidateVotes.reduce((sum, v) => sum + v.weight, 0),
      // Highest single voter weight; breaks ties in favour of the member with the most fairness debt
      topVoterWeight: candidateVotes.reduce((max, v) => Math.max(max, v.weight), 0),
    };
  });
}

// Results are identical for every member, so the same payload is returned over REST and broadcast live
async function buildResults(session: SessionDoc, eligibleVoterCount: number) {
  const votes = await VoteModel.find({ sessionId: session._id });

  return {
    sessionId: String(session._id),
    status: session.status,
    winnerCandidateId: session.winnerCandidateId ? String(session.winnerCandidateId) : null,
    voterCount: votes.length,
    eligibleVoterCount,
    totals: tally(session, votes).map(({ candidateId, voteCount, totalWeight }) => ({
      candidateId,
      voteCount,
      totalWeight,
    })),
  };
}

// Candidates still tied after comparing weighted totals, then top individual voter weight
function tiedLeaders(session: SessionDoc, votes: VoteDoc[]): string[] {
  const totals = tally(session, votes);
  const best = Math.max(...totals.map((t) => t.totalWeight));
  const leaders = totals.filter((t) => sameWeight(t.totalWeight, best));
  const bestVoter = Math.max(...leaders.map((t) => t.topVoterWeight));

  return leaders.filter((t) => sameWeight(t.topVoterWeight, bestVoter)).map((t) => t.candidateId);
}

// Decide the winner; a manual pick is only accepted when nobody voted or the tie cannot be broken
function pickWinner(session: SessionDoc, votes: VoteDoc[], manualPick: string | undefined): string {
  if (manualPick !== undefined && !hasCandidate(session, manualPick)) {
    throw new BadRequest('Candidate does not belong to this voting session');
  }

  if (votes.length === 0) {
    if (manualPick === undefined) {
      throw new Conflict('Cannot finalize with zero votes; cast a vote or pick a winner');
    }
    return manualPick;
  }

  const leaders = tiedLeaders(session, votes);
  const [onlyLeader] = leaders;

  if (leaders.length === 1 && onlyLeader !== undefined) {
    if (manualPick !== undefined && manualPick !== onlyLeader) {
      throw new Conflict('A winner can only be picked manually to break a tie');
    }
    return onlyLeader;
  }

  if (manualPick === undefined || !leaders.includes(manualPick)) {
    throw new Conflict(`Tie between candidates ${leaders.join(', ')}; pick one of them as the winner`);
  }

  return manualPick;
}

// Organizer turns the recommender's candidates into a vote; moves the meetup from SETUP to VOTING
async function createSession(meetupId: string, userId: string, candidates: CandidateInput[]) {
  const meetup = await meetupService.getForMember(meetupId, userId);
  if (String(meetup.organizerId) !== userId) {
    throw new Forbidden('Only the meetup organizer can start voting');
  }

  // Claim the status transition first so two concurrent starts cannot both succeed
  const moved = await MeetupModel.updateOne({ _id: meetupId, status: 'SETUP' }, { $set: { status: 'VOTING' } });
  if (moved.modifiedCount === 0) {
    throw new Conflict('Voting can only start while the meetup is in SETUP');
  }

  try {
    return await VotingSessionModel.create({ meetupId, candidates, createdBy: userId });
  } catch (err) {
    await MeetupModel.updateOne({ _id: meetupId, status: 'VOTING' }, { $set: { status: 'SETUP' } });
    if (isDuplicateKey(err)) throw new Conflict('This meetup already has a voting session');
    throw err;
  }
}

async function getSession(sessionId: string, userId: string) {
  const { session } = await loadForMember(sessionId, userId);
  return withMyVote(session, userId);
}

// Entry point from the meetup screen, which only knows the meetup id
async function getSessionForMeetup(meetupId: string, userId: string) {
  await meetupService.getForMember(meetupId, userId);

  const session = await VotingSessionModel.findOne({ meetupId });
  if (!session) throw new NotFound('Voting has not started for this meetup');

  return withMyVote(session, userId);
}

// Create or change the caller's single vote; members are only notified when something actually changed
async function submitVote(sessionId: string, userId: string, candidateId: string, weight: number) {
  if (!Number.isFinite(weight) || weight < 0) {
    throw new BadRequest('Vote weight must be a non-negative finite number');
  }

  const { session, group } = await loadForMember(sessionId, userId);

  if (session.status !== 'open') throw new Conflict('Voting session is closed');
  if (!hasCandidate(session, candidateId)) {
    throw new BadRequest('Candidate does not belong to this voting session');
  }

  const existing = await VoteModel.findOne({ sessionId, userId });
  if (existing && String(existing.candidateId) === candidateId && sameWeight(existing.weight, weight)) {
    return existing;
  }

  // Upsert on the unique (sessionId, userId) key: changing a vote never creates a second one
  const vote = await VoteModel.findOneAndUpdate(
    { sessionId, userId },
    { $set: { candidateId, weight } },
    { upsert: true, returnDocument: 'after', setDefaultsOnInsert: true },
  ).orFail();

  broadcaster.publish(
    votingRoom(sessionId),
    VOTING_EVENTS.results,
    await buildResults(session, group.members.length),
  );

  return vote;
}

async function getResults(sessionId: string, userId: string) {
  const { session, group } = await loadForMember(sessionId, userId);
  return buildResults(session, group.members.length);
}

// Close voting, record the winner and finalize the meetup; the session is reopened if finalizing fails
async function closeSession(sessionId: string, userId: string, manualPick: string | undefined) {
  const { session, meetup, group } = await loadForMember(sessionId, userId);

  if (String(session.createdBy) !== userId) {
    throw new Forbidden('Only the creator can close this voting session');
  }
  if (session.status !== 'open') throw new Conflict('Voting session is already closed');

  const winnerCandidateId = pickWinner(session, await VoteModel.find({ sessionId }), manualPick);

  // Conditional on still being open so a concurrent close (or a late vote check) sees one outcome
  const closed = await VotingSessionModel.findOneAndUpdate(
    { _id: sessionId, status: 'open' },
    { $set: { status: 'closed', winnerCandidateId } },
    { returnDocument: 'after' },
  );
  if (!closed) throw new Conflict('Voting session is already closed');

  try {
    await MeetupModel.updateOne({ _id: meetup._id, status: 'VOTING' }, { $set: { status: 'FINALIZED' } });
  } catch (err) {
    await VotingSessionModel.updateOne({ _id: sessionId }, { $set: { status: 'open', winnerCandidateId: null } });
    throw err;
  }

  broadcaster.publish(votingRoom(sessionId), VOTING_EVENTS.closed, {
    session: closed.toJSON(),
    results: await buildResults(closed, group.members.length),
  });

  return withMyVote(closed, userId);
}

export const voteService = {
  createSession,
  getSession,
  getSessionForMeetup,
  submitVote,
  getResults,
  closeSession,
};
