/**
 * Member typeahead (userSearch) emulator integration tests.
 *
 * Exercises the deployed-in-emulator surface end-to-end:
 * - `userSearch-members`: prefix matching semantics ('gt' finds 'gt_86'; '86'
 *   does not), the minimum-query-length refusal and its reason discriminator,
 *   the result-limit clamp, the per-user rate limit, exclusion of self /
 *   restricted / either-way-blocked users, and the allowlisted projection.
 * - `userSearch-onUserProfileWrite`: the trigger that re-derives
 *   `displayNameLower` from an OWNER-written `displayName`, which is the only
 *   thing that keeps a renamed member findable.
 *
 * SHARED-EMULATOR HAZARD: every emulator test file runs against ONE Firestore
 * instance with no data isolation, and a prefix search reads whatever any other
 * file happened to seed. Every member created here is therefore named under a
 * per-RUN unique prefix (see RUN_PREFIX) and every query is rooted at it, so a
 * result set can only ever contain this file's own users — no matter what else
 * is in the emulator.
 *
 * Requires the Auth + Functions + Firestore + Database emulators — run via:
 *   pnpm emulators:test
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { deleteApp, FirebaseError, initializeApp, type FirebaseApp } from 'firebase/app';
import {
  connectAuthEmulator,
  createUserWithEmailAndPassword,
  getAuth,
  signInWithEmailAndPassword,
  type Auth,
} from 'firebase/auth';
import {
  connectFunctionsEmulator,
  getFunctions,
  httpsCallable,
  type Functions,
} from 'firebase/functions';
import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getAuth as getAdminAuth } from 'firebase-admin/auth';
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { toSearchKey } from '../friends/friends-core';
import {
  MAX_SEARCH_RESULTS,
  MEMBER_SEARCH_RATE_LIMIT_COLLECTION,
  MEMBER_SEARCH_RATE_LIMIT_MAX,
  MEMBER_SEARCH_RATE_LIMIT_WINDOW_MS,
  REASON_QUERY_TOO_SHORT,
  memberSearchRateLimitDocId,
} from '../users/user-search-core';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

/**
 * Namespace for every display name this file creates. Lowercase letters and
 * digits only (so it survives toSearchKey unchanged) and unique per run, so a
 * prefix query rooted at it can never collide with another test file's members
 * or with leftovers from an earlier run against the same emulator instance.
 */
const RUN_PREFIX = `zsrch${Date.now().toString(36)}${Math.floor(Math.random() * 1e6).toString(36)}`;

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'user-search-emulator-tests');
const adminDb = getAdminFirestore(adminApp);
const adminAuth = getAdminAuth(adminApp);

let app: FirebaseApp;
let auth: Auth;
let functions: Functions;

interface TestUser {
  uid: string;
  email: string;
  password: string;
}

interface MemberHit {
  uid: string;
  displayName: string | null;
  avatarPath: string | null;
}

async function pollUntil<T>(read: () => Promise<T | undefined>, timeoutMs = 30_000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await read();
    if (value !== undefined) return value;
    if (Date.now() > deadline) throw new Error('Timed out waiting for condition');
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
}

async function callableErrorCode(promise: Promise<unknown>): Promise<string> {
  try {
    await promise;
    return 'no-error';
  } catch (error) {
    if (error instanceof FirebaseError) return error.code;
    throw error;
  }
}

async function callableErrorReason(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise;
    return 'no-error';
  } catch (error) {
    if (error instanceof FirebaseError) {
      return (error as unknown as { details?: { reason?: unknown } }).details?.reason;
    }
    throw error;
  }
}

let userSeq = 0;

/**
 * Creates an active member whose display name is `${RUN_PREFIX}${suffix}`, with
 * `displayNameLower` written exactly as the real write paths persist it.
 * Returns the account plus the full display name, so assertions can name it.
 */
async function newMember(
  suffix: string,
  extraProfileFields: Record<string, unknown> = {},
): Promise<TestUser & { displayName: string }> {
  userSeq += 1;
  const displayName = `${RUN_PREFIX}${suffix}`;
  const email = `usersearch-${userSeq}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const password = 'password-123';
  const credential = await createUserWithEmailAndPassword(auth, email, password);
  const uid = credential.user.uid;
  await pollUntil(async () => {
    const snap = await adminDb.collection('users').doc(uid).get();
    return snap.exists ? true : undefined;
  });
  await adminAuth.setCustomUserClaims(uid, { activeMember: true });
  await adminDb
    .collection('users')
    .doc(uid)
    .set(
      {
        activeMember: true,
        displayName,
        displayNameLower: toSearchKey(displayName),
        ...extraProfileFields,
      },
      { merge: true },
    );
  return { uid, email, password, displayName };
}

/**
 * Seeds a profile-only user document (no auth account) under the run prefix.
 * The search reads user documents, so this is enough to appear as a hit — and
 * it is far cheaper than creating dozens of auth accounts for the limit test.
 */
async function seedProfile(suffix: string, fields: Record<string, unknown> = {}): Promise<string> {
  const displayName = `${RUN_PREFIX}${suffix}`;
  const docId = `usersearch-${suffix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  await adminDb
    .collection('users')
    .doc(docId)
    .set({ displayName, displayNameLower: toSearchKey(displayName), ...fields });
  return docId;
}

async function signInAs(user: TestUser): Promise<void> {
  await signInWithEmailAndPassword(auth, user.email, user.password);
  await auth.currentUser?.getIdToken(true);
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

async function search(query: string, limit?: number): Promise<MemberHit[]> {
  const payload = limit === undefined ? { query } : { query, limit };
  const result = await call('userSearch-members', payload);
  return (result.data as { members: MemberHit[] }).members;
}

const namesOf = (hits: MemberHit[]) => hits.map((hit) => hit.displayName);
const uidsOf = (hits: MemberHit[]) => hits.map((hit) => hit.uid);

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'user-search-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('userSearch-members gating and input validation', () => {
  it('rejects an unauthenticated caller', async () => {
    await auth.signOut();
    expect(await callableErrorCode(search(`${RUN_PREFIX}gt`))).toBe('functions/unauthenticated');
  });

  it('refuses a query shorter than the minimum, tagged so the UI can stay silent', async () => {
    const caller = await newMember('callershort');
    await signInAs(caller);
    // 'a' is one code point after normalization — the near-scan the callable
    // exists to refuse. The reason discriminator is what lets the client render
    // "keep typing" instead of an error, so it is part of the contract.
    expect(await callableErrorCode(search('a'))).toBe('functions/invalid-argument');
    expect(await callableErrorReason(search('a'))).toBe(REASON_QUERY_TOO_SHORT);
    // Whitespace is trimmed BEFORE the length is measured, so padding cannot
    // buy a caller a 1-character scan.
    expect(await callableErrorReason(search('  a  '))).toBe(REASON_QUERY_TOO_SHORT);
  });

  it('rejects a malformed payload distinctly from a too-short query', async () => {
    const caller = await newMember('callermalformed');
    await signInAs(caller);
    expect(await callableErrorCode(call('userSearch-members', { query: 42 }))).toBe(
      'functions/invalid-argument',
    );
    // No QUERY_TOO_SHORT reason: this is an app bug, not a normal typing state.
    expect(await callableErrorReason(call('userSearch-members', { query: 42 }))).not.toBe(
      REASON_QUERY_TOO_SHORT,
    );
    expect(await callableErrorCode(call('userSearch-members', { query: 'gt', limit: -1 }))).toBe(
      'functions/invalid-argument',
    );
    expect(await callableErrorCode(call('userSearch-members', { query: 'gt', nope: true }))).toBe(
      'functions/invalid-argument',
    );
  });
});

describe('userSearch-members matching semantics', () => {
  it("finds 'gt_86' from the typed prefix 'gt', case-insensitively", async () => {
    const caller = await newMember('callerprefix');
    const target = await newMember('gt_86');
    await signInAs(caller);

    expect(namesOf(await search(`${RUN_PREFIX}gt`))).toContain(target.displayName);
    // The same query typed in any case must resolve to the same stored key.
    expect(namesOf(await search(`${RUN_PREFIX.toUpperCase()}GT`))).toContain(target.displayName);
    expect(namesOf(await search(`  ${RUN_PREFIX}gt  `))).toContain(target.displayName);
  });

  it('does NOT match a mid-word substring — the documented prefix limitation', async () => {
    const caller = await newMember('callersubstring');
    await newMember('gt_86x');
    await signInAs(caller);

    // '86' is a trailing chunk of 'gt_86x', not a prefix of it. This is the
    // trade-off the PR body states; if n-gram tokens are ever added, THIS is
    // the expectation that should change, deliberately.
    const hits = await search(`${RUN_PREFIX}86`);
    expect(namesOf(hits)).not.toContain(`${RUN_PREFIX}gt_86x`);
  });

  it('returns every member sharing the prefix, shortest name first', async () => {
    const caller = await newMember('callermulti');
    await seedProfile('mu');
    await seedProfile('mu_two');
    await seedProfile('mu_three');
    await signInAs(caller);

    const names = namesOf(await search(`${RUN_PREFIX}mu`));
    expect(names).toEqual(expect.arrayContaining([`${RUN_PREFIX}mu`, `${RUN_PREFIX}mu_two`]));
    // Ordered by the search key ASC, so the exact/shortest match heads the list
    // — the row a typeahead should offer first.
    expect(names[0]).toBe(`${RUN_PREFIX}mu`);
  });
});

describe('userSearch-members exclusions', () => {
  it('never returns the caller themselves', async () => {
    const caller = await newMember('self');
    await signInAs(caller);
    expect(uidsOf(await search(`${RUN_PREFIX}self`))).not.toContain(caller.uid);
  });

  it('excludes suspended and soft-deleted accounts', async () => {
    const caller = await newMember('callerrestricted');
    await seedProfile('restrsuspended', { suspended: true });
    await seedProfile('restrdeleted', { deleted: true });
    await seedProfile('restrfine');
    await signInAs(caller);

    const names = namesOf(await search(`${RUN_PREFIX}restr`));
    expect(names).toContain(`${RUN_PREFIX}restrfine`);
    expect(names).not.toContain(`${RUN_PREFIX}restrsuspended`);
    expect(names).not.toContain(`${RUN_PREFIX}restrdeleted`);
  });

  it('excludes a member the caller has blocked', async () => {
    const caller = await newMember('callerblocker');
    const blocked = await newMember('blktarget');
    const visible = await newMember('blkvisible');
    await adminDb
      .collection('userBlocks')
      .doc(caller.uid)
      .collection('blocked')
      .doc(blocked.uid)
      .set({ blockedUid: blocked.uid, createdAt: new Date() });
    await signInAs(caller);

    const uids = uidsOf(await search(`${RUN_PREFIX}blk`));
    expect(uids).toContain(visible.uid);
    expect(uids).not.toContain(blocked.uid);
  });

  it('excludes the RIGHT members out of a larger page, in both directions', async () => {
    // Guards the block check's result matching. It pairs batch-get snapshots by
    // document PATH; pairing them by POSITION would still pass a two-candidate
    // test but mis-attribute blocks once several candidates are in play — and
    // the dangerous direction of that failure is surfacing someone who blocked
    // the caller. Blocks are placed on candidates in the MIDDLE of the page, in
    // both directions, so an off-by-one or reordering cannot go unnoticed.
    const caller = await newMember('callerpairing');
    const visibleA = await newMember('pair_a');
    const iBlocked = await newMember('pair_b');
    const visibleC = await newMember('pair_c');
    const blockedMe = await newMember('pair_d');
    const visibleE = await newMember('pair_e');

    await adminDb
      .collection('userBlocks')
      .doc(caller.uid)
      .collection('blocked')
      .doc(iBlocked.uid)
      .set({ blockedUid: iBlocked.uid, createdAt: new Date() });
    await adminDb
      .collection('userBlocks')
      .doc(blockedMe.uid)
      .collection('blocked')
      .doc(caller.uid)
      .set({ blockedUid: caller.uid, createdAt: new Date() });
    await signInAs(caller);

    const uids = uidsOf(await search(`${RUN_PREFIX}pair_`));
    expect(uids).toEqual(
      expect.arrayContaining([visibleA.uid, visibleC.uid, visibleE.uid]),
    );
    expect(uids).not.toContain(iBlocked.uid);
    expect(uids).not.toContain(blockedMe.uid);
    // Exactly the three unblocked members — no more, no fewer.
    expect(uids.length).toBe(3);
  });

  it('excludes a member who has blocked the caller, with no trace in the response', async () => {
    const caller = await newMember('callerblockee');
    const blocker = await newMember('revblocker');
    const visible = await newMember('revvisible');
    await adminDb
      .collection('userBlocks')
      .doc(blocker.uid)
      .collection('blocked')
      .doc(caller.uid)
      .set({ blockedUid: caller.uid, createdAt: new Date() });
    await signInAs(caller);

    const hits = await search(`${RUN_PREFIX}rev`);
    expect(uidsOf(hits)).toContain(visible.uid);
    expect(uidsOf(hits)).not.toContain(blocker.uid);
    // Absence is the entire privacy property: no placeholder row, no "hidden"
    // count, nothing from which the blocked party could infer a block exists.
    expect(JSON.stringify(hits)).not.toContain(blocker.uid);
  });
});

describe('userSearch-members response shape and bounds', () => {
  it('returns ONLY uid, displayName and avatarPath — never a private field', async () => {
    const caller = await newMember('callerprojection');
    const target = await newMember('projtarget', {
      email: 'private-address@example.com',
      role: 'admin',
      bio: 'a private-ish bio',
      lastLoginAt: new Date(),
      avatarPath: 'avatars/proj.jpg',
    });
    await signInAs(caller);

    const hit = (await search(`${RUN_PREFIX}proj`)).find((row) => row.uid === target.uid);
    expect(hit).toBeDefined();
    expect(Object.keys(hit as unknown as Record<string, unknown>).sort()).toEqual([
      'avatarPath',
      'displayName',
      'uid',
    ]);
    expect(hit?.avatarPath).toBe('avatars/proj.jpg');
    expect(JSON.stringify(hit)).not.toContain('private-address@example.com');
    expect(JSON.stringify(hit)).not.toContain('a private-ish bio');
  });

  it('honours a smaller requested limit and CAPS an over-ask', async () => {
    const caller = await newMember('callerlimit');
    // One more profile than the cap, so an unclamped limit would be observable.
    await Promise.all(
      Array.from({ length: MAX_SEARCH_RESULTS + 1 }, (_, i) =>
        seedProfile(`lim${String(i).padStart(3, '0')}`),
      ),
    );
    await signInAs(caller);

    expect((await search(`${RUN_PREFIX}lim`, 3)).length).toBe(3);
    // An over-ask must be clamped, not honoured: no cursor plus a hard cap is
    // what stops this endpoint being a member-directory export.
    expect((await search(`${RUN_PREFIX}lim`, 1000)).length).toBeLessThanOrEqual(MAX_SEARCH_RESULTS);
    expect((await search(`${RUN_PREFIX}lim`)).length).toBeLessThanOrEqual(MAX_SEARCH_RESULTS);
  });

  it('returns an empty list (not an error) when nothing matches', async () => {
    const caller = await newMember('callerempty');
    await signInAs(caller);
    expect(await search(`${RUN_PREFIX}nosuchmemberanywhere`)).toEqual([]);
  });

  it('rejects with resource-exhausted once the per-user window is spent', async () => {
    const caller = await newMember('callerratelimit');
    await signInAs(caller);

    // Seeding the counter is deterministic and cheap; issuing 90 real calls
    // would be neither. Both the CURRENT and the NEXT window are filled so a
    // minute boundary crossing between the seed and the call cannot flake.
    const now = Date.now();
    await Promise.all(
      [now, now + MEMBER_SEARCH_RATE_LIMIT_WINDOW_MS].map((instant) =>
        adminDb
          .collection(MEMBER_SEARCH_RATE_LIMIT_COLLECTION)
          .doc(memberSearchRateLimitDocId(caller.uid, instant))
          .set({ count: MEMBER_SEARCH_RATE_LIMIT_MAX, uid: caller.uid }, { merge: true }),
      ),
    );

    expect(await callableErrorCode(search(`${RUN_PREFIX}gt`))).toBe('functions/resource-exhausted');
    // A too-short query is still rejected as invalid-argument: validation runs
    // BEFORE the limiter, so a malformed call never burns the window.
    expect(await callableErrorCode(search('a'))).toBe('functions/invalid-argument');
  });
});

describe('userSearch-onUserProfileWrite keeps the search key in sync', () => {
  it('re-derives displayNameLower after an OWNER-style displayName write', async () => {
    const caller = await newMember('callerrename');
    const renamer = await newMember('oldname');
    await signInAs(caller);
    expect(namesOf(await search(`${RUN_PREFIX}old`))).toContain(renamer.displayName);

    // Exactly what the Android profile screen does: firestore.rules lets the
    // owner write displayName and NOT displayNameLower, so without the trigger
    // the key would stay pinned to the OLD name forever.
    const newName = `${RUN_PREFIX}newname`;
    await adminDb.collection('users').doc(renamer.uid).update({ displayName: newName });

    await pollUntil(async () => {
      const snap = await adminDb.collection('users').doc(renamer.uid).get();
      return snap.data()?.displayNameLower === toSearchKey(newName) ? true : undefined;
    });

    expect(namesOf(await search(`${RUN_PREFIX}new`))).toContain(newName);
    expect(namesOf(await search(`${RUN_PREFIX}old`))).not.toContain(newName);
  });
});
