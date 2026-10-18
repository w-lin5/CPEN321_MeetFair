const ENV_KEYS = ['NODE_ENV', 'PORT', 'MONGODB_URI', 'JWT_SECRET', 'GOOGLE_CLIENT_ID'] as const;

type ConfigModule = typeof import('../../src/config');

const savedEnv = new Map<string, string | undefined>();

// Load config.ts fresh with exactly the given environment (and without reading backend/.env)
function loadConfig(vars: Partial<Record<(typeof ENV_KEYS)[number], string>>): ConfigModule {
  for (const key of ENV_KEYS) {
    Reflect.deleteProperty(process.env, key);
  }
  for (const [key, value] of Object.entries(vars)) {
    process.env[key] = value;
  }

  let loaded: ConfigModule | undefined;
  jest.isolateModules(() => {
    jest.doMock('dotenv/config', () => ({}));
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    loaded = require('../../src/config') as ConfigModule;
  });

  if (loaded === undefined) throw new Error('config did not load');
  return loaded;
}

beforeEach(() => {
  for (const key of ENV_KEYS) {
    savedEnv.set(key, process.env[key]);
  }
});

afterEach(() => {
  for (const key of ENV_KEYS) {
    const value = savedEnv.get(key);
    if (value === undefined) {
      Reflect.deleteProperty(process.env, key);
    } else {
      process.env[key] = value;
    }
  }
  jest.dontMock('dotenv/config');
});

// Interface: config (env + tuning)
describe('Mocked: config', () => {
  // Input: NODE_ENV=test with no other variables
  // Expected behavior: test defaults are supplied; PORT defaults to 3000
  // Expected output: env filled with test defaults
  test('Test environment falls back to defaults', () => {
    const { env } = loadConfig({ NODE_ENV: 'test' });

    expect(env).toEqual({
      port: 3000,
      mongoUri: 'mongodb://localhost:27017/test',
      jwtSecret: 'test-secret',
      googleClientId: 'test-client-id',
    });
  });

  // Input: NODE_ENV=test with empty-string variables
  // Expected behavior: empty strings are treated as unset
  test('Empty strings count as unset', () => {
    const { env } = loadConfig({
      NODE_ENV: 'test',
      PORT: '',
      MONGODB_URI: '',
      JWT_SECRET: '',
      GOOGLE_CLIENT_ID: '',
    });

    expect(env.port).toBe(3000);
    expect(env.jwtSecret).toBe('test-secret');
  });

  // Input: every variable provided, outside the test environment
  // Expected behavior: provided values are used verbatim
  test('Provided values win over defaults', () => {
    const { env } = loadConfig({
      NODE_ENV: 'production',
      PORT: '8080',
      MONGODB_URI: 'mongodb://db:27017/meetfair',
      JWT_SECRET: 'prod-secret',
      GOOGLE_CLIENT_ID: 'prod-client',
    });

    expect(env).toEqual({
      port: 8080,
      mongoUri: 'mongodb://db:27017/meetfair',
      jwtSecret: 'prod-secret',
      googleClientId: 'prod-client',
    });
  });

  // Input: outside the test environment, a required variable is missing
  // Expected behavior: loading the config throws, naming the variable
  test.each([['MONGODB_URI'], ['JWT_SECRET'], ['GOOGLE_CLIENT_ID']])('Missing %s is fatal in production', (missing) => {
    const vars = {
      NODE_ENV: 'production',
      MONGODB_URI: 'mongodb://db:27017/meetfair',
      JWT_SECRET: 'prod-secret',
      GOOGLE_CLIENT_ID: 'prod-client',
    };
    Reflect.deleteProperty(vars, missing);

    expect(() => loadConfig(vars)).toThrow(`Missing required env var ${missing}`);
  });

  // Input: PORT that is not a number / below 1 / above 65535
  // Expected behavior: loading the config throws
  test.each([['abc'], ['0'], ['65536'], ['-5']])('Invalid PORT %j is rejected', (port) => {
    expect(() => loadConfig({ NODE_ENV: 'test', PORT: port })).toThrow(`Invalid PORT: ${port}`);
  });

  // Input: PORT at the lowest and highest valid values
  // Expected behavior: accepted
  test.each([[1], [65535]])('PORT %p is accepted', (port) => {
    expect(loadConfig({ NODE_ENV: 'test', PORT: String(port) }).env.port).toBe(port);
  });
});
