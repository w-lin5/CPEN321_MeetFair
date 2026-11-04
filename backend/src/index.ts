import { existsSync, readFileSync } from 'node:fs';
import { createServer as createHttpsServer } from 'node:https';
import path from 'node:path';

import { createApp } from './app';
import { env } from './config';
import { connectDb, disconnectDb } from './db';
import { attachRealtime } from './realtime/gateway';

// HTTPS is served when both PEM files are present (e.g. copied from Let's Encrypt); otherwise plain HTTP
const TLS_CERT_FILE = path.join(__dirname, '..', 'certs', 'fullchain.pem');
const TLS_KEY_FILE = path.join(__dirname, '..', 'certs', 'privkey.pem');

// Starts the REST server on env.port, over TLS when a certificate is installed; Socket.IO attaches to the same server
function listen(onListening: (protocol: string) => void) {
  const hasCert = existsSync(TLS_CERT_FILE);
  if (hasCert !== existsSync(TLS_KEY_FILE)) {
    throw new Error('HTTPS needs both certs/fullchain.pem and certs/privkey.pem');
  }
  if (!hasCert) return createApp().listen(env.port, () => { onListening('http'); });

  const tls = { cert: readFileSync(TLS_CERT_FILE), key: readFileSync(TLS_KEY_FILE) };
  return createHttpsServer(tls, createApp()).listen(env.port, () => { onListening('https'); });
}

async function main(): Promise<void> {
  await connectDb(env.mongoUri);
  const server = listen((protocol) => {
    console.info(`Server listening on port ${env.port} (${protocol})`);
  });
  const io = attachRealtime(server);

  // Setting exitCode (instead of process.exit) lets Node exit on its own once the server and DB are closed
  const fail = () => { process.exitCode = 1; };
  for (const signal of ['SIGINT', 'SIGTERM'] as const) {
    process.on(signal, () => {
      // io.close also closes the HTTP server; its callback fires once both are down
      io.close(() => {
        disconnectDb().then(() => { process.exitCode = 0; }, fail);
      }).catch(fail);
    });
  }
}

main().catch((err: unknown) => {
  console.error('Fatal startup error:', err instanceof Error ? err.message : 'unknown');
  process.exitCode = 1;
  // Startup can fail after the DB connected; close it so the open connection does not keep the process alive
  disconnectDb().catch(() => undefined);
});
