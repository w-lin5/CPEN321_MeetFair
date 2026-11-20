import { isPlaceOpenAt } from '../../src/integrations/place-opening-hours';

const requestedAt = new Date('2025-02-03T20:00:00Z');
const daytime = {
  businessStatus: 'OPERATIONAL', timeZone: { id: 'America/Vancouver' },
  regularOpeningHours: { periods: Array.from({ length: 7 }, (_, day) => ({
    open: { day, hour: 9, minute: 0 }, close: { day, hour: 22, minute: 0 },
  })) },
};
const aroundTheClock = { ...daytime, regularOpeningHours: { periods: [{ open: { day: 0, hour: 0, minute: 0 } }] } };
const openAt = (place: unknown, instant: string) => isPlaceOpenAt(place, new Date(instant), requestedAt);

describe('venue opening hours at the scheduled instant', () => {
  test('regression: 01:03 Vancouver is closed even when the server UTC clock reads 09:03', () => {
    expect(openAt(daytime, '2025-02-04T09:03:00Z')).toBe(false);
  });

  test.each([
    ['2025-02-04T17:00:00Z', true], // 09:00: opening inclusive
    ['2025-02-05T05:59:59Z', true],
    ['2025-02-05T06:00:00Z', false], // 22:00: closing exclusive
    ['2025-07-04T16:30:00Z', true], // summer offset, 09:30
  ])('uses venue local time and correct boundaries at %s', (instant, expected) => {
    expect(openAt(daytime, instant)).toBe(expected);
  });

  test('uses a venue timezone rather than one hard-coded city or a fixed UTC offset', () => {
    const instant = '2025-02-04T09:03:00Z';
    expect(openAt(daytime, instant)).toBe(false);
    expect(openAt({ ...daytime, timeZone: { id: 'Europe/London' } }, instant)).toBe(true);
  });

  test('handles overnight periods and the Saturday-to-Sunday week boundary', () => {
    const overnight = { ...daytime, regularOpeningHours: { periods: [
      { open: { day: 1, hour: 22 }, close: { day: 2, hour: 2 } },
      { open: { day: 6, hour: 22 }, close: { day: 0, hour: 2 } },
    ] } };
    expect(openAt(overnight, '2025-02-04T09:03:00Z')).toBe(true);
    expect(openAt(overnight, '2025-02-04T10:00:00Z')).toBe(false);
    expect(openAt(overnight, '2025-02-09T09:03:00Z')).toBe(true);
  });

  test('recognizes the documented always-open marker and omitted protobuf zero values', () => {
    expect(openAt(aroundTheClock, '2025-02-04T09:03:00Z')).toBe(true);
    expect(openAt({ ...daytime, regularOpeningHours: { periods: [{ open: {} }] } }, '2025-02-04T09:03:00Z')).toBe(true);
  });

  test.each(['CLOSED_TEMPORARILY', 'CLOSED_PERMANENTLY', 'FUTURE_OPENING', undefined])('rejects business status %s', businessStatus => {
    expect(openAt({ ...aroundTheClock, businessStatus }, '2025-02-04T09:03:00Z')).toBe(false);
  });

  test.each([
    { regularOpeningHours: undefined },
    { regularOpeningHours: { periods: [] } },
    { regularOpeningHours: { periods: [{ open: { day: 2, hour: 9 }, close: { day: 2, hour: 99 } }] } },
    { timeZone: undefined, utcOffsetMinutes: -480 },
    { timeZone: { id: 'not-a-timezone' } },
  ])('does not treat missing or malformed availability data as open: %j', overrides => {
    expect(openAt({ ...daytime, ...overrides }, '2025-02-04T18:00:00Z')).toBe(false);
  });

  test.each([{}, { periods: [] }])('a current closure does not fall back to the regular 24-hour schedule', currentOpeningHours => {
    expect(openAt({ ...aroundTheClock, currentOpeningHours }, '2025-02-04T09:03:00Z')).toBe(false);
  });

  const special = { periods: [{
    open: { date: { year: 2025, month: 2, day: 4 }, hour: 0 },
    close: { date: { year: 2025, month: 2, day: 4 }, hour: 2 },
  }] };

  test('dated exceptional hours override regular hours for the current seven-day window', () => {
    const place = { ...daytime, currentOpeningHours: special };
    expect(openAt(place, '2025-02-04T09:03:00Z')).toBe(true);
    expect(openAt(place, '2025-02-04T18:00:00Z')).toBe(false);
    expect(openAt(place, '2025-02-11T09:03:00Z')).toBe(false); // do not repeat last week's exception
    expect(openAt(place, '2025-02-11T18:00:00Z')).toBe(true); // use the regular schedule beyond the window
  });

  test('does not use openNow to decide availability at a different meetup time', () => {
    expect(openAt({ ...daytime, regularOpeningHours: { ...daytime.regularOpeningHours, openNow: true } }, '2025-02-04T09:03:00Z')).toBe(false);
    expect(openAt({ ...daytime, regularOpeningHours: { ...daytime.regularOpeningHours, openNow: false } }, '2025-02-04T18:00:00Z')).toBe(true);
  });

  test('handles dated overnight hours and a window-truncated final minute', () => {
    const place = { ...daytime, currentOpeningHours: { periods: [{
      open: { date: { year: 2025, month: 2, day: 8 }, hour: 22 },
      close: { date: { year: 2025, month: 2, day: 9 }, hour: 23, minute: 59, truncated: true },
    }] } };
    expect(openAt(place, '2025-02-09T09:03:00Z')).toBe(true);
    expect(openAt(place, '2025-02-10T07:59:59Z')).toBe(true);
  });

  test('invalid instants or malformed dated periods are not accepted', () => {
    expect(openAt(daytime, 'invalid')).toBe(false);
    expect(openAt({ ...daytime, currentOpeningHours: { periods: [{
      open: { date: { year: 2025, month: 2, day: 31 } }, close: {},
    }] } }, '2025-02-04T18:00:00Z')).toBe(false);
  });
});
