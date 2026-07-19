#!/usr/bin/env node
/**
 * One-off backfill: populate `users/{uid}.displayNameLower` for accounts
 * created BEFORE the case-insensitive friend search landed.
 *
 * WHY THIS IS REQUIRED: friend nickname resolution
 * (functions/src/friends/manageFriends.ts resolveTarget) queries
 * `displayNameLower` and NEVER `displayName`. Every write path now populates the
 * key in lockstep (auth/provisioning.ts buildUserProfileDocument and
 * auth/onboarding-core.ts computeOnboardingWrites), but existing documents have
 * no key at all — and a Firestore range query simply does not return documents
 * that are missing the field. Until this script has run, pre-existing members
 * are UNFINDABLE by nickname. Run it once, immediately after deploying the
 * functions.
 *
 * The key is derived with the same locale-invariant toSearchKey() rule the
 * backend uses (trim + String.prototype.toLowerCase()) — kept in sync by
 * importing it from the compiled functions build rather than re-implementing it.
 *
 * Idempotent: a document whose key already matches the derived value is skipped,
 * so re-running is safe and cheap. Documents without a string `displayName` are
 * left untouched (nothing to derive a key from).
 *
 * WHY THIS LIVES UNDER functions/: it imports `firebase-admin`, which is a
 * dependency of functions/ only (functions/ is deliberately NOT a root npm
 * workspace, so the repo root has no node_modules providing it). Node resolves
 * bare specifiers by walking up from the SCRIPT'S OWN directory, not the shell's
 * cwd — so a copy of this script under the repo-root scripts/ cannot resolve
 * firebase-admin no matter which directory you invoke it from.
 *
 * Usage — every command below runs from functions/, starting at the repo root:
 *   cd functions
 *   npm ci                                  # provides firebase-admin
 *   npx tsc                                 # builds lib/, so the import below resolves
 *   GOOGLE_APPLICATION_CREDENTIALS=/path/to/sa.json \
 *     node scripts/backfill-display-name-lower.mjs --project <projectId> [--apply]
 *
 * The last line is equivalently `npm run backfill:display-name-lower -- --project <projectId>`
 * (the npm script only wraps the `node` invocation; `npm ci` and `npx tsc` are
 * still required first).
 *
 * Defaults to a DRY RUN that only reports what it would change; pass --apply to
 * write. Batched at 400 writes (under Firestore's 500-op limit).
 */

import { initializeApp, applicationDefault } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { toSearchKey } from '../lib/friends/friends-core.js';

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
  let skippedCurrent = 0;
  let skippedNoName = 0;

  let batch = db.batch();
  let pending = 0;

  // Stream the collection so memory stays flat regardless of user count.
  for await (const doc of db.collection('users').stream()) {
    scanned += 1;
    const data = doc.data();
    const displayName = data?.displayName;

    if (typeof displayName !== 'string' || displayName.trim().length === 0) {
      skippedNoName += 1;
      continue;
    }

    const key = toSearchKey(displayName);
    if (data?.displayNameLower === key) {
      skippedCurrent += 1;
      continue;
    }

    updated += 1;
    if (apply) {
      batch.set(doc.ref, { displayNameLower: key }, { merge: true });
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
    `[backfill] scanned=${scanned} ${apply ? 'updated' : 'would update'}=${updated} ` +
      `already-current=${skippedCurrent} no-displayName=${skippedNoName}`,
  );
  if (!apply && updated > 0) {
    console.log('[backfill] DRY RUN — re-run with --apply to write.');
  }
}

main().catch((error) => {
  console.error('[backfill] failed:', error);
  process.exit(1);
});
