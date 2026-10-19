import { BadRequest, Conflict, Forbidden, NotFound } from '../common/errors';
import { tuning } from '../config';
import { calendarClient } from '../integrations/calendar.client';
import { SERVER_EVENTS, meetupRoom } from '../realtime/events';
import { publisher } from '../realtime/publisher';
import { recommendationService } from '../recommendation/recommendation.service';
import { meetupService } from '../users-groups/meetup.service';
import { fairCreditService } from './fair-credit.service';
import { VoteModel } from './models';
import { decideWinner, tally } from './tally';

type MeetupDoc = Awaited<ReturnType<typeof meetupService.getById>>;

function isDuplicateKey(err: unknown): boolean {
  return typeof err === 'object' && err !== null && 'code' in err && err.code === tuning.mongoDuplicateKeyCode;
}

function requireParticipant(meetup: MeetupDoc, userId: string) {
  const participant = meetup.participants.find((p) => String(p.userId) === userId);
  if (!participant) throw new Forbidden('Only participants can do this');
  return participant;
}

// A meetup the caller may vote in: member of its group (404 / 403), meetup in VOTING (409), participant (403)
async function loadForVoting(meetupId: string, userId: string) {
  const meetup = await meetupService.getForMember(meetupId, userId);
  if (meetup.status !== 'VOTING') throw new Conflict('Voting is not open for this meetup');
  requireParticipant(meetup, userId);
  return meetup;
}

async function candidatePlaceIds(meetupId: string): Promise<string[]> {
  const recommendation = await recommendationService.getByMeetupId(meetupId);
  return recommendation.candidates.map((c) => c.placeId);
}

// The same tally goes to every member, over REST and as vote:updated
async function buildTally(meetup: MeetupDoc) {
  const meetupId = String(meetup._id);
  const votes = await VoteModel.find({ meetupId });

  return {
    meetupId,
    ...tally(await candidatePlaceIds(meetupId), votes),
    participantCount: meetup.participants.length,
  };
}

async function broadcastTally(meetup: MeetupDoc): Promise<void> {
  publisher.publish(meetupRoom(String(meetup._id)), SERVER_EVENTS.voteUpdated, await buildTally(meetup));
}

// Create or change the caller's vote; 409 once it is locked
async function castVote(meetupId: string, userId: string, placeId: string) {
  const meetup = await loadForVoting(meetupId, userId);
  if (!(await candidatePlaceIds(meetupId)).includes(placeId)) {
    throw new BadRequest('placeId is not one of this meetup\'s candidates');
  }

  // Re-sending the same choice changes nothing, so members are not notified
  const existing = await VoteModel.findOne({ meetupId, userId });
  if (existing && !existing.locked && existing.placeId === placeId) return existing;

  let vote;
  try {
    // The filter never matches a locked vote, so the upsert then hits the unique key instead of changing it
    vote = await VoteModel.findOneAndUpdate(
      { meetupId, userId, locked: false },
      { $set: { placeId } },
      { upsert: true, returnDocument: 'after', setDefaultsOnInsert: true },
    ).orFail();
  } catch (err) {
    if (isDuplicateKey(err)) throw new Conflict('Your vote is locked');
    throw err;
  }

  await broadcastTally(meetup);
  return vote;
}

// Lock the caller's vote and snapshot its weight from their Fair Credit
async function lockVote(meetupId: string, userId: string) {
  const meetup = await loadForVoting(meetupId, userId);
  const weight = await fairCreditService.getVoteWeight(userId, String(meetup.groupId));

  const vote = await VoteModel.findOneAndUpdate(
    { meetupId, userId, locked: false },
    { $set: { locked: true, weight } },
    { returnDocument: 'after' },
  );
  if (!vote) {
    if (await VoteModel.exists({ meetupId, userId })) throw new Conflict('Your vote is already locked');
    throw new NotFound('Vote for a candidate before locking');
  }

  await broadcastTally(meetup);
  return vote;
}

// Tally for any member, plus the caller's own vote so the UI can highlight it
async function getVotes(meetupId: string, userId: string) {
  const meetup = await meetupService.getForMember(meetupId, userId);
  const mine = await VoteModel.findOne({ meetupId, userId });
  return { ...(await buildTally(meetup)), myVote: mine };
}

// Decide the winner; a manual pick is only accepted when nobody locked a vote or the tie cannot be broken
function pickWinner(placeIds: string[], votes: Parameters<typeof decideWinner>[1], manualPick: string | undefined) {
  if (manualPick !== undefined && !placeIds.includes(manualPick)) {
    throw new BadRequest('placeId is not one of this meetup\'s candidates');
  }

  const decision = decideWinner(placeIds, votes);
  if (decision.kind === 'none') {
    if (manualPick === undefined) throw new Conflict('Cannot finalize with no locked votes; pick a winner');
    return manualPick;
  }
  if (decision.kind === 'winner') {
    if (manualPick !== undefined && manualPick !== decision.placeId) {
      throw new Conflict('A winner can only be picked manually to break a tie');
    }
    return decision.placeId;
  }
  if (manualPick === undefined || !decision.placeIds.includes(manualPick)) {
    throw new Conflict(`Tie between candidates ${decision.placeIds.join(', ')}; pick one of them as the winner`);
  }
  return manualPick;
}

// Organizer closes voting: the winner (by locked weight) is stored on the meetup, which moves to FINALIZED
async function finalize(meetupId: string, userId: string, manualPick: string | undefined) {
  const meetup = await meetupService.getForMember(meetupId, userId);
  if (String(meetup.organizerId) !== userId) throw new Forbidden('Only the meetup organizer can finalize');
  if (meetup.status !== 'VOTING') throw new Conflict('Only a meetup in VOTING can be finalized');

  const recommendation = await recommendationService.getByMeetupId(meetupId);
  const placeIds = recommendation.candidates.map((c) => c.placeId);
  const winnerId = pickWinner(placeIds, await VoteModel.find({ meetupId }), manualPick);
  const winner = recommendation.candidates.find((c) => c.placeId === winnerId);
  if (!winner?.location) throw new NotFound('Winning candidate not found');

  // Conditional on still being in VOTING, so concurrent finalizes see one outcome
  const finalized = await meetupService.finalize(meetupId, {
    placeId: winner.placeId,
    name: winner.name,
    address: winner.address,
    location: { lat: winner.location.lat, lng: winner.location.lng },
  });
  if (!finalized) throw new Conflict('Only a meetup in VOTING can be finalized');

  publisher.publish(meetupRoom(meetupId), SERVER_EVENTS.meetupStatus, {
    meetupId,
    status: finalized.status,
    winner: finalized.toJSON().winner,
  });

  return finalized;
}

// Google event ids may use 0-9 and a-v; ObjectId hex fits, so one id per (meetup, user) makes inserts idempotent
function calendarEventIdFor(meetupId: string, userId: string): string {
  return `meetfair${meetupId}${userId}`.toLowerCase();
}

/**
 * Adds a finalized meetup (at the winning place) to the caller's own primary Google Calendar.
 * [accessToken] is the device's short-lived calendar.events token: used once, never stored or logged.
 * Each participant can add the meetup once. The Google event id is derived from (meetup, user), so a
 * repeated or concurrent request is rejected by Google as a duplicate instead of creating a second event.
 */
async function addToCalendar(meetupId: string, userId: string, accessToken: string) {
  const meetup = await meetupService.getForMember(meetupId, userId);

  if (meetup.status !== 'FINALIZED' && meetup.status !== 'SETTLED') {
    throw new Conflict('Only a finalized meetup can be added to a calendar');
  }
  const participant = requireParticipant(meetup, userId);
  if (participant.calendarEventId) throw new Conflict('This meetup is already in your calendar');

  const winner = meetup.winner;
  const start = meetup.scheduledAt;
  const end = new Date(start.getTime()
    + tuning.calendarEventDurationMinutes * tuning.secondsPerMinute * tuning.msPerSecond);
  const eventId = calendarEventIdFor(String(meetup._id), userId);

  const created = await calendarClient.createEvent(accessToken, {
    eventId,
    title: meetup.title,
    description: 'Planned with MeetFair',
    startTime: start.toISOString(),
    endTime: end.toISOString(),
    // Meetup times are stored as UTC instants
    timezone: 'UTC',
    location: winner ? [winner.name, winner.address].filter((part) => part !== '').join(', ') : '',
    attendeeEmails: [],
  });

  // Record the id either way: after a duplicate (e.g. our earlier write failed) the event does exist
  const recorded = await meetupService.recordCalendarEvent(meetupId, userId, created.externalId);
  if (created.duplicate || !recorded) throw new Conflict('This meetup is already in your calendar');

  return { eventId: created.externalId, htmlLink: created.htmlLink };
}

export const votingService = { castVote, lockVote, getVotes, finalize, addToCalendar };
