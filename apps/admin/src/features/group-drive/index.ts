/**
 * Group drive feature module for the admin portal (Phase 13 vertical).
 *
 * Provides aggregate operational data for group drives attached to events.
 * Exposes only aggregate counts — no individual participant positions,
 * no blocking relationships, no personal location data.
 *
 * Reads come straight from Firestore (admin rules-gated since Phase 11):
 * the roster lives at events/{eventId}/groupDriveParticipants and the
 * status buckets are derived client-side, mirroring the backend aggregate.
 * This module is read-only — there are no admin groupDrive mutations
 * (join/updateStatus/leave are member/owner callables).
 *
 * Security notes:
 * - Never expose exact participant positions to admin views (positions are
 *   not stored on the roster document — only a coarse status).
 * - Never expose individual blocking relationships.
 * - Backend role validation (isAdmin() in firestore.rules) gates the read.
 *
 * Privacy notes:
 * - Aggregate counts only — no participant identification is returned.
 * - No live map view in admin.
 */

import { collection, getDocs } from 'firebase/firestore';

import { ApiError } from '../../lib/api';
import { getAdminFirestore } from '../../lib/firestore';

export type { ApiError };

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/**
 * Aggregate group drive stats for an event — admin view only.
 * Does not include individual participant details or positions.
 */
export interface AdminGroupDriveSummary {
  totalActive: number;
  joinedCount: number;
  onTheWayCount: number;
  arrivedCount: number;
}

// ---------------------------------------------------------------------------
// Read helpers (direct Firestore, admin rules-gated)
// ---------------------------------------------------------------------------

/**
 * Load aggregate group drive stats for an event.
 * Returns null when no active group drive exists (no non-`left`
 * participants) — the legacy 404 case — so the caller hides the panel.
 *
 * Counts are derived directly from the admin-readable
 * events/{eventId}/groupDriveParticipants roster. Participants with status
 * `left` are excluded, mirroring the backend aggregate; the callables
 * enforce member eligibility on every write, so the roster is the
 * authoritative source for the active buckets.
 */
export async function loadAdminGroupDriveSummary(
  eventId: string,
  _token?: string,
): Promise<AdminGroupDriveSummary | null> {
  const snapshot = await getDocs(
    collection(getAdminFirestore(), 'events', eventId, 'groupDriveParticipants'),
  );

  let joinedCount = 0;
  let onTheWayCount = 0;
  let arrivedCount = 0;

  for (const participant of snapshot.docs) {
    const status = participant.data().status as string | undefined;
    if (status === 'joined') joinedCount += 1;
    else if (status === 'on_the_way') onTheWayCount += 1;
    else if (status === 'arrived') arrivedCount += 1;
    // 'left' (and any unknown status) is excluded from the active buckets.
  }

  const totalActive = joinedCount + onTheWayCount + arrivedCount;
  if (totalActive === 0) {
    return null;
  }

  return { totalActive, joinedCount, onTheWayCount, arrivedCount };
}
