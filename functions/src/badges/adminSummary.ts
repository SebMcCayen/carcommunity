/**
 * badges.adminSummary — admin callable (contracts/functions/functions.json).
 *
 * Deployed via the `badges` export group as `badges-adminSummary`.
 *
 * Returns aggregate badge award counts per catalog key (total + last-30-day
 * "recent"). Ports the legacy SQL groupBy (services/api badge-service
 * getAdminBadgeSummary), which has no Firestore equivalent because badge
 * awards live at users/{uid}/badges/{badgeKey} — a per-user subcollection with
 * no cross-user aggregate.
 *
 * Client rules let any authenticated user READ a given member's wall (badges
 * are public — firebase/firestore.rules), yet no client can run the
 * collectionGroup scan this summary needs. Those two facts coexist because
 * Firestore authorises a collection group query ONLY from a rule written
 * against a recursive-wildcard path (`match /{path=**}/badges/{badgeKey}`): the
 * public-read grant is nested under `/users/{userId}`, which never applies to a
 * collection group query, and the single recursive-wildcard rule in
 * firestore.rules is the deny-all catch-all. Public read is therefore
 * per-member, not a global index.
 *
 * That is asserted, not just asserted-in-prose — emulator test "no client can
 * scan badges across members — collectionGroup is denied"
 * (functions/src/__tests__/security-rules.emulator.test.ts) runs the actual
 * collectionGroup('badges') query as a viewer, an activeMember and an admin and
 * requires permission-denied from all three, having first run the same query
 * with rules disabled to prove the query is valid and the data is there. Adding
 * a `/{path=**}/badges/…` grant later therefore fails the build rather than
 * silently opening cross-member enumeration.
 *
 * So the aggregate is computed server-side with the Admin SDK (which bypasses
 * rules) over a collectionGroup scan; no individual user data ever leaves this
 * function — only per-key counts.
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

    // Admin SDK collectionGroup scan bypasses security rules. A client cannot
    // reach this data the same way even though badge documents are publicly
    // readable: the public grant lives at /users/{userId}/badges/{badgeKey},
    // and a rule nested under a concrete ancestor never authorises a collection
    // group query — only a /{path=**}/badges/… rule would, and firestore.rules
    // has none (see the file header, and the emulator test that pins it). So
    // clients read one member's wall at a time and never scan them all.
    // Only aggregate counts are returned; project to just the two fields the
    // aggregation needs (not the full badge doc).
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
