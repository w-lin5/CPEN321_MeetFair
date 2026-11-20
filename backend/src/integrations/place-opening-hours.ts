// Interpret Google's structured periods in the venue's timezone, never the server/device timezone.
const DAY_MINUTES = 24 * 60;
const WEEK_MINUTES = 7 * DAY_MINUTES;

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function integer(value: unknown, maximum: number): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= maximum;
}

function clock(point: unknown): number | undefined {
  if (!record(point)) return undefined;
  // Protobuf JSON can omit zero-valued hour/minute/day fields.
  const hour = point.hour ?? 0;
  const minute = point.minute ?? 0;
  return integer(hour, 23) && integer(minute, 59) ? hour * 60 + minute : undefined;
}

function dateMinutes(value: unknown): number | undefined {
  if (!record(value) || !integer(value.year, 9999) || value.year < 100
    || !integer(value.month, 12) || value.month === 0 || !integer(value.day, 31) || value.day === 0) return undefined;
  const date = new Date(Date.UTC(value.year, value.month - 1, value.day));
  if (date.getUTCFullYear() !== value.year || date.getUTCMonth() !== value.month - 1 || date.getUTCDate() !== value.day) return undefined;
  return date.getTime() / 60_000;
}

function weeklyPoint(point: unknown): number | undefined {
  if (!record(point)) return undefined;
  const day = point.day ?? 0;
  const time = clock(point);
  return integer(day, 6) && time !== undefined ? day * DAY_MINUTES + time : undefined;
}

function datedPoint(point: unknown): number | undefined {
  if (!record(point)) return undefined;
  const date = dateMinutes(point.date);
  const time = clock(point);
  return date !== undefined && time !== undefined ? date + time : undefined;
}

function alwaysOpen(periods: unknown[]): boolean {
  if (periods.length !== 1) return false;
  const period = periods[0];
  return record(period) && record(period.open) && period.close === undefined
    && period.open.date === undefined && weeklyPoint(period.open) === 0;
}

function localTime(instant: Date, timeZone: string) {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone, year: 'numeric', month: '2-digit',
    day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).formatToParts(instant);
  const part = (name: string) => Number(parts.find(value => value.type === name)?.value);
  const midnight = Date.UTC(part('year'), part('month') - 1, part('day')) / 60_000;
  const minute = part('hour') * 60 + part('minute') + part('second') / 60 + instant.getUTCMilliseconds() / 60_000;
  return { midnight, dated: midnight + minute, weekly: new Date(midnight * 60_000).getUTCDay() * DAY_MINUTES + minute };
}

function duringRegularHours(hours: unknown, minute: number): boolean {
  if (!record(hours) || !Array.isArray(hours.periods)) return false;
  if (alwaysOpen(hours.periods)) return true;
  return hours.periods.some((period: unknown) => {
    if (!record(period)) return false;
    const open = weeklyPoint(period.open);
    let close = weeklyPoint(period.close);
    if (open === undefined || close === undefined || close === open) return false;
    if (close < open) close += WEEK_MINUTES; // Saturday night into Sunday morning.
    return [minute, minute + WEEK_MINUTES].some(value => open <= value && value < close);
  });
}

function duringCurrentHours(hours: Record<string, unknown>, minute: number): boolean {
  // Current hours cover a date window, including closures; do not fall back to regular hours
  // just because no period matches (for example, a holiday or temporary closure).
  if (!Array.isArray(hours.periods)) return false;
  if (alwaysOpen(hours.periods)) return true;
  return hours.periods.some((period: unknown) => {
    if (!record(period)) return false;
    const open = datedPoint(period.open);
    let close = datedPoint(period.close);
    if (open === undefined || close === undefined || close <= open) return false;
    // Google's seven-day window can truncate the final endpoint at 23:59.
    if (record(period.close) && period.close.truncated === true) close += 1;
    return open <= minute && minute < close;
  });
}

/** Unknown/malformed hours are not confirmation that a venue will be open. */
export function isPlaceOpenAt(place: unknown, scheduledAt: Date, requestedAt = new Date()): boolean {
  if (!record(place) || place.businessStatus !== 'OPERATIONAL'
    || !record(place.timeZone) || typeof place.timeZone.id !== 'string'
    || !Number.isFinite(scheduledAt.getTime()) || !Number.isFinite(requestedAt.getTime())) return false;
  try {
    const target = localTime(scheduledAt, place.timeZone.id);
    const today = localTime(requestedAt, place.timeZone.id).midnight;
    if (target.dated >= today && target.dated < today + WEEK_MINUTES && record(place.currentOpeningHours)) {
      return duringCurrentHours(place.currentOpeningHours, target.dated);
    }
    return duringRegularHours(place.regularOpeningHours, target.weekly);
  } catch {
    return false; // Unsupported timezone or invalid provider data.
  }
}
