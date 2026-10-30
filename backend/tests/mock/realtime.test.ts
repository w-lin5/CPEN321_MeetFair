import type { AddressInfo } from 'node:net';
import { type Server as HttpServer, createServer } from 'node:http';

import type { Server } from 'socket.io';
import { type Socket, io as connect } from 'socket.io-client';

import { createApp } from '../../src/app';
import { signSessionToken } from '../../src/common/session';
import { attachRealtime } from '../../src/realtime/gateway';
import { setTransport } from '../../src/realtime/publisher';
import { meetupService } from '../../src/users-groups/meetup.service';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { createGroup, createMeetup, createUserN } from '../helpers/factories';

type Ack = { ok: true } | { ok: false; error: string };

const EVENT_TIMEOUT_MS = 2_000;

let httpServer: HttpServer;
let ioServer: Server;
let url: string;
const clients: Socket[] = [];

beforeAll(async () => {
  await startDb();
  httpServer = createServer(createApp());
  ioServer = attachRealtime(httpServer);
  // Simulates a connection whose authenticated identity was lost after the handshake
  ioServer.use((socket, next) => {
    const auth: unknown = socket.handshake.auth;
    if (typeof auth === 'object' && auth !== null && 'dropIdentity' in auth) socket.data = {};
    next();
  });
  await new Promise<void>((resolve) => httpServer.listen(0, resolve));
  url = `http://localhost:${(httpServer.address() as AddressInfo).port}`;
});

afterEach(async () => {
  jest.restoreAllMocks();
  clients.splice(0).forEach((c) => {
    c.disconnect();
  });
  await clearDb();
});

afterAll(async () => {
  setTransport(null);
  await ioServer.close();
  await stopDb();
});

function client(auth: Record<string, unknown>): Promise<Socket> {
  const socket = connect(url, { transports: ['websocket'], reconnection: false, auth });
  clients.push(socket);
  return new Promise((resolve, reject) => {
    socket.once('connect', () => resolve(socket));
    socket.once('connect_error', reject);
  });
}

async function join(socket: Socket, meetupId: string): Promise<Ack> {
  const ack: Ack = await socket.timeout(EVENT_TIMEOUT_MS).emitWithAck('meetup:join', { meetupId });
  return ack;
}

async function setup() {
  const user = await createUserN(1);
  const group = await createGroup(user.id);
  const meetup = await createMeetup(group.id, user.id);
  return { user, meetup };
}

// Interface: "meetup:join" event
describe('Mocked: meetup:join', () => {
  // Input: group member joins their meetup's room
  // Expected output: ack { ok: false, error: "Internal Server Error" } (the internal message is not exposed)
  // Mocked behavior: meetupService.getForMember rejects with an unexpected error
  test('Unexpected failure hides the error message', async () => {
    const { user, meetup } = await setup();
    jest.spyOn(meetupService, 'getForMember').mockRejectedValue(new Error('db down'));
    const socket = await client({ token: signSessionToken(user.id) });

    expect(await join(socket, meetup.id)).toEqual({ ok: false, error: 'Internal Server Error' });
  });

  // Input: group member joins their meetup's room
  // Expected output: ack { ok: false, error: "Internal Server Error" }; the socket is not trusted without a user id
  // Mocked behavior: an extra middleware clears the socket's authenticated user id after the handshake
  test('Socket without an authenticated user id is rejected', async () => {
    const { user, meetup } = await setup();
    const socket = await client({ token: signSessionToken(user.id), dropIdentity: true });

    expect(await join(socket, meetup.id)).toEqual({ ok: false, error: 'Internal Server Error' });
  });
});
