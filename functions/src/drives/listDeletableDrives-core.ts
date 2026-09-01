/**
 * Pure input parsing for the owner-only deletion inventory (drives.listDeletable).
 *
 * After a downgrade, drives beyond the caller's tier window (Community past the
 * newest 5, Plus older than 90 days) are omitted by drives.listHistory, so the
 * user has no id with which to delete them. This callable returns the ids of
 * ALL owned drives regardless of tier window — MINIMAL fields only (no
 * distances, speeds, durations, route data, images or session ids) — so a
 * downgraded user can still clean up retained-but-hidden drives via
 * drives.delete. It is a deletion index, deliberately not a stats or history
 * bypass.
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { z } from 'zod';

export const DELETABLE_DRIVES_PAGE_SIZE_DEFAULT = 50;
export const DELETABLE_DRIVES_PAGE_SIZE_MAX = 50;

const listDeletableDrivesInputSchema = z
  .object({
    cursorRideId: z.string().trim().min(1).max(300).optional(),
    pageSize: z.number().int().min(1).max(DELETABLE_DRIVES_PAGE_SIZE_MAX).optional(),
  })
  .strict();

export type ListDeletableDrivesInput = z.infer<typeof listDeletableDrivesInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export function parseListDeletableDrivesInput(data: unknown): ParseResult<ListDeletableDrivesInput> {
  const parsed = listDeletableDrivesInputSchema.safeParse(data ?? {});
  if (!parsed.success) {
    return {
      ok: false,
      message: `Expected { cursorRideId?: string, pageSize?: integer 1-${DELETABLE_DRIVES_PAGE_SIZE_MAX} }.`,
    };
  }
  return { ok: true, input: parsed.data };
}

export function deletableDrivesPageSize(requested?: number): number {
  return Math.min(requested ?? DELETABLE_DRIVES_PAGE_SIZE_DEFAULT, DELETABLE_DRIVES_PAGE_SIZE_MAX);
}

/**
 * Minimal deletion-inventory row. Deliberately carries ONLY what a "delete this
 * drive" list needs — id, when it happened, and its label. No distance, speed,
 * duration, route path/thumbnail, image or source-session field is ever
 * included: those stay behind the tier-gated drives.listHistory.
 */
export interface DeletableDriveItem {
  rideId: string;
  createdAtMillis: number;
  /** Display label for the row; null when the drive was saved without a title. */
  title: string | null;
  /** When the drive started, for display parity with History; null if unset. */
  startedAtMillis: number | null;
}

export interface ListDeletableDrivesResponse {
  drives: DeletableDriveItem[];
  hasMore: boolean;
  /** Non-null only when another page is available. */
  nextCursorRideId: string | null;
}
