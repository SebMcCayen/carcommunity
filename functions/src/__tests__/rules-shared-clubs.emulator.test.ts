/** Combined CarCommunity rules, never the Driver emulator-only subset.
 * Run serially with the shared Firebase emulator slot reserved:
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
} from 'firebase/firestore';
import { deleteObject, getBytes, listAll, ref, uploadBytes } from 'firebase/storage';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

let env: RulesTestEnvironment;
let previousConfig: Record<string, unknown> | undefined;
const logoRun = Date.now().toString();
const root = resolve(__dirname, '../../../firebase');
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

beforeAll(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-test',
    firestore: {
      host: 'localhost',
      port: 8080,
      rules: readFileSync(resolve(root, 'firestore.rules'), 'utf8'),
    },
    storage: {
      host: 'localhost',
      port: 9199,
      rules: readFileSync(resolve(root, 'storage.rules'), 'utf8'),
    },
  });
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    previousConfig = (await getDocFromServer(doc(db, 'config/sharedClubs'))).data();
    await setDoc(doc(db, 'config/sharedClubs'), {
      enabled: true,
      statsSince: Timestamp.fromMillis(1),
    });
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
    await setDoc(doc(db, 'events/rules-club-event'), { status: 'published' });
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
    await setDoc(doc(db, 'events/rules-club-page-event'), { status: 'published' });
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
    await assertFails(read('suspended', 'config/sharedClubs'));
    await assertFails(read('driver-only', 'config/sharedClubs'));
    try {
      for (const mode of ['disabled', 'absent']) {
        await env.withSecurityRulesDisabled(async (context) => {
          const config = doc(context.firestore(), 'config/sharedClubs');
          if (mode === 'absent') await deleteDoc(config);
          else await setDoc(config, { enabled: false, statsSince: null });
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
        await setDoc(doc(context.firestore(), 'config/sharedClubs'), {
          enabled: true,
          statsSince: Timestamp.fromMillis(1),
        });
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
          else await setDoc(event, { status });
        });
        await assertFails(getDocsFromServer(page(dbFor('outsider'))));
      }
    } finally {
      await env.withSecurityRulesDisabled(async (context) => {
        await setDoc(doc(context.firestore(), 'events/rules-club-page-event'), {
          status: 'published',
        });
      });
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
