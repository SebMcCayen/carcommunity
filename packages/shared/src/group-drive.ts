import type { EventRsvpStatus, EventStatus } from './events.js';
import type { LiveLocationCoordinate } from './live-location.js';
import type { SubscriptionEntitlement, UserRole, UserStatus } from './users.js';
import { canAccessMemberFeatures } from './users.js';

// ---------------------------------------------------------------------------
// Participant status
// ---------------------------------------------------------------------------

export const GROUP_DRIVE_PARTICIPANT_STATUSES = ['joined', 'on_the_way', 'arrived', 'left'] as const;
export type GroupDriveParticipantStatus = (typeof GROUP_DRIVE_PARTICIPANT_STATUSES)[number];

/**
 * Status values that may be set by the user via the update-status endpoint.
 * `left` is only set by the dedicated leave endpoint.
 */
export const GROUP_DRIVE_UPDATABLE_STATUSES = ['joined', 'on_the_way', 'arrived'] as const;
export type GroupDriveUpdatableStatus = (typeof GROUP_DRIVE_UPDATABLE_STATUSES)[number];

// ---------------------------------------------------------------------------
// Route paths
// ---------------------------------------------------------------------------

export function buildGroupDriveJoinPath(eventId: string): string {
  return `/v1/events/${eventId}/group-drive/join`;
}

export function buildGroupDriveLeavePath(eventId: string): string {
  return `/v1/events/${eventId}/group-drive/leave`;
}

export function buildGroupDriveStatusPath(eventId: string): string {
  return `/v1/events/${eventId}/group-drive/status`;
}

export function buildGroupDriveSummaryPath(eventId: string): string {
  return `/v1/events/${eventId}/group-drive`;
}

export function buildGroupDriveMarkersPath(eventId: string): string {
  return `/v1/events/${eventId}/group-drive/markers`;
}

// ---------------------------------------------------------------------------
// Shared contracts
// ---------------------------------------------------------------------------

/**
 * Safe participant summary.
 *
 * Uses `participantId` (the group drive record ID) as the opaque identifier —
 * never expose raw user IDs in participant lists.
 *
 * Does NOT include: email, provider identity, subscription details, moderation
 * metadata, session tokens, route history, or previous positions.
 */
export interface GroupDriveParticipantSummary {
  /** Opaque group drive participant ID (record ID, not user ID). */
  participantId: string;
  displayName: string | null;
  status: GroupDriveParticipantStatus;
  joinedAt: string;
  /** Whether this participant currently has an active, non-expired live location session. */
  hasActiveLiveLocation: boolean;
}

/**
 * A safe live location marker for a group drive participant.
 *
 * Uses `participantId` as the opaque identifier. Does not include user ID,
 * route history, or previous positions.
 */
export interface GroupDriveMarker {
  /** Opaque group drive participant ID (record ID, not user ID). */
  participantId: string;
  sessionId: string;
  displayName: string | null;
  status: GroupDriveParticipantStatus;
  coordinate: LiveLocationCoordinate;
  expiresAt: string;
}

// ---------------------------------------------------------------------------
// Request contracts
// ---------------------------------------------------------------------------

/** No body required — user identity comes from the authenticated session. */
export interface JoinGroupDriveRequest {
  // intentionally empty
}

export interface UpdateGroupDriveStatusRequest {
  /** Must be one of the updatable statuses. `left` is rejected — use the leave endpoint. */
  status: GroupDriveUpdatableStatus;
}

// ---------------------------------------------------------------------------
// Response contracts
// ---------------------------------------------------------------------------

export interface JoinGroupDriveResponse {
  ok: true;
  data: {
    participant: GroupDriveParticipantSummary;
    rejoined: boolean;
  };
}

export interface LeaveGroupDriveResponse {
  ok: true;
  data: {
    left: true;
  };
}

export interface UpdateGroupDriveStatusResponse {
  ok: true;
  data: {
    participant: GroupDriveParticipantSummary;
  };
}

export interface GroupDriveSummaryResponse {
  ok: true;
  data: {
    /** Count of active participants (status is not `left`). */
    totalActive: number;
    joinedCount: number;
    onTheWayCount: number;
    arrivedCount: number;
    /** Current authenticated user's participant status, or null if not participating. */
    currentUserStatus: GroupDriveParticipantStatus | null;
    /** Whether the current user currently has an active live location session. */
    currentUserHasActiveLiveLocation: boolean;
    /** Safe participant summaries, excluding blocked users. */
    participants: GroupDriveParticipantSummary[];
  };
}

export interface GroupDriveMarkersResponse {
  ok: true;
  data: {
    markers: GroupDriveMarker[];
    generatedAt: string;
  };
}

// ---------------------------------------------------------------------------
// Access helpers
// ---------------------------------------------------------------------------

/**
 * Returns true if the user may join an event group drive.
 *
 * Rules:
 *  - User must have an active member_monthly subscription.
 *  - Suspension and deletion override subscription.
 *  - Event must be published (not draft, cancelled, or completed).
 *  - User must have RSVP status `going` or `maybe`.
 *
 * Timing checks (event start/end) are enforced at the service layer
 * where the event record is available.
 */
export function canJoinEventGroupDrive(input: {
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
  eventStatus: EventStatus;
  rsvpStatus: EventRsvpStatus | null;
}): boolean {
  if (!canAccessMemberFeatures(input)) return false;
  if (input.eventStatus !== 'published') return false;
  return input.rsvpStatus === 'going' || input.rsvpStatus === 'maybe';
}

/**
 * Returns true if the user may view a group drive summary or markers.
 *
 * Requires an active member_monthly subscription.
 * Suspension and deletion override subscription.
 */
export function canViewEventGroupDrive(input: {
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
}): boolean {
  return canAccessMemberFeatures(input);
}

/**
 * Returns true if the user may update their own group drive participant status.
 *
 * Rules:
 *  - User must have an active member_monthly subscription.
 *  - User must be an active participant (status is not `left` and not null).
 *  - Suspension and deletion override subscription.
 *
 * Does not allow updating to `left` — use the dedicated leave endpoint.
 */
export function canUpdateGroupDriveStatus(input: {
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
  currentParticipantStatus: GroupDriveParticipantStatus | null;
}): boolean {
  if (!canAccessMemberFeatures(input)) return false;
  if (!input.currentParticipantStatus) return false;
  return input.currentParticipantStatus !== 'left';
}
