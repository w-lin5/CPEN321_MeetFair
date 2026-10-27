import {
  type Parser,
  fields,
  isoDate,
  numberInRange,
  objectId,
  oneOf,
  oneOfMany,
  optionalText,
  text,
} from '../common/middleware/validate';
import { type LatLng, PREFERENCES, type Preference, TRAVEL_MODES, type TravelMode } from '../common/types';
import { tuning } from '../config';

// Validate and extract required Google ID from request body
export const googleSignInBody: Parser<{ idToken: string }> = (data) => ({
  idToken: text(fields(data), 'idToken', tuning.idTokenMaxLength),
});

// Path params for /groups/:groupId...
export const groupParams: Parser<{ groupId: string }> = (data) => ({
  groupId: objectId(fields(data, 'params'), 'groupId'),
});

export const groupMemberParams: Parser<{ groupId: string; memberId: string }> = (data) => {
  const f = fields(data, 'params');
  return { groupId: objectId(f, 'groupId'), memberId: objectId(f, 'memberId') };
};

export const createGroupBody: Parser<{ name: string }> = (data) => ({
  name: text(fields(data), 'name', tuning.groupNameMaxLength),
});

// Invite codes are matched case-insensitively
export const joinGroupBody: Parser<{ inviteCode: string }> = (data) => ({
  inviteCode: text(fields(data), 'inviteCode', tuning.inviteCodeInputMaxLength).toUpperCase(),
});

// Path params for /meetups/:meetupId...
export const meetupParams: Parser<{ meetupId: string }> = (data) => ({
  meetupId: objectId(fields(data, 'params'), 'meetupId'),
});

export const createMeetupBody: Parser<{ title: string; scheduledAt: string }> = (data) => {
  const f = fields(data);

  return {
    title: text(f, 'title', tuning.meetupTitleMaxLength),
    scheduledAt: isoDate(f, 'scheduledAt'),
  };
};

export interface ParticipationInput {
  startLocation: LatLng & { label: string };
  travelMode: TravelMode;
  preferences: Preference[];
}

export const participationBody: Parser<ParticipationInput> = (data) => {
  const f = fields(data);
  const loc = fields(f.get('startLocation'), 'startLocation');
  
  return {
    startLocation: {
      lat: numberInRange(loc, 'lat', -tuning.maxLatitude, tuning.maxLatitude),
      lng: numberInRange(loc, 'lng', -tuning.maxLongitude, tuning.maxLongitude),
      label: optionalText(loc, 'label', tuning.locationLabelMaxLength),
    },
    travelMode: oneOf(f, 'travelMode', TRAVEL_MODES),
    preferences: oneOfMany(f, 'preferences', PREFERENCES, tuning.maxPreferences),
  };
};

// displayName must be present: a non-empty string sets it, null resets to the Google name
export const updateProfileBody: Parser<{ displayName: string | null }> = (data) => {
  const f = fields(data);
  return { displayName: f.get('displayName') === null ? null : text(f, 'displayName', tuning.displayNameMaxLength) };
};
