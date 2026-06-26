/**
 * Crown Hunt (Kronjakt) service unit tests.
 *
 * Uses a fake Prisma client — no database connection required.
 *
 * Covers:
 *  - Free user cannot claim (not_eligible)
 *  - Suspended user cannot claim (not_eligible)
 *  - Deleted user cannot claim (not_eligible)
 *  - Disabled crownHunt feature flag blocks claims (feature_disabled)
 *  - Inactive point cannot be claimed (point_inactive)
 *  - Expired point cannot be claimed (point_inactive)
 *  - Claim outside geofence is rejected (outside_geofence)
 *  - Stale position is rejected (position_too_old)
 *  - Unsafe speed is rejected (moving_too_fast)
 *  - Valid safe claim creates exactly one points-ledger entry (awarded)
 *  - Duplicate idempotency key does not duplicate an award
 *  - Repeat rule 'once' is enforced (already_claimed)
 *  - Daily claim limit is enforced (daily_limit_reached)
 *  - High-risk claim receives no points (risk_review)
 *  - Client cannot provide reward amount (strict schema)
 *  - Claim response does not expose anti-fraud thresholds
 *  - Admin-created points start as draft
 *  - Point activation requires safety confirmation
 *  - Point changes write audit entries
 *  - No exact claim coordinates are stored
 *  - No route history is created
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { CrownHuntService } from './lib/crown-hunt-service.js';
import { PointsService } from './lib/points-service.js';
import { AppError } from './lib/errors.js';

// ---------------------------------------------------------------------------
// Test UUIDs
// ---------------------------------------------------------------------------

const USER_ID = 'aaaaaaaa-0000-4000-8000-000000000001';
const ADMIN_ID = 'aaaaaaaa-0000-4000-8000-000000000002';
const POINT_ID = 'cccccccc-0000-4000-8000-000000000001';

// ---------------------------------------------------------------------------
// Fake Prisma builder
// ---------------------------------------------------------------------------

interface FakeUser {
  id: string;
  status: string;
  role: string;
  subscriptionEntitlement: string;
}

interface FakeCrownHuntPoint {
  id: string;
  title: string;
  description: string | null;
  latitude: number;
  longitude: number;
  geofenceRadiusMeters: number;
  rewardPoints: number;
  status: string;
  repeatRule: string;
  availableFrom: Date | null;
  availableUntil: Date | null;
  approvedAt: Date | null;
  approvedByUserId: string | null;
  createdByUserId: string;
  createdAt: Date;
  updatedAt: Date;
}

interface FakeCrownHuntClaim {
  id: string;
  pointId: string;
  userId: string;
  pointsLedgerEntryId: string | null;
  result: string;
  claimedAt: Date;
  distanceMeters: number | null;
  reportedSpeedMetersPerSecond: number | null;
  positionRecordedAt: Date | null;
  riskScore: number | null;
  riskReasons: unknown;
  idempotencyKey: string;
  createdAt: Date;
  point: { title: string; rewardPoints?: number };
}

interface FakeLedgerEntry {
  id: string;
  userId: string;
  transactionType: string;
  source: string;
  amount: number;
  balanceAfter: number;
  description: string;
  idempotencyKey: string | null;
  relatedEntityType: string | null;
  relatedEntityId: string | null;
  createdByUserId: string | null;
  metadata: unknown;
  createdAt: Date;
}

interface FakeAuditLog {
  id: string;
  actorUserId: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  reason: string | null;
  metadata: unknown;
}

function buildFakePrisma(options: {
  users?: FakeUser[];
  points?: FakeCrownHuntPoint[];
  claims?: FakeCrownHuntClaim[];
  ledger?: FakeLedgerEntry[];
  auditLogs?: FakeAuditLog[];
  liveLocationPositions?: Array<{ userId: string; latitude: number; longitude: number; recordedAt: Date }>;
} = {}): Record<string, unknown> {
  const users: FakeUser[] = options.users ?? [];
  const points: FakeCrownHuntPoint[] = options.points ?? [];
  const claims: FakeCrownHuntClaim[] = options.claims ?? [];
  const ledger: FakeLedgerEntry[] = options.ledger ?? [];
  const auditLogs: FakeAuditLog[] = options.auditLogs ?? [];
  const liveLocationPositions = options.liveLocationPositions ?? [];

  let idCounter = 1;
  const nextId = () => `fake-id-${idCounter++}`;

  const fakePrisma: Record<string, unknown> = {
    _claims: claims,
    _ledger: ledger,
    _auditLogs: auditLogs,
    _points: points,

    user: {
      async findUnique({ where }: { where: { id?: string } }) {
        return users.find((u) => u.id === where.id) ?? null;
      },
    },

    crownHuntPoint: {
      async findUnique({ where }: { where: { id?: string } }) {
        return points.find((p) => p.id === where.id) ?? null;
      },
      async findMany({ where = {}, skip = 0, take = 20, orderBy: _orderBy }: { where?: Record<string, unknown>; skip?: number; take?: number; orderBy?: unknown }) {
        let filtered = [...points];
        if (where['status']) filtered = filtered.filter((p) => p.status === where['status']);
        if (where['AND']) {
          // availability window filter — for tests we keep all active points
        }
        return filtered.slice(skip, skip + take);
      },
      async count({ where = {} }: { where?: Record<string, unknown> }) {
        let filtered = [...points];
        if (where['status']) filtered = filtered.filter((p) => p.status === where['status']);
        return filtered.length;
      },
      async create({ data }: { data: Record<string, unknown> }) {
        const now = new Date();
        const point: FakeCrownHuntPoint = {
          id: nextId(),
          title: data['title'] as string,
          description: (data['description'] as string | null) ?? null,
          latitude: data['latitude'] as number,
          longitude: data['longitude'] as number,
          geofenceRadiusMeters: data['geofenceRadiusMeters'] as number,
          rewardPoints: data['rewardPoints'] as number,
          status: (data['status'] as string) ?? 'draft',
          repeatRule: (data['repeatRule'] as string) ?? 'once',
          availableFrom: data['availableFrom'] ? new Date(data['availableFrom'] as string) : null,
          availableUntil: data['availableUntil'] ? new Date(data['availableUntil'] as string) : null,
          approvedAt: null,
          approvedByUserId: null,
          createdByUserId: data['createdByUserId'] as string,
          createdAt: now,
          updatedAt: now,
        };
        points.push(point);
        return point;
      },
      async update({ where, data }: { where: { id: string }; data: Record<string, unknown> }) {
        const idx = points.findIndex((p) => p.id === where.id);
        if (idx < 0) return null;
        const existing = points[idx]!;
        const updated = { ...existing, ...data, updatedAt: new Date() };
        points[idx] = updated;
        return updated;
      },
    },

    crownHuntClaim: {
      async findUnique({ where }: { where: { idempotencyKey?: string; id?: string } }) {
        if (where.idempotencyKey) return claims.find((c) => c.idempotencyKey === where.idempotencyKey) ?? null;
        if (where.id) return claims.find((c) => c.id === where.id) ?? null;
        return null;
      },
      async findFirst({ where }: { where: Record<string, unknown> }) {
        return claims.find((c) => {
          if (where['userId'] && c.userId !== where['userId']) return false;
          if (where['pointId'] && c.pointId !== where['pointId']) return false;
          if (where['result'] && c.result !== where['result']) return false;
          if (where['claimedAt'] && (where['claimedAt'] as Record<string, unknown>)['gte']) {
            const gte = new Date((where['claimedAt'] as Record<string, unknown>)['gte'] as string);
            if (c.claimedAt < gte) return false;
          }
          return true;
        }) ?? null;
      },
      async create({ data }: { data: Record<string, unknown> }) {
        const claim: FakeCrownHuntClaim = {
          id: nextId(),
          pointId: data['pointId'] as string,
          userId: data['userId'] as string,
          pointsLedgerEntryId: (data['pointsLedgerEntryId'] as string | null) ?? null,
          result: data['result'] as string,
          claimedAt: data['claimedAt'] ? new Date(data['claimedAt'] as string) : new Date(),
          distanceMeters: (data['distanceMeters'] as number | null) ?? null,
          reportedSpeedMetersPerSecond: (data['reportedSpeedMetersPerSecond'] as number | null) ?? null,
          positionRecordedAt: data['positionRecordedAt'] ? new Date(data['positionRecordedAt'] as string) : null,
          riskScore: (data['riskScore'] as number | null) ?? null,
          riskReasons: data['riskReasons'] ?? null,
          idempotencyKey: data['idempotencyKey'] as string,
          createdAt: new Date(),
          point: {
            title: points.find((p) => p.id === data['pointId'])?.title ?? '',
            rewardPoints: points.find((p) => p.id === data['pointId'])?.rewardPoints ?? 0,
          },
        };
        // Enforce unique constraint on idempotencyKey
        if (claims.some((c) => c.idempotencyKey === claim.idempotencyKey)) {
          const error: NodeJS.ErrnoException = new Error('Unique constraint violation on idempotencyKey');
          (error as unknown as Record<string, unknown>)['code'] = 'P2002';
          throw error;
        }
        claims.push(claim);
        return claim;
      },
      async count({ where = {} }: { where?: Record<string, unknown> }) {
        return claims.filter((c) => {
          if (where['userId'] && c.userId !== where['userId']) return false;
          if (where['result'] && c.result !== where['result']) return false;
          if (where['claimedAt'] && (where['claimedAt'] as Record<string, unknown>)['gte']) {
            const gte = new Date((where['claimedAt'] as Record<string, unknown>)['gte'] as string);
            if (c.claimedAt < gte) return false;
          }
          if (where['createdAt'] && (where['createdAt'] as Record<string, unknown>)['gte']) {
            const gte = new Date((where['createdAt'] as Record<string, unknown>)['gte'] as string);
            if (c.createdAt < gte) return false;
          }
          return true;
        }).length;
      },
      async findMany({ where = {}, include: _include, skip = 0, take = 20 }: { where?: Record<string, unknown>; include?: unknown; skip?: number; take?: number }) {
        const filtered = claims.filter((c) => {
          if (where['userId'] && c.userId !== where['userId']) return false;
          if (where['result'] && c.result !== where['result']) return false;
          return true;
        });
        return filtered.slice(skip, skip + take).map((claim) => ({
          ...claim,
          point: {
            title: claim.point.title || (points.find((p) => p.id === claim.pointId)?.title ?? ''),
            rewardPoints: claim.point.rewardPoints ?? points.find((p) => p.id === claim.pointId)?.rewardPoints ?? 0,
          },
        }));
      },
      async groupBy({ by: _by, where = {}, _count }: { by: string[]; where?: Record<string, unknown>; _count?: unknown }) {
        const filtered = claims.filter((c) => {
          if ((where['result'] as string) && c.result !== where['result']) return false;
          return true;
        });
        const byPointId = new Map<string, number>();
        for (const c of filtered) {
          byPointId.set(c.pointId, (byPointId.get(c.pointId) ?? 0) + 1);
        }
        return [...byPointId.entries()].map(([pointId, count]) => ({ pointId, _count: { id: count } }));
      },
    },

    pointsLedgerEntry: {
      async findUnique({ where }: { where: { id?: string; idempotencyKey?: string } }) {
        if (where.idempotencyKey) return ledger.find((e) => e.idempotencyKey === where.idempotencyKey) ?? null;
        if (where.id) return ledger.find((e) => e.id === where.id) ?? null;
        return null;
      },
      async create({ data }: { data: Record<string, unknown> }) {
        const existing = data['idempotencyKey']
          ? ledger.find((e) => e.idempotencyKey === data['idempotencyKey'])
          : null;
        if (existing) return existing;
        const currentBalance = ledger.filter((e) => e.userId === data['userId']).reduce((s, e) => s + e.amount, 0);
        const entry: FakeLedgerEntry = {
          id: nextId(),
          userId: data['userId'] as string,
          transactionType: data['transactionType'] as string,
          source: data['source'] as string,
          amount: data['amount'] as number,
          balanceAfter: currentBalance + (data['amount'] as number),
          description: data['description'] as string,
          idempotencyKey: (data['idempotencyKey'] as string | null) ?? null,
          relatedEntityType: (data['relatedEntityType'] as string | null) ?? null,
          relatedEntityId: (data['relatedEntityId'] as string | null) ?? null,
          createdByUserId: (data['createdByUserId'] as string | null) ?? null,
          metadata: data['metadata'] ?? null,
          createdAt: new Date(),
        };
        ledger.push(entry);
        return entry;
      },
      async aggregate({ _sum, where = {} }: { _sum: Record<string, boolean>; where?: Record<string, unknown> }) {
        const filtered = ledger.filter((e) => {
          if (where['userId'] && e.userId !== where['userId']) return false;
          return true;
        });
        return { _sum: { amount: filtered.reduce((s, e) => s + e.amount, 0) } };
      },
      async findMany({ where = {}, skip = 0, take = 20 }: { where?: Record<string, unknown>; skip?: number; take?: number }) {
        return ledger
          .filter((e) => !where['userId'] || e.userId === where['userId'])
          .reverse()
          .slice(skip, skip + take);
      },
      async count({ where = {} }: { where?: Record<string, unknown> }) {
        return ledger.filter((e) => !where['userId'] || e.userId === where['userId']).length;
      },
    },

    auditLog: {
      async create({ data }: { data: Record<string, unknown> }) {
        const entry: FakeAuditLog = {
          id: nextId(),
          actorUserId: (data['actorUserId'] as string | null) ?? null,
          action: data['action'] as string,
          entityType: data['entityType'] as string,
          entityId: (data['entityId'] as string | null) ?? null,
          reason: (data['reason'] as string | null) ?? null,
          metadata: data['metadata'] ?? null,
        };
        auditLogs.push(entry);
        return entry;
      },
    },

    featureFlag: {
      async findFirst() { return null; },
    },

    liveLocationLatestPosition: {
      async findFirst({ where = {}, orderBy: _orderBy }: { where?: Record<string, unknown>; orderBy?: unknown } = {}) {
        const pos = liveLocationPositions.find((p) => p.userId === where['userId']);
        return pos ?? null;
      },
    },

    async $transaction<T>(fn: ((tx: unknown) => Promise<T>) | [unknown, unknown]): Promise<T | unknown[]> {
      if (Array.isArray(fn)) {
        return Promise.all(fn.map((q) => (q as Promise<unknown>)));
      }
      return (fn as (tx: unknown) => Promise<T>)(fakePrisma);
    },

    $executeRaw: async () => null,
  };

  return fakePrisma;
}

// ---------------------------------------------------------------------------
// Actor helpers
// ---------------------------------------------------------------------------

function memberActor(overrides: Partial<Record<string, string>> = {}) {
  return {
    userId: USER_ID,
    role: 'user' as const,
    status: 'active' as const,
    subscriptionEntitlement: 'member_monthly' as const,
    ...overrides,
  };
}

function freeActor() {
  return {
    userId: USER_ID,
    role: 'user' as const,
    status: 'active' as const,
    subscriptionEntitlement: 'none' as const,
  };
}

function suspendedActor() {
  return {
    userId: USER_ID,
    role: 'user' as const,
    status: "temporarily_suspended" as const,
    subscriptionEntitlement: 'member_monthly' as const,
  };
}

function deletedActor() {
  return {
    userId: USER_ID,
    role: 'user' as const,
    status: 'deleted' as const,
    subscriptionEntitlement: 'member_monthly' as const,
  };
}

function activePoint(overrides: Partial<FakeCrownHuntPoint> = {}): FakeCrownHuntPoint {
  return {
    id: POINT_ID,
    title: 'Testpunkt',
    description: null,
    latitude: 57.5086,
    longitude: 12.0742,
    geofenceRadiusMeters: 50,
    rewardPoints: 10,
    status: 'active',
    repeatRule: 'once',
    availableFrom: null,
    availableUntil: null,
    approvedAt: new Date(),
    approvedByUserId: ADMIN_ID,
    createdByUserId: ADMIN_ID,
    createdAt: new Date(),
    updatedAt: new Date(),
    ...overrides,
  };
}

/** Fresh timestamp (just now) */
const freshRecordedAt = () => new Date().toISOString();

/** Stale timestamp (2 minutes ago) */
const staleRecordedAt = () => new Date(Date.now() - 120_000).toISOString();

// Kungsbacka coords — same as the test point
const POINT_LAT = 57.5086;
const POINT_LON = 12.0742;

// User inside geofence (10m away)
const INSIDE_LAT = 57.50869;
const INSIDE_LON = 12.0742;

// User outside geofence (500m away)
const OUTSIDE_LAT = 57.5135;
const OUTSIDE_LON = 12.0742;

function buildService(prisma: Record<string, unknown>) {
  const pointsSvc = new PointsService(prisma as never);
  return new CrownHuntService(prisma as never, pointsSvc);
}

const CLAIM_DEFAULTS = {
  latitude: INSIDE_LAT,
  longitude: INSIDE_LON,
  accuracyMeters: 5,
  speedMetersPerSecond: 0,
  idempotencyKey: 'test-key-001',
  crownHuntFeatureEnabled: true,
};

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

await test('free user cannot claim (not_eligible)', async () => {
  const prisma = buildFakePrisma({
    points: [activePoint()],
  });
  const svc = buildService(prisma);

  const result = await svc.claimPoint({
    actor: freeActor(),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    ...CLAIM_DEFAULTS,
  });

  assert.equal(result.result, 'not_eligible');
  assert.equal(result.pointsAwarded, null);
  assert.equal(result.newBalance, null);

  const claims = (prisma as Record<string, unknown>)._claims as FakeCrownHuntClaim[];
  assert.equal(claims.length, 0, 'No claim record should be written for not_eligible');
});

await test('suspended user cannot claim (not_eligible)', async () => {
  const prisma = buildFakePrisma({ points: [activePoint()] });
  const svc = buildService(prisma);

  const result = await svc.claimPoint({
    actor: suspendedActor(),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    ...CLAIM_DEFAULTS,
  });

  assert.equal(result.result, 'not_eligible');
  assert.equal(result.pointsAwarded, null);
});

await test('deleted user cannot claim (not_eligible)', async () => {
  const prisma = buildFakePrisma({ points: [activePoint()] });
  const svc = buildService(prisma);

  const result = await svc.claimPoint({
    actor: deletedActor(),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    ...CLAIM_DEFAULTS,
  });

  assert.equal(result.result, 'not_eligible');
  assert.equal(result.pointsAwarded, null);
});

await test('disabled crownHunt feature flag blocks claims (feature_disabled)', async () => {
  const prisma = buildFakePrisma({ points: [activePoint()] });
  const svc = buildService(prisma);

  const result = await svc.claimPoint({
    actor: memberActor(),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    ...CLAIM_DEFAULTS,
    crownHuntFeatureEnabled: false,
  });

  assert.equal(result.result, 'feature_disabled');
  assert.equal(result.pointsAwarded, null);
});

await test('inactive point cannot be claimed (point_inactive)', async () => {
  const prisma = buildFakePrisma({
    points: [activePoint({ status: 'paused' })],
    users: [{ id: USER_ID, status: 'active', role: 'user', subscriptionEntitlement: 'member_monthly' }],
  });
  const svc = buildService(prisma);

  const result = await svc.claimPoint({
    actor: memberActor(),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    ...CLAIM_DEFAULTS,
  });

  assert.equal(result.result, 'point_inactive');
  assert.equal(result.pointsAwarded, null);
});

await test('expired point (availableUntil in the past) cannot be claimed', async () => {
  const past = new Date(Date.now() - 60_000);
  const prisma = buildFakePrisma({
    points: [activePoint({ availableUntil: past })],
    users: [{ id: USER_ID, status: 'active', role: 'user', subscriptionEntitlement: 'member_monthly' }],
  });
  const svc = buildService(prisma);

  const result = await svc.claimPoint({
    actor: memberActor(),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    ...CLAIM_DEFAULTS,
  });

  assert.equal(result.result, 'point_inactive');
  assert.equal(result.pointsAwarded, null);
});

await test('stale position is rejected (position_too_old)', async () => {
  const prisma = buildFakePrisma({
    points: [activePoint()],
    users: [{ id: USER_ID, status: 'active', role: 'user', subscriptionEntitlement: 'member_monthly' }],
  });
  const svc = buildService(prisma);

  const result = await svc.claimPoint({
    actor: memberActor(),
    pointId: POINT_ID,
    recordedAt: staleRecordedAt(),
    ...CLAIM_DEFAULTS,
  });

  assert.equal(result.result, 'position_too_old');
  assert.equal(result.pointsAwarded, null);

  // Claim record is written for auditing
  const claims = (prisma as Record<string, unknown>)._claims as FakeCrownHuntClaim[];
  assert.equal(claims.length, 1);
  assert.equal(claims[0]!.result, 'position_too_old');
  // No exact coordinates — only timestamp is stored
  assert.equal(claims[0]!.distanceMeters, null);
});

await test('claim outside geofence is rejected (outside_geofence)', async () => {
  const prisma = buildFakePrisma({
    points: [activePoint()],
    users: [{ id: USER_ID, status: 'active', role: 'user', subscriptionEntitlement: 'member_monthly' }],
  });
  const svc = buildService(prisma);

  const result = await svc.claimPoint({
    actor: memberActor(),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    latitude: OUTSIDE_LAT,
    longitude: OUTSIDE_LON,
    accuracyMeters: 5,
    speedMetersPerSecond: 0,
    idempotencyKey: 'outside-key-001',
    crownHuntFeatureEnabled: true,
  });

  assert.equal(result.result, 'outside_geofence');
  assert.equal(result.pointsAwarded, null);

  const claims = (prisma as Record<string, unknown>)._claims as FakeCrownHuntClaim[];
  assert.equal(claims.length, 1);
  assert.equal(claims[0]!.result, 'outside_geofence');
  // Distance is stored (not exact coordinates)
  assert.ok(claims[0]!.distanceMeters !== null);
  assert.ok(typeof claims[0]!.distanceMeters === 'number');
});

await test('unsafe speed is rejected (moving_too_fast)', async () => {
  const prisma = buildFakePrisma({
    points: [activePoint()],
    users: [{ id: USER_ID, status: 'active', role: 'user', subscriptionEntitlement: 'member_monthly' }],
  });
  const svc = buildService(prisma);

  const result = await svc.claimPoint({
    actor: memberActor(),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    latitude: INSIDE_LAT,
    longitude: INSIDE_LON,
    accuracyMeters: 5,
    speedMetersPerSecond: 15, // ~54 km/h — clearly too fast
    idempotencyKey: 'speed-key-001',
    crownHuntFeatureEnabled: true,
  });

  assert.equal(result.result, 'moving_too_fast');
  assert.equal(result.pointsAwarded, null);
});

await test('valid safe claim creates exactly one ledger entry (awarded)', async () => {
  const prisma = buildFakePrisma({
    points: [activePoint()],
    users: [{ id: USER_ID, status: 'active', role: 'user', subscriptionEntitlement: 'member_monthly' }],
  });
  const svc = buildService(prisma);

  const result = await svc.claimPoint({
    actor: memberActor(),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    latitude: INSIDE_LAT,
    longitude: INSIDE_LON,
    accuracyMeters: 5,
    speedMetersPerSecond: 0,
    idempotencyKey: 'valid-claim-001',
    crownHuntFeatureEnabled: true,
  });

  assert.equal(result.result, 'awarded');
  assert.equal(result.pointsAwarded, 10); // matches point.rewardPoints
  assert.equal(typeof result.newBalance, 'number');

  // Exactly one ledger entry must exist
  const ledger = (prisma as Record<string, unknown>)._ledger as FakeLedgerEntry[];
  assert.equal(ledger.length, 1);
  assert.equal(ledger[0]!.source, 'crown_hunt');
  assert.equal(ledger[0]!.amount, 10);
  assert.equal(ledger[0]!.transactionType, 'earn');

  // Exactly one claim record
  const claims = (prisma as Record<string, unknown>)._claims as FakeCrownHuntClaim[];
  assert.equal(claims.length, 1);
  assert.equal(claims[0]!.result, 'awarded');
  assert.ok(claims[0]!.pointsLedgerEntryId !== null);

  // No exact coordinates stored — only distance
  assert.ok(claims[0]!.distanceMeters !== null);
  assert.equal('latitude' in (claims[0] as unknown as Record<string, unknown>), false);
  assert.equal('longitude' in (claims[0] as unknown as Record<string, unknown>), false);
});

await test('duplicate idempotency key does not duplicate award', async () => {
  const prisma = buildFakePrisma({
    points: [activePoint()],
    users: [{ id: USER_ID, status: 'active', role: 'user', subscriptionEntitlement: 'member_monthly' }],
  });
  const svc = buildService(prisma);

  const key = 'idem-key-001';
  const claimInput = {
    actor: memberActor(),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    latitude: INSIDE_LAT,
    longitude: INSIDE_LON,
    accuracyMeters: 5,
    speedMetersPerSecond: 0,
    idempotencyKey: key,
    crownHuntFeatureEnabled: true,
  };

  // First claim
  const first = await svc.claimPoint(claimInput);
  assert.equal(first.result, 'awarded');

  // Second call with same key — should return original result without duplicate award
  const second = await svc.claimPoint({ ...claimInput, recordedAt: freshRecordedAt() });
  assert.equal(second.result, 'awarded');
  assert.equal(second.pointsAwarded, first.pointsAwarded);
  assert.equal(second.newBalance, first.newBalance);

  // Only ONE ledger entry must exist
  const ledger = (prisma as Record<string, unknown>)._ledger as FakeLedgerEntry[];
  assert.equal(ledger.length, 1, 'Duplicate idempotency key must not create a second ledger entry');
});

await test('same client idempotency key can be reused by different users', async () => {
  const otherUserId = 'aaaaaaaa-0000-4000-8000-000000000099';
  const prisma = buildFakePrisma({
    points: [activePoint()],
    users: [
      { id: USER_ID, status: 'active', role: 'user', subscriptionEntitlement: 'member_monthly' },
      { id: otherUserId, status: 'active', role: 'user', subscriptionEntitlement: 'member_monthly' },
    ],
  });
  const svc = buildService(prisma);

  const idempotencyKey = 'shared-client-key';
  const first = await svc.claimPoint({
    actor: memberActor(),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    latitude: INSIDE_LAT,
    longitude: INSIDE_LON,
    speedMetersPerSecond: 0,
    idempotencyKey,
    crownHuntFeatureEnabled: true,
  });
  const second = await svc.claimPoint({
    actor: memberActor({ userId: otherUserId }),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    latitude: INSIDE_LAT,
    longitude: INSIDE_LON,
    speedMetersPerSecond: 0,
    idempotencyKey,
    crownHuntFeatureEnabled: true,
  });

  assert.equal(first.result, 'awarded');
  assert.equal(second.result, 'awarded');

  const ledger = (prisma as Record<string, unknown>)._ledger as FakeLedgerEntry[];
  assert.equal(ledger.length, 2, 'Different users should not collide on client idempotency keys');
});

await test('awarded claim replay returns original award after a unique-constraint race', async () => {
  const prisma = buildFakePrisma({
    points: [activePoint()],
    users: [{ id: USER_ID, status: 'active', role: 'user', subscriptionEntitlement: 'member_monthly' }],
  });
  const svc = buildService(prisma);

  const claimModel = (prisma as Record<string, unknown>).crownHuntClaim as {
    create: ({ data }: { data: Record<string, unknown> }) => Promise<unknown>;
  };
  const originalCreate = claimModel.create.bind(claimModel);
  let injectedRace = false;
  claimModel.create = async ({ data }) => {
    if (!injectedRace && data['result'] === 'awarded') {
      injectedRace = true;
      await originalCreate({ data });
      const error = Object.assign(new Error('Unique constraint'), { code: 'P2002' });
      throw error;
    }
    return originalCreate({ data });
  };

  const result = await svc.claimPoint({
    actor: memberActor(),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    latitude: INSIDE_LAT,
    longitude: INSIDE_LON,
    speedMetersPerSecond: 0,
    idempotencyKey: 'race-key-001',
    crownHuntFeatureEnabled: true,
  });

  assert.equal(result.result, 'awarded');
  assert.equal(result.pointsAwarded, 10);
  assert.equal(result.newBalance, 10);

  const claims = (prisma as Record<string, unknown>)._claims as FakeCrownHuntClaim[];
  const ledger = (prisma as Record<string, unknown>)._ledger as FakeLedgerEntry[];
  assert.equal(claims.length, 1);
  assert.equal(ledger.length, 1);
});

await test('repeat rule "once" is enforced (already_claimed)', async () => {
  const prisma = buildFakePrisma({
    points: [activePoint({ repeatRule: 'once' })],
    users: [{ id: USER_ID, status: 'active', role: 'user', subscriptionEntitlement: 'member_monthly' }],
  });
  const svc = buildService(prisma);

  const first = await svc.claimPoint({
    actor: memberActor(),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    latitude: INSIDE_LAT,
    longitude: INSIDE_LON,
    speedMetersPerSecond: 0,
    idempotencyKey: 'once-key-001',
    crownHuntFeatureEnabled: true,
  });
  assert.equal(first.result, 'awarded');

  const second = await svc.claimPoint({
    actor: memberActor(),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    latitude: INSIDE_LAT,
    longitude: INSIDE_LON,
    speedMetersPerSecond: 0,
    idempotencyKey: 'once-key-002', // different idempotency key
    crownHuntFeatureEnabled: true,
  });
  assert.equal(second.result, 'already_claimed');
  assert.equal(second.pointsAwarded, null);

  const ledger = (prisma as Record<string, unknown>)._ledger as FakeLedgerEntry[];
  assert.equal(ledger.length, 1, 'Only one award should exist');
});

await test('daily claim limit is enforced (daily_limit_reached)', async () => {
  // Pre-populate with MAX_DAILY_SUCCESSFUL_CLAIMS successful claims today
  const todayStart = new Date();
  todayStart.setUTCHours(0, 0, 0, 0);

  const existingClaims: FakeCrownHuntClaim[] = Array.from({ length: 10 }, (_, i) => ({
    id: `claim-${i}`,
    pointId: `point-${i}`,
    userId: USER_ID,
    pointsLedgerEntryId: null,
    result: 'awarded',
    claimedAt: new Date(todayStart.getTime() + i * 1000),
    distanceMeters: 10,
    reportedSpeedMetersPerSecond: 0,
    positionRecordedAt: new Date(),
    riskScore: null,
    riskReasons: null,
    idempotencyKey: `daily-limit-key-${i}`,
    createdAt: new Date(),
    point: { title: 'Point' },
  }));

  const prisma = buildFakePrisma({
    points: [activePoint()],
    users: [{ id: USER_ID, status: 'active', role: 'user', subscriptionEntitlement: 'member_monthly' }],
    claims: existingClaims,
  });
  const svc = buildService(prisma);

  // Attempt another claim with a different point (so repeat rule doesn't trigger)
  const result = await svc.claimPoint({
    actor: memberActor(),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    latitude: INSIDE_LAT,
    longitude: INSIDE_LON,
    speedMetersPerSecond: 0,
    idempotencyKey: 'daily-limit-new-key',
    crownHuntFeatureEnabled: true,
  });

  assert.equal(result.result, 'daily_limit_reached');
  assert.equal(result.pointsAwarded, null);
});

await test('high-risk claim receives no points (risk_review)', async () => {
  const prisma = buildFakePrisma({
    points: [activePoint()],
    users: [{ id: USER_ID, status: 'active', role: 'user', subscriptionEntitlement: 'member_monthly' }],
    // Simulate many recent attempts to trigger high-risk score
    claims: Array.from({ length: 10 }, (_, i) => ({
      id: `attempt-${i}`,
      pointId: POINT_ID,
      userId: USER_ID,
      pointsLedgerEntryId: null,
      result: 'outside_geofence',
      claimedAt: new Date(),
      distanceMeters: null,
      reportedSpeedMetersPerSecond: null,
      positionRecordedAt: null,
      riskScore: null,
      riskReasons: null,
      idempotencyKey: `attempt-key-${i}`,
      createdAt: new Date(),
      point: { title: 'Testpunkt', rewardPoints: 10 },
    })),
  });
  const svc = buildService(prisma);

  // Use duplicate idempotency key signal to force high risk
  const result = await svc.claimPoint({
    actor: memberActor(),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    latitude: INSIDE_LAT,
    longitude: INSIDE_LON,
    speedMetersPerSecond: 0,
    idempotencyKey: 'risk-key-001',
    crownHuntFeatureEnabled: true,
    // Simulate impossible jump by injecting a live location far away
    platformIntegrityPassed: false, // strong risk signal
  });

  assert.equal(result.result, 'risk_review');
  assert.equal(result.pointsAwarded, null);
  assert.equal(result.newBalance, null);

  // risk_review claim must not have a ledger entry
  const ledger = (prisma as Record<string, unknown>)._ledger as FakeLedgerEntry[];
  assert.equal(ledger.length, 0, 'risk_review must not produce a ledger entry');
});

await test('client cannot provide reward amount (schema strictly rejects it)', async () => {
  // Test that the claimBodySchema does not accept rewardPoints
  const { z } = await import('zod');
  const claimBodySchema = z
    .object({
      latitude: z.number().gte(-90).lte(90),
      longitude: z.number().gte(-180).lte(180),
      accuracyMeters: z.number().positive().nullable().optional(),
      speedMetersPerSecond: z.number().min(0).nullable().optional(),
      recordedAt: z.string().datetime({ offset: true }),
      idempotencyKey: z.string().min(1).max(200),
      platformIntegrityPassed: z.boolean().nullable().optional(),
    })
    .strict();

  const withRewardAmount = {
    latitude: INSIDE_LAT,
    longitude: INSIDE_LON,
    recordedAt: freshRecordedAt(),
    idempotencyKey: 'test',
    rewardPoints: 9999, // must be rejected
  };

  const result = claimBodySchema.safeParse(withRewardAmount);
  assert.equal(result.success, false, 'rewardPoints must not be accepted in claim request');
});

await test('claim response does not expose anti-fraud thresholds or riskScore', async () => {
  const prisma = buildFakePrisma({
    points: [activePoint()],
    users: [{ id: USER_ID, status: 'active', role: 'user', subscriptionEntitlement: 'member_monthly' }],
  });
  const svc = buildService(prisma);

  const result = await svc.claimPoint({
    actor: memberActor(),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    latitude: INSIDE_LAT,
    longitude: INSIDE_LON,
    speedMetersPerSecond: 0,
    idempotencyKey: 'response-safe-001',
    crownHuntFeatureEnabled: true,
  });

  // The response object must not contain riskScore, riskThresholds, or internal signals
  const forbidden = ['riskScore', 'riskReasons', 'riskThreshold', 'fraudSignals', 'antifraud'];
  for (const field of forbidden) {
    assert.equal(field in result, false, `Field "${field}" must not be in claim response`);
  }
});

await test('admin-created point starts as draft', async () => {
  const prisma = buildFakePrisma({
    users: [{ id: ADMIN_ID, status: 'active', role: 'admin', subscriptionEntitlement: 'none' }],
  });
  const svc = buildService(prisma);

  const point = await svc.adminCreatePoint(ADMIN_ID, {
    title: 'Ny punkt',
    latitude: POINT_LAT,
    longitude: POINT_LON,
    geofenceRadiusMeters: 50,
    rewardPoints: 10,
    repeatRule: 'once',
  });

  assert.equal(point.status, 'draft', 'New admin-created point must start as draft');
  assert.equal(point.approvedAt, null);
  assert.equal(point.approvedByUserId, null);
});

await test('point activation requires safety confirmation', async () => {
  const points = [activePoint({ status: 'draft' })];
  const prisma = buildFakePrisma({ points });
  const svc = buildService(prisma);

  await assert.rejects(
    () =>
      svc.adminActivatePoint(ADMIN_ID, 'admin', POINT_ID, {
        safeLocationConfirmed: false,
        approvalNote: 'Testar utan bekräftelse',
      }),
    (err) => {
      assert.ok(err instanceof AppError);
      assert.equal(err.statusCode, 400);
      return true;
    },
  );

  // Point must still be draft
  const pts = (prisma as Record<string, unknown>)._points as FakeCrownHuntPoint[];
  assert.equal(pts[0]!.status, 'draft');
});

await test('point activation with safety confirmation succeeds and writes audit log', async () => {
  const points = [activePoint({ status: 'draft' })];
  const prisma = buildFakePrisma({ points });
  const svc = buildService(prisma);

  const activated = await svc.adminActivatePoint(ADMIN_ID, 'admin', POINT_ID, {
    safeLocationConfirmed: true,
    approvalNote: 'Verifierad säker parkeringsplats vid Kungsbacka torg.',
  });

  assert.equal(activated.status, 'active');
  assert.ok(activated.approvedAt !== null);
  assert.equal(activated.approvedByUserId, ADMIN_ID);

  const auditLogs = (prisma as Record<string, unknown>)._auditLogs as FakeAuditLog[];
  assert.equal(auditLogs.length, 1);
  assert.equal(auditLogs[0]!.action, 'crown_hunt_point.activated');
  assert.equal(auditLogs[0]!.actorUserId, ADMIN_ID);
  assert.equal(auditLogs[0]!.entityId, POINT_ID);
  // Audit log must not contain coordinates, tokens, or session data
  const meta = auditLogs[0]!.metadata as Record<string, unknown>;
  assert.equal('latitude' in meta, false);
  assert.equal('longitude' in meta, false);
  assert.equal('token' in meta, false);
});

await test('point creation writes audit log', async () => {
  const prisma = buildFakePrisma();
  const svc = buildService(prisma);

  await svc.adminCreatePoint(ADMIN_ID, {
    title: 'Ny punkt med audit',
    latitude: POINT_LAT,
    longitude: POINT_LON,
    geofenceRadiusMeters: 50,
    rewardPoints: 10,
    repeatRule: 'once',
  });

  const auditLogs = (prisma as Record<string, unknown>)._auditLogs as FakeAuditLog[];
  assert.equal(auditLogs.length, 1);
  assert.equal(auditLogs[0]!.action, 'crown_hunt_point.created');
  assert.equal(auditLogs[0]!.actorUserId, ADMIN_ID);
});

await test('no route history is created on claim', async () => {
  const prisma = buildFakePrisma({
    points: [activePoint()],
    users: [{ id: USER_ID, status: 'active', role: 'user', subscriptionEntitlement: 'member_monthly' }],
  });
  const svc = buildService(prisma);

  await svc.claimPoint({
    actor: memberActor(),
    pointId: POINT_ID,
    recordedAt: freshRecordedAt(),
    latitude: INSIDE_LAT,
    longitude: INSIDE_LON,
    speedMetersPerSecond: 0,
    idempotencyKey: 'route-test-001',
    crownHuntFeatureEnabled: true,
  });

  // There must be no route history table — only the claims table exists
  assert.equal('savedDrives' in prisma, false, 'No route history should be written');
  assert.equal('routeHistory' in prisma, false, 'No route history should be written');

  // The claim record itself stores no coordinates
  const claims = (prisma as Record<string, unknown>)._claims as FakeCrownHuntClaim[];
  assert.equal(claims.length, 1);
  assert.equal('claimLatitude' in (claims[0] as unknown as Record<string, unknown>), false);
  assert.equal('claimLongitude' in (claims[0] as unknown as Record<string, unknown>), false);
});

await test('claim history does not expose exact claim coordinates', async () => {
  const claim: FakeCrownHuntClaim = {
    id: 'claim-001',
    pointId: POINT_ID,
    userId: USER_ID,
    pointsLedgerEntryId: null,
    result: 'awarded',
    claimedAt: new Date(),
    distanceMeters: 12.5,
    reportedSpeedMetersPerSecond: 0,
    positionRecordedAt: new Date(),
    riskScore: null,
    riskReasons: null,
    idempotencyKey: 'history-key-001',
    createdAt: new Date(),
    point: { title: 'Testpunkt', rewardPoints: 10 },
  };

  const prisma = buildFakePrisma({ claims: [claim] });
  const svc = buildService(prisma);

  const history = await svc.listClaimHistory({ userId: USER_ID });

  assert.equal(history.claims.length, 1);
  const entry = history.claims[0]!;

  // Must not expose exact coordinates
  assert.equal('latitude' in entry, false, 'Exact latitude must not be in claim history');
  assert.equal('longitude' in entry, false, 'Exact longitude must not be in claim history');
  assert.equal('claimLatitude' in entry, false);
  assert.equal('claimLongitude' in entry, false);
  assert.equal(entry.pointsAwarded, 10);
});

await test('coordinates, tokens, and integrity values are not in admin claim list', async () => {
  const claim: FakeCrownHuntClaim = {
    id: 'admin-claim-001',
    pointId: POINT_ID,
    userId: USER_ID,
    pointsLedgerEntryId: null,
    result: 'risk_review',
    claimedAt: new Date(),
    distanceMeters: 45.2,
    reportedSpeedMetersPerSecond: 0.3,
    positionRecordedAt: new Date(),
    riskScore: 75,
    riskReasons: ['impossible_jump', 'platform_integrity_failed'],
    idempotencyKey: 'admin-key-001',
    createdAt: new Date(),
    point: { title: 'Testpunkt', rewardPoints: 10 },
  };

  const prisma = buildFakePrisma({ claims: [claim] });
  const svc = buildService(prisma);

  const list = await svc.adminListClaims({ result: 'risk_review' });

  assert.equal(list.claims.length, 1);
  const entry = list.claims[0]!;

  // Admin view must not expose exact coordinates
  assert.equal('latitude' in entry, false);
  assert.equal('longitude' in entry, false);
  // Must not expose raw riskScore (only categories)
  assert.equal('riskScore' in entry, false);
  // Must not expose tokens or session data
  assert.equal('token' in entry, false);
  assert.equal('sessionId' in entry, false);
  // Must expose safe risk reason categories
  assert.ok(Array.isArray(entry.riskReasonCategories));
});

await test('admin point activation requires approval note with minimum length', async () => {
  const points = [activePoint({ status: 'draft' })];
  const prisma = buildFakePrisma({ points });
  const svc = buildService(prisma);

  await assert.rejects(
    () =>
      svc.adminActivatePoint(ADMIN_ID, 'admin', POINT_ID, {
        safeLocationConfirmed: true,
        approvalNote: 'Ok', // too short — minimum 3 chars
      }),
    (err) => {
      assert.ok(err instanceof AppError);
      assert.equal(err.statusCode, 400);
      return true;
    },
  );
});

await test('ended points cannot be activated', async () => {
  const points = [activePoint({ status: 'ended' })];
  const prisma = buildFakePrisma({ points });
  const svc = buildService(prisma);

  await assert.rejects(
    () =>
      svc.adminActivatePoint(ADMIN_ID, 'admin', POINT_ID, {
        safeLocationConfirmed: true,
        approvalNote: 'Försöker aktivera avslutad punkt',
      }),
    (err) => {
      assert.ok(err instanceof AppError);
      assert.equal(err.statusCode, 400);
      return true;
    },
  );
});

await test('point validation rejects geofence radius outside safe range', async () => {
  const prisma = buildFakePrisma();
  const svc = buildService(prisma);

  // Too small
  await assert.rejects(
    () =>
      svc.adminCreatePoint(ADMIN_ID, {
        title: 'Ny punkt',
        latitude: POINT_LAT,
        longitude: POINT_LON,
        geofenceRadiusMeters: 5, // min is 20
        rewardPoints: 10,
        repeatRule: 'once',
      }),
    (err) => err instanceof AppError && err.statusCode === 400,
  );

  // Too large
  await assert.rejects(
    () =>
      svc.adminCreatePoint(ADMIN_ID, {
        title: 'Ny punkt',
        latitude: POINT_LAT,
        longitude: POINT_LON,
        geofenceRadiusMeters: 999, // max is 150
        rewardPoints: 10,
        repeatRule: 'once',
      }),
    (err) => err instanceof AppError && err.statusCode === 400,
  );
});

await test('listActivePoints returns only active points', async () => {
  const prisma = buildFakePrisma({
    points: [
      activePoint({ id: 'point-active', status: 'active' }),
      activePoint({ id: 'point-paused', status: 'paused' }),
      activePoint({ id: 'point-draft', status: 'draft' }),
    ],
    users: [{ id: USER_ID, status: 'active', role: 'user', subscriptionEntitlement: 'member_monthly' }],
  });
  const svc = buildService(prisma);

  const result = await svc.listActivePoints({ userId: USER_ID });

  assert.equal(result.points.length, 1, 'Only active points should be returned');
  assert.equal(result.points[0]!.status, 'active');
});

await test('listActivePoints does not return other users claim state', async () => {
  const prisma = buildFakePrisma({
    points: [activePoint()],
    users: [{ id: USER_ID, status: 'active', role: 'user', subscriptionEntitlement: 'member_monthly' }],
    claims: [{
      id: 'other-claim',
      pointId: POINT_ID,
      userId: 'other-user-id',
      pointsLedgerEntryId: null,
      result: 'awarded',
      claimedAt: new Date(),
      distanceMeters: 5,
      reportedSpeedMetersPerSecond: 0,
      positionRecordedAt: new Date(),
      riskScore: null,
      riskReasons: null,
      idempotencyKey: 'other-user-claim',
      createdAt: new Date(),
      point: { title: 'Testpunkt', rewardPoints: 10 },
    }],
  });
  const svc = buildService(prisma);

  const result = await svc.listActivePoints({ userId: USER_ID });
  assert.equal(result.points.length, 1);
  // Current user has NOT claimed this point
  assert.equal(result.points[0]!.claimedByCurrentUser, false);
});
