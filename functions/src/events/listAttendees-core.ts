/**
 * events.listAttendees — pure roster assembly (join + block filter + grouping).
 *
 * The callable (listAttendees.ts) does the Firebase I/O; this module holds the
 * Firebase-free logic so it is unit-testable without the emulator:
 *
 * - input parsing (`parseListAttendeesInput`)
 * - identity join: a raw RSVP doc is `{ status, updatedAt }` with NO identity
 *   (contracts/schemas/events.schema.json pins the shape), so each attendee's
 *   displayName + avatarPath must be joined from the public users/{uid}
 *   projection. `assembleRoster` performs that join.
 * - deleted / missing user handling: an RSVP whose users/{uid} doc is ABSENT is
 *   a deleted (or never-provisioned) account — there is no identity to attribute,
 *   and the whole point of this surface is to show WHO answered, so such rows are
 *   SKIPPED rather than rendered as a nameless "Unknown". An account that exists
 *   but has a blank displayName is a real member and is kept with `displayName:
 *   null` (the client renders its own fallback).
 * - blocking: a caller-injected `isBlocked(uid)` predicate drops any attendee in
 *   a block relationship with the caller in EITHER direction (the callable
 *   resolves the block matrix via the shared convoy helper).
 * - grouping order: the response is a single flat array (matching the contract),
 *   sorted deterministically by status rank (going, maybe, not_going) then
 *   displayName then userId, so the client can render stable status groups.
 *
 * No Firebase Admin SDK imports.
 */

import { z } from 'zod';
import { RSVP_STATUSES, type RsvpStatus } from './events-core';
import type { ProfileProjection } from '../convoy/convoy-core';

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

const listAttendeesSchema = z
  .object({
    eventId: z.string().trim().min(1).max(128),
  })
  .strict();

export type ListAttendeesInput = z.infer<typeof listAttendeesSchema>;

export const LIST_ATTENDEES_EXPECTED = 'Expected { eventId } (a non-empty event id).';

export function parseListAttendeesInput(data: unknown): ParseResult<ListAttendeesInput> {
  const result = listAttendeesSchema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: LIST_ATTENDEES_EXPECTED };
  }
  return { ok: true, input: result.data };
}

/** One attendee as surfaced to the caller's UI. Identity + RSVP answer only. */
export interface AttendeeView {
  userId: string;
  displayName: string | null;
  avatarPath: string | null;
  status: RsvpStatus;
}

/** A raw RSVP entry read from events/{eventId}/rsvps/{userId}. */
export interface RsvpEntry {
  userId: string;
  status: RsvpStatus;
}

/** Stable status ordering for the response (going first, not_going last). */
const STATUS_RANK: Record<RsvpStatus, number> = {
  going: 0,
  maybe: 1,
  not_going: 2,
};

/** True when `value` is one of the three canonical RSVP statuses. */
export function isRsvpStatus(value: unknown): value is RsvpStatus {
  return typeof value === 'string' && (RSVP_STATUSES as readonly string[]).includes(value);
}

/**
 * Joins RSVP entries with the public user projection, drops blocked and
 * deleted-user rows, and returns a deterministically-sorted flat roster.
 *
 * @param entries    RSVP docs (already status-validated by the caller).
 * @param profiles   users/{uid} projection by uid; a uid ABSENT from the map
 *                   (or mapped to `undefined`/`null`) is a deleted/missing
 *                   account and is skipped.
 * @param isBlocked  predicate — true drops the uid (block edge either direction).
 */
export function assembleRoster(
  entries: RsvpEntry[],
  profiles: Map<string, ProfileProjection | undefined | null>,
  isBlocked: (uid: string) => boolean,
): AttendeeView[] {
  const attendees: AttendeeView[] = [];
  const seen = new Set<string>();

  for (const entry of entries) {
    if (seen.has(entry.userId)) continue;
    if (isBlocked(entry.userId)) continue;
    const profile = profiles.get(entry.userId);
    // Deleted / never-provisioned account: no identity to show → skip.
    if (!profile) continue;
    seen.add(entry.userId);
    attendees.push({
      userId: entry.userId,
      displayName: typeof profile.displayName === 'string' ? profile.displayName : null,
      avatarPath: typeof profile.avatarPath === 'string' ? profile.avatarPath : null,
      status: entry.status,
    });
  }

  attendees.sort((a, b) => {
    const rank = STATUS_RANK[a.status] - STATUS_RANK[b.status];
    if (rank !== 0) return rank;
    const nameA = a.displayName ?? '';
    const nameB = b.displayName ?? '';
    const byName = nameA.localeCompare(nameB);
    if (byName !== 0) return byName;
    return a.userId.localeCompare(b.userId);
  });

  return attendees;
}
