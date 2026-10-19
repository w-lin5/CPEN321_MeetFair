import type { AddressInfo } from 'node:net';
import { type Server as HttpServer, createServer } from 'node:http';

import type { Server } from 'socket.io';
import { type Socket, io as connect } from 'socket.io-client';
import request from 'supertest';

import { createApp } from '../../src/app';
import { signSessionToken } from '../../src/common/session';
import { attachRealtime } from '../../src/realtime/gateway';
import { setTransport } from '../../src/realtime/publisher';
import { VoteModel } from '../../src/voting-fairness/models';
import { clearDb, startDb, stopDb } from '../helpers/db';
import {
  MISSING_ID,
  PLACE_2,
  PLACE_3,
  addMember,
  createGroup,
  createUserN,
  createVotingMeetup,
} from '../helpers/factories';

type Ack = { ok: true } | { ok: false; error: string };

const EVENT_TIMEOUT_MS = 2_000;
// How long to wait before concluding that an event was (correctly) not delivered
const SILENCE_MS = 200;

let app: ReturnType<typeof createApp>;
let httpServer: HttpServer;
let ioServer: Server;
let url: string;
const clients: Socket[] = [];

beforeAll(async () => {
  await startDb();
  app = createApp();
  httpServer = createServer(app);
  ioServer = attachRealtime(httpServer);
  await new Promise<void>((resolve) => httpServer.listen(0, resolve));
  url = `http://localhost:${(httpServer.address() as AddressInfo).port}`;
});

afterEach(async () => {
  clients.splice(0).forEach((c) => c.disconnect());
  await clearDb();
});

afterAll(async () => {
  setTransport(null);
  await ioServer.close();
  await stopDb();
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

function join(socket: Socket, meetupId: unknown): Promise<Ack> {
  return socket.timeout(EVENT_TIMEOUT_MS).emitWithAck('meetup:join', { meetupId });
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
