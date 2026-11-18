import request, { type Test } from 'supertest';

import { createApp } from '../../src/app';
import { tuning } from '../../src/config';
import { MeetupModel } from '../../src/users-groups/models';
import { fairCreditService } from '../../src/voting-fairness/fair-credit.service';
import { FairCreditEntryModel, FairCreditModel, SettlementModel, TripModel } from '../../src/voting-fairness/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { addMember, createGroup, createSettleableMeetup, createUserN } from '../helpers/factories';

beforeAll(startDb);
afterEach(clearDb);
afterAll(stopDb);

const MINUTE_MS = tuning.secondsPerMinute * tuning.msPerSecond;

// A settleable meetup whose stored estimates are 30 minutes for the organizer and 10 for the member
async function setup() {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const outsider = await createUserN(3);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  const meetup = await createSettleableMeetup(group.id, organizer.id, [[organizer.id, 1800], [member.id, 600]]);
  return { organizer, member, outsider, group, meetup };
}

function settle(meetupId: string, userId?: string): Test {
  const req = request(createApp()).post(`/api/meetups/${meetupId}/settle`);
  return userId ? req.set('Authorization', bearer(userId)) : req;
}

async function createTrip(meetupId: string, userId: string, fields: Record<string, unknown>) {
  await TripModel.create({ meetupId, userId, travelMode: 'TRANSIT', startedAt: new Date(), ...fields });
}

// Interface POST /api/meetups/:meetupId/settle
describe('Unmocked: POST /api/meetups/:meetupId/settle', () => {
  // Input: organizer settles after completing a 130-minute tracked trip; the member never started one (10-minute estimate);
  //   the organizer then settles again and the member tries to start a trip
  // Expected status code: 200; 200; 409 for the late trip start
  // Expected behavior: the organizer gains 60 minutes of credit and the member loses 60 (average 70 minutes); the ledger
  //   records one TRACKED and one ESTIMATED entry; the repeat changes nothing; the organizer's vote weight becomes 1.2
  // Expected output: the meetup with status SETTLED
  test('settles tracked and estimated commutes, updates history and next vote weights, and marks SETTLED', async () => {
    const { organizer, member, group, meetup } = await setup();
    await createTrip(meetup.id, organizer.id, { arrivedAt: new Date(), status: 'ARRIVED', actualSeconds: 7800 });

    const result = await settle(meetup.id, organizer.id);

    expect(result.status).toBe(200);
    expect(result.body.status).toBe('SETTLED');
    expect(await fairCreditService.getBalance(organizer.id, group.id)).toBe(3600);
    expect(await fairCreditService.getBalance(member.id, group.id)).toBe(-3600);
    expect(await fairCreditService.getVoteWeight(organizer.id, group.id)).toBeCloseTo(1.2);
    const entries = await FairCreditEntryModel.find({ meetupId: meetup.id }).lean();
    expect(entries).toEqual(expect.arrayContaining([
      expect.objectContaining({ source: 'TRACKED', actualSeconds: 7800, delta: 3600 }),
      expect.objectContaining({ source: 'ESTIMATED', actualSeconds: 600, delta: -3600 }),
    ]));

    expect((await settle(meetup.id, organizer.id)).status).toBe(200);
    expect(await FairCreditEntryModel.countDocuments({ meetupId: meetup.id })).toBe(2);
    expect(await fairCreditService.getBalance(organizer.id, group.id)).toBe(3600);
    const lateStart = await request(createApp())
      .post(`/api/meetups/${meetup.id}/trip/start`)
      .set('Authorization', bearer(member.id));
    expect(lateStart.status).toBe(409);
  });

  // Input: no session token; a member who is not the organizer; a user outside the group
  // Expected status code: 401; 403; 403
  // Expected behavior: no Fair Credit is written and the meetup stays FINALIZED
  // Expected output: None
  test('requires authentication, group membership and the organizer role', async () => {
    const { meetup, member, outsider } = await setup();

    expect((await settle(meetup.id)).status).toBe(401);
    expect((await settle(meetup.id, member.id)).status).toBe(403);
    expect((await settle(meetup.id, outsider.id)).status).toBe(403);

    expect(await FairCreditModel.countDocuments()).toBe(0);
    expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('FINALIZED');
  });

  // Input: organizer settles a meetup whose time is still ahead; one that is still in VOTING; one where a member's trip
  //   is active with sharing paused
  // Expected status code: 409 each time
  // Expected behavior: no settlement snapshot and no Fair Credit is written
  // Expected output: None
  test('rejects future and unfinalized meetups, and active trips including paused sharing', async () => {
    const { organizer, meetup, member } = await setup();

    await MeetupModel.updateOne({ _id: meetup.id }, { $set: { scheduledAt: new Date(Date.now() + MINUTE_MS) } });
    expect((await settle(meetup.id, organizer.id)).status).toBe(409);
    await MeetupModel.updateOne(
      { _id: meetup.id },
      { $set: { status: 'VOTING', scheduledAt: new Date(Date.now() - MINUTE_MS) } },
    );
    expect((await settle(meetup.id, organizer.id)).status).toBe(409);
    await MeetupModel.updateOne({ _id: meetup.id }, { $set: { status: 'FINALIZED' } });
    await createTrip(meetup.id, member.id, { status: 'ACTIVE', sharing: false });
    expect((await settle(meetup.id, organizer.id)).status).toBe(409);

    expect(await SettlementModel.countDocuments()).toBe(0);
    expect(await FairCreditModel.countDocuments()).toBe(0);
  });

  // Input: organizer settles a finalized meetup that nobody entered details for
  // Expected status code: 400
  // Expected behavior: nothing is settled
  // Expected output: None
  test('rejects a meetup without participants', async () => {
    const { organizer, meetup } = await setup();
    await MeetupModel.updateOne({ _id: meetup.id }, { $set: { participants: [] } });

    expect((await settle(meetup.id, organizer.id)).status).toBe(400);
    expect(await SettlementModel.countDocuments()).toBe(0);
  });

  // Input: three settlement requests from the organizer at the same time (stored estimates of 30 and 10 minutes)
  // Expected status code: 200 for each
  // Expected behavior: the meetup is settled once: two ledger entries, and the organizer's balance is +10 minutes
  // Expected output: None
  test('concurrent settlement requests apply each balance change once', async () => {
    const { organizer, group, meetup } = await setup();

    const results = await Promise.all([1, 2, 3].map(() => settle(meetup.id, organizer.id)));

    expect(results.map((result) => result.status)).toEqual([200, 200, 200]);
    expect(await FairCreditEntryModel.countDocuments()).toBe(2);
    expect(await fairCreditService.getBalance(organizer.id, group.id)).toBe(600);
  });

  // Input: the organizer's tracked trip took longer than a day (they tapped "I arrived" far too late); organizer settles
  // Expected status code: 200
  // Expected behavior: the unusable measurement is replaced by the stored 30-minute estimate, so the meetup can be settled
  // Expected output: the meetup with status SETTLED; both ledger entries are ESTIMATED
  test('a tracked commute longer than a day falls back to the estimate', async () => {
    const { organizer, group, meetup } = await setup();
    await createTrip(meetup.id, organizer.id, {
      arrivedAt: new Date(),
      status: 'ARRIVED',
      actualSeconds: tuning.maxTripSeconds + 1,
    });

    const result = await settle(meetup.id, organizer.id);

    expect(result.status).toBe(200);
    expect(result.body.status).toBe('SETTLED');
    expect(await FairCreditEntryModel.countDocuments({ source: 'ESTIMATED' })).toBe(2);
    expect(await fairCreditService.getBalance(organizer.id, group.id)).toBe(600);
  });

  // Input: a participant's stored entry has no start location (written around the API); organizer settles
  // Expected status code: 409
  // Expected behavior: nothing is settled and the meetup stays FINALIZED
  // Expected output: { error: "A member has an invalid starting location. Please submit it again." }
  test('rejects a participant without a start location', async () => {
    const { organizer, member, meetup } = await setup();
    await MeetupModel.collection.updateOne(
      { _id: meetup._id, 'participants.userId': member._id },
      { $unset: { 'participants.$.startLocation': '' } },
    );

    const result = await settle(meetup.id, organizer.id);

    expect(result.status).toBe(409);
    expect(result.body).toEqual({ error: 'A member has an invalid starting location. Please submit it again.' });
    expect(await SettlementModel.countDocuments()).toBe(0);
    expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('FINALIZED');
  });
});
