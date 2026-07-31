/**
 * Tests for the date/time *input* plumbing behind `components/ui/DateTimeField`.
 *
 * This file pins the process timezone to Europe/Stockholm (unstubbed in
 * `afterAll`) so the DST cases are meaningful: run under UTC — as CI is — a
 * local/UTC mix-up is invisible, because the two agree. Node re-reads the `TZ`
 * environment variable on assignment; the `pins the timezone` case fails loudly
 * if that ever stops working, so the rest cannot pass vacuously.
 *
 * Everything else here is timezone-independent by construction: it asserts
 * round-trip identity, which must hold in every zone.
 */
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

import {
  combineDateTime,
  completeDateTime,
  datePartOf,
  localToIso,
  parseLocalDateTime,
  timePartOf,
  toLocalDateTimeValue,
  toLocalDateValue,
  withDatePart,
  withTimePart,
} from '@/lib/datetime';

beforeAll(() => {
  vi.stubEnv('TZ', 'Europe/Stockholm');
});

afterAll(() => {
  vi.unstubAllEnvs();
});

/** Whether the pinned timezone actually took effect in this runtime. */
const stockholm = () =>
  new Date(2026, 6, 1).getTimezoneOffset() === -120 &&
  new Date(2026, 0, 1).getTimezoneOffset() === -60;

describe('splitting a field value', () => {
  it('splits a full value into its date and time parts', () => {
    expect(datePartOf('2026-07-08T23:12')).toBe('2026-07-08');
    expect(timePartOf('2026-07-08T23:12')).toBe('23:12');
  });

  it('reports an empty time for a date-only value', () => {
    expect(datePartOf('2026-07-08')).toBe('2026-07-08');
    expect(timePartOf('2026-07-08')).toBe('');
  });

  it('yields empty parts for empty or malformed values', () => {
    expect(datePartOf('')).toBe('');
    expect(timePartOf('')).toBe('');
    expect(datePartOf('not-a-date')).toBe('');
    expect(timePartOf('not-a-date')).toBe('');
    expect(datePartOf('2026-07-08T')).toBe('');
  });
});

describe('editing a field value', () => {
  it('keeps the time when the date changes', () => {
    expect(withDatePart('2026-07-08T23:12', '2026-07-09')).toBe('2026-07-09T23:12');
  });

  it('keeps the date when the time changes', () => {
    expect(withTimePart('2026-07-08T23:12', '07:05')).toBe('2026-07-08T07:05');
  });

  it('starts a date-only value when a date is picked first', () => {
    expect(withDatePart('', '2026-07-08')).toBe('2026-07-08');
  });

  it('adds a time to a date-only value', () => {
    expect(withTimePart('2026-07-08', '14:30')).toBe('2026-07-08T14:30');
  });

  it('discards a time typed with no date', () => {
    expect(withTimePart('', '14:30')).toBe('');
  });

  it('clears the whole value when the date is cleared, dropping the stale time', () => {
    expect(withDatePart('2026-07-08T23:12', '')).toBe('');
  });

  it('keeps the date when only the time is cleared', () => {
    expect(withTimePart('2026-07-08T23:12', '')).toBe('2026-07-08');
  });
});

describe('partial input', () => {
  it('defaults a missing time to midnight only at completion time', () => {
    expect(completeDateTime('2026-07-08')).toBe('2026-07-08T00:00');
    expect(combineDateTime('2026-07-08', '')).toBe('2026-07-08T00:00');
  });

  it('leaves a complete value alone', () => {
    expect(completeDateTime('2026-07-08T23:12')).toBe('2026-07-08T23:12');
  });

  it('never invents a value from a date-less field', () => {
    expect(completeDateTime('')).toBe('');
    expect(combineDateTime('', '23:12')).toBe('');
  });

  it('stores a date-only value at local midnight, not UTC midnight', () => {
    const iso = localToIso('2026-07-08');
    expect(iso).not.toBeNull();
    expect(toLocalDateTimeValue(iso)).toBe('2026-07-08T00:00');
    if (stockholm()) {
      // Local midnight in CEST is 22:00 UTC the previous day. `new Date('2026-07-08')`
      // — the trap this helper exists to avoid — would have said 00:00Z.
      expect(iso).toBe('2026-07-07T22:00:00.000Z');
    }
  });
});

describe('clearing', () => {
  it('yields null rather than an invalid date', () => {
    expect(localToIso('')).toBeNull();
    expect(parseLocalDateTime('')).toBeNull();
  });

  it('renders an empty field for missing stored values', () => {
    expect(toLocalDateTimeValue(null)).toBe('');
    expect(toLocalDateTimeValue(undefined)).toBe('');
    expect(toLocalDateTimeValue('')).toBe('');
    expect(toLocalDateValue(null)).toBe('');
  });
});

describe('invalid input', () => {
  it('renders an empty field for a malformed stored value instead of throwing', () => {
    expect(toLocalDateTimeValue('not-a-date')).toBe('');
    expect(toLocalDateValue('2026-13-45')).toBe('');
  });

  it('rejects an impossible calendar date rather than rolling it over', () => {
    expect(parseLocalDateTime('2026-02-31')).toBeNull();
    expect(localToIso('2026-02-31')).toBeNull();
  });

  it('rejects out-of-range components', () => {
    expect(parseLocalDateTime('2026-00-10')).toBeNull();
    expect(parseLocalDateTime('2026-07-08T24:00')).toBeNull();
    expect(parseLocalDateTime('2026-07-08T12:60')).toBeNull();
  });

  it('rejects free text', () => {
    expect(parseLocalDateTime('igår')).toBeNull();
    expect(localToIso('08/07/2026')).toBeNull();
  });

  // A field value is *local* wall-clock with no offset; a stored value is an
  // absolute UTC instant. The parser must never accept an offset-bearing
  // instant and silently treat its `HH:mm` as local — that is a whole
  // timezone-sized shift on a saved time. These cases fail (the string parses)
  // if `DATE_TIME_RE` is anchored only at the start; they pass once it is
  // anchored at both ends.
  it('rejects a Z-suffixed instant instead of localising it', () => {
    expect(parseLocalDateTime('2026-07-08T12:00Z')).toBeNull();
    expect(localToIso('2026-07-08T12:00Z')).toBeNull();
    expect(datePartOf('2026-07-08T12:00Z')).toBe('');
    expect(timePartOf('2026-07-08T12:00Z')).toBe('');
  });

  it('rejects an explicit UTC offset instead of dropping it', () => {
    expect(parseLocalDateTime('2026-07-08T12:00+02:00')).toBeNull();
    expect(localToIso('2026-07-08T12:00+02:00')).toBeNull();
    expect(parseLocalDateTime('2026-07-08T12:00-05:00')).toBeNull();
  });

  it('rejects a seconds-bearing string rather than truncating it', () => {
    // The field is minute-granular by construction; anything finer is not a
    // valid field value, so it is rejected, not silently trimmed to `12:00`.
    expect(parseLocalDateTime('2026-07-08T12:00:30')).toBeNull();
    expect(localToIso('2026-07-08T12:00:30')).toBeNull();
    expect(timePartOf('2026-07-08T12:00:30')).toBe('');
  });
});

describe('round-tripping a stored instant', () => {
  // Instants either side of both Swedish DST transitions, plus the boundaries
  // themselves. Round-trip identity must hold in ANY timezone, so these
  // assertions are not gated on `stockholm()`.
  //
  // Deliberately excluded: the *second* pass through the repeated autumn hour
  // (2026-10-25T01:00:00.000Z in Stockholm). Its local wall-clock is ambiguous,
  // so identity is impossible for any wall-clock UI — see the dedicated case in
  // the DST block below, which pins what actually happens.
  const instants = [
    '2026-01-15T08:30:00.000Z', // CET (UTC+1)
    '2026-03-29T00:59:00.000Z', // one minute before spring forward
    '2026-03-29T01:00:00.000Z', // the instant clocks jump 02:00 -> 03:00
    '2026-07-08T21:12:00.000Z', // CEST (UTC+2)
    '2026-10-25T00:59:00.000Z', // first pass through the repeated hour
    '2026-10-25T02:00:00.000Z', // after the repeated hour has elapsed
    '2026-12-31T23:00:00.000Z',
  ];

  it.each(instants)('preserves the exact instant for %s', (iso) => {
    const fieldValue = toLocalDateTimeValue(iso);
    expect(localToIso(fieldValue)).toBe(iso);
  });

  it.each(instants)('is stable when the operator edits and saves again: %s', (iso) => {
    // Load -> edit the time to itself -> save -> load again.
    const first = toLocalDateTimeValue(iso);
    const edited = withTimePart(first, timePartOf(first));
    const saved = localToIso(edited);
    expect(saved).toBe(iso);
    expect(toLocalDateTimeValue(saved)).toBe(first);
  });

  it('truncates seconds once and then stays put', () => {
    const withSeconds = '2026-07-08T21:12:37.512Z';
    const first = localToIso(toLocalDateTimeValue(withSeconds));
    expect(first).toBe('2026-07-08T21:12:00.000Z');
    expect(localToIso(toLocalDateTimeValue(first))).toBe(first);
  });

  it('does not drift when a date is changed but the time is left alone', () => {
    const loaded = toLocalDateTimeValue('2026-07-08T21:12:00.000Z');
    const moved = withDatePart(loaded, '2026-07-09');
    expect(timePartOf(moved)).toBe(timePartOf(loaded));
  });
});

describe('DST in Europe/Stockholm', () => {
  it('pins the timezone for these cases', () => {
    // If this fails the runtime ignored `process.env.TZ`; the cases below would
    // otherwise pass vacuously under UTC.
    expect(stockholm()).toBe(true);
  });

  it('does not shift the wall-clock across the summer boundary', () => {
    // 14:00 local on either side of the March transition must stay 14:00 local.
    const marchWinter = localToIso('2026-03-28T14:00');
    const marchSummer = localToIso('2026-03-30T14:00');
    expect(marchWinter).toBe('2026-03-28T13:00:00.000Z'); // UTC+1
    expect(marchSummer).toBe('2026-03-30T12:00:00.000Z'); // UTC+2
    expect(toLocalDateTimeValue(marchWinter)).toBe('2026-03-28T14:00');
    expect(toLocalDateTimeValue(marchSummer)).toBe('2026-03-30T14:00');
  });

  it('resolves a non-existent local time (spring forward) to a real instant, then holds', () => {
    // 02:30 on 2026-03-29 does not exist: clocks go 02:00 -> 03:00.
    const iso = localToIso('2026-03-29T02:30');
    expect(iso).toBe('2026-03-29T01:30:00.000Z');
    // Re-rendered it becomes 03:30, and a second save changes nothing further.
    const rendered = toLocalDateTimeValue(iso);
    expect(rendered).toBe('2026-03-29T03:30');
    expect(localToIso(rendered)).toBe(iso);
  });

  it('resolves an ambiguous local time (fall back) to the first occurrence', () => {
    // 02:30 on 2026-10-25 happens twice: once at 00:30Z (still CEST) and again
    // at 01:30Z (CET). The platform picks the first, and the wall-clock the
    // operator typed is what comes back.
    const iso = localToIso('2026-10-25T02:30');
    expect(iso).toBe('2026-10-25T00:30:00.000Z');
    expect(toLocalDateTimeValue(iso)).toBe('2026-10-25T02:30');
    expect(localToIso(toLocalDateTimeValue(iso))).toBe(iso);
  });

  it('collapses an instant in the repeated hour to the first occurrence, then holds', () => {
    // This is the one case where a stored instant is NOT preserved exactly: a
    // wall-clock with no offset cannot distinguish the two 02:30s, so loading
    // and re-saving the second one moves it back an hour to the first. That is
    // inherent to any offset-less date/time UI, not a defect in this plumbing;
    // it can only bite an operator who re-saves a record whose timestamp falls
    // in the single repeated hour each October. Pinned so the behaviour is a
    // decision on record rather than a surprise.
    const secondPass = '2026-10-25T01:30:00.000Z';
    const rendered = toLocalDateTimeValue(secondPass);
    expect(rendered).toBe('2026-10-25T02:30');
    const resaved = localToIso(rendered);
    expect(resaved).toBe('2026-10-25T00:30:00.000Z');
    // Idempotent from then on — it does not keep walking backwards.
    expect(localToIso(toLocalDateTimeValue(resaved))).toBe(resaved);
  });

  it('would have caught the UTC-into-a-local-input bug this replaces', () => {
    const stored = '2026-07-08T21:12:00.000Z';
    // The old code: `new Date(stored).toISOString().slice(0, 16)`.
    const buggyFieldValue = new Date(stored).toISOString().slice(0, 16);
    expect(buggyFieldValue).toBe('2026-07-08T21:12');
    // Re-read as local on save, that is two hours later than what was stored.
    expect(localToIso(buggyFieldValue)).toBe('2026-07-08T19:12:00.000Z');
    // The helper renders local wall-clock instead, and round-trips exactly.
    expect(toLocalDateTimeValue(stored)).toBe('2026-07-08T23:12');
    expect(localToIso(toLocalDateTimeValue(stored))).toBe(stored);
  });
});
