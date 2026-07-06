/**
 * Group drive feature module for the admin portal (Phase 13 vertical).
 *
 * Migrated from the legacy `apiRequest` REST client to a direct rules-gated
 * Firestore read (13a pattern). The roster lives at
 * `events/{eventId}/groupDriveParticipants/{uid}` and is admin-readable
 * (`isAdmin()` in firebase/firestore.rules); the admin view exposes only
 * aggregate counts computed client-side from participant `status` — never
 * individual positions, identities, or blocking relationships.
 *
 * Security / privacy notes (unchanged):
 * - Aggregate counts only — no participant identification, no live map.
 * - Never expose exact positions or blocking relationships.
 * - Backend/rules remain the authority; this is a read-only adapter.
 */

import { collection, getDocs, type DocumentData } from 'firebase/firestore';

import { ApiError } from '../../lib/api';
import { getAdminFirestore } from '../../lib/firestore';

export { ApiError };

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
// Read (direct Firestore)
// ---------------------------------------------------------------------------

/**
 * Loads aggregate group-drive stats for an event from the participant roster.
 * `left` participants are excluded from every count (totalActive is everyone
 * currently joined/on_the_way/arrived). Returns null when no one has ever
 * joined (empty roster), matching the legacy "no active group drive" result.
 */
export async function loadAdminGroupDriveSummary(
  eventId: string,
  _token?: string,
): Promise<AdminGroupDriveSummary | null> {
  const snapshot = await getDocs(
    collection(getAdminFirestore(), 'events', eventId, 'groupDriveParticipants'),
  );
  if (snapshot.empty) {
    return null;
  }

  let joinedCount = 0;
  let onTheWayCount = 0;
  let arrivedCount = 0;
  for (const participant of snapshot.docs) {
    const status = (participant.data() as DocumentData).status as string | undefined;
    if (status === 'joined') joinedCount += 1;
    else if (status === 'on_the_way') onTheWayCount += 1;
    else if (status === 'arrived') arrivedCount += 1;
    // `left` (or any unknown status) is not counted as active.
  }

  return {
    totalActive: joinedCount + onTheWayCount + arrivedCount,
    joinedCount,
    onTheWayCount,
    arrivedCount,
  };
}
