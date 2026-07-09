import { describe, expect, it } from 'vitest';

import { formatDate, formatDateOnly, formatTimeOnly } from '@/lib/format';
import { exceedsMaxEventDuration } from '@/components/events/EventForm';

// Dates are constructed and asserted in local time (both the helper and the
// Date constructor here use the machine's timezone), so these assertions are
// timezone-independent. We deliberately avoid `Z`/offset ISO strings.
describe('formatDate', () => {
  it('renders the deterministic Swedish `YYYY-MM-DD, HH:mm` format', () => {
    expect(formatDate(new Date(2024, 6, 8, 23, 12))).toBe('2024-07-08, 23:12');
  });

  it('zero-pads month, day, hour and minute', () => {
    expect(formatDate(new Date(2024, 0, 3, 4, 5))).toBe('2024-01-03, 04:05');
  });

  it('accepts a local datetime string', () => {
    expect(formatDate('2024-07-08T23:12:00')).toBe('2024-07-08, 23:12');
  });

  it('returns the placeholder for empty, null and invalid input', () => {
    expect(formatDate('')).toBe('—');
    expect(formatDate(null)).toBe('—');
    expect(formatDate(undefined)).toBe('—');
    expect(formatDate('not-a-date')).toBe('—');
  });
});

describe('formatDateOnly / formatTimeOnly', () => {
  it('formats only the date part', () => {
    expect(formatDateOnly(new Date(2024, 6, 8, 23, 12))).toBe('2024-07-08');
  });

  it('formats only the time part', () => {
    expect(formatTimeOnly(new Date(2024, 6, 8, 23, 12))).toBe('23:12');
  });

  it('returns the placeholder for missing input', () => {
    expect(formatDateOnly(null)).toBe('—');
    expect(formatTimeOnly(undefined)).toBe('—');
  });
});

describe('exceedsMaxEventDuration', () => {
  it('is false at exactly 3 days', () => {
    expect(exceedsMaxEventDuration('2024-07-01T10:00', '2024-07-04T10:00')).toBe(false);
  });

  it('is true just past 3 days', () => {
    expect(exceedsMaxEventDuration('2024-07-01T10:00', '2024-07-04T10:01')).toBe(true);
  });

  it('is false for short durations', () => {
    expect(exceedsMaxEventDuration('2024-07-01T10:00', '2024-07-01T12:00')).toBe(false);
  });

  it('is false when either bound is missing or unparseable', () => {
    expect(exceedsMaxEventDuration('', '2024-07-04T10:01')).toBe(false);
    expect(exceedsMaxEventDuration('2024-07-01T10:00', '')).toBe(false);
    expect(exceedsMaxEventDuration('bad', 'worse')).toBe(false);
  });
});
