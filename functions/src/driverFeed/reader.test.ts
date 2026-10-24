import { Timestamp, type Firestore } from 'firebase-admin/firestore';
import type { CallableRequest } from 'firebase-functions/v2/https';
import { describe, expect, it } from 'vitest';
import { DriverFeedReader } from './reader';
import { cursorSchema, profileFailure } from './contract';

type Data = Record<string, unknown>;
const VIEWER = 'viewer_0001';
const AUTHOR = 'author_0001';
const THREAD = 'thread_0001';
const NOW = 1_800_000_000_000;
const stamp = (nanos = 1) => new Timestamp(1_700_000_000, nanos);
const request = (data: unknown = {}) => ({
  data, auth: { uid: VIEWER, token: {} } as CallableRequest['auth'],
});
const profile = { displayName: 'Canonical name', onboardingCompletedAt: stamp() };
const driver = { handle: 'driver_handle', onboardingComplete: true };
const content = (authorUid = AUTHOR, nanos = 1): Data => ({
  authorUid, authorName: 'Stale name', authorHandle: 'stale_handle',
  text: 'Actual content', status: 'active', createdAt: stamp(nanos),
  likeCount: 2, replyCount: 3, mediaUrls: [],
});

// Transaction/query double exercises the reader's real joins, not a separate
// reimplementation of its authorization. Emulator tests pin real Firestore ordering.
class MemoryStore {
  docs = new Map<string, Data>();
  queries: Array<{ path: string; limit: number; direction: string; cursor?: unknown[] }> = [];
  reads: string[] = [];
  writes: string[] = [];
  failurePath?: string;
  beforeReadOnly?: () => void;
  get db(): Firestore {
    return {
      doc: (path: string) => this.ref(path),
      collection: (path: string) => new MemoryQuery(this, path),
      runTransaction: async (run: (tx: unknown) => Promise<unknown>, options?: { readOnly?: boolean }) => {
        if (options?.readOnly) this.beforeReadOnly?.();
        return run({
          get: async (ref: { path: string } | MemoryQuery) =>
            ref instanceof MemoryQuery ? ref.execute() : this.snapshot(ref.path),
          getAll: async (...refs: Array<{ path: string }>) => refs.map((ref) => this.snapshot(ref.path)),
          set: (ref: { path: string }, data: Data) => {
            this.writes.push(ref.path);
            this.docs.set(ref.path, data);
          },
        });
      },
    } as unknown as Firestore;
  }
  ref(path: string) {
    return { path, collection: (name: string) => new MemoryQuery(this, `${path}/${name}`) };
  }
  snapshot(path: string) {
    this.reads.push(path);
    if (path === this.failurePath) throw new Error('read failed');
    const data = this.docs.get(path);
    return { id: path.split('/').at(-1)!, exists: !!data, data: () => data };
  }
}
class MemoryQuery {
  direction = 'asc';
  cap = 0;
  cursor?: [Timestamp, string];
  constructor(readonly store: MemoryStore, readonly path: string) {}
  orderBy(_field: unknown, direction: string) { this.direction = direction; return this; }
  limit(cap: number) { this.cap = cap; return this; }
  startAfter(timestamp: Timestamp, id: string) { this.cursor = [timestamp, id]; return this; }
  execute() {
    const compare = (time: Timestamp, id: string, other: Timestamp, otherId: string) =>
      (time.seconds - other.seconds || time.nanoseconds - other.nanoseconds ||
        (id < otherId ? -1 : id > otherId ? 1 : 0)) * (this.direction === 'asc' ? 1 : -1);
    this.store.queries.push({ path: this.path, limit: this.cap, direction: this.direction, cursor: this.cursor });
    const paths = [...this.store.docs.keys()].filter((path) =>
      path.startsWith(`${this.path}/`) && path.split('/').length === this.path.split('/').length + 1 &&
      this.store.docs.get(path)?.createdAt instanceof Timestamp);
    paths.sort((a, b) => compare(this.store.docs.get(a)!.createdAt as Timestamp, a,
      this.store.docs.get(b)!.createdAt as Timestamp, b));
    const selected = paths.filter((path) => !this.cursor || compare(
      this.store.docs.get(path)!.createdAt as Timestamp, path.split('/').at(-1)!, ...this.cursor,
    ) > 0).slice(0, this.cap);
    return { docs: selected.map((path) => this.store.snapshot(path)) };
  }
}
function setup() {
  const store = new MemoryStore();
  for (const uid of [VIEWER, AUTHOR]) {
    store.docs.set(`users/${uid}`, { ...profile });
    store.docs.set(`driverUsers/${uid}`, { ...driver });
  }
  store.docs.set(`driverThreads/${THREAD}`, content());
  return { store, reader: new DriverFeedReader(store.db, () => NOW) };
}

describe('Driver read bridge guards and payload', () => {
  it('requires authentication before reads', async () => {
    const { store, reader } = setup();
    await expect(reader.list({ data: {}, auth: undefined })).rejects.toMatchObject({ code: 'unauthenticated' });
    expect(store.reads).toEqual([]);
  });
  it('uses canonical identity and projects only the allowlisted public fields', async () => {
    const { store, reader } = setup();
    store.docs.get(`driverThreads/${THREAD}`)!.privateField = 'must not escape';
    const page = await reader.list(request());
    expect(page.posts).toEqual([{
      id: THREAD, authorUid: AUTHOR, authorName: 'Canonical name', authorHandle: 'driver_handle',
      text: 'Actual content', createdAtMillis: 1_700_000_000_000, mediaUrls: [], likeCount: 2, replyCount: 3,
    }]);
    expect(store.writes).toEqual([`driverRateLimits/${VIEWER}_nativeFeedRead`]);
  });
  it.each<[string, string, Data | undefined, string]>([
    ['canonical missing', 'users', undefined, 'canonical-onboarding-required'],
    ['canonical onboarding', 'users', { displayName: 'Name' }, 'canonical-onboarding-required'],
    ['canonical suspended', 'users', { ...profile, suspended: true }, 'account-restricted'],
    ['canonical deleted', 'users', { ...profile, deleted: true }, 'account-restricted'],
    ['Driver missing', 'driverUsers', undefined, 'driver-onboarding-required'],
    ['Driver onboarding', 'driverUsers', { ...driver, onboardingComplete: false }, 'driver-onboarding-required'],
    ...['suspended', 'deleted', 'deletionPending', 'unknown'].map((status) =>
      [status, 'driverUsers', { ...driver, status }, 'account-restricted'] as [string, string, Data, string]),
  ])('denies %s with a stable reason and no writes', async (_label, collection, data, reason) => {
    const { store, reader } = setup();
    const path = `${collection}/${VIEWER}`;
    if (data) store.docs.set(path, data as Data); else store.docs.delete(path);
    await expect(reader.list(request())).rejects.toMatchObject({ details: { reason } });
    expect(store.queries).toEqual([]);
    expect(store.writes).toEqual([]);
  });
  it('rejects the suspended auth claim and does not grant a KCC or Driver staff bypass', async () => {
    const { store, reader } = setup();
    const caller = request();
    caller.auth!.token.suspended = true;
    await expect(reader.list(caller)).rejects.toMatchObject({ code: 'permission-denied' });
    caller.auth!.token.suspended = false;
    caller.auth!.token.admin = true;
    caller.auth!.token.driverRole = 'superAdmin';
    store.docs.get(`driverThreads/${THREAD}`)!.status = 'hidden';
    expect((await reader.list(caller)).posts).toEqual([]);
  });
  it('rechecks viewer suspension after quota charge, in the page snapshot', async () => {
    const { store, reader } = setup();
    store.beforeReadOnly = () => { store.docs.get(`users/${VIEWER}`)!.suspended = true; };
    await expect(reader.list(request())).rejects.toMatchObject({ details: { reason: 'account-restricted' } });
    expect(store.queries).toEqual([]);
  });
  it.each(['suspended', 'deleted', 'deletionPending'])('filters %s authors', async (status) => {
    const { store, reader } = setup();
    store.docs.get(`driverUsers/${AUTHOR}`)!.status = status;
    expect((await reader.list(request())).posts).toEqual([]);
  });
  it('does not treat forged onboarding strings as a completed profile', () => {
    expect(profileFailure({ onboardingCompletedAt: 'yes' }, driver)).toBe('canonical-onboarding-required');
  });
});

describe('Driver and canonical KCC block enforcement', () => {
  const edges = [
    `driverUsers/${VIEWER}/blockedUsers/${AUTHOR}`,
    `driverUsers/${AUTHOR}/blockedUsers/${VIEWER}`,
    `userBlocks/${VIEWER}/blocked/${AUTHOR}`,
    `userBlocks/${AUTHOR}/blocked/${VIEWER}`,
  ];
  it.each(edges)('hides posts and denies detail for %s even without the mirror', async (path) => {
    const { store, reader } = setup();
    store.docs.set(path, {});
    expect((await reader.list(request())).posts).toEqual([]);
    await expect(reader.detail(request({ threadId: THREAD }))).rejects.toMatchObject({ code: 'not-found' });
    expect(store.queries).toHaveLength(1); // denied parent never queries replies
  });
  it('also honors the canonical visibility mirror', async () => {
    const { store, reader } = setup();
    store.docs.set(`blockVisibility/${VIEWER}`, { hiddenUids: [AUTHOR] });
    expect((await reader.list(request())).posts).toEqual([]);
  });
  it.each(edges)('filters reply authors independently for %s', async (path) => {
    const { store, reader } = setup();
    store.docs.set(`driverThreads/${THREAD}`, content(VIEWER));
    store.docs.set(`driverThreads/${THREAD}/replies/reply_00001`, content());
    store.docs.set(path, {});
    const page = await reader.detail(request({ threadId: THREAD }));
    expect(page.post.authorUid).toBe(VIEWER);
    expect(page.comments).toEqual([]);
  });
  it('propagates policy-read failures instead of treating them as no block', async () => {
    const { store, reader } = setup();
    store.failurePath = `driverUsers/${AUTHOR}/blockedUsers/${VIEWER}`;
    await expect(reader.list(request())).rejects.toThrow('read failed');
  });
});

describe('bounded pagination and parent lifecycle', () => {
  it('preserves nanoseconds and descending ID ties, including empty filtered pages', async () => {
    const { store, reader } = setup();
    store.docs.clear();
    for (const uid of [VIEWER, AUTHOR]) {
      store.docs.set(`users/${uid}`, { ...profile });
      store.docs.set(`driverUsers/${uid}`, { ...driver });
    }
    for (const [id, nanos, status] of [
      ['thread_0003', 2, 'hidden'], ['thread_0002', 2, 'active'], ['thread_0001', 1, 'active'],
    ] as const) store.docs.set(`driverThreads/${id}`, { ...content(AUTHOR, nanos), status });
    const first = await reader.list(request({ limit: 1 }));
    expect(first.posts).toEqual([]);
    expect(first.nextCursor).toEqual({ seconds: stamp().seconds, nanoseconds: 2, id: 'thread_0003' });
    const second = await reader.list(request({ limit: 1, cursor: first.nextCursor }));
    expect(second.posts.map((p) => p.id)).toEqual(['thread_0002']);
    const third = await reader.list(request({ limit: 1, cursor: second.nextCursor }));
    expect(third.posts.map((p) => p.id)).toEqual(['thread_0001']);
    expect((await reader.list(request({ limit: 1, cursor: third.nextCursor }))).nextCursor).toBeNull();
    expect(store.queries.every((q) => q.limit === 1)).toBe(true);
  });
  it('uses ascending reply ID ties and author checks, skips hidden/deleted replies', async () => {
    const { store, reader } = setup();
    for (const id of ['reply_00002', 'reply_00001', 'reply_00003']) {
      store.docs.set(`driverThreads/${THREAD}/replies/${id}`, content());
    }
    store.docs.get(`driverThreads/${THREAD}/replies/reply_00002`)!.status = 'removed';
    const first = await reader.detail(request({ threadId: THREAD, limit: 2 }));
    expect(first.comments.map((c) => c.id)).toEqual(['reply_00001']);
    expect(first.nextCursor?.id).toBe('reply_00002');
    expect((await reader.detail(request({ threadId: THREAD, limit: 2, cursor: first.nextCursor })))
      .comments.map((c) => c.id)).toEqual(['reply_00003']);
  });
  it.each(['missing', 'hidden', 'removed', 'deleted', 'locked'])('denies %s parent on the next reply page', async (status) => {
    const { store, reader } = setup();
    store.docs.set(`driverThreads/${THREAD}/replies/reply_00001`, content());
    const first = await reader.detail(request({ threadId: THREAD, limit: 1 }));
    if (status === 'missing') store.docs.delete(`driverThreads/${THREAD}`);
    else store.docs.get(`driverThreads/${THREAD}`)!.status = status;
    await expect(reader.detail(request({ threadId: THREAD, limit: 1, cursor: first.nextCursor })))
      .rejects.toMatchObject({ details: { reason: 'thread-unavailable' } });
    expect(store.queries).toHaveLength(1);
  });
  it('does not read beyond 20 candidates or refill a filtered page', async () => {
    const { store, reader } = setup();
    for (let i = 0; i < 50; i++) store.docs.set(`driverThreads/thread_${i.toString().padStart(5, '0')}`,
      { ...content(), status: 'hidden' });
    await reader.list(request());
    expect(store.queries).toHaveLength(1);
    expect(store.queries[0]!.limit).toBe(20);
    expect(store.reads.filter((p) => p.startsWith('driverThreads/'))).toHaveLength(20);
  });
  it('does not need the cursor document to still exist', async () => {
    const { store, reader } = setup();
    const first = await reader.list(request({ limit: 1 }));
    store.docs.delete(`driverThreads/${THREAD}`);
    expect(await reader.list(request({ limit: 1, cursor: first.nextCursor })))
      .toEqual({ posts: [], nextCursor: null });
  });
});

describe('request bounds and page budget', () => {
  it.each([
    null, [], { limit: 0 }, { limit: 21 }, { limit: 1.5 }, { limit: '20' }, { uid: AUTHOR },
    { cursor: {} }, { cursor: { seconds: 0, nanoseconds: 1e9, id: THREAD } },
    { cursor: { seconds: 0.1, nanoseconds: 1, id: THREAD } },
    { cursor: { seconds: 0, nanoseconds: 1, id: '../bad-id' } },
  ])('rejects malformed input before reads: %j', async (data) => {
    const { store, reader } = setup();
    await expect(reader.list(request(data))).rejects.toMatchObject({ code: 'invalid-argument' });
    expect(store.reads).toEqual([]);
  });
  it('rejects invalid parent IDs and forged cursor fields', async () => {
    const { reader } = setup();
    await expect(reader.detail(request({ threadId: '../users' }))).rejects.toMatchObject({ code: 'invalid-argument' });
    expect(cursorSchema.safeParse({ seconds: 0, nanoseconds: 0, id: THREAD, uid: AUTHOR }).success).toBe(false);
  });
  it('charges list/detail once per page and shares the cap', async () => {
    const { store, reader } = setup();
    const path = `driverRateLimits/${VIEWER}_nativeFeedRead`;
    store.docs.set(path, { bucket: Math.floor(NOW / 3_600_000), count: 118 });
    await reader.list(request());
    await reader.detail(request({ threadId: THREAD }));
    await expect(reader.list(request())).rejects.toMatchObject({ code: 'resource-exhausted' });
    expect(store.docs.get(path)!.count).toBe(120);
    expect(store.writes).toHaveLength(2);
    expect(store.queries).toHaveLength(2);
  });
  it('resets the same bounded record in a new hour and fails closed on corrupt counts', async () => {
    const { store, reader } = setup();
    const path = `driverRateLimits/${VIEWER}_nativeFeedRead`;
    store.docs.set(path, { bucket: Math.floor(NOW / 3_600_000) - 1, count: 120 });
    await reader.list(request());
    expect(store.docs.get(path)!.count).toBe(1);
    store.docs.get(path)!.count = 'broken';
    await expect(reader.list(request())).rejects.toMatchObject({ code: 'resource-exhausted' });
  });
});
