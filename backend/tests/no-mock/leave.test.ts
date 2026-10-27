import request from 'supertest';
import { createApp } from '../../src/app';
import * as placesClient from '../../src/integrations/places.client';
import { setTransport } from '../../src/realtime/publisher';
import { placeSearchService } from '../../src/recommendation/place-search.service';
import { GroupModel, MeetupModel } from '../../src/users-groups/models';
import { VoteModel } from '../../src/voting-fairness/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { addMember, addParticipants, createGroup, createMeetup, createUserN, MISSING_ID, PARTICIPATION, PLACE_1 } from '../helpers/factories';

beforeAll(startDb);
afterEach(async () => { jest.restoreAllMocks(); setTransport(null); await clearDb(); });
afterAll(stopDb);

async function setup() {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const outsider = await createUserN(3);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  const meetup = await createMeetup(group.id, organizer.id);
  await MeetupModel.updateOne({ _id: meetup.id }, { $set: { participants: [
    { userId: organizer.id, ...PARTICIPATION, startLocation: { lat: 40, lng: -74 }, preferences: ['FOOD'] },
    { userId: member.id, ...PARTICIPATION, startLocation: { lat: 50, lng: -120 } },
  ] } });
  return { organizer, member, outsider, group, meetup };
}

function leaveMeetup(id: string, userId?: string) {
  const req = request(createApp()).delete(`/api/meetups/${id}/participation`);
  return userId ? req.set('Authorization', bearer(userId)) : req;
}
function leaveGroup(id: string, userId?: string) {
  const req = request(createApp()).post(`/api/groups/${id}/leave`);
  return userId ? req.set('Authorization', bearer(userId)) : req;
}

describe('voluntary leave', () => {
  test('leaving SETUP meetup removes inputs, excludes them from search and keeps group membership', async () => {
    const { organizer, member, group, meetup } = await setup();
    const events: string[] = [];
    setTransport((_room, event) => events.push(event));
    expect((await leaveMeetup(meetup.id, member.id)).status).toBe(204);
    expect((await MeetupModel.findById(meetup.id).orFail()).participants.map(p => String(p.userId))).toEqual([organizer.id]);
    expect((await GroupModel.findById(group.id).orFail()).members).toHaveLength(2);
    const google = jest.spyOn(placesClient, 'searchNearby').mockResolvedValue([]);
    await placeSearchService.search(meetup.id, organizer.id);
    expect(google).toHaveBeenCalledWith({ lat: 40, lng: -74 }, ['restaurant']);
    expect(events).toEqual(['meetup:updated']);
  });

  test.each(['VOTING', 'FINALIZED', 'SETTLED'] as const)('cannot leave a meetup in %s', async status => {
    const { member, meetup } = await setup();
    await MeetupModel.updateOne({ _id: meetup.id }, { $set: { status } });
    expect((await leaveMeetup(meetup.id, member.id)).status).toBe(409);
    expect((await MeetupModel.findById(meetup.id).orFail()).participants).toHaveLength(2);
  });

  test('group leave blocks only joined VOTING meetups and makes no changes on refusal', async () => {
    const { member, group, meetup } = await setup();
    const voting = await createMeetup(group.id, member.id, { status: 'VOTING' });
    await addParticipants(voting.id, [member.id]);
    expect((await leaveGroup(group.id, member.id)).status).toBe(409);
    expect((await GroupModel.findById(group.id).orFail()).members).toHaveLength(2);
    expect((await MeetupModel.findById(meetup.id).orFail()).participants).toHaveLength(2);
  });

  test('group leave allows finished votes, clears only SETUP and preserves other groups', async () => {
    const { organizer, member, group, meetup } = await setup();
    const finalized = await createMeetup(group.id, member.id, { status: 'FINALIZED' });
    await addParticipants(finalized.id, [member.id]);
    await VoteModel.create({ meetupId: finalized.id, userId: member.id, placeId: PLACE_1, locked: true, weight: 1 });
    const settled = await createMeetup(group.id, organizer.id, { status: 'SETTLED' });
    await addParticipants(settled.id, [member.id]);
    await createMeetup(group.id, organizer.id, { status: 'VOTING' }); // Target never joined this vote.
    const other = await createGroup(member.id, { inviteCode: 'XYZ234' });
    const otherMeetup = await createMeetup(other.id, member.id);
    await addParticipants(otherMeetup.id, [member.id]);
    const events: string[] = [];
    setTransport((_room, event) => events.push(event));
    expect((await leaveGroup(group.id, member.id)).status).toBe(204);
    expect((await MeetupModel.findById(meetup.id).orFail()).participants.map(p => String(p.userId))).toEqual([organizer.id]);
    expect((await MeetupModel.findById(finalized.id).orFail()).participants).toHaveLength(1);
    expect(String((await MeetupModel.findById(finalized.id).orFail()).organizerId)).toBe(organizer.id);
    expect((await MeetupModel.findById(settled.id).orFail()).participants).toHaveLength(1);
    expect(await VoteModel.countDocuments({ meetupId: finalized.id, userId: member.id })).toBe(1);
    expect((await MeetupModel.findById(otherMeetup.id).orFail()).participants).toHaveLength(1);
    expect(events.every(event => event === 'meetup:updated')).toBe(true);
    expect((await request(createApp()).get(`/api/groups/${group.id}`).set('Authorization', bearer(member.id))).status).toBe(403);
    await request(createApp()).post('/api/groups/join').set('Authorization', bearer(member.id)).send({ inviteCode: group.inviteCode });
    expect((await MeetupModel.findById(meetup.id).orFail()).participants).toHaveLength(1);
  });

  test('authorization, invalid identifiers and group organizer protection', async () => {
    const { organizer, member, outsider, group, meetup } = await setup();
    expect((await leaveGroup(group.id)).status).toBe(401);
    expect((await leaveMeetup(meetup.id)).status).toBe(401);
    expect((await leaveGroup(group.id, outsider.id)).status).toBe(403);
    expect((await leaveMeetup(meetup.id, outsider.id)).status).toBe(403);
    expect((await leaveGroup(group.id, organizer.id)).status).toBe(400);
    expect((await leaveGroup('invalid', member.id)).status).toBe(400);
    expect((await leaveMeetup('invalid', member.id)).status).toBe(400);
    expect((await leaveGroup(MISSING_ID, member.id)).status).toBe(404);
    expect((await leaveMeetup(MISSING_ID, member.id)).status).toBe(404);
  });
});
