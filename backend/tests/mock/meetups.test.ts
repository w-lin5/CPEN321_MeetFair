import request from 'supertest';

import { createApp } from '../../src/app';
import { GroupModel, MeetupModel } from '../../src/users-groups/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { PARTICIPATION, createGroup, createMeetup, createUserN } from '../helpers/factories';

beforeAll(startDb);
afterEach(async () => {
  jest.restoreAllMocks();
  await clearDb();
});
afterAll(stopDb);

// Silences the error handler's logging for tests that expect a 500
function quietConsole() {
  jest.spyOn(console, 'error').mockImplementation(() => undefined);
}

async function setup() {
  const user = await createUserN(1);
  const group = await createGroup(user.id);
  const meetup = await createMeetup(group.id, user.id);
  return { user, group, meetup };
}

const NEW_MEETUP = { title: 'Library', scheduledAt: '2026-11-05T22:30:00Z' };

// Interface POST /api/groups/:groupId/meetups
describe('Mocked: POST /api/groups/:groupId/meetups', () => {
  // Input: valid body from a group member
  // Expected status code: 500
  // Expected output: { error: "Internal Server Error" }
  // Mocked behavior: MeetupModel.create rejects
  test('Database failure while saving', async () => {
    quietConsole();
    const { user, group } = await setup();
    jest.spyOn(MeetupModel, 'create').mockRejectedValue(new Error('db down') as never);

    const res = await request(createApp())
      .post(`/api/groups/${group.id}/meetups`)
      .set('Authorization', bearer(user.id))
      .send(NEW_MEETUP);

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
  });

  // Input: valid body
  // Expected status code: 500
  // Expected behavior: nothing is created when membership cannot be checked
  // Mocked behavior: GroupModel.findById rejects
  test('Membership check failure', async () => {
    quietConsole();
    const { user, group } = await setup();
    jest.spyOn(GroupModel, 'findById').mockRejectedValue(new Error('db down') as never);

    const res = await request(createApp())
      .post(`/api/groups/${group.id}/meetups`)
      .set('Authorization', bearer(user.id))
      .send(NEW_MEETUP);

    expect(res.status).toBe(500);
    expect(await MeetupModel.countDocuments()).toBe(1);
  });
});

// Interface GET /api/groups/:groupId/meetups
describe('Mocked: GET /api/groups/:groupId/meetups', () => {
  // Input: group the caller belongs to
  // Expected status code: 500
  // Expected output: { error: "Internal Server Error" }
  // Mocked behavior: MeetupModel.find(...).sort rejects
  test('Database failure', async () => {
    quietConsole();
    const { user, group } = await setup();
    jest.spyOn(MeetupModel, 'find').mockReturnValue({
      sort: () => Promise.reject(new Error('db down')),
    } as never);

    const res = await request(createApp()).get(`/api/groups/${group.id}/meetups`).set('Authorization', bearer(user.id));

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
  });
});

// Interface GET /api/meetups/:meetupId
describe('Mocked: GET /api/meetups/:meetupId', () => {
  // Input: id of an existing meetup
  // Expected status code: 500
  // Expected output: { error: "Internal Server Error" }
  // Mocked behavior: MeetupModel.findById rejects
  test('Meetup lookup failure', async () => {
    quietConsole();
    const { user, meetup } = await setup();
    jest.spyOn(MeetupModel, 'findById').mockRejectedValue(new Error('db down') as never);

    const res = await request(createApp()).get(`/api/meetups/${meetup.id}`).set('Authorization', bearer(user.id));

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
  });

  // Input: id of an existing meetup
  // Expected status code: 500
  // Expected behavior: the meetup is not returned when its group's membership cannot be checked
  // Mocked behavior: GroupModel.findById rejects
  test('Membership check failure', async () => {
    quietConsole();
    const { user, meetup } = await setup();
    jest.spyOn(GroupModel, 'findById').mockRejectedValue(new Error('db down') as never);

    const res = await request(createApp()).get(`/api/meetups/${meetup.id}`).set('Authorization', bearer(user.id));

    expect(res.status).toBe(500);
    expect(res.body.title).toBeUndefined();
  });
});

// Interface PUT /api/meetups/:meetupId/participation
describe('Mocked: PUT /api/meetups/:meetupId/participation', () => {
  // Input: valid body
  // Expected status code: 500
  // Expected behavior: no entry is stored
  // Mocked behavior: MeetupModel.updateOne rejects
  test('Update failure', async () => {
    quietConsole();
    const { user, meetup } = await setup();
    jest.spyOn(MeetupModel, 'updateOne').mockRejectedValue(new Error('db down') as never);

    const res = await request(createApp())
      .put(`/api/meetups/${meetup.id}/participation`)
      .set('Authorization', bearer(user.id))
      .send(PARTICIPATION);

    expect(res.status).toBe(500);
    jest.restoreAllMocks();
    expect((await MeetupModel.findById(meetup.id).orFail()).participants).toHaveLength(0);
  });

  // Input: valid body
  // Expected status code: 409
  // Expected behavior: if the meetup left SETUP after the access check, both the replace and the add match nothing
  // Expected output: { error: "Meetup is no longer accepting participation" }
  // Mocked behavior: MeetupModel.updateOne matches and modifies nothing
  test('Meetup leaves SETUP during the request', async () => {
    const { user, meetup } = await setup();
    const update = jest
      .spyOn(MeetupModel, 'updateOne')
      .mockResolvedValue({ matchedCount: 0, modifiedCount: 0 } as never);

    const res = await request(createApp())
      .put(`/api/meetups/${meetup.id}/participation`)
      .set('Authorization', bearer(user.id))
      .send(PARTICIPATION);

    expect(res.status).toBe(409);
    expect(res.body).toEqual({ error: 'Meetup is no longer accepting participation' });
    expect(update).toHaveBeenCalledTimes(2);
  });

  // Input: valid body from a member with no entry yet
  // Expected status code: 200
  // Expected behavior: the replace matches nothing, so the add is attempted and succeeds
  // Mocked behavior: first MeetupModel.updateOne matches nothing, second modifies one document
  test('Falls back from replace to add', async () => {
    const { user, meetup } = await setup();
    const update = jest
      .spyOn(MeetupModel, 'updateOne')
      .mockResolvedValueOnce({ matchedCount: 0, modifiedCount: 0 } as never)
      .mockResolvedValueOnce({ matchedCount: 1, modifiedCount: 1 } as never);

    const res = await request(createApp())
      .put(`/api/meetups/${meetup.id}/participation`)
      .set('Authorization', bearer(user.id))
      .send(PARTICIPATION);

    expect(res.status).toBe(200);
    expect(update).toHaveBeenCalledTimes(2);
  });

  // Input: valid body from a member who already has an entry
  // Expected status code: 200
  // Expected behavior: when the replace matches, no add is attempted (even if nothing changed)
  // Mocked behavior: MeetupModel.updateOne matches one document but modifies none
  test('Replace that changes nothing does not fall through to add', async () => {
    const { user, meetup } = await setup();
    const update = jest
      .spyOn(MeetupModel, 'updateOne')
      .mockResolvedValue({ matchedCount: 1, modifiedCount: 0 } as never);

    const res = await request(createApp())
      .put(`/api/meetups/${meetup.id}/participation`)
      .set('Authorization', bearer(user.id))
      .send(PARTICIPATION);

    expect(res.status).toBe(200);
    expect(update).toHaveBeenCalledTimes(1);
  });
});

