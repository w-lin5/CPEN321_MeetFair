import {
  BadRequest,
  Conflict,
  Forbidden,
  HttpError,
  NotFound,
  UpstreamError,
  Unauthorized,
  ValidationError,
} from '../../src/common/errors';

// Interface: HTTP error classes
describe('Unmocked: HTTP errors', () => {
  // Input: each error class constructed without a message
  // Expected behavior: default status code and default message are used
  // Expected output: [status, message] for the class
  test.each([
    ['BadRequest', BadRequest, 400, 'Bad Request'],
    ['Unauthorized', Unauthorized, 401, 'Unauthorized'],
    ['Forbidden', Forbidden, 403, 'Forbidden'],
    ['NotFound', NotFound, 404, 'Not Found'],
    ['Conflict', Conflict, 409, 'Conflict'],
    ['UpstreamError', UpstreamError, 502, 'Upstream failure'],
  ])('%s uses its defaults', (_name, ErrorClass, status, message) => {
    const err = new ErrorClass();

    expect(err).toBeInstanceOf(HttpError);
    expect(err.status).toBe(status);
    expect(err.message).toBe(message);
  });

  // Input: each error class constructed with a custom message
  // Expected behavior: custom message replaces the default; status code is unchanged
  // Expected output: [status, custom message] for the class
  test.each([
    ['BadRequest', BadRequest, 400],
    ['Unauthorized', Unauthorized, 401],
    ['Forbidden', Forbidden, 403],
    ['NotFound', NotFound, 404],
    ['Conflict', Conflict, 409],
    ['UpstreamError', UpstreamError, 502],
  ])('%s accepts a custom message', (_name, ErrorClass, status) => {
    const err = new ErrorClass('custom');

    expect(err.status).toBe(status);
    expect(err.message).toBe('custom');
  });

  // Input: ValidationError built from a list of problems
  // Expected behavior: status 400, generic message, details preserved
  // Expected output: { status: 400, message: "Invalid request", details }
  test('ValidationError carries its details', () => {
    const err = new ValidationError(['a is bad', 'b is bad']);

    expect(err).toBeInstanceOf(HttpError);
    expect(err.status).toBe(400);
    expect(err.message).toBe('Invalid request');
    expect(err.details).toEqual(['a is bad', 'b is bad']);
  });
});
