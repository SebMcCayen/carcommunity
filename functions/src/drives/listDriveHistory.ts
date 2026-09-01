/**
 * drives.listHistory — server-authoritative saved-drive history visibility.
 *
 * This is the backwards-compatible first migration slice: new clients can use
 * this bounded callable while already-released clients keep their owner query.
 * Direct Firestore/Storage reads are closed only after the migrated Android
 * build has reached testers. That later rules change is what makes bypassing
 * this API impossible; until then this callable establishes and tests the
 * authoritative policy without breaking Closed Testing installs.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import {
  FieldPath,
  Timestamp,
  type DocumentData,
  type DocumentSnapshot,
  type Query,
} from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import {
  effectiveSubscriptionTierFromStoredRecord,
  type SubscriptionTier,
} from '../subscription/subscription-core';
import {
  driveHistoryPageSize,
  driveHistoryPolicyForTier,
  driveHistoryReadLimit,
  parseListDriveHistoryInput,
  type DriveHistoryPolicy,
} from './driveHistory-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface DriveHistoryItem {
  rideId: string;
  title: string | null;
  distanceMeters: number | null;
  durationSeconds: number;
  averageSpeedMetersPerSecond: number | null;
  maxSpeedMetersPerSecond: number | null;
  startedAtMillis: number | null;
  endedAtMillis: number | null;
  createdAtMillis: number;
  routeThumbnail: string | null;
  carImagePath: string | null;
  convoyMembers: Array<{ uid: string; displayName?: string; avatarPath?: string }>;
}

export interface ListDriveHistoryResponse {
  tier: SubscriptionTier;
  policy:
    | { kind: 'latest_count'; limit: number }
    | { kind: 'rolling_days'; days: number }
    | { kind: 'unlimited' };
  drives: DriveHistoryItem[];
  /** Server time used to resolve the rolling window; clients must not use device time. */
  serverNowMillis: number;
  /** Plus cutoff (inclusive), otherwise null. */
  windowStartsAtMillis: number | null;
  hasMore: boolean;
  /** Non-null only when another page is available within the caller's tier. */
  nextCursorRideId: string | null;
  /** First-page signal that at least one older drive is retained but hidden. */
  hasTierRestrictedHistory: boolean | null;
  /** Exact retained-but-hidden count on the first page; null on later pages. */
  hiddenDriveCount: number | null;
}

function nullableNonnegativeNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;
}

function timestampMillis(value: unknown): number | null {
  return value instanceof Timestamp ? value.toMillis() : null;
}

function boundedText(value: unknown, maxLength: number): string | null {
  return typeof value === 'string' && value.length > 0 && value.length <= maxLength ? value : null;
}

function sanitiseConvoyMembers(
  value: unknown,
): Array<{ uid: string; displayName?: string; avatarPath?: string }> {
  if (!Array.isArray(value)) return [];
  const result: Array<{ uid: string; displayName?: string; avatarPath?: string }> = [];
  const seen = new Set<string>();
  for (const raw of value) {
    if (result.length >= 24) break;
    if (raw == null || typeof raw !== 'object' || Array.isArray(raw)) continue;
    const candidate = raw as Record<string, unknown>;
    const uid = boundedText(candidate.uid, 128);
    if (!uid || seen.has(uid)) continue;
    seen.add(uid);
    const displayName = boundedText(candidate.displayName, 200);
    const avatarPath = boundedText(candidate.avatarPath, 500);
    result.push({
      uid,
      ...(displayName ? { displayName } : {}),
      ...(avatarPath ? { avatarPath } : {}),
    });
  }
  return result;
}

function toDriveHistoryItem(doc: DocumentSnapshot<DocumentData>): DriveHistoryItem | null {
  const data = doc.data();
  if (
    !data ||
    typeof data.durationSeconds !== 'number' ||
    !Number.isSafeInteger(data.durationSeconds) ||
    data.durationSeconds < 0
  ) {
    return null;
  }
  const createdAtMillis = timestampMillis(data.createdAt);
  if (createdAtMillis == null) return null;
  return {
    rideId: doc.id,
    title: boundedText(data.title, 200),
    distanceMeters: nullableNonnegativeNumber(data.distanceMeters),
    durationSeconds: data.durationSeconds,
    averageSpeedMetersPerSecond: nullableNonnegativeNumber(data.averageSpeedMetersPerSecond),
    maxSpeedMetersPerSecond: nullableNonnegativeNumber(data.maxSpeedMetersPerSecond),
    startedAtMillis: timestampMillis(data.startedAt),
    endedAtMillis: timestampMillis(data.endedAt),
    createdAtMillis,
    routeThumbnail: boundedText(data.routeThumbnail, 1_000),
    carImagePath: boundedText(data.carImagePath, 500),
    convoyMembers: sanitiseConvoyMembers(data.convoyMembers),
  };
}

function publicPolicy(policy: DriveHistoryPolicy): ListDriveHistoryResponse['policy'] {
  if (policy.kind === 'latest_count') return { kind: policy.kind, limit: policy.limit };
  if (policy.kind === 'rolling_days') return { kind: policy.kind, days: policy.days };
  return { kind: policy.kind };
}

export const listDriveHistory = onCall(
  CALLABLE_OPTS,
  async (request): Promise<ListDriveHistoryResponse> => {
    const actor = await requireActiveActor(request);
    const parsed = parseListDriveHistoryInput(request.data);
    if (!parsed.ok) throw new HttpsError('invalid-argument', parsed.message);

    const subscriptionSnap = await db.collection('subscriptions').doc(actor.uid).get();
    const tier = effectiveSubscriptionTierFromStoredRecord(
      subscriptionSnap.exists ? subscriptionSnap.data() : null,
      actor.uid,
    );
    const serverNowMillis = Date.now();
    const policy = driveHistoryPolicyForTier(tier, serverNowMillis);
    if (policy.kind === 'latest_count' && parsed.input.cursorRideId) {
      throw new HttpsError('invalid-argument', 'Community drive history does not support paging.');
    }

    const ownerQuery = db.collection('rides').where('userId', '==', actor.uid);
    let query: Query<DocumentData> = ownerQuery
      .orderBy('createdAt', 'desc')
      .orderBy(FieldPath.documentId(), 'desc');
    if (policy.kind === 'rolling_days') {
      query = query.where('createdAt', '>=', Timestamp.fromMillis(policy.cutoffMillis));
    }

    let hiddenDriveCount: number | null = null;
    if (!parsed.input.cursorRideId) {
      if (policy.kind === 'latest_count') {
        const total = (await ownerQuery.count().get()).data().count;
        hiddenDriveCount = Math.max(0, total - policy.limit);
      } else if (policy.kind === 'rolling_days') {
        const [total, visible] = await Promise.all([ownerQuery.count().get(), query.count().get()]);
        hiddenDriveCount = Math.max(0, total.data().count - visible.data().count);
      } else {
        hiddenDriveCount = 0;
      }
    }

    if (parsed.input.cursorRideId) {
      const cursor = await db.collection('rides').doc(parsed.input.cursorRideId).get();
      if (
        !cursor.exists ||
        cursor.data()?.userId !== actor.uid ||
        timestampMillis(cursor.data()?.createdAt) == null
      ) {
        // Do not reveal whether a cursor names another user's private drive.
        throw new HttpsError('not-found', 'Drive history cursor not found.');
      }
      if (
        policy.kind === 'rolling_days' &&
        timestampMillis(cursor.data()?.createdAt)! < policy.cutoffMillis
      ) {
        throw new HttpsError(
          'invalid-argument',
          'Drive history cursor is outside the Plus window.',
        );
      }
      query = query.startAfter(cursor);
    }

    const pageSize = driveHistoryPageSize(policy, parsed.input.pageSize);
    const snapshot = await query.limit(driveHistoryReadLimit(policy, pageSize)).get();
    const hasMoreWithinPolicy = policy.kind !== 'latest_count' && snapshot.size > pageSize;
    const visibleDocs = snapshot.docs.slice(0, pageSize);
    const drives = visibleDocs
      .map(toDriveHistoryItem)
      .filter((drive): drive is DriveHistoryItem => drive != null);

    return {
      tier,
      policy: publicPolicy(policy),
      drives,
      serverNowMillis,
      windowStartsAtMillis: policy.kind === 'rolling_days' ? policy.cutoffMillis : null,
      hasMore: hasMoreWithinPolicy,
      nextCursorRideId:
        hasMoreWithinPolicy && visibleDocs.length > 0
          ? visibleDocs[visibleDocs.length - 1]!.id
          : null,
      hasTierRestrictedHistory: hiddenDriveCount == null ? null : hiddenDriveCount > 0,
      hiddenDriveCount,
    };
  },
);
