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
  resolveEventCreatorName,
  updateAdminEvent,
} from '../features/events';
import { loadAdminGroupDriveSummary } from '../features/group-drive';
import {
  adminCreateBillboard,
  adminListBillboards,
  adminPauseBillboard,
} from '../features/digital-billboards';
import {
  adminActivateCrownHuntPoint,
  adminListCrownHuntClaims,
  adminListCrownHuntPoints,
} from '../features/crown-hunt';
import {
  adminApproveApplication,
  adminCreatePartnerCompany,
  adminGetPartnerOffer,
  adminListPartnerCompanies,
  adminPausePartnerOffer,
} from '../features/partners';
import { awardHelpfulMemberBadge, loadAdminBadgeSummary } from '../features/badges';
import {
  loadAdminChatReports,
  removeAdminChatMessageFromReport,
  resolveAdminChatReport,
} from '../features/event-chat';
import { adminSendNotification } from '../features/notifications';
import {
  adminGetPartnerInsightsSummary,
  periodToBucket,
} from '../features/partner-insights';
import {
  adminGetUserSubscription,
  adminGrantMembership,
  adminRevokeMembership,
} from '../features/subscription';
import {
  adminGetUser,
  adminListUsers,
  adminRestoreAccess,
  adminSetAdminRole,
  adminSuspendUser,
  adminWarnUser,
} from '../features/users';

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

  it('resolves the creator uid to the displayName from users/{uid}', async () => {
    getDocMock.mockResolvedValue({ data: () => ({ displayName: 'Anna Admin' }) });
    expect(await resolveEventCreatorName('admin1')).toBe('Anna Admin');
  });

  it('falls back to the uid when the user doc is missing or displayName is empty', async () => {
    getDocMock.mockResolvedValueOnce({ data: () => undefined });
    expect(await resolveEventCreatorName('admin1')).toBe('admin1');
    getDocMock.mockResolvedValueOnce({ data: () => ({ displayName: '' }) });
    expect(await resolveEventCreatorName('admin1')).toBe('admin1');
  });

  it('falls back to the uid when the users read is not permitted', async () => {
    getDocMock.mockRejectedValueOnce(new Error('permission-denied'));
    expect(await resolveEventCreatorName('admin1')).toBe('admin1');
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

describe('digital-billboards module', () => {
  const ts = (iso: string) => ({ toDate: () => new Date(iso) });
  const billboardData = (overrides: Record<string, unknown> = {}) => ({
    partnerCompanyId: 'c1',
    headline: 'Sommarrea',
    message: 'Boka nu',
    placementType: 'map_billboard',
    latitude: 57.48,
    longitude: 12.07,
    status: 'draft',
    availableFrom: null,
    availableUntil: null,
    callToActionType: 'website',
    callToActionValue: 'https://example.com',
    approvedAt: null,
    approvedByUserId: null,
    safetyNote: null,
    createdByUserId: 'admin1',
    createdAt: ts('2026-07-01T10:00:00Z'),
    updatedAt: ts('2026-07-02T10:00:00Z'),
    ...overrides,
  });

  it('maps billboard docs and resolves the sponsoring company name', async () => {
    getDocsMock.mockResolvedValue({ docs: [{ id: 'b1', data: () => billboardData() }] });
    getDocMock.mockResolvedValue({ data: () => ({ name: 'Acme AB' }) });

    const response = await adminListBillboards();
    expect(response.data.billboards[0]).toMatchObject({
      billboardId: 'b1',
      partnerId: 'c1',
      partnerCompanyName: 'Acme AB',
      status: 'draft',
      activatedAt: null,
      pausedAt: null,
      endedAt: null,
    });
    expect(response.meta.page).toBe(1);
  });

  it('falls back to the company id when the company is not readable', async () => {
    getDocsMock.mockResolvedValue({ docs: [{ id: 'b1', data: () => billboardData() }] });
    getDocMock.mockRejectedValue(new Error('permission-denied'));
    const response = await adminListBillboards();
    expect(response.data.billboards[0]!.partnerCompanyName).toBe('c1');
  });

  it('creates through billboards-create then re-reads', async () => {
    callAdminMock.mockResolvedValue({ billboardId: 'b9', status: 'draft' });
    getDocMock
      .mockResolvedValueOnce({ exists: () => true, id: 'b9', data: () => billboardData() })
      .mockResolvedValueOnce({ data: () => ({ name: 'Acme AB' }) });

    const request = {
      partnerCompanyId: 'c1',
      headline: 'Sommarrea',
      message: 'Boka nu',
      placementType: 'map_billboard' as const,
      latitude: 57.48,
      longitude: 12.07,
    };
    const response = await adminCreateBillboard(request);
    expect(callAdminMock).toHaveBeenCalledWith('billboards-create', request);
    expect(response.data.billboardId).toBe('b9');
  });

  it('pauses through billboards-setStatus with action pause', async () => {
    callAdminMock.mockResolvedValue({ billboardId: 'b1', status: 'paused' });
    getDocMock
      .mockResolvedValueOnce({ exists: () => true, id: 'b1', data: () => billboardData({ status: 'paused' }) })
      .mockResolvedValueOnce({ data: () => ({ name: 'Acme AB' }) });

    await adminPauseBillboard('b1', 'Kampanj slut');
    expect(callAdminMock).toHaveBeenCalledWith('billboards-setStatus', {
      billboardId: 'b1',
      action: 'pause',
      reason: 'Kampanj slut',
    });
  });
});

describe('crown-hunt module', () => {
  const ts = (iso: string) => ({ toDate: () => new Date(iso) });

  it('maps point docs into the admin point summary', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        {
          id: 'p1',
          data: () => ({
            title: 'Torget',
            description: null,
            latitude: 57.48,
            longitude: 12.07,
            geofenceRadiusMeters: 50,
            rewardPoints: 100,
            status: 'draft',
            repeatRule: 'once',
            availableFrom: null,
            availableUntil: null,
            approvedAt: null,
            approvedByUserId: null,
            createdByUserId: 'admin1',
            createdAt: ts('2026-07-01T10:00:00Z'),
            updatedAt: ts('2026-07-02T10:00:00Z'),
          }),
        },
      ],
    });
    const response = await adminListCrownHuntPoints();
    expect(response.data.points[0]).toMatchObject({
      pointId: 'p1',
      rewardPoints: 100,
      status: 'draft',
      repeatRule: 'once',
      totalClaims: 0,
    });
  });

  it('maps claims, joins point titles, and filters by result over the page', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        {
          id: 'cl1',
          data: () => ({
            pointId: 'p1',
            userId: 'u1',
            result: 'risk_review',
            distanceMeters: 12,
            claimedAt: ts('2026-07-05T10:00:00Z'),
          }),
        },
        {
          id: 'cl2',
          data: () => ({
            pointId: 'p1',
            userId: 'u2',
            result: 'awarded',
            distanceMeters: 3,
            claimedAt: ts('2026-07-04T10:00:00Z'),
          }),
        },
      ],
    });
    getDocMock.mockResolvedValue({ data: () => ({ title: 'Torget' }) });

    const response = await adminListCrownHuntClaims(1, 'risk_review');
    expect(response.data.claims).toHaveLength(1);
    expect(response.data.claims[0]).toMatchObject({
      claimId: 'cl1',
      pointTitle: 'Torget',
      result: 'risk_review',
      riskReasonCategories: [],
    });
    // Titles are resolved only for the matching claim, not the filtered-out one.
    expect(getDocMock).toHaveBeenCalledTimes(1);
    // hasNext is based on the unfiltered fetch (2 docs < page size), not the
    // filtered count.
    expect(response.meta.hasNext).toBe(false);
  });

  it('activates through crownHunt-activatePoint with the safety confirmation', async () => {
    callAdminMock.mockResolvedValue({ pointId: 'p1', status: 'active' });
    getDocMock.mockResolvedValue({
      exists: () => true,
      id: 'p1',
      data: () => ({
        title: 'Torget',
        latitude: 57.48,
        longitude: 12.07,
        geofenceRadiusMeters: 50,
        rewardPoints: 100,
        status: 'active',
        repeatRule: 'once',
        createdByUserId: 'admin1',
        createdAt: ts('2026-07-01T10:00:00Z'),
        updatedAt: ts('2026-07-06T10:00:00Z'),
      }),
    });

    const response = await adminActivateCrownHuntPoint('p1', 'Säker plats bekräftad');
    expect(callAdminMock).toHaveBeenCalledWith('crownHunt-activatePoint', {
      pointId: 'p1',
      safeLocationConfirmed: true,
      approvalNote: 'Säker plats bekräftad',
    });
    expect(response.data.status).toBe('active');
  });
});

describe('partners module', () => {
  const ts = (iso: string) => ({ toDate: () => new Date(iso) });

  it('maps company docs (name→companyName, etc.) into the summary', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        {
          id: 'co1',
          data: () => ({
            name: 'Acme AB',
            category: 'workshop',
            status: 'active',
            address: 'Storgatan 1',
            latitude: 57.48,
            longitude: 12.07,
            createdAt: ts('2026-07-01T10:00:00Z'),
            updatedAt: ts('2026-07-02T10:00:00Z'),
          }),
        },
      ],
    });
    const response = await adminListPartnerCompanies();
    expect(response.data.partners[0]).toMatchObject({
      partnerId: 'co1',
      companyName: 'Acme AB',
      category: 'workshop',
      status: 'active',
      activatedAt: null,
    });
  });

  it('merges the offer teaser doc with details/member (no discount code)', async () => {
    getDocMock
      .mockResolvedValueOnce({
        exists: () => true,
        id: 'of1',
        data: () => ({
          companyId: 'co1',
          partnerCompanyName: 'Acme AB',
          title: 'Rabatt',
          teaserText: '10% rabatt',
          offerType: 'percentage_discount',
          status: 'active',
          availableFrom: null,
          availableUntil: null,
          createdAt: ts('2026-07-01T10:00:00Z'),
          updatedAt: ts('2026-07-02T10:00:00Z'),
        }),
      })
      .mockResolvedValueOnce({
        data: () => ({
          description: 'Full beskrivning',
          terms: 'Gäller ej helgdagar',
          percentageDiscount: 10,
        }),
      });

    const detail = await adminGetPartnerOffer('of1');
    expect(detail).toMatchObject({
      offerId: 'of1',
      partnerId: 'co1',
      description: 'Full beskrivning',
      terms: 'Gäller ej helgdagar',
      percentageDiscount: 10,
    });
    expect('discountCode' in detail).toBe(false);
  });

  it('maps the create-company request onto the strict callable payload', async () => {
    callAdminMock.mockResolvedValue({ companyId: 'co9' });
    getDocMock.mockResolvedValue({
      exists: () => true,
      id: 'co9',
      data: () => ({
        name: 'Nyföretag',
        category: 'retail',
        status: 'draft',
        createdByUserId: 'admin1',
        createdAt: ts('2026-07-06T10:00:00Z'),
        updatedAt: ts('2026-07-06T10:00:00Z'),
      }),
    });

    const detail = await adminCreatePartnerCompany({
      companyName: 'Nyföretag',
      category: 'retail',
      publicDescription: 'Beskrivning',
      address: 'Storgatan 2',
      latitude: 57.5,
      longitude: 12.1,
      publicPhone: '0300-123',
      publicWebsiteUrl: 'https://ex.se',
    });
    expect(callAdminMock).toHaveBeenCalledWith('partners-createCompany', {
      name: 'Nyföretag',
      category: 'retail',
      description: 'Beskrivning',
      address: 'Storgatan 2',
      latitude: 57.5,
      longitude: 12.1,
      phone: '0300-123',
      website: 'https://ex.se',
    });
    expect(detail.partnerId).toBe('co9');
  });

  it('approves an application through partners-reviewApplication', async () => {
    callAdminMock.mockResolvedValue({
      applicationId: 'ap1',
      status: 'approved',
      partnerCompanyId: 'co5',
    });
    const result = await adminApproveApplication('ap1');
    expect(callAdminMock).toHaveBeenCalledWith('partners-reviewApplication', {
      applicationId: 'ap1',
      action: 'approve',
    });
    expect(result).toEqual({ partnerCompanyId: 'co5' });
  });

  it('throws when approve returns no partner company id', async () => {
    callAdminMock.mockResolvedValue({ applicationId: 'ap1', status: 'approved', partnerCompanyId: null });
    await expect(adminApproveApplication('ap1')).rejects.toMatchObject({ statusCode: 500 });
  });

  it('pauses an offer through partners-setOfferStatus with a reason', async () => {
    callAdminMock.mockResolvedValue({ offerId: 'of1', status: 'paused' });
    getDocMock
      .mockResolvedValueOnce({
        exists: () => true,
        id: 'of1',
        data: () => ({
          companyId: 'co1',
          status: 'paused',
          offerType: 'percentage_discount',
          createdAt: ts('2026-07-01T10:00:00Z'),
          updatedAt: ts('2026-07-06T10:00:00Z'),
        }),
      })
      .mockResolvedValueOnce({ data: () => ({}) });

    await adminPausePartnerOffer('of1', 'Kampanj pausad');
    expect(callAdminMock).toHaveBeenCalledWith('partners-setOfferStatus', {
      offerId: 'of1',
      action: 'pause',
      reason: 'Kampanj pausad',
    });
  });
});

describe('badges module', () => {
  it('wraps the badges-adminSummary payload in the REST envelope', async () => {
    callAdminMock.mockResolvedValue({
      summary: [
        { key: 'first_event', name: 'Första träffen', totalCount: 12, recentCount: 3 },
        { key: 'helpful_member', name: 'Hjälpsam medlem', totalCount: 4, recentCount: 1 },
      ],
    });
    const response = await loadAdminBadgeSummary();
    expect(callAdminMock).toHaveBeenCalledWith('badges-adminSummary', {});
    expect(response.ok).toBe(true);
    expect(response.data.summary).toHaveLength(2);
    expect(response.data.summary[0]).toMatchObject({ key: 'first_event', totalCount: 12 });
  });

  it('maps the award request onto the callable payload and shapes a fresh award', async () => {
    callAdminMock.mockResolvedValue({
      targetUid: 'user1',
      badgeKey: 'helpful_member',
      alreadyAwarded: false,
    });
    const response = await awardHelpfulMemberBadge('user1', { reason: 'Hjälpte till på träffen' });
    expect(callAdminMock).toHaveBeenCalledWith('badges-awardHelpfulMember', {
      targetUid: 'user1',
      reason: 'Hjälpte till på träffen',
    });
    expect(response.data.alreadyAwarded).toBe(false);
    expect(response.data.badge).toMatchObject({ key: 'helpful_member', name: 'Hjälpsam medlem' });
    // A fresh award carries a real ISO timestamp (client-now ≈ server time).
    expect(response.data.badge.awardedAt).not.toBe('');
    expect(Number.isNaN(Date.parse(response.data.badge.awardedAt))).toBe(false);
  });

  it('reports an idempotent repeat award without fabricating an awardedAt', async () => {
    callAdminMock.mockResolvedValue({
      targetUid: 'user1',
      badgeKey: 'helpful_member',
      alreadyAwarded: true,
    });
    const response = await awardHelpfulMemberBadge('user1', { reason: 'Igen' });
    expect(response.data.alreadyAwarded).toBe(true);
    // No fabricated "new award" time for an idempotent repeat.
    expect(response.data.badge.awardedAt).toBe('');
  });
});

describe('event-chat module', () => {
  it('maps listChatReports rows (eventId + reporter) and forwards the status filter', async () => {
    callAdminMock.mockResolvedValue({
      reports: [
        {
          id: 'r1',
          eventId: 'ev1',
          messageId: 'm1',
          reporterUserId: 'reporter1',
          reason: 'spam',
          details: 'Länkspam',
          status: 'new',
          createdAt: '2026-07-05T10:00:00.000Z',
          reviewedAt: null,
          reviewedByUserId: null,
        },
      ],
      meta: { page: 1, pageSize: 20, total: 1, hasNext: false },
    });
    const response = await loadAdminChatReports({ status: 'new', pageSize: 20 });
    expect(callAdminMock).toHaveBeenCalledWith('events-listChatReports', {
      status: 'new',
      pageSize: 20,
    });
    expect(response.data.reports[0]).toMatchObject({
      id: 'r1',
      eventId: 'ev1',
      messageId: 'm1',
      reporterUserId: 'reporter1',
      reason: 'spam',
      status: 'new',
    });
    expect(response.meta.total).toBe(1);
  });

  it('omits absent params from the callable payload', async () => {
    callAdminMock.mockResolvedValue({
      reports: [],
      meta: { page: 1, pageSize: 20, total: 0, hasNext: false },
    });
    await loadAdminChatReports();
    expect(callAdminMock).toHaveBeenCalledWith('events-listChatReports', {});
  });

  it('resolves a report via events-resolveChatReport with its eventId', async () => {
    callAdminMock.mockResolvedValue({ reportId: 'r1', status: 'dismissed' });
    const result = await resolveAdminChatReport('ev1', 'r1', 'dismissed');
    expect(callAdminMock).toHaveBeenCalledWith('events-resolveChatReport', {
      eventId: 'ev1',
      reportId: 'r1',
      status: 'dismissed',
    });
    expect(result.status).toBe('dismissed');
  });

  it('removes the offending message via events-removeChatMessage', async () => {
    callAdminMock.mockResolvedValue({
      eventId: 'ev1',
      messageId: 'm1',
      moderationState: 'removed',
    });
    const result = await removeAdminChatMessageFromReport('ev1', 'm1', 'Regelbrott');
    expect(callAdminMock).toHaveBeenCalledWith('events-removeChatMessage', {
      eventId: 'ev1',
      messageId: 'm1',
      reason: 'Regelbrott',
    });
    expect(result.moderationState).toBe('removed');
  });
});

describe('notifications module', () => {
  it('sends via notifications-adminSend and wraps the result in the envelope', async () => {
    callAdminMock.mockResolvedValue({
      batchId: 'b1',
      audience: 'members',
      recipientCount: 42,
      createdAt: '2026-07-06T10:00:00.000Z',
    });
    const response = await adminSendNotification({
      category: 'admin_message',
      audience: 'members',
      title: 'Hej',
      previewText: 'Kort',
      body: 'Meddelande',
      reason: 'Informationsutskick',
      idempotencyKey: 'key-1',
    });
    expect(callAdminMock).toHaveBeenCalledWith('notifications-adminSend', {
      category: 'admin_message',
      audience: 'members',
      title: 'Hej',
      previewText: 'Kort',
      body: 'Meddelande',
      reason: 'Informationsutskick',
      idempotencyKey: 'key-1',
    });
    expect(response).toEqual({
      ok: true,
      data: { batchId: 'b1', audience: 'members', recipientCount: 42, createdAt: '2026-07-06T10:00:00.000Z' },
    });
  });

  it('drops undefined optionals and forwards the confirmation flag', async () => {
    callAdminMock.mockResolvedValue({
      batchId: 'b2',
      audience: 'all_users',
      recipientCount: 3,
      createdAt: '2026-07-06T11:00:00.000Z',
    });
    await adminSendNotification({
      category: 'admin_message',
      audience: 'all_users',
      title: 'Alla',
      previewText: 'Kort',
      body: 'Text',
      reason: 'Viktigt',
      idempotencyKey: 'key-2',
      eventId: undefined,
      confirmed: true,
    });
    const payload = callAdminMock.mock.calls[0]![1] as Record<string, unknown>;
    expect('eventId' in payload).toBe(false);
    expect(payload.confirmed).toBe(true);
  });
});

describe('partner-insights module', () => {
  it('maps admin period options onto backend calendar buckets with an explicit date', () => {
    // Every option pins an explicit reference date so the backend never falls
    // back to its "yesterday" default (which would drift on boundary days).
    for (const [period, expectedType] of [
      ['last_7_days', 'week'],
      ['last_30_days', 'month'],
      ['current_month', 'month'],
      ['previous_month', 'month'],
    ] as const) {
      const bucket = periodToBucket(period);
      expect(bucket.periodType).toBe(expectedType);
      expect(typeof bucket.date).toBe('string');
      expect(Number.isNaN(Date.parse(bucket.date!))).toBe(false);
    }
  });

  it('summarizes via partnerInsights-adminSummary and adapts the metrics', async () => {
    callAdminMock.mockResolvedValue({
      companyId: 'co1',
      periodType: 'month',
      periodStart: '2026-07-01',
      metrics: [
        { interactionType: 'map_view', totalCount: 42, uniqueContributorCount: null, status: 'available' },
        {
          interactionType: 'anonymous_pass_by',
          totalCount: 0,
          uniqueContributorCount: null,
          status: 'insufficient_data',
        },
      ],
    });
    const summary = await adminGetPartnerInsightsSummary('co1', 'last_30_days');
    expect(callAdminMock).toHaveBeenCalledWith(
      'partnerInsights-adminSummary',
      expect.objectContaining({ companyId: 'co1', periodType: 'month' }),
    );
    // An explicit reference date is always sent (avoids the backend's yesterday default).
    const payload = callAdminMock.mock.calls[0]![1] as Record<string, unknown>;
    expect(typeof payload.date).toBe('string');
    expect(summary.partnerId).toBe('co1');
    expect(summary.period).toBe('last_30_days');
    expect(summary.metrics[0]).toMatchObject({
      interactionType: 'map_view',
      totalCount: 42,
      status: 'available',
      periodStart: '2026-07-01',
    });
    // uniqueContributorCount omitted when null.
    expect('uniqueContributorCount' in summary.metrics[0]!).toBe(false);
    expect(summary.metrics[1]).toMatchObject({ status: 'insufficient_data', totalCount: 0 });
  });

  it('passes a reference date for the previous_month option', async () => {
    callAdminMock.mockResolvedValue({
      companyId: 'co1',
      periodType: 'month',
      periodStart: '2026-06-01',
      metrics: [],
    });
    // Capture the clock in a window around the call: periodToBucket reads its
    // own `new Date()` internally, so at a UTC month boundary the reference
    // month could be `before`'s or `after`'s. Accepting either avoids a flaky
    // failure if the month rolls over mid-test.
    const before = new Date();
    await adminGetPartnerInsightsSummary('co1', 'previous_month');
    const after = new Date();
    const payload = callAdminMock.mock.calls[0]![1] as Record<string, unknown>;
    expect(payload.periodType).toBe('month');
    expect(typeof payload.date).toBe('string');

    const prevMonthOf = (d: Date) => {
      let year = d.getUTCFullYear();
      let month = d.getUTCMonth() - 1;
      if (month < 0) {
        month = 11;
        year -= 1;
      }
      return { year, month };
    };
    const sent = new Date(payload.date as string);
    const candidates = [prevMonthOf(before), prevMonthOf(after)];
    expect(
      candidates.some((c) => sent.getUTCFullYear() === c.year && sent.getUTCMonth() === c.month),
    ).toBe(true);
  });
});

describe('subscription module', () => {
  const ts = (iso: string) => ({ toDate: () => new Date(iso) });

  it('builds the summary from subscriptions/{uid} + users/{uid}', async () => {
    getDocMock
      .mockResolvedValueOnce({
        data: () => ({
          platform: 'manual',
          status: 'active',
          entitlement: 'member_monthly',
          expiresAt: ts('2026-12-31T00:00:00Z'),
        }),
      })
      .mockResolvedValueOnce({ data: () => ({ suspended: false }) });
    const summary = await adminGetUserSubscription('u1');
    expect(summary).toMatchObject({
      userId: 'u1',
      entitlement: 'member_monthly',
      isSuspendedWithActiveSubscription: false,
    });
    expect(summary.subscription).toMatchObject({
      platform: 'manual',
      status: 'active',
      expiresAt: '2026-12-31T00:00:00.000Z',
    });
  });

  it('flags a suspended user who still holds an active subscription', async () => {
    getDocMock
      .mockResolvedValueOnce({
        data: () => ({ platform: 'manual', status: 'active', entitlement: 'member_monthly' }),
      })
      .mockResolvedValueOnce({ data: () => ({ suspended: true }) });
    const summary = await adminGetUserSubscription('u1');
    expect(summary.isSuspendedWithActiveSubscription).toBe(true);
  });

  it('resolves a missing subscription to entitlement none', async () => {
    getDocMock
      .mockResolvedValueOnce({ data: () => undefined })
      .mockResolvedValueOnce({ data: () => ({ suspended: false }) });
    const summary = await adminGetUserSubscription('u1');
    expect(summary.entitlement).toBe('none');
    expect(summary.subscription).toBeNull();
  });

  it('coerces malformed stored status/platform/entitlement to safe defaults', async () => {
    getDocMock
      .mockResolvedValueOnce({
        data: () => ({
          platform: 'bogus_platform',
          status: 'not_a_status',
          entitlement: 'weird_entitlement',
        }),
      })
      .mockResolvedValueOnce({ data: () => ({ suspended: false }) });
    const summary = await adminGetUserSubscription('u1');
    expect(summary.entitlement).toBe('none');
    expect(summary.subscription).toMatchObject({
      platform: 'manual',
      status: 'inactive',
      entitlement: 'none',
    });
  });

  it('grants and revokes membership via subscription-grantEntitlement', async () => {
    callAdminMock.mockResolvedValue({ targetUid: 'u1', entitlement: 'member_monthly' });
    await adminGrantMembership('u1', 'Kampanj');
    expect(callAdminMock).toHaveBeenCalledWith('subscription-grantEntitlement', {
      targetUid: 'u1',
      entitlement: 'member_monthly',
      reason: 'Kampanj',
    });

    callAdminMock.mockResolvedValue({ targetUid: 'u1', entitlement: 'none' });
    await adminRevokeMembership('u1', 'Återbetalning');
    expect(callAdminMock).toHaveBeenLastCalledWith('subscription-grantEntitlement', {
      targetUid: 'u1',
      entitlement: 'none',
      reason: 'Återbetalning',
    });
  });
});

describe('users module', () => {
  const ts = (iso: string) => ({ toDate: () => new Date(iso) });

  it('maps user docs into the admin summary (newest-first list)', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        {
          id: 'u1',
          data: () => ({
            displayName: 'Anna',
            role: 'admin',
            activeMember: true,
            suspended: false,
            deleted: false,
            createdAt: ts('2026-07-01T10:00:00Z'),
            onboardingCompletedAt: ts('2026-07-01T10:05:00Z'),
          }),
        },
      ],
    });
    const users = await adminListUsers();
    expect(users).toHaveLength(1);
    expect(users[0]).toEqual({
      uid: 'u1',
      displayName: 'Anna',
      role: 'admin',
      activeMember: true,
      suspended: false,
      deleted: false,
      createdAt: '2026-07-01T10:00:00.000Z',
      onboardingCompletedAt: '2026-07-01T10:05:00.000Z',
    });
  });

  it('coerces malformed role/booleans to safe defaults in the list', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        {
          id: 'u2',
          data: () => ({
            // Unknown role must never read as admin/owner.
            role: 'superuser',
            // Non-boolean truthy values must not flip the flags on.
            activeMember: 'yes',
            suspended: 1,
            deleted: null,
            // Missing displayName / createdAt / onboardingCompletedAt.
          }),
        },
      ],
    });
    const users = await adminListUsers();
    expect(users[0]).toEqual({
      uid: 'u2',
      displayName: '',
      role: 'user',
      activeMember: false,
      suspended: false,
      deleted: false,
      createdAt: null,
      onboardingCompletedAt: null,
    });
  });

  it('maps the user detail (users/{uid} only) with the safe fields', async () => {
    getDocMock.mockResolvedValue({
      data: () => ({
        displayName: 'Bertil',
        role: 'user',
        activeMember: false,
        suspended: true,
        deleted: false,
        bio: 'Bilentusiast',
        createdAt: ts('2026-06-01T10:00:00Z'),
        updatedAt: ts('2026-07-02T10:00:00Z'),
      }),
    });
    const detail = await adminGetUser('u3');
    expect(detail).toEqual({
      uid: 'u3',
      displayName: 'Bertil',
      role: 'user',
      activeMember: false,
      suspended: true,
      deleted: false,
      bio: 'Bilentusiast',
      createdAt: '2026-06-01T10:00:00.000Z',
      updatedAt: '2026-07-02T10:00:00.000Z',
    });
  });

  it('resolves a missing user document to null', async () => {
    getDocMock.mockResolvedValue({ data: () => undefined });
    expect(await adminGetUser('missing')).toBeNull();
  });

  it('warns via admin-warnUser with the exact payload', async () => {
    callAdminMock.mockResolvedValue({ targetUid: 'u1', actionId: 'a1' });
    const result = await adminWarnUser('u1', 'Regelbrott');
    expect(callAdminMock).toHaveBeenCalledWith('admin-warnUser', {
      targetUid: 'u1',
      reason: 'Regelbrott',
    });
    expect(result).toEqual({ targetUid: 'u1', actionId: 'a1' });
  });

  it('suspends via admin-suspendUser with the exact payload', async () => {
    callAdminMock.mockResolvedValue({ targetUid: 'u1', suspended: true });
    const result = await adminSuspendUser('u1', 'Upprepade överträdelser');
    expect(callAdminMock).toHaveBeenCalledWith('admin-suspendUser', {
      targetUid: 'u1',
      reason: 'Upprepade överträdelser',
    });
    expect(result.suspended).toBe(true);
  });

  it('restores via admin-restoreAccess with the exact payload', async () => {
    callAdminMock.mockResolvedValue({ targetUid: 'u1', suspended: false });
    const result = await adminRestoreAccess('u1', 'Överklagan godkänd');
    expect(callAdminMock).toHaveBeenCalledWith('admin-restoreAccess', {
      targetUid: 'u1',
      reason: 'Överklagan godkänd',
    });
    expect(result.suspended).toBe(false);
  });

  it('grants and revokes admin via admin-setAdminRole with the boolean flag', async () => {
    callAdminMock.mockResolvedValue({ targetUid: 'u1', role: 'admin', admin: true });
    await adminSetAdminRole('u1', true, 'Ny moderator');
    expect(callAdminMock).toHaveBeenCalledWith('admin-setAdminRole', {
      targetUid: 'u1',
      admin: true,
      reason: 'Ny moderator',
    });

    callAdminMock.mockResolvedValue({ targetUid: 'u1', role: 'user', admin: false });
    await adminSetAdminRole('u1', false, 'Roll borttagen');
    expect(callAdminMock).toHaveBeenLastCalledWith('admin-setAdminRole', {
      targetUid: 'u1',
      admin: false,
      reason: 'Roll borttagen',
    });
  });
});
