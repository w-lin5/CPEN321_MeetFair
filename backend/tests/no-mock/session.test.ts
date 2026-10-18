import { createHmac } from 'node:crypto';

import { Unauthorized } from '../../src/common/errors';
import { signSessionToken, verifySessionToken } from '../../src/common/session';
import { env, tuning } from '../../src/config';

const USER_ID = '507f1f77bcf86cd799439011';
const T0 = 1_700_000_000_000; // aligned to a whole second

function b64(value: unknown): string {
  return Buffer.from(typeof value === 'string' ? value : JSON.stringify(value)).toString('base64url');
}

function forge(payload: unknown, secret: string = env.jwtSecret, header: unknown = { alg: 'HS256', typ: 'JWT' }) {
  const unsigned = `${b64(header)}.${b64(payload)}`;
  return `${unsigned}.${createHmac('sha256', secret).update(unsigned).digest('base64url')}`;
}

// Interface: signSessionToken / verifySessionToken
describe('Unmocked: session token', () => {
  // Input: token signed for USER_ID, verified immediately
  // Expected behavior: subject is recovered
  // Expected output: USER_ID
  test('Round trip returns the user id', () => {
    expect(verifySessionToken(signSessionToken(USER_ID))).toBe(USER_ID);
  });

  // Input: payload re-encoded with another subject, original signature kept
  // Expected behavior: signature check fails
  test('Tampered payload is rejected', () => {
    const [header, , signature] = signSessionToken(USER_ID).split('.');
    const forgedPayload = b64({ sub: 'attacker', exp: Math.floor(T0 / tuning.msPerSecond) + 1e6 });

    expect(() => verifySessionToken(`${header}.${forgedPayload}.${signature}`)).toThrow(Unauthorized);
  });

  // Input: correctly shaped token signed with a different secret
  // Expected behavior: rejected
  test('Wrong signing secret is rejected', () => {
    const token = forge({ sub: USER_ID, exp: Math.floor(Date.now() / tuning.msPerSecond) + 60 }, 'other-secret');

    expect(() => verifySessionToken(token)).toThrow(Unauthorized);
  });

  // Input: validly signed token with an unexpected header (alg confusion)
  // Expected behavior: rejected
  test('Unexpected header is rejected', () => {
    const token = forge({ sub: USER_ID, exp: Math.floor(Date.now() / tuning.msPerSecond) + 60 }, env.jwtSecret, {
      alg: 'none',
      typ: 'JWT',
    });

    expect(() => verifySessionToken(token)).toThrow(Unauthorized);
  });

  // Input: wrong segment counts and empty string
  // Expected behavior: rejected
  test.each(['', 'abc', 'a.b', 'a.b.c.d'])('Malformed token %j is rejected', (token) => {
    expect(() => verifySessionToken(token)).toThrow(Unauthorized);
  });

  // Input: validly signed tokens whose claims are malformed
  // Expected behavior: rejected
  test.each([
    ['null payload', null],
    ['non-object payload', 5],
    ['unparseable payload', 'not json'],
    ['numeric sub', { sub: 5, exp: 9_999_999_999 }],
    ['missing exp', { sub: USER_ID }],
    ['string exp', { sub: USER_ID, exp: 'soon' }],
  ])('Malformed claims (%s) are rejected', (_name, payload) => {
    expect(() => verifySessionToken(forge(payload))).toThrow(Unauthorized);
  });
});
