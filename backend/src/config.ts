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

// Every tunable number lives here (no magic numbers elsewhere) 
export const tuning = {
  jwtTtlSeconds: 7 * 24 * 60 * 60,
  msPerSecond: 1000,
  idTokenMaxLength: 4096,

  // Invite codes: unambiguous alphabet (no 0/O, 1/I/L)
  inviteCodeLength: 6,
  inviteCodeAlphabet: 'ABCDEFGHJKMNPQRSTUVWXYZ23456789',
  inviteCodeMaxAttempts: 5,
  inviteCodeInputMaxLength: 16,
  mongoDuplicateKeyCode: 11000,

  // Input limits
  groupNameMaxLength: 60,
  meetupTitleMaxLength: 100,
  locationLabelMaxLength: 120,
  maxPreferences: 5,
  maxLatitude: 90,
  maxLongitude: 180,

  // Profile
  displayNameMaxLength: 60,

  // Fair Credit (seconds of mode-normalized travel burden above the group average, summed over meetups)
  initialFairCredit: 0,
  // Upper bound on a single settled trip (one day), to reject obviously broken measurements
  maxTripSeconds: 24 * 60 * 60,
  // Mode normalization: raw seconds x multiplier [TBD: all 1.0 until the team decides otherwise]
  walkBurdenMultiplier: 1,
  transitBurdenMultiplier: 1,
  driveBurdenMultiplier: 1,
  // Vote weight = base + perHour x (Fair Credit in hours), clamped to [min, max]
  voteWeightBase: 1,
  voteWeightPerHour: 0.1,
  minVoteWeight: 1,
  maxVoteWeight: 1.5,
  secondsPerHour: 60 * 60,

  // Recommendation: the top candidates the group votes on
  recommendationCount: 3,
  placeIdMaxLength: 256,
  placeNameMaxLength: 200,
  placeAddressMaxLength: 300,

  // Voting: weighted totals closer than this count as a tie (guards against floating-point drift)
  voteWeightEpsilon: 1e-9,

  // Google Calendar
  // Google OAuth access tokens are short-lived JWTs; 2048 chars is a safe upper bound
  oauthAccessTokenMaxLength: 2048,
  // Meetups only store a start time; the calendar event gets this length [TBD]
  calendarEventDurationMinutes: 60,
  secondsPerMinute: 60,
} as const;
