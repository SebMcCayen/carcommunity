/**
 * subscription-reconcileEntitlements — the scheduled reconciliation sweep
 * (onSchedule). The I/O half of reconcile-core.ts.
 *
 * Walks the `subscriptions` collection in bounded, cursor-rotated pages and,
 * for each record, ensures the account's activeMember claim + users flag agree
 * with the record. When they don't and the account holds MORE access than the
 * record grants, it re-applies the record through applyEntitlement (the single
 * writer, with fail-safe revoke ordering) to clear the stale privilege. See
 * reconcile-core.ts for why this is downgrade-only and why it does not
 * re-query Play per subscription.
 *
 * PROVIDER-GATED: a no-op while `config/subscriptionProviders.google` is
 * disabled — the sweep deploys inert alongside the rest of the store feature.
 * BOUNDED + SELF-DRAINING: MAX_RECONCILE_PER_RUN per run, advanced by a
 * rotating documentId cursor (reconcileState/subscriptionRepresentation) that
 * wraps to the start when a short page is returned — so the whole collection
 * is covered over successive runs without ever loading it all. FAIL-SAFE
 * PER USER: one account's failure is logged and skipped, never aborting the
 * batch (same contract as the expiry sweep).
 *
 * runSubscriptionReconciliation(deps) is exported for deterministic unit tests
 * (Auth, Firestore, the provider gate and the cursor are all injected).
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { FieldPath, FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { adminAuth, db } from '../firebase';
import { CPU_SCHEDULED, MAX_INSTANCES_SCHEDULED } from '../shared/instanceLimits';
import { applyEntitlement } from './entitlement';
import { isSubscriptionProviderEnabled } from './provider-config';
import {
  MAX_RECONCILE_PER_RUN,
  RECONCILE_CURSOR_DOC,
  type ReconcileRecord,
  decideReconciliation,
} from './reconcile-core';
import {
  SUBSCRIPTION_PLATFORMS,
  SUBSCRIPTION_STATUSES,
  SUBSCRIPTION_TIERS,
  type EntitlementRecordInput,
  type SubscriptionEntitlement,
  type SubscriptionPlatform,
  type SubscriptionStatus,
  type SubscriptionTier,
} from './subscription-core';

const RECONCILE_STATE_COLLECTION = 'reconcileState';

export interface ReconcileCandidate {
  id: string;
  record: ReconcileRecord;
}

export interface UserPrivilegeState {
  /** False when the Auth account no longer exists (orphan record). */
  exists: boolean;
  /** True when the activeMember claim OR the users/{uid}.activeMember flag is set. */
  holds: boolean;
}

export interface ReconcileDeps {
  providerEnabled: () => Promise<boolean>;
  readCursor: () => Promise<string>;
  writeCursor: (afterId: string) => Promise<void>;
  queryPage: (afterId: string, limit: number) => Promise<ReconcileCandidate[]>;
  userPrivilege: (uid: string) => Promise<UserPrivilegeState>;
  applyEntitlement: (input: EntitlementRecordInput) => Promise<void>;
}

export interface ReconcileResult {
  skipped: boolean;
  scanned: number;
  /** Accounts whose lingering privilege was cleared. */
  reconciledCount: number;
  /** Records already consistent with the account's privilege. */
  consistentCount: number;
  /** Granting records whose account lacked privilege (logged, not auto-fixed). */
  underPrivilegedCount: number;
  /** Records whose Auth account no longer exists. */
  orphanedCount: number;
  /** Records whose reconciliation threw and will be retried next run. */
  failedCount: number;
  reconciledUids: string[];
}

export async function runSubscriptionReconciliation(
  deps: ReconcileDeps,
): Promise<ReconcileResult> {
  const empty: ReconcileResult = {
    skipped: false,
    scanned: 0,
    reconciledCount: 0,
    consistentCount: 0,
    underPrivilegedCount: 0,
    orphanedCount: 0,
    failedCount: 0,
    reconciledUids: [],
  };

  if (!(await deps.providerEnabled())) {
    logger.info('Subscription reconciliation skipped; Google provider disabled');
    return { ...empty, skipped: true };
  }

  const cursor = await deps.readCursor();
  const page = await deps.queryPage(cursor, MAX_RECONCILE_PER_RUN);

  const reconciledUids: string[] = [];
  let consistentCount = 0;
  let underPrivilegedCount = 0;
  let orphanedCount = 0;
  let failedCount = 0;

  for (const { id, record } of page) {
    try {
      const privilege = await deps.userPrivilege(id);
      if (!privilege.exists) {
        // No Auth account → there is no claim to clear; the expiry sweep's
        // orphan close owns the record. Nothing to reconcile here.
        orphanedCount += 1;
        continue;
      }
      const decision = decideReconciliation(id, record, privilege.holds);
      if (decision.action === 'downgrade') {
        await deps.applyEntitlement(decision.input);
        reconciledUids.push(id);
        logger.info('Subscription reconciliation cleared stale member privilege', {
          uid: id,
          status: record.status,
          entitlement: record.entitlement,
        });
      } else if (decision.action === 'under_privileged') {
        underPrivilegedCount += 1;
        logger.warn('Subscription reconciliation found an under-privileged member', {
          uid: id,
          status: record.status,
          entitlement: record.entitlement,
        });
      } else {
        consistentCount += 1;
      }
    } catch (error) {
      failedCount += 1;
      logger.error('Subscription reconciliation failed for a record; will retry next run', {
        uid: id,
        error: String(error),
      });
    }
  }

  // A short page means we reached the end: wrap the cursor to the start.
  const wrapped = page.length < MAX_RECONCILE_PER_RUN;
  const last = page[page.length - 1];
  const nextCursor = wrapped || last === undefined ? '' : last.id;
  await deps.writeCursor(nextCursor);

  const result: ReconcileResult = {
    skipped: false,
    scanned: page.length,
    reconciledCount: reconciledUids.length,
    consistentCount,
    underPrivilegedCount,
    orphanedCount,
    failedCount,
    reconciledUids,
  };
  logger.info('Subscription reconciliation run complete', {
    scanned: result.scanned,
    reconciledCount: result.reconciledCount,
    consistentCount,
    underPrivilegedCount,
    orphanedCount,
    failedCount,
    wrapped,
  });
  return result;
}

// ---------------------------------------------------------------------------
// Production dependency wiring
// ---------------------------------------------------------------------------

function storedDate(value: unknown): Date | null {
  return value instanceof Timestamp ? value.toDate() : null;
}

function storedStatus(value: unknown): SubscriptionStatus {
  return (SUBSCRIPTION_STATUSES as readonly unknown[]).includes(value)
    ? (value as SubscriptionStatus)
    : 'inactive';
}

function storedEntitlement(value: unknown): SubscriptionEntitlement {
  return value === 'member_monthly' ? 'member_monthly' : 'none';
}

function storedTier(value: unknown): SubscriptionTier | undefined {
  return (SUBSCRIPTION_TIERS as readonly unknown[]).includes(value)
    ? (value as SubscriptionTier)
    : undefined;
}

function storedPlatform(value: unknown): SubscriptionPlatform {
  return (SUBSCRIPTION_PLATFORMS as readonly unknown[]).includes(value)
    ? (value as SubscriptionPlatform)
    : 'manual';
}

function toReconcileRecord(data: FirebaseFirestore.DocumentData): ReconcileRecord {
  return {
    status: storedStatus(data.status),
    entitlement: storedEntitlement(data.entitlement),
    tier: storedTier(data.tier),
    platform: storedPlatform(data.platform),
    purchaseTokenHash:
      typeof data.purchaseTokenHash === 'string' ? data.purchaseTokenHash : null,
    startsAt: storedDate(data.startsAt),
    expiresAt: storedDate(data.expiresAt),
  };
}

async function readCursorFromStore(): Promise<string> {
  const snap = await db.collection(RECONCILE_STATE_COLLECTION).doc(RECONCILE_CURSOR_DOC).get();
  const value = snap.data()?.afterId;
  return typeof value === 'string' ? value : '';
}

async function writeCursorToStore(afterId: string): Promise<void> {
  await db
    .collection(RECONCILE_STATE_COLLECTION)
    .doc(RECONCILE_CURSOR_DOC)
    .set({ afterId, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
}

async function queryPageFromStore(afterId: string, limit: number): Promise<ReconcileCandidate[]> {
  const snap = await db
    .collection('subscriptions')
    .orderBy(FieldPath.documentId())
    .startAfter(afterId)
    .limit(limit)
    .get();
  return snap.docs.map((doc) => ({ id: doc.id, record: toReconcileRecord(doc.data()) }));
}

async function userPrivilegeFromAuth(uid: string): Promise<UserPrivilegeState> {
  let claimActive = false;
  try {
    const user = await adminAuth.getUser(uid);
    claimActive = (user.customClaims as { activeMember?: unknown } | undefined)?.activeMember === true;
  } catch (error) {
    if ((error as { code?: string } | null)?.code === 'auth/user-not-found') {
      return { exists: false, holds: false };
    }
    throw error;
  }
  const userSnap = await db.collection('users').doc(uid).get();
  const flagActive = userSnap.data()?.activeMember === true;
  return { exists: true, holds: claimActive || flagActive };
}

export function productionReconcileDeps(): ReconcileDeps {
  return {
    providerEnabled: () => isSubscriptionProviderEnabled('google'),
    readCursor: readCursorFromStore,
    writeCursor: writeCursorToStore,
    queryPage: queryPageFromStore,
    userPrivilege: userPrivilegeFromAuth,
    applyEntitlement,
  };
}

/**
 * Every 6 hours. Correctness does not depend on the cadence (drift is rare and
 * the RTDN + expiry paths are the primary mechanisms); 6-hourly is a cheap
 * integrity backstop that drains the whole collection over a day at this page
 * size for a mid-sized member base.
 *
 * No Android Publisher service account: this sweep never calls Play (see
 * reconcile-core.ts) — it only touches Firestore and Auth.
 */
export const reconcileEntitlements = onSchedule(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_SCHEDULED,
    cpu: CPU_SCHEDULED,
    concurrency: 1,
    timeZone: 'Europe/Stockholm',
    memory: '256MiB' as const,
    timeoutSeconds: 540,
    schedule: 'every 6 hours',
  },
  async () => {
    await runSubscriptionReconciliation(productionReconcileDeps());
  },
);
