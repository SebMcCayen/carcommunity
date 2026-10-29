import { beforeEach, describe, expect, it, vi } from 'vitest';

const fake = vi.hoisted(() => {
  const uid = 'guard-owner';
  const state = {
    events: [] as string[],
    guard: true,
    processed: false,
    authGone: false,
    authError: null as null | string,
    guardError: false,
  };
  const ref = (path: string) => ({
    path,
    id: path.split('/').at(-1),
    get: async () => ({ exists: false, data: () => undefined }),
    set: async () => undefined,
    update: async () => {
      state.events.push('processed');
      state.processed = true;
    },
    delete: async () => {
      if (path === `chatSendGuards/${uid}`) {
        state.events.push('guard-delete');
        if (state.guardError) throw Error('guard delete failed');
        state.guard = false;
      } else state.events.push(`delete:${path}`);
    },
  });
  const query = (path: string) => ({
    where() {
      return this;
    },
    orderBy() {
      return this;
    },
    limit() {
      return this;
    },
    startAfter() {
      return this;
    },
    doc: (id: string) => ref(`${path}/${id}`),
    get: async () =>
      path === 'accountDeletionRequests'
        ? { empty: false, size: 1, docs: [{ id: uid, ref: ref(`${path}/${uid}`) }] }
        : { empty: true, size: 0, docs: [] },
  });
  const db = {
    collection: query,
    collectionGroup: query,
    recursiveDelete: async (r: { path: string }) => {
      state.events.push(`tree:${r.path}`);
    },
    batch: () => ({ delete: () => undefined, set: () => undefined, commit: async () => undefined }),
  };
  return {
    uid,
    state,
    db,
    adminAuth: {
      deleteUser: async () => {
        state.events.push('auth-delete');
        if (state.authError)
          throw Object.assign(Error('auth delete failed'), { code: state.authError });
        if (state.authGone)
          throw Object.assign(Error('already gone'), { code: 'auth/user-not-found' });
        state.authGone = true;
      },
    },
    adminRtdb: { ref: () => ({ set: async () => undefined }) },
    adminStorage: { bucket: () => ({ deleteFiles: async () => undefined }) },
  };
});
vi.mock('../firebase', () => fake);
vi.mock('firebase-functions', () => ({ logger: { error: vi.fn(), warn: vi.fn(), info: vi.fn() } }));
import { purgeUserData, runAccountPurge } from './scheduled';
import { PURGE_DOC_TREES } from './deletion-core';

beforeEach(() =>
  Object.assign(fake.state, {
    events: [],
    guard: true,
    processed: false,
    authGone: false,
    authError: null,
    guardError: false,
  }),
);
describe('canonical post-Auth shared guard erasure', () => {
  it('deletes guard only after Auth succeeds and before progress/request completion', async () => {
    expect(PURGE_DOC_TREES as readonly string[]).not.toContain('chatSendGuards');
    expect((await runAccountPurge(new Date())).purgedCount).toBe(1);
    const steps = fake.state.events;
    expect(steps.indexOf('auth-delete')).toBeLessThan(steps.indexOf('guard-delete'));
    expect(steps.indexOf('guard-delete')).toBeLessThan(
      steps.indexOf(`delete:accountPurgeProgress/${fake.uid}`),
    );
    expect(steps.indexOf('guard-delete')).toBeLessThan(steps.indexOf('processed'));
    expect(fake.state.guard).toBe(false);
  });
  it('preserves guard and pending request when Auth fails; retry completes', async () => {
    fake.state.authError = 'auth/internal-error';
    expect((await runAccountPurge(new Date())).purgedCount).toBe(0);
    expect(fake.state.guard).toBe(true);
    expect(fake.state.events).not.toContain('guard-delete');
    expect(fake.state.processed).toBe(false);
    fake.state.authError = null;
    expect((await runAccountPurge(new Date())).purgedCount).toBe(1);
    expect(fake.state.guard).toBe(false);
  });
  it('propagates guard failure and retries safely after Auth already disappeared', async () => {
    fake.state.guardError = true;
    await expect(purgeUserData(fake.uid)).rejects.toThrow('guard delete failed');
    expect(fake.state.authGone).toBe(true);
    expect(fake.state.guard).toBe(true);
    expect(fake.state.events).not.toContain(`delete:accountPurgeProgress/${fake.uid}`);
    expect((await runAccountPurge(new Date())).purgedCount).toBe(0);
    expect(fake.state.processed).toBe(false);
    fake.state.guardError = false;
    expect((await runAccountPurge(new Date())).purgedCount).toBe(1);
    expect(fake.state.guard).toBe(false);
    expect(fake.state.processed).toBe(true);
  });
  it('accepts auth/user-not-found on the first purge and remains idempotent', async () => {
    fake.state.authError = 'auth/user-not-found';
    await purgeUserData(fake.uid);
    await purgeUserData(fake.uid);
    expect(fake.state.guard).toBe(false);
    expect(fake.state.events.filter((step) => step === 'guard-delete')).toHaveLength(2);
  });
});
