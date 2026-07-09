/**
 * Date/string formatting helpers.
 *
 * Kept free of Firebase (and any other side-effecting) imports so it can be
 * used from any component or test without initializing the Firebase SDK.
 */

/** Placeholder shown when a date is missing or unparseable. */
const EMPTY_DATE = '—';

/** Coerce a Date | string | null | undefined into a valid Date, or null. */
function toValidDate(date: Date | string | null | undefined): Date | null {
  if (date == null || date === '') {
    return null;
  }
  const d = typeof date === 'string' ? new Date(date) : date;
  return Number.isNaN(d.getTime()) ? null : d;
}

const pad = (part: number) => String(part).padStart(2, '0');

/** `YYYY-MM-DD` for a valid date (local time), else the empty-date placeholder. */
export function formatDateOnly(date: Date | string | null | undefined): string {
  const d = toValidDate(date);
  if (!d) return EMPTY_DATE;
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** `HH:mm` for a valid date (local time), else the empty-date placeholder. */
export function formatTimeOnly(date: Date | string | null | undefined): string {
  const d = toValidDate(date);
  if (!d) return EMPTY_DATE;
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * Format a date to the deterministic Swedish numeric admin format
 * `YYYY-MM-DD, HH:mm` (e.g. `2024-07-08, 23:12`), in local time.
 *
 * Built by manual padding rather than `toLocaleString`, whose output varies
 * across ICU versions. Invalid or empty values yield a placeholder.
 */
export function formatDate(date: Date | string | null | undefined): string {
  const d = toValidDate(date);
  if (!d) return EMPTY_DATE;
  return `${formatDateOnly(d)}, ${formatTimeOnly(d)}`;
}

/** Truncate a string to a max length with ellipsis. */
export function truncate(str: string, maxLength: number): string {
  if (str.length <= maxLength) return str;
  if (maxLength <= 0) return '';
  if (maxLength === 1) return '…';
  return `${str.slice(0, maxLength - 1)}…`;
}
