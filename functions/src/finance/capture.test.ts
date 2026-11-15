import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ usage: vi.fn(), billing: vi.fn(), set: vi.fn(), warn: vi.fn() }));
vi.mock('./providers', () => ({ collectUsage: mocks.usage, collectBilling: mocks.billing }));
vi.mock('firebase-functions', () => ({ logger: { warn: mocks.warn } }));
vi.mock('../firebase', () => ({
  db: {
    collection: () => ({
      doc: (id: string) => ({
        set: (data: unknown, options: unknown) => mocks.set(id, data, options),
        get: async () => ({ get: () => 'ready' }),
      }),
    }),
  },
}));
import { captureFinanceEvidence } from './capture';

beforeEach(() => {
  vi.clearAllMocks();
  vi.unstubAllEnvs();
  vi.stubEnv('FUNCTIONS_EMULATOR', 'false');
  vi.stubEnv('FIRESTORE_EMULATOR_HOST', '');
  vi.stubEnv('GCLOUD_PROJECT', 'demo-test');
  vi.stubEnv('FINANCE_MONITORING_ENABLED', 'true');
  vi.stubEnv('FINANCE_BILLING_TABLE', 'demo-test.billing.table');
  mocks.set.mockResolvedValue(undefined);
  mocks.usage.mockResolvedValue({ capturedAtMs: 1 });
  mocks.billing.mockResolvedValue({ capturedAtMs: 1 });
});

describe('daily aggregate capture', () => {
  it('never queries Google APIs from emulators', async () => {
    vi.stubEnv('FUNCTIONS_EMULATOR', 'true');
    await captureFinanceEvidence();
    expect(mocks.usage).not.toHaveBeenCalled();
    expect(mocks.billing).not.toHaveBeenCalled();
  });
  it('marks disabled providers as unconfigured, without fabricating zero costs', async () => {
    vi.stubEnv('FINANCE_MONITORING_ENABLED', 'false');
    vi.stubEnv('FINANCE_BILLING_TABLE', '');
    await captureFinanceEvidence();
    expect(mocks.usage).not.toHaveBeenCalled();
    expect(mocks.billing).not.toHaveBeenCalled();
    expect(
      mocks.set.mock.calls.every(
        (call) => call[1].state === 'not-configured' && !('data' in call[1]),
      ),
    ).toBe(true);
  });
  it('keeps last good data on provider failure and independently refreshes the other provider', async () => {
    mocks.usage.mockRejectedValue(new Error('sensitive provider error'));
    await captureFinanceEvidence();
    expect(mocks.set).toHaveBeenCalledWith(
      'usage',
      expect.objectContaining({ state: 'ready', lastAttemptFailed: true }),
      { merge: true },
    );
    expect(mocks.set).toHaveBeenCalledWith(
      'billing',
      expect.objectContaining({ state: 'ready', lastAttemptFailed: false }),
      undefined,
    );
    expect(JSON.stringify(mocks.warn.mock.calls)).not.toContain('sensitive');
  });
});
