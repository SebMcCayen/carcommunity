/**
 * Focused regressions using the REAL Follow Me handler and local Firestore.
 * .run bypasses HTTP/App Check, not membership checks or Admin transactions.
 * The companion convoy-followme suite covers HTTP Auth/Functions transport.
 */
import { localProductAccount } from './helpers/local-product-account';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestContext,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import { getApps, initializeApp } from 'firebase-admin/app';
import { FieldValue } from 'firebase-admin/firestore';
import {
  deleteField,
  doc,
  getDocFromServer,
  runTransaction,
  serverTimestamp,
  updateDoc,
} from 'firebase/firestore';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

let db: (typeof import('../firebase'))['db'];
let setFollowMe: (typeof import('../convoy/setFollowMe'))['setFollowMe'];
let env: RulesTestEnvironment;
const ids: string[] = [];

beforeAll(async () => {
  const host = process.env.FIRESTORE_EMULATOR_HOST;
  const projectId = process.env.GCLOUD_PROJECT ?? 'demo-test';
  if (!host || !/^(127\.0\.0\.1|localhost):\d+$/.test(host) || !projectId.startsWith('demo-')) {
    throw new Error('Follow Me race tests require a loopback Firestore emulator and demo project');
  }
  if (!getApps().some((app) => app.name === '[DEFAULT]')) {
    initializeApp({ projectId, databaseURL: `https://${projectId}-default-rtdb.firebaseio.com` });
  }
  ({ db } = await import('../firebase'));
  ({ setFollowMe } = await import('../convoy/setFollowMe'));
  const [hostname, port] = host.split(':');
  env = await initializeTestEnvironment({
    projectId,
    firestore: {
      host: hostname,
      port: Number(port),
      rules: readFileSync(resolve(__dirname, '../../../firebase/firestore.rules'), 'utf8'),
    },
  });
});

afterAll(async () => {
  vi.restoreAllMocks();
  await env?.cleanup();
  if (db) {
    await Promise.all(
      ids.flatMap((id) => [
        db.recursiveDelete(db.doc(`convoys/${id}`)),
        ...['a', 'b', 'c'].map((suffix) => db.doc(`users/${id}-${suffix}`).delete()),
      ]),
    );
  }
});

async function fixture() {
  const id = `followme-race-${randomUUID()}`;
  ids.push(id);
  const uids = ['a', 'b', 'c'].map((suffix) => `${id}-${suffix}`);
  const convoy = db.doc(`convoys/${id}`);
  const trail = convoy.collection('followMe').doc('current');
  await Promise.all(
    uids.map((uid) =>
      db.doc(`users/${uid}`).set({
        suspended: false,
        deleted: false,
        activeMember: false,
      }),
    ),
  );
  await Promise.all(uids.map((uid) => localProductAccount(uid)));
  await convoy.set({
    status: 'active',
    ownerUid: uids[0],
    memberUids: uids,
    members: Object.fromEntries(
      uids.map((uid, i) => [
        uid,
        {
          uid,
          inviteStatus: 'accepted',
          role: i === 0 ? 'owner' : 'member',
        },
      ]),
    ),
  });
  const toggle = (uid: string, active: boolean) =>
    setFollowMe.run({ auth: { uid, token: {} }, data: { convoyId: id, active } } as never);
  const client = (uid: string) => env.authenticatedContext(uid).firestore();
  return { id, uids, convoy, trail, toggle, client };
}

/** Mirrors the client protocol: check the captured generation in the SAME
 * transaction as the partial update. Legacy direct updates deliberately remain
 * compatible; the rules cannot infer their previously captured generation. */
function writeCapturedTrail(
  client: ReturnType<RulesTestContext['firestore']>,
  id: string,
  uid: string,
  activationId: unknown,
) {
  return runTransaction(client, async (tx) => {
    const ref = doc(client, `convoys/${id}/followMe/current`);
    const snap = await tx.get(ref);
    if (
      !snap.exists() ||
      snap.data().leaderUid !== uid ||
      (snap.data().activationId ?? null) !== activationId
    )
      return false;
    tx.update(ref, { polyline: 'CAPTURED', updatedAt: serverTimestamp() });
    return true;
  });
}

describe('Follow Me real-handler lifecycle races', () => {
  it.each(['removed', 'ended', 'deleted'] as const)(
    'rechecks the parent when %s and cleanup commit before the leadership transaction',
    async (transition) => {
      const f = await fixture();
      const uid = f.uids[0]!;
      // This boundary reproduced the original bug: membership used to be read
      // BEFORE runTransaction. Only the scheduler boundary is injected; the
      // handler, parent writes, cleanup and leadership transaction are real.
      const original = db.runTransaction.bind(db);
      const spy = vi
        .spyOn(db, 'runTransaction')
        .mockImplementationOnce((body, options) => original(body, options)) // Product identity initialization.
        .mockImplementationOnce(async (body, options) => {
          if (transition === 'deleted') await f.convoy.delete();
          else if (transition === 'ended') await f.convoy.update({ status: 'ended' });
          else
            await f.convoy.update({
              memberUids: f.uids.slice(1),
              [`members.${uid}`]: FieldValue.delete(),
            });
          await f.trail.delete();
          return original(body, options);
        });
      try {
        await expect(f.toggle(uid, true)).rejects.toMatchObject({
          code: transition === 'ended' ? 'failed-precondition' : 'not-found',
        });
        expect(spy).toHaveBeenCalledTimes(2);
        expect((await f.trail.get()).exists).toBe(false);
      } finally {
        spy.mockRestore();
      }
    },
  );

  it.each(['forming', 'ended'])('rejects a %s convoy', async (status) => {
    const f = await fixture();
    await f.convoy.update({ status });
    await expect(f.toggle(f.uids[0]!, true)).rejects.toMatchObject({ code: 'failed-precondition' });
    expect((await f.trail.get()).exists).toBe(false);
  });

  it('serializes simultaneous participant takeovers and preserves the winner on old-member off', async () => {
    const f = await fixture();
    await Promise.all(f.uids.map((uid) => f.toggle(uid, true)));
    const winner = (await f.trail.get()).data()!;
    expect(f.uids).toContain(winner.leaderUid);
    expect(winner.activationId).toEqual(expect.any(String));
    expect((await f.trail.parent.get()).size).toBe(1);
    const oldUid = f.uids.find((uid) => uid !== winner.leaderUid)!;
    await f.toggle(oldUid, false);
    expect((await f.trail.get()).data()).toEqual(winner);
    await assertFails(
      updateDoc(doc(f.client(oldUid), f.trail.path), {
        polyline: 'OLD',
        updatedAt: serverTimestamp(),
      }),
    );
  });
});

describe('Follow Me activation generations and rules', () => {
  it('keeps the id, points and timestamp on an idempotent activation; rotates on takeover and off/on', async () => {
    const f = await fixture();
    const [a, b] = f.uids as [string, string, string];
    expect(await f.toggle(a, true)).toEqual({ leading: true, leaderUid: a });
    await f.trail.update({ polyline: 'SEED' });
    const first = (await f.trail.get()).data()!;
    await f.toggle(a, true);
    expect((await f.trail.get()).data()).toEqual(first);
    await f.toggle(b, true);
    const takeover = (await f.trail.get()).data()!;
    expect(takeover.activationId).toEqual(expect.any(String));
    expect(takeover.activationId).not.toBe(first.activationId);
    expect(takeover.polyline).toBe('');
    await f.toggle(b, false);
    expect((await f.trail.get()).exists).toBe(false);
    await f.toggle(b, true);
    expect((await f.trail.get()).data()!.activationId).not.toBe(takeover.activationId);
  });

  it('rejects a captured same-UID generation after off/on, but accepts the new generation', async () => {
    const f = await fixture();
    const uid = f.uids[0]!;
    const client = f.client(uid);
    await f.toggle(uid, true);
    const oldId = (await f.trail.get()).data()!.activationId;
    await f.toggle(uid, false);
    expect(await writeCapturedTrail(client, f.id, uid, oldId)).toBe(false);
    await f.toggle(uid, true);
    const fresh = (await f.trail.get()).data()!;
    expect(await writeCapturedTrail(client, f.id, uid, oldId)).toBe(false);
    expect((await f.trail.get()).data()!.polyline).toBe('');
    expect(await writeCapturedTrail(client, f.id, uid, fresh.activationId)).toBe(true);
    expect((await f.trail.get()).data()!.polyline).toBe('CAPTURED');
  });

  it('rechecks a captured generation when the client transaction retries after takeover and reclaim', async () => {
    const f = await fixture();
    const [a, b] = f.uids as [string, string, string];
    await f.toggle(a, true);
    const capturedId = (await f.trail.get()).data()!.activationId;
    const client = f.client(a);
    let attempts = 0;
    const result = await runTransaction(client, async (tx) => {
      const ref = doc(client, f.trail.path);
      const snap = await tx.get(ref);
      attempts += 1;
      if (snap.data()?.activationId !== capturedId) return false;
      if (attempts === 1) {
        await f.toggle(b, true);
        await f.toggle(a, true);
      }
      tx.update(ref, { polyline: 'STALE-IN-FLIGHT', updatedAt: serverTimestamp() });
      return true;
    });
    expect(attempts).toBeGreaterThan(1);
    expect(result).toBe(false);
    expect((await f.trail.get()).data()!.polyline).toBe('');
  });

  it('allows legacy partial writes, but denies altering or removing an existing activation id', async () => {
    const f = await fixture();
    const uid = f.uids[0]!;
    await f.toggle(uid, true);
    const ref = doc(f.client(uid), f.trail.path);
    await assertSucceeds(updateDoc(ref, { polyline: 'LEGACY', updatedAt: serverTimestamp() }));
    const activationId = (await f.trail.get()).data()!.activationId;
    for (const replacement of ['OTHER', null, deleteField()]) {
      await assertFails(
        updateDoc(ref, { activationId: replacement, updatedAt: serverTimestamp() }),
      );
    }
    expect((await f.trail.get()).data()!.activationId).toBe(activationId);
    // Compatibility is intentional: an old writer does not carry the generation
    // it captured, so it cannot be made generation-safe by these rules alone.
    await f.toggle(uid, false);
    await f.toggle(uid, true);
    await assertSucceeds(
      updateDoc(ref, { polyline: 'LEGACY-AFTER-RESTART', updatedAt: serverTimestamp() }),
    );
  });

  it('supports legacy docs without minting an id on noop; a new activation upgrades them', async () => {
    const f = await fixture();
    const uid = f.uids[0]!;
    await f.trail.set({
      leaderUid: uid,
      polyline: 'LEGACY',
      updatedAt: FieldValue.serverTimestamp(),
    });
    await f.toggle(uid, true);
    expect((await f.trail.get()).data()!.activationId).toBeUndefined();
    const ref = doc(f.client(uid), f.trail.path);
    await assertSucceeds(
      updateDoc(ref, { polyline: 'STILL-LEGACY', updatedAt: serverTimestamp() }),
    );
    await assertFails(updateDoc(ref, { activationId: 'FORGED', updatedAt: serverTimestamp() }));
    await f.toggle(uid, false);
    await f.toggle(uid, true);
    expect((await f.trail.get()).data()!.activationId).toEqual(expect.any(String));
  });

  it.each(['forming', 'ended'])(
    'denies direct writes on a retained trail in a %s convoy',
    async (status) => {
      const f = await fixture();
      const uid = f.uids[0]!;
      await f.toggle(uid, true);
      await f.convoy.update({ status });
      await assertFails(
        updateDoc(doc(f.client(uid), f.trail.path), {
          polyline: 'AFTER-END',
          updatedAt: serverTimestamp(),
        }),
      );
    },
  );

  it('allows every accepted participant, including the author, to read the trail and its deletion', async () => {
    const f = await fixture();
    await f.toggle(f.uids[0]!, true);
    for (const uid of f.uids) {
      expect((await getDocFromServer(doc(f.client(uid), f.trail.path))).exists()).toBe(true);
    }
    await f.toggle(f.uids[0]!, false);
    for (const uid of f.uids) {
      expect((await getDocFromServer(doc(f.client(uid), f.trail.path))).exists()).toBe(false);
    }
  });
});
