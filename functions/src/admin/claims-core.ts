/**
 * Admin domain — pure input validation, guard logic, custom-claim
 * computation, and audit/moderation record builders.
 *
 * Semantics ported from services/api/src/lib/moderation-service.ts and
 * packages/shared/src/users.ts:
 * - Backend is the source of truth; no client self-elevation paths.
 * - Admins cannot moderate owner accounts; only owners can.
 * - Every admin action produces an immutable audit record with actor UID,
 *   target UID, action type, reason, and a server timestamp.
 *
 * No Firebase Admin SDK imports — the server-timestamp sentinel is injected
 * so this module stays unit-testable without emulators.
 */

import { z } from 'zod';
import { canAccessAdminFeatures, isOwnerRole, type UserAccessState } from '../shared/access';

export const MODERATION_REASON_MAX_LENGTH = 500;

const uidSchema = z.string().trim().min(1).max(128);
const reasonSchema = z.string().trim().min(1).max(MODERATION_REASON_MAX_LENGTH);

const setAdminRoleInputSchema = z
  .object({
    targetUid: uidSchema,
    admin: z.boolean(),
    reason: reasonSchema,
  })
  .strict();

const moderationInputSchema = z
  .object({
    targetUid: uidSchema,
    reason: reasonSchema,
  })
  .strict();

export type SetAdminRoleInput = z.infer<typeof setAdminRoleInputSchema>;
export type ModerationInput = z.infer<typeof moderationInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export function parseSetAdminRoleInput(data: unknown): ParseResult<SetAdminRoleInput> {
  const result = setAdminRoleInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message: 'Expected { targetUid: string, admin: boolean, reason: string }.',
    };
  }
  return { ok: true, input: result.data };
}

export function parseModerationInput(data: unknown): ParseResult<ModerationInput> {
  const result = moderationInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: 'Expected { targetUid: string, reason: string }.' };
  }
  return { ok: true, input: result.data };
}

// ---------------------------------------------------------------------------
// Guards
// ---------------------------------------------------------------------------

/**
 * Outcome of an authorization guard. `code` values come from
 * contracts/errors/errors.json.
 */
export type GuardResult =
  | { ok: true }
  | { ok: false; code: 'permission-denied' | 'failed-precondition'; message: string };

/**
 * The actor must be a non-suspended, non-deleted admin or owner according to
 * the authoritative Firestore user document — never a client-supplied value.
 */
export function guardActorIsActiveAdmin(actor: UserAccessState): GuardResult {
  if (!canAccessAdminFeatures(actor)) {
    return {
      ok: false,
      code: 'permission-denied',
      message: 'Admin privileges are required for this operation.',
    };
  }
  return { ok: true };
}

/**
 * Guard for admin.setAdminRole:
 * - No self-elevation or self-demotion: callers can never change their own
 *   role (prevents both privilege escalation and admin lock-out mistakes).
 * - The owner role is managed out-of-band; this callable never grants,
 *   revokes, or overwrites it.
 */
export function guardSetAdminRole(input: {
  actorUid: string;
  targetUid: string;
  targetRole: UserAccessState['role'];
}): GuardResult {
  if (input.actorUid === input.targetUid) {
    return {
      ok: false,
      code: 'failed-precondition',
      message: 'You cannot change your own admin role.',
    };
  }
  if (isOwnerRole(input.targetRole)) {
    return {
      ok: false,
      code: 'failed-precondition',
      message: 'Owner accounts cannot be modified by this operation.',
    };
  }
  return { ok: true };
}

/**
 * Guard for moderation actions (suspend/restore):
 * - Actors cannot moderate themselves.
 * - Admins cannot moderate owner accounts; only owners can
 *   (ported from services/api/src/lib/moderation-service.ts resolveTarget).
 */
export function guardModerationTarget(input: {
  actorUid: string;
  actorRole: UserAccessState['role'];
  targetUid: string;
  targetRole: UserAccessState['role'];
}): GuardResult {
  if (input.actorUid === input.targetUid) {
    return {
      ok: false,
      code: 'failed-precondition',
      message: 'You cannot moderate your own account.',
    };
  }
  if (isOwnerRole(input.targetRole) && !isOwnerRole(input.actorRole)) {
    return {
      ok: false,
      code: 'permission-denied',
      message: 'Admin users cannot moderate owner accounts.',
    };
  }
  return { ok: true };
}

// ---------------------------------------------------------------------------
// Custom-claim computation
// ---------------------------------------------------------------------------

/**
 * Computes the replacement custom-claims object for
 * `auth.setCustomUserClaims()` (which overwrites all claims, so unrelated
 * existing claims must be carried over).
 *
 * Claims are removed rather than set to `false` to keep ID tokens small;
 * Security Rules use `!= true` comparisons so an absent claim is equivalent.
 */
export function computeUpdatedClaims(
  existing: Record<string, unknown> | undefined,
  updates: Partial<Record<'admin' | 'suspended' | 'activeMember', boolean>>,
): Record<string, unknown> {
  const next: Record<string, unknown> = { ...existing };
  for (const key of ['admin', 'suspended', 'activeMember'] as const) {
    const value = updates[key];
    if (value === undefined) continue;
    if (value) {
      next[key] = true;
    } else {
      delete next[key];
    }
  }
  return next;
}

// ---------------------------------------------------------------------------
// Record builders (adminAuditEvents / moderationActions)
// ---------------------------------------------------------------------------

/** Action types recorded in moderationActions (packages/shared/src/users.ts). */
export type ModerationActionType =
  | 'warning'
  | 'temporary_suspension'
  | 'permanent_suspension'
  | 'restriction'
  | 'restore_access';

/**
 * Immutable audit record for `adminAuditEvents/{eventId}`
 * (docs/firebase-data-model.md). Written by the Admin SDK only; Security
 * Rules deny all client writes.
 */
export function buildAdminAuditEvent(
  input: {
    adminId: string;
    action: string;
    targetType: string;
    targetId: string;
    reason: string;
    details?: Record<string, unknown>;
  },
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    adminId: input.adminId,
    action: input.action,
    targetType: input.targetType,
    targetId: input.targetId,
    reason: input.reason,
    ...(input.details ? { details: input.details } : {}),
    createdAt: serverTimestamp(),
  };
}

/**
 * Immutable moderation record for `moderationActions/{actionId}`
 * (docs/migration/backend-domain-mapping.md). Written by the Admin SDK only.
 */
export function buildModerationAction(
  input: {
    targetUserId: string;
    actorUserId: string;
    actionType: ModerationActionType;
    reason: string;
  },
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    targetUserId: input.targetUserId,
    actorUserId: input.actorUserId,
    actionType: input.actionType,
    reason: input.reason,
    expiresAt: null,
    createdAt: serverTimestamp(),
  };
}
