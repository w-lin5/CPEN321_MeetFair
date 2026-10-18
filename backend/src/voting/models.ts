import { type InferSchemaType, Schema, model } from 'mongoose';

import { jsonOptions } from '../db';

export const VOTING_STATUSES = ['open', 'closed'] as const;

// One place the group can vote for; its generated _id is the candidateId votes refer to
const candidateSchema = new Schema(
  {
    // Google Places id, kept so the winner can be resolved again (e.g. for the calendar event)
    placeId: { type: String, required: true },
    name: { type: String, required: true },
    address: { type: String, default: '' },
    location: {
      lat: { type: Number, required: true },
      lng: { type: Number, required: true },
    },
  },
  { toJSON: jsonOptions },
);

const votingSessionSchema = new Schema(
  {
    // One voting session per meetup; access is limited to that meetup's group members
    meetupId: { type: Schema.Types.ObjectId, ref: 'Meetup', required: true, unique: true },
    // Candidate locations users can vote for this round
    candidates: { type: [candidateSchema], required: true },
    // The current status of the voting session
    status: { type: String, enum: [...VOTING_STATUSES], default: 'open' },
    // The organizer who created this voting session
    createdBy: { type: Schema.Types.ObjectId, ref: 'User', required: true, index: true },
    // Set when the session is closed
    winnerCandidateId: { type: Schema.Types.ObjectId, default: null },
  },
  { timestamps: true, toJSON: jsonOptions },
);

export type VotingSessionFields = InferSchemaType<typeof votingSessionSchema>;
export const VotingSessionModel = model('VotingSession', votingSessionSchema);

const voteSchema = new Schema(
  {
    // The session this vote belongs to
    sessionId: { type: Schema.Types.ObjectId, ref: 'VotingSession', required: true, index: true },
    userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    candidateId: { type: Schema.Types.ObjectId, required: true },
    // Fair Credit at the time of voting
    weight: { type: Number, required: true, default: 1 },
  },
  { timestamps: true, toJSON: jsonOptions },
);

// Ensure a user can only vote one candidate per session
voteSchema.index({ sessionId: 1, userId: 1 }, { unique: true });

export const VoteModel = model('Vote', voteSchema);
