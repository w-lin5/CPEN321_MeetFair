import { createHmac, timingSafeEqual } from 'node:crypto';

import { env, tuning } from '../config';
import { Unauthorized } from './errors';

// Pre-encode the fixed HS256 header to avoid rebuilding header for each token
const HEADER = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');

function mac(data: string): Buffer {
  // Authenticate token contents using server-side signing secret
  return createHmac('sha256', env.jwtSecret).update(data).digest();
}

function parseClaims(payload: string): { sub: string; exp: number } | null {
  try {
    const raw: unknown = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));

    // Validate decoded payload before accessing
    if (typeof raw !== 'object' || raw === null) return null;

    const claims = new Map(Object.entries(raw));
    const sub = claims.get('sub');
    const exp = claims.get('exp');
    
    return typeof sub === 'string' && typeof exp === 'number' ? { sub, exp } : null;
  } catch {
    return null;
  }
}

export function signSessionToken(userId: string): string {
  const now = Math.floor(Date.now() / tuning.msPerSecond);
  const payload = Buffer.from(
    JSON.stringify({ sub: userId, iat: now, exp: now + tuning.jwtTtlSeconds }),
  ).toString('base64url');

  // Sign encoded header and payload according to JWT compact format
  const unsigned = `${HEADER}.${payload}`;
  return `${unsigned}.${mac(unsigned).toString('base64url')}`;
}

export function verifySessionToken(token: string): string {
  const [header, payload, signature, extra] = token.split('.');

  // Require exactly three segments and expected signing header
  if (header !== HEADER || payload === undefined || signature === undefined || extra !== undefined) {
    throw new Unauthorized('Invalid session token');
  }

  const expected = mac(`${header}.${payload}`);
  const actual = Buffer.from(signature, 'base64url');

  // Verify signature before trusting the payload
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) {
    throw new Unauthorized('Invalid session token');
  }

  const claims = parseClaims(payload);

  // Reject malformed claims and expired tokens
  if (claims === null || claims.exp * tuning.msPerSecond <= Date.now()) {
    throw new Unauthorized('Invalid or expired session token');
  }
  
  return claims.sub;
}
