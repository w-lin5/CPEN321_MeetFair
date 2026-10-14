import { type InferSchemaType, Schema, model } from 'mongoose';

import { jsonOptions } from '../db';

// Define persisted user fields and enforce uniqueness for Google identities (i.e., one account per Google id)
const userSchema = new Schema(
  {
    googleSub: { type: String, required: true, unique: true },
    email: { type: String, required: true },
    name: { type: String, required: true },
    photoUrl: { type: String, default: null },
  },
  { timestamps: true, toJSON: jsonOptions },
);

export type UserFields = InferSchemaType<typeof userSchema>;
export const UserModel = model('User', userSchema);
