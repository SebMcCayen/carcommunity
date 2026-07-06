/**
 * Unit tests for the Phase 13 migrated admin feature modules
 * (feature-flags, points, events): adapter behavior over mocked Firebase
 * clients.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

const getDocMock = vi.fn();
const getDocsMock = vi.fn();
const getCountMock = vi.fn();
const callAdminMock = vi.fn();

vi.mock('../lib/firestore', () => ({ getAdminFirestore: () => ({}) }));
vi.mock('../lib/callables', () => ({ callAdmin: (...args: unknown[]) => callAdminMock(...args) }));
vi.mock('firebase/firestore', () => ({
  doc: (...segments: unknown[]) => ({ segments }),
  collection: (...segments: unknown[]) => ({ segments }),
  query: (target: unknown) => target,
  orderBy: () => undefined,
  limit: () => undefined,
  where: () => undefined,
  getDoc: (...args: unknown[]) => getDocMock(...args),
  getDocs: (...args: unknown[]) => getDocsMock(...args),
  getCountFromServer: (...args: unknown[]) => getCountMock(...args),
}));

import {
  DEFAULT_FEATURE_FLAGS,
  loadFeatureFlagRows,
  setFeatureFlag,
} from '../features/feature-flags';
import {
  applyAdminPointsAdjustment,
  getAdminUserPointsBalance,
  getAdminUserPointsLedger,
} from '../features/points';
import {
  cancelAdminEvent,
  createAdminEvent,
  loadAdminEvent,
  loadAdminEvents,
  publishAdminEvent,
  updateAdminEvent,
} from '../features/events';
import { loadAdminGroupDriveSummary } from '../features/group-drive';

beforeEach(() => {
  getDocMock.mockReset();
  getDocsMock.mockReset();
  getCountMock.mockReset();
  callAdminMock.mockReset();
});

describe('feature-flags module', () => {
  it('overlays Firestore values on contract defaults', async () => {
    getDocMock.mockResolvedValue({ data: () => ({ chat: false, bogus: 'ignored' }) });
    const rows = await loadFeatureFlagRows();
    const chat = rows.find((r) => r.key === 'chat')!;
    expect(chat).toMatchObject({ enabled: false, overridden: true });
    const crownHunt = rows.find((r) => r.key === 'crownHunt')!;
    expect(crownHunt).toMatchObject({ enabled: true, overridden: false });
    // The 9m contract key exists even though the legacy shared list lacks it.
    expect(rows.some((r) => r.key === 'partnerInsightsPassBy')).toBe(true);
    expect(DEFAULT_FEATURE_FLAGS.partnerInsightsPassBy).toBe(false);
  });

  it('toggles through the audited callable', async () => {
    callAdminMock.mockResolvedValue({ key: 'chat', enabled: false });
    await setFeatureFlag('chat', false, 'Incident');
    expect(callAdminMock).toHaveBeenCalledWith('admin-setFeatureFlag', {
      key: 'chat',
      enabled: false,
      reason: 'Incident',
    });
  });
});

describe('points module', () => {
  it('serves balances from the wallet document (missing wallet = 0)', async () => {
    getDocMock.mockResolvedValue({ data: () => undefined });
    const response = await getAdminUserPointsBalance('u1');
    expect(response).toEqual({
      ok: true,
      data: { balance: 0, displayName: 'Kronpoäng', shortForm: 'KP' },
    });
  });

  it('adapts ledger entries into the legacy envelope', async () => {
    getDocMock.mockResolvedValue({ data: () => ({ balance: 120 }) });
    getDocsMock.mockResolvedValue({
      docs: [
        {
          id: 'e1',
          data: () => ({
            transactionType: 'adjustment_credit',
            source: 'admin_adjustment',
            amount: 20,
            balanceAfter: 120,
            description: 'Justering',
            createdAt: { toDate: () => new Date('2026-07-01T10:00:00Z') },
          }),
        },
      ],
    });
    const response = await getAdminUserPointsLedger('u1');
    expect(response.data.balance).toBe(120);
    expect(response.data.transactions[0]).toMatchObject({
      transactionId: 'e1',
      amount: 20,
      createdAt: '2026-07-01T10:00:00.000Z',
    });
    expect(response.meta.hasNext).toBe(false);
  });

  it('routes adjustments through points-adminAdjust', async () => {
    callAdminMock.mockResolvedValue({
      targetUid: 'u1',
      entryId: 'e9',
      amount: 50,
      balanceAfter: 170,
      alreadyApplied: false,
    });
    const response = await applyAdminPointsAdjustment('u1', {
      type: 'adjustment_credit',
      amount: 50,
      reason: 'Tävlingsvinst',
    });
    expect(callAdminMock).toHaveBeenCalledWith('points-adminAdjust', {
      targetUid: 'u1',
      type: 'adjustment_credit',
      amount: 50,
      reason: 'Tävlingsvinst',
    });
    expect(response.data).toMatchObject({ transactionId: 'e9', balanceAfter: 170 });
  });
});

describe('events module', () => {
  const ts = (iso: string) => ({ toDate: () => new Date(iso) });

  it('maps event documents and applies the status filter over the page', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        {
          id: 'e1',
          data: () => ({
            title: 'Träff',
            status: 'published',
            isOfficial: true,
            startsAt: ts('2026-08-01T18:00:00Z'),
            endsAt: null,
            approximateArea: 'Kungsbacka',
            rsvpCounts: { going: 3, maybe: 1, not_going: 0 },
            cancelledAt: null,
            createdAt: ts('2026-07-01T10:00:00Z'),
            updatedAt: ts('2026-07-02T10:00:00Z'),
          }),
        },
        {
          id: 'e2',
          data: () => ({
            title: 'Utkast',
            status: 'draft',
            isOfficial: false,
            startsAt: ts('2026-09-01T18:00:00Z'),
            approximateArea: 'Onsala',
            rsvpCounts: { going: 0, maybe: 0, not_going: 0 },
            createdAt: ts('2026-07-01T10:00:00Z'),
            updatedAt: ts('2026-07-01T10:00:00Z'),
          }),
        },
      ],
    });

    const response = await loadAdminEvents({ status: 'published', pageSize: 20 });
    expect(response.data.events).toHaveLength(1);
    expect(response.data.events[0]).toMatchObject({
      id: 'e1',
      status: 'published',
      startsAt: '2026-08-01T18:00:00.000Z',
      rsvpCounts: { going: 3, maybe: 1, not_going: 0 },
    });
    expect(response.meta).toEqual({ total: 1, page: 1, pageSize: 20 });
  });

  it('merges the teaser doc with details/private into the admin detail', async () => {
    getDocMock
      .mockResolvedValueOnce({
        exists: () => true,
        id: 'e1',
        data: () => ({
          title: 'Träff',
          summary: 'Kort',
          status: 'published',
          isOfficial: true,
          startsAt: ts('2026-08-01T18:00:00Z'),
          endsAt: null,
          approximateArea: 'Kungsbacka',
          createdByUserId: 'admin1',
          createdAt: ts('2026-07-01T10:00:00Z'),
          updatedAt: ts('2026-07-02T10:00:00Z'),
          cancelledAt: null,
          rsvpCounts: { going: 3, maybe: 1, not_going: 0 },
        }),
      })
      .mockResolvedValueOnce({
        data: () => ({
          description: 'Full beskrivning',
          locationName: 'Torget',
          address: 'Storgatan 1',
          latitude: 57.48,
          longitude: 12.07,
        }),
      });

    const response = await loadAdminEvent('e1');
    expect(response.data.event).toMatchObject({
      id: 'e1',
      summary: 'Kort',
      description: 'Full beskrivning',
      locationName: 'Torget',
      latitude: 57.48,
      createdByUserId: 'admin1',
    });
  });

  it('throws a 404 ApiError when the event document is missing', async () => {
    getDocMock
      .mockResolvedValueOnce({ exists: () => false, data: () => undefined })
      .mockResolvedValueOnce({ data: () => undefined });
    await expect(loadAdminEvent('missing')).rejects.toMatchObject({ statusCode: 404 });
  });

  it('creates via events-create then re-reads the fresh detail', async () => {
    callAdminMock.mockResolvedValue({ eventId: 'e9', status: 'draft' });
    getDocMock
      .mockResolvedValueOnce({
        exists: () => true,
        id: 'e9',
        data: () => ({
          title: 'Ny',
          status: 'draft',
          startsAt: ts('2026-08-01T18:00:00Z'),
          approximateArea: 'Kungsbacka',
          rsvpCounts: { going: 0, maybe: 0, not_going: 0 },
          createdAt: ts('2026-07-06T10:00:00Z'),
          updatedAt: ts('2026-07-06T10:00:00Z'),
        }),
      })
      .mockResolvedValueOnce({ data: () => ({}) });

    const response = await createAdminEvent({
      title: 'Ny',
      startsAt: '2026-08-01T18:00:00Z',
      approximateArea: 'Kungsbacka',
    });
    expect(callAdminMock).toHaveBeenCalledWith('events-create', {
      title: 'Ny',
      startsAt: '2026-08-01T18:00:00Z',
      approximateArea: 'Kungsbacka',
    });
    expect(response.data.event.id).toBe('e9');
  });

  it('cancels through events-cancel with the mandatory reason', async () => {
    callAdminMock.mockResolvedValue({ eventId: 'e1', status: 'cancelled' });
    getDocMock
      .mockResolvedValueOnce({
        exists: () => true,
        id: 'e1',
        data: () => ({
          title: 'Träff',
          status: 'cancelled',
          startsAt: ts('2026-08-01T18:00:00Z'),
          approximateArea: 'Kungsbacka',
          rsvpCounts: { going: 0, maybe: 0, not_going: 0 },
          createdAt: ts('2026-07-01T10:00:00Z'),
          updatedAt: ts('2026-07-06T10:00:00Z'),
          cancelledAt: ts('2026-07-06T10:00:00Z'),
        }),
      })
      .mockResolvedValueOnce({ data: () => ({}) });

    await cancelAdminEvent('e1', { reason: 'Väder' });
    expect(callAdminMock).toHaveBeenCalledWith('events-cancel', { eventId: 'e1', reason: 'Väder' });
  });

  it('updates via events-update with { eventId, ...data } then re-reads', async () => {
    callAdminMock.mockResolvedValue({ eventId: 'e1', status: 'draft' });
    getDocMock
      .mockResolvedValueOnce({
        exists: () => true,
        id: 'e1',
        data: () => ({
          title: 'Uppdaterad',
          status: 'draft',
          startsAt: ts('2026-08-01T18:00:00Z'),
          approximateArea: 'Onsala',
          rsvpCounts: { going: 0, maybe: 0, not_going: 0 },
          createdAt: ts('2026-07-01T10:00:00Z'),
          updatedAt: ts('2026-07-06T10:00:00Z'),
        }),
      })
      .mockResolvedValueOnce({ data: () => ({}) });

    const response = await updateAdminEvent('e1', { title: 'Uppdaterad', approximateArea: 'Onsala' });
    expect(callAdminMock).toHaveBeenCalledWith('events-update', {
      eventId: 'e1',
      title: 'Uppdaterad',
      approximateArea: 'Onsala',
    });
    expect(response.data.event.title).toBe('Uppdaterad');
  });

  it('publishes via events-publish with { eventId } then re-reads', async () => {
    callAdminMock.mockResolvedValue({ eventId: 'e1', status: 'published' });
    getDocMock
      .mockResolvedValueOnce({
        exists: () => true,
        id: 'e1',
        data: () => ({
          title: 'Träff',
          status: 'published',
          startsAt: ts('2026-08-01T18:00:00Z'),
          approximateArea: 'Kungsbacka',
          rsvpCounts: { going: 0, maybe: 0, not_going: 0 },
          createdAt: ts('2026-07-01T10:00:00Z'),
          updatedAt: ts('2026-07-06T10:00:00Z'),
        }),
      })
      .mockResolvedValueOnce({ data: () => ({}) });

    const response = await publishAdminEvent('e1');
    expect(callAdminMock).toHaveBeenCalledWith('events-publish', { eventId: 'e1' });
    expect(response.data.event.status).toBe('published');
  });
});

describe('group-drive module', () => {
  it('derives the active buckets from server-side counts (no roster download)', async () => {
    // countByStatus is called in order: joined, on_the_way, arrived.
    getCountMock
      .mockResolvedValueOnce({ data: () => ({ count: 2 }) })
      .mockResolvedValueOnce({ data: () => ({ count: 1 }) })
      .mockResolvedValueOnce({ data: () => ({ count: 1 }) });
    const summary = await loadAdminGroupDriveSummary('e1');
    expect(summary).toEqual({
      totalActive: 4,
      joinedCount: 2,
      onTheWayCount: 1,
      arrivedCount: 1,
    });
    // Roster documents are never fetched — only aggregate counts cross the wire.
    expect(getDocsMock).not.toHaveBeenCalled();
  });

  it('returns null when there is no active group drive', async () => {
    getCountMock.mockResolvedValue({ data: () => ({ count: 0 }) });
    expect(await loadAdminGroupDriveSummary('e1')).toBeNull();
  });
});
