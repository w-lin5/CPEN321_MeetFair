import request from 'supertest';
import { createApp } from '../../src/app';
import { TripModel } from '../../src/live-location/models';
import { RecommendationModel } from '../../src/recommendation/models';
import { MeetupModel } from '../../src/users-groups/models';
import { FairCreditEntryModel, FairCreditModel, SettlementModel } from '../../src/voting-fairness/models';
import { fairCreditService } from '../../src/voting-fairness/fair-credit.service';
import * as routes from '../../src/integrations/routes.client';
import { bearer } from '../helpers/auth';
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
  expect((await request(app).post(`/api/meetups/${meetup.id}/trip/start`).set('Authorization', bearer(member.id))).status).toBe(409);
});

test('requires authentication, group membership and organizer role', async () => {
  const { meetup, member, outsider, settle } = await setup();
  expect((await request(app).post(`/api/meetups/${meetup.id}/settle`)).status).toBe(401);
  expect((await settle(member.id)).status).toBe(403);
  expect((await settle(outsider.id)).status).toBe(403);
  expect(await FairCreditModel.countDocuments()).toBe(0);
});

test('rejects future and unfinalized meetups, and active trips including paused sharing', async () => {
  const { meetup, member, settle } = await setup();
  await MeetupModel.updateOne({ _id: meetup.id }, { $set: { scheduledAt: new Date(Date.now() + 60000) } });
  expect((await settle()).status).toBe(409);
  await MeetupModel.updateOne({ _id: meetup.id }, { $set: { status: 'VOTING', scheduledAt: new Date(Date.now() - 60000) } });
  expect((await settle()).status).toBe(409);
  await MeetupModel.updateOne({ _id: meetup.id }, { $set: { status: 'FINALIZED' } });
  await TripModel.create({ meetupId: meetup.id, userId: member.id, travelMode: 'TRANSIT',
    startedAt: new Date(), status: 'ACTIVE', sharing: false });
  expect((await settle()).status).toBe(409);
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

test('rejects invalid commute durations without freezing the meetup', async () => {
  const { organizer, meetup, settle } = await setup();
  await TripModel.create({ meetupId: meetup.id, userId: organizer.id, travelMode: 'TRANSIT',
    startedAt: new Date(), status: 'ARRIVED', actualSeconds: 86401 });
  expect((await settle()).status).toBe(400);
  expect(await SettlementModel.countDocuments()).toBe(0);
  expect(await FairCreditModel.countDocuments()).toBe(0);
});

test('retries after a failed final state update without applying credits again', async () => {
  const { organizer, group, settle } = await setup();
  const update = jest.spyOn(MeetupModel, 'findOneAndUpdate').mockRejectedValueOnce(new Error('temporary database failure'));
  try {
    expect((await settle()).status).toBe(500);
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
    expect((await settle()).status).toBe(500);
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
    expect((await settle()).status).toBe(409);
    expect((await MeetupModel.findById(meetup.id))?.status).toBe('FINALIZED');
    expect(await FairCreditModel.countDocuments()).toBe(0);
    expect(await SettlementModel.countDocuments()).toBe(0);
  } finally {
    estimate.mockRestore();
  }
});
