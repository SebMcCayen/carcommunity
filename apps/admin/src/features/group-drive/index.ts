/**
 * Group drive feature module for the admin portal.
 *
 * Provides aggregate operational data for group drives attached to events.
 * Exposes only aggregate counts — no individual participant positions,
 * no blocking relationships, no personal location data.
 *
 * Security notes:
 * - Never expose exact participant positions to admin views.
 * - Never expose individual blocking relationships.
 * - Backend role validation is always required.
 * - TODO: Add safety/moderation operations if required (e.g., end a group drive
 *   attached to a cancelled event, or review participant counts for safety concerns).
 *
 * Privacy notes:
 * - Aggregate counts only — no participant identification.
 * - No live map view in admin.
 */

import {
  buildAdminGroupDriveSummaryPath,
  type AdminGroupDriveSummaryResponse,
} from '@carcommunity/shared/group-drive';

import { ApiError, apiRequest } from '../../lib/api';

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
// API helpers
// ---------------------------------------------------------------------------

/**
 * Load aggregate group drive stats for an event.
 * Returns null if no active group drive exists for the event.
 *
 * Uses the admin-only GET /v1/admin/events/:eventId/group-drive/summary endpoint
 * which returns aggregate counts only and is protected by admin role checks.
 */
export async function loadAdminGroupDriveSummary(
  eventId: string,
  token?: string,
): Promise<AdminGroupDriveSummary | null> {
  try {
    const result = await apiRequest<AdminGroupDriveSummaryResponse>(
      buildAdminGroupDriveSummaryPath(eventId),
      { token },
    );
    return {
      totalActive: result.data.totalActive,
      joinedCount: result.data.joinedCount,
      onTheWayCount: result.data.onTheWayCount,
      arrivedCount: result.data.arrivedCount,
    };
  } catch (err) {
    if (err instanceof ApiError && err.statusCode === 404) {
      return null;
    }
    throw err;
  }
}
