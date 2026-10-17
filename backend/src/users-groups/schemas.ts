import { type Parser, fields, objectId, text } from '../common/middleware/validate';
import { tuning } from '../config';

// Validate and extract required Google ID from request body
export const googleSignInBody: Parser<{ idToken: string }> = (data) => ({
  idToken: text(fields(data), 'idToken', tuning.idTokenMaxLength),
});

// Path params for /groups/:groupId...
export const groupParams: Parser<{ groupId: string }> = (data) => ({
  groupId: objectId(fields(data, 'params'), 'groupId'),
});

export const createGroupBody: Parser<{ name: string }> = (data) => ({
  name: text(fields(data), 'name', tuning.groupNameMaxLength),
});

// Invite codes are matched case-insensitively
export const joinGroupBody: Parser<{ inviteCode: string }> = (data) => ({
  inviteCode: text(fields(data), 'inviteCode', tuning.inviteCodeInputMaxLength).toUpperCase(),
});
