import type { AddressInfo } from 'node:net';
import { type Server as HttpServer, createServer } from 'node:http';

import type { Server } from 'socket.io';
import { type Socket, io as connect } from 'socket.io-client';
import request from 'supertest';

import { createApp } from '../../src/app';
import { signSessionToken } from '../../src/common/session';
import { attachRealtime } from '../../src/realtime/gateway';
import { setTransport } from '../../src/realtime/publisher';
import { clearDb, startDb, stopDb } from '../helpers/db';
import {
  MISSING_ID,
  addMember,
  candidateIds,
  createGroup,
  createMeetup,
  createUserN,
  createVotingSession,
} from '../helpers/factories';

type JoinAck = { ok: true; results: { totals: { voteCount: number }[] } } | { ok: false; error: string };

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

function join(socket: Socket, sessionId: unknown): Promise<JoinAck> {
  return socket.timeout(EVENT_TIMEOUT_MS).emitWithAck('voting:join', { sessionId });
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

async function setup() {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const outsider = await createUserN(3);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  const meetup = await createMeetup(group.id, organizer.id);
  const session = await createVotingSession(meetup.id, organizer.id);
  return { organizer, member, outsider, session, ids: candidateIds(session) };
}

function vote(sessionId: string, userId: string, candidateId: string) {
  return request(app)
    .post(`/api/voting-sessions/${sessionId}/vote`)
    .set('Authorization', `Bearer ${signSessionToken(userId)}`)
    .send({ candidateId });
}

// Interface: Socket.IO handshake
describe('Unmocked: socket authentication', () => {
  // Input: no token / forged token
  // Expected behavior: connection refused with "Unauthorized"
  test.each([['missing', undefined], ['invalid', 'not.a.token']])('Rejects a %s token', async (_label, token) => {
    await expect(client(token)).rejects.toThrow('Unauthorized');
  });

  // Input: token sent as an Authorization: Bearer header instead of in the auth payload
  // Expected behavior: connection accepted and the user can join their session
  test('Accepts a Bearer header', async () => {
    const { member, session } = await setup();
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

    expect(await join(socket, session.id)).toMatchObject({ ok: true });
  });
});

// Interface: "voting:join" event
describe('Unmocked: voting:join', () => {
  // Input: group member joins an existing session
  // Expected output: ack { ok: true } with the current results
  test('Member joins and receives current results', async () => {
    const { member, session, ids } = await setup();
    await vote(session.id, member.id, ids[0]);
    const socket = await client(signSessionToken(member.id));

    const ack = await join(socket, session.id);

    expect(ack).toMatchObject({ ok: true, results: { totals: [{ voteCount: 1 }, { voteCount: 0 }, { voteCount: 0 }] } });
  });

  // Input: outsider / missing session / malformed id
  // Expected output: ack { ok: false } with the matching error message
  test('Rejects outsiders, missing sessions and malformed ids', async () => {
    const { outsider, member, session } = await setup();
    const outsiderSocket = await client(signSessionToken(outsider.id));
    const memberSocket = await client(signSessionToken(member.id));

    expect(await join(outsiderSocket, session.id)).toEqual({ ok: false, error: 'Not a member of this group' });
    expect(await join(memberSocket, MISSING_ID)).toEqual({ ok: false, error: 'Voting session not found' });
    expect(await join(memberSocket, 'nope')).toEqual({ ok: false, error: 'Invalid request' });
  });

  // Input: join without an ack callback
  // Expected behavior: the socket still joins the room and receives later updates
  test('Join without an ack still subscribes', async () => {
    const { organizer, member, session, ids } = await setup();
    const socket = await client(signSessionToken(member.id));

    socket.emit('voting:join', { sessionId: session.id });
    await new Promise((resolve) => setTimeout(resolve, SILENCE_MS));
    const received = nextEvent(socket, 'voting:results');
    await vote(session.id, organizer.id, ids[0]);

    await expect(received).resolves.toBeDefined();
  });
});

// Interface: "voting:results" broadcast
describe('Unmocked: live vote updates', () => {
  // Input: two members in the room; one votes over REST, then repeats the same vote
  // Expected behavior: both receive exactly one results update; an outsider-rejected join receives nothing
  test('Broadcasts to joined members only when a vote changes', async () => {
    const { organizer, member, outsider, session, ids } = await setup();
    const organizerSocket = await client(signSessionToken(organizer.id));
    const memberSocket = await client(signSessionToken(member.id));
    const outsiderSocket = await client(signSessionToken(outsider.id));
    await join(organizerSocket, session.id);
    await join(memberSocket, session.id);
    await join(outsiderSocket, session.id);
    const organizerSeen = recorder(organizerSocket, 'voting:results');
    const outsiderSeen = recorder(outsiderSocket, 'voting:results');

    const received = nextEvent<{ voterCount: number; totals: { voteCount: number }[] }>(memberSocket, 'voting:results');
    await vote(session.id, member.id, ids[1]);
    const update = await received;
    await vote(session.id, member.id, ids[1]);
    await new Promise((resolve) => setTimeout(resolve, SILENCE_MS));

    expect(update.voterCount).toBe(1);
    expect(update.totals.map((t) => t.voteCount)).toEqual([0, 1, 0]);
    expect(organizerSeen).toHaveLength(1);
    expect(outsiderSeen).toHaveLength(0);
  });

  // Input: member leaves the room, then another member votes
  // Expected behavior: the departed socket receives nothing
  test('voting:leave stops updates', async () => {
    const { organizer, member, session, ids } = await setup();
    const memberSocket = await client(signSessionToken(member.id));
    await join(memberSocket, session.id);
    const seen = recorder(memberSocket, 'voting:results');

    memberSocket.emit('voting:leave', { sessionId: session.id });
    memberSocket.emit('voting:leave', { sessionId: 'malformed' });
    await new Promise((resolve) => setTimeout(resolve, SILENCE_MS));
    await vote(session.id, organizer.id, ids[0]);
    await new Promise((resolve) => setTimeout(resolve, SILENCE_MS));

    expect(seen).toHaveLength(0);
  });

  // Input: organizer closes the session while a member is joined
  // Expected behavior: member receives "voting:closed" with the winner
  test('Broadcasts the close with the winner', async () => {
    const { organizer, member, session, ids } = await setup();
    const memberSocket = await client(signSessionToken(member.id));
    await join(memberSocket, session.id);
    await vote(session.id, member.id, ids[2]);

    const closed = nextEvent<{ session: { status: string; winnerCandidateId: string } }>(memberSocket, 'voting:closed');
    await request(app)
      .patch(`/api/voting-sessions/${session.id}/close`)
      .set('Authorization', `Bearer ${signSessionToken(organizer.id)}`)
      .send({});

    expect((await closed).session).toMatchObject({ status: 'closed', winnerCandidateId: ids[2] });
  });
});
