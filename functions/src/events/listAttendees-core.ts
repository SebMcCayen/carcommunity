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

/** Splits `items` into consecutive groups of at most `size` (size >= 1). */
function chunk<T>(items: T[], size: number): T[][] {
  const groups: T[][] = [];
  for (let i = 0; i < items.length; i += size) {
    groups.push(items.slice(i, i + size));
  }
  return groups;
}

/**
 * Set of candidate uids in a block relationship with the SINGLE caller peer in
 * EITHER direction, resolved with a BOUNDED number of injected lookups.
 *
 * Firebase-free: the two lookups do the I/O, this only chunks + unions their
 * results, so a unit test can both assert the either-direction filter AND count
 * the lookup invocations (proving the fan-out is bounded to O(ceil(N/size)) per
 * direction, not one query per candidate).
 *
 * Why two lookups instead of the generic `resolvePeerBlockPairs`: with a lone
 * peer, that helper enqueues one query per candidate for the "candidate blocked
 * caller" direction (a distinct `userBlocks/{candidate}/blocked` subcollection
 * each), i.e. ~N parallel Firestore RPCs. Splitting the two directions lets each
 * collapse into chunked reads instead:
 *  - `callerBlocked`: one blocker (the caller), many blocked → a single
 *    `documentId() in [chunk]` query per chunk (billed per doc RETURNED).
 *  - `blockedCaller`: many distinct blockers, one blocked (the caller) → a
 *    batched `getAll` of the point docs per chunk (one streamed RPC per chunk).
 *
 * @param candidateUids  attendee uids (deduped internally).
 * @param size           chunk size (Firestore caps `documentId() in` at 30).
 * @param callerBlocked  given up to `size` candidates, the subset the CALLER has
 *   blocked.
 * @param blockedCaller  given up to `size` candidates, the subset who have
 *   blocked the CALLER.
 */
export async function resolveCallerBlockSet(
  candidateUids: string[],
  size: number,
  callerBlocked: (candidates: string[]) => Promise<string[]>,
  blockedCaller: (candidates: string[]) => Promise<string[]>,
): Promise<Set<string>> {
  const unique = [...new Set(candidateUids)];
  if (unique.length === 0) return new Set<string>();

  const groups = chunk(unique, size);
  const results = await Promise.all(
    groups.flatMap((group) => [callerBlocked(group), blockedCaller(group)]),
  );

  const blocked = new Set<string>();
  for (const hits of results) {
    for (const uid of hits) blocked.add(uid);
  }
  return blocked;
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
    // A blank / whitespace-only displayName is normalised to null so the
    // exposed shape matches the header contract (blank → null, client renders
    // its own fallback) and the sort below treats every blank uniformly. A
    // genuinely-present name is forwarded untouched.
    const rawName = profile.displayName;
    const displayName =
      typeof rawName === 'string' && rawName.trim().length > 0 ? rawName : null;
    attendees.push({
      userId: entry.userId,
      displayName,
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
