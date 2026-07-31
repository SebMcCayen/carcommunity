/**
 * Date/time *input* helpers — the write-side counterpart to `./format.ts`
 * (which is read-side/display only).
 *
 * ## The format problem
 *
 * A native `<input type="datetime-local">` renders its visible format from the
 * browser/OS locale and cannot be overridden with CSS, which is why a browser
 * running an `en-GB` UI shows `dd/mm/yyyy, --:--` instead of the `YYYY-MM-DD`
 * + 24h `HH:mm` this admin requires. Splitting the control into a separate
 * `type="date"` and `type="time"` pair (see `components/ui/DateTimeField`)
 * gives us two independently-labelled, keyboard-typable fields whose *values*
 * are always ISO (`YYYY-MM-DD` / `HH:mm`) regardless of what chrome the
 * browser paints around them. This module owns the value plumbing for that
 * pair.
 *
 * ## The canonical form value
 *
 * A field's value is a **partial local datetime string**, one of:
 *   - `''`                    — nothing entered (or cleared)
 *   - `'YYYY-MM-DD'`          — a date, no time yet (partial input)
 *   - `'YYYY-MM-DDTHH:mm'`    — a date and a time
 *
 * Keeping "date but no time" representable is what lets a half-filled field
 * stay half-filled in the UI instead of silently snapping to midnight while
 * the operator is still typing. A time with no date is *not* representable:
 * it is discarded, because there is no instant it could denote.
 *
 * ## Timezone contract (read this before changing anything)
 *
 * Stored values (Firestore / callable payloads) are **absolute instants**,
 * carried as UTC ISO-8601 strings. Input values are **local wall-clock** with
 * no offset. Both directions therefore go through the local timezone, and
 * they must be each other's inverse:
 *
 *   stored ISO ──`toLocalDateTimeValue`──► local wall-clock  (local getters)
 *   local wall-clock ──`localToIso`──► stored ISO            (local Date ctor)
 *
 * The bug this replaces did *not* do that: it built the input value with
 * `new Date(iso).toISOString().slice(0, 16)`, i.e. rendered the **UTC**
 * wall-clock into a **local** input, then re-read it as local on save. Every
 * save shifted the instant by the UTC offset — one hour in Swedish winter,
 * two in Swedish summer — and the shift compounded on each edit.
 *
 * Precision note: these inputs are minute-granular, so a round trip truncates
 * seconds and milliseconds. That is inherent to the control, and it is
 * idempotent — a second round trip changes nothing further.
 *
 * DST note: an offset-less wall-clock cannot name both passes through the hour
 * that repeats when Swedish clocks fall back each October. An instant in that
 * second pass, loaded and re-saved, collapses to the first pass (one hour
 * earlier) and is stable from then on. No wall-clock UI can avoid this without
 * an explicit offset picker; it is pinned in `__tests__/datetime.test.ts`.
 */

/** Time used when a date was entered but no time was. */
const DEFAULT_TIME = '00:00';

/** `YYYY-MM-DD`. */
const DATE_ONLY_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * `YYYY-MM-DDTHH:mm` — fully anchored, exactly minute granularity.
 *
 * The trailing `$` is load-bearing, not tidiness. A field value is *local*
 * wall-clock with no offset; a stored value is an absolute UTC instant. If this
 * pattern were anchored only at the start it would also match a stored instant
 * (`2026-07-08T12:00Z`, `...T12:00+02:00`) or a seconds-bearing string, keep
 * only the `HH:mm`, and throw the offset/seconds away — silently reinterpreting
 * a UTC instant as local wall-clock (a timezone-sized shift, the exact bug this
 * module exists to prevent). Anchoring both ends makes any such input *fail to
 * match*, so `parseLocalDateTime`/`localToIso` return null and the caller sees
 * "not a valid field value" instead of a mis-parse.
 */
const DATE_TIME_RE = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/;

const pad = (part: number) => String(part).padStart(2, '0');

/** Coerce a Date | string | null | undefined into a valid Date, or null. */
function toValidDate(value: Date | string | null | undefined): Date | null {
  if (value == null || value === '') {
    return null;
  }
  const date = typeof value === 'string' ? new Date(value) : value;
  return Number.isNaN(date.getTime()) ? null : date;
}

/**
 * Render a stored instant as the `YYYY-MM-DD` half of a field value, in the
 * operator's local timezone. Missing/unparseable input yields `''`, so a
 * malformed stored value shows an empty field rather than throwing.
 */
export function toLocalDateValue(value: Date | string | null | undefined): string {
  const date = toValidDate(value);
  if (!date) return '';
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/**
 * Render a stored instant as a full `YYYY-MM-DDTHH:mm` field value, in the
 * operator's local timezone. Missing/unparseable input yields `''`.
 */
export function toLocalDateTimeValue(value: Date | string | null | undefined): string {
  const date = toValidDate(value);
  if (!date) return '';
  return `${toLocalDateValue(date)}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** The `YYYY-MM-DD` part of a field value, or `''`. */
export function datePartOf(value: string): string {
  if (!value) return '';
  const match = DATE_TIME_RE.exec(value) ?? DATE_ONLY_RE.exec(value);
  return match ? `${match[1]}-${match[2]}-${match[3]}` : '';
}

/** The `HH:mm` part of a field value, or `''` when only a date was entered. */
export function timePartOf(value: string): string {
  if (!value) return '';
  const match = DATE_TIME_RE.exec(value);
  return match ? `${match[4]}:${match[5]}` : '';
}

/**
 * Replace the date half of a field value, keeping any time already entered.
 * Clearing the date clears the whole field: a time on its own denotes no
 * instant, so it is dropped rather than left dangling in the UI.
 */
export function withDatePart(value: string, date: string): string {
  if (!date) return '';
  const time = timePartOf(value);
  return time ? `${date}T${time}` : date;
}

/**
 * Replace the time half of a field value. With no date entered there is
 * nothing to attach the time to, so the value stays empty. Clearing the time
 * leaves the date alone (the field falls back to `DEFAULT_TIME` on submit).
 */
export function withTimePart(value: string, time: string): string {
  const date = datePartOf(value);
  if (!date) return '';
  return time ? `${date}T${time}` : date;
}

/**
 * Combine separate date and time strings into a full field value. A date with
 * no time defaults to midnight; with no date there is no value at all.
 */
export function combineDateTime(date: string, time: string): string {
  if (!date) return '';
  return `${date}T${time || DEFAULT_TIME}`;
}

/**
 * Normalize a field value to either `''` or a full `YYYY-MM-DDTHH:mm`,
 * defaulting a missing time to midnight. Use this before any comparison or
 * duration arithmetic so partial input never reaches a bare `new Date(...)`.
 */
export function completeDateTime(value: string): string {
  return combineDateTime(datePartOf(value), timePartOf(value));
}

/**
 * Parse a partial local datetime string into a Date at **local** wall-clock,
 * or null if it is empty or not a real calendar date.
 *
 * Built from numeric components rather than `new Date(string)` on purpose:
 * per the ECMAScript spec a bare `'YYYY-MM-DD'` parses as **UTC** midnight,
 * which lands on the previous day for operators west of UTC and at 01:00/02:00
 * for operators in Sweden. Component construction is unambiguously local.
 *
 * Calendar rollover is rejected (`2026-02-31` is an error, not 2026-03-03) so
 * an impossible date fails loudly instead of being silently relocated.
 */
export function parseLocalDateTime(value: string): Date | null {
  if (!value) return null;

  const match = DATE_TIME_RE.exec(value) ?? DATE_ONLY_RE.exec(value);
  if (!match) return null;

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hours = match[4] != null ? Number(match[4]) : 0;
  const minutes = match[5] != null ? Number(match[5]) : 0;

  if (month < 1 || month > 12 || day < 1 || day > 31 || hours > 23 || minutes > 59) {
    return null;
  }

  const date = new Date(year, month - 1, day, hours, minutes, 0, 0);
  if (
    date.getFullYear() !== year ||
    date.getMonth() !== month - 1 ||
    date.getDate() !== day
  ) {
    return null;
  }

  return date;
}

/**
 * Convert a partial local datetime field value to the UTC ISO-8601 string that
 * gets stored. Empty input — and input that is not a real date — yields null,
 * so a cleared optional field saves as "no value" rather than throwing a
 * RangeError out of `toISOString()`.
 *
 * A date with no time is stored at local midnight of that day.
 *
 * On a DST transition the local wall-clock may be non-existent (spring
 * forward) or ambiguous (autumn back). The platform resolves both; the result
 * is a real instant, and re-rendering it produces a value that round-trips
 * unchanged from then on.
 */
export function localToIso(value: string): string | null {
  const date = parseLocalDateTime(value);
  return date ? date.toISOString() : null;
}

/**
 * ## Locale-independent text entry
 *
 * The native `<input type="date">` / `<input type="time">` controls render
 * their visible format from the browser/OS locale — `dd/mm/yyyy`, `mm/dd/yyyy`,
 * a 12-hour clock, whatever the operator's machine says — and no attribute,
 * `lang` tag or CSS rule can force `YYYY-MM-DD` there (Chromium honours `lang`
 * unreliably; Firefox and Safari ignore it outright). The only way to guarantee
 * the admin always sees and types `YYYY-MM-DD` + 24h `HH:mm` is to drive plain
 * `<input type="text">` controls ourselves. These helpers are that control's
 * value plumbing: a mask that keeps the visible text well-formed as the operator
 * types, and a normaliser that reports the canonical field value (or `''`) once
 * the text is a complete, real calendar value.
 */

/** `HH:mm`, fully anchored. Numeric range is validated separately. */
const TIME_ONLY_RE = /^(\d{2}):(\d{2})$/;

/**
 * Reformat free text into the visible `YYYY-MM-DD` shape as it is typed.
 *
 * Non-digits are dropped and dashes are re-inserted purely from the digit
 * count, so the field is stable under insertion, deletion and paste: typing
 * `20260731` shows `2026-07-31`, and backspacing a digit removes the dash with
 * it rather than stranding one. At most eight digits are kept; anything past
 * the day is ignored. This only shapes the *text* — it never asserts the value
 * is a real date (see `normalizeDateInput`).
 */
export function maskDateInput(raw: string): string {
  const digits = raw.replace(/\D/g, '').slice(0, 8);
  const year = digits.slice(0, 4);
  const month = digits.slice(4, 6);
  const day = digits.slice(6, 8);
  let out = year;
  if (digits.length > 4) out += `-${month}`;
  if (digits.length > 6) out += `-${day}`;
  return out;
}

/**
 * Reformat free text into the visible `HH:mm` shape as it is typed. Same
 * digit-count masking as `maskDateInput`, capped at four digits (`HHmm`).
 */
export function maskTimeInput(raw: string): string {
  const digits = raw.replace(/\D/g, '').slice(0, 4);
  const hours = digits.slice(0, 2);
  const minutes = digits.slice(2, 4);
  return digits.length > 2 ? `${hours}:${minutes}` : hours;
}

/**
 * The canonical `YYYY-MM-DD` field value for masked date text, or `''` when the
 * text is empty, still partial, or not a real calendar date. Rejects rollover
 * (`2026-02-31`) by reusing `parseLocalDateTime`, so partial keystrokes and
 * impossible dates alike stay uncommitted rather than reaching a `Date`
 * constructor.
 */
export function normalizeDateInput(text: string): string {
  if (!DATE_ONLY_RE.test(text)) return '';
  return parseLocalDateTime(text) ? text : '';
}

/**
 * The canonical `HH:mm` field value for masked time text, or `''` when the text
 * is empty, still partial, or out of range (hours > 23, minutes > 59).
 */
export function normalizeTimeInput(text: string): string {
  const match = TIME_ONLY_RE.exec(text);
  if (!match) return '';
  return Number(match[1]) <= 23 && Number(match[2]) <= 59 ? text : '';
}
