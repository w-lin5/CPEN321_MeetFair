import type { Server as HttpServer } from 'node:http';

import { Server, type Socket } from 'socket.io';

import { HttpError } from '../common/errors';
import { parseInput } from '../common/middleware/validate';
import { verifySessionToken } from '../common/session';
import { votingSessionParams } from '../voting/schemas';
import { voteService } from '../voting/vote.service';
import { VOTING_EVENTS, setTransport, votingRoom } from './broadcaster';

type Ack = (response: { ok: true; results: unknown } | { ok: false; error: string }) => void;

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

// Joining is authorized like GET /voting-sessions/:id/votes and answers with the current results,
// so a client never misses votes cast between its REST load and the join
async function handleJoin(socket: Socket, payload: unknown, ack: Ack): Promise<void> {
  try {
    const { sessionId } = parseInput(votingSessionParams, payload);
    const results = await voteService.getResults(sessionId, userIdOf(socket));
    await socket.join(votingRoom(sessionId));
    ack({ ok: true, results });
  } catch (err) {
    ack({ ok: false, error: errorMessage(err) });
  }
}

async function handleLeave(socket: Socket, payload: unknown): Promise<void> {
  try {
    const { sessionId } = parseInput(votingSessionParams, payload);
    await socket.leave(votingRoom(sessionId));
  } catch {
    // Leaving a room with a malformed id is a no-op
  }
}

export function attachRealtime(httpServer: HttpServer): Server {
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
    socket.on(VOTING_EVENTS.join, (payload: unknown, ack: unknown) => {
      void handleJoin(socket, payload, safeAck(ack));
    });
    socket.on(VOTING_EVENTS.leave, (payload: unknown) => {
      void handleLeave(socket, payload);
    });
  });

  setTransport((room, event, payload) => {
    io.to(room).emit(event, payload);
  });

  return io;
}
