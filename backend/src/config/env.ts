import 'dotenv/config';

const isTest = process.env.NODE_ENV === 'test';

function requireValue(name: string, value: string | undefined, testDefault: string): string {
  if (value !== undefined && value !== '') return value;
  if (isTest) return testDefault;
  throw new Error(`Missing required env var ${name}`);
}

const rawPort = process.env.PORT;
const port = rawPort === undefined || rawPort === '' ? 3000 : Number.parseInt(rawPort, 10);
if (Number.isNaN(port) || port < 1 || port > 65535) {
  throw new Error(`Invalid PORT: ${rawPort}`);
}

// TODO: change test-secret and test-client-id, not sure what to put rn as default to not expose anythnig
export const env = {
  port,
  mongoUri: requireValue('MONGODB_URI', process.env.MONGODB_URI, 'mongodb://localhost:27017/test'),
  jwtSecret: requireValue('JWT_SECRET', process.env.JWT_SECRET, 'test-secret'),
  googleClientId: requireValue('GOOGLE_CLIENT_ID', process.env.GOOGLE_CLIENT_ID, 'test-client-id'),
} as const;
