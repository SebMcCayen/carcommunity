/** Private Partner reads must reject retained admin claims after revocation. */
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  collection,
  deleteDoc,
  doc,
  documentId,
  getDocFromServer,
  getDocsFromServer,
  limit,
  orderBy,
  query,
  setDoc,
  Timestamp,
  updateDoc,
  where,
} from 'firebase/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const prefix = `partner-admin-read-${randomUUID()}`;
const collections = ['partnerApplications', 'partnerBillboardRequests'] as const;
const ownedUsers: string[] = [];
let env: RulesTestEnvironment;

type Client = ReturnType<RulesTestEnvironment['authenticatedContext']>;
type Policy = 'preparing' | 'enforced' | 'missing';

async function policy(mode: Policy) {
  await env.withSecurityRulesDisabled(async (context) => {
    const ref = doc(context.firestore(), 'carCommunityPolicy', 'access');
    if (mode === 'missing') await deleteDoc(ref);
    else await setDoc(ref, { enforceInitialized: mode === 'enforced' });
  });
}

async function account(
  profile: Record<string, unknown> = {},
  claims: Record<string, unknown> = { admin: true },
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

async function readBoth(context: Client, name: (typeof collections)[number], allowed: boolean) {
  const firestore = context.firestore();
  const single = getDocFromServer(doc(firestore, name, prefix));
  // Same server-filtered, ordered shape as the application review queue.
  const list = getDocsFromServer(
    query(
      collection(firestore, name),
      where('status', '==', 'submitted'),
      orderBy('submittedAt', 'desc'),
      orderBy(documentId(), 'desc'),
      limit(21),
    ),
  );
  if (allowed) {
    const [document, page] = await Promise.all([assertSucceeds(single), assertSucceeds(list)]);
    expect(document.exists()).toBe(true);
    expect(page.docs.some((item) => item.id === prefix)).toBe(true);
  } else {
    await Promise.all([assertFails(single), assertFails(list)]);
  }
}

beforeAll(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-test',
    firestore: {
      host: '127.0.0.1',
      port: 8080,
      rules: readFileSync(resolve(__dirname, '../../../firebase/firestore.rules'), 'utf8'),
    },
  });
  await env.withSecurityRulesDisabled(async (context) => {
    for (const name of collections)
      await setDoc(doc(context.firestore(), name, prefix), {
        status: 'submitted',
        submittedAt: Timestamp.now(),
        contactEmail: 'private@example.com',
        submittedByUserId: `${prefix}-submitter`,
      });
  });
});

afterAll(async () => {
  if (!env) return;
  await env.withSecurityRulesDisabled(async (context) => {
    const firestore = context.firestore();
    for (const uid of ownedUsers) {
      await deleteDoc(doc(firestore, 'users', uid));
      await deleteDoc(doc(firestore, 'carCommunityAccess', uid));
    }
    for (const name of collections) await deleteDoc(doc(firestore, name, prefix));
    await deleteDoc(doc(firestore, 'carCommunityPolicy', 'access'));
  });
  await env.cleanup();
});

for (const mode of ['preparing', 'enforced', 'missing'] as const) {
  describe(`private Partner reads (${mode} access policy)`, () => {
    beforeAll(() => policy(mode));

    async function actor(
      profile: Record<string, unknown> = {},
      claims: Record<string, unknown> = { admin: true },
    ) {
      const current = await account(profile, claims);
      // Preparation deliberately has no projection; no global cutover required.
      if (mode !== 'preparing')
        await env.withSecurityRulesDisabled(async (context) => {
          await setDoc(doc(context.firestore(), 'carCommunityAccess', current.uid), {
            initialized: true,
            suspended: false,
            inheritedBlocked: false,
            explicitBlacklistId: null,
          });
        });
      return current;
    }

    it.each(['admin', 'owner'])('allows a current %s to get and list', async (role) => {
      const current = await actor({ role });
      for (const name of collections) await readBoth(current.context, name, true);
    });

    it.each([
      'demoted',
      'missing-role',
      'malformed-role',
      'suspended',
      'deleted',
      'missing-profile',
    ])('denies the same retained token after %s', async (state) => {
      const current = await actor();
      for (const name of collections) await readBoth(current.context, name, true);
      await env.withSecurityRulesDisabled(async (context) => {
        const ref = doc(context.firestore(), 'users', current.uid);
        if (state === 'missing-profile') await deleteDoc(ref);
        else {
          const profile: Record<string, unknown> = {
            role: 'admin',
            suspended: state === 'suspended',
            deleted: state === 'deleted',
          };
          if (state === 'demoted') profile.role = 'user';
          if (state === 'missing-role') delete profile.role;
          if (state === 'malformed-role') profile.role = ['admin'];
          await setDoc(ref, profile);
        }
      });
      // Do not recreate the context or refresh its token. Force server reads
      // so previously cached application data cannot hide the rules decision.
      for (const name of collections) await readBoth(current.context, name, false);
    });

    it.each(['missing', 'false', 'suspended'])(
      'denies current admin profiles with %s token authority',
      async (state) => {
        const claims =
          state === 'missing' ? {} : { admin: state !== 'false', suspended: state === 'suspended' };
        const current = await actor({}, claims);
        for (const name of collections) await readBoth(current.context, name, false);
      },
    );

    it('denies ordinary users, the submitter and anonymous clients', async () => {
      const member = await actor({ role: 'user' }, {});
      const submitter = env.authenticatedContext(`${prefix}-submitter`);
      const anonymous = env.unauthenticatedContext();
      for (const name of collections)
        for (const client of [member.context, submitter, anonymous])
          await readBoth(client, name, false);
    });

    it('keeps all client application and billboard-request writes backend-only', async () => {
      const current = await actor();
      for (const name of collections) {
        const existing = doc(current.context.firestore(), name, prefix);
        await assertFails(setDoc(doc(current.context.firestore(), name, `${prefix}-new`), {}));
        await assertFails(updateDoc(existing, { status: 'approved' }));
        await assertFails(deleteDoc(existing));
      }
    });

    if (mode !== 'preparing') {
      it.each(['missing', 'uninitialized', 'suspended', 'blacklisted', 'inherited', 'identity'])(
        'retains strict product access rejection for %s projections',
        async (state) => {
          const current = await actor();
          const identityKey = `${current.uid}-identity`;
          await env.withSecurityRulesDisabled(async (context) => {
            const firestore = context.firestore();
            const ref = doc(firestore, 'carCommunityAccess', current.uid);
            if (state === 'missing') await deleteDoc(ref);
            else
              await setDoc(ref, {
                initialized: state !== 'uninitialized',
                suspended: state === 'suspended',
                inheritedBlocked: state === 'inherited',
                explicitBlacklistId: state === 'blacklisted' ? 'existing-case' : null,
                googleKey: state === 'identity' ? identityKey : null,
              });
            if (state === 'identity')
              await setDoc(doc(firestore, 'carCommunityIdentityBlocks', identityKey), {
                blocked: true,
              });
          });
          try {
            for (const name of collections) await readBoth(current.context, name, false);
          } finally {
            if (state === 'identity')
              await env.withSecurityRulesDisabled(async (context) => {
                await deleteDoc(
                  doc(context.firestore(), 'carCommunityIdentityBlocks', identityKey),
                );
              });
          }
        },
      );
    }
  });
}
