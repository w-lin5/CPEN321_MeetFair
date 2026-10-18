/**
 * Decouples services from Socket.IO: services publish, the socket layer (when attached) delivers.
 * Without an attached transport (e.g. in REST-only tests) publishing is a no-op.
 */

export const VOTING_EVENTS = {
  join: 'voting:join',
  leave: 'voting:leave',
  results: 'voting:results',
  closed: 'voting:closed',
} as const;

type Publish = (room: string, event: string, payload: unknown) => void;

let transport: Publish | null = null;

export function votingRoom(sessionId: string): string {
  return `voting:${sessionId}`;
}

export function setTransport(publish: Publish | null): void {
  transport = publish;
}

function publish(room: string, event: string, payload: unknown): void {
  transport?.(room, event, payload);
}

export const broadcaster = { publish };
