import { BadRequest, Conflict, Forbidden, NotFound } from '../common/errors';
import { tuning } from '../config';
import { calendarClient } from '../integrations/calendar.client';
import { VOTING_EVENTS, votingRoom } from '../realtime/events';
import { publisher } from '../realtime/publisher';
import { groupService } from '../users-groups/group.service';
import { meetupService } from '../users-groups/meetup.service';
import { VoteModel, VotingSessionModel } from './models';
import type { CandidateInput } from './schemas';
import { sameWeight, tally, tiedLeaders } from './tally';

type SessionDoc = InstanceType<typeof VotingSessionModel>;
type VoteDoc = InstanceType<typeof VoteModel>;

function isDuplicateKey(err: unknown): boolean {
  return typeof err === 'object' && err !== null && 'code' in err && err.code === tuning.mongoDuplicateKeyCode;
}

function hasCandidate(session: SessionDoc, candidateId: string): boolean {
  return session.candidates.some((c) => String(c._id) === candidateId);
}

// Load a session the caller may access: it exists and the caller belongs to its meetup's group (404 / 403 otherwise)
async function loadForMember(sessionId: string, userId: string) {
  const session = await VotingSessionModel.findById(sessionId);
  if (!session) throw new NotFound('Voting session not found');

  const meetup = await meetupService.getById(String(session.meetupId));
  const group = await groupService.requireMembership(String(meetup.groupId), userId);

  return { session, meetup, group };
}

// Session JSON plus the caller's current choice, so the UI can highlight it
async function withMyVote(session: SessionDoc, userId: string) {
  const mine = await VoteModel.findOne({ sessionId: session._id, userId });
  return { ...session.toJSON(), myCandidateId: mine ? String(mine.candidateId) : null };
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
    totals: tally(session.candidates, votes).map(({ candidateId, voteCount, totalWeight }) => ({
      candidateId,
      voteCount,
      totalWeight,
    })),
  };
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

  const leaders = tiedLeaders(session.candidates, votes);
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
  if (!(await meetupService.transitionStatus(meetupId, 'SETUP', 'VOTING'))) {
    throw new Conflict('Voting can only start while the meetup is in SETUP');
  }

  try {
    return await VotingSessionModel.create({ meetupId, candidates, createdBy: userId });
  } catch (err) {
    await meetupService.transitionStatus(meetupId, 'VOTING', 'SETUP');
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

  publisher.publish(
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
    await meetupService.transitionStatus(String(meetup._id), 'VOTING', 'FINALIZED');
  } catch (err) {
    await VotingSessionModel.updateOne({ _id: sessionId }, { $set: { status: 'open', winnerCandidateId: null } });
    throw err;
  }

  publisher.publish(votingRoom(sessionId), VOTING_EVENTS.closed, {
    session: closed.toJSON(),
    results: await buildResults(closed, group.members.length),
  });

  return withMyVote(closed, userId);
}

/**
 * Adds a finalized meetup (at the winning place) to the caller's own primary Google Calendar.
 * [accessToken] is the device's short-lived calendar.events token: used once, never stored or logged.
 * Each participant can add the meetup once; the created event id is recorded on their participation entry.
 */
async function addToCalendar(meetupId: string, userId: string, accessToken: string) {
  const meetup = await meetupService.getForMember(meetupId, userId);

  if (meetup.status !== 'FINALIZED' && meetup.status !== 'SETTLED') {
    throw new Conflict('Only a finalized meetup can be added to a calendar');
  }
  const participant = meetup.participants.find((p) => String(p.userId) === userId);
  if (!participant) throw new Forbidden('Only participants can add this meetup to their calendar');
  if (participant.calendarEventId) throw new Conflict('This meetup is already in your calendar');

  const session = await VotingSessionModel.findOne({ meetupId });
  const winner = session?.candidates.find((c) => String(c._id) === String(session.winnerCandidateId));

  const start = meetup.scheduledAt;
  const end = new Date(start.getTime()
    + tuning.calendarEventDurationMinutes * tuning.secondsPerMinute * tuning.msPerSecond);

  const created = await calendarClient.createEvent(accessToken, {
    title: meetup.title,
    description: 'Planned with MeetFair',
    startTime: start.toISOString(),
    endTime: end.toISOString(),
    // Meetup times are stored as UTC instants
    timezone: 'UTC',
    location: winner ? [winner.name, winner.address].filter((part) => part !== '').join(', ') : '',
    attendeeEmails: [],
  });

  // A concurrent request may have recorded an event in the meantime; the first one wins
  if (!(await meetupService.recordCalendarEvent(meetupId, userId, created.externalId))) {
    throw new Conflict('This meetup is already in your calendar');
  }

  return { eventId: created.externalId, htmlLink: created.htmlLink };
}

export const votingService = {
  createSession,
  getSession,
  getSessionForMeetup,
  submitVote,
  getResults,
  closeSession,
  addToCalendar,
};
