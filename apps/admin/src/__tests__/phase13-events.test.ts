/**
 * Unit tests for the Phase 13 migrated events admin module: adapter behavior
 * over mocked Firebase clients (list window + client filters, detail merge of
 * the private subcollection, and callable-then-re-read for mutations).
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

const getDocMock = vi.fn();
const getDocsMock = vi.fn();
const callAdminMock = vi.fn();

vi.mock('../lib/firestore', () => ({ getAdminFirestore: () => ({}) }));
vi.mock('../lib/callables', () => ({ callAdmin: (...args: unknown[]) => callAdminMock(...args) }));
vi.mock('firebase/firestore', () => ({
  doc: (...segments: unknown[]) => ({ segments }),
  collection: (...segments: unknown[]) => ({ segments }),
  query: (target: unknown) => target,
  orderBy: () => undefined,
  limit: () => undefined,
  getDoc: (...args: unknown[]) => getDocMock(...args),
  getDocs: (...args: unknown[]) => getDocsMock(...args),
}));

import {
  cancelAdminEvent,
  createAdminEvent,
  formatEventStatus,
  isUpcomingEvent,
  loadAdminEvent,
  loadAdminEvents,
  publishAdminEvent,
  updateAdminEvent,
} from '../features/events';

const ts = (iso: string) => ({ toDate: () => new Date(iso) });

function eventDoc(id: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    data: () => ({
      title: `Event ${id}`,
      summary: 'teaser',
      status: 'published',
      isOfficial: false,
      startsAt: ts('2026-08-01T18:00:00Z'),
      endsAt: null,
      approximateArea: 'Kungsbacka',
      rsvpCounts: { going: 3, maybe: 1, not_going: 0 },
      cancelledAt: null,
      createdByUserId: 'admin1',
      createdAt: ts('2026-07-01T10:00:00Z'),
      updatedAt: ts('2026-07-02T10:00:00Z'),
      ...overrides,
    }),
  };
}

beforeEach(() => {
  getDocMock.mockReset();
  getDocsMock.mockReset();
  callAdminMock.mockReset();
});

describe('events module — helpers', () => {
  it('labels statuses in Swedish', () => {
    expect(formatEventStatus('draft')).toBe('Utkast');
    expect(formatEventStatus('cancelled')).toBe('Inställt');
  });

  it('treats only future published events as upcoming', () => {
    expect(isUpcomingEvent({ status: 'published', startsAt: '2999-01-01T00:00:00Z' })).toBe(true);
    expect(isUpcomingEvent({ status: 'draft', startsAt: '2999-01-01T00:00:00Z' })).toBe(false);
    expect(isUpcomingEvent({ status: 'published', startsAt: '2000-01-01T00:00:00Z' })).toBe(false);
  });
});

describe('events module — reads', () => {
  it('maps the window into admin summaries with ISO timestamps', async () => {
    getDocsMock.mockResolvedValue({ docs: [eventDoc('e1')] });
    const response = await loadAdminEvents();
    expect(response.data.events).toHaveLength(1);
    expect(response.data.events[0]).toMatchObject({
      id: 'e1',
      status: 'published',
      startsAt: '2026-08-01T18:00:00.000Z',
      createdAt: '2026-07-01T10:00:00.000Z',
      rsvpCounts: { going: 3, maybe: 1, not_going: 0 },
    });
    expect(response.meta.total).toBe(1);
  });

  it('applies status + isOfficial filters client-side', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        eventDoc('e1', { status: 'draft', isOfficial: true }),
        eventDoc('e2', { status: 'published', isOfficial: false }),
        eventDoc('e3', { status: 'draft', isOfficial: false }),
      ],
    });
    const drafts = await loadAdminEvents({ status: 'draft' });
    expect(drafts.data.events.map((e) => e.id)).toEqual(['e1', 'e3']);
    const official = await loadAdminEvents({ isOfficial: true });
    expect(official.data.events.map((e) => e.id)).toEqual(['e1']);
  });

  it('merges the private detail subcollection into the detail view', async () => {
    getDocMock
      .mockResolvedValueOnce({ exists: () => true, data: () => eventDoc('e1').data() })
      .mockResolvedValueOnce({
        exists: () => true,
        data: () => ({
          description: 'Full description',
          locationName: 'Torg',
          address: 'Storgatan 1',
          latitude: 57.5,
          longitude: 12.1,
        }),
      });
    const response = await loadAdminEvent('e1');
    expect(response.data.event).toMatchObject({
      id: 'e1',
      description: 'Full description',
      locationName: 'Torg',
      latitude: 57.5,
      createdByUserId: 'admin1',
    });
  });

  it('throws a 404 ApiError when the event does not exist', async () => {
    getDocMock
      .mockResolvedValueOnce({ exists: () => false, data: () => undefined })
      .mockResolvedValueOnce({ exists: () => false, data: () => undefined });
    await expect(loadAdminEvent('missing')).rejects.toMatchObject({ statusCode: 404 });
  });
});

describe('events module — mutations route through callables then re-read', () => {
  const reReadDetail = () => {
    getDocMock
      .mockResolvedValueOnce({ exists: () => true, data: () => eventDoc('e1').data() })
      .mockResolvedValueOnce({ exists: () => true, data: () => ({}) });
  };

  it('creates via events-create', async () => {
    callAdminMock.mockResolvedValue({ eventId: 'e1', status: 'draft' });
    reReadDetail();
    const response = await createAdminEvent({
      title: 'New',
      startsAt: '2026-08-01T18:00:00Z',
      approximateArea: 'Kungsbacka',
      isOfficial: false,
    });
    expect(callAdminMock).toHaveBeenCalledWith('events-create', expect.objectContaining({ title: 'New' }));
    expect(response.data.event.id).toBe('e1');
  });

  it('updates via events-update with the eventId in the payload', async () => {
    callAdminMock.mockResolvedValue({ eventId: 'e1', status: 'draft' });
    reReadDetail();
    await updateAdminEvent('e1', { title: 'Edited' });
    expect(callAdminMock).toHaveBeenCalledWith('events-update', { eventId: 'e1', title: 'Edited' });
  });

  it('publishes via events-publish', async () => {
    callAdminMock.mockResolvedValue({ eventId: 'e1', status: 'published' });
    reReadDetail();
    await publishAdminEvent('e1');
    expect(callAdminMock).toHaveBeenCalledWith('events-publish', { eventId: 'e1' });
  });

  it('cancels via events-cancel with the mandatory reason', async () => {
    callAdminMock.mockResolvedValue({ eventId: 'e1', status: 'cancelled' });
    reReadDetail();
    await cancelAdminEvent('e1', { reason: 'Väder' });
    expect(callAdminMock).toHaveBeenCalledWith('events-cancel', { eventId: 'e1', reason: 'Väder' });
  });
});
