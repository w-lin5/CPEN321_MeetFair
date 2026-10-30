type Callback = () => void;
type Signal = 'SIGINT' | 'SIGTERM';

interface FakeServer {
  close: jest.Mock<void, [Callback]>;
}

interface FakeIo {
  close: jest.Mock<Promise<void>, [Callback]>;
}

// Which of backend/certs/fullchain.pem and privkey.pem exist
interface CertFiles {
  cert: boolean;
  key: boolean;
}

interface Harness {
  connectDb: jest.Mock<Promise<void>, [string]>;
  disconnectDb: jest.Mock<Promise<void>, []>;
  listen: jest.Mock<FakeServer, [number, Callback]>;
  createHttpsServer: jest.Mock<{ listen: Harness['listen'] }, [unknown, unknown]>;
  app: { listen: Harness['listen'] };
  server: FakeServer;
  io: FakeIo;
  attachRealtime: jest.Mock<FakeIo, [FakeServer]>;
  handlers: Map<string, Callback>;
  exit: jest.SpyInstance;
  consoleLog: jest.SpyInstance;
  consoleError: jest.SpyInstance;
}

const PORT = 4321;
const URI = 'mongodb://example/meetfair';

// Let pending promise callbacks (the `main().catch` chain, `disconnectDb().then`) run
async function flush(): Promise<void> {
  await new Promise((resolve) => setImmediate(resolve));
}

// Load index.ts fresh with its collaborators replaced by fakes; no certificate files unless `certs` says so
function boot(
  overrides: Partial<Pick<Harness, 'connectDb' | 'disconnectDb'>> = {},
  certs: CertFiles = { cert: false, key: false },
): Harness {
  // Fake server/listen invoke their callbacks synchronously so no real port is opened
  const handlers = new Map<string, Callback>();
  const server: FakeServer = { close: jest.fn((cb: Callback) => { cb(); }) };
  const listen = jest.fn((_port: number, cb: Callback): FakeServer => {
    cb();
    return server;
  });
  // Closing Socket.IO also closes the HTTP server it is attached to
  const io: FakeIo = {
    close: jest.fn((cb: Callback) => {
      server.close(cb);
      return Promise.resolve();
    }),
  };
  const attachRealtime = jest.fn((_server: FakeServer): FakeIo => io);
  const app = { listen };
  // The HTTPS server listens through the same fake, so both protocols hand back `server`
  const createHttpsServer = jest.fn<{ listen: typeof listen }, [unknown, unknown]>(() => ({ listen }));
  const fileExists = (file: string) => (file.endsWith('fullchain.pem') ? certs.cert : certs.key);
  const readPem = (file: string) => Buffer.from(`pem of ${file.endsWith('fullchain.pem') ? 'cert' : 'key'}`);
  const connectDb = overrides.connectDb ?? jest.fn().mockResolvedValue(undefined);
  const disconnectDb = overrides.disconnectDb ?? jest.fn().mockResolvedValue(undefined);

  // Capture signal handlers instead of attaching them to the real process; stub exit so tests survive it
  jest.spyOn(process, 'on').mockImplementation(((event: string, handler: Callback) => {
    handlers.set(event, handler);
    return process;
  }) as never);
  const exit = jest.spyOn(process, 'exit').mockImplementation((() => undefined) as never);
  const consoleLog = jest.spyOn(console, 'log').mockImplementation(() => undefined);
  const consoleError = jest.spyOn(console, 'error').mockImplementation(() => undefined);

  // isolateModules gives index.ts a fresh module registry, so its top-level bootstrap re-runs on every call
  jest.isolateModules(() => {
    jest.doMock('node:fs', () => ({ existsSync: fileExists, readFileSync: readPem }));
    jest.doMock('node:https', () => ({ createServer: createHttpsServer }));
    jest.doMock('../../src/app', () => ({ createApp: () => app }));
    jest.doMock('../../src/config', () => ({ env: { port: PORT, mongoUri: URI } }));
    jest.doMock('../../src/db', () => ({ connectDb, disconnectDb }));
    jest.doMock('../../src/realtime/gateway', () => ({ attachRealtime }));
    require('../../src/index');
  });

  return {
    connectDb,
    disconnectDb,
    listen,
    createHttpsServer,
    app,
    server,
    io,
    attachRealtime,
    handlers,
    exit,
    consoleLog,
    consoleError,
  };
}

afterEach(() => {
  jest.restoreAllMocks();
  jest.dontMock('node:fs');
  jest.dontMock('node:https');
  jest.dontMock('../../src/app');
  jest.dontMock('../../src/config');
  jest.dontMock('../../src/db');
  jest.dontMock('../../src/realtime/gateway');
});

// Interface: server bootstrap (src/index.ts)
describe('Mocked: index bootstrap', () => {
  // Mocked behavior: app, config, DB, Socket.IO gateway, fs, https, process.on/exit and console are fakes; connectDb resolves; fs reports no PEM files
  // Input: normal startup, no certificate files in backend/certs/
  // Expected behavior: DB connected with the configured URI, app listens over plain HTTP on the configured port,
  //                    Socket.IO is attached to the HTTP server, startup is logged,
  //                    SIGINT and SIGTERM handlers are registered
  test('Starts the server and registers shutdown handlers', async () => {
    const h = boot();
    await flush();

    expect(h.connectDb).toHaveBeenCalledWith(URI);
    expect(h.listen).toHaveBeenCalledWith(PORT, expect.any(Function));
    expect(h.createHttpsServer).not.toHaveBeenCalled();
    expect(h.attachRealtime).toHaveBeenCalledWith(h.server);
    expect(h.consoleLog).toHaveBeenCalledWith(`Server listening on port ${PORT} (http)`);
    expect([...h.handlers.keys()].sort()).toEqual(['SIGINT', 'SIGTERM']);
    expect(h.exit).not.toHaveBeenCalled();
  });

  // Mocked behavior: app, config, DB, Socket.IO gateway, fs, https, process.on/exit and console are fakes; fs reports both PEM files and returns stub contents
  // Input: startup with backend/certs/fullchain.pem and privkey.pem present
  // Expected behavior: an HTTPS server is built from the PEM files around the Express app, listens on the
  //                    configured port, gets Socket.IO attached, and startup is logged as https
  test('Serves HTTPS when a certificate and key are installed', async () => {
    const h = boot({}, { cert: true, key: true });
    await flush();

    expect(h.createHttpsServer).toHaveBeenCalledWith(
      { cert: Buffer.from('pem of cert'), key: Buffer.from('pem of key') },
      h.app,
    );
    expect(h.listen).toHaveBeenCalledWith(PORT, expect.any(Function));
    expect(h.attachRealtime).toHaveBeenCalledWith(h.server);
    expect(h.consoleLog).toHaveBeenCalledWith(`Server listening on port ${PORT} (https)`);
    expect(h.exit).not.toHaveBeenCalled();
  });

  // Mocked behavior: app, config, DB, Socket.IO gateway, fs, https, process.on/exit and console are fakes; fs reports only one of the two PEM files
  // Input: startup with only one of the two PEM files present (certificate only / key only)
  // Expected behavior: startup fails with a message naming both files instead of silently falling back to HTTP;
  //                    the server never listens; exit code 1
  test.each<[string, CertFiles]>([
    ['certificate only', { cert: true, key: false }],
    ['key only', { cert: false, key: true }],
  ])('Half-installed TLS (%s) is fatal', async (_label, certs) => {
    const h = boot({}, certs);
    await flush();

    expect(h.consoleError).toHaveBeenCalledWith(
      'Fatal startup error:',
      'HTTPS needs both certs/fullchain.pem and certs/privkey.pem',
    );
    expect(h.listen).not.toHaveBeenCalled();
    expect(h.exit).toHaveBeenCalledWith(1);
  });

  // Mocked behavior: app, config, DB, Socket.IO gateway, fs, https, process.on/exit and console are fakes; connectDb and disconnectDb resolve; process.on captures the signal handlers
  // Input: SIGINT / SIGTERM after a successful startup, DB disconnects cleanly
  // Expected behavior: Socket.IO and the server are closed, DB disconnected, process exits with 0
  test.each<Signal>(['SIGINT', 'SIGTERM'])('%s shuts down gracefully', async (signal) => {
    const h = boot();
    await flush();

    h.handlers.get(signal)?.();
    await flush();

    expect(h.io.close).toHaveBeenCalledTimes(1);
    expect(h.server.close).toHaveBeenCalledTimes(1);
    expect(h.disconnectDb).toHaveBeenCalledTimes(1);
    expect(h.exit).toHaveBeenCalledWith(0);
  });

  // Mocked behavior: app, config, DB, Socket.IO gateway, fs, https, process.on/exit and console are fakes; disconnectDb rejects with an Error
  // Input: shutdown signal, but the DB fails to disconnect
  // Expected behavior: process exits with 1
  test('Shutdown exits with 1 when disconnect fails', async () => {
    const h = boot({ disconnectDb: jest.fn().mockRejectedValue(new Error('stuck')) });
    await flush();

    h.handlers.get('SIGTERM')?.();
    await flush();

    expect(h.exit).toHaveBeenCalledWith(1);
    expect(h.exit).not.toHaveBeenCalledWith(0);
  });

  // Mocked behavior: app, config, DB, Socket.IO gateway, fs, https, process.on/exit and console are fakes; connectDb rejects with Error("no db")
  // Input: DB connection fails with an Error
  // Expected behavior: only the error message is logged; the server never listens; exit code 1
  test('Startup failure with an Error exits with 1', async () => {
    const h = boot({ connectDb: jest.fn().mockRejectedValue(new Error('no db')) });
    await flush();

    expect(h.consoleError).toHaveBeenCalledWith('Fatal startup error:', 'no db');
    expect(h.listen).not.toHaveBeenCalled();
    expect(h.exit).toHaveBeenCalledWith(1);
  });

  // Mocked behavior: app, config, DB, Socket.IO gateway, fs, https, process.on/exit and console are fakes; connectDb rejects with the string "weird"
  // Input: DB connection fails with a non-Error value
  // Expected behavior: a generic message is logged instead of the raw value; exit code 1
  test('Startup failure with a non-Error exits with 1', async () => {
    const h = boot({ connectDb: jest.fn().mockRejectedValue('weird') });
    await flush();

    expect(h.consoleError).toHaveBeenCalledWith('Fatal startup error:', 'unknown');
    expect(h.exit).toHaveBeenCalledWith(1);
  });
});
