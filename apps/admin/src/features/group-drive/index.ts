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

import {
  collection,
  getCountFromServer,
  query,
  where,
  type Query,
} from 'firebase/firestore';

import type { ApiError } from '../../lib/errors';
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

/** Server-side count of roster docs in one status bucket (no doc data leaves the server). */
async function countByStatus(roster: Query, status: string): Promise<number> {
  const snap = await getCountFromServer(query(roster, where('status', '==', status)));
  return snap.data().count;
}

/**
 * Load aggregate group drive stats for an event.
 * Returns null when no active group drive exists (no non-`left`
 * participants) — the legacy 404 case — so the caller hides the panel.
 *
 * Counts are computed with Firestore server-side aggregation
 * (getCountFromServer) over the admin-readable
 * events/{eventId}/groupDriveParticipants roster, one query per active
 * status bucket. Only the counts cross the wire — participant documents
 * (which carry displayName) are never downloaded, preserving the
 * aggregate-only contract. Status `left` is excluded, mirroring the backend
 * aggregate; the callables enforce member eligibility on every write.
 */
export async function loadAdminGroupDriveSummary(
  eventId: string,
  _token?: string,
): Promise<AdminGroupDriveSummary | null> {
  const roster = collection(getAdminFirestore(), 'events', eventId, 'groupDriveParticipants');

  const [joinedCount, onTheWayCount, arrivedCount] = await Promise.all([
    countByStatus(roster, 'joined'),
    countByStatus(roster, 'on_the_way'),
    countByStatus(roster, 'arrived'),
  ]);

  const totalActive = joinedCount + onTheWayCount + arrivedCount;
  if (totalActive === 0) {
    return null;
  }

  return { totalActive, joinedCount, onTheWayCount, arrivedCount };
}
