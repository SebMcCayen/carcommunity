import { describe, expect, it, vi } from 'vitest';
import {
  EVENT_CREATED_TITLE,
  eventCreatedNotificationId,
  eventCreatedPreview,
  fanOutEventCreated,
  isEventPublishedTransition,
} from './eventCreatedNotification-core';
import { MAX_NOTIFICATION_TITLE_LENGTH } from '../notifications/notifications-core';

/** An async page stream from plain arrays, for the pure fan-out tests. */
async function* pagesOf(...pages: string[][]): AsyncGenerator<string[]> {
  for (const page of pages) {
    yield page;
  }
}

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

describe('fanOutEventCreated', () => {
  const okDeliver = async () => ({ delivered: true });

  it('delivers to everyone except the creator and tallies the counts', async () => {
    const summary = await fanOutEventCreated(pagesOf(['a', 'creator', 'b'], ['c']), {
      creatorUid: 'creator',
      deliverOne: okDeliver,
      maxRecipients: 100,
      concurrency: 2,
    });
    expect(summary).toEqual({ delivered: 3, skipped: 0, failed: 0, capped: false });
  });

  it('counts declined (delivered:false) recipients as skipped, not delivered', async () => {
    const summary = await fanOutEventCreated(pagesOf(['a', 'b', 'c']), {
      creatorUid: 'creator',
      deliverOne: async (uid) => ({ delivered: uid !== 'b' }),
      maxRecipients: 100,
      concurrency: 5,
    });
    expect(summary.delivered).toBe(2);
    expect(summary.skipped).toBe(1);
    expect(summary.failed).toBe(0);
  });

  it('counts thrown deliveries as failed and keeps going (never aborts the run)', async () => {
    const summary = await fanOutEventCreated(pagesOf(['a', 'b', 'c']), {
      creatorUid: 'creator',
      deliverOne: async () => {
        throw new Error('boom');
      },
      maxRecipients: 100,
      concurrency: 5,
    });
    expect(summary.delivered).toBe(0);
    expect(summary.failed).toBe(3);
  });

  it('THE CAP FIX: a systemic failure still binds the cap on attempts, not just successes', async () => {
    // Every delivery throws; without counting failures the cap could never bind
    // and the run would walk every page. It must stop at maxRecipients ATTEMPTS.
    const onFailure = vi.fn();
    const summary = await fanOutEventCreated(pagesOf(['a', 'b', 'c', 'd', 'e']), {
      creatorUid: 'creator',
      deliverOne: async () => {
        throw new Error('boom');
      },
      maxRecipients: 3,
      concurrency: 1,
      onFailure,
    });
    expect(summary.failed).toBe(3);
    expect(summary.delivered).toBe(0);
    expect(summary.capped).toBe(true);
    // Exactly the cap's worth of attempts were made — the 4th/5th never ran.
    expect(onFailure).toHaveBeenCalledTimes(3);
  });

  it('truncates a page to the remaining budget so a run never overshoots the cap', async () => {
    let attempts = 0;
    const summary = await fanOutEventCreated(pagesOf(['a', 'b', 'c', 'd', 'e']), {
      creatorUid: 'creator',
      deliverOne: async () => {
        attempts += 1;
        return { delivered: true };
      },
      maxRecipients: 2,
      concurrency: 10,
    });
    expect(summary.delivered).toBe(2);
    expect(summary.capped).toBe(true);
    expect(attempts).toBe(2);
  });

  it('does not count the creator against the cap or the tallies', async () => {
    const summary = await fanOutEventCreated(pagesOf(['creator', 'creator', 'a']), {
      creatorUid: 'creator',
      deliverOne: okDeliver,
      maxRecipients: 100,
      concurrency: 5,
    });
    expect(summary.delivered).toBe(1);
  });
});
