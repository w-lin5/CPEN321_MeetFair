import request from 'supertest';
import { createApp } from '../../src/app';
import { setRevoker } from '../../src/realtime/publisher';
import { GroupModel, MeetupModel } from '../../src/users-groups/models';
import { VoteModel, FairCreditEntryModel } from '../../src/voting-fairness/models';
import * as placesClient from '../../src/integrations/places.client';
import { placeSearchService } from '../../src/recommendation/place-search.service';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { addMember, addParticipants, createGroup, createMeetup, createUserN, createVotingMeetup, MISSING_ID, PARTICIPATION, PLACE_1 } from '../helpers/factories';

beforeAll(startDb);
afterEach(async () => { jest.restoreAllMocks(); setRevoker(null); await clearDb(); });
afterAll(stopDb);

async function setup() {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const outsider = await createUserN(3);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  return { organizer, member, outsider, group };
}

function remove(groupId: string, targetId: string, callerId?: string) {
  const req = request(createApp()).delete(`/api/groups/${groupId}/members/${targetId}`);
  return callerId ? req.set('Authorization', bearer(callerId)) : req;
}

describe('DELETE /api/groups/:groupId/members/:memberId', () => {
  test('organizer removes member, revokes access and clears participation even for settled meetups', async () => {
    const { organizer, member, group } = await setup();
    const meetup = await createMeetup(group.id, organizer.id, { status: 'SETTLED' });
    await addParticipants(meetup.id, [member.id]);
    await FairCreditEntryModel.create({
      groupId: group.id, meetupId: meetup.id, userId: member.id,
      actualSeconds: 60, normalizedBurden: 60, expectedBurden: 60, delta: 0, source: 'ESTIMATED',
    });
    const revoked: string[] = [];
    setRevoker(userId => revoked.push(userId));
    expect((await remove(group.id, member.id, organizer.id)).status).toBe(204);
    expect(revoked).toEqual([member.id]);
    expect((await GroupModel.findById(group.id).orFail()).members.map(m => String(m.userId))).toEqual([organizer.id]);
    expect((await MeetupModel.findById(meetup.id).orFail()).participants).toHaveLength(0);
    expect(await FairCreditEntryModel.countDocuments({ meetupId: meetup.id, userId: member.id })).toBe(1);
    const app = createApp();
    expect((await request(app).get(`/api/groups/${group.id}`).set('Authorization', bearer(member.id))).status).toBe(403);
    expect((await request(app).get(`/api/meetups/${meetup.id}`).set('Authorization', bearer(member.id))).status).toBe(403);
    const groups = await request(app).get('/api/groups').set('Authorization', bearer(member.id));
    expect(groups.body).toEqual([]);
  });

  test('requires organizer privileges and prevents self-removal', async () => {
    const { organizer, member, outsider, group } = await setup();
    expect((await remove(group.id, member.id)).status).toBe(401);
    expect((await remove(group.id, organizer.id, member.id)).status).toBe(403);
    expect((await remove(group.id, member.id, outsider.id)).status).toBe(403);
    expect((await remove(group.id, organizer.id, organizer.id)).status).toBe(400);
    expect((await GroupModel.findById(group.id).orFail()).members).toHaveLength(2);
  });

  test('validates identifiers and missing targets', async () => {
    const { organizer, member, group } = await setup();
    expect((await remove('invalid', member.id, organizer.id)).status).toBe(400);
    expect((await remove(group.id, 'invalid', organizer.id)).status).toBe(400);
    expect((await remove(MISSING_ID, member.id, organizer.id)).status).toBe(404);
    expect((await remove(group.id, MISSING_ID, organizer.id)).status).toBe(404);
  });

  test.each(['VOTING', 'FINALIZED'] as const)('blocks removal only when the target participates in %s', async status => {
    const { organizer, member, group } = await setup();
    const meetup = await createMeetup(group.id, organizer.id, { status });
    await addParticipants(meetup.id, [member.id]);
    expect((await remove(group.id, member.id, organizer.id)).status).toBe(409);
    expect((await GroupModel.findById(group.id).orFail()).members).toHaveLength(2);
    expect((await MeetupModel.findById(meetup.id).orFail()).participants).toHaveLength(1);
  });

  test('allows SETUP participants to be removed and clears their editable inputs', async () => {
    const { organizer, member, group } = await setup();
    const meetup = await createMeetup(group.id, organizer.id);
    await addParticipants(meetup.id, [organizer.id, member.id]);
    expect((await remove(group.id, member.id, organizer.id)).status).toBe(204);
    const updated = await MeetupModel.findById(meetup.id).orFail();
    expect(updated.status).toBe('SETUP');
    expect(updated.participants.map(p => String(p.userId))).toEqual([organizer.id]);
  });

  test('allows removal during voting even if the target did not participate', async () => {
    const { organizer, member, group } = await setup();
    await createMeetup(group.id, organizer.id, { status: 'VOTING' });
    expect((await remove(group.id, member.id, organizer.id)).status).toBe(204);
  });

  test('SETUP participant can be removed while unrelated meetups are voting', async () => {
    const { organizer, member, group } = await setup();
    const setupMeetup = await createMeetup(group.id, organizer.id);
    await addParticipants(setupMeetup.id, [organizer.id, member.id]);
    await createVotingMeetup(group.id, organizer.id, [organizer.id]);
    expect((await remove(group.id, member.id, organizer.id)).status).toBe(204);
    expect((await MeetupModel.findById(setupMeetup.id).orFail()).participants.map(p => String(p.userId)))
      .toEqual([organizer.id]);
  });

  test('one participating VOTING meetup blocks removal and preserves all submitted data', async () => {
    const { organizer, member, group } = await setup();
    const setupMeetup = await createMeetup(group.id, organizer.id);
    await addParticipants(setupMeetup.id, [member.id]);
    const voting = await createVotingMeetup(group.id, organizer.id, [member.id]);
    await VoteModel.create({ meetupId: voting.id, userId: member.id, placeId: PLACE_1, locked: true, weight: 1 });
    expect((await remove(group.id, member.id, organizer.id)).status).toBe(409);
    expect((await MeetupModel.findById(setupMeetup.id).orFail()).participants).toHaveLength(1);
    expect(await VoteModel.countDocuments({ meetupId: voting.id, userId: member.id })).toBe(1);
  });

  test('transfers an unsettled meetup to the group organizer on removal', async () => {
    const { organizer, member, group } = await setup();
    const meetup = await createMeetup(group.id, member.id);
    expect((await remove(group.id, member.id, organizer.id)).status).toBe(204);
    expect(String((await MeetupModel.findById(meetup.id).orFail()).organizerId)).toBe(organizer.id);
  });

  test('removed member cannot submit participation or start new meetups', async () => {
    const { organizer, member, group } = await setup();
    const meetup = await createMeetup(group.id, organizer.id);
    expect((await remove(group.id, member.id, organizer.id)).status).toBe(204);
    expect((await request(createApp()).put(`/api/meetups/${meetup.id}/participation`)
      .set('Authorization', bearer(member.id)).send(PARTICIPATION)).status).toBe(403);
    expect((await request(createApp()).post(`/api/groups/${group.id}/meetups`)
      .set('Authorization', bearer(member.id)).send({ title: 'Test', scheduledAt: '2027-01-01T12:00:00Z' })).status).toBe(403);
    expect((await remove(group.id, member.id, organizer.id)).status).toBe(404);
  });

  test('removal after settlement cleans old votes and keeps other groups intact', async () => {
    const { organizer, member, group } = await setup();
    const meetup = await createVotingMeetup(group.id, organizer.id, [organizer.id, member.id]);
    await VoteModel.create({ meetupId: meetup.id, userId: member.id, placeId: PLACE_1, locked: true, weight: 1.5 });
    await MeetupModel.updateOne({ _id: meetup.id }, { $set: { status: 'SETTLED' } });
    const otherGroup = await createGroup(member.id, { inviteCode: 'XYZ234' });
    const otherMeetup = await createVotingMeetup(otherGroup.id, member.id, [member.id]);
    await VoteModel.create({ meetupId: otherMeetup.id, userId: member.id, placeId: PLACE_1 });
    expect((await remove(group.id, member.id, organizer.id)).status).toBe(204);
    expect(await VoteModel.countDocuments({ meetupId: meetup.id, userId: member.id })).toBe(0);
    expect(await VoteModel.countDocuments({ meetupId: otherMeetup.id, userId: member.id })).toBe(1);
    expect((await MeetupModel.findById(otherMeetup.id).orFail()).participants).toHaveLength(1);
    const votes = await request(createApp()).get(`/api/meetups/${meetup.id}/votes`).set('Authorization', bearer(organizer.id));
    expect(votes.status).toBe(200);
    expect(votes.body).toMatchObject({ participantCount: 1, lockedCount: 0, totalWeight: 0 });
  });

  test('subsequent searches use only remaining members and rejoining does not restore old inputs', async () => {
    const { organizer, member, group } = await setup();
    const meetup = await createMeetup(group.id, organizer.id);
    await MeetupModel.updateOne({ _id: meetup.id }, { $set: { participants: [
      { userId: organizer.id, ...PARTICIPATION, startLocation: { lat: 40, lng: -74 }, preferences: ['FOOD'] },
      { userId: member.id, ...PARTICIPATION, startLocation: { lat: 50, lng: -120 } },
    ] } });
    const google = jest.spyOn(placesClient, 'searchNearby').mockResolvedValue([]);
    expect((await remove(group.id, member.id, organizer.id)).status).toBe(204);
    await placeSearchService.search(meetup.id, organizer.id);
    expect(google).toHaveBeenCalledWith({ lat: 40, lng: -74 }, ['restaurant']);
    await request(createApp()).post('/api/groups/join').set('Authorization', bearer(member.id)).send({ inviteCode: group.inviteCode });
    expect((await MeetupModel.findById(meetup.id).orFail()).participants.map(p => String(p.userId))).toEqual([organizer.id]);
  });
});
