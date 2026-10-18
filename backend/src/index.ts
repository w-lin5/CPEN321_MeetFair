import { createApp } from './app';
import { env } from './config';
import { connectDb, disconnectDb } from './db';
import { attachRealtime } from './realtime/gateway';

async function main(): Promise<void> {
  await connectDb(env.mongoUri);
  const server = createApp().listen(env.port, () => {
    console.log(`Server listening on port ${env.port}`);
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
