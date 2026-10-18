import mongoose from 'mongoose';
import * as os from 'node:os';

const SERVER_SELECTION_TIMEOUT_MS = 4_000;

// One database per Jest worker so parallel test files never impact eachother
export async function startDb(): Promise<void> {
  const uri = process.env.MONGO_URL;
  if (uri === undefined || uri === '') {
    throw new Error('MONGO_URL is not set: @shelf/jest-mongodb globalSetup did not run or failed');
  }

  await mongoose.connect(uri, {
    dbName: `meetfair_test_${process.env.JEST_WORKER_ID ?? '0'}`,
    serverSelectionTimeoutMS: SERVER_SELECTION_TIMEOUT_MS,
    runtimeAdapters: { os },
  });
}

export async function clearDb(): Promise<void> {
  for (const collection of Object.values(mongoose.connection.collections)) {
    await collection.deleteMany({});
  }
}

export async function stopDb(): Promise<void> {
  await mongoose.disconnect();
}
