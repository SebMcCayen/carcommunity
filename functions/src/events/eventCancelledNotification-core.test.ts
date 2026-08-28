import { describe, expect, it } from 'vitest';
import {
  EVENT_CANCELLED_TITLE,
  cancelledRecipients,
  eventCancelledNotificationId,
  eventCancelledPreview,
  isEventCancelledTransition,
} from './eventCancelledNotification-core';
import { MAX_NOTIFICATION_TITLE_LENGTH } from '../notifications/notifications-core';

describe('isEventCancelledTransition', () => {
  it('fires on a published -> cancelled transition', () => {
    expect(
      isEventCancelledTransition({ beforeStatus: 'published', afterStatus: 'cancelled' }),
    ).toBe(true);
  });

  it('fires on a draft -> cancelled transition (a draft can be cancelled too)', () => {
    expect(isEventCancelledTransition({ beforeStatus: 'draft', afterStatus: 'cancelled' })).toBe(
      true,
    );
  });

  it('does NOT fire on any write to an already-cancelled event', () => {
    expect(
      isEventCancelledTransition({ beforeStatus: 'cancelled', afterStatus: 'cancelled' }),
    ).toBe(false);
  });

  it('does NOT fire on an edit / counter bump of a live event', () => {
    expect(
      isEventCancelledTransition({ beforeStatus: 'published', afterStatus: 'published' }),
    ).toBe(false);
  });

  it('does NOT fire when a published event is completed (not cancelled)', () => {
    expect(
      isEventCancelledTransition({ beforeStatus: 'published', afterStatus: 'completed' }),
    ).toBe(false);
  });

  it('does NOT fire on a create written as a draft or published', () => {
    expect(isEventCancelledTransition({ beforeStatus: undefined, afterStatus: 'draft' })).toBe(
      false,
    );
    expect(isEventCancelledTransition({ beforeStatus: undefined, afterStatus: 'published' })).toBe(
      false,
    );
  });

  it('does NOT fire on a delete (no after state)', () => {
    expect(
      isEventCancelledTransition({ beforeStatus: 'cancelled', afterStatus: undefined }),
    ).toBe(false);
  });
});

describe('eventCancelledNotificationId', () => {
  it('is deterministic and stays within the markRead id charset', () => {
    const id = eventCancelledNotificationId('abc123');
    expect(id).toBe('event-cancelled-abc123');
    expect(id).toMatch(/^[A-Za-z0-9._-]+$/);
    // Stable across calls — the idempotency guard depends on it.
    expect(eventCancelledNotificationId('abc123')).toBe(id);
  });
});

describe('eventCancelledPreview', () => {
  it('quotes the event title', () => {
    expect(eventCancelledPreview('Bilträff Kungsbacka')).toBe('"Bilträff Kungsbacka" har ställts in.');
  });

  it('falls back to a generic line for a blank title', () => {
    expect(eventCancelledPreview('   ')).toBe('Ett event du anmält dig till har ställts in.');
    expect(eventCancelledPreview('')).toBe('Ett event du anmält dig till har ställts in.');
  });

  it('title is well under the notification title cap', () => {
    expect(EVENT_CANCELLED_TITLE.length).toBeLessThanOrEqual(MAX_NOTIFICATION_TITLE_LENGTH);
  });
});

describe('cancelledRecipients', () => {
  it('drops the creator from the going list', () => {
    expect(cancelledRecipients(['a', 'creator', 'b'], 'creator')).toEqual(['a', 'b']);
  });

  it('drops EVERY occurrence of the creator (defensive)', () => {
    expect(cancelledRecipients(['creator', 'a', 'creator'], 'creator')).toEqual(['a']);
  });

  it('keeps the whole list when the creator is not among the going', () => {
    expect(cancelledRecipients(['a', 'b'], 'creator')).toEqual(['a', 'b']);
  });

  it('excludes nobody when the creator uid is null (corrupt/unattributed event)', () => {
    expect(cancelledRecipients(['a', 'b'], null)).toEqual(['a', 'b']);
  });
});
