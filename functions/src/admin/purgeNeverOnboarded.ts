/**
 * admin.purgeNeverOnboarded — admin-only, DRY-RUN-FIRST one-off cleanup.
 *
 * Deployed via the `admin` export group as `admin-purgeNeverOnboarded`.
 *
 * Deletes the batch of NEVER-ONBOARDED accounts left behind by the historical
 * display-name leak (their Google real name sits in the world-readable
 * `users/{uid}.displayName`). See purgeNeverOnboarded-core.ts for the full
 * rationale and the exact selection predicate.
 *
 * ## Safety design
 *
 * - Admin/owner only (requireAdminActor), admin instance tier.
 * - Selection is conservative and role-guarded: never onboarded AND not an
 *   admin/owner AND not safelisted (classifyAccount). An admin/owner account
 *   is excluded on ROLE grounds even if its onboarding flag were null.
 * - `dryRun: true` deletes NOTHING and returns only NON-SENSITIVE identifiers
 *   (uid, createdAt, whether a userPrivate doc exists) — never the leaked
 *   displayName or email, which would just re-expose the data being removed.
 * - `dryRun: false` REFUSES unless `confirmToken === 'PURGE'` (PURGE_CONFIRM_
 *   TOKEN), so a real purge can never happen by accident or default.
 * - The real purge REUSES the existing account-deletion cascade
 *   (account/scheduled.ts `purgeUserData`) per account — Auth user + every
 *   Firestore mirror — so no orphan is left. It is idempotent per account
 *   (already-deleted → the cascade's own no-op), so a re-run is safe.
 * - A real purge writes ONE `adminAuditEvents` record (action
 *   `admin.purgeNeverOnboarded`) naming the actor, the count, and the purged uids
 *   (uids only — no names) as the accountability record.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { requireAdminActor } from './actorContext';
import { buildAdminAuditEvent } from './claims-core';
import {
  PURGE_MAX_BATCH,
  PURGE_MAX_SCAN,
  classifyAccount,
  isConfirmed,
  parsePurgeInput,
} from './purgeNeverOnboarded-core';
import { purgeUserData } from '../account/scheduled';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';

/** A dry-run candidate — NON-SENSITIVE identifiers only (never name/email). */
export interface PurgeCandidate {
  uid: string;
  /** ISO string of the account's createdAt, or null when unset/malformed. */
  createdAt: string | null;
  /** Whether a `userPrivate/{uid}` document exists (a rough "how real" hint). */
  hasUserPrivate: boolean;
}

export interface PurgeDryRunResponse {
  dryRun: true;
  candidateCount: number;
  candidates: PurgeCandidate[];
  /** Accounts skipped because their role is admin/owner — Seb's own protection. */
  excludedAdminOwnerCount: number;
  /**
   * True when EITHER the scan cap (PURGE_MAX_SCAN) OR the batch cap
   * (PURGE_MAX_BATCH) was hit — this preview does not cover every candidate;
   * one real run deletes only this previewed batch, so re-run for the rest.
   */
  capped: boolean;
}

export interface PurgeRealResponse {
  dryRun: false;
  purgedCount: number;
  purgedUids: string[];
  failures: { uid: string; error: string }[];
  excludedAdminOwnerCount: number;
  /**
   * True when EITHER the scan cap (PURGE_MAX_SCAN) OR the batch cap
   * (PURGE_MAX_BATCH) was hit — not every candidate was covered in this run;
   * re-run (idempotent) for the rest.
   */
  capped: boolean;
}

export type PurgeNeverOnboardedResponse = PurgeDryRunResponse | PurgeRealResponse;

function toIso(value: unknown): string | null {
  return value instanceof Timestamp ? value.toDate().toISOString() : null;
}

/**
 * Scans the (small) `users` collection once, up to PURGE_MAX_SCAN docs, and
 * partitions it via classifyAccount. Returns the selected candidate uids, the
 * admin/owner-excluded count, and whether the scan hit the cap.
 *
 * Ordered by `createdAt` DESCENDING (newest first). At the current scale the
 * target collection is tiny (~20 docs), far below PURGE_MAX_SCAN, so the cap is
 * never approached: the WHOLE collection is scanned on every run and the order
 * is immaterial to completeness. This is a single, un-cursored page — there is
 * NO pagination. So the ordering direction only matters in the (not-current)
 * event that the cap is actually hit, and there it is a KNOWN LIMITATION, not a
 * drain guarantee: a single pass sees only the newest PURGE_MAX_SCAN documents,
 * and if those were dominated by kept (onboarded) accounts a plain re-run would
 * just re-see the same newest page — older candidates beyond the cap would NOT
 * be reached without a cursor. The cap-hit is therefore LOGGED (see the
 * `capped` warning below) so a scan that outgrew this design is visible;
 * completeness at that scale would require adding pagination, which is
 * deliberately not built at ~20 docs.
 *
 * The ordering also makes the scan DETERMINISTIC, which the emulator test
 * relies on (its freshly-created accounts are the newest, so they are always in
 * range). Uses the automatic single-field `createdAt` index — no composite
 * index to deploy.
 *
 * Note: Firestore `orderBy` excludes documents that lack the ordered field, so
 * a `users` doc without `createdAt` is not scanned. Every account provisioned
 * by auth.onUserCreate writes `createdAt` (buildUserProfileDocument), so no
 * leaked account is missed; a doc without it is not a normally-provisioned
 * account and is deliberately left untouched — the safe direction for a
 * deletion.
 */
/** A scanned candidate — uid plus its already-read createdAt (ISO or null). */
interface ScannedCandidate {
  uid: string;
  createdAt: string | null;
}

async function scanCandidates(): Promise<{
  candidates: ScannedCandidate[];
  excludedAdminOwnerCount: number;
  capped: boolean;
}> {
  const snap = await db
    .collection('users')
    .orderBy('createdAt', 'desc')
    .limit(PURGE_MAX_SCAN)
    .get();
  const candidates: ScannedCandidate[] = [];
  let excludedAdminOwnerCount = 0;
  for (const docSnap of snap.docs) {
    const decision = classifyAccount(docSnap.id, docSnap.data());
    if (decision.selected) {
      // Carry createdAt out of the doc we just read, so the dry-run loop does
      // not re-read users/{uid} — it only probes userPrivate/{uid}.
      candidates.push({ uid: docSnap.id, createdAt: toIso(docSnap.data()?.createdAt) });
    } else if (decision.reason === 'admin_or_owner') {
      excludedAdminOwnerCount += 1;
    }
  }
  const capped = snap.size >= PURGE_MAX_SCAN;
  if (capped) {
    logger.warn('purgeNeverOnboarded scan hit PURGE_MAX_SCAN — result may be partial', {
      scanned: snap.size,
      cap: PURGE_MAX_SCAN,
    });
  }
  return { candidates, excludedAdminOwnerCount, capped };
}

export const purgeNeverOnboarded = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_ADMIN,
    cpu: CPU_ADMIN,
    concurrency: 1,
    memory: '512MiB',
    timeoutSeconds: 540,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<PurgeNeverOnboardedResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parsePurgeInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { dryRun, confirmToken } = parsed.input;

    // Reject an unconfirmed REAL run BEFORE scanning — an invalid real-run call
    // costs zero reads (the confirm sentinel is a fixed string, not data). Dry
    // runs never need the token and fall through to the scan.
    if (!dryRun && !isConfirmed(confirmToken)) {
      throw new HttpsError(
        'failed-precondition',
        'Confirmation required: pass confirmToken "PURGE" to run the real purge.',
      );
    }

    const { candidates, excludedAdminOwnerCount, capped } = await scanCandidates();

    // The dry-run preview must MIRROR one real run: the real purge slices to
    // PURGE_MAX_BATCH, so the preview shows exactly that first batch and reports
    // `capped` when a batch cap OR the scan cap applies. Otherwise Seb would
    // confirm a count larger than a single invocation actually deletes.
    const batchCandidates = candidates.slice(0, PURGE_MAX_BATCH);
    const batchCapped = capped || candidates.length > PURGE_MAX_BATCH;

    // ---- DRY RUN: delete nothing, return non-sensitive identifiers only. ----
    if (dryRun) {
      const previewed: PurgeCandidate[] = [];
      for (const candidate of batchCandidates) {
        // createdAt already came from scanCandidates' read — here we only PROBE
        // userPrivate/{uid} existence. Deliberately NOT returned: displayName /
        // email — returning them would re-expose the very data this cleanup
        // removes. A uid is enough for Seb.
        const privateSnap = await db.collection('userPrivate').doc(candidate.uid).get();
        previewed.push({
          uid: candidate.uid,
          createdAt: candidate.createdAt,
          hasUserPrivate: privateSnap.exists,
        });
      }
      return {
        dryRun: true,
        candidateCount: previewed.length,
        candidates: previewed,
        excludedAdminOwnerCount,
        capped: batchCapped,
      };
    }

    // ---- REAL PURGE (confirm sentinel already verified above). ----
    // Same batch the dry-run previewed (batchCandidates / batchCapped, computed
    // above): one call runs the cascade over at most PURGE_MAX_BATCH accounts so
    // it cannot blow the timeout; a remainder is drained by a (idempotent)
    // re-run. Preview and real run therefore delete the same set.
    const batch = batchCandidates.map((candidate) => candidate.uid);

    const purgedUids: string[] = [];
    const failures: { uid: string; error: string }[] = [];
    for (const uid of batch) {
      try {
        // REUSE the existing deletion cascade — Auth user + all Firestore
        // mirrors. Idempotent per account (already-deleted → cascade no-op).
        await purgeUserData(uid);
        purgedUids.push(uid);
      } catch (error) {
        logger.error('purgeNeverOnboarded: account purge failed', {
          uid,
          error: String(error),
        });
        failures.push({ uid, error: String(error) });
      }
    }

    // ONE audit record for the whole operation — the accountability trail.
    // uids only, never names, per the reason this cleanup exists.
    await db
      .collection('adminAuditEvents')
      .doc()
      .set(
        buildAdminAuditEvent(
          {
            adminId: actor.uid,
            action: 'admin.purgeNeverOnboarded',
            targetType: 'account_batch',
            targetId: 'never_onboarded',
            reason: 'One-off cleanup of never-onboarded accounts (display-name leak remediation).',
            details: {
              purgedCount: purgedUids.length,
              purgedUids,
              failureCount: failures.length,
            },
          },
          () => FieldValue.serverTimestamp(),
        ),
      );

    logger.info('purgeNeverOnboarded complete', {
      purgedCount: purgedUids.length,
      failureCount: failures.length,
    });

    return {
      dryRun: false,
      purgedCount: purgedUids.length,
      purgedUids,
      failures,
      excludedAdminOwnerCount,
      capped: batchCapped,
    };
  },
);
