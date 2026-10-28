import type { RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, setDoc, deleteDoc } from 'firebase/firestore';
import { ref, set, remove } from 'firebase/database';
/** Existing fake-identity rules suites exercise the explicit pre-cutover mode.
 * Strict product initialization/blacklisting is exercised separately against
 * real Auth sessions in admin-carcommunity-access.emulator.test.ts. */
export async function legacyAccessMigration(env: RulesTestEnvironment, rtdb = false) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), 'carCommunityPolicy', 'access'), {
      enforceInitialized: false,
      rolloutState: 'preparing',
    });
    if (rtdb) await set(ref(context.database(), 'carCommunityPolicy/enforceInitialized'), false);
  });
}
export async function endLegacyAccessMigration(env: RulesTestEnvironment, rtdb = false) {
  await env.withSecurityRulesDisabled(async (context) => {
    await deleteDoc(doc(context.firestore(), 'carCommunityPolicy', 'access'));
    if (rtdb) await remove(ref(context.database(), 'carCommunityPolicy/enforceInitialized'));
  });
}
