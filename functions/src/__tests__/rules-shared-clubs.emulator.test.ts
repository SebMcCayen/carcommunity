/** Combined CarCommunity rules, never the Driver emulator-only subset.
 * Run serially with the shared Firebase emulator slot reserved, or with an
 * isolated demo GCLOUD_PROJECT and loopback FIRESTORE_EMULATOR_HOST /
 * FIREBASE_STORAGE_EMULATOR_HOST on dedicated ports:
 * pnpm --dir functions test:emulator src/__tests__/rules-shared-clubs.emulator.test.ts
 */
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  collection,
  collectionGroup,
  deleteDoc,
  deleteField,
  doc,
  documentId,
  getDocFromServer,
  getDocs,
  getDocsFromServer,
  limit,
  onSnapshot,
  orderBy,
  query,
  setDoc,
  startAfter,
  Timestamp,
  type DocumentData,
  type QuerySnapshot,
  updateDoc,
  where,
  writeBatch,
} from 'firebase/firestore';
import { deleteObject, getBytes, listAll, ref, uploadBytes } from 'firebase/storage';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

let env: RulesTestEnvironment;
let previousConfig: Record<string, unknown> | undefined;
const logoRun = Date.now().toString();
const root = resolve(__dirname, '../../../firebase');
const projectId = process.env.GCLOUD_PROJECT ?? 'demo-test';
const emulatorEndpoint = (address: string | undefined, defaultPort: number) => {
  const url = new URL(`http://${address ?? `127.0.0.1:${defaultPort}`}`);
  if (!['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname))
    throw new Error('Shared-club rules tests require loopback emulators.');
  return { host: url.hostname, port: Number(url.port) };
};
const firestoreEndpoint = emulatorEndpoint(process.env.FIRESTORE_EMULATOR_HOST, 8080);
const storageEndpoint = emulatorEndpoint(process.env.FIREBASE_STORAGE_EMULATOR_HOST, 9199);
const clubId = 'rules-shared-club';
const clubPath = `driverClubs/${clubId}`;
const uid = (name: string) => `rules-club-${name}`;
const dbFor = (name: string, claims = {}) =>
  env.authenticatedContext(uid(name), claims).firestore();
const read = (name: string, path: string, claims = {}) =>
  getDocFromServer(doc(dbFor(name, claims), path));
const members = [
  'owner',
  'moderator',
  'member',
  'removed',
  'lock-only',
  'revoked',
  'suspended',
  'deleted',
  'unfinished',
  'driver-only',
  'claim-suspended',
  'fake-owner',
];
const activeUser = {
  onboardingCompletedAt: Timestamp.fromMillis(1),
  suspended: false,
  deleted: false,
};
const enabledConfig = {
  enabled: true,
  activatedAt: Timestamp.fromMillis(1),
  publicAttendanceReady: true,
  statsSince: Timestamp.fromMillis(1),
};
const futureEvent = {
  status: 'published',
  startsAt: Timestamp.fromMillis(Date.now() + 86400000),
};
const canonicalRestrictions = [
  { name: 'stored banned', user: { banned: true }, claims: {} },
  ...['deleted', 'suspended', 'deletionPending', 'banned'].map((status) => ({
    name: `status ${status}`,
    user: { status },
    claims: {},
  })),
  { name: 'banned claim', user: {}, claims: { banned: true } },
];

beforeAll(async () => {
  if (!projectId.startsWith('demo-'))
    throw new Error('Shared-club rules tests require a demo project.');
  env = await initializeTestEnvironment({
    projectId,
    firestore: {
      ...firestoreEndpoint,
      rules: readFileSync(resolve(root, 'firestore.rules'), 'utf8'),
    },
    storage: {
      ...storageEndpoint,
      rules: readFileSync(resolve(root, 'storage.rules'), 'utf8'),
    },
  });
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    previousConfig = (await getDocFromServer(doc(db, 'config/sharedClubs'))).data();
    await setDoc(doc(db, 'config/sharedClubs'), enabledConfig);
    for (const name of [...members, 'outsider', 'recipient', 'admin']) {
      if (name !== 'driver-only') {
        await setDoc(doc(db, `users/${uid(name)}`), {
          ...activeUser,
          ...(name === 'suspended' ? { suspended: true } : {}),
          ...(name === 'deleted' ? { deleted: true } : {}),
          ...(name === 'unfinished' ? { onboardingCompletedAt: null } : {}),
          displayName: 'Canonical account',
        });
      }
      await setDoc(doc(db, `driverClubAccounts/${uid(name)}`), {
        activeClubId: members.includes(name) ? clubId : null,
        deletionRevoked: name === 'revoked',
      });
    }
    // Driver access/role alone must never grant shared-club access.
    await setDoc(doc(db, `driverUsers/${uid('driver-only')}`), {
      onboardingComplete: true,
      status: 'active',
      role: 'admin',
    });
    await setDoc(doc(db, clubPath), {
      schemaVersion: 2,
      status: 'active',
      visibility: 'closed',
      name: 'Shared club',
      ownerUid: uid('owner'),
      memberCount: members.length,
      moderatorCount: 1,
      stats: { driveCount: 2, distanceMeters: 5000, durationSeconds: 600 },
    });
    await setDoc(doc(db, 'driverClubs/rules-archived'), {
      schemaVersion: 2,
      status: 'archived',
      ownerUid: uid('owner'),
    });
    await setDoc(doc(db, 'driverClubs/rules-legacy'), { status: 'active', ownerUid: uid('owner') });
    for (const name of members) {
      await setDoc(doc(db, `${clubPath}/members/${uid(name)}`), {
        uid: uid(name),
        role: ['owner', 'fake-owner'].includes(name)
          ? 'owner'
          : name === 'moderator'
            ? 'moderator'
            : 'member',
        joinedAt: Timestamp.fromMillis(1),
      });
    }
    await setDoc(doc(db, `${clubPath}/messages/m1`), {
      kind: 'sharedClub',
      clubId,
      authorUid: uid('member'),
      text: 'Private chat',
      createdAt: Timestamp.now(),
      status: 'active',
    });
    await setDoc(doc(db, `${clubPath}/events/e1`), {
      eventId: 'rules-club-event',
      title: 'Private calendar',
      startMillis: Date.now() + 3600000,
    });
    await setDoc(doc(db, `${clubPath}/invitations/${uid('recipient')}`), {
      clubId,
      recipientUid: uid('recipient'),
      inviterUid: uid('moderator'),
      status: 'pending',
      expiresAt: Timestamp.fromMillis(Date.now() + 86400000),
    });
    await setDoc(doc(db, `driverClubAccounts/${uid('member')}/history/h1`), {
      clubId,
      joinedAt: Timestamp.fromMillis(1),
      leftAt: null,
    });
    await setDoc(doc(db, 'events/rules-club-event'), futureEvent);
    await setDoc(doc(db, `events/rules-club-event/clubs/${clubId}`), {
      clubId,
      name: 'Shared club',
      logoUrl: null,
    });
    await setDoc(doc(db, 'events/rules-club-event/clubs/rules-archived'), {
      clubId: 'rules-archived',
      name: 'Archived',
      logoUrl: null,
    });
    await setDoc(doc(db, 'events/rules-club-page-event'), futureEvent);
    for (let index = 0; index < 30; index++) {
      const id = `rules-page-club-${index.toString().padStart(3, '0')}`;
      await setDoc(doc(db, `driverClubs/${id}`), {
        schemaVersion: 2,
        status: 'active',
        ownerUid: uid('owner'),
      });
      await setDoc(doc(db, `events/rules-club-page-event/clubs/${id}`), {
        clubId: id,
        name: `Club ${index}`,
        logoUrl: null,
      });
    }
  });
});
afterAll(async () => {
  if (env) {
    await env.withSecurityRulesDisabled(async (context) => {
      const config = doc(context.firestore(), 'config/sharedClubs');
      if (previousConfig) await setDoc(config, previousConfig);
      else await deleteDoc(config);
    });
    await env.cleanup();
  }
});

describe('shared clubs canonical identity and public scope', () => {
  it('exposes safe config get/absence, with fail-closed read kill switch and no list/writes', async () => {
    const db = dbFor('outsider');
    await assertSucceeds(read('outsider', 'config/sharedClubs'));
    await assertFails(getDocs(query(collection(db, 'config'), limit(25))));
    await assertFails(setDoc(doc(db, 'config/sharedClubs'), { enabled: true }));
    await assertFails(updateDoc(doc(db, 'config/sharedClubs'), { publicAttendanceReady: true }));
    await assertFails(
      updateDoc(doc(dbFor('admin', { admin: true }), 'config/sharedClubs'), {
        publicAttendanceReady: true,
      }),
    );
    await assertFails(read('suspended', 'config/sharedClubs'));
    await assertFails(read('driver-only', 'config/sharedClubs'));
    try {
      for (const mode of ['disabled', 'absent']) {
        await env.withSecurityRulesDisabled(async (context) => {
          const config = doc(context.firestore(), 'config/sharedClubs');
          if (mode === 'absent') await deleteDoc(config);
          else
            await setDoc(config, { enabled: false, publicAttendanceReady: true, statsSince: null });
        });
        await assertFails(
          getDocsFromServer(
            query(
              collection(dbFor('outsider'), 'events/rules-club-page-event/clubs'),
              orderBy(documentId()),
              limit(25),
            ),
          ),
        );
        const config = await assertSucceeds(read('outsider', 'config/sharedClubs'));
        expect(config.exists()).toBe(mode !== 'absent');
        for (const path of [
          clubPath,
          `${clubPath}/members/${uid('member')}`,
          `${clubPath}/messages/m1`,
          `${clubPath}/events/e1`,
          `${clubPath}/invitations/${uid('recipient')}`,
          `events/rules-club-event/clubs/${clubId}`,
        ]) {
          await assertFails(read('owner', path));
        }
        await assertFails(
          getDocs(query(collection(dbFor('member'), `${clubPath}/messages`), limit(50))),
        );
      }
    } finally {
      await env.withSecurityRulesDisabled(async (context) => {
        await setDoc(doc(context.firestore(), 'config/sharedClubs'), enabledConfig);
      });
    }
  });

  it('allows an onboarded KCC-only user the bounded active v2 directory and detail', async () => {
    const db = dbFor('outsider');
    const snapshot = await assertSucceeds(read('outsider', clubPath));
    expect(snapshot.data()).not.toHaveProperty('memberUids');
    expect(snapshot.data()).not.toHaveProperty('members');
    await assertSucceeds(
      getDocs(
        query(
          collection(db, 'driverClubs'),
          where('schemaVersion', '==', 2),
          where('status', '==', 'active'),
          orderBy('memberCount', 'desc'),
          orderBy(documentId()),
          limit(25),
        ),
      ),
    );
    await assertFails(
      getDocs(query(collection(db, 'driverClubs'), where('status', '==', 'active'), limit(25))),
    );
    await assertFails(
      getDocs(
        query(
          collection(db, 'driverClubs'),
          where('schemaVersion', '==', 2),
          where('status', '==', 'active'),
        ),
      ),
    );
    await assertFails(
      getDocs(
        query(
          collection(db, 'driverClubs'),
          where('schemaVersion', '==', 2),
          where('status', '==', 'active'),
          limit(26),
        ),
      ),
    );
    await assertFails(read('outsider', 'driverClubs/rules-archived'));
    await assertFails(read('outsider', 'driverClubs/rules-legacy'));
  });
  it.each([
    undefined,
    null,
    false,
    true,
    1,
    '2026-10-28T00:00:00Z',
    { seconds: 1, nanoseconds: 0 },
  ])(
    'denies club reads with enabled true but non-Timestamp activatedAt %j',
    async (activatedAt) => {
      try {
        await env.withSecurityRulesDisabled(async (context) => {
          const config: Record<string, unknown> = { ...enabledConfig };
          if (activatedAt === undefined) delete config.activatedAt;
          else config.activatedAt = activatedAt;
          await setDoc(doc(context.firestore(), 'config/sharedClubs'), config);
        });
        await assertSucceeds(read('member', 'config/sharedClubs'));
        await assertSucceeds(read('member', `driverClubAccounts/${uid('member')}`));
        for (const path of [
          clubPath,
          `${clubPath}/members/${uid('member')}`,
          `${clubPath}/messages/m1`,
          `${clubPath}/events/e1`,
          `${clubPath}/invitations/${uid('recipient')}`,
          `events/rules-club-event/clubs/${clubId}`,
        ])
          await assertFails(read('owner', path));
        const db = dbFor('outsider');
        await assertFails(
          getDocsFromServer(
            query(
              collection(db, 'driverClubs'),
              where('schemaVersion', '==', 2),
              where('status', '==', 'active'),
              limit(25),
            ),
          ),
        );
        await assertFails(
          getDocsFromServer(
            query(
              collection(db, 'events/rules-club-page-event/clubs'),
              orderBy(documentId()),
              limit(25),
            ),
          ),
        );
        await assertFails(
          getDocsFromServer(query(collection(dbFor('member'), `${clubPath}/messages`), limit(50))),
        );
      } finally {
        await env.withSecurityRulesDisabled(async (context) => {
          await setDoc(doc(context.firestore(), 'config/sharedClubs'), enabledConfig);
        });
      }
      await assertSucceeds(read('member', `${clubPath}/messages/m1`));
      await assertSucceeds(read('outsider', `events/rules-club-event/clubs/${clubId}`));
    },
  );
  it.each(canonicalRestrictions)(
    'denies $name only on shared-club authority',
    async ({ user, claims }) => {
      try {
        await env.withSecurityRulesDisabled(async (context) => {
          if (Object.keys(user).length)
            await updateDoc(doc(context.firestore(), `users/${uid('member')}`), user);
        });
        for (const path of [
          'config/sharedClubs',
          clubPath,
          `driverClubAccounts/${uid('member')}`,
          `${clubPath}/members/${uid('member')}`,
          `${clubPath}/messages/m1`,
          `${clubPath}/events/e1`,
          `events/rules-club-event/clubs/${clubId}`,
        ])
          await assertFails(read('member', path, { admin: true, ...claims }));
        const db = dbFor('member', { admin: true, ...claims });
        await assertFails(
          getDocsFromServer(
            query(
              collection(db, 'driverClubs'),
              where('schemaVersion', '==', 2),
              where('status', '==', 'active'),
              limit(25),
            ),
          ),
        );
        await assertFails(
          getDocsFromServer(query(collection(db, `${clubPath}/messages`), limit(50))),
        );
        await assertFails(
          getDocsFromServer(
            query(
              collection(db, 'events/rules-club-page-event/clubs'),
              orderBy(documentId()),
              limit(25),
            ),
          ),
        );
        // These new club checks must not change the existing public profile grant.
        await assertSucceeds(read('member', `users/${uid('owner')}`, claims));
      } finally {
        await env.withSecurityRulesDisabled(async (context) => {
          await updateDoc(doc(context.firestore(), `users/${uid('member')}`), {
            banned: deleteField(),
            status: deleteField(),
          });
        });
      }
      await assertSucceeds(read('member', `${clubPath}/messages/m1`));
    },
  );
  it.each([undefined, false, null, 'true', 1])(
    'fails closed on public attendance readiness %s without denying private membership',
    async (ready) => {
      try {
        await env.withSecurityRulesDisabled(async (context) => {
          const config: Record<string, unknown> = {
            enabled: true,
            activatedAt: Timestamp.fromMillis(1),
            statsSince: Timestamp.fromMillis(1),
          };
          if (ready !== undefined) config.publicAttendanceReady = ready;
          await setDoc(doc(context.firestore(), 'config/sharedClubs'), config);
        });
        await assertSucceeds(read('outsider', 'config/sharedClubs'));
        await assertFails(read('outsider', `events/rules-club-event/clubs/${clubId}`));
        await assertFails(
          getDocsFromServer(
            query(
              collection(dbFor('outsider'), 'events/rules-club-page-event/clubs'),
              orderBy(documentId()),
              limit(25),
            ),
          ),
        );
        await assertSucceeds(read('member', `${clubPath}/messages/m1`));
        await assertSucceeds(read('member', `${clubPath}/events/e1`));
      } finally {
        await env.withSecurityRulesDisabled(async (context) => {
          await setDoc(doc(context.firestore(), 'config/sharedClubs'), enabledConfig);
        });
      }
      await assertSucceeds(read('outsider', `events/rules-club-event/clubs/${clubId}`));
    },
  );

  it('denies fresh reads on archive and revokes the listener before cleanup can deliver a changed page', async () => {
    const archivedId = 'rules-page-club-000';
    const archivedPath = `driverClubs/${archivedId}`;
    const summaryPath = `events/rules-club-page-event/clubs/${archivedId}`;
    const freshPage = () =>
      query(
        collection(dbFor('outsider'), 'events/rules-club-page-event/clubs'),
        orderBy(documentId()),
        limit(25),
      );
    const page = freshPage();
    let markStarted!: () => void;
    const started = new Promise<void>((resolve) => {
      markStarted = resolve;
    });
    let markDenied!: (code: string) => void;
    const denied = new Promise<string>((resolve) => {
      markDenied = resolve;
    });
    let gateClosed = false;
    const deliveredAfterClosure: string[][] = [];
    let unsubscribe = () => {};
    const timer = setTimeout(() => markDenied('timeout'), 10000);
    unsubscribe = onSnapshot(
      page,
      (snapshot) => {
        if (!snapshot.metadata.fromCache) {
          if (gateClosed) deliveredAfterClosure.push(snapshot.docs.map((entry) => entry.id));
          else if (snapshot.size === 25) markStarted();
        }
      },
      (error) => markDenied(error.code),
    );
    try {
      expect(await Promise.race([started.then(() => 'started'), denied])).toBe('started');
      await env.withSecurityRulesDisabled(async (context) => {
        const admin = context.firestore();
        const batch = writeBatch(admin);
        batch.update(doc(admin, archivedPath), { status: 'archived' });
        batch.update(doc(admin, 'config/sharedClubs'), { publicAttendanceReady: false });
        await batch.commit();
      });
      gateClosed = true;
      // A new context forces a new server request, instead of joining the
      // already-current target of the existing listener in the same SDK app.
      await assertFails(getDocsFromServer(freshPage()));
      await assertFails(read('outsider', summaryPath));
      await assertSucceeds(read('member', `${clubPath}/messages/m1`));
      // The emulator does not invalidate this watch on the dependency-only
      // config write. Exercise the real cleanup source mutation: reevaluation
      // must deny the listener before it can deliver the changed public page.
      await env.withSecurityRulesDisabled(async (context) => {
        await deleteDoc(doc(context.firestore(), summaryPath));
      });
      expect(await denied).toBe('permission-denied');
      expect(deliveredAfterClosure).toEqual([]);
      await env.withSecurityRulesDisabled(async (context) => {
        await updateDoc(doc(context.firestore(), 'config/sharedClubs'), {
          publicAttendanceReady: true,
        });
      });
      const reopened = await assertSucceeds(getDocsFromServer(freshPage()));
      expect(reopened.size).toBe(25);
      expect(reopened.docs.some((entry) => entry.id === archivedId)).toBe(false);
      await assertSucceeds(read('member', `${clubPath}/messages/m1`));
    } finally {
      clearTimeout(timer);
      unsubscribe();
      await env.withSecurityRulesDisabled(async (context) => {
        await updateDoc(doc(context.firestore(), archivedPath), { status: 'active' });
        await setDoc(doc(context.firestore(), summaryPath), {
          clubId: archivedId,
          name: 'Club 0',
          logoUrl: null,
        });
        await setDoc(doc(context.firestore(), 'config/sharedClubs'), enabledConfig);
      });
    }
  });

  it('supports a server-confirmed 25-summary listener and document-ID cursor pages', async () => {
    const summaries = collection(dbFor('outsider'), 'events/rules-club-page-event/clubs');
    const firstQuery = query(summaries, orderBy(documentId()), limit(25));
    const first = await new Promise<QuerySnapshot<DocumentData>>((resolvePage, reject) => {
      const timer = setTimeout(() => {
        unsubscribe();
        reject(new Error('No server-confirmed attendance page'));
      }, 10000);
      const unsubscribe = onSnapshot(
        firstQuery,
        (snapshot) => {
          if (!snapshot.metadata.fromCache) {
            clearTimeout(timer);
            unsubscribe();
            resolvePage(snapshot);
          }
        },
        (error) => {
          clearTimeout(timer);
          unsubscribe();
          reject(error);
        },
      );
    });
    expect(first.size).toBe(25);
    expect(first.docs[0]?.id).toBe('rules-page-club-000');
    expect(first.docs[24]?.id).toBe('rules-page-club-024');
    const firstEnd = first.docs[24];
    if (!firstEnd) throw new Error('Missing end of the full 25-summary page');
    for (const entry of first.docs)
      expect(Object.keys(entry.data()).sort()).toEqual(['clubId', 'logoUrl', 'name']);
    const second = await assertSucceeds(
      getDocsFromServer(query(summaries, orderBy(documentId()), startAfter(firstEnd), limit(25))),
    );
    expect(second.docs.map((entry) => entry.id)).toEqual(
      Array.from(
        { length: 5 },
        (_, index) => `rules-page-club-${(index + 25).toString().padStart(3, '0')}`,
      ),
    );
    const secondEnd = second.docs[4];
    if (!secondEnd) throw new Error('Missing end of the five-summary next page');
    const end = await assertSucceeds(
      getDocsFromServer(query(summaries, orderBy(documentId()), startAfter(secondEnd), limit(25))),
    );
    expect(end.empty).toBe(true);
    await assertFails(getDocsFromServer(query(summaries, orderBy(documentId()), limit(26))));
    await assertFails(getDocsFromServer(query(summaries, orderBy(documentId()))));
  });
  it('denies public attendance pages for restricted/anonymous readers and unpublished/missing events', async () => {
    const page = (db: ReturnType<typeof dbFor>) =>
      query(collection(db, 'events/rules-club-page-event/clubs'), orderBy(documentId()), limit(25));
    for (const name of ['suspended', 'deleted', 'unfinished', 'driver-only', 'missing']) {
      await assertFails(getDocsFromServer(page(dbFor(name, { admin: true }))));
    }
    await assertFails(getDocsFromServer(page(dbFor('member', { suspended: true }))));
    await assertFails(getDocsFromServer(page(env.unauthenticatedContext().firestore())));
    try {
      for (const status of ['draft', 'cancelled', 'completed', 'missing']) {
        await env.withSecurityRulesDisabled(async (context) => {
          const event = doc(context.firestore(), 'events/rules-club-page-event');
          if (status === 'missing') await deleteDoc(event);
          else await setDoc(event, { ...futureEvent, status });
        });
        await assertFails(getDocsFromServer(page(dbFor('outsider'))));
      }
    } finally {
      await env.withSecurityRulesDisabled(async (context) => {
        await setDoc(doc(context.firestore(), 'events/rules-club-page-event'), futureEvent);
      });
    }
  });
  it.each([
    undefined,
    null,
    false,
    1,
    '2099-01-01T00:00:00Z',
    { seconds: 4070908800 },
    Timestamp.fromMillis(1),
  ])(
    'denies public attendance GET and list25 for unavailable/past startsAt %j',
    async (startsAt) => {
      try {
        await env.withSecurityRulesDisabled(async (context) => {
          const event: Record<string, unknown> = { status: 'published' };
          if (startsAt !== undefined) event.startsAt = startsAt;
          await setDoc(doc(context.firestore(), 'events/rules-club-page-event'), event);
        });
        await assertFails(
          read('outsider', 'events/rules-club-page-event/clubs/rules-page-club-000'),
        );
        await assertFails(
          getDocsFromServer(
            query(
              collection(dbFor('outsider'), 'events/rules-club-page-event/clubs'),
              orderBy(documentId()),
              limit(25),
            ),
          ),
        );
        await assertSucceeds(read('member', `${clubPath}/events/e1`));
      } finally {
        await env.withSecurityRulesDisabled(async (context) => {
          await setDoc(doc(context.firestore(), 'events/rules-club-page-event'), futureEvent);
        });
      }
      await assertSucceeds(
        read('outsider', 'events/rules-club-page-event/clubs/rules-page-club-000'),
      );
      expect(
        (
          await assertSucceeds(
            getDocsFromServer(
              query(
                collection(dbFor('outsider'), 'events/rules-club-page-event/clubs'),
                orderBy(documentId()),
                limit(25),
              ),
            ),
          )
        ).size,
      ).toBe(25);
    },
  );
  it('denies past and exact-start attendance, allowing future list25 at a deterministic request-time boundary', async () => {
    // The SDK cannot set request.time. Freeze only the production comparison's
    // clock in a separate demo namespace; the real-clock cases above still run
    // the unmodified rules. This proves strict > rather than a flaky wall-clock
    // approximation that would also pass if the production operator became >=.
    const rules = readFileSync(resolve(root, 'firestore.rules'), 'utf8');
    const comparison = 'event.data.startsAt > request.time';
    expect(rules.split(comparison)).toHaveLength(2);
    const boundary = Date.UTC(2030, 0, 1);
    const clockEnv = await initializeTestEnvironment({
      projectId: `${projectId}-club-clock`,
      firestore: {
        ...firestoreEndpoint,
        rules: rules.replace(comparison, 'event.data.startsAt > timestamp.date(2030, 1, 1)'),
      },
    });
    try {
      await clockEnv.withSecurityRulesDisabled(async (context) => {
        const db = context.firestore();
        const batch = writeBatch(db);
        batch.set(doc(db, `users/${uid('outsider')}`), activeUser);
        batch.set(doc(db, 'config/sharedClubs'), enabledConfig);
        batch.set(doc(db, clubPath), { schemaVersion: 2, status: 'active' });
        for (const [id, offset] of [
          ['past', -1],
          ['exact', 0],
          ['future', 1],
        ] as const) {
          batch.set(doc(db, `events/${id}`), {
            status: 'published',
            startsAt: Timestamp.fromMillis(boundary + offset),
          });
          for (let index = 0; index < 25; index++) {
            const idClub = index === 0 ? clubId : `boundary-club-${index}`;
            batch.set(doc(db, `events/${id}/clubs/${idClub}`), {
              clubId: idClub,
              name: 'Club',
              logoUrl: null,
            });
          }
        }
        await batch.commit();
      });
      for (const id of ['past', 'exact', 'future']) {
        const db = clockEnv.authenticatedContext(uid('outsider')).firestore();
        const pointRead = getDocFromServer(doc(db, `events/${id}/clubs/${clubId}`));
        if (id === 'future') await assertSucceeds(pointRead);
        else await assertFails(pointRead);
        const summaries = collection(db, `events/${id}/clubs`);
        const page = getDocsFromServer(query(summaries, orderBy(documentId()), limit(25)));
        if (id === 'future') expect((await assertSucceeds(page)).size).toBe(25);
        else await assertFails(page);
        await assertFails(getDocsFromServer(query(summaries, orderBy(documentId()), limit(26))));
        await assertFails(getDocsFromServer(query(summaries, orderBy(documentId()))));
      }
    } finally {
      await clockEnv.cleanup();
    }
  });
  it.each(['suspended', 'deleted', 'unfinished', 'driver-only', 'missing', 'claim-suspended'])(
    'denies %s identities even with elevated claims',
    async (name) => {
      await assertFails(
        read(name, clubPath, { admin: true, role: 'admin', suspended: name === 'claim-suspended' }),
      );
      await assertFails(
        read(name, `events/rules-club-event/clubs/${clubId}`, {
          admin: true,
          suspended: name === 'claim-suspended',
        }),
      );
    },
  );
  it('denies anonymous users and keeps locks owner-get-only and history/operations private', async () => {
    await assertFails(getDocFromServer(doc(env.unauthenticatedContext().firestore(), clubPath)));
    await assertSucceeds(read('member', `driverClubAccounts/${uid('member')}`));
    await assertFails(read('admin', `driverClubAccounts/${uid('member')}`, { admin: true }));
    await assertFails(getDocs(query(collection(dbFor('member'), 'driverClubAccounts'), limit(1))));
    for (const path of [
      `driverClubAccounts/${uid('member')}/history/h1`,
      'driverClubOperations/op',
      'driverClubCleanup/job',
      'driverClubEventOperations/op',
      'driverClubEventProjectionJobs/job',
      'driverClubEventRevocations/revocation',
      'driverClubRideContributions/ride',
      `${clubPath}/stats/private`,
      `${clubPath}/members/${uid('member')}/rides/secret`,
    ]) {
      await assertFails(read('member', path));
    }
  });
  it('exposes only public event club summaries, not member identities or private calendars', async () => {
    const snapshot = await assertSucceeds(
      read('outsider', `events/rules-club-event/clubs/${clubId}`),
    );
    expect(Object.keys(snapshot.data()!).sort()).toEqual(['clubId', 'logoUrl', 'name']);
    // Transitional projections may use clubName; canonical new writes use name.
    try {
      await env.withSecurityRulesDisabled(async (context) => {
        await updateDoc(doc(context.firestore(), `events/rules-club-event/clubs/${clubId}`), {
          name: deleteField(),
          clubName: 'Shared club',
        });
      });
      await assertSucceeds(read('outsider', `events/rules-club-event/clubs/${clubId}`));
    } finally {
      await env.withSecurityRulesDisabled(async (context) => {
        await updateDoc(doc(context.firestore(), `events/rules-club-event/clubs/${clubId}`), {
          clubName: deleteField(),
          name: 'Shared club',
        });
      });
    }
    await assertFails(read('outsider', `${clubPath}/events/e1`));
    await assertFails(read('outsider', `${clubPath}/members/${uid('member')}`));
    await assertFails(read('outsider', `events/rules-club-event/clubs/rules-archived`));
    // Server projection mistakes fail closed instead of publishing identities.
    try {
      await env.withSecurityRulesDisabled(async (context) => {
        await updateDoc(doc(context.firestore(), `events/rules-club-event/clubs/${clubId}`), {
          memberUids: [uid('member')],
        });
      });
      await assertFails(read('outsider', `events/rules-club-event/clubs/${clubId}`));
    } finally {
      await env.withSecurityRulesDisabled(async (context) => {
        await updateDoc(doc(context.firestore(), `events/rules-club-event/clubs/${clubId}`), {
          memberUids: deleteField(),
        });
      });
    }
    await assertSucceeds(
      getDocs(
        query(
          collection(dbFor('outsider'), 'events/rules-club-event/clubs'),
          where(documentId(), '==', clubId),
          limit(5),
        ),
      ),
    );

    await assertFails(
      getDocs(query(collection(dbFor('outsider'), 'events/rules-club-event/clubs'), limit(26))),
    );
  });
});

describe('shared clubs membership and server authority', () => {
  it('allows current member roster/chat/calendar but no public, admin or role-claim bypass', async () => {
    for (const path of [
      `${clubPath}/members/${uid('owner')}`,
      `${clubPath}/messages/m1`,
      `${clubPath}/events/e1`,
    ]) {
      await assertSucceeds(read('member', path));
      await assertFails(read('outsider', path));
      await assertFails(read('admin', path, { admin: true, role: 'owner' }));
    }
    await assertSucceeds(
      getDocs(
        query(collection(dbFor('member'), `${clubPath}/members`), orderBy(documentId()), limit(25)),
      ),
    );
    await assertSucceeds(
      getDocs(
        query(
          collection(dbFor('member'), `${clubPath}/messages`),
          orderBy('createdAt', 'desc'),
          limit(50),
        ),
      ),
    );
    await assertSucceeds(
      getDocs(
        query(collection(dbFor('member'), `${clubPath}/events`), orderBy('startMillis'), limit(25)),
      ),
    );
    await assertFails(getDocs(collection(dbFor('member'), `${clubPath}/messages`)));
    await assertFails(
      getDocs(query(collection(dbFor('outsider'), `${clubPath}/members`), limit(25))),
    );
  });
  it('reads private invitations only as recipient or authoritative manager', async () => {
    const path = `${clubPath}/invitations/${uid('recipient')}`;
    for (const name of ['recipient', 'owner', 'moderator']) await assertSucceeds(read(name, path));
    for (const name of ['member', 'outsider', 'admin', 'fake-owner'])
      await assertFails(read(name, path, { admin: true, role: 'moderator' }));
    await assertSucceeds(
      getDocs(query(collection(dbFor('owner'), `${clubPath}/invitations`), limit(25))),
    );
    await assertSucceeds(
      getDocs(
        query(
          collection(dbFor('recipient'), `${clubPath}/invitations`),
          where('recipientUid', '==', uid('recipient')),
          limit(25),
        ),
      ),
    );
    // The cross-club state query is an Admin SDK callable, not a recursive
    // grant on every collection named invitations anywhere in the database.
    await assertFails(
      getDocs(
        query(
          collectionGroup(dbFor('recipient'), 'invitations'),
          where('recipientUid', '==', uid('recipient')),
          limit(25),
        ),
      ),
    );
  });
  it('denies all direct mutations, including from owners/managers/global admins', async () => {
    for (const name of ['owner', 'moderator', 'member', 'admin']) {
      const db = dbFor(name, { admin: true, role: 'owner' });
      for (const path of [
        clubPath,
        `${clubPath}/members/${uid(name)}`,
        `${clubPath}/invitations/${uid('recipient')}`,
        `${clubPath}/messages/m1`,
        `${clubPath}/events/e1`,
        `driverClubAccounts/${uid(name)}`,
        `events/rules-club-event/clubs/${clubId}`,
      ]) {
        await assertFails(setDoc(doc(db, path), { role: 'owner', text: 'forged' }));
        await assertFails(deleteDoc(doc(db, path)));
      }
    }
  });
  it('observes self membership absence and denies old chat get/query/send after removal', async () => {
    const db = dbFor('removed');
    const self = doc(db, `${clubPath}/members/${uid('removed')}`);
    await assertSucceeds(getDocFromServer(self));
    let unsubscribe = () => {};
    const absent = new Promise<void>((resolveAbsence, reject) => {
      const timer = setTimeout(
        () => reject(new Error('Membership listener did not observe removal')),
        5000,
      );
      unsubscribe = onSnapshot(
        self,
        (snapshot) => {
          if (!snapshot.metadata.fromCache && !snapshot.exists()) {
            clearTimeout(timer);
            resolveAbsence();
          }
        },
        (error) => {
          clearTimeout(timer);
          reject(error);
        },
      );
    });
    try {
      await env.withSecurityRulesDisabled(async (context) => {
        await deleteDoc(doc(context.firestore(), `${clubPath}/members/${uid('removed')}`));
        await updateDoc(doc(context.firestore(), `driverClubAccounts/${uid('removed')}`), {
          activeClubId: null,
        });
      });
      await absent;
      expect((await assertSucceeds(getDocFromServer(self))).exists()).toBe(false);
      await assertFails(getDocFromServer(doc(db, `${clubPath}/messages/m1`)));
      await assertFails(getDocs(query(collection(db, `${clubPath}/messages`), limit(50))));
      await assertFails(setDoc(doc(db, `${clubPath}/messages/new`), { text: 'old member' }));
      await assertFails(read('removed', `${clubPath}/events/e1`));
      await assertFails(getDocs(query(collection(db, `${clubPath}/members`), limit(25))));
    } finally {
      unsubscribe();
    }
  });
  it('revokes immediately when only the lock changes or canonical deletion is tombstoned', async () => {
    await env.withSecurityRulesDisabled(async (context) => {
      await updateDoc(doc(context.firestore(), `driverClubAccounts/${uid('lock-only')}`), {
        activeClubId: 'some-other-club',
      });
    });
    for (const name of ['lock-only', 'revoked']) {
      for (const path of [
        `${clubPath}/messages/m1`,
        `${clubPath}/events/e1`,
        `${clubPath}/members/${uid('member')}`,
        `${clubPath}/members/${uid(name)}`,
      ])
        await assertFails(read(name, path));
      await assertFails(getDocs(query(collection(dbFor(name), `${clubPath}/messages`), limit(50))));
    }
  });
  it('denies all member rights on archival without waiting for membership cleanup', async () => {
    try {
      await env.withSecurityRulesDisabled(async (context) => {
        await updateDoc(doc(context.firestore(), clubPath), { status: 'archived' });
      });
      for (const path of [
        clubPath,
        `${clubPath}/members/${uid('member')}`,
        `${clubPath}/messages/m1`,
        `${clubPath}/events/e1`,
        `${clubPath}/invitations/${uid('recipient')}`,
        `events/rules-club-event/clubs/${clubId}`,
      ]) {
        await assertFails(read('owner', path));
      }
      await assertFails(
        getDocs(query(collection(dbFor('member'), `${clubPath}/messages`), limit(50))),
      );
    } finally {
      await env.withSecurityRulesDisabled(async (context) => {
        await updateDoc(doc(context.firestore(), clubPath), { status: 'active' });
      });
    }
  });
  it('rechecks canonical restrictions against stale authenticated membership', async () => {
    try {
      await env.withSecurityRulesDisabled(async (context) => {
        await updateDoc(doc(context.firestore(), `users/${uid('member')}`), { deleted: true });
      });
      await assertFails(read('member', `${clubPath}/messages/m1`));
      await assertFails(
        getDocs(query(collection(dbFor('member'), `${clubPath}/messages`), limit(50))),
      );
    } finally {
      await env.withSecurityRulesDisabled(async (context) => {
        await updateDoc(doc(context.firestore(), `users/${uid('member')}`), { deleted: false });
      });
    }
  });
  it('rejects badge assignment, replacement and deletion through actual canonical profile writes', async () => {
    const db = dbFor('member');
    const user = doc(db, `users/${uid('member')}`);
    const clubBadge = { clubId, clubName: 'forged', logoUrl: null };
    await assertFails(updateDoc(user, { clubBadge }));
    await assertFails(setDoc(user, { displayName: 'forged', clubBadge }, { merge: true }));
    await env.withSecurityRulesDisabled(async (context) => {
      await updateDoc(doc(context.firestore(), `users/${uid('member')}`), { clubBadge });
    });
    await assertFails(updateDoc(user, { 'clubBadge.clubId': 'other' }));
    await assertFails(updateDoc(user, { clubBadge: deleteField() }));
    await assertSucceeds(updateDoc(user, { displayName: 'Allowed edit' }));
  });
});

describe('shared club immutable logos', () => {
  const storageFor = (name: string, claims = {}) =>
    env.authenticatedContext(uid(name), claims).storage();
  it.each([
    ['image/jpeg', 'jpg'],
    ['image/webp', 'webp'],
  ])(
    'allows only owner creates at the exact 256KiB %s boundary',
    async (contentType, extension) => {
      const path = `clubLogos/${clubId}/${uid('owner')}/${logoRun}-boundary.${extension}`;
      const logo = ref(storageFor('owner'), path);
      await assertSucceeds(uploadBytes(logo, new Uint8Array(256 * 1024), { contentType }));
      await assertFails(uploadBytes(logo, new Uint8Array([1]), { contentType }));
      await assertFails(deleteObject(logo));
      await assertSucceeds(getBytes(ref(storageFor('outsider'), path)));
      await assertFails(getBytes(ref(env.unauthenticatedContext().storage(), path)));
      await assertFails(
        uploadBytes(
          ref(storageFor('owner'), `clubLogos/${clubId}/${uid('owner')}/too-big.${extension}`),
          new Uint8Array(256 * 1024 + 1),
          { contentType },
        ),
      );
    },
  );
  it.each(['suspended', 'deleted', 'unfinished', 'driver-only'])(
    'denies %s even when it is the authoritative club owner',
    async (name) => {
      await env.withSecurityRulesDisabled(async (context) => {
        await setDoc(doc(context.firestore(), `driverClubs/rules-logo-${name}`), {
          schemaVersion: 2,
          status: 'active',
          ownerUid: uid(name),
        });
      });
      await assertFails(
        uploadBytes(
          ref(
            storageFor(name, { admin: true }),
            `clubLogos/rules-logo-${name}/${uid(name)}/logo.jpg`,
          ),
          new Uint8Array([1]),
          { contentType: 'image/jpeg' },
        ),
      );
    },
  );
  it.each(canonicalRestrictions)(
    'denies logo creation for $name without another document lookup',
    async ({ name, user, claims }) => {
      const id = `rules-logo-${name.replaceAll(' ', '-')}`;
      await env.withSecurityRulesDisabled(async (context) => {
        const db = context.firestore();
        await setDoc(doc(db, `users/${uid(id)}`), { ...activeUser, ...user });
        await setDoc(doc(db, `driverClubs/${id}`), {
          schemaVersion: 2,
          status: 'active',
          ownerUid: uid(id),
        });
      });
      const storage = env.authenticatedContext(uid(id), { admin: true, ...claims }).storage();
      await assertFails(
        uploadBytes(ref(storage, `clubLogos/${id}/${uid(id)}/logo.jpg`), new Uint8Array([1]), {
          contentType: 'image/jpeg',
        }),
      );
      // Public profile reads retain their existing grant for these identities.
      await assertSucceeds(
        getDocFromServer(
          doc(env.authenticatedContext(uid(id), claims).firestore(), `users/${uid('owner')}`),
        ),
      );
    },
  );
  it('rejects SVG/PNG, mismatched extension, empty, wrong path, nonowners and elevated claims', async () => {
    for (const [contentType, fileName, size] of [
      ['image/svg+xml', 'logo.svg', 1],
      ['image/png', 'logo.png', 1],
      ['image/jpeg', 'logo.svg', 1],
      ['image/jpeg', 'empty.jpg', 0],
    ] as const) {
      await assertFails(
        uploadBytes(
          ref(storageFor('owner'), `clubLogos/${clubId}/${uid('owner')}/${fileName}`),
          new Uint8Array(size),
          { contentType },
        ),
      );
    }
    for (const name of [
      'member',
      'moderator',
      'outsider',
      'admin',
      'suspended',
      'deleted',
      'unfinished',
      'driver-only',
    ]) {
      await assertFails(
        uploadBytes(
          ref(storageFor(name, { admin: true }), `clubLogos/${clubId}/${uid(name)}/logo.jpg`),
          new Uint8Array([1]),
          { contentType: 'image/jpeg' },
        ),
      );
      await assertFails(
        uploadBytes(
          ref(storageFor(name, { admin: true }), `clubLogos/${clubId}/${uid('owner')}/${name}.jpg`),
          new Uint8Array([1]),
          { contentType: 'image/jpeg' },
        ),
      );
    }
    await assertFails(
      uploadBytes(
        ref(
          storageFor('owner', { suspended: true }),
          `clubLogos/${clubId}/${uid('owner')}/suspended.jpg`,
        ),
        new Uint8Array([1]),
        { contentType: 'image/jpeg' },
      ),
    );
    await assertFails(
      uploadBytes(
        ref(storageFor('owner'), `clubLogos/rules-archived/${uid('owner')}/logo.jpg`),
        new Uint8Array([1]),
        { contentType: 'image/jpeg' },
      ),
    );
    await assertFails(
      uploadBytes(
        ref(storageFor('owner'), `clubLogos/rules-legacy/${uid('owner')}/logo.jpg`),
        new Uint8Array([1]),
        { contentType: 'image/jpeg' },
      ),
    );
    await assertFails(listAll(ref(storageFor('outsider'), 'clubLogos')));
  });
});
