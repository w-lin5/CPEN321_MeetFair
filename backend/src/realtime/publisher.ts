/**
 * Decouples services from Socket.IO: services publish, the socket layer (when attached) delivers.
 * Without an attached transport (e.g. in REST-only tests) publishing is a no-op.
 */

type Publish = (room: string, event: string, payload: unknown) => void;

let transport: Publish | null = null;

export function setTransport(publish: Publish | null): void {
  transport = publish;
}

function publish(room: string, event: string, payload: unknown): void {
  transport?.(room, event, payload);
}

export const publisher = { publish };
