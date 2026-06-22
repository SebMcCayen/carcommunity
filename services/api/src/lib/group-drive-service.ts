/**
 * GroupDriveService — backend business logic for event group driving.
 *
 * Design rules enforced here:
 *  - Only authenticated members with active member_monthly may participate.
 *  - User must have RSVP `going` or `maybe`.
 *  - Event must be published and not ended.
 *  - Suspended and deleted users are rejected.
 *  - Blocking is enforced in both directions.
 *  - Live location data is never stored here; LiveLocationLatestPosition is reused.
 *  - No position history or route data is created.
 *  - Client-provided user IDs are never trusted; userId always comes from auth context.
 *
 * Rejoin behaviour:
 *  A participant who previously left (status = 'left') may rejoin if product rules
 *  still allow it (event is eligible, RSVP is still going/maybe, user is still an
 *  active member). Rejoining resets status to 'joined', updates joinedAt, and clears
 *  leftAt. The createdAt timestamp is preserved for audit purposes.
 */

import type { PrismaClient } from '@prisma/client';
import {
  GROUP_DRIVE_PARTICIPANT_STATUSES,
  GROUP_DRIVE_UPDATABLE_STATUSES,
  canJoinEventGroupDrive,
  canUpdateGroupDriveStatus,
  canViewEventGroupDrive,
  type GroupDriveMarker,
  type GroupDriveParticipantStatus,
  type GroupDriveParticipantSummary,
  type GroupDriveUpdatableStatus,
} from '@carcommunity/shared/group-drive';
import {
  LIVE_LOCATION_MARKER_STALE_THRESHOLD_MS,
  type LiveLocationCoordinate,
} from '@carcommunity/shared/live-location';
import type { SubscriptionEntitlement, UserRole, UserStatus } from '@carcommunity/shared/users';
import { isSuspendedStatus } from '@carcommunity/shared/users';

import { AppError } from './errors.js';

export interface GroupDriveViewer {
  userId: string;
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
}

export interface JoinGroupDriveResult {
  participant: GroupDriveParticipantSummary;
  rejoined: boolean;
}

export interface LeaveGroupDriveResult {
  left: true;
}

export interface UpdateGroupDriveStatusResult {
  participant: GroupDriveParticipantSummary;
}

export interface GroupDriveSummaryResult {
  totalActive: number;
  joinedCount: number;
  onTheWayCount: number;
  arrivedCount: number;
  currentUserStatus: GroupDriveParticipantStatus | null;
  currentUserHasActiveLiveLocation: boolean;
  participants: GroupDriveParticipantSummary[];
}

export interface GroupDriveMarkersResult {
  markers: GroupDriveMarker[];
  generatedAt: string;
}

export interface AdminGroupDriveSummaryResult {
  totalActive: number;
  joinedCount: number;
  onTheWayCount: number;
  arrivedCount: number;
}

function optionalNumber(value: number | null): number | undefined {
  return value ?? undefined;
}

function toCoordinate(position: {
  latitude: number;
  longitude: number;
  accuracyMeters: number | null;
  headingDegrees: number | null;
  speedMetersPerSecond: number | null;
  recordedAt: Date;
}): LiveLocationCoordinate {
  return {
    latitude: position.latitude,
    longitude: position.longitude,
    accuracyMeters: optionalNumber(position.accuracyMeters),
    headingDegrees: optionalNumber(position.headingDegrees),
    speedMetersPerSecond: optionalNumber(position.speedMetersPerSecond),
    recordedAt: position.recordedAt.toISOString(),
  };
}

function calculateStaleThreshold(now: Date): Date {
  return new Date(now.getTime() - LIVE_LOCATION_MARKER_STALE_THRESHOLD_MS);
}

export class GroupDriveService {
  constructor(private readonly prisma: PrismaClient) {}

  /**
   * Join an event group drive.
   *
   * Idempotent: if the user is already an active participant (status ≠ 'left'),
   * their current record is returned without modification.
   *
   * Rejoin: if the user previously left, their record is reactivated (status reset
   * to 'joined', joinedAt updated, leftAt cleared) provided product rules still allow it.
   *
   * Does NOT start a live location session automatically.
   */
  public async joinGroupDrive(params: {
    eventId: string;
    viewer: GroupDriveViewer;
    now?: Date;
  }): Promise<JoinGroupDriveResult> {
    const now = params.now ?? new Date();
    const { eventId, viewer } = params;

    this.assertActiveUser(viewer);

    // Load event with RSVP
    const event = await this.prisma.event.findUnique({
      where: { id: eventId },
      select: {
        id: true,
        status: true,
        startsAt: true,
        endsAt: true,
        rsvps: {
          where: { userId: viewer.userId },
          select: { status: true },
          take: 1,
        },
      },
    });

    if (!event) {
      throw new AppError(404, 'not_found', 'Event not found.');
    }

    const rsvp = event.rsvps[0] ?? null;

    if (
      !canJoinEventGroupDrive({
        role: viewer.role,
        status: viewer.status,
        subscriptionEntitlement: viewer.subscriptionEntitlement,
        eventStatus: event.status,
        rsvpStatus: rsvp?.status ?? null,
      })
    ) {
      if (event.status !== 'published') {
        throw new AppError(403, 'forbidden', 'Event is not eligible for group driving.');
      }
      if (!rsvp || (rsvp.status !== 'going' && rsvp.status !== 'maybe')) {
        throw new AppError(403, 'forbidden', 'RSVP going or maybe required to join group drive.');
      }
      throw new AppError(403, 'forbidden', 'Member subscription required.');
    }

    // Reject if event has ended
    if (event.endsAt !== null && event.endsAt <= now) {
      throw new AppError(403, 'forbidden', 'Event has ended.');
    }

    // Upsert participant — idempotent if already active, reactivates if left
    const existing = await this.prisma.eventGroupDriveParticipant.findUnique({
      where: {
        eventId_userId: { eventId, userId: viewer.userId },
      },
    });

    let participantId: string;
    let joinedAt: Date;
    let status: GroupDriveParticipantStatus;
    let rejoined = false;

    if (!existing) {
      // First join
      const created = await this.prisma.eventGroupDriveParticipant.create({
        data: {
          eventId,
          userId: viewer.userId,
          status: 'joined',
          joinedAt: now,
        },
        select: { id: true, status: true, joinedAt: true },
      });
      participantId = created.id;
      joinedAt = created.joinedAt;
      status = created.status;
    } else if (existing.status === 'left') {
      // Rejoin
      const updated = await this.prisma.eventGroupDriveParticipant.update({
        where: { id: existing.id },
        data: {
          status: 'joined',
          joinedAt: now,
          leftAt: null,
        },
        select: { id: true, status: true, joinedAt: true },
      });
      participantId = updated.id;
      joinedAt = updated.joinedAt;
      status = updated.status;
      rejoined = true;
    } else {
      // Already active — idempotent
      participantId = existing.id;
      joinedAt = existing.joinedAt;
      status = existing.status;
    }

    const hasActiveLiveLocation = await this.hasActiveLiveLocation(viewer.userId, now);

    return {
      participant: {
        participantId,
        displayName: null, // own record; displayName not needed for join response
        status,
        joinedAt: joinedAt.toISOString(),
        hasActiveLiveLocation,
      },
      rejoined,
    };
  }

  /**
   * Leave an event group drive.
   *
   * Idempotent: if the user is not a participant or has already left, returns
   * successfully without error.
   *
   * Does NOT stop the user's live location session.
   * The participant is immediately excluded from marker and participant responses.
   */
  public async leaveGroupDrive(params: {
    eventId: string;
    viewer: GroupDriveViewer;
    now?: Date;
  }): Promise<LeaveGroupDriveResult> {
    const now = params.now ?? new Date();
    const { eventId, viewer } = params;

    this.assertActiveUser(viewer);

    const participant = await this.prisma.eventGroupDriveParticipant.findUnique({
      where: {
        eventId_userId: { eventId, userId: viewer.userId },
      },
      select: { id: true, status: true },
    });

    if (!participant || participant.status === 'left') {
      // Idempotent — already not participating
      return { left: true };
    }

    await this.prisma.eventGroupDriveParticipant.update({
      where: { id: participant.id },
      data: {
        status: 'left',
        leftAt: now,
      },
    });

    return { left: true };
  }

  /**
   * Update the current user's group drive participant status.
   *
   * Only the authenticated user may update their own status.
   * The `left` status cannot be set here — use leaveGroupDrive instead.
   */
  public async updateStatus(params: {
    eventId: string;
    viewer: GroupDriveViewer;
    status: GroupDriveUpdatableStatus;
    now?: Date;
  }): Promise<UpdateGroupDriveStatusResult> {
    const now = params.now ?? new Date();
    const { eventId, viewer, status } = params;

    this.assertActiveUser(viewer);

    // Validate status value
    if (!(GROUP_DRIVE_UPDATABLE_STATUSES as readonly string[]).includes(status)) {
      throw new AppError(400, 'validation_error', 'Invalid status value.');
    }

    // Load event to validate it is still eligible
    const event = await this.prisma.event.findUnique({
      where: { id: eventId },
      select: { id: true, status: true, endsAt: true },
    });

    if (!event) {
      throw new AppError(404, 'not_found', 'Event not found.');
    }

    if (event.status !== 'published') {
      throw new AppError(403, 'forbidden', 'Event is not eligible for group driving.');
    }

    if (event.endsAt !== null && event.endsAt <= now) {
      throw new AppError(403, 'forbidden', 'Event has ended.');
    }

    const participant = await this.prisma.eventGroupDriveParticipant.findUnique({
      where: {
        eventId_userId: { eventId, userId: viewer.userId },
      },
      select: { id: true, status: true, joinedAt: true },
    });

    if (!participant) {
      throw new AppError(404, 'not_found', 'Not a group drive participant.');
    }

    if (
      !canUpdateGroupDriveStatus({
        role: viewer.role,
        status: viewer.status,
        subscriptionEntitlement: viewer.subscriptionEntitlement,
        currentParticipantStatus: participant.status,
      })
    ) {
      throw new AppError(403, 'forbidden', 'Cannot update group drive status.');
    }

    const updated = await this.prisma.eventGroupDriveParticipant.update({
      where: { id: participant.id },
      data: { status },
      select: { id: true, status: true, joinedAt: true },
    });

    const hasActiveLiveLocation = await this.hasActiveLiveLocation(viewer.userId, now);

    return {
      participant: {
        participantId: updated.id,
        displayName: null,
        status: updated.status,
        joinedAt: updated.joinedAt.toISOString(),
        hasActiveLiveLocation,
      },
    };
  }

  /**
   * Get the group drive summary for an event.
   *
   * Returns aggregate counts, the current user's participation status,
   * and safe participant summaries (excluding blocked users).
   *
   * Does NOT expose exact positions.
   */
  public async getGroupDriveSummary(params: {
    eventId: string;
    viewer: GroupDriveViewer;
    excludeUserIds?: string[];
    now?: Date;
  }): Promise<GroupDriveSummaryResult> {
    const now = params.now ?? new Date();
    const { eventId, viewer, excludeUserIds = [] } = params;

    this.assertActiveUser(viewer);

    if (!canViewEventGroupDrive(viewer)) {
      throw new AppError(403, 'forbidden', 'Member subscription required.');
    }

    const event = await this.prisma.event.findUnique({
      where: { id: eventId },
      select: { id: true, status: true },
    });

    if (!event) {
      throw new AppError(404, 'not_found', 'Event not found.');
    }

    if (event.status !== 'published') {
      throw new AppError(403, 'forbidden', 'Event is not eligible for group driving.');
    }

    // Fetch all active participants, excluding blocked users and ineligible users
    // Use a single query to avoid N+1
    const activeParticipants = await this.prisma.eventGroupDriveParticipant.findMany({
      where: {
        eventId,
        status: { not: 'left' },
        userId: excludeUserIds.length > 0 ? { notIn: excludeUserIds } : undefined,
        user: {
          status: { in: ['active', 'warned'] },
          deletedAt: null,
          subscriptionEntitlement: 'member_monthly',
        },
      },
      select: {
        id: true,
        userId: true,
        status: true,
        joinedAt: true,
        user: {
          select: { displayName: true },
        },
      },
      orderBy: { joinedAt: 'asc' },
    });

    // Find current user's record (even if they are not in the active list)
    const currentUserParticipant = await this.prisma.eventGroupDriveParticipant.findUnique({
      where: {
        eventId_userId: { eventId, userId: viewer.userId },
      },
      select: { status: true },
    });

    // Aggregate counts
    let joinedCount = 0;
    let onTheWayCount = 0;
    let arrivedCount = 0;

    for (const p of activeParticipants) {
      if (p.status === 'joined') joinedCount++;
      else if (p.status === 'on_the_way') onTheWayCount++;
      else if (p.status === 'arrived') arrivedCount++;
    }

    // Fetch active live location session user IDs for the participants in one query
    const participantUserIds = activeParticipants.map((p) => p.userId);
    const activeLiveLocationUserIds = await this.getActiveLiveLocationUserIds(participantUserIds, now);
    const activeLiveLocationSet = new Set(activeLiveLocationUserIds);

    const currentUserHasActiveLiveLocation = await this.hasActiveLiveLocation(viewer.userId, now);

    // Build safe participant summaries
    const participants: GroupDriveParticipantSummary[] = activeParticipants.map((p) => ({
      participantId: p.id,
      displayName: p.user.displayName,
      status: p.status,
      joinedAt: p.joinedAt.toISOString(),
      hasActiveLiveLocation: activeLiveLocationSet.has(p.userId),
    }));

    return {
      totalActive: activeParticipants.length,
      joinedCount,
      onTheWayCount,
      arrivedCount,
      currentUserStatus: currentUserParticipant?.status ?? null,
      currentUserHasActiveLiveLocation,
      participants,
    };
  }

  /**
   * Get visible live location markers for active group drive participants.
   *
   * Rules:
   *  - Viewer must be a member and an active group participant.
   *  - Only active participants (status ≠ 'left') are considered.
   *  - Only participants with an active, non-expired, non-stale live position are returned.
   *  - Blocked users are excluded in both directions.
   *  - The viewer's own marker is excluded (shown separately by the client).
   *  - No route history or previous positions are exposed.
   */
  public async getGroupDriveMarkers(params: {
    eventId: string;
    viewer: GroupDriveViewer;
    excludeUserIds?: string[];
    now?: Date;
  }): Promise<GroupDriveMarkersResult> {
    const now = params.now ?? new Date();
    const { eventId, viewer, excludeUserIds = [] } = params;

    this.assertActiveUser(viewer);

    if (!canViewEventGroupDrive(viewer)) {
      throw new AppError(403, 'forbidden', 'Member subscription required.');
    }

    const event = await this.prisma.event.findUnique({
      where: { id: eventId },
      select: { id: true, status: true },
    });

    if (!event) {
      throw new AppError(404, 'not_found', 'Event not found.');
    }

    if (event.status !== 'published') {
      throw new AppError(403, 'forbidden', 'Event is not eligible for group driving.');
    }

    // Viewer must be an active participant to see markers
    const viewerParticipant = await this.prisma.eventGroupDriveParticipant.findUnique({
      where: {
        eventId_userId: { eventId, userId: viewer.userId },
      },
      select: { status: true },
    });

    if (!viewerParticipant || viewerParticipant.status === 'left') {
      throw new AppError(403, 'forbidden', 'You are not an active group drive participant.');
    }

    const staleThreshold = calculateStaleThreshold(now);

    // Blocked user IDs + viewer's own ID are excluded
    const allExcluded = new Set([viewer.userId, ...excludeUserIds]);

    // Find all active participants with valid live positions in one query.
    // Filter at DB level to avoid N+1 and prevent exposing stale data.
    const positions = await this.prisma.liveLocationLatestPosition.findMany({
      where: {
        userId: { notIn: Array.from(allExcluded) },
        recordedAt: { gte: staleThreshold },
        session: {
          status: 'active',
          expiresAt: { gt: now },
          user: {
            status: { in: ['active', 'warned'] },
            deletedAt: null,
            subscriptionEntitlement: 'member_monthly',
          },
        },
        // Only participants in this event's group drive with non-left status
        user: {
          groupDriveParticipations: {
            some: {
              eventId,
              status: { not: 'left' },
            },
          },
        },
      },
      select: {
        userId: true,
        sessionId: true,
        latitude: true,
        longitude: true,
        accuracyMeters: true,
        headingDegrees: true,
        speedMetersPerSecond: true,
        recordedAt: true,
        session: {
          select: { expiresAt: true },
        },
        user: {
          select: {
            displayName: true,
            groupDriveParticipations: {
              where: { eventId, status: { not: 'left' } },
              select: { id: true, status: true },
              take: 1,
            },
          },
        },
      },
      orderBy: { recordedAt: 'desc' },
    });

    const generatedAt = now.toISOString();

    const markers: GroupDriveMarker[] = positions
      .map((position) => {
        const participation = position.user.groupDriveParticipations[0];
        if (!participation) return null;

        return {
          participantId: participation.id,
          sessionId: position.sessionId,
          displayName: position.user.displayName,
          status: participation.status,
          coordinate: toCoordinate(position),
          expiresAt: position.session.expiresAt.toISOString(),
        };
      })
      .filter((m): m is GroupDriveMarker => m !== null);

    return { markers, generatedAt };
  }

  /**
   * Get aggregate group drive counts for an event — admin use only.
   *
   * Returns only counts; never includes individual participant details,
   * positions, blocking relationships, or display names.
   * Counts only eligible participants (active/warned, not deleted, member_monthly).
   */
  public async getAdminGroupDriveSummary(params: {
    eventId: string;
  }): Promise<AdminGroupDriveSummaryResult> {
    const { eventId } = params;

    const counts = await this.prisma.eventGroupDriveParticipant.groupBy({
      by: ['status'],
      where: {
        eventId,
        status: { not: 'left' },
        user: {
          status: { in: ['active', 'warned'] },
          deletedAt: null,
          subscriptionEntitlement: 'member_monthly',
        },
      },
      _count: { status: true },
    });

    let joinedCount = 0;
    let onTheWayCount = 0;
    let arrivedCount = 0;

    for (const row of counts) {
      if (row.status === 'joined') joinedCount = row._count.status;
      else if (row.status === 'on_the_way') onTheWayCount = row._count.status;
      else if (row.status === 'arrived') arrivedCount = row._count.status;
    }

    const totalActive = joinedCount + onTheWayCount + arrivedCount;

    return { totalActive, joinedCount, onTheWayCount, arrivedCount };
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private assertActiveUser(viewer: GroupDriveViewer): void {
    if (viewer.status === 'deleted') {
      throw new AppError(403, 'forbidden', 'Your account has been deleted.');
    }
    if (isSuspendedStatus(viewer.status)) {
      throw new AppError(403, 'suspended', 'Your account has been suspended.');
    }
  }

  private async hasActiveLiveLocation(userId: string, now: Date): Promise<boolean> {
    const session = await this.prisma.liveLocationSession.findFirst({
      where: {
        userId,
        status: 'active',
        expiresAt: { gt: now },
      },
      select: { id: true },
    });
    return session !== null;
  }

  /**
   * Returns the subset of userIds that have an active, non-expired live location session.
   * Uses a single query to avoid N+1 when checking multiple participants.
   */
  private async getActiveLiveLocationUserIds(userIds: string[], now: Date): Promise<string[]> {
    if (userIds.length === 0) return [];

    const sessions = await this.prisma.liveLocationSession.findMany({
      where: {
        userId: { in: userIds },
        status: 'active',
        expiresAt: { gt: now },
      },
      select: { userId: true },
      distinct: ['userId'],
    });

    return sessions.map((s) => s.userId);
  }
}

// Re-export for use in routes
export { GROUP_DRIVE_PARTICIPANT_STATUSES, GROUP_DRIVE_UPDATABLE_STATUSES };
