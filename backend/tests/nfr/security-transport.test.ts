import { execFileSync } from 'node:child_process';
import * as fs from 'node:fs';
import { get as httpGet } from 'node:http';
import type { Server as HttpsServer } from 'node:https';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import path from 'node:path';

import { type Server } from 'socket.io';
import { io as connect, type Socket } from 'socket.io-client';
import request from 'supertest';

import { createApp } from '../../src/app';
import { signSessionToken } from '../../src/common/session';
import { env } from '../../src/config';
import { attachRealtime } from '../../src/realtime/gateway';
import { setRevoker, setRoomCloser, setTransport } from '../../src/realtime/publisher';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { addMember, createGroup, createMeetup, createUserN } from '../helpers/factories';

// Requirements 3.7.1 / 4.7.1. Boot the production index with real HTTPS and Socket.IO.
// Substitute only DB bootstrap (already connected to the test DB), port and test PEM paths.
let server: HttpsServer | undefined;
let ioServer: Server | undefined;
let url: string;
let cert: Buffer;
let pemDirectory: string;
const clients: Socket[] = [];

beforeAll(async () => {
  await startDb();
  pemDirectory = fs.mkdtempSync(path.join(tmpdir(), 'meetfair-nfr-tls-'));
  const gitOpenSsl = 'C:\\Program Files\\Git\\usr\\bin\\openssl.exe';
  const openssl = process.platform === 'win32' && fs.existsSync(gitOpenSsl) ? gitOpenSsl : 'openssl';
  // Generate a disposable certificate at runtime, so no checked-in credential or fixed expiry is needed.
  execFileSync(openssl, [
    'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
    '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1',
    '-keyout', path.join(pemDirectory, 'key.pem'), '-out', path.join(pemDirectory, 'cert.pem'),
  ], { stdio: 'pipe', timeout: 15_000 });
  cert = fs.readFileSync(path.join(pemDirectory, 'cert.pem'));
  const key = fs.readFileSync(path.join(pemDirectory, 'key.pem'));
  const originalFs = jest.requireActual<typeof fs>('node:fs');
  const onSignal = jest.spyOn(process, 'on').mockImplementation(() => process);
  const startupLog = jest.spyOn(console, 'info').mockImplementation(() => undefined);
  try {
    jest.isolateModules(() => {
      jest.doMock('node:fs', () => ({
        ...originalFs,
        existsSync: (file: string) => file.endsWith('fullchain.pem') || file.endsWith('privkey.pem') || originalFs.existsSync(file),
        readFileSync: (file: string) => file.endsWith('fullchain.pem') ? cert : key,
      }));
      jest.doMock('../../src/app', () => ({ createApp }));
      jest.doMock('../../src/config', () => ({ env: { ...env, port: 0 } }));
      jest.doMock('../../src/db', () => ({ connectDb: async () => undefined, disconnectDb: async () => undefined }));
      jest.doMock('../../src/realtime/gateway', () => ({
        attachRealtime: (listener: HttpsServer) => {
          server = listener;
          ioServer = attachRealtime(listener);
          return ioServer;
        },
      }));
      jest.requireActual('../../src/index');
    });
    await new Promise<void>(resolve => setImmediate(resolve));
    if (!server) throw new Error('Production HTTPS bootstrap did not start');
    if (!server.listening) await new Promise<void>(resolve => server?.once('listening', resolve));
    url = `https://127.0.0.1:${(server.address() as AddressInfo).port}`;
    expect(startupLog).toHaveBeenCalledWith('Server listening on port 0 (https)');
  } finally {
    onSignal.mockRestore();
    startupLog.mockRestore();
    for (const module of ['node:fs', '../../src/app', '../../src/config', '../../src/db', '../../src/realtime/gateway']) {
      jest.dontMock(module);
    }
  }
}, 30_000);

afterEach(async () => {
  clients.splice(0).forEach(socket => socket.disconnect());
  await clearDb();
});

afterAll(async () => {
  setRoomCloser(null);
  setRevoker(null);
  setTransport(null);
  if (ioServer) await ioServer.close();
  else if (server) await new Promise<void>(resolve => server?.close(() => resolve()));
  await stopDb();
  if (pemDirectory) fs.rmSync(pemDirectory, { recursive: true, force: true });
});

function socketClient(token?: string): Promise<Socket> {
  const socket = connect(url, {
    transports: ['websocket'], reconnection: false, timeout: 2_000,
    ca: cert.toString('utf8'), rejectUnauthorized: true, ...(token ? { auth: { token } } : {}),
  });
  clients.push(socket);
  return new Promise((resolve, reject) => {
    socket.once('connect', () => resolve(socket));
    socket.once('connect_error', reject);
  });
}

describe('NFR SEC-1: user data uses HTTPS on the configured production listener', () => {
  test('verifies TLS and reads protected data only with a valid session', async () => {
    const user = await createUserN(1);
    const rejected = await request(url).get('/api/users/me').ca(cert);
    expect(rejected.status).toBe(401);
    expect(rejected.body).toEqual({ error: 'Missing bearer token' });
    const accepted = await request(url).get('/api/users/me').ca(cert).set('Authorization', bearer(user.id));
    expect(accepted.status).toBe(200);
    expect(accepted.body).toMatchObject({ id: user.id, email: 'user1@example.com' });
    // Trust only this test certificate; do not disable TLS verification globally.
    expect(process.env.NODE_TLS_REJECT_UNAUTHORIZED).not.toBe('0');
  });

  test('plaintext requests to the TLS port cannot return user data', async () => {
    const result = await new Promise<{ code: number | string; body: string }>((resolve, reject) => {
      const call = httpGet(url.replace('https:', 'http:') + '/api/users/me', response => {
        let body = '';
        response.on('data', (chunk: Buffer) => { body += chunk.toString(); });
        response.on('end', () => resolve({ code: response.statusCode ?? 0, body }));
      });
      call.setTimeout(2_000, () => call.destroy(new Error('Plaintext probe timed out')));
      call.on('error', (error: NodeJS.ErrnoException) => {
        if (error.code === 'ECONNRESET') resolve({ code: error.code, body: '' });
        else reject(error);
      });
    });
    expect([400, 'ECONNRESET']).toContain(result.code);
    expect(result.body).not.toContain('email');
  });
});

describe('NFR SEC-2/SEC-3: WSS authenticates sessions and enforces meetup membership', () => {
  test.each(['missing', 'invalid', 'expired'] as const)('rejects a %s socket session', async kind => {
    let token: string | undefined = kind === 'missing' ? undefined : 'invalid-token';
    if (kind === 'expired') {
      const now = Date.now();
      const clock = jest.spyOn(Date, 'now').mockReturnValue(now - 8 * 24 * 60 * 60 * 1000);
      try { token = signSessionToken('507f1f77bcf86cd799439011'); } finally { clock.mockRestore(); }
    }
    await expect(socketClient(token)).rejects.toThrow('Unauthorized');
  });

  test('accepts the member over WSS but denies an outsider access to the meetup room', async () => {
    const member = await createUserN(1);
    const outsider = await createUserN(2);
    const group = await createGroup(member.id);
    const meetup = await createMeetup(group.id, member.id);
    const allowed = await socketClient(signSessionToken(member.id));
    const denied = await socketClient(signSessionToken(outsider.id));
    const room = `meetup:${meetup.id}`;
    await expect(allowed.timeout(2_000).emitWithAck('meetup:join', { meetupId: meetup.id })).resolves.toEqual({ ok: true });
    await expect(denied.timeout(2_000).emitWithAck('meetup:join', { meetupId: meetup.id })).resolves.toEqual({ ok: false, error: 'Not a member of this group' });
    expect(ioServer?.sockets.sockets.get(allowed.id ?? '')?.rooms.has(room)).toBe(true);
    expect(ioServer?.sockets.sockets.get(denied.id ?? '')?.rooms.has(room)).toBe(false);
    // A real membership change lets the same authenticated user join; the denial was role-based.
    await addMember(group.id, outsider.id);
    await expect(denied.timeout(2_000).emitWithAck('meetup:join', { meetupId: meetup.id })).resolves.toEqual({ ok: true });
  });
});
