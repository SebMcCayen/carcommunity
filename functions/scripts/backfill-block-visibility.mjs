#!/usr/bin/env node
/**
 * One-off backfill: populate `blockVisibility/{uid}.hiddenUids` from the blocks
 * that already existed BEFORE block invisibility landed.
 *
 * WHY THIS IS REQUIRED: the chat read paths (communityChat-list,
 * convoyChat-list, dm-listConversations, and the Android live-window filters)
 * resolve "is this uid hidden from me" from the symmetric `blockVisibility`
 * mirror — one document read instead of one lookup per message. The mirror is
 * maintained by the `blocking-onBlockWrite` trigger, which only fires when a
 * `userBlocks/{blocker}/blocked/{blocked}` document is WRITTEN. A block made
 * before this deployment has no such write pending, so it has no mirror entry
 * and the chat surfaces will not honour it until someone blocks/unblocks again.
 *
 * (The surfaces that resolve a KNOWN pair directly — the live map, the dm-*
 * callables, and the firestore.rules DM gate — read the authoritative
 * `userBlocks` edges and are correct with or without this script. It is the
 * many-senders channel surfaces that need the denormalized set.)
 *
 * Run it once, immediately after deploying the functions and rules.
 *
 * Idempotent: entries are unioned, and a uid already present is left alone, so
 * re-running is safe and cheap. It only ADDS — it never removes an entry, so it
 * cannot race the trigger into un-hiding a pair that is still blocked.
 *
 * SYMMETRY: each directional edge A→B writes B into A's mirror AND A into B's,
 * because a block hides the pair from each other regardless of who pressed it.
 *
 * BOUND: a viewer already holding MAX_HIDDEN_UIDS entries is left at the cap and
 * reported, matching the trigger's behaviour rather than growing a document the
 * client holds a live listener on.
 *
 * WHY THIS LIVES UNDER functions/: it imports `firebase-admin`, a dependency of
 * functions/ only (functions/ is deliberately NOT a root npm workspace). Node
 * resolves bare specifiers by walking up from the SCRIPT'S OWN directory, not
 * the shell's cwd.
 *
 * Usage — every command below runs from functions/, starting at the repo root:
 *   cd functions
 *   npm ci                                  # provides firebase-admin
 *   npx tsc                                 # builds lib/, so the import below resolves
 *   GOOGLE_APPLICATION_CREDENTIALS=/path/to/sa.json \
 *     node scripts/backfill-block-visibility.mjs --project <projectId> [--apply]
 *
 * Defaults to a DRY RUN that only reports what it would change; pass --apply to
 * write. Batched at 400 writes (under Firestore's 500-op limit).
 */

import { initializeApp, applicationDefault } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { MAX_HIDDEN_UIDS } from '../lib/blocking/block-visibility.js';

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

  // Collect the full symmetric set in memory first: a viewer's mirror is ONE
  // document, so writing it per edge would rewrite the same document repeatedly
  // (and, with batched writes, conflict inside a single batch).
  /** @type {Map<string, Set<string>>} viewerUid -> uids hidden from them */
  const desired = new Map();
  const add = (viewer, other) => {
    const set = desired.get(viewer) ?? new Set();
    set.add(other);
    desired.set(viewer, set);
  };

  let edges = 0;
  // `blocked` is a subcollection of userBlocks/{blockerUid}; a collection-group
  // stream walks every edge once without knowing the blocker uids up front.
  for await (const doc of db.collectionGroup('blocked').stream()) {
    const blockerUid = doc.ref.parent.parent?.id; // userBlocks/{blockerUid}
    const blockedUid = doc.id;
    if (!blockerUid || !blockedUid || blockerUid === blockedUid) continue;
    // Guard against another `blocked` collection group appearing later.
    if (doc.ref.parent.parent?.parent?.id !== 'userBlocks') continue;
    edges += 1;
    add(blockerUid, blockedUid);
    add(blockedUid, blockerUid);
  }

  let scanned = 0;
  let updated = 0;
  let alreadyCurrent = 0;
  let atCap = 0;

  let batch = db.batch();
  let pending = 0;

  for (const [viewerUid, shouldHide] of desired) {
    scanned += 1;
    const ref = db.collection('blockVisibility').doc(viewerUid);
    const raw = (await ref.get()).data()?.hiddenUids;
    const existing = new Set(Array.isArray(raw) ? raw.filter((v) => typeof v === 'string') : []);

    const missing = [...shouldHide].filter((uid) => !existing.has(uid));
    if (missing.length === 0) {
      alreadyCurrent += 1;
      continue;
    }

    const room = MAX_HIDDEN_UIDS - existing.size;
    if (room <= 0) {
      atCap += 1;
      console.warn(`[backfill] ${viewerUid} is at the ${MAX_HIDDEN_UIDS} cap — skipped.`);
      continue;
    }
    if (missing.length > room) {
      atCap += 1;
      console.warn(
        `[backfill] ${viewerUid} would exceed the ${MAX_HIDDEN_UIDS} cap ` +
          `(${missing.length} to add, room for ${room}) — truncated.`,
      );
    }

    updated += 1;
    if (apply) {
      batch.set(ref, { hiddenUids: [...existing, ...missing.slice(0, room)] }, { merge: true });
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
    `[backfill] edges=${edges} viewers=${scanned} ` +
      `${apply ? 'updated' : 'would update'}=${updated} ` +
      `already-current=${alreadyCurrent} at-cap=${atCap}`,
  );
  if (!apply && updated > 0) {
    console.log('[backfill] DRY RUN — re-run with --apply to write.');
  }
}

main().catch((error) => {
  console.error('[backfill] failed:', error);
  process.exit(1);
});
