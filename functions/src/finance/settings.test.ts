import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ actor: vi.fn(), set: vi.fn(), commit: vi.fn(), audit: vi.fn() }));
vi.mock('../access/callable', () => ({ onCall: (_options: unknown, handler: unknown) => handler }));
vi.mock('../admin/actorContext', () => ({ requireAdminActor: mocks.actor }));
vi.mock('../admin/claims-core', () => ({ buildAdminAuditEvent: mocks.audit }));
vi.mock('../firebase', () => ({
  db: {
    collection: (name: string) => ({ doc: (id = 'audit-id') => ({ name, id }) }),
    batch: () => ({ set: mocks.set, commit: mocks.commit }),
  },
}));
import { updateWorkloadAssumptions } from './settings';

const invoke = updateWorkloadAssumptions as unknown as (request: unknown) => Promise<unknown>;
beforeEach(() => {
  vi.clearAllMocks();
  mocks.actor.mockResolvedValue({ uid: 'admin' });
  mocks.audit.mockReturnValue({ audited: true });
  mocks.commit.mockResolvedValue(undefined);
});

describe('finance assumption authorization and audit', () => {
  it('rejects non-admins before any write', async () => {
    mocks.actor.mockRejectedValue(new Error('permission-denied'));
    await expect(invoke({ data: { overrides: [] } })).rejects.toThrow('permission-denied');
    expect(mocks.set).not.toHaveBeenCalled();
  });
  it('writes only known fallback rates and an audit record atomically', async () => {
    const overrides = [{ id: 'roadCameras.photo', eventsPerMemberPerDay: 3 }];
    await expect(invoke({ data: { overrides } })).resolves.toEqual({ ok: true });
    expect(mocks.set).toHaveBeenCalledTimes(2);
    expect(mocks.set.mock.calls[0]![0]).toEqual({ name: 'financeSettings', id: 'workloads' });
    expect(mocks.set.mock.calls[0]![1].overrides).toEqual(overrides);
    expect(mocks.audit.mock.calls[0]![0].action).toBe('finance.updateWorkloadAssumptions');
    expect(mocks.commit).toHaveBeenCalledOnce();
  });
  it('does not allow price, provider, project or arbitrary function injection', async () => {
    for (const data of [
      { overrides: [], project: 'other' },
      { overrides: [{ id: 'roadCameras.photo', eventsPerMemberPerDay: 1, price: 0 }] },
      { overrides: [{ id: 'other', eventsPerMemberPerDay: 1 }] },
    ]) {
      await expect(invoke({ data })).rejects.toMatchObject({ code: 'invalid-argument' });
    }
    expect(mocks.set).not.toHaveBeenCalled();
  });
});
