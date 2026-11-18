import mongoose from 'mongoose';

import { tuning } from './config';

export async function connectDb(uri: string): Promise<void> {
  await mongoose.connect(uri);
  // Unique indexes back several 409 checks (one vote per member, one settlement per meetup, ...);
  // wait until every registered model has built them so early requests cannot slip past
  await Promise.all(mongoose.modelNames().map((name) => mongoose.model(name).init()));
}

export async function disconnectDb(): Promise<void> {
  await mongoose.disconnect();
}

// Unique indexes turn races (two votes, two invite codes, ...) into this error, which callers map to a 409 or a retry
export function isDuplicateKeyError(err: unknown): boolean {
  return typeof err === 'object' && err !== null && 'code' in err && err.code === tuning.mongoDuplicateKeyCode;
}

// Defines JSON serialization options to normalize Mongoose documents by renaming _id to id and removing the internal __v version key
export const jsonOptions = {
  transform: (_doc: unknown, ret: Record<string, unknown>): Record<string, unknown> => {
    ret.id = String(ret._id);
    delete ret._id;
    delete ret.__v;
    return ret;
  },
};
