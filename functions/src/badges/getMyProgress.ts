/**
 * badges.getMyProgress — OWNER-ONLY callable (contracts/functions/functions.json).
 *
 * Deployed via the `badges` export group as `badges-getMyProgress`.
 *
 * Returns the signed-in member's OWN seven ladder counters, read from the
 * backend-only `badgeProgress/{uid}` document and projected through
 * `readBadgeCounters` (badge-tiers.ts). The uid is taken exclusively from
 * `request.auth.uid` — never from the payload — so a caller can only ever read
 * their own numbers; there is no argument that selects another member.
 *
 * WHY A CALLABLE AND NOT A RULE. The tiered ladders are measured against
 * server-verified counters on `badgeProgress/{uid}`, which `firebase/firestore
 * .rules` denies to EVERY client, the owner included (the doc is backend-only —
 * a client that could read it could also infer/spoof progress, and it carries
 * cross-domain telemetry the trophies deliberately don't). The profile wall
 * therefore knew a member's held rungs (from their public `users/{uid}/badges`
 * award docs) and each ladder's next threshold (from the static catalog), but
 * for five of the seven ladders it had no counter to draw a progress bar with —
 * only Vägfarare (folded drive distance) and Samlare (garage size) had a cheap
 * owner-scoped client stand-in. Issue #799.
 *
 * This callable closes that gap WITHOUT relaxing the rule: the Admin SDK (which
 * bypasses rules) reads the caller's own document server-side and hands back a
 * read-only projection of just the seven numeric counters. The document stays
 * backend-only, and nothing here exposes another member's counters — the
 * public/other-member wall (`PublicBadgeWall`) still receives no counters at
 * all, so progress bars remain an own-profile affordance.
 *
 * The projection is exactly `readBadgeCounters` so the client's bars are drawn
 * against the same sanitised, floored, non-negative numbers the awarding layer
 * qualifies tiers against — a missing document reads as all-zero counters, and a
 * corrupt field reads as 0 rather than a spurious bar.
 */

import { onCall } from 'firebase-functions/v2/https';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { readBadgeCounters, type BadgeCounters } from './badge-tiers';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

/**
 * The owner's own ladder counters. Keys are the seven `BadgeMetric` names, so
 * the payload maps 1:1 onto the client's `BadgeCounters` and each ladder's bar
 * reads the counter it is measured against.
 */
export type MyBadgeProgressResult = BadgeCounters;

export const getMyProgress = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_MEMBER,
    memory: '256MiB',
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<MyBadgeProgressResult> => {
    // Owner scope: the uid is the authenticated caller's, never a payload
    // argument, so this can only ever read the caller's own counters.
    const actor = await requireActiveActor(request);

    // Admin SDK read bypasses the rule that denies badgeProgress to every
    // client (owner included); a missing document projects to all-zero
    // counters, which renders as untouched bars rather than an error.
    const snap = await db.collection('badgeProgress').doc(actor.uid).get();
    return readBadgeCounters(snap.data());
  },
);
