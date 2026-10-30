import type { ErrorRequestHandler, Request, RequestHandler, Response } from 'express';

import { HttpError, ValidationError } from '../errors';

// Wraps an async route handler so a rejected promise is forwarded to a synchronous errorHandler via next()
export function asyncHandler(handler: (req: Request, res: Response) => Promise<void>): RequestHandler {
  return (req, res, next) => {
    handler(req, res).catch((err: unknown) => {
      next(err);
    });
  };
}

// Identifies malformed JSON errors raised by Express body parser
function isBodyParseError(err: unknown): boolean {
  return typeof err === 'object' && err !== null && 'type' in err && err.type === 'entity.parse.failed';
}

export const errorHandler: ErrorRequestHandler = (err: unknown, _req, res, _next) => {
  if (err instanceof ValidationError) {
    res.status(400).json({ error: err.message, details: err.details });
    return;
  }
  if (err instanceof HttpError) {
    res.status(err.status).json({ error: err.message });
    return;
  }
  if (isBodyParseError(err)) {
    res.status(400).json({ error: 'Malformed JSON body' });
    return;
  }

  // Avoid exposing internal error details to clients
  console.error('Unhandled error:', err instanceof Error ? err.message : 'unknown');
  res.status(500).json({ error: 'Internal Server Error' });
};
