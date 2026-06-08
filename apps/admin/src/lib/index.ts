/**
 * Shared utility library for the admin portal.
 *
 * TODO: Add API client and shared helpers as backend integration is implemented.
 */

export { ADMIN_AUTH_PLACEHOLDER_NOTE } from './auth';

/** Format a date to a readable admin-friendly string (Swedish locale). */
export function formatDate(date: Date | string): string {
  const d = typeof date === 'string' ? new Date(date) : date;
  return d.toLocaleString('sv-SE', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** Truncate a string to a max length with ellipsis. */
export function truncate(str: string, maxLength: number): string {
  if (str.length <= maxLength) return str;
  if (maxLength <= 0) return '';
  if (maxLength === 1) return '…';
  return `${str.slice(0, maxLength - 1)}…`;
}
