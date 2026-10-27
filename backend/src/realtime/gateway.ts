import type { Server as HttpServer } from 'node:http';
import type { Server as HttpsServer } from 'node:https';

import { Server, type Socket } from 'socket.io';

import { HttpError } from '../common/errors';
import { parseInput } from '../common/middleware/validate';
import { verifySessionToken } from '../common/session';
import { meetupService } from '../users-groups/meetup.service';
import { meetupParams } from '../users-groups/schemas';
import { CLIENT_EVENTS, meetupRoom } from './events';
import { setRevoker, setTransport } from './publisher';

type Ack = (response: { ok: true } | { ok: false; error: string }) => void;

// Same token the REST API accepts, sent as { auth: { token } } or an Authorization: Bearer header
function handshakeToken(socket: Socket): string | undefined {
  const auth: unknown = socket.handshake.auth;
  const fromAuth = typeof auth === 'object' && auth !== null && 'token' in auth ? auth.token : undefined;
  if (typeof fromAuth === 'string' && fromAuth !== '') return fromAuth;

  const [scheme, token] = (socket.handshake.headers.authorization ?? '').split(' ');
  return scheme === 'Bearer' && token !== undefined && token !== '' ? token : undefined;
}

function userIdOf(socket: Socket): string {
  const data: unknown = socket.data;
  const userId = typeof data === 'object' && data !== null && 'userId' in data ? data.userId : undefined;
  if (typeof userId !== 'string') throw new Error('Socket is not authenticated');
  return userId;
}

// Only expose messages we chose to make public (HttpError); hide anything unexpected
function errorMessage(err: unknown): string {
  return err instanceof HttpError ? err.message : 'Internal Server Error';
}

function safeAck(ack: unknown): Ack {
  return typeof ack === 'function' ? (ack as Ack) : () => undefined;
}

// Only members of the meetup's group may join its room. Clients reload over REST after the ack,
// so nothing published between their first load and the join is missed
async function handleJoin(socket: Socket, payload: unknown, ack: Ack): Promise<void> {
  try {
    const { meetupId } = parseInput(meetupParams, payload);
    await meetupService.getForMember(meetupId, userIdOf(socket));
    await socket.join(meetupRoom(meetupId));
    // Recheck after joining so an in-flight removal cannot leave an unauthorized subscription.
    try {
      await meetupService.getForMember(meetupId, userIdOf(socket));
    } catch (err) {
      await socket.leave(meetupRoom(meetupId));
      throw err;
    }
    ack({ ok: true });
  } catch (err) {
    ack({ ok: false, error: errorMessage(err) });
  }
}

async function handleLeave(socket: Socket, payload: unknown, ack: Ack): Promise<void> {
  try {
    const { meetupId } = parseInput(meetupParams, payload);
    await socket.leave(meetupRoom(meetupId));
    ack({ ok: true });
  } catch (err) {
    ack({ ok: false, error: errorMessage(err) });
  }
}

// Works on the plain HTTP server and on the HTTPS server alike (clients then connect with wss://)
export function attachRealtime(httpServer: HttpServer | HttpsServer): Server {
  const io = new Server(httpServer);

  io.use((socket, next) => {
    const token = handshakeToken(socket);
    try {
      if (token === undefined) throw new Error('Missing token');
      socket.data = { userId: verifySessionToken(token) };
      next();
    } catch {
      next(new Error('Unauthorized'));
    }
  });

  io.on('connection', (socket) => {
    socket.on(CLIENT_EVENTS.meetupJoin, (payload: unknown, ack: unknown) => {
      void handleJoin(socket, payload, safeAck(ack));
    });
    socket.on(CLIENT_EVENTS.meetupLeave, (payload: unknown, ack: unknown) => {
      void handleLeave(socket, payload, safeAck(ack));
    });
  });

  setTransport((room, event, payload) => {
    io.to(room).emit(event, payload);
  });

  setRevoker(userId => {
    for (const socket of io.sockets.sockets.values()) {
      if (userIdOf(socket) === userId) socket.disconnect(true);
    }
  });

  return io;
}
