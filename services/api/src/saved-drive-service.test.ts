/**
 * SavedDriveService unit tests using a fake Prisma client.
 *
 * Covers:
 *  - Stop does not auto-save a drive (service has no stop method)
 *  - Save requires member entitlement
 *  - Discard does not create a saved drive
 *  - User cannot access another user's saved drive
 *  - Duplicate save handled idempotently (existing record returned)
 *  - Free-user blocked from saving
 *  - Suspended/deleted users blocked
 *  - List is owner-scoped
 *  - Detail returns routeOverview for members, null for free users
 *  - Cleanup returns 0 (no-op until TemporaryDrivePoint is implemented)
 *  - Top speed is never stored or returned
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { SavedDriveService } from './lib/saved-drive-service.js';
import type { SavedDriveActor } from './lib/saved-drive-service.js';
import { AppError } from './lib/errors.js';

// ---------------------------------------------------------------------------
// Fake Prisma builder
// ---------------------------------------------------------------------------

interface FakeSession {
  id: string;
  userId: string;
  status: 'active' | 'stopped' | 'expired';
  startedAt: Date;
  expiresAt: Date;
  stoppedAt: Date | null;
}

interface FakeDriveRecord {
  id: string;
  userId: string;
  sourceLiveLocationSessionId: string | null;
  startedAt: Date;
  endedAt: Date;
  durationSeconds: number;
  distanceMeters: number | null;
  averageSpeedMetersPerSecond: number | null;
  approximateStartArea: string | null;
  approximateEndArea: string | null;
  routeOverview: unknown;
  createdAt: Date;
  updatedAt: Date;
}

function buildFakePrisma(options: {
  sessions?: FakeSession[];
  drives?: FakeDriveRecord[];
}): Record<string, unknown> {
  const sessions = options.sessions ?? [];
  const drives: FakeDriveRecord[] = options.drives ?? [];
  let driveIdCounter = 1;

  return {
    liveLocationSession: {
      async findUnique({ where }: { where: { id: string } }) {
        return sessions.find((s) => s.id === where.id) ?? null;
      },
    },
    savedDrive: {
      async findFirst({ where }: { where: { userId: string; sourceLiveLocationSessionId?: string } }) {
        return (
          drives.find(
            (d) =>
              d.userId === where.userId &&
              d.sourceLiveLocationSessionId === where.sourceLiveLocationSessionId,
          ) ?? null
        );
      },
      async create({ data }: { data: Partial<FakeDriveRecord> }) {
        const id = `drive-${driveIdCounter++}`;
        const drive: FakeDriveRecord = {
          id,
          userId: data.userId!,
          sourceLiveLocationSessionId: data.sourceLiveLocationSessionId ?? null,
          startedAt: data.startedAt!,
          endedAt: data.endedAt!,
          durationSeconds: data.durationSeconds!,
          distanceMeters: data.distanceMeters ?? null,
          averageSpeedMetersPerSecond: data.averageSpeedMetersPerSecond ?? null,
          approximateStartArea: data.approximateStartArea ?? null,
          approximateEndArea: data.approximateEndArea ?? null,
          routeOverview: data.routeOverview ?? null,
          createdAt: new Date(),
          updatedAt: new Date(),
        };
        drives.push(drive);
        return drive;
      },
      async findUnique({ where }: { where: { id: string } }) {
        return drives.find((d) => d.id === where.id) ?? null;
      },
      async delete({ where }: { where: { id: string } }) {
        const idx = drives.findIndex((d) => d.id === where.id);
        if (idx >= 0) drives.splice(idx, 1);
      },
      async count({ where }: { where: { userId?: string } }) {
        return drives.filter((d) => !where?.userId || d.userId === where.userId).length;
      },
      async findMany({
        where,
        skip = 0,
        take = 20,
      }: {
        where: { userId?: string };
        skip?: number;
        take?: number;
        orderBy?: unknown;
      }) {
        return drives
          .filter((d) => !where?.userId || d.userId === where.userId)
          .slice(skip, skip + take);
      },
    },
    async $transaction(
      arg: ((tx: unknown) => Promise<unknown>) | Array<Promise<unknown>>,
    ) {
      if (typeof arg === 'function') {
        return arg({});
      }
      return Promise.all(arg);
    },
  };
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function memberActor(userId = 'user-1'): SavedDriveActor {
  return {
    userId,
    role: 'user',
    status: 'active',
    subscriptionEntitlement: 'member_monthly',
  };
}

function freeActor(userId = 'user-2'): SavedDriveActor {
  return {
    userId,
    role: 'user',
    status: 'active',
    subscriptionEntitlement: 'none',
  };
}

function suspendedActor(): SavedDriveActor {
  return {
    userId: 'user-3',
    role: 'user',
    status: 'temporarily_suspended',
    subscriptionEntitlement: 'member_monthly',
  };
}

function deletedActor(): SavedDriveActor {
  return {
    userId: 'user-4',
    role: 'user',
    status: 'deleted',
    subscriptionEntitlement: 'none',
  };
}

const STOPPED_SESSION: FakeSession = {
  id: 'session-1',
  userId: 'user-1',
  status: 'stopped',
  startedAt: new Date('2026-06-22T08:00:00.000Z'),
  expiresAt: new Date('2026-06-22T10:00:00.000Z'),
  stoppedAt: new Date('2026-06-22T08:30:00.000Z'),
};

const ACTIVE_SESSION: FakeSession = {
  ...STOPPED_SESSION,
  id: 'session-active',
  status: 'active',
  stoppedAt: null,
};

// ---------------------------------------------------------------------------
// getPostDriveSummary
// ---------------------------------------------------------------------------

test('getPostDriveSummary: returns summary without persisting a drive', async () => {
  const prisma = buildFakePrisma({ sessions: [STOPPED_SESSION] });
  const svc = new SavedDriveService(prisma as never);
  const result = await svc.getPostDriveSummary({
    sessionId: 'session-1',
    actor: memberActor(),
  });
  assert.equal(result.summary.sessionId, 'session-1');
  assert.equal(result.summary.durationSeconds, 1800);
  assert.equal(result.summary.distanceMeters, null, 'summary-only: distanceMeters must be null');
  assert.ok(!('topSpeed' in result.summary), 'Top speed must never be in the summary');
});

test('getPostDriveSummary: canSave is true for member', async () => {
  const prisma = buildFakePrisma({ sessions: [STOPPED_SESSION] });
  const svc = new SavedDriveService(prisma as never);
  const result = await svc.getPostDriveSummary({ sessionId: 'session-1', actor: memberActor() });
  assert.equal(result.canSave, true);
});

test('getPostDriveSummary: canSave is false for free user', async () => {
  const prisma = buildFakePrisma({ sessions: [{ ...STOPPED_SESSION, userId: 'user-2' }] });
  const svc = new SavedDriveService(prisma as never);
  const result = await svc.getPostDriveSummary({ sessionId: 'session-1', actor: freeActor() });
  assert.equal(result.canSave, false);
});

test('getPostDriveSummary: throws 400 if session is still active', async () => {
  const prisma = buildFakePrisma({ sessions: [ACTIVE_SESSION] });
  const svc = new SavedDriveService(prisma as never);
  await assert.rejects(
    () => svc.getPostDriveSummary({ sessionId: 'session-active', actor: memberActor() }),
    (err: AppError) => {
      assert.equal(err.statusCode, 400);
      return true;
    },
  );
});

test('getPostDriveSummary: throws 403 for another user\'s session', async () => {
  const prisma = buildFakePrisma({ sessions: [STOPPED_SESSION] });
  const svc = new SavedDriveService(prisma as never);
  await assert.rejects(
    () => svc.getPostDriveSummary({ sessionId: 'session-1', actor: freeActor('user-other') }),
    (err: AppError) => {
      assert.equal(err.statusCode, 403);
      return true;
    },
  );
});

test('getPostDriveSummary: throws 403 for suspended user', async () => {
  const prisma = buildFakePrisma({ sessions: [STOPPED_SESSION] });
  const svc = new SavedDriveService(prisma as never);
  await assert.rejects(
    () => svc.getPostDriveSummary({ sessionId: 'session-1', actor: suspendedActor() }),
    (err: AppError) => {
      assert.equal(err.statusCode, 403);
      return true;
    },
  );
});

// ---------------------------------------------------------------------------
// saveDrive
// ---------------------------------------------------------------------------

test('saveDrive: creates a drive for member', async () => {
  const prisma = buildFakePrisma({ sessions: [STOPPED_SESSION] });
  const svc = new SavedDriveService(prisma as never);
  const drive = await svc.saveDrive({ sessionId: 'session-1', actor: memberActor() });
  assert.ok(drive.id, 'Drive must have an ID');
  assert.equal(drive.durationSeconds, 1800);
  assert.ok(!('topSpeed' in drive), 'Top speed must never be stored or returned');
  assert.equal(drive.distanceMeters, null, 'summary-only: distanceMeters must be null');
});

test('saveDrive: blocked for free user (member required)', async () => {
  const prisma = buildFakePrisma({ sessions: [{ ...STOPPED_SESSION, userId: 'user-2' }] });
  const svc = new SavedDriveService(prisma as never);
  await assert.rejects(
    () => svc.saveDrive({ sessionId: 'session-1', actor: freeActor() }),
    (err: AppError) => {
      assert.equal(err.statusCode, 403);
      return true;
    },
  );
});

test('saveDrive: blocked for suspended user', async () => {
  const prisma = buildFakePrisma({ sessions: [STOPPED_SESSION] });
  const svc = new SavedDriveService(prisma as never);
  await assert.rejects(
    () => svc.saveDrive({ sessionId: 'session-1', actor: suspendedActor() }),
    (err: AppError) => {
      assert.equal(err.statusCode, 403);
      return true;
    },
  );
});

test('saveDrive: blocked for deleted user', async () => {
  const prisma = buildFakePrisma({ sessions: [STOPPED_SESSION] });
  const svc = new SavedDriveService(prisma as never);
  await assert.rejects(
    () => svc.saveDrive({ sessionId: 'session-1', actor: deletedActor() }),
    (err: AppError) => {
      assert.equal(err.statusCode, 403);
      return true;
    },
  );
});

test('saveDrive: throws 403 when session belongs to different user', async () => {
  const prisma = buildFakePrisma({ sessions: [STOPPED_SESSION] }); // userId = 'user-1'
  const svc = new SavedDriveService(prisma as never);
  await assert.rejects(
    () => svc.saveDrive({ sessionId: 'session-1', actor: memberActor('user-other') }),
    (err: AppError) => {
      assert.equal(err.statusCode, 403);
      return true;
    },
  );
});

test('saveDrive: duplicate save returns existing drive (idempotent)', async () => {
  const existingDrive: FakeDriveRecord = {
    id: 'existing-drive',
    userId: 'user-1',
    sourceLiveLocationSessionId: 'session-1',
    startedAt: STOPPED_SESSION.startedAt,
    endedAt: STOPPED_SESSION.stoppedAt!,
    durationSeconds: 1800,
    distanceMeters: null,
    averageSpeedMetersPerSecond: null,
    approximateStartArea: null,
    approximateEndArea: null,
    routeOverview: null,
    createdAt: new Date(),
    updatedAt: new Date(),
  };
  const prisma = buildFakePrisma({ sessions: [STOPPED_SESSION], drives: [existingDrive] });
  const svc = new SavedDriveService(prisma as never);
  const drive = await svc.saveDrive({ sessionId: 'session-1', actor: memberActor() });
  assert.equal(drive.id, 'existing-drive', 'Should return the existing drive, not create a new one');
});

test('saveDrive: throws 400 if session is still active', async () => {
  const prisma = buildFakePrisma({ sessions: [ACTIVE_SESSION] });
  const svc = new SavedDriveService(prisma as never);
  await assert.rejects(
    () => svc.saveDrive({ sessionId: 'session-active', actor: memberActor() }),
    (err: AppError) => {
      assert.equal(err.statusCode, 400);
      return true;
    },
  );
});

// ---------------------------------------------------------------------------
// discardDrive
// ---------------------------------------------------------------------------

test('discardDrive: does not create a saved drive', async () => {
  const drives: FakeDriveRecord[] = [];
  const prisma = buildFakePrisma({ sessions: [STOPPED_SESSION], drives });
  const svc = new SavedDriveService(prisma as never);
  await svc.discardDrive({ sessionId: 'session-1', actor: memberActor() });
  assert.equal(drives.length, 0, 'Discard must not create any saved drives');
});

test('discardDrive: idempotent when session does not exist', async () => {
  const prisma = buildFakePrisma({ sessions: [] });
  const svc = new SavedDriveService(prisma as never);
  // Should not throw
  await svc.discardDrive({ sessionId: 'nonexistent', actor: memberActor() });
});

test('discardDrive: throws 403 for another user\'s session', async () => {
  const prisma = buildFakePrisma({ sessions: [STOPPED_SESSION] });
  const svc = new SavedDriveService(prisma as never);
  await assert.rejects(
    () => svc.discardDrive({ sessionId: 'session-1', actor: memberActor('user-other') }),
    (err: AppError) => {
      assert.equal(err.statusCode, 403);
      return true;
    },
  );
});

// ---------------------------------------------------------------------------
// listDrives
// ---------------------------------------------------------------------------

test('listDrives: returns only the user\'s own drives', async () => {
  const user1Drive: FakeDriveRecord = {
    id: 'd-1',
    userId: 'user-1',
    sourceLiveLocationSessionId: null,
    startedAt: new Date(),
    endedAt: new Date(),
    durationSeconds: 600,
    distanceMeters: null,
    averageSpeedMetersPerSecond: null,
    approximateStartArea: null,
    approximateEndArea: null,
    routeOverview: null,
    createdAt: new Date(),
    updatedAt: new Date(),
  };
  const user2Drive: FakeDriveRecord = { ...user1Drive, id: 'd-2', userId: 'user-2' };
  const prisma = buildFakePrisma({ drives: [user1Drive, user2Drive] });
  const svc = new SavedDriveService(prisma as never);

  const result = await svc.listDrives({ actor: memberActor('user-1'), page: 1, pageSize: 20 });
  assert.equal(result.drives.length, 1);
  const firstDrive = result.drives[0];
  assert.ok(firstDrive !== undefined, 'Expected at least one drive');
  assert.equal(firstDrive.id, 'd-1');
});

test('listDrives: list items never contain routeOverview', async () => {
  const user1Drive: FakeDriveRecord = {
    id: 'd-1',
    userId: 'user-1',
    sourceLiveLocationSessionId: null,
    startedAt: new Date(),
    endedAt: new Date(),
    durationSeconds: 600,
    distanceMeters: null,
    averageSpeedMetersPerSecond: null,
    approximateStartArea: null,
    approximateEndArea: null,
    routeOverview: [{ latitude: 57.7, longitude: 12.0 }],
    createdAt: new Date(),
    updatedAt: new Date(),
  };
  const prisma = buildFakePrisma({ drives: [user1Drive] });
  const svc = new SavedDriveService(prisma as never);

  const result = await svc.listDrives({ actor: memberActor('user-1'), page: 1, pageSize: 20 });
  assert.equal(result.drives.length, 1);
  const driveItem = result.drives[0];
  assert.ok(driveItem !== undefined, 'Expected at least one drive item');
  assert.ok(!('routeOverview' in driveItem), 'List items must not expose routeOverview');
});

// ---------------------------------------------------------------------------
// getDrive (detail)
// ---------------------------------------------------------------------------

test('getDrive: routeOverview returned for member', async () => {
  const drive: FakeDriveRecord = {
    id: 'd-1',
    userId: 'user-1',
    sourceLiveLocationSessionId: null,
    startedAt: new Date(),
    endedAt: new Date(),
    durationSeconds: 600,
    distanceMeters: null,
    averageSpeedMetersPerSecond: null,
    approximateStartArea: null,
    approximateEndArea: null,
    routeOverview: [{ latitude: 57.7, longitude: 12.0 }],
    createdAt: new Date(),
    updatedAt: new Date(),
  };
  const prisma = buildFakePrisma({ drives: [drive] });
  const svc = new SavedDriveService(prisma as never);

  const detail = await svc.getDrive({ driveId: 'd-1', actor: memberActor('user-1') });
  assert.ok(Array.isArray(detail.routeOverview), 'Member should receive routeOverview');
});

test('getDrive: routeOverview is null for free user', async () => {
  const drive: FakeDriveRecord = {
    id: 'd-2',
    userId: 'user-2',
    sourceLiveLocationSessionId: null,
    startedAt: new Date(),
    endedAt: new Date(),
    durationSeconds: 600,
    distanceMeters: null,
    averageSpeedMetersPerSecond: null,
    approximateStartArea: null,
    approximateEndArea: null,
    routeOverview: [{ latitude: 57.7, longitude: 12.0 }],
    createdAt: new Date(),
    updatedAt: new Date(),
  };
  const prisma = buildFakePrisma({ drives: [drive] });
  const svc = new SavedDriveService(prisma as never);

  const detail = await svc.getDrive({ driveId: 'd-2', actor: freeActor('user-2') });
  assert.equal(detail.routeOverview, null, 'Free user must not receive routeOverview');
});

test('getDrive: throws 403 for non-owner', async () => {
  const drive: FakeDriveRecord = {
    id: 'd-1',
    userId: 'user-1',
    sourceLiveLocationSessionId: null,
    startedAt: new Date(),
    endedAt: new Date(),
    durationSeconds: 600,
    distanceMeters: null,
    averageSpeedMetersPerSecond: null,
    approximateStartArea: null,
    approximateEndArea: null,
    routeOverview: null,
    createdAt: new Date(),
    updatedAt: new Date(),
  };
  const prisma = buildFakePrisma({ drives: [drive] });
  const svc = new SavedDriveService(prisma as never);

  await assert.rejects(
    () => svc.getDrive({ driveId: 'd-1', actor: memberActor('user-other') }),
    (err: AppError) => {
      assert.equal(err.statusCode, 403);
      return true;
    },
  );
});

// ---------------------------------------------------------------------------
// cleanupExpiredTemporaryPoints
// ---------------------------------------------------------------------------

test('cleanupExpiredTemporaryPoints: returns 0 (no-op until TemporaryDrivePoint implemented)', async () => {
  const prisma = buildFakePrisma({});
  const svc = new SavedDriveService(prisma as never);
  const count = await svc.cleanupExpiredTemporaryPoints();
  assert.equal(count, 0);
});
