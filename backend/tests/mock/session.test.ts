import { Unauthorized } from '../../src/common/errors';
import { signSessionToken, verifySessionToken } from '../../src/common/session';
import { tuning } from '../../src/config';

const USER_ID = '507f1f77bcf86cd799439011';
const T0 = 1_700_000_000_000; // aligned to a whole second
const TTL_MS = tuning.jwtTtlSeconds * tuning.msPerSecond;

afterEach(() => jest.restoreAllMocks());

// Interface: signSessionToken / verifySessionToken
describe('Mocked: session token expiry', () => {
  // Input: token verified 1 ms before the TTL elapses (i.e., "app reopened days later")
  // Expected behavior: still valid, so the user stays signed in
  // Expected output: USER_ID
  test('Valid until just before expiry', () => {
    const now = jest.spyOn(Date, 'now').mockReturnValue(T0);
    const token = signSessionToken(USER_ID);
    now.mockReturnValue(T0 + TTL_MS - 1);

    expect(verifySessionToken(token)).toBe(USER_ID);
  });

  // Input: token verified exactly at the TTL
  // Expected behavior: rejected with Unauthorized
  test('Rejected at expiry', () => {
    const now = jest.spyOn(Date, 'now').mockReturnValue(T0);
    const token = signSessionToken(USER_ID);
    now.mockReturnValue(T0 + TTL_MS);

    expect(() => verifySessionToken(token)).toThrow(Unauthorized);
  });
});
