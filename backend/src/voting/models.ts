import { Schema, model } from 'mongoose';
import { jsonOptions } from '../db';

const votingSessionSchema = new Schema(
  {
    // Candidate locations users can vote for this round
      candidateIds: {
      type: [Schema.Types.ObjectId],
      required: true,
    },
    // The current status of the voting session
    status: {
      type: String,
      enum: ['open', 'closed'],
      default: 'open',
    },
    // The organizer who created this voting session
    createdBy: {
      type: Schema.Types.ObjectId,
      required: true,
      index: true,
    },

  },
  {
    timestamps: true,
    toJSON: jsonOptions,
  },
);

export const VotingSessionModel = model('VotingSession', votingSessionSchema);

const voteSchema = new Schema(
  {
    // The session this vote belongs to
    sessionId: {
      type: Schema.Types.ObjectId,
      required: true,
      index: true,
    },
    userId: {
      type: Schema.Types.ObjectId,
      required: true,
      index: true,
    },
    candidateId: {
      type: Schema.Types.ObjectId,
      required: true,
    },
    weight: {
      type: Number,
      required: true,
      default: 1,
    },
  },
  {
    timestamps: true,
    toJSON: jsonOptions,
  },
);

// Ensure a user can only vote one candidate per session
voteSchema.index(
  { sessionId: 1, userId: 1 },
  { unique: true },
);

export const VoteModel = model('Vote', voteSchema);