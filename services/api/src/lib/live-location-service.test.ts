/* eslint-disable @typescript-eslint/no-explicit-any */
import assert from 'node:assert/strict';
import test from 'node:test';

import type { PrismaClient } from '@prisma/client';

import { LiveLocationService } from './live-location-service.js';

interface SessionRecord {
  id: string;
  userId: string;
  status: 'active' | 'stopped' | 'expired';
  startedAt: Date;
  expiresAt: Date;
  stoppedAt: Date | null;
  createdAt: Date;
  updatedAt: Date;
}

interface PositionRecord {
  id: string;
  sessionId: string;
  userId: string;
  latitude: number;
  longitude: number;
  accuracyMeters: number | null;
  headingDegrees: number | null;
  speedMetersPerSecond: number | null;
  recordedAt: Date;
  updatedAt: Date;
}

interface UserRecord {
  status: 'active' | 'warned' | 'temporarily_suspended' | 'permanently_suspended' | 'deleted';
  deletedAt: Date | null;
  displayName?: string | null;
}

function createFakePrisma(initial?: {
  sessions?: SessionRecord[];
  positions?: PositionRecord[];
  users?: Record<string, UserRecord>;
}) {
  const sessions = [...(initial?.sessions ?? [])];
  const positions = [...(initial?.positions ?? [])];
  const users = new Map(Object.entries(initial?.users ?? {}));
  let sessionCounter = sessions.length;
  let positionCounter = positions.length;

  const nowFromData = () => new Date('2026-06-09T12:00:00.000Z');

  const prisma = {
    $transaction: async (arg: unknown) => {
      if (typeof arg === 'function') {
        return arg(prisma);
      }
      return Promise.all(arg as Promise<unknown>[]);
    },
    liveLocationSession: {
      updateMany: async ({ where, data }: { where: any; data: any }) => {
        let count = 0;
        for (const session of sessions) {
          const matchStatus = where.status ? session.status === where.status : true;
          const matchUser = where.userId ? session.userId === where.userId : true;
          const matchExpires = where.expiresAt?.lte ? session.expiresAt <= where.expiresAt.lte : true;
          const matchIdIn = where.id?.in ? where.id.in.includes(session.id) : true;
          if (matchStatus && matchUser && matchExpires && matchIdIn) {
            session.status = data.status ?? session.status;
            session.stoppedAt = data.stoppedAt ?? session.stoppedAt;
            session.updatedAt = nowFromData();
            count += 1;
          }
        }
        return { count };
      },
      findMany: async ({ where, select }: { where: any; select?: any }) => {
        const filtered = sessions.filter((session) => {
          const matchUser = where.userId ? session.userId === where.userId : true;
          const matchStatus = where.status ? session.status === where.status : true;
          return matchUser && matchStatus;
        });
        if (select?.id) {
          return filtered.map((session) => ({ id: session.id }));
        }
        return filtered;
      },
      create: async ({ data }: { data: any }) => {
        sessionCounter += 1;
        const created: SessionRecord = {
          id: `session-${sessionCounter}`,
          userId: data.userId,
          status: data.status,
          startedAt: data.startedAt,
          expiresAt: data.expiresAt,
          stoppedAt: null,
          createdAt: nowFromData(),
          updatedAt: nowFromData(),
        };
        sessions.push(created);
        return created;
      },
      findUnique: async ({ where }: { where: any }) => sessions.find((session) => session.id === where.id) ?? null,
      update: async ({ where, data }: { where: any; data: any }) => {
        const session = sessions.find((candidate) => candidate.id === where.id);
        if (!session) {
          throw new Error('session not found');
        }
        session.status = data.status ?? session.status;
        session.stoppedAt = data.stoppedAt ?? session.stoppedAt;
        session.updatedAt = nowFromData();
        return session;
      },
      count: async ({ where }: { where: any }) => {
        return sessions.filter((session) => {
          const matchStatus = where.status ? session.status === where.status : true;
          const matchExpiresGt = where.expiresAt?.gt ? session.expiresAt > where.expiresAt.gt : true;
          return matchStatus && matchExpiresGt;
        }).length;
      },
    },
    liveLocationLatestPosition: {
      deleteMany: async ({ where }: { where: any }) => {
        const before = positions.length;
        if (where.sessionId?.in) {
          const allowed = new Set(where.sessionId.in as string[]);
          for (let index = positions.length - 1; index >= 0; index -= 1) {
            if (allowed.has(positions[index]!.sessionId)) {
              positions.splice(index, 1);
            }
          }
        } else if (where.sessionId) {
          for (let index = positions.length - 1; index >= 0; index -= 1) {
            if (positions[index]!.sessionId === where.sessionId) {
              positions.splice(index, 1);
            }
          }
        } else if (where.session?.status === 'expired') {
          const expiredSessionIds = new Set(sessions.filter((session) => session.status === 'expired').map((session) => session.id));
          for (let index = positions.length - 1; index >= 0; index -= 1) {
            if (expiredSessionIds.has(positions[index]!.sessionId)) {
              positions.splice(index, 1);
            }
          }
        }
        return { count: before - positions.length };
      },
      upsert: async ({ where, create, update }: { where: any; create: any; update: any }) => {
        const existing = positions.find((position) => position.sessionId === where.sessionId);
        if (existing) {
          existing.latitude = update.latitude;
          existing.longitude = update.longitude;
          existing.accuracyMeters = update.accuracyMeters ?? null;
          existing.headingDegrees = update.headingDegrees ?? null;
          existing.speedMetersPerSecond = update.speedMetersPerSecond ?? null;
          existing.recordedAt = update.recordedAt;
          existing.updatedAt = nowFromData();
          return existing;
        }

        positionCounter += 1;
        const created: PositionRecord = {
          id: `position-${positionCounter}`,
          sessionId: create.sessionId,
          userId: create.userId,
          latitude: create.latitude,
          longitude: create.longitude,
          accuracyMeters: create.accuracyMeters ?? null,
          headingDegrees: create.headingDegrees ?? null,
          speedMetersPerSecond: create.speedMetersPerSecond ?? null,
          recordedAt: create.recordedAt,
          updatedAt: nowFromData(),
        };
        positions.push(created);
        return created;
      },
      count: async ({ where }: { where: any }) => {
        return positions.filter((position) => {
          if (where.userId?.not && position.userId === where.userId.not) {
            return false;
          }
          if (where.recordedAt?.gte && position.recordedAt < where.recordedAt.gte) {
            return false;
          }
          const session = sessions.find((candidate) => candidate.id === position.sessionId);
          if (!session) {
            return false;
          }
          if (where.session?.status && session.status !== where.session.status) {
            return false;
          }
          if (where.session?.expiresAt?.gt && !(session.expiresAt > where.session.expiresAt.gt)) {
            return false;
          }
          const user = users.get(position.userId);
          if (where.session?.user?.status?.in && user && !where.session.user.status.in.includes(user.status)) {
            return false;
          }
          return true;
        }).length;
      },
      findMany: async ({ where, skip, take }: { where: any; skip: number; take: number }) => {
        const filtered = positions
          .filter((position) => {
            if (where.userId?.not && position.userId === where.userId.not) {
              return false;
            }
            if (where.recordedAt?.gte && position.recordedAt < where.recordedAt.gte) {
              return false;
            }
            const session = sessions.find((candidate) => candidate.id === position.sessionId);
            if (!session) {
              return false;
            }
            if (where.session?.status && session.status !== where.session.status) {
              return false;
            }
            if (where.session?.expiresAt?.gt && !(session.expiresAt > where.session.expiresAt.gt)) {
              return false;
            }
            const user = users.get(position.userId);
            if (where.session?.user?.status?.in && user && !where.session.user.status.in.includes(user.status)) {
              return false;
            }
            return true;
          })
          .sort((a, b) => b.updatedAt.getTime() - a.updatedAt.getTime());

        return filtered.slice(skip, skip + take).map((position) => {
          const session = sessions.find((candidate) => candidate.id === position.sessionId);
          const user = users.get(position.userId);
          return {
            ...position,
            session: session ? { expiresAt: session.expiresAt } : undefined,
            user: user ? { displayName: user.displayName ?? null } : undefined,
          };
        });
      },
      findFirst: async () => {
        const sorted = [...positions].sort((a, b) => b.updatedAt.getTime() - a.updatedAt.getTime());
        return sorted[0] ? { updatedAt: sorted[0].updatedAt } : null;
      },
    },
    __data: {
      sessions,
      positions,
    },
  };

  return prisma;
}

test('start session creates an active session with expected expiry', async () => {
  const fakePrisma = createFakePrisma({
    users: {
      'user-1': { status: 'active', deletedAt: null },
    },
  });
  const service = new LiveLocationService(fakePrisma as unknown as PrismaClient);

  const result = await service.startSession({
    userId: 'user-1',
    duration: '2h',
    now: new Date('2026-06-09T10:00:00.000Z'),
  });

  assert.equal(result.session.status, 'active');
  assert.equal(result.session.startedAt, '2026-06-09T10:00:00.000Z');
  assert.equal(result.session.expiresAt, '2026-06-09T12:00:00.000Z');
});

test('second position update replaces previous latest position', async () => {
  const fakePrisma = createFakePrisma({
    sessions: [
      {
        id: 'session-1',
        userId: 'user-1',
        status: 'active',
        startedAt: new Date('2026-06-09T10:00:00.000Z'),
        expiresAt: new Date('2026-06-09T12:00:00.000Z'),
        stoppedAt: null,
        createdAt: new Date('2026-06-09T10:00:00.000Z'),
        updatedAt: new Date('2026-06-09T10:00:00.000Z'),
      },
    ],
    users: {
      'user-1': { status: 'active', deletedAt: null },
    },
  });

  const service = new LiveLocationService(fakePrisma as unknown as PrismaClient);

  await service.updateLatestPosition({
    sessionId: 'session-1',
    userId: 'user-1',
    coordinate: {
      latitude: 57.7,
      longitude: 12.0,
      recordedAt: '2026-06-09T10:10:00.000Z',
    },
    now: new Date('2026-06-09T10:15:00.000Z'),
  });

  const second = await service.updateLatestPosition({
    sessionId: 'session-1',
    userId: 'user-1',
    coordinate: {
      latitude: 57.8,
      longitude: 12.1,
      recordedAt: '2026-06-09T10:11:00.000Z',
    },
    now: new Date('2026-06-09T10:16:00.000Z'),
  });

  assert.equal(fakePrisma.__data.positions.length, 1);
  assert.equal(fakePrisma.__data.positions[0]?.latitude, 57.8);
  assert.equal(second.latestPosition?.latitude, 57.8);
});

test('stop session deletes latest position', async () => {
  const fakePrisma = createFakePrisma({
    sessions: [
      {
        id: 'session-1',
        userId: 'user-1',
        status: 'active',
        startedAt: new Date('2026-06-09T10:00:00.000Z'),
        expiresAt: new Date('2026-06-09T12:00:00.000Z'),
        stoppedAt: null,
        createdAt: new Date('2026-06-09T10:00:00.000Z'),
        updatedAt: new Date('2026-06-09T10:00:00.000Z'),
      },
    ],
    positions: [
      {
        id: 'position-1',
        sessionId: 'session-1',
        userId: 'user-1',
        latitude: 57.7,
        longitude: 12.0,
        accuracyMeters: null,
        headingDegrees: null,
        speedMetersPerSecond: null,
        recordedAt: new Date('2026-06-09T10:05:00.000Z'),
        updatedAt: new Date('2026-06-09T10:05:00.000Z'),
      },
    ],
    users: {
      'user-1': { status: 'active', deletedAt: null },
    },
  });

  const service = new LiveLocationService(fakePrisma as unknown as PrismaClient);

  const result = await service.stopSession({
    sessionId: 'session-1',
    userId: 'user-1',
    now: new Date('2026-06-09T10:30:00.000Z'),
  });

  assert.equal(result.session.status, 'stopped');
  assert.equal(result.latestPositionRemoved, true);
  assert.equal(fakePrisma.__data.positions.length, 0);
});

test('hide me now stops active sessions and deletes latest positions', async () => {
  const fakePrisma = createFakePrisma({
    sessions: [
      {
        id: 'session-1',
        userId: 'user-1',
        status: 'active',
        startedAt: new Date('2026-06-09T10:00:00.000Z'),
        expiresAt: new Date('2026-06-09T12:00:00.000Z'),
        stoppedAt: null,
        createdAt: new Date('2026-06-09T10:00:00.000Z'),
        updatedAt: new Date('2026-06-09T10:00:00.000Z'),
      },
      {
        id: 'session-2',
        userId: 'user-1',
        status: 'active',
        startedAt: new Date('2026-06-09T11:00:00.000Z'),
        expiresAt: new Date('2026-06-09T13:00:00.000Z'),
        stoppedAt: null,
        createdAt: new Date('2026-06-09T11:00:00.000Z'),
        updatedAt: new Date('2026-06-09T11:00:00.000Z'),
      },
    ],
    positions: [
      {
        id: 'position-1',
        sessionId: 'session-1',
        userId: 'user-1',
        latitude: 57.7,
        longitude: 12.0,
        accuracyMeters: null,
        headingDegrees: null,
        speedMetersPerSecond: null,
        recordedAt: new Date('2026-06-09T10:05:00.000Z'),
        updatedAt: new Date('2026-06-09T10:05:00.000Z'),
      },
      {
        id: 'position-2',
        sessionId: 'session-2',
        userId: 'user-1',
        latitude: 57.9,
        longitude: 12.2,
        accuracyMeters: null,
        headingDegrees: null,
        speedMetersPerSecond: null,
        recordedAt: new Date('2026-06-09T11:05:00.000Z'),
        updatedAt: new Date('2026-06-09T11:05:00.000Z'),
      },
    ],
    users: {
      'user-1': { status: 'active', deletedAt: null },
    },
  });

  const service = new LiveLocationService(fakePrisma as unknown as PrismaClient);

  const result = await service.hideMeNow({
    userId: 'user-1',
    now: new Date('2026-06-09T11:30:00.000Z'),
  });

  assert.equal(result.stoppedSessionCount, 2);
  assert.equal(result.removedLatestPositionCount, 2);
  assert.equal(fakePrisma.__data.positions.length, 0);
});

test('expired sessions are excluded from visible markers', async () => {
  const fakePrisma = createFakePrisma({
    sessions: [
      {
        id: 'session-active',
        userId: 'user-2',
        status: 'active',
        startedAt: new Date('2026-06-09T10:00:00.000Z'),
        expiresAt: new Date('2026-06-09T14:00:00.000Z'),
        stoppedAt: null,
        createdAt: new Date('2026-06-09T10:00:00.000Z'),
        updatedAt: new Date('2026-06-09T10:00:00.000Z'),
      },
      {
        id: 'session-expired',
        userId: 'user-3',
        status: 'active',
        startedAt: new Date('2026-06-09T08:00:00.000Z'),
        expiresAt: new Date('2026-06-09T09:00:00.000Z'),
        stoppedAt: null,
        createdAt: new Date('2026-06-09T08:00:00.000Z'),
        updatedAt: new Date('2026-06-09T08:00:00.000Z'),
      },
    ],
    positions: [
      {
        id: 'position-1',
        sessionId: 'session-active',
        userId: 'user-2',
        latitude: 57.7,
        longitude: 12.0,
        accuracyMeters: null,
        headingDegrees: null,
        speedMetersPerSecond: null,
        recordedAt: new Date('2026-06-09T11:29:45.000Z'),
        updatedAt: new Date('2026-06-09T11:29:45.000Z'),
      },
      {
        id: 'position-2',
        sessionId: 'session-expired',
        userId: 'user-3',
        latitude: 57.1,
        longitude: 12.4,
        accuracyMeters: null,
        headingDegrees: null,
        speedMetersPerSecond: null,
        recordedAt: new Date('2026-06-09T08:30:00.000Z'),
        updatedAt: new Date('2026-06-09T08:30:00.000Z'),
      },
    ],
    users: {
      'user-1': { status: 'active', deletedAt: null },
      'user-2': { status: 'active', deletedAt: null },
      'user-3': { status: 'active', deletedAt: null },
    },
  });

  const service = new LiveLocationService(fakePrisma as unknown as PrismaClient);

  const result = await service.getVisibleMarkers({
    viewer: {
      userId: 'user-1',
      role: 'user',
      status: 'active',
      subscriptionEntitlement: 'member_monthly',
    },
    page: 1,
    pageSize: 20,
    now: new Date('2026-06-09T11:30:00.000Z'),
  });

  assert.equal(result.markers.length, 1);
  assert.equal(result.markers[0]?.sessionId, 'session-active');
});

test('free user cannot view other live location markers', async () => {
  const fakePrisma = createFakePrisma({ users: { 'user-1': { status: 'active', deletedAt: null } } });
  const service = new LiveLocationService(fakePrisma as unknown as PrismaClient);

  await assert.rejects(
    () =>
      service.getVisibleMarkers({
        viewer: {
          userId: 'user-1',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'none',
        },
        page: 1,
        pageSize: 20,
      }),
    (error: unknown) => {
      const typed = error as { message?: string };
      return typed.message === 'Member subscription required.';
    },
  );
});

test('admin without member entitlement cannot view other live location markers', async () => {
  const fakePrisma = createFakePrisma({ users: { 'admin-1': { status: 'active', deletedAt: null } } });
  const service = new LiveLocationService(fakePrisma as unknown as PrismaClient);

  await assert.rejects(
    () =>
      service.getVisibleMarkers({
        viewer: {
          userId: 'admin-1',
          role: 'admin',
          status: 'active',
          subscriptionEntitlement: 'none',
        },
        page: 1,
        pageSize: 20,
      }),
    (error: unknown) => {
      const typed = error as { message?: string };
      return typed.message === 'Member subscription required.';
    },
  );
});

test('member user can view live location markers and admin summary excludes exact tracking data', async () => {
  const fakePrisma = createFakePrisma({
    sessions: [
      {
        id: 'session-active',
        userId: 'user-2',
        status: 'active',
        startedAt: new Date('2026-06-09T10:00:00.000Z'),
        expiresAt: new Date('2026-06-09T14:00:00.000Z'),
        stoppedAt: null,
        createdAt: new Date('2026-06-09T10:00:00.000Z'),
        updatedAt: new Date('2026-06-09T10:00:00.000Z'),
      },
    ],
    positions: [
      {
        id: 'position-1',
        sessionId: 'session-active',
        userId: 'user-2',
        latitude: 57.7,
        longitude: 12.0,
        accuracyMeters: null,
        headingDegrees: null,
        speedMetersPerSecond: null,
        recordedAt: new Date('2026-06-09T11:29:45.000Z'),
        updatedAt: new Date('2026-06-09T11:29:45.000Z'),
      },
    ],
    users: {
      'user-1': { status: 'active', deletedAt: null },
      'user-2': { status: 'active', deletedAt: null },
    },
  });

  const service = new LiveLocationService(fakePrisma as unknown as PrismaClient);

  const markers = await service.getVisibleMarkers({
    viewer: {
      userId: 'user-1',
      role: 'user',
      status: 'active',
      subscriptionEntitlement: 'member_monthly',
    },
    page: 1,
    pageSize: 20,
    now: new Date('2026-06-09T11:30:00.000Z'),
  });

  assert.equal(markers.markers.length, 1);

  const summary = await service.getAdminSummary(new Date('2026-06-09T11:30:00.000Z'));

  assert.deepEqual(Object.keys(summary).sort(), ['activeSessionCount', 'expiredSessionCount', 'latestPositionUpdatedAt']);
  assert.equal(summary.activeSessionCount, 1);
  assert.equal(summary.latestPositionUpdatedAt, '2026-06-09T11:29:45.000Z');
});

test('suspended user cannot access live location markers', async () => {
  const fakePrisma = createFakePrisma({ users: { 'user-1': { status: 'temporarily_suspended', deletedAt: null } } });
  const service = new LiveLocationService(fakePrisma as unknown as PrismaClient);

  await assert.rejects(
    () =>
      service.getVisibleMarkers({
        viewer: {
          userId: 'user-1',
          role: 'user',
          status: 'temporarily_suspended',
          subscriptionEntitlement: 'member_monthly',
        },
        page: 1,
        pageSize: 20,
      }),
    (error: unknown) => {
      const typed = error as { message?: string };
      return typed.message === 'Your account has been suspended.';
    },
  );
});

test('stale positions older than the threshold are excluded from visible markers', async () => {
  const now = new Date('2026-06-09T11:30:00.000Z');
  const staleRecordedAt = new Date(now.getTime() - 120_000); // 2 minutes ago — beyond 60s threshold
  const freshRecordedAt = new Date(now.getTime() - 20_000);  // 20 seconds ago — within threshold

  const fakePrisma = createFakePrisma({
    sessions: [
      {
        id: 'session-fresh',
        userId: 'user-2',
        status: 'active',
        startedAt: new Date('2026-06-09T10:00:00.000Z'),
        expiresAt: new Date('2026-06-09T14:00:00.000Z'),
        stoppedAt: null,
        createdAt: new Date('2026-06-09T10:00:00.000Z'),
        updatedAt: now,
      },
      {
        id: 'session-stale',
        userId: 'user-3',
        status: 'active',
        startedAt: new Date('2026-06-09T10:00:00.000Z'),
        expiresAt: new Date('2026-06-09T14:00:00.000Z'),
        stoppedAt: null,
        createdAt: new Date('2026-06-09T10:00:00.000Z'),
        updatedAt: staleRecordedAt,
      },
    ],
    positions: [
      {
        id: 'position-fresh',
        sessionId: 'session-fresh',
        userId: 'user-2',
        latitude: 57.7,
        longitude: 12.0,
        accuracyMeters: null,
        headingDegrees: null,
        speedMetersPerSecond: null,
        recordedAt: freshRecordedAt,
        updatedAt: freshRecordedAt,
      },
      {
        id: 'position-stale',
        sessionId: 'session-stale',
        userId: 'user-3',
        latitude: 57.8,
        longitude: 12.1,
        accuracyMeters: null,
        headingDegrees: null,
        speedMetersPerSecond: null,
        recordedAt: staleRecordedAt,
        updatedAt: staleRecordedAt,
      },
    ],
    users: {
      'user-1': { status: 'active', deletedAt: null },
      'user-2': { status: 'active', deletedAt: null },
      'user-3': { status: 'active', deletedAt: null },
    },
  });

  const service = new LiveLocationService(fakePrisma as unknown as PrismaClient);

  const result = await service.getVisibleMarkers({
    viewer: {
      userId: 'user-1',
      role: 'user',
      status: 'active',
      subscriptionEntitlement: 'member_monthly',
    },
    page: 1,
    pageSize: 20,
    now,
  });

  assert.equal(result.markers.length, 1);
  assert.equal(result.markers[0]?.sessionId, 'session-fresh');
});

test('visible markers include expiresAt and displayName from session and user', async () => {
  const now = new Date('2026-06-09T11:30:00.000Z');
  const freshRecordedAt = new Date(now.getTime() - 15_000);

  const fakePrisma = createFakePrisma({
    sessions: [
      {
        id: 'session-active',
        userId: 'user-2',
        status: 'active',
        startedAt: new Date('2026-06-09T10:00:00.000Z'),
        expiresAt: new Date('2026-06-09T14:00:00.000Z'),
        stoppedAt: null,
        createdAt: new Date('2026-06-09T10:00:00.000Z'),
        updatedAt: freshRecordedAt,
      },
    ],
    positions: [
      {
        id: 'position-1',
        sessionId: 'session-active',
        userId: 'user-2',
        latitude: 57.7,
        longitude: 12.0,
        accuracyMeters: null,
        headingDegrees: null,
        speedMetersPerSecond: null,
        recordedAt: freshRecordedAt,
        updatedAt: freshRecordedAt,
      },
    ],
    users: {
      'user-1': { status: 'active', deletedAt: null },
      'user-2': { status: 'active', deletedAt: null, displayName: 'Seb' },
    },
  });

  const service = new LiveLocationService(fakePrisma as unknown as PrismaClient);

  const result = await service.getVisibleMarkers({
    viewer: {
      userId: 'user-1',
      role: 'user',
      status: 'active',
      subscriptionEntitlement: 'member_monthly',
    },
    page: 1,
    pageSize: 20,
    now,
  });

  assert.equal(result.markers.length, 1);
  assert.equal(result.markers[0]?.displayName, 'Seb');
  assert.equal(result.markers[0]?.expiresAt, '2026-06-09T14:00:00.000Z');
});

test('visible markers exclude sensitive fields (email, subscription, session tokens)', async () => {
  const now = new Date('2026-06-09T11:30:00.000Z');
  const freshRecordedAt = new Date(now.getTime() - 10_000);

  const fakePrisma = createFakePrisma({
    sessions: [
      {
        id: 'session-active',
        userId: 'user-2',
        status: 'active',
        startedAt: new Date('2026-06-09T10:00:00.000Z'),
        expiresAt: new Date('2026-06-09T14:00:00.000Z'),
        stoppedAt: null,
        createdAt: new Date('2026-06-09T10:00:00.000Z'),
        updatedAt: freshRecordedAt,
      },
    ],
    positions: [
      {
        id: 'position-1',
        sessionId: 'session-active',
        userId: 'user-2',
        latitude: 57.7,
        longitude: 12.0,
        accuracyMeters: null,
        headingDegrees: null,
        speedMetersPerSecond: null,
        recordedAt: freshRecordedAt,
        updatedAt: freshRecordedAt,
      },
    ],
    users: {
      'user-1': { status: 'active', deletedAt: null },
      'user-2': { status: 'active', deletedAt: null },
    },
  });

  const service = new LiveLocationService(fakePrisma as unknown as PrismaClient);

  const result = await service.getVisibleMarkers({
    viewer: {
      userId: 'user-1',
      role: 'user',
      status: 'active',
      subscriptionEntitlement: 'member_monthly',
    },
    page: 1,
    pageSize: 20,
    now,
  });

  assert.equal(result.markers.length, 1);
  const marker = result.markers[0]!;
  // Must not include sensitive fields
  assert.ok(!('email' in marker), 'marker must not include email');
  assert.ok(!('subscriptionEntitlement' in marker), 'marker must not include subscriptionEntitlement');
  assert.ok(!('tokenHash' in marker), 'marker must not include session token');
  assert.ok(!('identities' in marker), 'marker must not include identity provider data');
  // Allowed safe fields
  assert.ok('userId' in marker);
  assert.ok('sessionId' in marker);
  assert.ok('coordinate' in marker);
  assert.ok('status' in marker);
});
