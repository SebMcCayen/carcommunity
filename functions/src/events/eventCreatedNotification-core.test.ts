import { describe, expect, it } from 'vitest';
import {
  EVENT_CREATED_TITLE,
  eventCreatedNotificationId,
  eventCreatedPreview,
  isEventPublishedTransition,
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
