import { createApp } from './app';
import { env } from './config';
import { connectDb, disconnectDb } from './db';
import { CalendarEventModel } from './integrations/calendar-event.model';

async function main(): Promise<void> {
  await connectDb(env.mongoUri);
  await CalendarEventModel.syncIndexes();
  const server = createApp().listen(env.port, () => {
    console.log(`Server listening on port ${env.port}`);
  });

  for (const signal of ['SIGINT', 'SIGTERM'] as const) {
    process.on(signal, () => {
      server.close(() => {
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
