import type { AddressInfo } from 'node:net';
import { type Server as HttpServer, createServer } from 'node:http';

import type { Server } from 'socket.io';
import { type Socket, io as connect } from 'socket.io-client';
import request from 'supertest';

import { createApp } from '../../src/app';
import { signSessionToken } from '../../src/common/session';
import { attachRealtime } from '../../src/realtime/gateway';
import { setRevoker, setRoomCloser, setTransport } from '../../src/realtime/publisher';
import { VoteModel } from '../../src/voting-fairness/models';
import { clearDb, startDb, stopDb } from '../helpers/db';
import {
  MISSING_ID,
  PLACE_2,
  PLACE_3,
  PARTICIPATION,
  addMember,
  createGroup,
  createMeetup,
  createUserN,
  createVotingMeetup,
  createFinalizedMeetup,
} from '../helpers/factories';
import { MeetupModel } from '../../src/users-groups/models';

type Ack = { ok: true } | { ok: false; error: string };

const EVENT_TIMEOUT_MS = 2_000;
// How long to wait before concluding that an event was (correctly) not delivered
const SILENCE_MS = 200;

let app: ReturnType<typeof createApp>;
let httpServer: HttpServer;
let ioServer: Server;
let url: string;
const clients: Socket[] = [];

test('trip updates invalidate a connected group member map without broadcasting coordinates', async () => {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  const meetup = await createFinalizedMeetup(group.id, organizer.id, [member.id]);
  await MeetupModel.updateOne({ _id: meetup.id }, { $set: { scheduledAt: new Date(Date.now() - 60000) } });
  const socket = await client(signSessionToken(organizer.id));
  expect(await join(socket, meetup.id)).toEqual({ ok: true });
  let update = nextEvent(socket, 'trip:updated');
  expect((await request(app).post(`/api/meetups/${meetup.id}/trip/start`)
    .set('Authorization', `Bearer ${signSessionToken(member.id)}`)).status).toBe(200);
  expect(await update).toEqual({ meetupId: meetup.id });
  update = nextEvent(socket, 'trip:updated');
  expect((await request(app).put(`/api/meetups/${meetup.id}/trip/location`)
    .set('Authorization', `Bearer ${signSessionToken(member.id)}`).send({ lat: 49, lng: -123, accuracyM: 5 })).status).toBe(200);
  expect(await update).toEqual({ meetupId: meetup.id });
});

test('entering, editing and leaving details silently updates an already-open meetup', async () => {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  const meetup = await createMeetup(group.id, organizer.id);
  const viewer = await client(signSessionToken(organizer.id));
  expect(await join(viewer, meetup.id)).toEqual({ ok: true });
  const notices = recorder(viewer, 'group:meetup-deleted');
  const auth = `Bearer ${signSessionToken(member.id)}`;
  const read = () => request(app).get(`/api/meetups/${meetup.id}`).set('Authorization', `Bearer ${signSessionToken(organizer.id)}`);

  let update = nextEvent<{ meetupId: string }>(viewer, 'meetup:updated');
  expect((await request(app).put(`/api/meetups/${meetup.id}/participation`).set('Authorization', auth).send(PARTICIPATION)).status).toBe(200);
  expect(await update).toEqual({ meetupId: meetup.id });
  expect((await read()).body.participants).toHaveLength(1);

  update = nextEvent<{ meetupId: string }>(viewer, 'meetup:updated');
  const edit = { ...PARTICIPATION, startLocation: { lat: 40, lng: -74, label: 'New location' } };
  expect((await request(app).put(`/api/meetups/${meetup.id}/participation`).set('Authorization', auth).send(edit)).status).toBe(200);
  await update;
  const edited = await read();
  expect(edited.body.participants).toHaveLength(1);
  expect(edited.body.participants[0].startLocation).toMatchObject(edit.startLocation);

  update = nextEvent<{ meetupId: string }>(viewer, 'meetup:updated');
  expect((await request(app).delete(`/api/meetups/${meetup.id}/participation`).set('Authorization', auth)).status).toBe(204);
  await update;
  expect((await read()).body.participants).toHaveLength(0);
  expect(notices).toEqual([]);
});

beforeAll(async () => {
  await startDb();
  app = createApp();
  httpServer = createServer(app);
  ioServer = attachRealtime(httpServer);
  await new Promise<void>((resolve) => httpServer.listen(0, resolve));
  url = `http://localhost:${(httpServer.address() as AddressInfo).port}`;
});

afterEach(async () => {
  clients.splice(0).forEach((c) => {
    c.disconnect();
  });
  await clearDb();
});

afterAll(async () => {
  setRoomCloser(null);
  setRevoker(null);
  setTransport(null);
  await ioServer.close();
  await stopDb();
});

test('deleting a voting meetup notifies members, clears the room and prevents joining again', async () => {
  const { organizer, member, meetup } = await setup();
  const socket = await client(signSessionToken(member.id));
  expect(await join(socket, meetup.id)).toEqual({ ok: true });
  const deleted = nextEvent<{ meetupId: string }>(socket, 'meetup:deleted');
  const res = await request(app).delete(`/api/meetups/${meetup.id}`)
    .set('Authorization', `Bearer ${signSessionToken(organizer.id)}`);
  expect(res.status).toBe(204);
  expect(await deleted).toEqual({ meetupId: meetup.id, participantIds: [organizer.id, member.id] });
  expect(ioServer.sockets.adapter.rooms.has(`meetup:${meetup.id}`)).toBe(false);
  expect(await join(socket, meetup.id)).toMatchObject({ ok: false, error: 'Meetup not found' });
});

test('group deletion notifies members without a meetup subscription, but not outsiders', async () => {
  const { organizer, member, outsider, meetup } = await setup();
  const memberSocket = await client(signSessionToken(member.id));
  const outsiderSocket = await client(signSessionToken(outsider.id));
  const outsideEvents = recorder(outsiderSocket, 'group:deleted');
  const notification = nextEvent<{ groupId: string }>(memberSocket, 'group:deleted');
  const groupId = String(meetup.groupId);
  const res = await request(app).delete(`/api/groups/${groupId}`)
    .set('Authorization', `Bearer ${signSessionToken(organizer.id)}`);
  expect(res.status).toBe(204);
  expect(await notification).toEqual({ groupId });
  await silence();
  expect(outsideEvents).toEqual([]);
});

test('meetup deletion notifies the group detail listener without joining the meetup', async () => {
  const { organizer, member, outsider, meetup } = await setup();
  const memberSocket = await client(signSessionToken(member.id));
  const outsiderSocket = await client(signSessionToken(outsider.id));
  const outsideEvents = recorder(outsiderSocket, 'group:meetup-deleted');
  const notification = nextEvent<{ groupId: string; meetupId: string }>(memberSocket, 'group:meetup-deleted');
  const res = await request(app).delete(`/api/meetups/${meetup.id}`)
    .set('Authorization', `Bearer ${signSessionToken(organizer.id)}`);
  expect(res.status).toBe(204);
  expect(await notification).toEqual({ groupId: String(meetup.groupId), meetupId: meetup.id, notifyParticipant: true });
  await silence();
  expect(outsideEvents).toEqual([]);
});

test('removing a member disconnects their existing socket and denies a new room subscription', async () => {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  const meetup = await createMeetup(group.id, organizer.id);
  const socket = await client(signSessionToken(member.id));
  expect(await join(socket, meetup.id)).toEqual({ ok: true });
  const disconnected = nextEvent<string>(socket, 'disconnect');
  const response = await request(app).delete(`/api/groups/${group.id}/members/${member.id}`)
    .set('Authorization', `Bearer ${signSessionToken(organizer.id)}`);
  expect(response.status).toBe(204);
  expect(await disconnected).toBe('io server disconnect');
  const reconnected = await client(signSessionToken(member.id));
  expect(await join(reconnected, meetup.id)).toMatchObject({ ok: false, error: 'Not a member of this group' });
});

test('non-participants get silent list invalidation and no deletion notice eligibility', async () => {
  const { organizer, member, meetup } = await setup();
  const observer = await createUserN(4);
  await addMember(String(meetup.groupId), observer.id);
  const socket = await client(signSessionToken(observer.id));
  expect(await join(socket, meetup.id)).toEqual({ ok: true });
  const listUpdate = nextEvent<{ notifyParticipant: boolean }>(socket, 'group:meetup-deleted');
  const pageUpdate = nextEvent<{ participantIds: string[] }>(socket, 'meetup:deleted');
  const res = await request(app).delete(`/api/meetups/${meetup.id}`)
    .set('Authorization', `Bearer ${signSessionToken(organizer.id)}`);
  expect(res.status).toBe(204);
  expect(await listUpdate).toMatchObject({ notifyParticipant: false });
  expect((await pageUpdate).participantIds).toEqual([organizer.id, member.id]);
});

// Connects with the given session token (or none) and resolves once connected or rejected
function client(token?: string): Promise<Socket> {
  const socket = connect(url, { transports: ['websocket'], reconnection: false, ...(token ? { auth: { token } } : {}) });
  clients.push(socket);
  return new Promise((resolve, reject) => {
    socket.once('connect', () => resolve(socket));
    socket.once('connect_error', reject);
  });
}

async function join(socket: Socket, meetupId: unknown): Promise<Ack> {
  const ack: Ack = await socket.timeout(EVENT_TIMEOUT_MS).emitWithAck('meetup:join', { meetupId });
  return ack;
}

function nextEvent<T>(socket: Socket, event: string): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`No ${event} within ${EVENT_TIMEOUT_MS}ms`)), EVENT_TIMEOUT_MS);
    socket.once(event, (payload: T) => {
      clearTimeout(timer);
      resolve(payload);
    });
  });
}

// Records every event of a kind so a test can assert that none arrived
function recorder(socket: Socket, event: string): unknown[] {
  const seen: unknown[] = [];
  socket.on(event, (payload: unknown) => seen.push(payload));
  return seen;
}

function silence(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, SILENCE_MS));
}

async function setup() {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const outsider = await createUserN(3);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  const meetup = await createVotingMeetup(group.id, organizer.id, [organizer.id, member.id]);
  return { organizer, member, outsider, meetup };
}

function vote(meetupId: string, userId: string, placeId: string) {
  return request(app)
    .put(`/api/meetups/${meetupId}/vote`)
    .set('Authorization', `Bearer ${signSessionToken(userId)}`)
    .send({ placeId });
}

// Interface: Socket.IO handshake
describe('Unmocked: socket authentication', () => {
  // Input: no token / forged token
  // Expected behavior: connection refused with "Unauthorized"
  test.each([['missing', undefined], ['invalid', 'not.a.token']])('Rejects a %s token', async (_label, token) => {
    await expect(client(token)).rejects.toThrow('Unauthorized');
  });

  // Input: token sent as an Authorization: Bearer header instead of in the auth payload
  // Expected behavior: connection accepted and the user can join their meetup
  test('Accepts a Bearer header', async () => {
    const { member, meetup } = await setup();
    const socket = connect(url, {
      transports: ['websocket'],
      reconnection: false,
      extraHeaders: { Authorization: `Bearer ${signSessionToken(member.id)}` },
    });
    clients.push(socket);
    await new Promise((resolve, reject) => {
      socket.once('connect', () => resolve(undefined));
      socket.once('connect_error', reject);
    });

    expect(await join(socket, meetup.id)).toEqual({ ok: true });
  });
});

// Interface: "meetup:join" / "meetup:leave" events
describe('Unmocked: meetup:join', () => {
  // Input: group member joins; outsider / missing meetup / malformed id
  // Expected output: ack { ok: true }; ack { ok: false } with the matching error message
  test('Members join, others are rejected', async () => {
    const { outsider, member, meetup } = await setup();
    const outsiderSocket = await client(signSessionToken(outsider.id));
    const memberSocket = await client(signSessionToken(member.id));

    expect(await join(memberSocket, meetup.id)).toEqual({ ok: true });
    expect(await join(outsiderSocket, meetup.id)).toEqual({ ok: false, error: 'Not a member of this group' });
    expect(await join(memberSocket, MISSING_ID)).toEqual({ ok: false, error: 'Meetup not found' });
    expect(await join(memberSocket, 'nope')).toEqual({ ok: false, error: 'Invalid request' });
  });

  // Input: join without an ack callback
  // Expected behavior: the socket still joins the room and receives later updates
  test('Join without an ack still subscribes', async () => {
    const { organizer, member, meetup } = await setup();
    const socket = await client(signSessionToken(member.id));

    socket.emit('meetup:join', { meetupId: meetup.id });
    await silence();
    const received = nextEvent(socket, 'vote:updated');
    await vote(meetup.id, organizer.id, PLACE_2);

    await expect(received).resolves.toBeDefined();
  });

  // Input: member leaves the room (and sends a malformed leave), then another member votes
  // Expected behavior: leave acks { ok: true } / { ok: false }; the departed socket receives nothing
  test('meetup:leave stops updates', async () => {
    const { organizer, member, meetup } = await setup();
    const memberSocket = await client(signSessionToken(member.id));
    await join(memberSocket, meetup.id);
    const seen = recorder(memberSocket, 'vote:updated');

    expect(await memberSocket.timeout(EVENT_TIMEOUT_MS).emitWithAck('meetup:leave', { meetupId: meetup.id }))
      .toEqual({ ok: true });
    expect(await memberSocket.timeout(EVENT_TIMEOUT_MS).emitWithAck('meetup:leave', { meetupId: 'malformed' }))
      .toMatchObject({ ok: false });
    await vote(meetup.id, organizer.id, PLACE_2);
    await silence();

    expect(seen).toHaveLength(0);
  });
});

// Interface: "vote:updated" and "meetup:status" broadcasts
describe('Unmocked: live meetup updates', () => {
  // Input: two members in the room; one votes over REST, then repeats the same vote
  // Expected behavior: both receive exactly one tally update; a rejected outsider receives nothing
  test('Broadcasts the tally to joined members only when a vote changes', async () => {
    const { organizer, member, outsider, meetup } = await setup();
    const organizerSocket = await client(signSessionToken(organizer.id));
    const memberSocket = await client(signSessionToken(member.id));
    const outsiderSocket = await client(signSessionToken(outsider.id));
    await join(organizerSocket, meetup.id);
    await join(memberSocket, meetup.id);
    await join(outsiderSocket, meetup.id);
    const organizerSeen = recorder(organizerSocket, 'vote:updated');
    const outsiderSeen = recorder(outsiderSocket, 'vote:updated');

    const received = nextEvent<{ meetupId: string; candidates: { voteCount: number }[] }>(memberSocket, 'vote:updated');
    await vote(meetup.id, member.id, PLACE_2);
    const update = await received;
    await vote(meetup.id, member.id, PLACE_2);
    await silence();

    expect(update.meetupId).toBe(meetup.id);
    expect(update.candidates.map((c) => c.voteCount)).toEqual([0, 1, 0]);
    expect(organizerSeen).toHaveLength(1);
    expect(outsiderSeen).toHaveLength(0);
  });

  // Input: organizer finalizes while a member is joined
  // Expected behavior: member receives "meetup:status" with FINALIZED and the winner
  test('Broadcasts the finalize with the winner', async () => {
    const { organizer, member, meetup } = await setup();
    const memberSocket = await client(signSessionToken(member.id));
    await join(memberSocket, meetup.id);
    await VoteModel.create({ meetupId: meetup.id, userId: member.id, placeId: PLACE_3, locked: true, weight: 1 });

    const status = nextEvent<{ status: string; winner: { placeId: string } }>(memberSocket, 'meetup:status');
    await request(app)
      .post(`/api/meetups/${meetup.id}/finalize`)
      .set('Authorization', `Bearer ${signSessionToken(organizer.id)}`);

    expect(await status).toMatchObject({ status: 'FINALIZED', winner: { placeId: PLACE_3 } });
  });
});
