import request from 'supertest';
import { createApp } from '../../src/app';
import { TripModel } from '../../src/live-location/models';
import { RecommendationModel } from '../../src/recommendation/models';
import { setTransport } from '../../src/realtime/publisher';
import { MeetupModel } from '../../src/users-groups/models';
import { FairCreditEntryModel, FairCreditModel, SettlementModel } from '../../src/voting-fairness/models';
import { fairCreditService } from '../../src/voting-fairness/fair-credit.service';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { addMember, CANDIDATES, createFinalizedMeetup, createGroup, createUserN } from '../helpers/factories';

const app = createApp();
beforeAll(startDb);
beforeEach(() => { jest.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('Unexpected external HTTP request')); });
afterEach(async () => { jest.restoreAllMocks(); setTransport(null); await clearDb(); });
afterAll(stopDb);

async function setup() {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  const meetup = await createFinalizedMeetup(group.id, organizer.id, [organizer.id, member.id]);
  await MeetupModel.updateOne({ _id: meetup.id }, { $set: { scheduledAt: new Date(Date.now() - 60_000) } });
  const trips = [organizer, member].map((user, i) => ({ userId: user.id, mode: 'TRANSIT' as const,
    actualSeconds: i === 0 ? 1800 : 600, source: 'ESTIMATED' as const }));
  await RecommendationModel.create({ meetupId: meetup.id, candidates: [{ ...CANDIDATES[0], travel: trips.map(trip => ({
    userId: trip.userId, mode: trip.mode, seconds: trip.actualSeconds, normalizedSeconds: trip.actualSeconds,
  })) }] });
  const events: unknown[] = [];
  setTransport((room, event, payload) => events.push({ room, event, payload }));
  return { organizer, member, group, meetup, trips, events,
    settle: () => request(app).post(`/api/meetups/${meetup.id}/settle`).set('Authorization', bearer(organizer.id)),
    assertNoCredits: async () => {
      expect(await FairCreditModel.countDocuments()).toBe(0);
      expect(await FairCreditEntryModel.countDocuments()).toBe(0);
      expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('FINALIZED');
      expect(events).toEqual([]);
    },
  };
}

describe('Settlement preconditions and legacy data', () => {
  test.each([
    ['missing winner', { $unset: { winner: 1 } }, 409, 'The meetup must have a finalized destination'],
    ['no participants', { $set: { participants: [] } }, 400, 'There are no participants to settle'],
    ['missing destination', { $unset: { 'winner.location': 1 } }, 400, 'The destination has no valid location'],
    ['null destination', { $set: { 'winner.location': null } }, 400, 'The destination has no valid location'],
    ['missing latitude', { $unset: { 'winner.location.lat': 1 } }, 400, 'The destination has no valid location'],
    ['missing longitude', { $unset: { 'winner.location.lng': 1 } }, 400, 'The destination has no valid location'],
  ] as const)('rejects %s without freezing inputs or applying balances', async (_label, update, status, message) => {
    const fixture = await setup();
    // Raw records represent legacy/corrupted data, not a mocked result from the credit algorithm.
    await MeetupModel.collection.updateOne({ _id: fixture.meetup._id }, update);
    await RecommendationModel.deleteMany({ meetupId: fixture.meetup.id });
    const result = await fixture.settle();
    expect({ status: result.status, body: result.body }).toEqual({ status, body: { error: message } });
    expect(await SettlementModel.countDocuments()).toBe(0);
    await fixture.assertNoCredits();
    expect(globalThis.fetch).not.toHaveBeenCalled();
  });

  test.each([-1, Infinity])('rejects a corrupted saved estimate (%s) before writing a snapshot', async seconds => {
    const fixture = await setup();
    await RecommendationModel.collection.updateOne({ meetupId: fixture.meetup._id }, { $set: { 'candidates.0.travel.0.seconds': seconds } });
    const result = await fixture.settle();
    expect(result.status).toBe(400);
    expect(result.body).toEqual({ error: 'A commute duration is invalid or exceeds one day' });
    expect(await SettlementModel.countDocuments()).toBe(0);
    await fixture.assertNoCredits();
  });
});

describe('Settlement snapshot failure and recovery', () => {
  test.each([new Error('storage unavailable'), null, 'connection lost', {}, { code: 123 }])('propagates a non-duplicate snapshot failure safely: %p', async failure => {
    const fixture = await setup();
    jest.spyOn(console, 'error').mockImplementation(() => undefined);
    jest.spyOn(SettlementModel, 'findOneAndUpdate').mockRejectedValueOnce(failure);
    const result = await fixture.settle();
    expect(result.status).toBe(500);
    expect(result.body).toEqual({ error: 'Internal Server Error' });
    expect(await SettlementModel.countDocuments()).toBe(0);
    await fixture.assertNoCredits();
  });

  test('recovers a duplicate snapshot from the database and applies each credit exactly once', async () => {
    const fixture = await setup();
    const original = SettlementModel.findOneAndUpdate.bind(SettlementModel);
    jest.spyOn(SettlementModel, 'findOneAndUpdate').mockImplementationOnce((...args: Parameters<typeof original>) => {
      const query = original(...args);
      const execute = query.exec.bind(query);
      jest.spyOn(query, 'exec').mockImplementationOnce(async () => {
        await execute(); // Another writer's committed snapshot is now visible.
        throw Object.assign(new Error('duplicate snapshot'), { code: 11000 });
      });
      return query;
    });
    expect((await fixture.settle()).status).toBe(200);
    expect(await SettlementModel.countDocuments()).toBe(1);
    expect(await FairCreditEntryModel.countDocuments()).toBe(2);
    expect(await fairCreditService.getBalance(fixture.organizer.id, fixture.group.id)).toBe(600);
    expect(await fairCreditService.getBalance(fixture.member.id, fixture.group.id)).toBe(-600);
    expect((await fixture.settle()).status).toBe(200);
    expect(await FairCreditEntryModel.countDocuments()).toBe(2);
    expect(await fairCreditService.getBalance(fixture.organizer.id, fixture.group.id)).toBe(600);
  });

  test('asks for retry if a competing snapshot is no longer visible, without changing balances', async () => {
    const fixture = await setup();
    jest.spyOn(SettlementModel, 'findOneAndUpdate').mockRejectedValueOnce(Object.assign(new Error('duplicate then deleted'), { code: 11000 }));
    const result = await fixture.settle();
    expect(result.status).toBe(409);
    expect(result.body).toEqual({ error: 'Please retry settlement' });
    expect(await SettlementModel.countDocuments()).toBe(0);
    await fixture.assertNoCredits();
    expect((await fixture.settle()).status).toBe(200);
    expect(await FairCreditEntryModel.countDocuments()).toBe(2);
  });

  test('an active trip in an existing snapshot cannot be settled; leaving permits a safe retry', async () => {
    const fixture = await setup();
    const trip = await TripModel.create({ meetupId: fixture.meetup.id, userId: fixture.member.id,
      travelMode: 'TRANSIT', status: 'ACTIVE', startedAt: new Date(), sharing: false });
    const snapshot = await SettlementModel.create({ meetupId: fixture.meetup.id, groupId: fixture.group.id, trips: fixture.trips });
    const result = await fixture.settle();
    expect(result.status).toBe(409);
    expect(result.body).toEqual({ error: 'Wait for all started trips to arrive before settling Fair Credit' });
    await fixture.assertNoCredits();
    expect((await TripModel.findById(trip.id).orFail()).status).toBe('ACTIVE');
    expect((await request(app).post(`/api/groups/${fixture.group.id}/leave`).set('Authorization', bearer(fixture.member.id))).status).toBe(204);
    expect((await fixture.settle()).status).toBe(200);
    expect((await SettlementModel.findById(snapshot.id).orFail()).trips.map(row => row.actualSeconds)).toEqual([1800, 600]);
    expect(await FairCreditEntryModel.countDocuments()).toBe(2);
  });

  test('a concurrent completed status transition returns the stored settled meetup without duplicate accounting', async () => {
    const fixture = await setup();
    const original = MeetupModel.findOneAndUpdate.bind(MeetupModel);
    jest.spyOn(MeetupModel, 'findOneAndUpdate').mockImplementationOnce((...args: Parameters<typeof original>) =>
      (async () => {
        await MeetupModel.updateOne({ _id: fixture.meetup.id }, { $set: { status: 'SETTLED' } });
        return original(...args);
      })() as ReturnType<typeof original>);
    const result = await fixture.settle();
    expect(result.status).toBe(200);
    expect(result.body.status).toBe('SETTLED');
    expect(await FairCreditEntryModel.countDocuments()).toBe(2);
    expect(await fairCreditService.getBalance(fixture.organizer.id, fixture.group.id)).toBe(600);
  });
});
