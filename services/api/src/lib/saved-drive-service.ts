/**
 * SavedDriveService — backend business logic for saved drives.
 *
 * Design rules enforced here:
 *  - Saved drives belong only to the authenticated user.
 *    userId is always derived from the auth context, never from the client.
 *  - A drive is only saved on explicit user action (save endpoint).
 *    Stopping a live location session never auto-saves a drive.
 *  - Duplicate saves for the same session are prevented.
 *  - Suspended and deleted users cannot access saved-drive features.
 *  - member_monthly is required for:
 *      • saving multiple drives (free users are limited to 0 saved drives)
 *      • route overview in detail responses
 *  - Free users receive a post-drive summary but no persistent drive library.
 *  - No top-speed field is ever stored or returned.
 *  - Raw temporary route points are never returned.
 *  - Backend is the source of truth for ownership and entitlement.
 *
 * Summary-only MVP:
 *  distanceMeters, averageSpeedMetersPerSecond, and routeOverview are null
 *  until TemporaryDrivePoint collection is implemented.
 *  TODO: Integrate TemporaryDrivePoint sampling once that model is added.
 */

import type { PrismaClient, SavedDrive } from '@prisma/client';
import type {
  PostDriveSummary,
  SavedDriveDetail,
  SavedDriveListItem,
} from '@carcommunity/shared/saved-drives';
import {
  DEFAULT_SAVED_DRIVES_PAGE_SIZE,
  MAX_SAVED_DRIVES_PAGE_SIZE,
} from '@carcommunity/shared/saved-drives';
import { canAccessMemberFeatures, isSuspendedStatus } from '@carcommunity/shared/users';
import type { SubscriptionEntitlement, UserRole, UserStatus } from '@carcommunity/shared/users';

import { AppError } from './errors.js';
import { driveDurationSeconds } from './drive-calculations.js';

export interface SavedDriveActor {
  userId: string;
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
}

export interface ListSavedDrivesResult {
  drives: SavedDriveListItem[];
  total: number;
  hasNext: boolean;
}

export interface GetPostDriveSummaryResult {
  summary: PostDriveSummary;
  canSave: boolean;
}

function assertNotSuspendedOrDeleted(actor: SavedDriveActor): void {
  if (actor.status === 'deleted') {
    throw new AppError(403, 'forbidden', 'Your account has been deleted.');
  }
  if (isSuspendedStatus(actor.status)) {
    throw new AppError(403, 'suspended', 'Your account has been suspended.');
  }
}

function toListItem(drive: SavedDrive): SavedDriveListItem {
  return {
    id: drive.id,
    startedAt: drive.startedAt.toISOString(),
    endedAt: drive.endedAt.toISOString(),
    durationSeconds: drive.durationSeconds,
    distanceMeters: drive.distanceMeters ?? null,
    averageSpeedMetersPerSecond: drive.averageSpeedMetersPerSecond ?? null,
    approximateStartArea: drive.approximateStartArea ?? null,
    approximateEndArea: drive.approximateEndArea ?? null,
    createdAt: drive.createdAt.toISOString(),
  };
}

function toDetail(drive: SavedDrive, isMember: boolean): SavedDriveDetail {
  return {
    ...toListItem(drive),
    // Route overview is member-only. Raw points are never exposed.
    routeOverview: isMember && drive.routeOverview ? (drive.routeOverview as { latitude: number; longitude: number }[]) : null,
  };
}

export class SavedDriveService {
  constructor(private readonly prisma: PrismaClient) {}

  /**
   * Return a post-drive summary for a stopped or expired session.
   * Does NOT persist a saved drive — summary is temporary.
   * canSave reflects whether the user is eligible to save (member required).
   */
  public async getPostDriveSummary(params: {
    sessionId: string;
    actor: SavedDriveActor;
  }): Promise<GetPostDriveSummaryResult> {
    assertNotSuspendedOrDeleted(params.actor);

    const session = await this.prisma.liveLocationSession.findUnique({
      where: { id: params.sessionId },
    });

    if (!session) {
      throw new AppError(404, 'not_found', 'Live location session not found.');
    }

    if (session.userId !== params.actor.userId) {
      throw new AppError(403, 'forbidden', 'You can only view your own session summary.');
    }

    if (session.status === 'active') {
      throw new AppError(
        400,
        'validation_error',
        'Session is still active. Stop the session before requesting a post-drive summary.',
      );
    }

    const endedAt = session.stoppedAt ?? session.expiresAt;
    const durationSecs = driveDurationSeconds(session.startedAt, endedAt);

    const summary: PostDriveSummary = {
      sessionId: session.id,
      startedAt: session.startedAt.toISOString(),
      endedAt: endedAt.toISOString(),
      durationSeconds: durationSecs,
      // Summary-only MVP — no route buffer yet.
      distanceMeters: null,
      averageSpeedMetersPerSecond: null,
      approximateStartArea: null,
      approximateEndArea: null,
    };

    const canSave = canAccessMemberFeatures(params.actor);

    return { summary, canSave };
  }

  /**
   * Save a drive from a stopped or expired session.
   * Requires explicit user action.
   * Requires member_monthly.
   * Prevents duplicate saves for the same session.
   */
  public async saveDrive(params: {
    sessionId: string;
    actor: SavedDriveActor;
  }): Promise<SavedDriveDetail> {
    assertNotSuspendedOrDeleted(params.actor);

    if (!canAccessMemberFeatures(params.actor)) {
      throw new AppError(403, 'forbidden', 'Member subscription required to save drives.');
    }

    const session = await this.prisma.liveLocationSession.findUnique({
      where: { id: params.sessionId },
    });

    if (!session) {
      throw new AppError(404, 'not_found', 'Live location session not found.');
    }

    if (session.userId !== params.actor.userId) {
      throw new AppError(403, 'forbidden', 'You can only save your own session.');
    }

    if (session.status === 'active') {
      throw new AppError(
        400,
        'validation_error',
        'Session is still active. Stop the session before saving.',
      );
    }

    // Idempotent: return existing saved drive if already saved.
    const existing = await this.prisma.savedDrive.findFirst({
      where: {
        userId: params.actor.userId,
        sourceLiveLocationSessionId: session.id,
      },
    });

    if (existing) {
      return toDetail(existing, true);
    }

    const endedAt = session.stoppedAt ?? session.expiresAt;
    const durationSecs = driveDurationSeconds(session.startedAt, endedAt);

    const drive = await this.prisma.savedDrive.create({
      data: {
        userId: params.actor.userId,
        sourceLiveLocationSessionId: session.id,
        startedAt: session.startedAt,
        endedAt,
        durationSeconds: durationSecs,
        // Summary-only MVP — no route buffer yet.
        distanceMeters: null,
        averageSpeedMetersPerSecond: null,
        approximateStartArea: null,
        approximateEndArea: null,
        routeOverview: null,
      },
    });

    return toDetail(drive, true);
  }

  /**
   * Discard a drive session — deletes any temporary route data.
   * Does not create a saved drive.
   * Idempotent: safe to call multiple times.
   */
  public async discardDrive(params: {
    sessionId: string;
    actor: SavedDriveActor;
  }): Promise<void> {
    assertNotSuspendedOrDeleted(params.actor);

    // Verify session ownership before doing any discard work.
    const session = await this.prisma.liveLocationSession.findUnique({
      where: { id: params.sessionId },
    });

    if (!session) {
      // Idempotent: if the session doesn't exist, nothing to discard.
      return;
    }

    if (session.userId !== params.actor.userId) {
      throw new AppError(403, 'forbidden', 'You can only discard your own session.');
    }

    // TODO: Delete TemporaryDrivePoint records for this session here once
    //       that model is implemented.
    // await this.prisma.temporaryDrivePoint.deleteMany({
    //   where: { liveLocationSessionId: params.sessionId },
    // });
  }

  /**
   * List the authenticated user's saved drives, newest first.
   * Returns only summary items (no route overview in list).
   */
  public async listDrives(params: {
    actor: SavedDriveActor;
    page: number;
    pageSize: number;
  }): Promise<ListSavedDrivesResult> {
    assertNotSuspendedOrDeleted(params.actor);

    const take = Math.min(params.pageSize, MAX_SAVED_DRIVES_PAGE_SIZE);
    const skip = (params.page - 1) * take;

    const where = { userId: params.actor.userId };

    const [total, drives] = await this.prisma.$transaction([
      this.prisma.savedDrive.count({ where }),
      this.prisma.savedDrive.findMany({
        where,
        orderBy: { startedAt: 'desc' },
        skip,
        take,
      }),
    ]);

    return {
      drives: drives.map(toListItem),
      total,
      hasNext: skip + drives.length < total,
    };
  }

  /**
   * Get saved drive detail for the authenticated owner.
   * routeOverview is populated only for members.
   */
  public async getDrive(params: {
    driveId: string;
    actor: SavedDriveActor;
  }): Promise<SavedDriveDetail> {
    assertNotSuspendedOrDeleted(params.actor);

    const drive = await this.prisma.savedDrive.findUnique({
      where: { id: params.driveId },
    });

    if (!drive) {
      throw new AppError(404, 'not_found', 'Saved drive not found.');
    }

    if (drive.userId !== params.actor.userId) {
      throw new AppError(403, 'forbidden', 'You can only view your own saved drives.');
    }

    const isMember = canAccessMemberFeatures(params.actor);
    return toDetail(drive, isMember);
  }

  /**
   * Delete a saved drive owned by the authenticated user.
   * Also removes routeOverview data stored on the record.
   * Does not delete related live location or audit records.
   */
  public async deleteDrive(params: {
    driveId: string;
    actor: SavedDriveActor;
  }): Promise<void> {
    assertNotSuspendedOrDeleted(params.actor);

    const drive = await this.prisma.savedDrive.findUnique({
      where: { id: params.driveId },
      select: { userId: true },
    });

    if (!drive) {
      throw new AppError(404, 'not_found', 'Saved drive not found.');
    }

    if (drive.userId !== params.actor.userId) {
      throw new AppError(403, 'forbidden', 'You can only delete your own saved drives.');
    }

    await this.prisma.savedDrive.delete({ where: { id: params.driveId } });
  }

  /**
   * Delete expired temporary drive points whose expiresAt has passed.
   * Idempotent — safe to call repeatedly.
   * TODO: Wire this up to a scheduled job once TemporaryDrivePoint is implemented.
   * For now this is a no-op placeholder that documents where cleanup will live.
   */
  public async cleanupExpiredTemporaryPoints(_now: Date = new Date()): Promise<number> {
    // TODO: Implement once TemporaryDrivePoint model is added.
    // return this.prisma.temporaryDrivePoint.deleteMany({
    //   where: { expiresAt: { lte: now } },
    // }).then((r) => r.count);
    return 0;
  }

  /**
   * Return aggregate operational stats for admin use only.
   * Never includes individual drive details, routes, or user data.
   */
  public async getAdminStats(): Promise<{ totalSavedDriveCount: number }> {
    const totalSavedDriveCount = await this.prisma.savedDrive.count();
    return { totalSavedDriveCount };
  }

  /**
   * Helper: default page size constant exposed for route handlers.
   */
  public static readonly DEFAULT_PAGE_SIZE = DEFAULT_SAVED_DRIVES_PAGE_SIZE;
}
