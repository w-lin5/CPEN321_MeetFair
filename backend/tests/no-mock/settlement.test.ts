import request from 'supertest';
import { createApp } from '../../src/app';
import { TripModel } from '../../src/live-location/models';
import { RecommendationModel } from '../../src/recommendation/models';
import { GroupModel, MeetupModel } from '../../src/users-groups/models';
import { FairCreditEntryModel, FairCreditModel, SettlementModel } from '../../src/voting-fairness/models';
import { fairCreditService } from '../../src/voting-fairness/fair-credit.service';
import * as routes from '../../src/integrations/routes.client';
import { bearer } from '../helpers/auth';
import { expectApiError } from '../helpers/http';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { addMember, CANDIDATES, createFinalizedMeetup, createGroup, createUserN } from '../helpers/factories';

const app = createApp();
beforeAll(startDb);
afterEach(clearDb);
afterAll(stopDb);

async function setup() {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const outsider = await createUserN(3);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  const meetup = await createFinalizedMeetup(group.id, organizer.id, [organizer.id, member.id]);
  await MeetupModel.updateOne({ _id: meetup.id }, { $set: { scheduledAt: new Date(Date.now() - 60000) } });
  await RecommendationModel.create({ meetupId: meetup.id, candidates: [{ ...CANDIDATES[0], travel: [
    { userId: organizer.id, mode: 'TRANSIT', seconds: 1800, normalizedSeconds: 1800 },
    { userId: member.id, mode: 'TRANSIT', seconds: 600, normalizedSeconds: 600 },
  ] }] });
  const settle = (id = organizer.id) => request(app).post(`/api/meetups/${meetup.id}/settle`).set('Authorization', bearer(id));
  return { organizer, member, outsider, group, meetup, settle };
}

test('settles tracked and estimated commutes, updates history and next vote weights, and marks SETTLED', async () => {
  const { organizer, member, group, meetup, settle } = await setup();
  await TripModel.create({ meetupId: meetup.id, userId: organizer.id, travelMode: 'TRANSIT',
    startedAt: new Date(Date.now() - 7800000), arrivedAt: new Date(), status: 'ARRIVED', actualSeconds: 7800 });
  const result = await settle();
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
  expect((await settle()).status).toBe(200);
  expect(await FairCreditEntryModel.countDocuments({ meetupId: meetup.id })).toBe(2);
  expect(await fairCreditService.getBalance(organizer.id, group.id)).toBe(3600);
  expectApiError(await request(app).post(`/api/meetups/${meetup.id}/trip/start`).set('Authorization', bearer(member.id)), 409, 'The meetup must have a finalized destination');
});

test('requires authentication, group membership and organizer role', async () => {
  const { meetup, member, outsider, settle } = await setup();
  const before = await MeetupModel.findById(meetup.id).lean().orFail();
  expectApiError(await request(app).post(`/api/meetups/${meetup.id}/settle`), 401, 'Missing bearer token');
  expectApiError(await settle(member.id), 403, 'Only the meetup organizer can settle Fair Credit');
  expectApiError(await settle(outsider.id), 403, 'Not a member of this group');
  expect(await MeetupModel.findById(meetup.id).lean()).toEqual(before);
  expect(await SettlementModel.countDocuments({ meetupId: meetup.id })).toBe(0);
  expect(await FairCreditEntryModel.countDocuments({ meetupId: meetup.id })).toBe(0);
  expect(await FairCreditModel.countDocuments()).toBe(0);
});

test('rejects future and unfinalized meetups, and active trips including paused sharing', async () => {
  const { meetup, member, settle } = await setup();
  await MeetupModel.updateOne({ _id: meetup.id }, { $set: { scheduledAt: new Date(Date.now() + 60000) } });
  expectApiError(await settle(), 409, 'Fair Credit can be settled after the meetup time arrives');
  await MeetupModel.updateOne({ _id: meetup.id }, { $set: { status: 'VOTING', scheduledAt: new Date(Date.now() - 60000) } });
  expectApiError(await settle(), 409, 'The meetup must have a finalized destination');
  await MeetupModel.updateOne({ _id: meetup.id }, { $set: { status: 'FINALIZED' } });
  await TripModel.create({ meetupId: meetup.id, userId: member.id, travelMode: 'TRANSIT',
    startedAt: new Date(), status: 'ACTIVE', sharing: false });
  expectApiError(await settle(), 409, 'Wait for all started trips to arrive before settling Fair Credit');
  expect(await SettlementModel.countDocuments()).toBe(0);
  expect(await FairCreditModel.countDocuments()).toBe(0);
});

test('concurrent settlement requests apply each balance change once', async () => {
  const { organizer, group, settle } = await setup();
  const results = await Promise.all([settle(), settle(), settle()]);
  expect(results.map(result => result.status)).toEqual([200, 200, 200]);
  expect(await FairCreditEntryModel.countDocuments()).toBe(2);
  expect(await fairCreditService.getBalance(organizer.id, group.id)).toBe(600);
});

test.each([true, false])('leaving with an active trip (sharing=%s) permits settlement using an estimate, not a false arrival', async sharing => {
  const { organizer, member, group, meetup, settle } = await setup();
  const arrivedAt = new Date();
  await TripModel.create({ meetupId: meetup.id, userId: organizer.id, travelMode: 'TRANSIT',
    startedAt: new Date(Date.now() - 1800000), arrivedAt, status: 'ARRIVED', sharing: false, actualSeconds: 1800 });
  const unfinished = await TripModel.create({ meetupId: meetup.id, userId: member.id, travelMode: 'TRANSIT',
    startedAt: new Date(Date.now() - 300000), status: 'ACTIVE', sharing,
    location: sharing ? { lat: 49.26, lng: -123.25, accuracyM: 8, updatedAt: new Date() } : null });
  const blocked = await settle();
  expect(blocked.status).toBe(409);
  expect(blocked.body.error).toBe('Wait for all started trips to arrive before settling Fair Credit');
  expect((await request(app).post(`/api/groups/${group.id}/leave`).set('Authorization', bearer(member.id))).status).toBe(204);
  const cancelled = await TripModel.findById(unfinished.id).orFail();
  expect(cancelled.toObject()).toMatchObject({ status: 'CANCELLED', sharing: false, location: null,
    arrivedAt: null, actualSeconds: null, startedAt: unfinished.startedAt });
  expect(cancelled.cancelledAt).toBeInstanceOf(Date);
  expect(await TripModel.findOne({ meetupId: meetup.id, userId: organizer.id }).lean())
    .toMatchObject({ status: 'ARRIVED', arrivedAt, actualSeconds: 1800 });
  expectApiError(await request(app).post(`/api/meetups/${meetup.id}/trip/arrive`).set('Authorization', bearer(member.id)), 403, 'Not a member of this group');
  expect((await settle()).status).toBe(200);
  expect(await FairCreditEntryModel.findOne({ meetupId: meetup.id, userId: member.id }).lean())
    .toMatchObject({ source: 'ESTIMATED', actualSeconds: 600 });
  expect(await FairCreditEntryModel.findOne({ meetupId: meetup.id, userId: organizer.id }).lean())
    .toMatchObject({ source: 'TRACKED', actualSeconds: 1800 });
  expect((await settle()).status).toBe(200);
  expect(await FairCreditEntryModel.countDocuments({ meetupId: meetup.id })).toBe(2);
});

test('settlement repairs legacy active trips of departed members but still waits for current members', async () => {
  const { organizer, member, group, meetup, settle } = await setup();
  await TripModel.create({ meetupId: meetup.id, userId: member.id, travelMode: 'TRANSIT',
    startedAt: new Date(), status: 'ACTIVE', sharing: false });
  // Simulate the old leave behavior: membership gone, paused ACTIVE trip retained.
  await GroupModel.updateOne({ _id: group.id }, { $pull: { members: { userId: member.id } } });
  await TripModel.create({ meetupId: meetup.id, userId: organizer.id, travelMode: 'TRANSIT',
    startedAt: new Date(), status: 'ACTIVE', sharing: false });
  expectApiError(await settle(), 409, 'Wait for all started trips to arrive before settling Fair Credit');
  expect(await TripModel.findOne({ meetupId: meetup.id, userId: member.id }).lean())
    .toMatchObject({ status: 'CANCELLED', sharing: false, location: null, actualSeconds: null });
  expect(await SettlementModel.countDocuments()).toBe(0);
  expect((await request(app).post(`/api/meetups/${meetup.id}/trip/arrive`).set('Authorization', bearer(organizer.id))).status).toBe(200);
  expect((await settle()).status).toBe(200);
});

test('rejects invalid commute durations without freezing the meetup', async () => {
  const { organizer, meetup, settle } = await setup();
  await TripModel.create({ meetupId: meetup.id, userId: organizer.id, travelMode: 'TRANSIT',
    startedAt: new Date(), status: 'ARRIVED', actualSeconds: 86401 });
  expectApiError(await settle(), 400, 'A commute duration is invalid or exceeds one day');
  expect(await SettlementModel.countDocuments()).toBe(0);
  expect(await FairCreditModel.countDocuments()).toBe(0);
});

test('retries after a failed final state update without applying credits again', async () => {
  const { organizer, group, settle } = await setup();
  const update = jest.spyOn(MeetupModel, 'findOneAndUpdate').mockRejectedValueOnce(new Error('temporary database failure'));
  try {
    expectApiError(await settle(), 500, 'Internal Server Error');
  } finally {
    update.mockRestore();
  }
  expect(await fairCreditService.getBalance(organizer.id, group.id)).toBe(600);
  expect((await settle()).status).toBe(200);
  expect(await fairCreditService.getBalance(organizer.id, group.id)).toBe(600);
  expect(await FairCreditEntryModel.countDocuments()).toBe(2);
});

test('resumes a partial balance update without double counting the first member', async () => {
  const { organizer, member, group, settle } = await setup();
  const original = FairCreditModel.updateOne.bind(FairCreditModel);
  let calls = 0;
  const update = jest.spyOn(FairCreditModel, 'updateOne').mockImplementation((...args: Parameters<typeof original>) => {
    calls += 1;
    if (calls === 3) throw new Error('temporary failure before second member');
    return original(...args);
  });
  try {
    expectApiError(await settle(), 500, 'Internal Server Error');
  } finally {
    update.mockRestore();
  }
  expect(await fairCreditService.getBalance(organizer.id, group.id)).toBe(600);
  expect(await fairCreditService.getBalance(member.id, group.id)).toBe(0);
  expect((await settle()).status).toBe(200);
  expect(await fairCreditService.getBalance(organizer.id, group.id)).toBe(600);
  expect(await fairCreditService.getBalance(member.id, group.id)).toBe(-600);
});

test('older recommendations fetch missing estimates and mark them as estimated', async () => {
  const { organizer, meetup, group, settle } = await setup();
  await RecommendationModel.deleteMany({ meetupId: meetup.id });
  const estimate = jest.spyOn(routes, 'travelSeconds').mockResolvedValueOnce([[2400], [1200]]);
  try {
    expect((await settle()).status).toBe(200);
    expect(estimate).toHaveBeenCalledTimes(1);
    expect(await fairCreditService.getBalance(organizer.id, group.id)).toBe(600);
    expect(await FairCreditEntryModel.countDocuments({ source: 'ESTIMATED' })).toBe(2);
  } finally {
    estimate.mockRestore();
  }
});

test('unreachable fallback leaves the meetup finalized with no credit changes', async () => {
  const { meetup, settle } = await setup();
  await RecommendationModel.deleteMany({ meetupId: meetup.id });
  const estimate = jest.spyOn(routes, 'travelSeconds').mockResolvedValueOnce([[null], [1200]]);
  try {
    expectApiError(await settle(), 409, 'A participant has no usable travel estimate; ask them to complete a tracked trip');
    expect((await MeetupModel.findById(meetup.id))?.status).toBe('FINALIZED');
    expect(await FairCreditModel.countDocuments()).toBe(0);
    expect(await SettlementModel.countDocuments()).toBe(0);
  } finally {
    estimate.mockRestore();
  }
});
