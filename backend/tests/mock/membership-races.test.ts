import request from 'supertest';
import { createApp } from '../../src/app';
import { setTransport } from '../../src/realtime/publisher';
import { GroupModel, MeetupModel } from '../../src/users-groups/models';
import { deletionService } from '../../src/users-groups/deletion.service';
import { RecommendationModel } from '../../src/recommendation/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { addMember, addParticipants, CANDIDATES, createGroup, createMeetup, createUserN } from '../helpers/factories';

const app = createApp();
beforeAll(startDb);
afterEach(async () => { jest.restoreAllMocks(); setTransport(null); await clearDb(); });
afterAll(stopDb);
async function setup() {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  const meetup = await createMeetup(group.id, organizer.id);
  await addParticipants(meetup.id, [organizer.id, member.id]);
  const events: unknown[] = [];
  setTransport((room, event, payload) => events.push({ room, event, payload }));
  return { organizer, member, group, meetup, events };
}

test.each(['remove', 'leave'] as const)('a competing membership removal prevents %s from cleaning someone else\'s state', async action => {
  const { organizer, member, group, meetup, events } = await setup();
  const before = await MeetupModel.findById(meetup.id).lean();
  const original = GroupModel.updateOne.bind(GroupModel);
  jest.spyOn(GroupModel, 'updateOne').mockImplementationOnce((...args: Parameters<typeof original>) =>
    (async () => {
      // Membership changes after authorization but before the conditional write.
      await original({ _id: group.id }, { $pull: { members: { userId: member.id } } });
      return original(...args);
    })() as ReturnType<typeof original>);
  const result = action === 'leave'
    ? await request(app).post(`/api/groups/${group.id}/leave`).set('Authorization', bearer(member.id))
    : await request(app).delete(`/api/groups/${group.id}/members/${member.id}`).set('Authorization', bearer(organizer.id));
  expect(result.status).toBe(409);
  expect(result.body).toEqual({ error: 'Group membership changed. Please refresh and try again.' });
  expect(await MeetupModel.findById(meetup.id).lean()).toEqual(before);
  expect(events).toEqual([]);
});

test('a vote starting between the leave check and write preserves participation', async () => {
  const { member, meetup, events } = await setup();
  const original = MeetupModel.updateOne.bind(MeetupModel);
  jest.spyOn(MeetupModel, 'updateOne').mockImplementationOnce((...args: Parameters<typeof original>) =>
    (async () => {
      await MeetupModel.collection.updateOne({ _id: meetup._id }, { $set: { status: 'VOTING' } });
      return original(...args);
    })() as ReturnType<typeof original>);
  const result = await request(app).delete(`/api/meetups/${meetup.id}/participation`).set('Authorization', bearer(member.id));
  expect(result.status).toBe(409);
  expect(result.body).toEqual({ error: 'Voting has started. You can no longer leave this meetup.' });
  const stored = await MeetupModel.findById(meetup.id).orFail();
  expect(stored.status).toBe('VOTING');
  expect(stored.participants.map(row => String(row.userId))).toContain(member.id);
  expect(events).toEqual([]);
});

test.each(['meetup', 'group'] as const)('two interleaved %s deletions cascade and notify only once', async kind => {
  const { organizer, group, meetup, events } = await setup();
  await RecommendationModel.create({ meetupId: meetup.id, candidates: CANDIDATES });
  if (kind === 'meetup') {
    const original = MeetupModel.deleteOne.bind(MeetupModel);
    jest.spyOn(MeetupModel, 'deleteOne').mockImplementationOnce((...args: Parameters<typeof original>) =>
      (async () => {
        await deletionService.deleteMeetup(meetup.id, organizer.id); // The competing request wins first.
        return original(...args);
      })() as ReturnType<typeof original>);
  } else {
    const original = GroupModel.deleteOne.bind(GroupModel);
    jest.spyOn(GroupModel, 'deleteOne').mockImplementationOnce((...args: Parameters<typeof original>) =>
      (async () => {
        await deletionService.deleteGroup(group.id, organizer.id);
        return original(...args);
      })() as ReturnType<typeof original>);
  }
  const path = kind === 'meetup' ? `/api/meetups/${meetup.id}` : `/api/groups/${group.id}`;
  const result = await request(app).delete(path).set('Authorization', bearer(organizer.id));
  expect(result.status).toBe(404);
  expect(result.body).toEqual({ error: kind === 'meetup' ? 'Meetup not found' : 'Group not found' });
  expect(await MeetupModel.findById(meetup.id)).toBeNull();
  expect(await RecommendationModel.countDocuments({ meetupId: meetup.id })).toBe(0);
  expect(events.filter(event => (event as { event: string }).event === 'meetup:deleted')).toHaveLength(1);
  expect(await GroupModel.countDocuments({ _id: group.id })).toBe(kind === 'meetup' ? 1 : 0);
});
