/**
 * Unit tests for the pure event-reminder decision logic
 * (eventReminders-core.ts). No emulator — every branch is exercised in
 * isolation. The Firestore I/O (paging, the transactional marker claim, the
 * going-RSVP fan-out) is covered CI-only by the emulator suite
 * (functions/src/__tests__/events-reminders.emulator.test.ts).
 */

import { describe, expect, it } from 'vitest';
import {
  EVENT_REMINDER_LEAD_MS,
  EVENT_REMINDER_TITLE,
  decideEventReminder,
  eventReminderNotificationId,
  eventReminderPreview,
  normalizeEventReminderLimits,
  reminderWindowEnd,
  reminderWindowStart,
  type EventReminderInputs,
} from './eventReminders-core';

const NOW_MS = Date.UTC(2027, 6, 17, 9, 0, 0);

/** A published, unreminded event starting `offsetMs` from NOW. */
function inputs(offsetMs: number, overrides: Partial<EventReminderInputs> = {}): EventReminderInputs {
  return {
    status: 'published',
    startsAtMs: NOW_MS + offsetMs,
    reminderAlreadySent: false,
    nowMs: NOW_MS,
    leadMs: EVENT_REMINDER_LEAD_MS,
    ...overrides,
  };
}

describe('EVENT_REMINDER_LEAD_MS', () => {
  it('is two hours', () => {
    expect(EVENT_REMINDER_LEAD_MS).toBe(2 * 60 * 60 * 1000);
  });
});

describe('reminder window bounds', () => {
  it('starts at now (already-started events are excluded below this)', () => {
    const now = new Date(NOW_MS);
    expect(reminderWindowStart(now).getTime()).toBe(NOW_MS);
  });

  it('ends one lead width after now', () => {
    const now = new Date(NOW_MS);
    expect(reminderWindowEnd(now).getTime()).toBe(NOW_MS + EVENT_REMINDER_LEAD_MS);
  });

  it('honours a custom lead width', () => {
    const now = new Date(NOW_MS);
    expect(reminderWindowEnd(now, 30 * 60 * 1000).getTime()).toBe(NOW_MS + 30 * 60 * 1000);
  });
});

describe('normalizeEventReminderLimits', () => {
  const sane = { leadMs: 1000, pageSize: 50, maxCandidates: 200, concurrency: 5 };

  it('passes already-valid limits through unchanged', () => {
    expect(normalizeEventReminderLimits(sane)).toEqual(sane);
  });

  it('clamps maxCandidates to at least 1 (guards Firestore .limit(0)/negative)', () => {
    expect(normalizeEventReminderLimits({ ...sane, maxCandidates: 0 }).maxCandidates).toBe(1);
    expect(normalizeEventReminderLimits({ ...sane, maxCandidates: -5 }).maxCandidates).toBe(1);
  });

  it('clamps every limit to at least 1', () => {
    expect(
      normalizeEventReminderLimits({ leadMs: 0, pageSize: -1, maxCandidates: -10, concurrency: 0 }),
    ).toEqual({ leadMs: 1, pageSize: 1, maxCandidates: 1, concurrency: 1 });
  });
});

describe('decideEventReminder', () => {
  it('reminds a published event starting inside the window', () => {
    expect(decideEventReminder(inputs(60 * 60 * 1000))).toEqual({ remind: true });
  });

  it('reminds an event starting exactly at the lead edge (inclusive upper bound)', () => {
    expect(decideEventReminder(inputs(EVENT_REMINDER_LEAD_MS))).toEqual({ remind: true });
  });

  it('reminds a short-notice event starting in a few minutes', () => {
    expect(decideEventReminder(inputs(5 * 60 * 1000))).toEqual({ remind: true });
  });

  it('does not remind an event starting just beyond the lead edge', () => {
    expect(decideEventReminder(inputs(EVENT_REMINDER_LEAD_MS + 1))).toEqual({
      remind: false,
      reason: 'outside_window',
    });
  });

  it('does not remind an event far in the future', () => {
    expect(decideEventReminder(inputs(24 * 60 * 60 * 1000))).toEqual({
      remind: false,
      reason: 'outside_window',
    });
  });

  it('does not remind an event that already started', () => {
    expect(decideEventReminder(inputs(-1))).toEqual({ remind: false, reason: 'already_started' });
  });

  it('treats a start exactly at now as already started (exclusive lower bound)', () => {
    expect(decideEventReminder(inputs(0))).toEqual({ remind: false, reason: 'already_started' });
  });

  it('does not remind when the per-event marker is already set (never double-reminds)', () => {
    expect(
      decideEventReminder(inputs(60 * 60 * 1000, { reminderAlreadySent: true })),
    ).toEqual({ remind: false, reason: 'already_sent' });
  });

  it('already_sent wins over an in-window start (edited-start-time safety)', () => {
    // An event edited from far-future INTO the window, but already reminded once,
    // stays already_sent — the marker is sticky.
    expect(
      decideEventReminder(inputs(30 * 60 * 1000, { reminderAlreadySent: true })),
    ).toEqual({ remind: false, reason: 'already_sent' });
  });

  it.each(['draft', 'cancelled', 'completed'])(
    'does not remind a %s event even inside the window',
    (status) => {
      expect(decideEventReminder(inputs(60 * 60 * 1000, { status }))).toEqual({
        remind: false,
        reason: 'not_published',
      });
    },
  );

  it('status is checked before the marker (a cancelled reminded event reports not_published)', () => {
    expect(
      decideEventReminder(inputs(60 * 60 * 1000, { status: 'cancelled', reminderAlreadySent: true })),
    ).toEqual({ remind: false, reason: 'not_published' });
  });

  it('honours a custom lead width', () => {
    const shortLead = 30 * 60 * 1000;
    expect(decideEventReminder(inputs(45 * 60 * 1000, { leadMs: shortLead }))).toEqual({
      remind: false,
      reason: 'outside_window',
    });
    expect(decideEventReminder(inputs(20 * 60 * 1000, { leadMs: shortLead }))).toEqual({
      remind: true,
    });
  });
});

describe('eventReminderNotificationId', () => {
  it('is deterministic and event-scoped', () => {
    expect(eventReminderNotificationId('abc123')).toBe('event-reminder-abc123');
    expect(eventReminderNotificationId('abc123')).toBe(eventReminderNotificationId('abc123'));
  });

  it('matches the notification-id charset (A-Za-z0-9._-)', () => {
    expect(eventReminderNotificationId('Ev_ID.9-x')).toMatch(/^[A-Za-z0-9._-]+$/);
  });
});

describe('eventReminderPreview', () => {
  it('quotes the event title', () => {
    expect(eventReminderPreview('Bilträff Kungsbacka')).toBe('"Bilträff Kungsbacka" börjar snart.');
  });

  it('trims surrounding whitespace', () => {
    expect(eventReminderPreview('  Kvällscruising  ')).toBe('"Kvällscruising" börjar snart.');
  });

  it('falls back when the title is empty or whitespace', () => {
    expect(eventReminderPreview('')).toBe('Ditt event börjar snart.');
    expect(eventReminderPreview('   ')).toBe('Ditt event börjar snart.');
  });
});

describe('EVENT_REMINDER_TITLE', () => {
  it('is a short non-empty title', () => {
    expect(EVENT_REMINDER_TITLE.length).toBeGreaterThan(0);
    expect(EVENT_REMINDER_TITLE.length).toBeLessThanOrEqual(100);
  });
});
