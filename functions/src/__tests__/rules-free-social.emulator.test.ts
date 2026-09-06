/**
 * Permanent free access under a simulated legacy re-lock. The substitution is
 * applied only to rules loaded into the emulator, never to repository/live
 * settings. Includes stale-token suspension/deletion and private-data denials.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  deleteDoc,
  doc,
  getDoc,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
} from 'firebase/firestore';
import { getBytes, ref, uploadBytes } from 'firebase/storage';
import { afterAll, beforeAll, describe, it } from 'vitest';

let env: RulesTestEnvironment;
const subjects = ['free', 'suspended', 'deleted', 'claim-suspended'] as const;
const uid = (subject: string) => `free-social-rules-${subject}`;
const root = resolve(__dirname, '../../../firebase');
const relock = (file: string) =>
  readFileSync(resolve(root, file), 'utf8').replace(
    /function isActiveMember\(\) \{[\s\S]*?\n    \}/,
    `function isActiveMember() {
      return isAuthenticated() && isNotSuspended()
        && request.auth.token.activeMember == true;
    }`,
  );

function readablePaths(subject: string): string[] {
  const id = uid(subject);
  return [
    `users/${id}/friends/peer`,
    `friendRequests/${id}`,
    `conversations/${id}`,
    `conversations/${id}/messages/m1`,
    `convoys/${id}`,
    `convoys/${id}/followMe/current`,
    `convoyChats/${id}/messages/m1`,
    `convoyChats/${id}/reactions/r1`,
    'communityChat/global',
    'communityChat/global/messages/free-social',
    `events/${id}/groupDriveParticipants/${id}`,
    `incidents/${id}`,
    `policeReports/${id}`,
  ];
}

beforeAll(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-test',
    firestore: { host: 'localhost', port: 8080, rules: relock('firestore.rules') },
    storage: { host: 'localhost', port: 9199, rules: relock('storage.rules') },
  });
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, 'communityChat/global'), {});
    await setDoc(doc(db, 'communityChat/global/messages/free-social'), { text: 'free social' });
    for (const subject of subjects) {
      const id = uid(subject);
      await setDoc(doc(db, `users/${id}`), {
        activeMember: false,
        suspended: subject === 'suspended',
        deleted: subject === 'deleted',
      });
      await setDoc(doc(db, `users/${id}/friends/peer`), { uid: 'peer' });
      await setDoc(doc(db, `friendRequests/${id}`), { fromUid: id, toUid: 'peer' });
      await setDoc(doc(db, `conversations/${id}`), { members: [id, 'peer'] });
      await setDoc(doc(db, `conversations/${id}/messages/m1`), { text: 'private' });
      await setDoc(doc(db, `convoys/${id}`), {
        ownerUid: id,
        memberUids: [id],
        status: 'active',
        members: { [id]: { role: 'owner', inviteStatus: 'accepted' } },
      });
      await setDoc(doc(db, `convoys/${id}/followMe/current`), {
        leaderUid: id,
        polyline: '',
        updatedAt: Timestamp.now(),
      });
      await setDoc(doc(db, `convoyChats/${id}/messages/m1`), { text: 'convoy private' });
      await setDoc(doc(db, `convoyChats/${id}/reactions/r1`), { type: 'hello' });
      await setDoc(doc(db, `events/${id}`), { status: 'published' });
      await setDoc(doc(db, `events/${id}/groupDriveParticipants/${id}`), { status: 'joined' });
      for (const collection of ['incidents', 'policeReports']) {
        await setDoc(doc(db, `${collection}/${id}`), {
          status: 'active',
          source: 'user',
          expiresAt: Timestamp.fromMillis(Date.now() + 3600000),
        });
      }
    }
  });
});

afterAll(async () => {
  // Other suites use the same emulator project. Restore the real rules even
  // when this suite fails, so test-only re-locking cannot spill into them.
  if (env) {
    await env.cleanup();
    const restored = await initializeTestEnvironment({
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
    await restored.cleanup();
  }
});

describe('free social rules independent of legacy membership', () => {
  it('allows a free actor every owned read surface with legacy gates on', async () => {
    const db = env.authenticatedContext(uid('free'), { activeMember: false }).firestore();
    for (const path of readablePaths('free')) await assertSucceeds(getDoc(doc(db, path)));
  });

  it.each(['suspended', 'deleted', 'claim-suspended'])(
    'denies %s reads and trail updates',
    async (subject) => {
      const db = env
        .authenticatedContext(uid(subject), {
          activeMember: true,
          suspended: subject === 'claim-suspended',
        })
        .firestore();
      for (const path of readablePaths(subject)) await assertFails(getDoc(doc(db, path)));
      await assertFails(
        updateDoc(doc(db, `convoys/${uid(subject)}/followMe/current`), {
          polyline: 'updated',
          updatedAt: serverTimestamp(),
        }),
      );
    },
  );

  it('denies anonymous reads and nonparticipant private reads', async () => {
    for (const path of readablePaths('free')) {
      await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), path)));
    }
    const db = env
      .authenticatedContext('free-social-outsider', { activeMember: false })
      .firestore();
    for (const path of readablePaths('free').slice(0, 8)) await assertFails(getDoc(doc(db, path)));
  });

  it('preserves DM blocking in either direction', async () => {
    const id = uid('free');
    const db = env.authenticatedContext(id, { activeMember: false }).firestore();
    for (const [from, to] of [
      [id, 'peer'],
      ['peer', id],
    ]) {
      await env.withSecurityRulesDisabled(async (context) => {
        await setDoc(doc(context.firestore(), `userBlocks/${from}/blocked/${to}`), {});
      });
      await assertFails(getDoc(doc(db, `conversations/${id}/messages/m1`)));
      await env.withSecurityRulesDisabled(async (context) => {
        await deleteDoc(doc(context.firestore(), `userBlocks/${from}/blocked/${to}`));
      });
    }
  });

  it('allows only the accepted leader to update the trail and denies forged social writes', async () => {
    const id = uid('free');
    const db = env.authenticatedContext(id, { activeMember: false }).firestore();
    await assertSucceeds(
      updateDoc(doc(db, `convoys/${id}/followMe/current`), {
        polyline: 'updated',
        updatedAt: serverTimestamp(),
      }),
    );
    await assertFails(updateDoc(doc(db, `convoys/${id}/followMe/current`), { leaderUid: 'peer' }));
    for (const path of readablePaths('free').filter((path) => !path.includes('/followMe/'))) {
      await assertFails(setDoc(doc(db, path), { forged: true }));
    }
  });

  it('allows free route uploads only to the actor own prefix', async () => {
    const storage = env.authenticatedContext(uid('free'), { activeMember: false }).storage();
    const route = ref(storage, `rideRoutes/${uid('free')}/saved/route.bin`);
    await assertSucceeds(
      uploadBytes(route, new Uint8Array([1, 2]), { contentType: 'application/octet-stream' }),
    );
    // Saving is free even under the simulated re-lock; the existing replay
    // subscription switch is deliberately preserved by the account-state fix.
    await assertFails(getBytes(route));
    await assertFails(
      uploadBytes(ref(storage, 'rideRoutes/peer/saved/route.bin'), new Uint8Array([1]), {
        contentType: 'application/octet-stream',
      }),
    );
  });

  it.each([false, true])('denies uploads after profile removal (admin=%s)', async (admin) => {
    const id = uid(`removed-${admin}`);
    const storage = env.authenticatedContext(id, { admin, activeMember: true }).storage();
    const upload = () =>
      uploadBytes(ref(storage, `rideRoutes/${id}/saved/route.bin`), new Uint8Array([1]), {
        contentType: 'application/octet-stream',
      });
    // Unprovisioned and purged profiles both fail closed, including the admin
    // write alternative; a normal provisioned free account still succeeds.
    await assertFails(upload());
    await env.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), `users/${id}`), {
        activeMember: false,
        suspended: false,
        deleted: false,
      });
    });
    await assertSucceeds(upload());
    await env.withSecurityRulesDisabled(async (context) => {
      await deleteDoc(doc(context.firestore(), `users/${id}`));
    });
    await assertFails(upload());
  });

  it.each(['suspended', 'deleted', 'claim-suspended'])(
    'denies %s uploads, including an admin token',
    async (subject) => {
      const storage = env
        .authenticatedContext(uid(subject), {
          admin: true,
          activeMember: true,
          suspended: subject === 'claim-suspended',
        })
        .storage();
      await assertFails(
        uploadBytes(
          ref(storage, `rideRoutes/${uid(subject)}/saved/route.bin`),
          new Uint8Array([1]),
          {
            contentType: 'application/octet-stream',
          },
        ),
      );
    },
  );
});
