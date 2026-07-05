/**
 * Unit tests for the Phase 13 migrated admin feature modules
 * (feature-flags, points): adapter behavior over mocked Firebase clients.
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
  DEFAULT_FEATURE_FLAGS,
  loadFeatureFlagRows,
  setFeatureFlag,
} from '../features/feature-flags';
import {
  applyAdminPointsAdjustment,
  getAdminUserPointsBalance,
  getAdminUserPointsLedger,
} from '../features/points';

beforeEach(() => {
  getDocMock.mockReset();
  getDocsMock.mockReset();
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
