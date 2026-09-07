/**
 * Group driving domain — constants, pure guards, and builders (Phase 11).
 *
 * Ports packages/shared/src/group-drive.ts and the legacy
 * group-drive-service semantics to the Firestore model:
 *
 * - `events/{eventId}/groupDriveParticipants/{uid}` — document ID = the
 *   participant's UID (mapping collection-design table). Member-readable
 *   for published events (like event details); ALL writes via the
 *   groupDrive.* callables so the join preconditions are enforced
 *   server-side.
 * - Join requires (legacy canJoinEventGroupDrive): active member, event
 *   published, RSVP going|maybe, event not ended. Joining is idempotent;
 *   a left participant REJOINS (joinedAt reset, leftAt cleared).
 * - updateStatus accepts joined|on_the_way|arrived (never `left` — leave
 *   is its own idempotent operation and does NOT stop the live location
 *   session, legacy parity).
 * - Participant map markers remain the live-location domain
 *   (liveLocation/{uid}/latest); this collection is the roster.
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { z } from 'zod';

export const GROUP_DRIVE_PARTICIPANT_STATUSES = [
  'joined',
  'on_the_way',
  'arrived',
  'left',
] as const;
export type GroupDriveParticipantStatus = (typeof GROUP_DRIVE_PARTICIPANT_STATUSES)[number];

/** `left` is set by groupDrive.leave, never by updateStatus (legacy). */
export const GROUP_DRIVE_UPDATABLE_STATUSES = ['joined', 'on_the_way', 'arrived'] as const;
export type GroupDriveUpdatableStatus = (typeof GROUP_DRIVE_UPDATABLE_STATUSES)[number];

const firestoreIdSchema = z
  .string()
  .trim()
  .min(1)
  .max(300)
  .regex(/^[A-Za-z0-9._-]+$/)
  .refine((id) => id !== '.' && id !== '..');

const joinInputSchema = z.object({ eventId: firestoreIdSchema }).strict();
const updateStatusInputSchema = z
  .object({
    eventId: firestoreIdSchema,
    status: z.enum(GROUP_DRIVE_UPDATABLE_STATUSES),
  })
  .strict();
const leaveInputSchema = z.object({ eventId: firestoreIdSchema }).strict();

export type JoinGroupDriveInput = z.infer<typeof joinInputSchema>;
export type UpdateDriveStatusInput = z.infer<typeof updateStatusInputSchema>;
export type LeaveGroupDriveInput = z.infer<typeof leaveInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export const parseJoinGroupDriveInput = (d: unknown) =>
  parse(joinInputSchema, d, 'Expected { eventId }.');
export const parseUpdateDriveStatusInput = (d: unknown) =>
  parse(
    updateStatusInputSchema,
    d,
    `Expected { eventId, status: ${GROUP_DRIVE_UPDATABLE_STATUSES.join('|')} }.`,
  );
export const parseLeaveGroupDriveInput = (d: unknown) =>
  parse(leaveInputSchema, d, 'Expected { eventId }.');

// ---------------------------------------------------------------------------
// Guards (legacy canJoinEventGroupDrive, decomposed — the member check is
// requireActiveActor's job)
// ---------------------------------------------------------------------------

export type GuardResult =
  | { ok: true }
  | { ok: false; code: 'failed-precondition' | 'permission-denied'; message: string };

export function guardJoinableEvent(input: {
  eventStatus: string;
  endsAt: Date | null;
  rsvpStatus: string | null;
  now: Date;
}): GuardResult {
  if (input.eventStatus !== 'published') {
    return {
      ok: false,
      code: 'failed-precondition',
      message: 'Event is not eligible for group driving.',
    };
  }
  if (input.rsvpStatus !== 'going' && input.rsvpStatus !== 'maybe') {
    return {
      ok: false,
      code: 'permission-denied',
      message: 'RSVP going or maybe required to join group drive.',
    };
  }
  if (input.endsAt !== null && input.endsAt <= input.now) {
    return { ok: false, code: 'failed-precondition', message: 'Event has ended.' };
  }
  return { ok: true };
}

/** groupDriveParticipants/{uid} document. */
export function buildParticipantDocument(
  displayName: string | null,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    displayName,
    status: 'joined',
    joinedAt: serverTimestamp(),
    leftAt: null,
    updatedAt: serverTimestamp(),
  };
}
