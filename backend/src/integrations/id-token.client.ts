import { OAuth2Client } from 'google-auth-library';

import { Unauthorized } from '../common/errors';
import { env } from '../config';

export interface GoogleProfile {
  sub: string;
  email: string;
  name: string;
  picture: string | null;
}

// Reuse OAuth client across different verification requests
const client = new OAuth2Client();

async function verifyIdToken(idToken: string): Promise<GoogleProfile> {
  try {
    // Verify token's signature, validity, and intended audience
    const ticket = await client.verifyIdToken({ idToken, audience: env.googleClientId });
    const payload = ticket.getPayload();

    if (payload?.sub === undefined || payload.email === undefined) {
      throw new Unauthorized('Invalid Google ID token');
    }

    // Normalize optional Google profile fields for downstream services
    return {
      sub: payload.sub,
      email: payload.email,
      name: payload.name ?? payload.email,
      picture: payload.picture ?? null,
    };
  } catch (err) {
    if (err instanceof Unauthorized) throw err;
    throw new Unauthorized('Invalid Google ID token');
  }
}

export const idTokenClient = { verifyIdToken };
