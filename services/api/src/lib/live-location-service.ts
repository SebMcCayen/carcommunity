import type {
  LiveLocationLatestPosition,
  LiveLocationSession,
  Prisma,
  PrismaClient,
} from '@prisma/client';
import {
  LIVE_LOCATION_DURATIONS,
  LIVE_LOCATION_MARKER_STALE_THRESHOLD_MS,
  getLiveLocationDurationMs,
  type LiveLocationCoordinate,
  type LiveLocationDuration,
  type LiveLocationSessionSummary,
  type PublicLiveLocationMarker,
} from '@carcommunity/shared/live-location';
import {
  canViewOtherLiveLocations,
  isSuspendedStatus,
  type SubscriptionEntitlement,
  type UserRole,
  type UserStatus,
} from '@carcommunity/shared/users';

import { AppError } from './errors.js';

export const LIVE_LOCATION_DATABASE_META = {
  source: 'database',
  productionReady: true,
  ttlCleanupPrepared: true,
} as const;

/**
 * Service-level upper bound for marker query size.
 * Effective response size is min(route-level pageSize validation, this cap).
 */
export const LIVE_LOCATION_MAX_MARKER_COUNT = 100;

export interface LiveLocationViewer {
  userId: string;
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
}

export interface StartSessionResult {
  session: LiveLocationSessionSummary;
  latestPosition: LiveLocationCoordinate | null;
  latestPositionRemoved: boolean;
}

export type UpdatePositionResult = StartSessionResult;
export type StopSessionResult = StartSessionResult;

export interface HideMeNowResult {
  stoppedSessionCount: number;
  removedLatestPositionCount: number;
}

export interface VisibleMarkersResult {
  markers: PublicLiveLocationMarker[];
  total: number;
  hasNext: boolean;
  generatedAt: string;
}

export interface AdminLiveLocationSummaryResult {
  activeSessionCount: number;
  expiredSessionCount: number;
  latestPositionUpdatedAt: string | null;
}

/**
 * Returns the cutoff Date before which a latest position is considered stale.
 * Positions recorded before this threshold are excluded from marker responses.
 */
function calculateStaleThreshold(now: Date): Date {
  return new Date(now.getTime() - LIVE_LOCATION_MARKER_STALE_THRESHOLD_MS);
}

function inferDuration(session: Pick<LiveLocationSession, 'startedAt' | 'expiresAt'>): LiveLocationDuration {
  const durationMs = session.expiresAt.getTime() - session.startedAt.getTime();
  let selectedDuration: LiveLocationDuration = '1h';
  let smallestDifference = Number.POSITIVE_INFINITY;

  for (const candidate of LIVE_LOCATION_DURATIONS) {
    const difference = Math.abs(durationMs - getLiveLocationDurationMs(candidate));
    if (difference < smallestDifference) {
      smallestDifference = difference;
      selectedDuration = candidate;
    }
  }

  return selectedDuration;
}

function toSessionSummary(session: LiveLocationSession): LiveLocationSessionSummary {
  return {
    id: session.id,
    status: session.status,
    duration: inferDuration(session),
    startedAt: session.startedAt.toISOString(),
    expiresAt: session.expiresAt.toISOString(),
    stoppedAt: session.stoppedAt ? session.stoppedAt.toISOString() : null,
  };
}

function toCoordinate(position: LiveLocationLatestPosition): LiveLocationCoordinate {
  return {
    latitude: position.latitude,
    longitude: position.longitude,
    accuracyMeters: optionalNumber(position.accuracyMeters),
    headingDegrees: optionalNumber(position.headingDegrees),
    speedMetersPerSecond: optionalNumber(position.speedMetersPerSecond),
    recordedAt: position.recordedAt.toISOString(),
  };
}

function optionalNumber(value: number | null): number | undefined {
  return value ?? undefined;
}

function toPositionWriteData(coordinate: LiveLocationCoordinate): {
  latitude: number;
  longitude: number;
  accuracyMeters: number | undefined;
  headingDegrees: number | undefined;
  speedMetersPerSecond: number | undefined;
  recordedAt: Date;
} {
  return {
    latitude: coordinate.latitude,
    longitude: coordinate.longitude,
    accuracyMeters: coordinate.accuracyMeters,
    headingDegrees: coordinate.headingDegrees,
    speedMetersPerSecond: coordinate.speedMetersPerSecond,
    recordedAt: new Date(coordinate.recordedAt),
  };
}

export class LiveLocationService {
  constructor(private readonly prisma: PrismaClient) {}

  public async expireSessions(now: Date = new Date()): Promise<number> {
    const expired = await this.prisma.liveLocationSession.updateMany({
      where: {
        status: 'active',
        expiresAt: {
          lte: now,
        },
      },
      data: {
        status: 'expired',
        stoppedAt: now,
      },
    });

    if (expired.count > 0) {
      await this.prisma.liveLocationLatestPosition.deleteMany({
        where: {
          session: {
            status: 'expired',
          },
        },
      });
    }

    return expired.count;
  }

  public async startSession(params: {
    userId: string;
    duration: LiveLocationDuration;
    now?: Date;
  }): Promise<StartSessionResult> {
    const now = params.now ?? new Date();
    const expiresAt = new Date(now.getTime() + getLiveLocationDurationMs(params.duration));

    await this.expireSessions(now);

    return this.prisma.$transaction(async (tx) => {
      const existingActiveSessions = await tx.liveLocationSession.findMany({
        where: {
          userId: params.userId,
          status: 'active',
        },
        select: {
          id: true,
        },
      });

      let latestPositionRemoved = false;

      if (existingActiveSessions.length > 0) {
        const activeSessionIds = existingActiveSessions.map((session) => session.id);

        const deletedLatestPositions = await tx.liveLocationLatestPosition.deleteMany({
          where: {
            sessionId: {
              in: activeSessionIds,
            },
          },
        });

        await tx.liveLocationSession.updateMany({
          where: {
            id: {
              in: activeSessionIds,
            },
          },
          data: {
            status: 'stopped',
            stoppedAt: now,
          },
        });

        latestPositionRemoved = deletedLatestPositions.count > 0;
      }

      const session = await tx.liveLocationSession.create({
        data: {
          userId: params.userId,
          status: 'active',
          startedAt: now,
          expiresAt,
        },
      });

      return {
        session: toSessionSummary(session),
        latestPosition: null,
        latestPositionRemoved,
      };
    });
  }

  public async updateLatestPosition(params: {
    sessionId: string;
    userId: string;
    coordinate: LiveLocationCoordinate;
    now?: Date;
  }): Promise<UpdatePositionResult> {
    const now = params.now ?? new Date();

    await this.expireSessions(now);

    const session = await this.prisma.liveLocationSession.findUnique({
      where: {
        id: params.sessionId,
      },
    });

    if (!session) {
      throw new AppError(404, 'not_found', 'Live location session not found.');
    }

    if (session.userId !== params.userId) {
      throw new AppError(403, 'forbidden', 'You can only update your own live location session.');
    }

    if (session.status !== 'active' || session.expiresAt <= now) {
      throw new AppError(403, 'forbidden', 'Live location session is not active.');
    }

    const positionData = toPositionWriteData(params.coordinate);

    const position = await this.prisma.liveLocationLatestPosition.upsert({
      where: {
        sessionId: session.id,
      },
      create: {
        sessionId: session.id,
        userId: session.userId,
        ...positionData,
      },
      update: positionData,
    });

    return {
      session: toSessionSummary(session),
      latestPosition: toCoordinate(position),
      latestPositionRemoved: false,
    };
  }

  public async stopSession(params: { sessionId: string; userId: string; now?: Date }): Promise<StopSessionResult> {
    const now = params.now ?? new Date();

    await this.expireSessions(now);

    return this.prisma.$transaction(async (tx) => {
      const session = await tx.liveLocationSession.findUnique({
        where: {
          id: params.sessionId,
        },
      });

      if (!session) {
        throw new AppError(404, 'not_found', 'Live location session not found.');
      }

      if (session.userId !== params.userId) {
        throw new AppError(403, 'forbidden', 'You can only stop your own live location session.');
      }

      let finalSession = session;

      if (session.status === 'active') {
        finalSession = await tx.liveLocationSession.update({
          where: {
            id: session.id,
          },
          data: {
            status: session.expiresAt <= now ? 'expired' : 'stopped',
            stoppedAt: now,
          },
        });
      }

      const deletedLatestPosition = await tx.liveLocationLatestPosition.deleteMany({
        where: {
          sessionId: session.id,
        },
      });

      return {
        session: toSessionSummary(finalSession),
        latestPosition: null,
        latestPositionRemoved: deletedLatestPosition.count > 0,
      };
    });
  }

  public async hideMeNow(params: { userId: string; now?: Date }): Promise<HideMeNowResult> {
    const now = params.now ?? new Date();

    await this.expireSessions(now);

    return this.prisma.$transaction(async (tx) => {
      const activeSessions = await tx.liveLocationSession.findMany({
        where: {
          userId: params.userId,
          status: 'active',
        },
        select: {
          id: true,
        },
      });

      if (activeSessions.length === 0) {
        return {
          stoppedSessionCount: 0,
          removedLatestPositionCount: 0,
        };
      }

      const activeSessionIds = activeSessions.map((session) => session.id);

      const deletedLatestPositions = await tx.liveLocationLatestPosition.deleteMany({
        where: {
          sessionId: {
            in: activeSessionIds,
          },
        },
      });

      const stoppedSessions = await tx.liveLocationSession.updateMany({
        where: {
          id: {
            in: activeSessionIds,
          },
        },
        data: {
          status: 'stopped',
          stoppedAt: now,
        },
      });

      return {
        stoppedSessionCount: stoppedSessions.count,
        removedLatestPositionCount: deletedLatestPositions.count,
      };
    });
  }

  public async getVisibleMarkers(params: {
    viewer: LiveLocationViewer;
    page: number;
    pageSize: number;
    now?: Date;
  }): Promise<VisibleMarkersResult> {
    if (params.viewer.status === 'deleted') {
      throw new AppError(403, 'forbidden', 'Your account has been deleted.');
    }

    if (isSuspendedStatus(params.viewer.status)) {
      throw new AppError(403, 'suspended', 'Your account has been suspended.');
    }

    if (!canViewOtherLiveLocations(params.viewer)) {
      throw new AppError(403, 'forbidden', 'Member subscription required.');
    }

    const now = params.now ?? new Date();
    const staleThreshold = calculateStaleThreshold(now);
    const take = Math.min(params.pageSize, LIVE_LOCATION_MAX_MARKER_COUNT);
    const skip = (params.page - 1) * take;

    await this.expireSessions(now);

    const where: Prisma.LiveLocationLatestPositionWhereInput = {
      userId: {
        not: params.viewer.userId,
      },
      recordedAt: {
        gte: staleThreshold,
      },
      session: {
        status: 'active',
        expiresAt: {
          gt: now,
        },
        user: {
          status: {
            in: ['active', 'warned'],
          },
          deletedAt: null,
        },
      },
    };

    const [total, positions] = await this.prisma.$transaction([
      this.prisma.liveLocationLatestPosition.count({ where }),
      this.prisma.liveLocationLatestPosition.findMany({
        where,
        orderBy: {
          updatedAt: 'desc',
        },
        skip,
        take,
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
            select: {
              expiresAt: true,
            },
          },
          user: {
            select: {
              displayName: true,
            },
          },
        },
      }),
    ]);

    return {
      markers: positions.map((position) => ({
        userId: position.userId,
        sessionId: position.sessionId,
        status: 'active',
        displayName: position.user.displayName,
        expiresAt: position.session.expiresAt.toISOString(),
        coordinate: {
          latitude: position.latitude,
          longitude: position.longitude,
          accuracyMeters: optionalNumber(position.accuracyMeters),
          headingDegrees: optionalNumber(position.headingDegrees),
          speedMetersPerSecond: optionalNumber(position.speedMetersPerSecond),
          recordedAt: position.recordedAt.toISOString(),
        },
      })),
      total,
      hasNext: skip + positions.length < total,
      generatedAt: now.toISOString(),
    };
  }

  public async getAdminSummary(now: Date = new Date()): Promise<AdminLiveLocationSummaryResult> {
    await this.expireSessions(now);

    const [activeSessionCount, expiredSessionCount, latestPosition] = await this.prisma.$transaction([
      this.prisma.liveLocationSession.count({
        where: {
          status: 'active',
          expiresAt: {
            gt: now,
          },
        },
      }),
      this.prisma.liveLocationSession.count({
        where: {
          status: 'expired',
        },
      }),
      this.prisma.liveLocationLatestPosition.findFirst({
        orderBy: {
          updatedAt: 'desc',
        },
        select: {
          updatedAt: true,
        },
      }),
    ]);

    return {
      activeSessionCount,
      expiredSessionCount,
      latestPositionUpdatedAt: latestPosition?.updatedAt.toISOString() ?? null,
    };
  }
}
