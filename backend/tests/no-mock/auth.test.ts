import request from 'supertest';

import { createApp } from '../../src/app';
import { signSessionToken } from '../../src/common/session';
import { UserModel } from '../../src/users-groups/models';
import { bearer, createUser } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';

beforeAll(startDb);
afterEach(clearDb);
afterAll(stopDb);

// Interface POST /api/auth/google
describe('Unmocked: POST /api/auth/google', () => {
  // Input: body without idToken
  // Expected status code: 400
  // Expected behavior: rejected; no user created
  // Expected output: { error, details: [...] }
  test('Missing idToken', async () => {
    const res = await request(createApp()).post('/api/auth/google').send({});

    expect(res.status).toBe(400);
    expect(res.body.details).toHaveLength(1);
    expect(await UserModel.countDocuments()).toBe(0);
  });

  // Input: idToken that is not a string / blank / over the length limit
  // Expected status code: 400
  test.each([[123], ['   '], ['x'.repeat(5000)]])('Invalid idToken %#', async (idToken) => {
    const res = await request(createApp()).post('/api/auth/google').send({ idToken });

    expect(res.status).toBe(400);
  });

  // Input: syntactically invalid JSON
  // Expected status code: 400
  // Expected output: { error: "Malformed JSON body" }
  test('Malformed JSON', async () => {
    const res = await request(createApp())
      .post('/api/auth/google')
      .set('Content-Type', 'application/json')
      .send('{"idToken":');

    expect(res.status).toBe(400);
    expect(res.body).toEqual({ error: 'Malformed JSON body' });
  });

  // Input: idToken that is not a JWT
  // Expected status code: 401
  // Expected behavior: Google verification fails; no session issued, no user created
  // Expected output: { error: "Invalid Google ID token" }
  test('Garbage ID token is rejected', async () => {
    const res = await request(createApp()).post('/api/auth/google').send({ idToken: 'not-a-jwt' });

    expect(res.status).toBe(401);
    expect(res.body).toEqual({ error: 'Invalid Google ID token' });
    expect(res.body.token).toBeUndefined();
    expect(await UserModel.countDocuments()).toBe(0);
  });
});

// Interface GET /api/users/me
describe('Unmocked: GET /api/users/me', () => {
  // Input: valid session token for an existing user
  // Expected status code: 200
  // Expected output: the user with `id` and without `_id` / `__v`
  test('Returns the signed-in user', async () => {
    const user = await createUser();
    const res = await request(createApp()).get('/api/users/me').set('Authorization', bearer(user.id));

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ id: user.id, email: 'ada@example.com', name: 'Ada Lovelace' });
    expect(res.body._id).toBeUndefined();
    expect(res.body.__v).toBeUndefined();
  });

  // Input: the same token sent to two independent app instances (simulates the server
  //        restarting while the phone keeps its stored token)
  // Expected status code: 200 for both
  // Expected behavior: the session lives in the token, not in server memory
  test('Token survives a server restart', async () => {
    const user = await createUser();
    const token = bearer(user.id);

    const before = await request(createApp()).get('/api/users/me').set('Authorization', token);
    const after = await request(createApp()).get('/api/users/me').set('Authorization', token);

    expect(before.status).toBe(200);
    expect(after.status).toBe(200);
    expect(after.body.id).toBe(user.id);
  });

  // Input: no Authorization header
  // Expected status code: 401
  // Expected output: { error: "Missing bearer token" }
  test('No token', async () => {
    const res = await request(createApp()).get('/api/users/me');

    expect(res.status).toBe(401);
    expect(res.body).toEqual({ error: 'Missing bearer token' });
  });

  // Input: wrong scheme / empty token / tampered token
  // Expected status code: 401
  test.each([
    ['Basic abc'],
    ['Bearer '],
    ['Bearer'],
    [`Bearer ${signSessionToken('507f1f77bcf86cd799439011')}x`],
  ])('Bad Authorization header %j', async (header) => {
    const res = await request(createApp()).get('/api/users/me').set('Authorization', header);

    expect(res.status).toBe(401);
  });

  // Input: token older than its TTL (the user has been away > 7 days)
  // Expected status code: 401
  // Expected behavior: the app must send the user back to sign-in
  test('Expired token', async () => {
    const user = await createUser();
    const eightDaysMs = 8 * 24 * 60 * 60 * 1000;
    const spy = jest.spyOn(Date, 'now').mockReturnValue(Date.now() - eightDaysMs);
    const token = signSessionToken(user.id);
    spy.mockRestore();

    const res = await request(createApp()).get('/api/users/me').set('Authorization', `Bearer ${token}`);

    expect(res.status).toBe(401);
  });

  // Input: valid token whose user no longer exists
  // Expected status code: 404
  // Expected output: { error: "User not found" }
  test('Deleted user', async () => {
    const user = await createUser();
    const header = bearer(user.id);
    await UserModel.deleteMany({});

    const res = await request(createApp()).get('/api/users/me').set('Authorization', header);

    expect(res.status).toBe(404);
    expect(res.body).toEqual({ error: 'User not found' });
  });
});
