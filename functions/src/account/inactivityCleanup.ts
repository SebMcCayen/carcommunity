/**
 * Inactive-account cleanup sweep (account lifecycle cross-lane).
 *
 * account-cleanupInactive (04:15 Europe/Stockholm, daily) enforces the
 * inactivity lifecycle described in inactivity-core.ts:
 *
 * 1. Candidate scan: every account whose `createdAt` is older than the 11-month
 *    warning threshold. This is a SUFFICIENT filter — an account can only be
 *    inactive (lastActivity = lastLoginAt ?? createdAt older than 11 months) if
 *    it was created more than 11 months ago, since createdAt <= lastLoginAt
 *    always. Soft-deleted accounts (deleted=true, already on the deletion track)
 *    are skipped. The scan is paged with a PERSISTED CURSOR
 *    (system/inactivitySweepCursor holding { lastCreatedAt, lastUid }): each run
 *    resumes with startAfter(...) past the previous run's last doc and RESETS
 *    the cursor when a short page signals the end of the collection, wrapping
 *    back to the start. This guarantees the sweep advances across the whole
 *    `users` collection over successive days rather than forever re-scanning the
 *    oldest page — so newer eligible accounts behind a wall of active/warned
 *    ones can't be starved. The lifecycle fields it reads per candidate
 *    (lastLoginAt / inactivityWarnedAt / inactivityDeleteAfter) live in the
 *    backend-only userLifecycle/{uid} doc (batch-read via getAll), NOT on the
 *    public users/{uid} profile — createdAt still comes from the user doc.
 * 2. Per candidate, `decideInactivity` returns one of: skip, warn, clear_warning,
 *    delete, or would_delete.
 *    - warn: stamp inactivityWarnedAt = now and inactivityDeleteAfter = now + 30
 *      days, then attempt an EMAIL warning (degrades to a logged no-op until
 *      email is wired) AND write an essential in-app account_warning notice
 *      through the Phase 9l writer.
 *    - clear_warning: the user signed in again — remove the warning fields.
 *    - delete: DISABLE the Auth user + revoke its refresh tokens FIRST (fail-safe
 *      lockdown, mirroring account.deleteAccount), then REUSE the account-deletion
 *      routine (purgeUserData from account/scheduled.ts) to purge Firestore trees,
 *      owned docs, storage, and the Auth user, then retain an
 *      accountDeletionRequests proof-of-deletion record (reason
 *      inactivity_auto_cleanup).
 *    - would_delete: the hard-delete gate is closed — logged, no mutation.
 *
 * CRITICAL GATE. `delete` is only ever returned when deletionEnabled is true,
 * computed here as:
 *     config/accountLifecycle.inactiveAccountDeletionEnabled (default FALSE)
 *   AND isEmailDeliveryAvailable()  (FALSE for the MVP — email not wired).
 * With today's setup the sweep therefore only MARKS + would-warn and deletes
 * NOTHING. TO TURN DELETION ON once email works: (a) integrate an email provider
 * in functions/src/notifications/email.ts so isEmailDeliveryAvailable() is true,
 * and (b) set config/accountLifecycle.inactiveAccountDeletionEnabled = true.
 * BOTH are required — either one alone keeps deletes off.
 *
 * runInactivityCleanup is exported so emulator tests can drive it
 * deterministically.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { FieldPath, FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { adminAuth, db } from '../firebase';
import { writeInAppNotification } from '../notifications/deliver';
import { isEmailDeliveryAvailable, sendAccountEmail } from '../notifications/email';
import { purgeUserData } from './scheduled';
import {
  ACCOUNT_LIFECYCLE_CONFIG_DOC,
  INACTIVE_DELETION_ENABLED_DEFAULT,
  INACTIVE_DELETION_ENABLED_FIELD,
  INACTIVITY_DELETE_GRACE_DAYS,
  INACTIVITY_DELETION_REASON,
  addDays,
  decideInactivity,
  inactivityWarnCutoff,
  resolveLastActivity,
  type InactivityAction,
} from './inactivity-core';

/**
 * Upper bound of candidates examined per sweep — bounds the daily run's cost and
 * (with oldest-first ordering) drains any backlog over subsequent days.
 */
const MAX_CANDIDATES_PER_RUN = 200;

/**
 * Upper bound of hard-deletes (purgeUserData) performed per sweep. Each delete
 * recursively purges Firestore trees, owned docs, storage, and the Auth user, so
 * an unbounded run against a large backlog could exceed the scheduled timeout.
 * Mirrors MAX_PURGES_PER_RUN in account/scheduled.ts (account-purgeDeleted); the
 * remainder drains over subsequent daily runs (candidates are oldest-first).
 * Warnings/clears are cheap and stay bounded only by MAX_CANDIDATES_PER_RUN.
 */
const MAX_DELETES_PER_RUN = 25;

/**
 * Singleton cursor doc that lets the daily sweep advance across the full `users`
 * collection over successive runs instead of forever re-scanning the same oldest
 * page. Holds the last processed doc's ordering key { lastCreatedAt, lastUid };
 * the next run resumes with startAfter(...) and resets (clears) the cursor once a
 * short page signals the end of the collection, wrapping back to the start.
 */
const SWEEP_CURSOR_COLLECTION = 'system';
const SWEEP_CURSOR_DOC = 'inactivitySweepCursor';

interface SweepCursor {
  lastCreatedAt: Timestamp;
  lastUid: string;
}

/** Reads the persisted scan cursor; null when unset (start from the beginning). */
async function readSweepCursor(): Promise<SweepCursor | null> {
  try {
    const snap = await db.collection(SWEEP_CURSOR_COLLECTION).doc(SWEEP_CURSOR_DOC).get();
    const data = snap.data();
    if (data?.lastCreatedAt instanceof Timestamp && typeof data.lastUid === 'string') {
      return { lastCreatedAt: data.lastCreatedAt, lastUid: data.lastUid };
    }
    return null;
  } catch (error) {
    // A cursor read failure must not abort the sweep — fall back to a full scan
    // from the beginning (the delete/warn actions are all idempotent).
    logger.warn('Inactivity sweep cursor read failed; starting from beginning', {
      error: String(error),
    });
    return null;
  }
}

/** Persists the next scan cursor, or clears it to wrap back to the collection start. */
async function writeSweepCursor(cursor: SweepCursor | null): Promise<void> {
  const ref = db.collection(SWEEP_CURSOR_COLLECTION).doc(SWEEP_CURSOR_DOC);
  if (cursor === null) {
    await ref.set({ lastCreatedAt: FieldValue.delete(), lastUid: FieldValue.delete() }, { merge: true });
    return;
  }
  await ref.set(
    { lastCreatedAt: cursor.lastCreatedAt, lastUid: cursor.lastUid, updatedAt: FieldValue.serverTimestamp() },
    { merge: true },
  );
}

/** Reads the hard-delete kill-switch from config/accountLifecycle (default OFF). */
async function readInactiveDeletionFlag(): Promise<boolean> {
  try {
    const snap = await db.collection('config').doc(ACCOUNT_LIFECYCLE_CONFIG_DOC).get();
    const value = snap.data()?.[INACTIVE_DELETION_ENABLED_FIELD];
    return typeof value === 'boolean' ? value : INACTIVE_DELETION_ENABLED_DEFAULT;
  } catch (error) {
    logger.warn('Inactive-deletion flag read failed; using default (off)', {
      error: String(error),
    });
    return INACTIVE_DELETION_ENABLED_DEFAULT;
  }
}

function toDate(value: unknown): Date | null {
  return value instanceof Timestamp ? value.toDate() : null;
}

/** Marks an account warned and attempts the (currently no-op) email + in-app notice. */
async function warnAccount(uid: string, now: Date): Promise<void> {
  // Lifecycle state lives in the backend-only userLifecycle/{uid} doc, never on
  // the public users/{uid} profile — pending-deletion state must not leak to
  // other signed-in users.
  await db
    .collection('userLifecycle')
    .doc(uid)
    .set(
      {
        // Both timestamps share ONE time basis (the sweep's `now`) so the grace
        // window is exact: inactivityDeleteAfter === inactivityWarnedAt + grace.
        // A serverTimestamp() for warnedAt could resolve later than `now`,
        // shortening the intended 30-day window (and, once the hard-delete gate
        // is enabled, allowing deletion sooner than specified).
        inactivityWarnedAt: Timestamp.fromDate(now),
        inactivityDeleteAfter: Timestamp.fromDate(addDays(now, INACTIVITY_DELETE_GRACE_DAYS)),
      },
      { merge: true },
    );

  // Email warning — the primary channel per the lifecycle spec, but not wired
  // yet. While delivery is unavailable (the MVP default) sendAccountEmail is a
  // no-op, so skip the userPrivate/{uid} recipient read entirely rather than
  // spending a Firestore read per warned account every run. Best-effort: never
  // fails the sweep.
  if (isEmailDeliveryAvailable()) {
    const email = (await db.collection('userPrivate').doc(uid).get()).data()?.email;
    await sendAccountEmail({
      to: typeof email === 'string' ? email : null,
      subject: 'Ditt konto är inaktivt',
      body:
        'Ditt konto har varit inaktivt i 11 månader och kommer att raderas om 30 ' +
        'dagar om du inte loggar in igen.',
      kind: 'inactivity_warning',
    }).catch((error) => {
      logger.warn('Inactivity warning email failed', { uid, error: String(error) });
      return { sent: false as const };
    });
  }

  // Secondary in-app notice through the Phase 9l writer. account_warning is an
  // essential category (delivered even to suspended users, cannot be opted out).
  // Best-effort and non-blocking.
  try {
    await writeInAppNotification(
      uid,
      {
        category: 'account_warning',
        title: 'Ditt konto är inaktivt',
        previewText: 'Logga in inom 30 dagar för att behålla ditt konto.',
        body:
          'Ditt konto har varit inaktivt i 11 månader. Om du inte loggar in inom ' +
          '30 dagar kommer kontot att raderas automatiskt.',
      },
      // Deterministic ID keeps repeated sweeps from stacking duplicate notices
      // for the same warning window (one notice per UTC day).
      `inactivity-warn-${uid}-${now.toISOString().slice(0, 10)}`,
    );
  } catch (error) {
    logger.warn('Inactivity warning in-app notice failed', { uid, error: String(error) });
  }
}

/** Clears the warning fields when an account has become active again. */
async function clearWarning(uid: string): Promise<void> {
  // Warning fields live in userLifecycle/{uid} (see warnAccount).
  await db
    .collection('userLifecycle')
    .doc(uid)
    .set(
      {
        inactivityWarnedAt: FieldValue.delete(),
        inactivityDeleteAfter: FieldValue.delete(),
      },
      { merge: true },
    );
}

/**
 * Hard-deletes an inactive account by REUSING the account-deletion routine
 * (purgeUserData), then retains a processed accountDeletionRequests record as
 * the proof-of-deletion (consistent with the account-purgeDeleted sweep).
 *
 * Fail-safe ordering mirrors account.deleteAccount: the Auth user is DISABLED
 * and its refresh tokens revoked BEFORE the purge begins. purgeUserData deletes
 * Firestore trees first and the Auth user last, so without this lockdown a
 * transient failure of that final Auth delete would leave a still-sign-in-capable
 * account whose data is already gone. Locking down first makes any partial
 * failure fail CLOSED (no further sign-ins, sessions can't renew); the next
 * daily sweep re-runs deleteInactiveAccount idempotently to finish the purge.
 */
async function deleteInactiveAccount(uid: string, warnedAt: Date | null): Promise<void> {
  // Lock down FIRST (fail-safe ordering). Idempotent across retries and tolerant
  // of an already-removed Auth user (a previous partial run may have deleted it).
  await adminAuth.updateUser(uid, { disabled: true }).catch((error) => {
    if ((error as { code?: string }).code !== 'auth/user-not-found') throw error;
  });
  await adminAuth.revokeRefreshTokens(uid).catch((error) => {
    if ((error as { code?: string }).code !== 'auth/user-not-found') throw error;
  });

  await purgeUserData(uid);
  await db
    .collection('accountDeletionRequests')
    .doc(uid)
    .set(
      {
        userId: uid,
        reason: INACTIVITY_DELETION_REASON,
        status: 'processed',
        createdAt: warnedAt ? Timestamp.fromDate(warnedAt) : FieldValue.serverTimestamp(),
        processedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
}

export interface InactivityCleanupSummary {
  candidates: number;
  warned: number;
  cleared: number;
  deleted: number;
  /** Delete-eligible accounts skipped this run because MAX_DELETES_PER_RUN was
   * reached; they are retried on the next daily sweep. */
  deleteCapped: number;
  wouldDelete: number;
  deletionEnabled: boolean;
}

/** Runs one inactivity sweep at the given instant. */
export async function runInactivityCleanup(now: Date): Promise<InactivityCleanupSummary> {
  const flagEnabled = await readInactiveDeletionFlag();
  const emailAvailable = isEmailDeliveryAvailable();
  // CRITICAL GATE: both the config flag AND real email delivery are required.
  const deletionEnabled = flagEnabled && emailAvailable;

  // Persisted-cursor scan: resume after the last doc processed by the previous
  // run so the sweep advances across the whole `users` collection over
  // successive days instead of forever re-scanning the same oldest page (which
  // would starve newer eligible accounts sitting behind >MAX active/warned
  // ones). orderBy(documentId()) is the deterministic tiebreaker the cursor
  // resumes on; it matches Firestore's automatic single-field index on
  // createdAt (which orders by __name__ next), so NO composite index is needed.
  const cursor = await readSweepCursor();
  let query = db
    .collection('users')
    .where('createdAt', '<=', Timestamp.fromDate(inactivityWarnCutoff(now)))
    .orderBy('createdAt', 'asc')
    .orderBy(FieldPath.documentId());
  if (cursor !== null) {
    query = query.startAfter(cursor.lastCreatedAt, cursor.lastUid);
  }
  const candidates = await query.limit(MAX_CANDIDATES_PER_RUN).get();

  // Lifecycle fields (lastLoginAt / inactivityWarnedAt / inactivityDeleteAfter)
  // now live in the backend-only userLifecycle/{uid} doc, not on the public
  // users/{uid} profile. Batch-read the page's lifecycle docs in one getAll so
  // the sweep still sources those fields (createdAt stays on the user doc).
  const lifecycleRefs = candidates.docs.map((snap) =>
    db.collection('userLifecycle').doc(snap.id),
  );
  const lifecycleSnaps =
    lifecycleRefs.length > 0 ? await db.getAll(...lifecycleRefs) : [];
  const lifecycleByUid = new Map(
    lifecycleSnaps.map((snap) => [snap.id, snap.data() ?? {}]),
  );

  const summary: InactivityCleanupSummary = {
    candidates: candidates.size,
    warned: 0,
    cleared: 0,
    deleted: 0,
    deleteCapped: 0,
    wouldDelete: 0,
    deletionEnabled,
  };

  for (const snap of candidates.docs) {
    const data = snap.data();
    if (data.deleted === true) {
      // Already on the deletion track (account-purgeDeleted owns it).
      continue;
    }
    const createdAt = toDate(data.createdAt);
    if (createdAt === null) {
      continue;
    }
    const uid = snap.id;
    // Accounts that never logged in have no userLifecycle doc → lastLoginAt
    // undefined → resolveLastActivity falls back to createdAt (as before).
    const lifecycle = lifecycleByUid.get(uid) ?? {};
    const warnedAt = toDate(lifecycle.inactivityWarnedAt);
    const decision = decideInactivity({
      now,
      lastActivityAt: resolveLastActivity(toDate(lifecycle.lastLoginAt), createdAt),
      warnedAt,
      deleteAfter: toDate(lifecycle.inactivityDeleteAfter),
      deletionEnabled,
    });

    try {
      await applyDecision(uid, decision.action, warnedAt, now, summary);
    } catch (error) {
      // One account's failure must not abort the sweep; the next run retries
      // (all actions are idempotent / purgeUserData is safe to re-run).
      logger.error('Inactivity action failed; will retry next run', {
        uid,
        action: decision.action,
        error: String(error),
      });
    }
  }

  if (summary.deleteCapped > 0) {
    logger.warn('Inactive-account delete cap reached; deferring remainder to next run', {
      maxDeletesPerRun: MAX_DELETES_PER_RUN,
      deleted: summary.deleted,
      deferred: summary.deleteCapped,
    });
  }

  // Advance the persisted cursor so the next daily run resumes past this page.
  // A short page (fewer than the limit) means the end of the collection was
  // reached this run → RESET the cursor so the next run wraps back to the start
  // (a full pass, so every account is eventually reached — no starvation).
  const lastDoc = candidates.docs[candidates.docs.length - 1];
  if (candidates.size < MAX_CANDIDATES_PER_RUN || lastDoc === undefined) {
    await writeSweepCursor(null);
  } else {
    const lastCreatedAt = lastDoc.get('createdAt');
    if (lastCreatedAt instanceof Timestamp) {
      await writeSweepCursor({ lastCreatedAt, lastUid: lastDoc.id });
    } else {
      // Defensive: a full page whose last doc lacks a Timestamp createdAt can't
      // seed a valid startAfter cursor — reset rather than persist a bad one.
      await writeSweepCursor(null);
    }
  }

  logger.info('Inactive-account sweep complete', { ...summary });
  return summary;
}

async function applyDecision(
  uid: string,
  action: InactivityAction,
  warnedAt: Date | null,
  now: Date,
  summary: InactivityCleanupSummary,
): Promise<void> {
  switch (action) {
    case 'warn':
      await warnAccount(uid, now);
      summary.warned += 1;
      return;
    case 'clear_warning':
      await clearWarning(uid);
      summary.cleared += 1;
      return;
    case 'delete':
      if (summary.deleted >= MAX_DELETES_PER_RUN) {
        // Per-run hard-delete cap reached — defer this (and any further)
        // delete to the next daily sweep so a backlog drains over days
        // instead of pushing this run past its timeout.
        summary.deleteCapped += 1;
        return;
      }
      await deleteInactiveAccount(uid, warnedAt);
      summary.deleted += 1;
      return;
    case 'would_delete':
      // Gate closed: log the account that WOULD be deleted once deletion is
      // enabled, but make no change.
      logger.info('Inactive account past grace period but delete gate is closed', { uid });
      summary.wouldDelete += 1;
      return;
    case 'skip':
      return;
  }
}

/** Daily inactive-account sweep. */
export const cleanupInactive = onSchedule(
  {
    region: 'europe-west1',
    timeZone: 'Europe/Stockholm',
    memory: '512MiB' as const,
    timeoutSeconds: 540,
    schedule: '15 4 * * *',
  },
  async () => {
    await runInactivityCleanup(new Date());
  },
);
