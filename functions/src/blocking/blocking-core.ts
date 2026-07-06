/**
 * Blocking domain core (pure logic): input parsing and document/summary
 * builders for the blocking.block / blocking.unblock callables.
 *
 * Ports services/api/src/lib/blocking-service.ts to Firestore. The Firestore
 * model is userBlocks/{blockerUid}/blocked/{blockedUid} — a per-owner
 * subcollection, owner-readable, backend-only writes. Blocks are directional
 * and idempotent; self-blocking is rejected. Kept Firebase-free so it is
 * unit-testable without the emulator.
 */

import { z } from 'zod';

/**
 * Minimal safe summary of a blocked user (mirrors
 * packages/shared/src/blocking.ts BlockedUserSummary — the functions package
 * is standalone and does not depend on @carcommunity/shared, so the contract
 * shape is re-declared here). Never includes email, provider identity,
 * subscription, or other sensitive fields.
 */
export interface BlockedUserSummary {
  userId: string;
  displayName?: string | null;
  blockedAt: string;
}

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

/**
 * Firebase Auth UIDs (and the equivalent target ids) are opaque, non-empty,
 * bounded strings. We intentionally do not constrain the character set beyond
 * a length bound — existence is validated against users/{uid} at call time.
 */
const blockTargetSchema = z
  .object({
    targetUserId: z.string().trim().min(1).max(128),
  })
  .strict();

export type BlockTargetInput = z.infer<typeof blockTargetSchema>;

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

const EXPECTED = 'Expected { targetUserId } (a non-empty user id).';

export function parseBlockInput(data: unknown): ParseResult<BlockTargetInput> {
  return parse(blockTargetSchema, data, EXPECTED);
}

export function parseUnblockInput(data: unknown): ParseResult<BlockTargetInput> {
  return parse(blockTargetSchema, data, EXPECTED);
}

/** Message for a self-block attempt (invalid-argument). */
export const SELF_BLOCK_MESSAGE = 'You cannot block yourself.';

/** userBlocks/{uid}/blocked/{targetUid} document — displayName denormalized for list rendering. */
export function buildBlockDocument(
  blockedUserId: string,
  displayName: string | null,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    blockedUserId,
    displayName: displayName ?? null,
    createdAt: serverTimestamp(),
  };
}

/** Maps a stored block into the minimal safe summary contract. */
export function toBlockedUserSummary(
  blockedUserId: string,
  displayName: string | null | undefined,
  blockedAtIso: string,
): BlockedUserSummary {
  return {
    userId: blockedUserId,
    displayName: displayName ?? null,
    blockedAt: blockedAtIso,
  };
}
