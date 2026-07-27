/**
 * Account deletion domain — constants, purge plan, and validation
 * (Phase 9p).
 *
 * There is no legacy implementation to port (the legacy route was never
 * built); the workflow is designed from the repo docs:
 *
 * - backend-domain-mapping.md (users): "Account deletion: soft-delete
 *   `deleted: true` immediately; hard-delete after retention window
 *   (implementation-defined)". The window here is 30 DAYS — long enough
 *   for accidental-deletion recovery and admin review, GDPR Article 17
 *   compatible.
 * - `accountDeletionRequests/{uid}` (Phase 8 rules): the request record;
 *   status pending → processed once the purge has run.
 *
 * Two stages:
 * 1. account.deleteAccount (signedIn — works while suspended; deletion is
 *    support path): immediate soft delete. Auth user disabled, refresh
 *    tokens revoked, `users/{uid}.deleted: true`, request record written.
 *    Idempotent.
 * 2. Scheduled account-purgeDeleted: after the retention window, purge
 *    the user's data per PURGE_PLAN below and delete the Auth user;
 *    the request record flips to `processed` and is RETAINED as the
 *    proof-of-deletion record.
 *
 * A doc-tree purge only removes what the deleted user OWNS. Anything the
 * graph mirrors onto OTHER users' documents has to be swept separately, or
 * the deleted user survives as a dangling row in everyone else's data —
 * see PURGE_FRIEND_MIRROR / PURGE_FRIEND_REQUEST_USER_FIELDS /
 * PURGE_CONVOY_MEMBERSHIP below.
 *
 * What is deliberately retained (documented, not an oversight):
 * - moderationActions / adminAuditEvents / moderationReports — immutable
 *   moderation and audit history (legitimate-interest records).
 * - partnerInsightsEvents — partner-scoped hashes only, raw UIDs never
 *   stored, 7-day TTL; nothing to purge.
 * - crownHuntClaims — claim keys are SHA-256-scoped; awarded-claim
 *   records back the points audit trail. The ledger itself is purged.
 * - convoys/{id}.ownerUid on a convoy the deleted user OWNED — see
 *   PURGE_CONVOY_MEMBERSHIP. Every other trace of them is stripped from that
 *   document and the convoy is ended; the bare uid stays because it is the
 *   convoy's structural key (an ended convoy grants it nothing) and blanking it
 *   makes the Android parser discard the row, taking the surviving members'
 *   record of their own drive with it.
 * - EVENT chat messages and RSVPs — community-context records with
 *   denormalized author names; scrubbing them is the blocking domain's
 *   listed follow-up and is out of the 30-day purge's scope for MVP.
 *   (NOTE: community, convoy, and 1:1 DM chat ARE purged — see the chat
 *   erasure step in scheduled.ts; only EVENT chat is retained here.)
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { z } from 'zod';

/** Implementation-defined hard-delete window (mapping leaves it open). */
export const DELETION_RETENTION_DAYS = 30;

export const MAX_DELETION_REASON_LENGTH = 500;

const deleteAccountInputSchema = z
  .object({
    reason: z.string().trim().min(1).max(MAX_DELETION_REASON_LENGTH).optional(),
  })
  .strict();

export type DeleteAccountInput = z.infer<typeof deleteAccountInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export function parseDeleteAccountInput(data: unknown): ParseResult<DeleteAccountInput> {
  const result = deleteAccountInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: 'Expected { reason?: string }.' };
  }
  return { ok: true, input: result.data };
}

/**
 * The purge plan, in execution order.
 * - `docTree`: delete the document and every subcollection beneath it
 *   (users/{uid} + badges, userPrivate/{uid} + pushTokens,
 *   userLifecycle/{uid} (last-login + inactivity lifecycle fields),
 *   notifications/{uid} + items, pointsLedger/{uid} + entries).
 * - `ownedQuery`: delete documents matching userId == uid.
 * - Storage prefixes are removed wholesale.
 */
export const PURGE_DOC_TREES = [
  'users',
  'userPrivate',
  // Backend-only last-login + inactivity state (auth.recordLogin /
  // account-cleanupInactive). Must be purged on ALL deletion paths so no
  // per-user lifecycle data is retained after erasure.
  'userLifecycle',
  'notifications',
  'pointsLedger',
] as const;

export const PURGE_OWNED_COLLECTIONS: ReadonlyArray<{
  collection: string;
  userField: string;
}> = [
  { collection: 'vehicles', userField: 'userId' },
  { collection: 'rides', userField: 'userId' },
];

/**
 * MIRROR side of the friend graph. A friendship is stored twice —
 * `users/{a}/friends/{b}` AND `users/{b}/friends/{a}` — each row carrying the
 * OTHER party's denormalized displayName/avatarPath. Purging the deleted
 * user's `users/{uid}` tree removes only THEIR half; every remaining friend
 * keeps a row naming the deleted user, which friend.list still returns and the
 * Friends screen still renders.
 *
 * The sweep is a COLLECTION-GROUP query on the `friends` subcollection filtered
 * by `friendUid` — deliberately not "read the deleted user's own friend list
 * first, then delete its counterparts". The doc-tree purge runs first and is
 * retried after a partial failure, so on a retry that own-side list is already
 * gone and the mirrors would be orphaned forever. Keying off the mirror rows
 * themselves makes the sweep order-independent and idempotent. A single
 * equality filter with no orderBy is served by Firestore's AUTOMATIC
 * single-field index (which has collection-group scope) — no composite index.
 */
export const PURGE_FRIEND_MIRROR = {
  collectionGroup: 'friends',
  friendField: 'friendUid',
} as const;

/**
 * `friendRequests/{requestId}` is a top-level collection keyed by the PAIR, so
 * it is owned by neither side and survives both users' doc-tree purges. A
 * pending request would keep showing in the other party's incoming/outgoing
 * lists (friend.list's readFriendGraph) forever. Swept in BOTH directions.
 *
 * Each direction is one equality filter with no orderBy → covered by the
 * automatic single-field indexes; the composite indexes in
 * firebase/firestore.indexes.json exist for friend.list's
 * (uid + status + createdAt) reads and are not needed here.
 */
export const PURGE_FRIEND_REQUEST_USER_FIELDS = ['fromUid', 'toUid'] as const;

/**
 * Convoy membership is denormalized onto the convoy document (`memberUids`
 * array + `members`/`memberProfiles` maps, the latter holding displayName and
 * avatarPath), so it is likewise unreachable from the deleted user's own tree.
 * `memberUids array-contains` with no orderBy is covered by the automatic
 * single-field index (the composite entries exist for convoy.list's
 * membership+createdAt / membership+status reads).
 *
 * The same sweep scrubs the two references that sit OUTSIDE those maps:
 * `summary.participantUids` (written by convoy.end from the membership as it
 * stood then, so stripping the maps alone leaves the uid there) and
 * `destination.setByDisplayName` (a denormalized display name, exactly like
 * memberProfiles'). `destination.setByUid` and `ownerUid` are retained — see
 * the retained list at the top of this file and removeConvoyMemberships.
 */
export const PURGE_CONVOY_MEMBERSHIP = {
  collection: 'convoys',
  memberField: 'memberUids',
} as const;

export const PURGE_STORAGE_PREFIXES = (uid: string): string[] => [
  `profileImages/${uid}/`,
  `vehicleImages/${uid}/`,
  `rideRoutes/${uid}/`,
];

const DAY_MS = 24 * 60 * 60 * 1000;

/** Pending requests created before this instant are purged. */
export function deletionRetentionCutoff(now: Date): Date {
  return new Date(now.getTime() - DELETION_RETENTION_DAYS * DAY_MS);
}
