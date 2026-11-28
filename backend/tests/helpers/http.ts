import type { Response } from 'supertest';

export function expectApiError(response: Response, status: number, error: string, details?: string[]): void {
  expect(response.status).toBe(status);
  expect(response.body).toEqual(details === undefined ? { error } : { error, details });
}
