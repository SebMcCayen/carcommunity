/**
 * badges.adminSummary — admin callable (contracts/functions/functions.json).
 *
 * Deployed via the `badges` export group as `badges-adminSummary`.
 *
 * Returns aggregate badge award counts per catalog key (total + last-30-day
 * "recent"). Ports the legacy SQL groupBy (services/api badge-service
 * getAdminBadgeSummary), which has no Firestore equivalent because badge
 * awards live at users/{uid}/badges/{badgeKey} — a per-user, owner-only
 * subcollection that is NOT admin-readable via client rules (Phase 9f: badges
 * are strictly owner-only, "not even admin clients"). So the aggregate is
 * computed server-side with the Admin SDK (which bypasses rules) over a
 * collectionGroup scan; no individual user data ever leaves this function —
 * only per-key counts.
 */

import { onCall } from 'firebase-functions/v2/https';
import type { Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminBadgeSummary, type AdminBadgeAggregateItem } from './badge-core';

export interface AdminBadgeSummaryResult {
  summary: AdminBadgeAggregateItem[];
}

export const adminSummary = onCall(
  {
    region: 'europe-west1',
    memory: '256MiB',
    timeoutSeconds: 60,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<AdminBadgeSummaryResult> => {
    await requireAdminActor(request);

    // Admin SDK collectionGroup scan bypasses security rules — badges stay
    // owner-only for clients. Only aggregate counts are returned; project to
    // just the two fields the aggregation needs (not the full badge doc).
    const snapshot = await db.collectionGroup('badges').select('badgeKey', 'awardedAt').get();
    const awards = snapshot.docs.map((doc) => {
      const data = doc.data();
      const awardedAt = data.awardedAt as Timestamp | undefined;
      return {
        badgeKey: (data.badgeKey as string | undefined) ?? doc.id,
        awardedAtMillis: awardedAt ? awardedAt.toMillis() : null,
      };
    });

    return { summary: buildAdminBadgeSummary(awards, Date.now()) };
  },
);
