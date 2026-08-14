/**
 * crownHunt.seedPerkCatalog — admin callable (contracts/functions/functions.json).
 *
 * Writes the member-readable DISPLAY MIRROR `config/perkCatalog` from the
 * authoritative constants in perks-core.ts. The mirror carries only what the
 * client needs to render the shop (perkId, kind, name, icon key, cost, blurb);
 * the authoritative costs and effect parameters stay server-side, and the buy
 * path (crownHunt.buyPerk) NEVER trusts a price read from this document.
 *
 * Idempotent by construction — it overwrites the doc with the current
 * constants — so an operator re-runs it after any catalog change (a new perk, a
 * price update) to refresh the mirror. Admin-gated (requireAdminActor), App
 * Check enforced, audited to adminAuditEvents. Deployed via the `crownHunt`
 * export group as `crownHunt-seedPerkCatalog`.
 */

import { onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';
import { buildPerkCatalogDoc, PERK_CATALOG_DOC_VERSION } from './perks-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface SeedPerkCatalogResponse {
  version: number;
  perkCount: number;
}

export const seedPerkCatalog = onCall(
  CALLABLE_OPTS,
  async (request): Promise<SeedPerkCatalogResponse> => {
    const actor = await requireAdminActor(request);

    const doc = buildPerkCatalogDoc();
    const serverTimestamp = () => FieldValue.serverTimestamp();

    const batch = db.batch();
    batch.set(db.collection('config').doc('perkCatalog'), {
      ...doc,
      updatedAt: serverTimestamp(),
    });
    batch.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'crownHunt.seedPerkCatalog',
          targetType: 'config',
          targetId: 'perkCatalog',
          reason: 'Seeded the Kronjakt shop display catalog from server constants.',
          details: { version: doc.version, perkCount: doc.perks.length },
        },
        serverTimestamp,
      ),
    );
    await batch.commit();

    return { version: PERK_CATALOG_DOC_VERSION, perkCount: doc.perks.length };
  },
);
