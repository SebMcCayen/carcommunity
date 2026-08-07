#!/usr/bin/env node
/**
 * One-off backfill: flip existing `userPrivate/{uid}.anonymousPartnerStatsOptIn`
 * from an explicit `false` to `true`, so existing members contribute anonymised
 * partner statistics by default (matching the new default-on / opt-out model).
 *
 * WHY THIS IS REQUIRED: partner statistics moved from opt-IN to default-ON.
 * Provisioning now seeds `true` for NEW users, and the consent gate in
 * `recordInteraction` excludes a member only on an explicit `false`. But every
 * EXISTING user was provisioned under the old opt-in default, which wrote an
 * explicit `false` — so without this backfill they would stay opted OUT forever.
 * This flips those old-default `false` values to `true`.
 *
 * SCOPE: only documents whose field is exactly `false` are touched. Documents
 * where the field is MISSING already read as default-on under the new gate and
 * are left alone; documents already `true` are left alone. Note that an explicit
 * `false` cannot be distinguished from a deliberate opt-out made under the old
 * model — this backfill intentionally re-enables both (the product decision:
 * "on by default for everyone"; members can opt out again in Settings).
 *
 * Run it ONCE, immediately after deploying the functions that ship the
 * default-on gate.
 *
 * Idempotent: re-running is a no-op once every `false` has become `true` (the
 * query returns nothing). It only flips false -> true; it never turns anyone off.
 *
 * WHY THIS LIVES UNDER functions/: it imports `firebase-admin`, a dependency of
 * functions/ only (functions/ is deliberately NOT a root npm workspace). Node
 * resolves bare specifiers by walking up from the SCRIPT'S OWN directory.
 *
 * Usage — every command below runs from functions/, starting at the repo root:
 *   cd functions
 *   npm ci                                  # provides firebase-admin
 *   GOOGLE_APPLICATION_CREDENTIALS=/path/to/sa.json \
 *     node scripts/backfill-partner-stats-optin.mjs --project <projectId> [--apply]
 *
 * (Or `gcloud auth application-default login` instead of the SA key.)
 * Defaults to a DRY RUN that only reports what it would change; pass --apply to
 * write. Batched at 400 writes (under Firestore's 500-op limit).
 */

import { initializeApp, applicationDefault } from 'firebase-admin/app';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';

const args = process.argv.slice(2);
const apply = args.includes('--apply');
const projectIdx = args.indexOf('--project');
const projectId = projectIdx >= 0 ? args[projectIdx + 1] : process.env.GCLOUD_PROJECT;

if (!projectId) {
  console.error('Missing --project <projectId> (or GCLOUD_PROJECT).');
  process.exit(1);
}

initializeApp({ credential: applicationDefault(), projectId });
const db = getFirestore();

const BATCH_SIZE = 400;

async function main() {
  console.log(`[backfill] project=${projectId} mode=${apply ? 'APPLY' : 'DRY RUN'}`);

  let scanned = 0;
  let updated = 0;

  let batch = db.batch();
  let pending = 0;

  // Only docs whose field is EXACTLY false. Missing-field docs already read as
  // default-on and must not be rewritten; already-true docs are skipped too.
  const stream = db
    .collection('userPrivate')
    .where('anonymousPartnerStatsOptIn', '==', false)
    .stream();

  for await (const doc of stream) {
    scanned += 1;
    updated += 1;
    if (apply) {
      batch.set(
        doc.ref,
        {
          anonymousPartnerStatsOptIn: true,
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true },
      );
      pending += 1;
      if (pending >= BATCH_SIZE) {
        await batch.commit();
        batch = db.batch();
        pending = 0;
      }
    }
  }

  if (apply && pending > 0) {
    await batch.commit();
  }

  console.log(
    `[backfill] explicit-false scanned=${scanned} ${apply ? 'flipped-to-true' : 'would-flip'}=${updated}`,
  );
  if (!apply && updated > 0) {
    console.log('[backfill] DRY RUN — re-run with --apply to write.');
  }
}

main().catch((error) => {
  console.error('[backfill] failed:', error);
  process.exit(1);
});
