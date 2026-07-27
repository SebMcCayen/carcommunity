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
 * What is deliberately retained (documented, not an oversight):
 * - moderationActions / adminAuditEvents / moderationReports — immutable
 *   moderation and audit history (legitimate-interest records).
 * - partnerInsightsEvents — partner-scoped hashes only, raw UIDs never
 *   stored, 7-day TTL; nothing to purge.
 * - crownHuntClaims — claim keys are SHA-256-scoped; awarded-claim
 *   records back the points audit trail. The ledger itself is purged.
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
  // Backend-only achievement counters (functions/src/badges). Same rule as
  // userLifecycle: this document holds per-user activity — crowns collected,
  // lifetime distance driven, events attended, convoys led and the local-day
  // key of the member's last app open — so it must not survive erasure.
  'badgeProgress',
] as const;

export const PURGE_OWNED_COLLECTIONS: ReadonlyArray<{
  collection: string;
  userField: string;
}> = [
  { collection: 'vehicles', userField: 'userId' },
  { collection: 'rides', userField: 'userId' },
];

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
