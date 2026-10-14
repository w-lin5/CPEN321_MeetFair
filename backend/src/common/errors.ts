/**
 * Defines custom HTTP error classes with standard status codes
 * for consistent error handling across the application.
 */

export class HttpError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
}

export class ValidationError extends HttpError {
  constructor(public readonly details: string[]) {
    super(400, 'Invalid request');
  }
}

export class BadRequest extends HttpError { constructor(m = 'Bad Request') { super(400, m); } }
export class Unauthorized extends HttpError { constructor(m = 'Unauthorized') { super(401, m); } }
export class Forbidden extends HttpError { constructor(m = 'Forbidden') { super(403, m); } }
export class NotFound extends HttpError { constructor(m = 'Not Found') { super(404, m); } }
export class Conflict extends HttpError { constructor(m = 'Conflict') { super(409, m); } }
export class UpstreamError extends HttpError { constructor(m = 'Upstream failure') { super(502, m); } }
