/** Business-image rules under the existing preparation policy; fixed demo emulators only. */
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import { deleteDoc, doc, setDoc } from 'firebase/firestore';
import { deleteObject, getBytes, ref, uploadBytes } from 'firebase/storage';
import { legacyAccessMigration, endLegacyAccessMigration } from './helpers/legacy-access-migration';

const firebaseDir = resolve(__dirname, '../../../firebase');
const prefix = `partner-image-${randomUUID()}`;
const imagePrefixes = ['companyImages', 'billboardImages', 'offerImages'] as const;
const ownedUsers: string[] = [];
const objects: string[] = [];
let env: RulesTestEnvironment;

beforeAll(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-test',
    firestore: {
      host: '127.0.0.1',
      port: 8080,
      rules: readFileSync(resolve(firebaseDir, 'firestore.rules'), 'utf8'),
    },
    storage: {
      host: '127.0.0.1',
      port: 9199,
      rules: readFileSync(resolve(firebaseDir, 'storage.partner-images-preparing.rules'), 'utf8'),
    },
  });
  await legacyAccessMigration(env);
});

afterAll(async () => {
  if (!env) return;
  await env.withSecurityRulesDisabled(async (context) => {
    for (const uid of ownedUsers) {
      for (const collection of ['users', 'carCommunityAccess', 'driverUsers'])
        await deleteDoc(doc(context.firestore(), collection, uid));
    }
    for (const path of objects) {
      try {
        await deleteObject(ref(context.storage(), path));
      } catch {
        /* May already be deleted. */
      }
    }
  });
  await endLegacyAccessMigration(env);
  await env.cleanup();
  // The next sequential suite must receive the canonical rules, not this staged release.
  const canonical = await initializeTestEnvironment({
    projectId: 'demo-test',
    storage: {
      host: '127.0.0.1',
      port: 9199,
      rules: readFileSync(resolve(firebaseDir, 'storage.rules'), 'utf8'),
    },
  });
  await canonical.cleanup();
});

async function account(
  profile: Record<string, unknown>,
  claims: { admin: boolean; suspended?: boolean } = { admin: true },
) {
  const uid = `${prefix}-${randomUUID()}`;
  ownedUsers.push(uid);
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), 'users', uid), {
      role: 'admin',
      suspended: false,
      deleted: false,
      ...profile,
    });
  });
  return { uid, context: env.authenticatedContext(uid, claims) };
}

function pathFor(kind: string) {
  const path = `${kind}/${prefix}/${randomUUID()}.png`;
  objects.push(path);
  return path;
}

const bytes = new Uint8Array([1, 2, 3]);
const png = { contentType: 'image/png' };

describe('preparation-compatible business image authority', () => {
  for (const kind of imagePrefixes) {
    it.each(['admin', 'owner'])(
      'current %s can create, replace and delete ' + kind,
      async (role) => {
        // No initialized projection: releasing image authority must not cut over global access.
        const actor = await account({ role });
        const object = ref(actor.context.storage(), pathFor(kind));
        await assertSucceeds(uploadBytes(object, bytes, png));
        await assertSucceeds(uploadBytes(object, new Uint8Array([4, 5]), png));
        expect(new Uint8Array(await getBytes(object))).toEqual(new Uint8Array([4, 5]));
        await assertSucceeds(deleteObject(object));
      },
    );

    it.each(['demoted', 'suspended', 'deleted', 'missing'])(
      'same retained token cannot change ' + kind + ' after %s profile change',
      async (state) => {
        const actor = await account({});
        const object = ref(actor.context.storage(), pathFor(kind));
        await assertSucceeds(uploadBytes(object, bytes, png));
        await env.withSecurityRulesDisabled(async (context) => {
          const profile = doc(context.firestore(), 'users', actor.uid);
          if (state === 'missing') await deleteDoc(profile);
          else
            await setDoc(profile, {
              role: state === 'demoted' ? 'member' : 'admin',
              suspended: state === 'suspended',
              deleted: state === 'deleted',
            });
        });
        await assertFails(uploadBytes(object, new Uint8Array([9]), png));
        await assertFails(deleteObject(object));
        await assertFails(uploadBytes(ref(actor.context.storage(), pathFor(kind)), bytes, png));
      },
    );
  }

  it.each(['ordinary', 'partner-manager', 'redeemer', 'missing-claim', 'claim-suspended'])(
    'does not grant business-image writes to %s',
    async (kind) => {
      const actor = await account(
        { role: kind === 'missing-claim' || kind === 'claim-suspended' ? 'admin' : 'member' },
        kind === 'claim-suspended' ? { admin: true, suspended: true } : { admin: false },
      );
      for (const path of imagePrefixes)
        await assertFails(uploadBytes(ref(actor.context.storage(), pathFor(path)), bytes, png));
    },
  );

  it.each(['suspended', 'explicitBlacklistId', 'inheritedBlocked'])(
    'honors current product %s without requiring strict initialization',
    async (restriction) => {
      const actor = await account({});
      await env.withSecurityRulesDisabled(async (context) => {
        await setDoc(doc(context.firestore(), 'carCommunityAccess', actor.uid), {
          initialized: false,
          [restriction]: restriction === 'explicitBlacklistId' ? 'retained-case' : true,
        });
      });
      for (const path of imagePrefixes)
        await assertFails(uploadBytes(ref(actor.context.storage(), pathFor(path)), bytes, png));
    },
  );

  it('allows uninitialized unrestricted admin projections during preparation', async () => {
    const actor = await account({});
    await env.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), 'carCommunityAccess', actor.uid), {
        initialized: false,
        suspended: false,
        inheritedBlocked: false,
        explicitBlacklistId: null,
      });
    });
    for (const path of imagePrefixes)
      await assertSucceeds(uploadBytes(ref(actor.context.storage(), pathFor(path)), bytes, png));
  });

  it('preserves authenticated image reads and denies anonymous reads and writes', async () => {
    const admin = await account({});
    const reader = await account({ role: 'member' }, { admin: false });
    const anonymous = env.unauthenticatedContext();
    for (const kind of imagePrefixes) {
      const path = pathFor(kind);
      await assertSucceeds(uploadBytes(ref(admin.context.storage(), path), bytes, png));
      await assertSucceeds(getBytes(ref(reader.context.storage(), path)));
      await assertFails(getBytes(ref(anonymous.storage(), path)));
      await assertFails(uploadBytes(ref(anonymous.storage(), pathFor(kind)), bytes, png));
    }
  });

  it('keeps preparation-mode member route and profile operations without a product projection', async () => {
    const member = await account({ role: 'member' }, { admin: false });
    const routePath = `rideRoutes/${member.uid}/${prefix}/route.bin`;
    const avatarPath = `profileImages/${member.uid}/${prefix}.png`;
    objects.push(routePath, avatarPath);
    const route = ref(member.context.storage(), routePath);
    await assertSucceeds(uploadBytes(route, bytes, { contentType: 'application/octet-stream' }));
    await assertSucceeds(getBytes(route));
    await assertSucceeds(uploadBytes(ref(member.context.storage(), avatarPath), bytes, png));
    await assertSucceeds(deleteObject(ref(member.context.storage(), avatarPath)));
    const other = await account({ role: 'member' }, { admin: false });
    await assertFails(getBytes(ref(other.context.storage(), routePath)));
  });

  it('keeps Driver media authority independent of a CarCommunity projection', async () => {
    const driver = await account({ role: 'member' }, { admin: false });
    await env.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), 'driverUsers', driver.uid), {
        onboardingComplete: true,
        status: 'active',
      });
    });
    const path = `driverThreadMedia/${driver.uid}/${prefix}.jpg`;
    objects.push(path);
    const object = ref(driver.context.storage(), path);
    await assertSucceeds(uploadBytes(object, bytes, { contentType: 'image/jpeg' }));
    await assertSucceeds(getBytes(ref(env.unauthenticatedContext().storage(), path)));
    const unrelated = await account({ role: 'member' }, { admin: false });
    await assertFails(
      uploadBytes(ref(unrelated.context.storage(), path), bytes, { contentType: 'image/jpeg' }),
    );
    await assertSucceeds(deleteObject(object));
  });
});
