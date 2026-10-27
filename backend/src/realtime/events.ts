// Socket.IO event names and room names (CLAUDE.md §4.6); mirrored by Android (data/RealtimeClient.kt)

// Client -> server
export const CLIENT_EVENTS = {
  meetupJoin: 'meetup:join',
  meetupLeave: 'meetup:leave',
} as const;

// Server -> client, always sent to the meetup's room
export const SERVER_EVENTS = {
  meetupUpdated: 'meetup:updated',
  voteUpdated: 'vote:updated',
  meetupStatus: 'meetup:status',
  recommendationsReady: 'recommendations:ready',
} as const;

export function meetupRoom(meetupId: string): string {
  return `meetup:${meetupId}`;
}
