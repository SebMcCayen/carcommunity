/**
 * Admin notification batch-send core (pure logic): input parsing, audience
 * constants, and the validation guards for notifications.adminSend.
 *
 * Ports services/api notification-delivery-service.deliverToAudience. Kept
 * Firebase-free so parsing, the confirmation guard, and per-audience field
 * requirements are unit-testable without the emulator.
 */

import { z } from 'zod';
import {
  MAX_NOTIFICATION_BODY_LENGTH,
  MAX_NOTIFICATION_PREVIEW_LENGTH,
  MAX_NOTIFICATION_TITLE_LENGTH,
  NOTIFICATION_ACTION_TYPES,
  NOTIFICATION_CATEGORIES,
} from './notifications-core';

/** Admin send audiences (mirrors packages/shared ADMIN_NOTIFICATION_AUDIENCES). */
export const ADMIN_NOTIFICATION_AUDIENCES = [
  'all_users',
  'free_users',
  'members',
  'event_participants',
  'specific_user',
  'admins',
] as const;
export type AdminNotificationAudience = (typeof ADMIN_NOTIFICATION_AUDIENCES)[number];

/** Synchronous fan-out cap (legacy MAX_SYNC_AUDIENCE_SIZE). */
export const MAX_SYNC_AUDIENCE_SIZE = 500;

/** Broad audiences that require an explicit confirmation flag. */
export const CONFIRMATION_REQUIRED_AUDIENCES: readonly AdminNotificationAudience[] = [
  'all_users',
  'free_users',
];

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

const adminSendSchema = z
  .object({
    category: z.enum(NOTIFICATION_CATEGORIES),
    audience: z.enum(ADMIN_NOTIFICATION_AUDIENCES),
    title: z.string().trim().min(1).max(MAX_NOTIFICATION_TITLE_LENGTH),
    previewText: z.string().trim().min(1).max(MAX_NOTIFICATION_PREVIEW_LENGTH),
    body: z.string().trim().min(1).max(MAX_NOTIFICATION_BODY_LENGTH),
    actionType: z.enum(NOTIFICATION_ACTION_TYPES).optional(),
    eventId: z.string().trim().min(1).max(128).optional(),
    targetUserId: z.string().trim().min(1).max(128).optional(),
    relatedEntityId: z.string().trim().min(1).max(256).optional(),
    reason: z.string().trim().min(1).max(2000),
    idempotencyKey: z.string().trim().min(1).max(200),
    confirmed: z.boolean().optional(),
  })
  .strict();

export type AdminSendInput = z.infer<typeof adminSendSchema>;

export function parseAdminSendInput(data: unknown): ParseResult<AdminSendInput> {
  const result = adminSendSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message:
        'Expected { category, audience, title, previewText, body, reason, idempotencyKey, actionType?, eventId?, targetUserId?, relatedEntityId?, confirmed? }.',
    };
  }
  return { ok: true, input: result.data };
}

export function requiresConfirmation(audience: AdminNotificationAudience): boolean {
  return CONFIRMATION_REQUIRED_AUDIENCES.includes(audience);
}

/**
 * Cross-field checks that a schema can't express: audience-specific required
 * ids and the confirmation flag for broad sends. Failures carry an explicit
 * `kind` so the callable maps the error code without brittle string matching.
 */
export type AudienceValidation =
  | { ok: true; input: AdminSendInput }
  | { ok: false; message: string; kind: 'missing_field' | 'confirmation_required' };

export function validateAudienceRequirements(input: AdminSendInput): AudienceValidation {
  if (input.audience === 'event_participants' && !input.eventId) {
    return {
      ok: false,
      message: 'eventId is required for the event_participants audience.',
      kind: 'missing_field',
    };
  }
  if (input.audience === 'specific_user' && !input.targetUserId) {
    return {
      ok: false,
      message: 'targetUserId is required for the specific_user audience.',
      kind: 'missing_field',
    };
  }
  if (requiresConfirmation(input.audience) && input.confirmed !== true) {
    return {
      ok: false,
      message: `Explicit confirmation is required for the ${input.audience} audience.`,
      kind: 'confirmation_required',
    };
  }
  return { ok: true, input };
}
