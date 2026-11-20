import { beforeEach, describe, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({
  getDocs: vi.fn(),
  getDoc: vi.fn(),
  query: vi.fn((...args: unknown[]) => args),
  callAdmin: vi.fn(),
}));
vi.mock('@/lib/firebase', () => ({ getFirebaseApp: vi.fn() }));
vi.mock('@/lib/firestore', () => ({ getAdminFirestore: () => 'admin-db' }));
vi.mock('@/lib/callables', () => ({ callAdmin: mocks.callAdmin }));
vi.mock('firebase/firestore', () => ({
  collection: (_db: unknown, name: string) => ({ collection: name }),
  doc: vi.fn(),
  getDoc: mocks.getDoc,
  getDocs: mocks.getDocs,
  query: mocks.query,
  where: (...args: unknown[]) => ({ where: args }),
  orderBy: (...args: unknown[]) => ({ orderBy: args }),
  documentId: () => '__name__',
  limit: (value: number) => ({ limit: value }),
  startAfter: (cursor: unknown) => ({ startAfter: cursor }),
}));
vi.mock('firebase/storage', () => ({ getStorage: vi.fn(), getBlob: vi.fn(), ref: vi.fn() }));
import { adminActivatePartnerOffer } from '@/features/partners';
import {
  getApplication,
  getStatistics,
  listApplications,
  listOffers,
  reviewApplication,
  setMembership,
  statisticsPeriods,
  submitManagedOffer,
  uploadLogo,
  type PortalCursor,
} from '@/features/partners/portal';
function documents(count: number) {
  return Array.from({ length: count }, (_, i) => ({
    id: `doc${i}`,
    data: () => ({
      companyName: `Company ${i}`,
      companyId: 'company',
      status: 'draft',
      createdAt: '2026-10-01T10:00:00Z',
      submittedAt: '2026-10-01T10:00:00Z',
    }),
  }));
}
beforeEach(() => {
  vi.clearAllMocks();
});
describe('admin portal cursor reads', () => {
  it('filters offers on the server before paging, and uses the last visible document as the next cursor', async () => {
    const docs = documents(21);
    mocks.getDocs.mockResolvedValue({ docs });
    const result = await listOffers(null, 'company', 'draft');
    expect(result.items).toHaveLength(20);
    expect(result.nextCursor).toBe(docs[19]);
    expect(mocks.query).toHaveBeenLastCalledWith(
      { collection: 'offers' },
      { where: ['companyId', '==', 'company'] },
      { where: ['status', '==', 'draft'] },
      { orderBy: ['createdAt', 'desc'] },
      { orderBy: ['__name__', 'desc'] },
      { limit: 21 },
    );
    await listOffers(result.nextCursor, 'company', 'draft');
    expect(mocks.query.mock.calls.at(-1)).toContainEqual({ startAfter: docs[19] });
  });
  it('does not advertise a next page when the last page has exactly 20 documents', async () => {
    mocks.getDocs.mockResolvedValue({ docs: documents(20) });
    expect((await listApplications()).nextCursor).toBeNull();
  });
  it('applies application status before the cursor boundary', async () => {
    const cursor = { id: 'last' } as PortalCursor;
    mocks.getDocs.mockResolvedValue({ docs: [] });
    await listApplications(cursor, 'changes_requested');
    expect(mocks.query).toHaveBeenLastCalledWith(
      { collection: 'partnerApplications' },
      { where: ['status', '==', 'changes_requested'] },
      { orderBy: ['submittedAt', 'desc'] },
      { orderBy: ['__name__', 'desc'] },
      { startAfter: cursor },
      { limit: 21 },
    );
  });
  it('preserves the private review reason and the separate public phone', async () => {
    mocks.getDoc.mockResolvedValue({
      id: 'application',
      exists: () => true,
      data: () => ({
        companyName: 'Business',
        reviewReason: 'Supply address',
        contactPhone: 'private',
        publicPhone: 'public',
        selfServiceRequested: true,
        billboardRequested: true,
      }),
    });
    expect(await getApplication('application')).toMatchObject({
      reviewReason: 'Supply address',
      contactPhone: 'private',
      publicPhone: 'public',
      selfServiceRequested: true,
      billboardRequested: true,
    });
  });
});
describe('admin portal callable contracts', () => {
  it('records explicit logo usage-rights confirmation in the upload command', async () => {
    await uploadLogo('company', 'pngdata');
    expect(mocks.callAdmin).toHaveBeenCalledWith('partners-portal', {
      action: 'uploadLogo',
      companyId: 'company',
      logoBase64: 'pngdata',
      logoRightsConfirmed: true,
    });
  });

  it('binds managed offer activation to the version reviewed by the admin', async () => {
    mocks.getDoc.mockResolvedValue({
      id: 'offer',
      exists: () => true,
      data: () => ({ companyId: 'company', title: 'Offer' }),
    });
    await adminActivatePartnerOffer('offer', undefined, 7);
    expect(mocks.callAdmin).toHaveBeenCalledWith('partners-setOfferStatus', {
      offerId: 'offer',
      action: 'activate',
      expectedVersion: 7,
    });
  });

  it('publishes through the atomic review action and never supplies an arbitrary user id', async () => {
    mocks.callAdmin.mockResolvedValue({ status: 'approved', companyId: 'company' });
    await reviewApplication('application', 'publish', {
      expectedRevision: 4,
      grantSelfService: true,
      latitude: 57.5,
      longitude: 12.1,
    });
    expect(mocks.callAdmin).toHaveBeenCalledWith('partners-portal', {
      action: 'reviewApplication',
      applicationId: 'application',
      decision: 'publish',
      expectedRevision: 4,
      grantSelfService: true,
      latitude: 57.5,
      longitude: 12.1,
    });
  });
  it('revokes only a selected company membership, without admin claims', async () => {
    await setMembership('company', 'staff', 'redeemer', false);
    expect(mocks.callAdmin).toHaveBeenCalledWith('partners-portal', {
      action: 'membership',
      companyId: 'company',
      uid: 'staff',
      role: 'redeemer',
      enabled: false,
    });
  });
  it('creates a capacity-limited campaign as a draft using the managed endpoint', async () => {
    const input = {
      companyId: 'company',
      title: 'Rekond',
      teaserText: 'Tre platser',
      description: 'Beskrivning',
      terms: 'Villkor',
      capacity: 3,
      reservationDays: 7,
    };
    await submitManagedOffer(input);
    expect(mocks.callAdmin).toHaveBeenCalledWith('partners-portal', {
      action: 'submitOffer',
      ...input,
    });
  });
  it('loads all-partner aggregates without requesting raw records or personal metrics', async () => {
    await getStatistics(undefined, '2026-10');
    expect(mocks.callAdmin).toHaveBeenCalledWith('partners-portal', {
      action: 'adminStatistics',
      period: '2026-10',
    });
  });
  it('offers only all-time and completed calendar months, including the year boundary', () => {
    expect(statisticsPeriods(new Date('2026-01-01T00:01:00Z')).slice(0, 3)).toEqual([
      'all',
      '2025-12',
      '2025-11',
    ]);
    expect(statisticsPeriods(new Date('2026-11-17T23:59:00Z'))).not.toContain('2026-11');
  });
});
