#!/usr/bin/env node
/**
 * One-off reconciliation for offers created before company lifecycle cascades.
 *
 * An active offer is valid only while its parent company is active. Historical
 * paused/ended companies may still have active offers because the old status
 * callable neither cascaded company transitions nor checked parents during
 * offer activation. Mobile clients intentionally use status-only offer queries,
 * so those stale offers must be repaired before rollout.
 *
 * The script scans active offers, then rechecks both offer and parent in a
 * transaction before writing. Offers under paused/draft/invalid companies are
 * paused; offers under ended or missing companies are ended. Offers whose parent
 * is active are untouched. This makes the script idempotent and safe to rerun,
 * including while an administrator changes lifecycle state concurrently.
 *
 * Usage from functions/:
 *   npm ci
 *   GOOGLE_APPLICATION_CREDENTIALS=/path/to/sa.json \
 *     node scripts/backfill-partner-offer-status.mjs --project <projectId> [--apply]
 *
 * Defaults to a dry run. After --apply, run the dry run again and require
 * would-update=0 before relying on status-only offer queries.
 */

import { applicationDefault, initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore } from 'firebase-admin/firestore';

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

function reconciledOfferStatus(company) {
  if (!company.exists || company.data()?.status === 'ended') return 'ended';
  if (company.data()?.status === 'active') return null;
  return 'paused';
}

async function inspectOffer(offer) {
  const companyId = offer.data().companyId;
  if (typeof companyId !== 'string' || companyId.trim() === '') return 'ended';
  return reconciledOfferStatus(await db.collection('companies').doc(companyId).get());
}

async function reconcileOffer(offerRef) {
  return db.runTransaction(async (tx) => {
    const currentOffer = await tx.get(offerRef);
    if (!currentOffer.exists || currentOffer.data()?.status !== 'active') return null;

    const companyId = currentOffer.data()?.companyId;
    const company =
      typeof companyId === 'string' && companyId.trim() !== ''
        ? await tx.get(db.collection('companies').doc(companyId))
        : null;
    const nextStatus = company ? reconciledOfferStatus(company) : 'ended';
    if (nextStatus === null) return null;

    tx.update(offerRef, {
      status: nextStatus,
      updatedAt: FieldValue.serverTimestamp(),
    });
    return nextStatus;
  });
}

async function main() {
  console.log(`[backfill] project=${projectId} mode=${apply ? 'APPLY' : 'DRY RUN'}`);

  let scanned = 0;
  let preserved = 0;
  let paused = 0;
  let ended = 0;
  const stream = db.collection('offers').where('status', '==', 'active').stream();

  for await (const offer of stream) {
    scanned += 1;
    const nextStatus = apply ? await reconcileOffer(offer.ref) : await inspectOffer(offer);
    if (nextStatus === 'paused') paused += 1;
    else if (nextStatus === 'ended') ended += 1;
    else preserved += 1;
  }

  console.log(
    `[backfill] active-scanned=${scanned} preserved=${preserved} ` +
      `${apply ? 'updated' : 'would-update'}=${paused + ended} paused=${paused} ended=${ended}`,
  );
  if (!apply && paused + ended > 0) {
    console.log('[backfill] DRY RUN — re-run with --apply to write.');
  }
}

main().catch((error) => {
  console.error('[backfill] failed:', error);
  process.exit(1);
});
