/** Test-only in-memory transactional adapter; no Firebase bootstrap or network. */
import { Timestamp, type Firestore } from 'firebase-admin/firestore';
import { UserRecord } from 'firebase-admin/auth';
import { describe, expect, it } from 'vitest';
import { ClubCalendarService } from './service';
import { LIMITS, SUBSCRIPTIONS, TOKENS, hash, pairRevocationPath } from './contract';
import { providerIdentityKeys } from '../access/carCommunity-core';
import { handleFeed } from './http';

type Data = Record<string, unknown>;
function clone<T>(value: T): T {
  if (value instanceof Timestamp || value === null || typeof value !== 'object') return value;
  if (Array.isArray(value)) return value.map(clone) as T;
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, clone(item)])) as T;
}
class Snapshot {
  constructor(
    readonly ref: Reference,
    private readonly value: Data | undefined,
  ) {}
  get id() {
    return this.ref.id;
  }
  get exists() {
    return this.value !== undefined;
  }
  data() {
    return clone(this.value);
  }
}
class Reference {
  constructor(
    readonly path: string,
    private readonly store: MemoryStore,
  ) {}
  get id() {
    return this.path.split('/').at(-1)!;
  }
  async get() {
    return this.store.read(this);
  }
}
class Query {
  constructor(
    readonly path: string,
    readonly maximum = Infinity,
  ) {}
  orderBy() {
    return this;
  }
  select() {
    return this;
  }
  limit(maximum: number) {
    return new Query(this.path, maximum);
  }
}
export class MemoryStore {
  readonly records = new Map<string, Data>();
  readonly reads: string[] = [];
  writes = 0;
  failPath: string | null = null;
  private commitIndex = 0;
  private queue = Promise.resolve();
  firestore(): Firestore {
    return this as unknown as Firestore;
  }
  commitVersion() {
    return new Timestamp(Math.floor(NOW / 1000) - 1, ++this.commitIndex);
  }
  seed(path: string, value: Data) {
    this.records.set(path, clone(value));
    return this;
  }
  data(path: string) {
    return clone(this.records.get(path));
  }
  doc(path: string) {
    return new Reference(path, this);
  }
  collection(path: string) {
    return new Query(path);
  }
  read(ref: Reference) {
    this.reads.push(ref.path);
    if (this.failPath === ref.path) throw new Error('sensitive backend/path/token details');
    return new Snapshot(ref, this.records.get(ref.path));
  }
  async runTransaction<T>(run: (tx: unknown) => Promise<T>) {
    const previous = this.queue;
    let unlock!: () => void;
    this.queue = new Promise<void>((resolve) => {
      unlock = resolve;
    });
    await previous;
    const mutations = new Map<string, Data | null>();
    const get = async (target: Reference | Query) => {
      if (mutations.size) throw new Error('Read after transaction write');
      if (target instanceof Reference) return this.read(target);
      this.reads.push(target.path);
      if (this.failPath === target.path) throw new Error('sensitive query details');
      const docs = [...this.records.keys()]
        .filter(
          (path) =>
            path.startsWith(target.path + '/') && !path.slice(target.path.length + 1).includes('/'),
        )
        .sort()
        .slice(0, target.maximum)
        .map((path) => this.read(this.doc(path)));
      return { docs, size: docs.length };
    };
    try {
      const result = await run({
        get,
        getAll: (...refs: (Reference | { fieldMask: string[] })[]) =>
          Promise.all(refs.filter((ref): ref is Reference => ref instanceof Reference).map(get)),
        set: (ref: Reference, data: Data) => {
          mutations.set(ref.path, clone(data));
        },
        create: (ref: Reference, data: Data) => {
          if (this.records.has(ref.path)) throw new Error('already exists');
          mutations.set(ref.path, clone(data));
        },
        delete: (ref: Reference) => {
          mutations.set(ref.path, null);
        },
      });
      for (const [path, data] of mutations) {
        if (data === null) this.records.delete(path);
        else this.records.set(path, data);
        this.writes++;
      }
      return result;
    } finally {
      unlock();
    }
  }
}

const NOW = Date.parse('2026-10-29T12:00:00Z');
const time = (ms: number) => Timestamp.fromMillis(ms);
const identity = () => ({
  disabled: false,
  tokensValidAfterTime: new Date(NOW - 100000).toUTCString(),
  providerData: [{ providerId: 'google.com', uid: 'google-subject' }] as UserRecord['providerData'],
});
function fixture(uid = 'member', store = new MemoryStore()) {
  const joined = time(NOW - 10000),
    intervalId = `interval-${uid}`;
  store
    .seed('config/sharedClubs', {
      enabled: true,
      activatedAt: time(1),
      calendarSubscriptionsReady: true,
    })
    .seed(`users/${uid}`, { onboardingCompletedAt: time(1), status: 'active' })
    .seed('driverClubs/club', { status: 'active', schemaVersion: 2, ownerUid: 'owner' })
    .seed(`driverClubs/club/members/${uid}`, { role: 'member', joinedAt: joined })
    .seed(`driverClubAccounts/${uid}`, {
      activeClubId: 'club',
      role: 'member',
      joinedAt: joined,
      intervalId,
    })
    .seed(`driverClubAccounts/${uid}/history/${intervalId}`, {
      clubId: 'club',
      joinedAt: joined,
      leftAt: null,
    })
    .seed(`carCommunityAccess/${uid}`, { initialized: true });
  let now = NOW;
  const auth = identity();
  const service = new ClubCalendarService(
    store.firestore(),
    async () => auth,
    'demo-calendar',
    () => now,
  );
  const request = (data: unknown) => ({
    auth: { uid, token: { auth_time: (NOW - 100000) / 1000 } },
    data,
  });
  const manage = (action: string, extra: Record<string, unknown> = {}) =>
    service.manage(
      request({ action, clubId: 'club', ...extra }) as Parameters<ClubCalendarService['manage']>[0],
    );
  const issue = async (version = 0) => {
    const response = await manage('issue', { expectedVersion: version });
    if (!('url' in response) || !response.url) throw new Error('Issue did not return a URL');
    return { ...response, token: new URL(response.url).searchParams.get('token')! };
  };
  const event = (eventId = 'event', fields: Data = {}) => {
    store.seed(`events/${eventId}`, {
      status: 'published',
      title: `Title ${eventId}`,
      startsAt: time(now + 3600000),
      endsAt: time(now + 7200000),
      locationName: 'Meeting place',
      ...fields,
    });
    store.seed(`driverClubs/club/events/${eventId}`, {
      projectionType: 'shared-club-event-v1',
      clubId: 'club',
      eventId,
      createdAt: store.commitVersion(),
      title: 'STALE PROJECTED TITLE',
      startMillis: NOW,
    });
  };
  const withdraw = (eventId = 'event') => {
    const through = store.commitVersion();
    store.records.delete(`driverClubs/club/events/${eventId}`);
    store.seed(pairRevocationPath('club', eventId), {
      schemaVersion: 1,
      clubId: 'club',
      eventId,
      invalidatedThrough: through,
    });
  };
  return {
    store,
    auth,
    service,
    manage,
    issue,
    event,
    withdraw,
    tick: (ms = 1000) => {
      now += ms;
    },
  };
}
const privateState = (store: MemoryStore, uid = 'member') =>
  store.data(`${SUBSCRIPTIONS}/${hash(uid)}`)!;

describe('personal club calendar lifecycle', () => {
  it('ordinary members issue unguessable hash-only links; status never recovers a secret', async () => {
    const f = fixture();
    expect(await f.manage('status')).toEqual({ active: false, version: 0, limits: LIMITS });
    const link = await f.issue();
    expect(link.token).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(link.url).toBe(
      `https://europe-west1-demo-calendar.cloudfunctions.net/clubCalendar-feed/calendar.ics?token=${link.token}`,
    );
    expect(link.webcalUrl).toBe(link.url.replace('https:', 'webcal:'));
    const persisted = JSON.stringify([...f.store.records]);
    expect(persisted).not.toContain(link.token);
    expect(privateState(f.store).tokenHash).toBe(hash(link.token));
    const status = await f.manage('status');
    expect(status).toEqual({ active: true, version: 1, limits: LIMITS });
    expect(JSON.stringify(status)).not.toMatch(/url|tokenHash|token/);
  });

  it('concurrent issue uses compare-and-swap, returning exactly one usable link', async () => {
    const f = fixture();
    const results = await Promise.allSettled([f.issue(), f.issue()]);
    expect(results.filter((item) => item.status === 'fulfilled')).toHaveLength(1);
    const rejected = results.find((item) => item.status === 'rejected') as PromiseRejectedResult;
    expect(rejected.reason.code).toBe('aborted');
    expect(
      [...f.store.records.keys()].filter((path) => path.startsWith(TOKENS + '/')),
    ).toHaveLength(1);
    const issued = results.find((item) => item.status === 'fulfilled') as PromiseFulfilledResult<
      Awaited<ReturnType<typeof f.issue>>
    >;
    expect((await f.service.fetch(issued.value.token)).body).toContain('END:VCALENDAR');
  });

  it('rotation preserves UIDs and journal and affects only the issuing member', async () => {
    const f = fixture(),
      other = fixture('other', f.store);
    f.event();
    const first = await f.issue(),
      otherLink = await other.issue();
    const before = await f.service.fetch(first.token);
    const rotated = await f.issue(1);
    await expect(f.service.fetch(first.token)).rejects.toMatchObject({ code: 'permission-denied' });
    expect(await f.service.fetch(rotated.token)).toEqual(before);
    expect((await other.service.fetch(otherLink.token)).body).toContain('Title event');
    await expect(f.manage('revoke', { expectedVersion: 1 })).rejects.toMatchObject({
      code: 'aborted',
    });
    expect(await f.manage('revoke', { expectedVersion: 2 })).toEqual({ active: false, version: 3 });
    await expect(f.service.fetch(rotated.token)).rejects.toMatchObject({
      code: 'permission-denied',
    });
    expect((await other.service.fetch(otherLink.token)).body).toContain('Title event');
  });

  it('authenticated revoke can clean up a former membership without granting calendar access', async () => {
    const f = fixture(),
      link = await f.issue();
    f.store.records.delete('driverClubs/club/members/member');
    f.store.seed('config/sharedClubs', { enabled: false });
    expect(await f.manage('revoke')).toEqual({ active: false, version: 2 });
    await expect(f.service.fetch(link.token)).rejects.toMatchObject({ code: 'permission-denied' });
    const writes = f.store.writes;
    expect(await f.manage('revoke')).toEqual({ active: false, version: 2 });
    expect(f.store.writes).toBe(writes);
  });

  it('a concurrent feed refresh cannot resurrect a revoked token or owner record', async () => {
    const f = fixture(),
      link = await f.issue();
    f.event();
    await Promise.allSettled([
      f.service.fetch(link.token),
      f.manage('revoke', { expectedVersion: 1 }),
    ]);
    expect(privateState(f.store).tokenHash).toBeNull();
    expect(f.store.data(`${TOKENS}/${hash(link.token)}`)).toBeUndefined();
    await expect(f.service.fetch(link.token)).rejects.toMatchObject({ code: 'permission-denied' });
  });

  it('requires auth, exact payloads and explicit current version for issuing', async () => {
    const f = fixture();
    await expect(f.service.manage({ data: {} })).rejects.toMatchObject({ code: 'unauthenticated' });
    await expect(f.manage('issue')).rejects.toMatchObject({ code: 'invalid-argument' });
    await expect(f.manage('issue', { expectedVersion: 0, uid: 'victim' })).rejects.toMatchObject({
      code: 'invalid-argument',
    });
    await expect(f.manage('status', { clubId: '../secret' })).rejects.toMatchObject({
      code: 'invalid-argument',
    });
    f.auth.tokensValidAfterTime = new Date(NOW + 1000).toUTCString();
    await expect(f.manage('issue', { expectedVersion: 0 })).rejects.toMatchObject({
      code: 'permission-denied',
    });
  });

  it('accepts a newly authenticated token in the revocation second, matching real Admin SDK precision', async () => {
    const f = fixture();
    const subsecondNow = NOW + 999,
      authTime = Math.floor(subsecondNow / 1000);
    // Exercise the real SDK model's internal wire-response constructor, not a
    // fake millisecond ISO value that Admin Auth never returns for validSince.
    const auth = Reflect.construct(UserRecord, [
      {
        localId: 'member',
        validSince: String(authTime),
        providerUserInfo: [{ providerId: 'google.com', rawId: 'google-subject' }],
      },
    ]) as UserRecord;
    expect(Date.parse(auth.tokensValidAfterTime!)).toBe(NOW);
    const service = new ClubCalendarService(
      f.store.firestore(),
      async () => auth,
      'demo-calendar',
      () => subsecondNow,
    );
    const request = (seconds: number, action = 'status') =>
      ({
        auth: { uid: 'member', token: { auth_time: seconds } },
        data:
          action === 'issue'
            ? { action, clubId: 'club', expectedVersion: 0 }
            : { action, clubId: 'club' },
      }) as Parameters<ClubCalendarService['manage']>[0];
    expect(await service.manage(request(authTime))).toMatchObject({ active: false, version: 0 });
    expect(await service.manage(request(authTime, 'issue'))).toMatchObject({
      active: true,
      version: 1,
    });
    await expect(service.manage(request(authTime - 1))).rejects.toMatchObject({
      code: 'permission-denied',
    });
  });
});

describe('server readiness gate protects automatic deployment', () => {
  it.each([undefined, false, null, 'true', 1])(
    'readiness %s denies status, issue and feed without writes; revoke remains idempotent',
    async (ready) => {
      const f = fixture(),
        link = await f.issue();
      f.event();
      await f.service.fetch(link.token);
      const config: Data = { enabled: true, activatedAt: time(1) };
      if (ready !== undefined) config.calendarSubscriptionsReady = ready;
      f.store.seed('config/sharedClubs', config);
      const state = privateState(f.store),
        writes = f.store.writes;
      await expect(f.manage('status')).rejects.toMatchObject({ code: 'failed-precondition' });
      await expect(f.issue(1)).rejects.toMatchObject({ code: 'failed-precondition' });
      await expect(f.service.fetch(link.token)).rejects.toMatchObject({
        code: 'failed-precondition',
      });
      expect(privateState(f.store)).toEqual(state);
      expect(f.store.writes).toBe(writes);
      expect(await f.manage('revoke', { expectedVersion: 1 })).toEqual({
        active: false,
        version: 2,
      });
      const revokedWrites = f.store.writes;
      expect(await f.manage('revoke', { expectedVersion: 2 })).toEqual({
        active: false,
        version: 2,
      });
      expect(f.store.writes).toBe(revokedWrites);
    },
  );

  it('revoking readiness defeats a matching ETag; restoring true still requires all existing proofs', async () => {
    const f = fixture(),
      link = await f.issue();
    f.event();
    const first = await f.service.fetch(link.token),
      writes = f.store.writes;
    const request = {
      method: 'GET',
      path: '/calendar.ics',
      protocol: 'https',
      query: { token: link.token },
      get: (_name: string) => first.etag,
    };
    const response = () => ({
      code: 0,
      headers: {} as Record<string, string>,
      body: '',
      set(name: string, value: string) {
        this.headers[name] = value;
        return this;
      },
      status(code: number) {
        this.code = code;
        return this;
      },
      send(body: string) {
        this.body = body;
      },
      end() {},
    });
    f.store.seed('config/sharedClubs', {
      enabled: true,
      activatedAt: time(1),
      calendarSubscriptionsReady: false,
    });
    const closed = response();
    await handleFeed(f.service, request, closed);
    expect(closed.code).toBe(404);
    expect(closed.body).toBe('Not found.');
    expect(closed.headers['Cache-Control']).toBe('no-store');
    expect(closed.headers.ETag).toBeUndefined();
    expect(f.store.writes).toBe(writes);
    f.store.seed('config/sharedClubs', {
      enabled: true,
      activatedAt: time(1),
      calendarSubscriptionsReady: true,
    });
    const reopened = response();
    await handleFeed(f.service, request, reopened);
    expect(reopened.code).toBe(304);
    expect(f.store.writes).toBe(writes);
    expect(await f.manage('status')).toMatchObject({ active: true, version: 1 });
    f.store.records.delete('driverClubs/club/members/member');
    const noMembership = response();
    await handleFeed(f.service, request, noMembership);
    expect(noMembership.code).toBe(404);
    expect(noMembership.headers.ETag).toBeUndefined();
  });
});

describe('fail closed canonical authorization on every fetch', () => {
  const restrictions: [string, (f: ReturnType<typeof fixture>) => void][] = [
    ...['suspended', 'deleted', 'banned'].map(
      (flag): [string, (f: ReturnType<typeof fixture>) => void] => [
        `canonical ${flag}`,
        (f) => f.store.seed('users/member', { onboardingCompletedAt: time(1), [flag]: true }),
      ],
    ),
    ...[
      'deleted',
      'suspended',
      'deletionPending',
      'banned',
      'temporarily_suspended',
      'permanently_suspended',
      'unknown',
    ].map((status): [string, (f: ReturnType<typeof fixture>) => void] => [
      `status ${status}`,
      (f) => f.store.seed('users/member', { onboardingCompletedAt: time(1), status }),
    ]),
    [
      'missing user',
      (f) => {
        f.store.records.delete('users/member');
      },
    ],
    ['unfinished onboarding', (f) => f.store.seed('users/member', {})],
    [
      'malformed onboarding timestamp',
      (f) => f.store.seed('users/member', { onboardingCompletedAt: 'today' }),
    ],
    [
      'disabled Auth account',
      (f) => {
        f.auth.disabled = true;
      },
    ],
    [
      'revoked Auth credentials',
      (f) => {
        f.auth.tokensValidAfterTime = new Date(NOW + 1000).toUTCString();
      },
    ],
    [
      'feature disabled',
      (f) =>
        f.store.seed('config/sharedClubs', {
          enabled: false,
          activatedAt: time(1),
          calendarSubscriptionsReady: true,
        }),
    ],
    [
      'activation missing',
      (f) =>
        f.store.seed('config/sharedClubs', { enabled: true, calendarSubscriptionsReady: true }),
    ],
    [
      'activation malformed',
      (f) =>
        f.store.seed('config/sharedClubs', {
          enabled: true,
          activatedAt: 'today',
          calendarSubscriptionsReady: true,
        }),
    ],
    [
      'club archived',
      (f) => f.store.seed('driverClubs/club', { status: 'archived', schemaVersion: 2 }),
    ],
    [
      'club unmigrated',
      (f) => f.store.seed('driverClubs/club', { status: 'active', schemaVersion: 1 }),
    ],
    [
      'member missing',
      (f) => {
        f.store.records.delete('driverClubs/club/members/member');
      },
    ],
    [
      'lock missing',
      (f) => {
        f.store.records.delete('driverClubAccounts/member');
      },
    ],
    ['lock moved', (f) => f.store.seed('driverClubAccounts/member', { activeClubId: 'other' })],
    [
      'deletion tombstone',
      (f) =>
        f.store.seed('driverClubAccounts/member', {
          ...f.store.data('driverClubAccounts/member'),
          deletionRevoked: true,
        }),
    ],
    [
      'role mismatch',
      (f) =>
        f.store.seed('driverClubAccounts/member', {
          ...f.store.data('driverClubAccounts/member'),
          role: 'owner',
        }),
    ],
    [
      'forged owner role',
      (f) => {
        f.store.seed('driverClubs/club/members/member', {
          ...f.store.data('driverClubs/club/members/member'),
          role: 'owner',
        });
        f.store.seed('driverClubAccounts/member', {
          ...f.store.data('driverClubAccounts/member'),
          role: 'owner',
        });
      },
    ],
    [
      'membership timestamp changed',
      (f) =>
        f.store.seed('driverClubs/club/members/member', { role: 'member', joinedAt: time(NOW) }),
    ],
    [
      'interval missing',
      (f) => {
        f.store.records.delete('driverClubAccounts/member/history/interval-member');
      },
    ],
    [
      'interval closed',
      (f) =>
        f.store.seed('driverClubAccounts/member/history/interval-member', {
          clubId: 'club',
          joinedAt: time(NOW - 10000),
          leftAt: time(NOW),
        }),
    ],
    ['CC access uninitialized', (f) => f.store.seed('carCommunityAccess/member', {})],
    [
      'CC access suspended',
      (f) => f.store.seed('carCommunityAccess/member', { initialized: true, suspended: true }),
    ],
    [
      'CC inherited identity block',
      (f) =>
        f.store.seed('carCommunityAccess/member', { initialized: true, inheritedBlocked: true }),
    ],
    [
      'CC explicit blacklist',
      (f) =>
        f.store.seed('carCommunityAccess/member', {
          initialized: true,
          explicitBlacklistId: 'block',
        }),
    ],
    [
      'CC malformed access flag',
      (f) => f.store.seed('carCommunityAccess/member', { initialized: true, suspended: 'true' }),
    ],
    [
      'CC malformed stored identity',
      (f) => f.store.seed('carCommunityAccess/member', { initialized: true, googleKey: 'corrupt' }),
    ],
    [
      'current provider identity block',
      (f) => {
        const key = providerIdentityKeys(f.auth.providerData).googleKey!;
        f.store.seed(`carCommunityIdentityBlocks/${key}`, { blocked: true });
      },
    ],
    [
      'CC malformed provider block',
      (f) => {
        const key = providerIdentityKeys(f.auth.providerData).googleKey!;
        f.store.seed(`carCommunityIdentityBlocks/${key}`, { blocked: 'true' });
      },
    ],
  ];
  it.each(restrictions)('%s invalidates an already issued bearer', async (_name, restrict) => {
    const f = fixture(),
      link = await f.issue();
    f.event();
    await f.service.fetch(link.token);
    restrict(f);
    await expect(f.service.fetch(link.token)).rejects.toBeDefined();
  });

  it('same-club leave and rejoin cannot resurrect an old token', async () => {
    const f = fixture(),
      link = await f.issue();
    const joined = time(NOW + 1);
    f.store
      .seed('driverClubs/club/members/member', { role: 'member', joinedAt: joined })
      .seed('driverClubAccounts/member', {
        activeClubId: 'club',
        role: 'member',
        joinedAt: joined,
        intervalId: 'new-interval',
      })
      .seed('driverClubAccounts/member/history/new-interval', {
        clubId: 'club',
        joinedAt: joined,
        leftAt: null,
      });
    await expect(f.service.fetch(link.token)).rejects.toMatchObject({ code: 'permission-denied' });
    expect(await f.manage('status')).toMatchObject({ active: false, version: 1 });
    const rejoined = await f.issue(1);
    expect((await f.service.fetch(rejoined.token)).body).toContain('END:VCALENDAR');
  });

  it('invalid token forms do not read Firestore', async () => {
    const f = fixture();
    for (const token of ['', 'short', '../token', 'a'.repeat(1000), 'a'.repeat(42) + '_']) {
      const reads = f.store.reads.length;
      await expect(f.service.fetch(token)).rejects.toMatchObject({ code: 'permission-denied' });
      expect(f.store.reads.length).toBe(reads);
    }
  });

  it('deleted Auth, temporary Auth error and revocation during the final Auth check all fail closed', async () => {
    const f = fixture(),
      link = await f.issue();
    f.event();
    for (const error of [{ code: 'auth/user-not-found' }, new Error('PRIVATE SDK AUTH DETAIL')]) {
      const service = new ClubCalendarService(
        f.store.firestore(),
        async () => {
          throw error;
        },
        'demo-calendar',
      );
      await expect(service.fetch(link.token)).rejects.toMatchObject({
        code: 'code' in error ? 'permission-denied' : 'unavailable',
      });
    }
    let calls = 0;
    const service = new ClubCalendarService(
      f.store.firestore(),
      async () => {
        if (++calls > 1) return { ...f.auth, disabled: true };
        return f.auth;
      },
      'demo-calendar',
      () => NOW,
    );
    await expect(service.fetch(link.token)).rejects.toMatchObject({ code: 'permission-denied' });
    expect(calls).toBe(2);
  });
});

describe('dynamic content, persistent deletion journal and bounded reads', () => {
  it('uses fresh canonical title/time, stable UID, SEQUENCE and DTSTAMP; no-change poll does not write', async () => {
    const f = fixture(),
      link = await f.issue();
    f.event();
    const initial = await f.service.fetch(link.token),
      writes = f.store.writes;
    expect(initial.body).toContain('Title event');
    expect(initial.body).not.toContain('STALE PROJECTED TITLE');
    expect(initial.body).toContain('SEQUENCE:0');
    f.tick();
    expect(await f.service.fetch(link.token)).toEqual(initial);
    expect(f.store.writes).toBe(writes);
    f.store.seed('events/event', {
      ...f.store.data('events/event'),
      title: 'Renamed',
      startsAt: time(NOW + 4000000),
    });
    const changed = await f.service.fetch(link.token);
    expect(changed.body).toContain('SUMMARY:Renamed');
    expect(changed.body).toContain('SEQUENCE:1');
    expect(changed.body.match(/UID:[^\r]+/)?.[0]).toBe(initial.body.match(/UID:[^\r]+/)?.[0]);
    expect(changed.etag).not.toBe(initial.etag);
    expect(changed.body.match(/DTSTAMP:[^\r]+/)?.[0]).not.toBe(
      initial.body.match(/DTSTAMP:[^\r]+/)?.[0],
    );
  });

  it.each(['withdrawn', 'deleted', 'cancelled', 'draft'])(
    '%s becomes a retained cancellation, never omission',
    async (action) => {
      const f = fixture(),
        link = await f.issue();
      f.event();
      const initial = await f.service.fetch(link.token);
      f.store.records.delete('driverClubs/club/events/event');
      if (action === 'deleted') f.store.records.delete('events/event');
      if (['cancelled', 'draft'].includes(action))
        f.store.seed('events/event', { status: action, title: 'PRIVATE NEW TITLE' });
      f.tick();
      const cancelled = await f.service.fetch(link.token);
      expect(cancelled.body).toContain('STATUS:CANCELLED');
      expect(cancelled.body).toContain('SEQUENCE:1');
      expect(cancelled.body).not.toContain('PRIVATE NEW TITLE');
      expect(cancelled.body.match(/UID:[^\r]+/)?.[0]).toBe(initial.body.match(/UID:[^\r]+/)?.[0]);
      f.tick(399 * 86400000);
      const writes = f.store.writes;
      expect(await f.service.fetch(link.token)).toEqual(cancelled);
      expect(f.store.writes).toBe(writes);
    },
  );

  it('reregistration after withdrawal restores the same UID with increasing sequence', async () => {
    const f = fixture(),
      link = await f.issue();
    f.event();
    const first = await f.service.fetch(link.token);
    f.store.records.delete('driverClubs/club/events/event');
    await f.service.fetch(link.token);
    f.event();
    f.tick();
    const rejoined = await f.service.fetch(link.token);
    expect(rejoined.body).toContain('STATUS:CONFIRMED');
    expect(rejoined.body).toContain('SEQUENCE:2');
    expect(rejoined.body.match(/UID:[^\r]+/)?.[0]).toBe(first.body.match(/UID:[^\r]+/)?.[0]);
  });

  it('withdrawal before start remains cancelled when the next provider poll is after start', async () => {
    const f = fixture(),
      link = await f.issue();
    f.event();
    await f.service.fetch(link.token);
    // Driver deletes this same projection for both withdrawal and normal expiry.
    f.store.records.delete('driverClubs/club/events/event');
    f.store.seed('driverAuditLogs/clubEvent_withdrawal', {
      actorUid: 'owner',
      action: 'club.eventAttendance',
      target: 'club',
      clubId: 'club',
      eventId: 'event',
      attending: false,
      createdAt: time(NOW + 1000),
    });
    f.withdraw();
    f.tick(3600001);
    expect((await f.service.fetch(link.token)).body).toContain('STATUS:CANCELLED');
    expect(f.store.reads.some((path) => path.startsWith('driverAuditLogs/'))).toBe(false);
  });

  it('pair path is shared, unambiguous and independent of the viewer UID', () => {
    expect(pairRevocationPath('club', 'event')).toBe(
      'driverClubEventRevocations/pair_4aaad3756733b252aa037399e464bd004a59ff0f0ddd3b7955caa086ea029c1b',
    );
    expect(pairRevocationPath('a.b', 'c')).not.toBe(pairRevocationPath('a', 'b.c'));
  });

  it('same-millisecond reconsent survives an older marker and ordinary expiry; later withdrawal cancels completed history', async () => {
    const f = fixture(),
      link = await f.issue();
    f.event();
    const first = await f.service.fetch(link.token);
    const firstVersion = (privateState(f.store).rows as Data[])[0]!.consentGeneration;
    f.withdraw();
    expect((await f.service.fetch(link.token)).body).toContain('STATUS:CANCELLED');
    f.event(); // Same millisecond, strictly later native createdAt nanoseconds.
    const rejoined = await f.service.fetch(link.token);
    expect(rejoined.body).toContain('STATUS:CONFIRMED');
    expect((privateState(f.store).rows as Data[])[0]!.consentGeneration).not.toEqual(firstVersion);
    expect(rejoined.body.match(/UID:[^\r]+/)?.[0]).toBe(first.body.match(/UID:[^\r]+/)?.[0]);
    f.store.records.delete('driverClubs/club/events/event'); // Scheduled expiry writes no marker.
    f.tick(3 * 3600000);
    expect((await f.service.fetch(link.token)).body).toContain('STATUS:CONFIRMED');
    f.store.seed('events/event', { ...f.store.data('events/event'), status: 'completed' });
    f.withdraw(); // Explicit withdrawal after expiry still invalidates the observed consent.
    const cancelled = await f.service.fetch(link.token);
    expect(cancelled.body).toContain('STATUS:CANCELLED');
    expect(Number(cancelled.body.match(/SEQUENCE:(\d+)/)?.[1])).toBeGreaterThan(2);
  });

  it('a cutoff equal to the exact native consent generation vetoes even a stale live projection', async () => {
    const f = fixture(),
      link = await f.issue();
    f.event();
    await f.service.fetch(link.token);
    const generation = f.store.data('driverClubs/club/events/event')!.createdAt;
    f.store.seed(pairRevocationPath('club', 'event'), {
      schemaVersion: 1,
      clubId: 'club',
      eventId: 'event',
      invalidatedThrough: generation,
    });
    expect((await f.service.fetch(link.token)).body).toContain('STATUS:CANCELLED');
  });

  it('a failed pair proof read preserves the published journal instead of returning stale confirmation', async () => {
    const f = fixture(),
      link = await f.issue();
    f.event();
    await f.service.fetch(link.token);
    const before = privateState(f.store),
      writes = f.store.writes;
    f.store.failPath = pairRevocationPath('club', 'event');
    await expect(f.service.fetch(link.token)).rejects.toBeDefined();
    expect(privateState(f.store)).toEqual(before);
    expect(f.store.writes).toBe(writes);
  });

  it.each([
    { schemaVersion: 1, clubId: 'other', eventId: 'event', invalidatedThrough: time(NOW) },
    { schemaVersion: 1, clubId: 'club', eventId: 'other', invalidatedThrough: time(NOW) },
    {
      schemaVersion: 1,
      clubId: 'club',
      eventId: 'event',
      invalidatedThrough: 'not-a-server-timestamp',
    },
    { clubId: 'club', eventId: 'event', invalidatedThrough: time(NOW) },
  ])(
    'malformed pair evidence fails closed instead of preserving or cancelling history',
    async (marker) => {
      const f = fixture(),
        link = await f.issue();
      f.event();
      await f.service.fetch(link.token);
      const before = privateState(f.store),
        writes = f.store.writes;
      f.store.seed(pairRevocationPath('club', 'event'), marker);
      await expect(f.service.fetch(link.token)).rejects.toMatchObject({ code: 'unavailable' });
      expect(privateState(f.store)).toEqual(before);
      expect(f.store.writes).toBe(writes);
    },
  );

  it('legacy past history without a native generation becomes a conservative cancellation', async () => {
    const f = fixture(),
      link = await f.issue();
    f.event();
    await f.service.fetch(link.token);
    const state = privateState(f.store);
    const rows = (state.rows as Data[]).map((row) => {
      const copy = { ...row };
      delete copy.consentGeneration;
      return copy;
    });
    f.store.seed(`${SUBSCRIPTIONS}/${hash('member')}`, { ...state, rows });
    f.store.records.delete('driverClubs/club/events/event');
    f.tick(3 * 3600000);
    const cancelled = await f.service.fetch(link.token);
    expect(cancelled.body).toContain('STATUS:CANCELLED');
    expect(Number(cancelled.body.match(/SEQUENCE:(\d+)/)?.[1])).toBeGreaterThan(0);
  });

  it('revocation cutoffs suppress stale private intent even when its projection remains', async () => {
    const f = fixture(),
      link = await f.issue();
    f.event();
    await f.service.fetch(link.token);
    f.store.seed('driverClubEventRevocations/event_event', { invalidatedThrough: time(NOW) });
    expect((await f.service.fetch(link.token)).body).toContain('STATUS:CANCELLED');
    f.store.seed('driverClubs/club/events/event', {
      ...f.store.data('driverClubs/club/events/event'),
      createdAt: time(NOW + 1),
    });
    expect((await f.service.fetch(link.token)).body).toContain('STATUS:CONFIRMED');
  });

  it('normal expiry/completion retains delivered history, but canonical deletion still cancels it', async () => {
    const f = fixture(),
      link = await f.issue();
    f.event();
    await f.service.fetch(link.token);
    f.tick(3 * 3600000);
    f.store.records.delete('driverClubs/club/events/event');
    expect((await f.service.fetch(link.token)).body).toContain('STATUS:CONFIRMED');
    f.store.seed('events/event', { ...f.store.data('events/event'), status: 'completed' });
    f.store.seed('driverClubEventRevocations/event_event', {
      invalidatedThrough: time(NOW + 2 * 3600000),
    });
    expect((await f.service.fetch(link.token)).body).toContain('STATUS:CONFIRMED');
    f.store.records.delete('events/event');
    expect((await f.service.fetch(link.token)).body).toContain('STATUS:CANCELLED');
  });

  it('overbound current query fails before canonical reads; no journal or token mutation', async () => {
    const f = fixture(),
      link = await f.issue();
    for (let n = 0; n <= LIMITS.maxCurrentEvents; n++) f.event(`event-${n}`);
    const writes = f.store.writes;
    await expect(f.service.fetch(link.token)).rejects.toMatchObject({ code: 'resource-exhausted' });
    expect(f.store.writes).toBe(writes);
    expect(f.store.reads.filter((path) => path.startsWith('events/'))).toHaveLength(0);
  });

  it('source failures and malformed events never overwrite a previously published journal', async () => {
    const f = fixture(),
      link = await f.issue();
    f.event();
    await f.service.fetch(link.token);
    const state = privateState(f.store),
      writes = f.store.writes;
    f.store.failPath = 'events/event';
    await expect(f.service.fetch(link.token)).rejects.toBeDefined();
    expect(privateState(f.store)).toEqual(state);
    expect(f.store.writes).toBe(writes);
    f.store.failPath = null;
    f.store.seed('events/event', { status: 'published', startsAt: 'MALFORMED', title: 'bad' });
    await expect(f.service.fetch(link.token)).rejects.toMatchObject({ code: 'unavailable' });
    expect(privateState(f.store)).toEqual(state);
  });

  it('remembered-event capacity never evicts cancellation tombstones to fit new entries', async () => {
    const f = fixture(),
      link = await f.issue();
    f.event();
    await f.service.fetch(link.token);
    const prior = privateState(f.store),
      row = (prior.rows as Data[])[0]!;
    f.store.seed(`${SUBSCRIPTIONS}/${hash('member')}`, {
      ...prior,
      rows: Array.from({ length: LIMITS.maxRememberedEvents }, (_, n) => ({
        ...row,
        id: `old-${n}`,
        status: 'CANCELLED',
      })),
    });
    const writes = f.store.writes;
    await expect(f.service.fetch(link.token)).rejects.toMatchObject({ code: 'resource-exhausted' });
    expect(f.store.writes).toBe(writes);
    expect(privateState(f.store).rows as Data[]).toHaveLength(LIMITS.maxRememberedEvents);
  });

  it('bounded retention frees old journal capacity and reappearing IDs keep increasing SEQUENCE', async () => {
    const f = fixture(),
      link = await f.issue();
    f.event();
    const first = await f.service.fetch(link.token);
    f.store.records.delete('driverClubs/club/events/event');
    const cancelled = await f.service.fetch(link.token);
    expect(cancelled.body).toContain('STATUS:CANCELLED');
    f.tick(401 * 86400000);
    const retired = await f.service.fetch(link.token);
    expect(retired.body).not.toContain('BEGIN:VEVENT');
    expect(privateState(f.store).rows).toEqual([]);
    f.event();
    const reappeared = await f.service.fetch(link.token);
    expect(reappeared.body.match(/UID:[^\r]+/)?.[0]).toBe(first.body.match(/UID:[^\r]+/)?.[0]);
    expect(Number(reappeared.body.match(/SEQUENCE:(\d+)/)?.[1])).toBeGreaterThan(1);
    expect(reappeared.body).toContain('STATUS:CONFIRMED');
  });
});
