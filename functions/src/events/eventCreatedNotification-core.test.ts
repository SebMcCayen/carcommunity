import { describe, expect, it } from 'vitest';
import {
  EVENT_CREATED_TITLE,
  eventCreatedNotificationId,
  eventCreatedPreview,
  isEventPublishedTransition,
  recipientsWithinCap,
} from './eventCreatedNotification-core';
import { MAX_NOTIFICATION_TITLE_LENGTH } from '../notifications/notifications-core';

describe('isEventPublishedTransition', () => {
  it('fires on a member create written already published (no before state)', () => {
    expect(isEventPublishedTransition({ beforeStatus: undefined, afterStatus: 'published' })).toBe(
      true,
    );
  });

  it('fires on an admin draft -> published transition', () => {
    expect(isEventPublishedTransition({ beforeStatus: 'draft', afterStatus: 'published' })).toBe(
      true,
    );
  });

  it('does NOT fire on an admin create written as a draft', () => {
    expect(isEventPublishedTransition({ beforeStatus: undefined, afterStatus: 'draft' })).toBe(
      false,
    );
  });

  it('does NOT fire on any write to an already-published event (edit / counter bump)', () => {
    expect(isEventPublishedTransition({ beforeStatus: 'published', afterStatus: 'published' })).toBe(
      false,
    );
  });

  it('does NOT fire when a published event is cancelled or completed', () => {
    expect(isEventPublishedTransition({ beforeStatus: 'published', afterStatus: 'cancelled' })).toBe(
      false,
    );
    expect(isEventPublishedTransition({ beforeStatus: 'published', afterStatus: 'completed' })).toBe(
      false,
    );
  });

  it('does NOT fire on a delete (no after state)', () => {
    expect(isEventPublishedTransition({ beforeStatus: 'published', afterStatus: undefined })).toBe(
      false,
    );
  });
});

describe('eventCreatedNotificationId', () => {
  it('is deterministic and stays within the markRead id charset', () => {
    const id = eventCreatedNotificationId('abc123');
    expect(id).toBe('event-created-abc123');
    expect(id).toMatch(/^[A-Za-z0-9._-]+$/);
    // Stable across calls — the idempotency guard depends on it.
    expect(eventCreatedNotificationId('abc123')).toBe(id);
  });
});

describe('eventCreatedPreview', () => {
  it('quotes the event title', () => {
    expect(eventCreatedPreview('Bilträff Kungsbacka')).toBe(
      '"Bilträff Kungsbacka" har lagts till. Tryck för att se eventet.',
    );
  });

  it('falls back to a generic line for a blank title', () => {
    expect(eventCreatedPreview('   ')).toBe('Ett nytt event har lagts till. Tryck för att se det.');
    expect(eventCreatedPreview('')).toBe('Ett nytt event har lagts till. Tryck för att se det.');
  });

  it('title is well under the notification title cap', () => {
    expect(EVENT_CREATED_TITLE.length).toBeLessThanOrEqual(MAX_NOTIFICATION_TITLE_LENGTH);
  });
});

describe('recipientsWithinCap', () => {
  it('drops the creator from the page', () => {
    const { recipients } = recipientsWithinCap(['a', 'creator', 'b', 'creator'], {
      creatorUid: 'creator',
      processed: 0,
      maxRecipients: 100,
    });
    expect(recipients).toEqual(['a', 'b']);
  });

  it('passes the whole (creator-free) page through when under the cap', () => {
    const { recipients, capped } = recipientsWithinCap(['a', 'b', 'c'], {
      creatorUid: 'creator',
      processed: 0,
      maxRecipients: 100,
    });
    expect(recipients).toEqual(['a', 'b', 'c']);
    expect(capped).toBe(false);
  });

  it('truncates a page to the remaining budget and flags capped', () => {
    // 3 already processed, cap 5 → only 2 more may be attempted from this page.
    const { recipients, capped } = recipientsWithinCap(['a', 'b', 'c', 'd'], {
      creatorUid: 'creator',
      processed: 3,
      maxRecipients: 5,
    });
    expect(recipients).toEqual(['a', 'b']);
    expect(capped).toBe(true);
  });

  it('THE CAP FIX: `processed` is delivered+skipped+FAILED, so a systemic failure still binds it', () => {
    // The runner passes delivered+skipped+failed as `processed`; once that reaches
    // the cap the next page yields nothing, so a run where every write FAILS still
    // stops (rather than walking the whole active-users collection).
    const { recipients, capped } = recipientsWithinCap(['x', 'y'], {
      creatorUid: 'creator',
      processed: 5, // e.g. all 5 prior attempts were failures
      maxRecipients: 5,
    });
    expect(recipients).toEqual([]);
    expect(capped).toBe(true);
  });
});
