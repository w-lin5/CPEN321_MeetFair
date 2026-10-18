import { ValidationError } from '../errors';

export type Parser<T> = (data: unknown) => T;
export type Fields = ReadonlyMap<string, unknown>;

// Matches MongoDB ObjectID hex strings
const OBJECT_ID = /^[0-9a-fA-F]{24}$/;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/;

function fail(message: string): never {
  throw new ValidationError([message]);
}

export function parseInput<T>(parser: Parser<T>, data: unknown): T {
  return parser(data);
}

export function fields(data: unknown, name = 'body'): Fields {
  if (typeof data !== 'object' || data === null || Array.isArray(data)) {
    return fail(`${name} must be an object`);
  }

  return new Map(Object.entries(data));
}

export function text(f: Fields, key: string, max: number): string {
  const value = f.get(key);

  if (typeof value !== 'string' || value.trim() === '' || value.trim().length > max) {
    return fail(`${key} must be a non-empty string of at most ${max} characters`);
  }

  return value.trim();
}

export function optionalText(f: Fields, key: string, max: number): string {
  const value = f.get(key);
  
  if (value === undefined || value === null) return '';
  
  if (typeof value !== 'string' || value.trim().length > max) {
    return fail(`${key} must be a string of at most ${max} characters`);
  }
  
  return value.trim();
}

export function numberInRange(f: Fields, key: string, min: number, max: number): number {
  const value = f.get(key);

  if (typeof value !== 'number' || !Number.isFinite(value)
    || value < min || value > max) {
    return fail(`${key} must be a number between ${min} and ${max}`);
  }

  return value;
}

export function oneOf<T extends string>(f: Fields, key: string, allowed: readonly T[]): T {
  const value = f.get(key);
  const match = allowed.find((a) => a === value);
  return match ?? fail(`${key} must be one of ${allowed.join(', ')}`);
}

export function oneOfMany<T extends string>(
  f: Fields,
  key: string,
  allowed: readonly T[],
  maxItems: number,
): T[] {
  const value = f.get(key);

  if (value === undefined) return [];
  if (!Array.isArray(value) || value.length > maxItems) {
    return fail(`${key} must be array of at most ${maxItems} items`);
  }

  const items: unknown[] = value;

  return items.map((item) => allowed.find((a) => a === item) ?? fail(`${key} has an invalid item`));
}

export function isoDate(f: Fields, key: string): string {
  const value = f.get(key);

  if (typeof value !== 'string' || !ISO_DATE.test(value) || Number.isNaN(Date.parse(value))) {
    return fail(`${key} must be an ISO-8601 date time`);
  }
  
  return value;
}

export function objectId(f: Fields, key: string): string {
  const value = f.get(key);
  
  if (typeof value !== 'string' || !OBJECT_ID.test(value)) {
    return fail(`${key} must be a 24-hex ObjectId`);
  }
  
  return value;
}

// Optional ObjectId: absent or null yields undefined, anything else must be a valid id
export function optionalObjectId(f: Fields, key: string): string | undefined {
  const value = f.get(key);
  if (value === undefined || value === null) return undefined;
  return objectId(f, key);
}

// An array of exactly `exactItems` objects, each returned as Fields for further parsing
export function objectList(f: Fields, key: string, exactItems: number): Fields[] {
  const value = f.get(key);

  if (!Array.isArray(value) || value.length !== exactItems) {
    return fail(`${key} must contain exactly ${exactItems} items`);
  }

  const items: unknown[] = value;

  return items.map((item, i) => fields(item, `${key}[${i}]`));
}
