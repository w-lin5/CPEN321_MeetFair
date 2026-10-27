/**
 * Decouples services from Socket.IO: services publish, the socket layer (when attached) delivers.
 * Without an attached transport (e.g. in REST-only tests) publishing is a no-op.
 */

type Publish = (room: string, event: string, payload: unknown) => void;

let transport: Publish | null = null;
let revokeTransport: ((userId: string) => void) | null = null;
let roomCloser: ((room: string) => void) | null = null;

export function setRoomCloser(close: ((room: string) => void) | null): void {
  roomCloser = close;
}

export function setRevoker(revoke: ((userId: string) => void) | null): void {
  revokeTransport = revoke;
}

export function setTransport(publish: Publish | null): void {
  transport = publish;
}

function publish(room: string, event: string, payload: unknown): void {
  transport?.(room, event, payload);
}

function revokeUser(userId: string): void {
  revokeTransport?.(userId);
}

function closeRoom(room: string): void {
  roomCloser?.(room);
}

export const publisher = { publish, revokeUser, closeRoom };
