import type { NextFunction, Request, Response } from 'express';

import { Unauthorized } from '../errors';
import { verifySessionToken } from '../session';

export function requireAuth(req: Request, _res: Response, next: NextFunction): void {
  // Require Bearer token in the auth header
  const [scheme, token] = (req.header('authorization') ?? '').split(' ');
  if (scheme !== 'Bearer' || token === undefined || token === '') {
    throw new Unauthorized('Missing bearer token');
  }

  // Attach verified identity for downstream handler
  req.userId = verifySessionToken(token);
  next();
}

export function getUserId(req: Request): string {
  if (req.userId === undefined) throw new Unauthorized();
  return req.userId;
}
