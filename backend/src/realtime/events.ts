// Socket.IO event names and room names; mirrored by Android (data/RealtimeClient.kt)

export const VOTING_EVENTS = {
  join: 'voting:join',
  leave: 'voting:leave',
  results: 'voting:results',
  closed: 'voting:closed',
} as const;

export function votingRoom(sessionId: string): string {
  return `voting:${sessionId}`;
}
