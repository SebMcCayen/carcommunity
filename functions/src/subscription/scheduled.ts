/**
 * subscription-expireLapsed — the EXIT from the paid tier.
 *
 * `subscriptions/{uid}.expiresAt` was written and never read, so an
 * entitlement never lapsed: a manual grant with an expiry date, or a store
 * subscription whose renewal stopped, kept `users/{uid}.activeMember` and
 * the `activeMember` custom claim forever. This sweep revokes them.
 *
 * ENTITLEMENT LIVES IN THREE PLACES and the sweep must clear all three, or
 * access survives in whichever one it missed:
 *   1. `subscriptions/{uid}` — the record (status/entitlement); read by the
 *      admin console and the Android subscription screen.
 *   2. `users/{uid}.activeMember` — read by shared/access.ts
 *      (toUserAccessState → memberGateAllows) for every gated callable, by
 *      the admin dashboard's member count, and by notifications.adminSend's
 *      `members` audience query.
 *   3. the `activeMember` Firebase Auth custom claim — read by
 *      firestore.rules / storage.rules (`isActiveMember()`) and by
 *      database.rules.json for live-location markers.
 * It does NOT re-implement those writes: it calls the SAME
 * applyEntitlement the admin grant path uses, which is the single writer
 * for all three and carries the Phase 8 fail-safe ordering (a revocation
 * DECREASES privilege, so the claim is cleared and refresh tokens revoked
 * BEFORE the records — a partial failure can never leave someone with more
 * access than their records say). No fourth mirror of membership exists
 * (userLifecycle, userPrivate, badgeProgress and the RTDB nodes all carry
 * none), so those three are exhaustive.
 *
 * MEMBER GATING IS STILL OFF. shared/memberGating.ts MEMBER_GATING_ENABLED
 * is false, so revoking entitlement does not currently remove any
 * capability — this builds the exit so it is correct and tested BEFORE the
 * gate is flipped. Flipping it is a separate, deliberate, atomic change
 * (the five-switch runbook in memberGating.ts) and is NOT done here.
 *
 * IDEMPOTENT, with three independent guards:
 *   - The query only matches GRANTING statuses, so a revoked document
 *     stops matching. A re-run sees its own output and does nothing.
 *   - decideSubscriptionExpiry re-derives the decision from the document,
 *     so a stale/mis-indexed query result is still rejected.
 *   - The member notification uses a deterministic ID keyed on the lapse
 *     instant, so a replay collapses onto the same inbox item.
 * applyEntitlement itself is an overwrite of fixed values plus a claim
 * removal, both naturally idempotent.
 *
 * BOUNDED AND PAGED. Two `limit(MAX_EXPIRIES_PER_RUN)` queries separate the
 * immediate cancelled deadline from the active/grace outage window. Their
 * results are merged by effective revocation deadline and re-bounded before
 * writes, so the most-overdue member is always served first and every
 * document in the final page is processed (a single
 * poison record therefore consumes one slot, it does not block the page
 * behind it). Because a revocation makes the document stop matching, a
 * backlog DRAINS across runs instead of recirculating — the same
 * re-run-the-query-rather-than-cursor pattern as account-purgeDeleted, and
 * it needs no cursor document. Nothing ever loads the whole collection.
 *
 * WHERE THE REVOCATION IS RECORDED: `userLifecycle/{uid}.subscriptionExpiry`
 * — the existing backend-only per-user system-state document, which
 * already holds exactly this class of fact (`lastLoginAt` from
 * auth.recordLogin, `inactivityWarnedAt` / `inactivityDeleteAfter` from
 * the account-cleanupInactive sweep). Rules already deny every client
 * write there and limit reads to owner + admin, and the account purge
 * already deletes it, so the record inherits the right lifecycle for
 * free. It is deliberately NOT `adminAuditEvents`: that log's entries are
 * keyed on an `adminId` and it is a record of ADMIN actions (the same
 * reasoning events/eventLifecycle.ts applies when a non-admin ends an
 * event). A system-initiated revocation has no admin actor. No new
 * collection is introduced.
 *
 * The stamp is written BEFORE applyEntitlement, on purpose. The two writes
 * cannot be made atomic (one touches Auth), so one of them has to be able
 * to fail first, and this ordering is the recoverable one: if the stamp
 * fails, nothing was revoked and the document still matches, so the whole
 * step retries; if the stamp succeeds and the revocation fails, the
 * document still matches and the next run re-stamps (an identical
 * overwrite) and retries. The reverse order would lose the audit record
 * permanently, because a successful revocation removes the document from
 * the query and nothing would ever come back for it.
 *
 * MEMBER NOTIFICATION: yes, in-app, under the pre-existing
 * `subscription_status` category — silently losing access is confusing,
 * and that category already exists end-to-end (backend enum, contract
 * schema, Android parser, settings toggle, and a push deep link to the
 * subscription screen), so no new category is introduced. Push follows
 * automatically via notifications-onNotificationCreated. NO EMAIL: email
 * is deferred project-wide to inactive-account warnings only
 * (notifications/email.ts EMAIL_PROVIDER_INTEGRATED === false).
 * Notification failure is logged and swallowed — it must never leave the
 * entitlement un-revoked.
 *
 * runSubscriptionExpirySweep(now) is exported for deterministic emulator
 * tests (same pattern as runAccountPurge / runInactivityCleanup).
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { adminAuth, db } from '../firebase';
import { MAX_INSTANCES_SCHEDULED, CPU_SCHEDULED } from '../shared/instanceLimits';
import { writeInAppNotification } from '../notifications/deliver';
import { applyEntitlement } from './entitlement';
import type { SubscriptionStatus, SubscriptionTier } from './subscription-core';
import {
  EXPIRY_GRACE_SWEEP_STATUSES,
  EXPIRY_IMMEDIATE_SWEEP_STATUSES,
  MAX_EXPIRIES_PER_RUN,
  SUBSCRIPTION_EXPIRY_GRACE_HOURS,
  decideSubscriptionExpiry,
  subscriptionExpiredNotificationId,
  subscriptionExpiryCutoff,
  subscriptionRevocationDeadline,
} from './expiry-core';

/** Names this sweep in the userLifecycle record, in place of an adminId. */
const EXPIRY_SOURCE = 'subscription-expireLapsed';

const EXPIRY_NOTIFICATION_TITLE = 'Ditt medlemskap har upphört';
const EXPIRY_NOTIFICATION_PREVIEW = 'Medlemsförmånerna är pausade tills du förnyar.';
const EXPIRY_NOTIFICATION_BODY =
  'Ditt medlemskap har gått ut och medlemsförmånerna är pausade. ' +
  'Förnya medlemskapet i appen för att få tillbaka dem.';

export interface SubscriptionExpirySweepResult {
  /** Documents the bounded query returned. */
  scanned: number;
  /** Entitlements fully revoked (all three representations cleared). */
  expiredCount: number;
  expiredUids: string[];
  /**
   * Records whose AUTH account no longer exists, closed record-only (see
   * expireOrphanedRecord). Never incremented for an account we merely
   * failed to look up — that counts as failed, not orphaned.
   */
  orphanedCount: number;
  /** Documents the re-derived decision rejected. */
  skippedCount: number;
  /** Members who received the in-app expiry notice. */
  notifiedCount: number;
  /** Documents whose revocation threw and will be retried next run. */
  failedCount: number;
}

function toDate(value: unknown): Date | null {
  return value instanceof Timestamp ? value.toDate() : null;
}

/**
 * Records the revocation on the backend-only per-user lifecycle document.
 * Written as one nested map so a re-stamp REPLACES the record wholesale
 * (a `merge: true` set replaces a map-valued field) rather than merging
 * two different lapses into one incoherent row.
 */
async function recordRevocation(
  uid: string,
  lapsedAt: Date,
  previousStatus: string,
  platform: string,
): Promise<void> {
  await db
    .collection('userLifecycle')
    .doc(uid)
    .set(
      {
        subscriptionExpiry: {
          // The subscription's own expiresAt — the instant it lapsed.
          lapsedAt: Timestamp.fromDate(lapsedAt),
          // When the sweep acted on it.
          expiredAt: FieldValue.serverTimestamp(),
          previousStatus,
          platform,
          graceHours: SUBSCRIPTION_EXPIRY_GRACE_HOURS,
          // No admin actor — this names the system actor instead.
          source: EXPIRY_SOURCE,
        },
      },
      { merge: true },
    );
}

/**
 * Closes a subscription record whose AUTH ACCOUNT is gone.
 *
 * `subscriptions/{uid}` is purged by neither the account deletion doc-tree
 * sweep (PURGE_DOC_TREES) nor the owned-document sweep
 * (PURGE_OWNED_COLLECTIONS) — verified against account/deletion-core.ts,
 * where it appears in neither list nor on the deliberately-retained list.
 * So an erased account CAN leave an orphan record behind; these are not
 * merely theoretical. Left alone one would be a poison pill: it matches
 * the query forever, applyEntitlement's `adminAuth.getUser` throws
 * auth/user-not-found, and it would consume a slot in every future run.
 *
 * Closed with a MERGING set — there is no claim to clear and no user
 * document to write. The derived tier and startsAt are materialized while
 * merging preserves platform/purchaseTokenHash/expiresAt as the historical
 * record. No notification (there is no one to
 * notify) and no lifecycle stamp (that document HAS been purged, and
 * writing one would resurrect it for an erased account) — which is why
 * the orphan test happens before recordRevocation, not in place of it.
 */
async function expireOrphanedRecord(
  uid: string,
  tier: SubscriptionTier,
  startsAt: Date | null,
): Promise<void> {
  await db.collection('subscriptions').doc(uid).set(
    {
      status: 'expired',
      entitlement: 'none',
      tier,
      startsAt,
      updatedAt: FieldValue.serverTimestamp(),
    },
    { merge: true },
  );
}

/**
 * The ONLY error that means "this account no longer exists".
 *
 * Deliberately an exact match on the Admin SDK's `auth/user-not-found`
 * code and nothing else. Every other Auth failure — a quota error, a
 * network blip, `auth/internal-error`, an `auth/invalid-uid` from a
 * malformed document ID — is a failure to ANSWER the question, not a
 * negative answer, and must fall through to the retry path. Widening this
 * (to, say, any thrown Auth error) would let one transient blip downgrade
 * a real revocation to a record-only close, which is the expensive
 * direction: the record would then stop matching the query, so nothing
 * would ever come back to clear the member's claim and they would keep
 * paid access permanently.
 */
function isUserNotFound(error: unknown): boolean {
  return (error as { code?: string } | null)?.code === 'auth/user-not-found';
}

/**
 * Revokes active/grace entitlements after the outage-tolerance window and
 * canceled subscriptions at their paid expiry, up to MAX_EXPIRIES_PER_RUN.
 *
 * A member holding entitlement with NO `subscriptions` document, or with
 * one carrying no `expiresAt`, is DELIBERATELY UNTOUCHED: the sweep is
 * driven by an explicit, expired `expiresAt` and never by the absence of
 * evidence. That is what keeps a perpetual manual grant — the operational
 * path today, since subscription.verify has no store adapter and fails
 * closed, and the mechanism behind the operator's own admin/test access —
 * from being revoked by a sweep that cannot tell "granted forever" from
 * "record not written yet". Revoking on absence would also turn any future
 * read failure or partial migration into a mass lockout of paying members.
 * Ending a perpetual grant stays an explicit admin act
 * (subscription.grantEntitlement with entitlement `none`).
 */
export async function runSubscriptionExpirySweep(
  now: Date,
): Promise<SubscriptionExpirySweepResult> {
  const cutoff = subscriptionExpiryCutoff(now);
  const dueQuery = (statuses: readonly SubscriptionStatus[], dueBefore: Date) =>
    db
      .collection('subscriptions')
      // Only statuses that currently GRANT access, so revoked records drop
      // out of the query and a backlog drains instead of recirculating.
      .where('status', 'in', statuses)
      // Excludes PERPETUAL grants (expiresAt null) at the QUERY level, not
      // just in the decision below. Firestore's inequality filters follow its
      // total type ordering, in which null sorts BEFORE every timestamp.
      .where('expiresAt', '>', Timestamp.fromMillis(0))
      .where('expiresAt', '<=', Timestamp.fromDate(dueBefore))
      .orderBy('expiresAt', 'asc')
      .limit(MAX_EXPIRIES_PER_RUN)
      .get();

  const [graceDue, immediateDue] = await Promise.all([
    dueQuery(EXPIRY_GRACE_SWEEP_STATUSES, cutoff),
    dueQuery(EXPIRY_IMMEDIATE_SWEEP_STATUSES, now),
  ]);
  // Both status sets are disjoint. Merge by the deadline that actually governs
  // revocation (paid expiry for cancelled, paid expiry + outage tolerance for
  // active/grace), then re-apply the global page bound so two queries cannot
  // double one run's mutation ceiling or starve an immediately-due cancellation.
  const dueDocs = [...graceDue.docs, ...immediateDue.docs]
    .sort((left, right) => {
      const deadlineMs = (doc: FirebaseFirestore.QueryDocumentSnapshot): number => {
        const expiresAt = doc.get('expiresAt')?.toDate?.();
        const status = doc.get('status') as SubscriptionStatus;
        return expiresAt instanceof Date
          ? subscriptionRevocationDeadline(status, expiresAt).getTime()
          : Number.MAX_SAFE_INTEGER;
      };
      return deadlineMs(left) - deadlineMs(right);
    })
    .slice(0, MAX_EXPIRIES_PER_RUN);

  const expiredUids: string[] = [];
  let orphanedCount = 0;
  let skippedCount = 0;
  let notifiedCount = 0;
  let failedCount = 0;

  for (const docSnap of dueDocs) {
    const uid = docSnap.id;
    const data = docSnap.data();
    const decisionCutoff = data.status === 'cancelled' ? now : cutoff;
    const decision = decideSubscriptionExpiry(
      {
        ...data,
        tier: data.tier,
        startsAt: toDate(data.startsAt),
        expiresAt: toDate(data.expiresAt),
      },
      decisionCutoff,
    );
    if (!decision.expire) {
      // The query and the re-derived decision disagreed — never revoke on
      // a result we cannot independently justify.
      skippedCount += 1;
      logger.warn('Subscription expiry skipped by re-derived decision', {
        uid,
        reason: decision.reason,
      });
      continue;
    }

    try {
      // ORPHAN TEST, and it asks AUTH — not Firestore.
      //
      // Entitlement lives in three places and the Auth custom claim is one
      // of them, so "is there anything left to revoke?" is a question only
      // Auth can answer. A missing `users/{uid}` document does NOT imply a
      // missing account: account purgeUserData deletes the doc trees and
      // the Auth user LAST (account/scheduled.ts), so between those two
      // steps — and permanently, if that final delete fails — the account
      // is alive and still carrying `activeMember: true`. Treating that as
      // an orphan would close the record record-only, and because a closed
      // record stops matching this query, NOTHING would ever come back to
      // clear the claim: firestore.rules / storage.rules / database.rules
      // would keep honouring it forever. That is the un-revoked-access
      // failure, and it is the reason this is an Auth read.
      //
      // Cost is unchanged, not added: this replaces the Firestore read it
      // used to do, so it is still one lookup per candidate, bounded by
      // the query's MAX_EXPIRIES_PER_RUN page. It runs FIRST, before the
      // lifecycle stamp, so an orphan is closed without resurrecting the
      // userLifecycle document the purge already erased.
      //
      // Throwing is the ONLY signal: `auth/user-not-found` is caught below
      // and closed record-only; every other Auth error falls through to
      // the retry path (see isUserNotFound). Fail-safe by construction —
      // if Auth cannot be reached, we revoke nothing and try again.
      await adminAuth.getUser(uid);

      // Audit record FIRST — see the header: this is the recoverable order.
      await recordRevocation(uid, decision.expiresAt, decision.previousStatus, decision.platform);

      // The single writer for all three representations of entitlement,
      // with fail-safe revoke ordering (claim + token revocation before
      // records). The record's historical fields are carried through
      // because this write is merge-less.
      await applyEntitlement({
        userId: uid,
        platform: decision.platform,
        status: 'expired',
        entitlement: 'none',
        tier: decision.tier,
        purchaseTokenHash: decision.purchaseTokenHash,
        startsAt: decision.startsAt,
        expiresAt: decision.expiresAt,
      });
      expiredUids.push(uid);

      // Best-effort: a member must never keep entitlement because their
      // inbox write failed.
      try {
        const delivered = await writeInAppNotification(
          uid,
          {
            category: 'subscription_status',
            title: EXPIRY_NOTIFICATION_TITLE,
            previewText: EXPIRY_NOTIFICATION_PREVIEW,
            body: EXPIRY_NOTIFICATION_BODY,
            actionType: 'open_subscription',
          },
          subscriptionExpiredNotificationId(decision.expiresAt),
        );
        if (delivered.delivered) notifiedCount += 1;
      } catch (error) {
        logger.warn('Subscription expiry notification failed', { uid, error: String(error) });
      }
    } catch (error) {
      if (isUserNotFound(error)) {
        // The Auth account is gone. Normally raised by the orphan test at
        // the top of the block (so nothing has been written yet); also
        // covers the narrow race where the account is deleted between
        // that test and applyEntitlement's own getUser.
        try {
          await expireOrphanedRecord(uid, decision.tier, decision.startsAt);
        } catch (closeError) {
          // The close WRITE failed, so the record is still granting and
          // still matches the query. Count it as failed rather than
          // orphaned: `orphanedCount` means "closed", and a counter that
          // reports a close that did not happen would make the sweep's own
          // metrics the thing that hides the backlog. The next run retries
          // — the Auth lookup fails the same way and lands back here.
          failedCount += 1;
          logger.error('Failed to close orphaned subscription record; will retry next run', {
            uid,
            error: String(closeError),
          });
          continue;
        }
        orphanedCount += 1;
        logger.info('Closed orphaned subscription record (Auth account gone)', { uid });
        continue;
      }
      // NOT an orphan — we simply failed to act. Leave the document
      // granting; it still matches the query, so the next run retries.
      // Every step above is idempotent.
      failedCount += 1;
      logger.error('Subscription expiry failed; will retry next run', {
        uid,
        error: String(error),
      });
    }
  }

  const result: SubscriptionExpirySweepResult = {
    scanned: dueDocs.length,
    expiredCount: expiredUids.length,
    expiredUids,
    orphanedCount,
    skippedCount,
    notifiedCount,
    failedCount,
  };
  logger.info('Subscription expiry sweep complete', {
    scanned: result.scanned,
    expiredCount: result.expiredCount,
    orphanedCount,
    skippedCount,
    notifiedCount,
    failedCount,
  });
  return result;
}

/**
 * Every 3 hours. The 72 h grace window makes the exact cadence
 * immaterial to correctness; 3-hourly is chosen for THROUGHPUT — eight
 * runs a day at MAX_EXPIRIES_PER_RUN gives >2x headroom over the lapse
 * rate of a 20 000-member base (see MAX_EXPIRIES_PER_RUN).
 */
export const expireLapsed = onSchedule(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_SCHEDULED,
    cpu: CPU_SCHEDULED,
    concurrency: 1,
    timeZone: 'Europe/Stockholm',
    memory: '256MiB' as const,
    timeoutSeconds: 540,
    schedule: 'every 3 hours',
  },
  async () => {
    await runSubscriptionExpirySweep(new Date());
  },
);
