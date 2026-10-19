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
    console.log(`Server listening on port ${env.port} (${protocol})`);
  });
  const io = attachRealtime(server);

  for (const signal of ['SIGINT', 'SIGTERM'] as const) {
    process.on(signal, () => {
      // io.close also closes the HTTP server; its callback fires once both are down
      void io.close(() => {
        disconnectDb().then(
          () => process.exit(0),
          () => process.exit(1),
        );
      });
    });
  }
}

main().catch((err: unknown) => {
  console.error('Fatal startup error:', err instanceof Error ? err.message : 'unknown');
  process.exit(1);
});
