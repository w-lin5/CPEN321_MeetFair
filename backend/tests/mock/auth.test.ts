import { type LoginTicket, OAuth2Client } from 'google-auth-library';
import request from 'supertest';

import { createApp } from '../../src/app';
import { Unauthorized } from '../../src/common/errors';
import { verifySessionToken } from '../../src/common/session';
import { env } from '../../src/config';
import { idTokenClient } from '../../src/integrations/id-token.client';
import { UserModel } from '../../src/users-groups/models';
import { GOOGLE_PROFILE, bearer, createUser } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';

beforeAll(startDb);
afterEach(async () => {
  jest.restoreAllMocks();
  await clearDb();
});
afterAll(stopDb);

// Interface POST /api/auth/google
describe('Mocked: POST /api/auth/google', () => {
  // Input: { idToken: "valid" }
  // Expected status code: 200
  // Expected behavior: user is upserted; a session token for that user is issued
  // Expected output: { token, user: { id, email, name, photoUrl } }
  // Mocked behavior: idTokenClient.verifyIdToken resolves GOOGLE_PROFILE
  test('First sign-in creates the user and issues a usable session', async () => {
    jest.spyOn(idTokenClient, 'verifyIdToken').mockResolvedValue(GOOGLE_PROFILE);

    const res = await request(createApp()).post('/api/auth/google').send({ idToken: 'valid' });

    expect(res.status).toBe(200);
    expect(res.body.user).toMatchObject({ email: 'ada@example.com', name: 'Ada Lovelace', photoUrl: null });
    expect(res.body.user._id).toBeUndefined();
    expect(verifySessionToken(String(res.body.token))).toBe(res.body.user.id);
    expect(await UserModel.countDocuments()).toBe(1);
  });

  // Input: sign in, then use the returned token on GET /users/me with a *new* app instance
  // Expected status code: 200
  // Expected behavior: the issued token alone keeps the user signed in across restarts
  // Mocked behavior: verifyIdToken resolves GOOGLE_PROFILE
  test('Issued token authenticates later requests', async () => {
    jest.spyOn(idTokenClient, 'verifyIdToken').mockResolvedValue(GOOGLE_PROFILE);
    const signIn = await request(createApp()).post('/api/auth/google').send({ idToken: 'valid' });

    const me = await request(createApp()).get('/api/users/me').set('Authorization', `Bearer ${signIn.body.token}`);

    expect(me.status).toBe(200);
    expect(me.body.id).toBe(signIn.body.user.id);
  });

  // Input: second sign-in for the same Google account with a changed name
  // Expected status code: 200
  // Expected behavior: same user id, profile refreshed, no duplicate document
  // Mocked behavior: verifyIdToken resolves the old, then the updated profile
  test('Repeat sign-in reuses the account and refreshes the profile', async () => {
    const verify = jest.spyOn(idTokenClient, 'verifyIdToken');
    verify.mockResolvedValueOnce(GOOGLE_PROFILE);
    const first = await request(createApp()).post('/api/auth/google').send({ idToken: 'a' });
    verify.mockResolvedValueOnce({ ...GOOGLE_PROFILE, name: 'Ada King' });

    const second = await request(createApp()).post('/api/auth/google').send({ idToken: 'b' });

    expect(second.status).toBe(200);
    expect(second.body.user.id).toBe(first.body.user.id);
    expect(second.body.user.name).toBe('Ada King');
    expect(await UserModel.countDocuments()).toBe(1);
  });

  // Input: { idToken: "  padded  " }
  // Expected behavior: token is trimmed before being sent to Google
  // Mocked behavior: verifyIdToken resolves GOOGLE_PROFILE
  test('idToken is trimmed', async () => {
    const verify = jest.spyOn(idTokenClient, 'verifyIdToken').mockResolvedValue(GOOGLE_PROFILE);

    await request(createApp()).post('/api/auth/google').send({ idToken: '  padded  ' });

    expect(verify).toHaveBeenCalledWith('padded');
  });

  // Input: { idToken: "forged" }
  // Expected status code: 401
  // Expected behavior: no user created, no token issued
  // Expected output: { error: "Invalid Google ID token" }
  // Mocked behavior: verifyIdToken rejects with Unauthorized
  test('Google rejects the token', async () => {
    jest.spyOn(idTokenClient, 'verifyIdToken').mockRejectedValue(new Unauthorized('Invalid Google ID token'));

    const res = await request(createApp()).post('/api/auth/google').send({ idToken: 'forged' });

    expect(res.status).toBe(401);
    expect(res.body).toEqual({ error: 'Invalid Google ID token' });
    expect(await UserModel.countDocuments()).toBe(0);
  });

  // Input: { idToken: "valid" }
  // Expected status code: 500
  // Expected behavior: no stack or internal message is leaked
  // Expected output: { error: "Internal Server Error" }
  // Mocked behavior: verifyIdToken throws an unexpected Error
  test('Unexpected integration failure is a 500 without leaks', async () => {
    jest.spyOn(console, 'error').mockImplementation(() => undefined);
    jest.spyOn(idTokenClient, 'verifyIdToken').mockRejectedValue(new Error('secret internals'));

    const res = await request(createApp()).post('/api/auth/google').send({ idToken: 'valid' });

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
  });

  // Input: { idToken: "valid" }
  // Expected status code: 500
  // Expected behavior: a non-Error value is never echoed to the client
  // Expected output: { error: "Internal Server Error" }
  // Mocked behavior: verifyIdToken rejects with a plain string
  test('Non-Error failure is a 500 without leaks', async () => {
    const consoleError = jest.spyOn(console, 'error').mockImplementation(() => undefined);
    jest.spyOn(idTokenClient, 'verifyIdToken').mockRejectedValue('secret internals');

    const res = await request(createApp()).post('/api/auth/google').send({ idToken: 'valid' });

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
    expect(consoleError).toHaveBeenCalledWith('Unhandled error:', 'unknown');
  });

  // Input: { idToken: "valid" }
  // Expected status code: 500
  // Expected behavior: no token is returned if the user could not be saved
  // Mocked behavior: verifyIdToken resolves; UserModel.findOneAndUpdate rejects
  test('Database failure during upsert', async () => {
    jest.spyOn(console, 'error').mockImplementation(() => undefined);
    jest.spyOn(idTokenClient, 'verifyIdToken').mockResolvedValue(GOOGLE_PROFILE);
    jest.spyOn(UserModel, 'findOneAndUpdate').mockReturnValue({
      orFail: () => Promise.reject(new Error('db down')),
    } as unknown as ReturnType<typeof UserModel.findOneAndUpdate>);

    const res = await request(createApp()).post('/api/auth/google').send({ idToken: 'valid' });

    expect(res.status).toBe(500);
    expect(res.body.token).toBeUndefined();
  });
});

// Interface GET /api/users/me
describe('Mocked: GET /api/users/me', () => {
  // Input: valid token
  // Expected status code: 500
  // Expected output: { error: "Internal Server Error" }
  // Mocked behavior: UserModel.findById rejects
  test('Database failure', async () => {
    jest.spyOn(console, 'error').mockImplementation(() => undefined);
    const user = await createUser();
    jest.spyOn(UserModel, 'findById').mockRejectedValue(new Error('db down') as never);

    const res = await request(createApp()).get('/api/users/me').set('Authorization', bearer(user.id));

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
  });
});

// Interface POST /api/auth/google (Google library behind idTokenClient)
describe('Mocked: POST /api/auth/google with the Google library', () => {
  function ticket(payload: Record<string, unknown> | undefined): LoginTicket {
    return { getPayload: () => payload } as unknown as LoginTicket;
  }

  function mockTicket(payload: Record<string, unknown> | undefined) {
    jest.spyOn(OAuth2Client.prototype, 'verifyIdToken').mockResolvedValue(ticket(payload) as never);
  }

  // Input: { idToken: "valid" }
  // Expected status code: 200
  // Expected behavior: verification uses the configured client id as audience; the full profile is stored
  // Expected output: { token, user } with email, name and photoUrl from Google
  // Mocked behavior: OAuth2Client.verifyIdToken resolves a payload with every field
  test('Full Google profile is accepted', async () => {
    const verify = jest.spyOn(OAuth2Client.prototype, 'verifyIdToken')
      .mockResolvedValue(ticket({ sub: 'g-1', email: 'a@x.com', name: 'A', picture: 'http://img/a.png' }) as never);

    const res = await request(createApp()).post('/api/auth/google').send({ idToken: 'valid' });

    expect(res.status).toBe(200);
    expect(res.body.user).toMatchObject({ email: 'a@x.com', name: 'A', photoUrl: 'http://img/a.png' });
    expect(verify).toHaveBeenCalledWith({ idToken: 'valid', audience: env.googleClientId });
  });

  // Input: { idToken: "valid" }
  // Expected status code: 200
  // Expected behavior: missing name falls back to the email, missing picture to null
  // Mocked behavior: OAuth2Client.verifyIdToken resolves a payload with only sub and email
  test('Optional Google fields fall back to defaults', async () => {
    mockTicket({ sub: 'g-1', email: 'a@x.com' });

    const res = await request(createApp()).post('/api/auth/google').send({ idToken: 'valid' });

    expect(res.status).toBe(200);
    expect(res.body.user).toMatchObject({ email: 'a@x.com', name: 'a@x.com', photoUrl: null });
  });

  // Input: { idToken: "valid" }
  // Expected status code: 401
  // Expected behavior: tokens without usable claims are rejected; no user is created
  // Expected output: { error: "Invalid Google ID token" }
  // Mocked behavior: OAuth2Client.verifyIdToken resolves an empty / incomplete payload
  test.each([[undefined], [{ email: 'a@x.com' }], [{ sub: 'g-1' }]])('Payload %j is rejected', async (payload) => {
    mockTicket(payload);

    const res = await request(createApp()).post('/api/auth/google').send({ idToken: 'valid' });

    expect(res.status).toBe(401);
    expect(res.body).toEqual({ error: 'Invalid Google ID token' });
    expect(await UserModel.countDocuments()).toBe(0);
  });

  // Input: { idToken: "valid" }
  // Expected status code: 401
  // Expected behavior: any library failure becomes a generic 401 without leaking the cause
  // Mocked behavior: OAuth2Client.verifyIdToken rejects
  test('Library failure is a 401', async () => {
    jest.spyOn(OAuth2Client.prototype, 'verifyIdToken').mockRejectedValue(new Error('Wrong recipient') as never);

    const res = await request(createApp()).post('/api/auth/google').send({ idToken: 'valid' });

    expect(res.status).toBe(401);
    expect(res.body).toEqual({ error: 'Invalid Google ID token' });
  });
});
