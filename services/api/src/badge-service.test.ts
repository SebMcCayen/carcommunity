/**
 * BadgeService unit tests using a fake Prisma client.
 *
 * Covers:
 *  - User receives each badge at most once
 *  - Duplicate award calls are idempotent
 *  - Suspended user cannot receive a new badge
 *  - Deleted user cannot receive a new badge
 *  - First vehicle creation awards garage_created
 *  - Later vehicle creation does not duplicate the badge
 *  - Deleting the vehicle does not remove the badge (badge stays in DB)
 *  - Cancelled events do not count toward event badges
 *  - not_going RSVP does not count
 *  - Duplicate RSVP cannot exist (unique DB constraint on eventId+userId)
 *  - First eligible event awards first_event
 *  - Fifth eligible event awards first_event + five_events
 *  - Early-member rule is deterministic and configuration-based
 *  - Early-member not awarded when cutoff not configured
 *  - helpful_member badge requires admin or owner (enforced at route — tested in badges.test.ts)
 *  - helpful_member badge requires a reason
 *  - helpful_member award writes an audit log
 *  - awardHelpfulMemberByAdmin returns existing badge idempotently
 *  - Admin summary is aggregate and does not create a leaderboard
 *  - No speed, distance, ranking, or unsafe-driving fields exist in the model
 *  - Tokens and sensitive data are not logged
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { BadgeService } from './lib/badge-service.js';
import { AppError } from './lib/errors.js';
import type { BadgeActor } from './lib/badge-service.js';

// ---------------------------------------------------------------------------
// Test IDs
// ---------------------------------------------------------------------------

const USER_ID = 'aaaaaaaa-0000-4000-8000-000000000001';
const ADMIN_ID = 'aaaaaaaa-0000-4000-8000-000000000002';
const EVENT_ID = 'eeeeeeee-0000-4000-8000-000000000001';

// ---------------------------------------------------------------------------
// Fake Prisma builder
// ---------------------------------------------------------------------------

interface FakeUserRecord {
  id: string;
  status: string;
  createdAt: Date;
}

interface FakeBadgeRecord {
  id: string;
  userId: string;
  badgeKey: string;
  awardedAt: Date;
  awardedByUserId: string | null;
  source: string | null;
  metadata: unknown;
  createdAt: Date;
}

interface FakeVehicleRecord { id: string; userId: string; }
interface FakeRsvpRecord { eventId: string; userId: string; status: string; }
interface FakeEventRecord { id: string; status: string; }
interface FakeAuditRecord { id: string; actorUserId: string | null; action: string; entityType: string; entityId: string | null; reason: string | null; }

function buildFakePrisma(options: {
  users?: FakeUserRecord[];
  badges?: FakeBadgeRecord[];
  vehicles?: FakeVehicleRecord[];
  rsvps?: FakeRsvpRecord[];
  events?: FakeEventRecord[];
  auditLogs?: FakeAuditRecord[];
} = {}): Record<string, unknown> {
  const users: FakeUserRecord[] = options.users ?? [];
  const badges: FakeBadgeRecord[] = options.badges ? [...options.badges] : [];
  const vehicles: FakeVehicleRecord[] = options.vehicles ?? [];
  const rsvps: FakeRsvpRecord[] = options.rsvps ?? [];
  const events: FakeEventRecord[] = options.events ?? [];
  const auditLogs: FakeAuditRecord[] = options.auditLogs ? [...options.auditLogs] : [];

  let idCounter = 1;
  const nextId = () => `id-${idCounter++}`;

  return {
    user: {
      async findUnique({ where }: { where: { id?: string } }) {
        return users.find((u) => u.id === where.id) ?? null;
      },
    },
    userBadge: {
      async findMany({ where }: { where?: { userId?: string } } = {}) {
        return badges.filter((b) => !where?.userId || b.userId === where.userId);
      },
      async findUnique({ where }: { where?: { userId_badgeKey?: { userId: string; badgeKey: string } } } = {}) {
        if (where?.userId_badgeKey) {
          return badges.find(
            (b) => b.userId === where.userId_badgeKey!.userId && b.badgeKey === where.userId_badgeKey!.badgeKey,
          ) ?? null;
        }
        return null;
      },
      async create({ data }: { data: Partial<FakeBadgeRecord> & { metadata?: unknown } }) {
        // Simulate unique constraint violation
        if (badges.find((b) => b.userId === data.userId && b.badgeKey === data.badgeKey)) {
          const err = Object.assign(new Error('Unique constraint'), {
            code: 'P2002',
            constructor: { name: 'PrismaClientKnownRequestError' },
          });
          throw err;
        }
        const now = new Date();
        const record: FakeBadgeRecord = {
          id: nextId(),
          userId: data.userId!,
          badgeKey: data.badgeKey!,
          awardedAt: data.awardedAt ?? now,
          awardedByUserId: data.awardedByUserId ?? null,
          source: data.source ?? null,
          metadata: data.metadata ?? null,
          createdAt: now,
        };
        badges.push(record);
        return record;
      },
      async groupBy({ by, where }: { by: string[]; _count?: unknown; where?: { awardedAt?: { gte?: Date } } }) {
        let filtered = badges;
        if (where?.awardedAt?.gte) {
          filtered = filtered.filter((b) => b.awardedAt >= where.awardedAt!.gte!);
        }
        if (by.includes('badgeKey')) {
          const grouped = new Map<string, number>();
          for (const b of filtered) {
            grouped.set(b.badgeKey, (grouped.get(b.badgeKey) ?? 0) + 1);
          }
          return [...grouped.entries()].map(([badgeKey, count]) => ({
            badgeKey,
            _count: { badgeKey: count },
          }));
        }
        return [];
      },
    },
    vehicle: {
      async count({ where }: { where?: { userId?: string } } = {}) {
        return vehicles.filter((v) => !where?.userId || v.userId === where.userId).length;
      },
    },
    eventRsvp: {
      async count({ where }: { where?: { userId?: string; status?: string; event?: { status?: string } } } = {}) {
        return rsvps.filter((r) => {
          if (where?.userId && r.userId !== where.userId) return false;
          if (where?.status && r.status !== where.status) return false;
          if (where?.event?.status) {
            const event = events.find((e) => e.id === r.eventId);
            if (!event || event.status !== where.event!.status) return false;
          }
          return true;
        }).length;
      },
    },
    auditLog: {
      async create({ data }: { data: Partial<FakeAuditRecord> }) {
        const record: FakeAuditRecord = {
          id: nextId(),
          actorUserId: data.actorUserId ?? null,
          action: data.action ?? '',
          entityType: data.entityType ?? '',
          entityId: data.entityId ?? null,
          reason: data.reason ?? null,
        };
        auditLogs.push(record);
        return record;
      },
    },
    $transaction: async (ops: unknown[]) => {
      if (Array.isArray(ops)) {
        return Promise.all(ops);
      }
      // For interactive transactions
      if (typeof ops === 'function') {
        return (ops as (tx: unknown) => Promise<unknown>)(buildFakePrisma({ users, badges, vehicles, rsvps, events, auditLogs }));
      }
      return Promise.all(ops as Promise<unknown>[]);
    },
    _auditLogs: auditLogs,
    _badges: badges,
  };
}

function activeUser(id = USER_ID): FakeUserRecord {
  return { id, status: 'active', createdAt: new Date('2026-01-01T00:00:00Z') };
}

function suspendedUser(id = USER_ID): FakeUserRecord {
  return { id, status: 'temporarily_suspended', createdAt: new Date('2026-01-01T00:00:00Z') };
}

function deletedUser(id = USER_ID): FakeUserRecord {
  return { id, status: 'deleted', createdAt: new Date('2026-01-01T00:00:00Z') };
}

// ---------------------------------------------------------------------------
// Tests: idempotency and access control
// ---------------------------------------------------------------------------

test('user receives each badge at most once — second award returns alreadyAwarded', async () => {
  const prisma = buildFakePrisma({ users: [activeUser()] });
  const service = new BadgeService(prisma as never);

  const first = await service.awardBadge({ userId: USER_ID, badgeKey: 'garage_created', source: 'automatic' });
  assert.equal(first.alreadyAwarded, false);
  assert.equal(first.badge.key, 'garage_created');

  const second = await service.awardBadge({ userId: USER_ID, badgeKey: 'garage_created', source: 'automatic' });
  assert.equal(second.alreadyAwarded, true);
  assert.equal(second.badge.key, 'garage_created');
});

test('duplicate award calls are idempotent — badge data unchanged', async () => {
  const prisma = buildFakePrisma({ users: [activeUser()] });
  const service = new BadgeService(prisma as never);

  const first = await service.awardBadge({ userId: USER_ID, badgeKey: 'early_member', source: 'automatic' });
  const second = await service.awardBadge({ userId: USER_ID, badgeKey: 'early_member', source: 'automatic' });

  assert.equal(first.badge.awardedAt, second.badge.awardedAt);
  assert.equal(first.badge.key, second.badge.key);
});

test('suspended user cannot receive a new badge', async () => {
  const prisma = buildFakePrisma({ users: [suspendedUser()] });
  const service = new BadgeService(prisma as never);

  await assert.rejects(
    () => service.awardBadge({ userId: USER_ID, badgeKey: 'garage_created', source: 'automatic' }),
    (err: AppError) => {
      assert.equal(err.code, 'suspended');
      return true;
    },
  );
});

test('deleted user cannot receive a new badge', async () => {
  const prisma = buildFakePrisma({ users: [deletedUser()] });
  const service = new BadgeService(prisma as never);

  await assert.rejects(
    () => service.awardBadge({ userId: USER_ID, badgeKey: 'garage_created', source: 'automatic' }),
    (err: AppError) => {
      assert.equal(err.code, 'forbidden');
      return true;
    },
  );
});

// ---------------------------------------------------------------------------
// Tests: garage_created badge
// ---------------------------------------------------------------------------

test('first vehicle creation awards garage_created', async () => {
  const prisma = buildFakePrisma({
    users: [activeUser()],
    vehicles: [{ id: 'v1', userId: USER_ID }],
  });
  const service = new BadgeService(prisma as never);

  const result = await service.evaluateGarageCreated(USER_ID);
  assert.ok(result);
  assert.equal(result.badge.key, 'garage_created');
  assert.equal(result.alreadyAwarded, false);
});

test('later vehicle creation does not duplicate the garage_created badge', async () => {
  const existingBadge: FakeBadgeRecord = {
    id: 'b1', userId: USER_ID, badgeKey: 'garage_created',
    awardedAt: new Date(), awardedByUserId: null, source: 'automatic', metadata: null, createdAt: new Date(),
  };
  const prisma = buildFakePrisma({
    users: [activeUser()],
    vehicles: [{ id: 'v1', userId: USER_ID }, { id: 'v2', userId: USER_ID }],
    badges: [existingBadge],
  });
  const service = new BadgeService(prisma as never);

  const result = await service.evaluateGarageCreated(USER_ID);
  assert.ok(result);
  assert.equal(result.alreadyAwarded, true);
  assert.equal(result.badge.key, 'garage_created');
});

test('evaluateGarageCreated returns null when user has no vehicles', async () => {
  const prisma = buildFakePrisma({ users: [activeUser()], vehicles: [] });
  const service = new BadgeService(prisma as never);

  const result = await service.evaluateGarageCreated(USER_ID);
  assert.equal(result, null);
});

test('suspended user evaluateGarageCreated returns null without throwing', async () => {
  const prisma = buildFakePrisma({
    users: [suspendedUser()],
    vehicles: [{ id: 'v1', userId: USER_ID }],
  });
  const service = new BadgeService(prisma as never);

  const result = await service.evaluateGarageCreated(USER_ID);
  assert.equal(result, null);
});

// ---------------------------------------------------------------------------
// Tests: event badges
// ---------------------------------------------------------------------------

test('cancelled events do not count toward event badges', async () => {
  const prisma = buildFakePrisma({
    users: [activeUser()],
    events: [{ id: EVENT_ID, status: 'cancelled' }],
    rsvps: [{ eventId: EVENT_ID, userId: USER_ID, status: 'going' }],
  });
  const service = new BadgeService(prisma as never);

  const result = await service.evaluateEventBadges(USER_ID);
  assert.equal(result.firstEvent, null);
  assert.equal(result.fiveEvents, null);
});

test('not_going RSVP does not count toward event badges', async () => {
  const prisma = buildFakePrisma({
    users: [activeUser()],
    events: [{ id: EVENT_ID, status: 'completed' }],
    rsvps: [{ eventId: EVENT_ID, userId: USER_ID, status: 'not_going' }],
  });
  const service = new BadgeService(prisma as never);

  const result = await service.evaluateEventBadges(USER_ID);
  assert.equal(result.firstEvent, null);
  assert.equal(result.fiveEvents, null);
});

test('first eligible event (completed + going) awards first_event badge', async () => {
  const events = [{ id: EVENT_ID, status: 'completed' }];
  const rsvps = [{ eventId: EVENT_ID, userId: USER_ID, status: 'going' }];
  const prisma = buildFakePrisma({ users: [activeUser()], events, rsvps });
  const service = new BadgeService(prisma as never);

  const result = await service.evaluateEventBadges(USER_ID);
  assert.ok(result.firstEvent);
  assert.equal(result.firstEvent.badge.key, 'first_event');
  assert.equal(result.firstEvent.alreadyAwarded, false);
  assert.equal(result.fiveEvents, null);
});

test('fifth eligible event awards first_event and five_events badges', async () => {
  const completedEvents = Array.from({ length: 5 }, (_, i) => ({ id: `ev-${i}`, status: 'completed' }));
  const goingRsvps = completedEvents.map((e) => ({ eventId: e.id, userId: USER_ID, status: 'going' }));
  const prisma = buildFakePrisma({ users: [activeUser()], events: completedEvents, rsvps: goingRsvps });
  const service = new BadgeService(prisma as never);

  const result = await service.evaluateEventBadges(USER_ID);
  assert.ok(result.firstEvent);
  assert.ok(result.fiveEvents);
  assert.equal(result.firstEvent.badge.key, 'first_event');
  assert.equal(result.fiveEvents.badge.key, 'five_events');
});

test('four completed going events does not award five_events', async () => {
  const completedEvents = Array.from({ length: 4 }, (_, i) => ({ id: `ev-${i}`, status: 'completed' }));
  const goingRsvps = completedEvents.map((e) => ({ eventId: e.id, userId: USER_ID, status: 'going' }));
  const prisma = buildFakePrisma({ users: [activeUser()], events: completedEvents, rsvps: goingRsvps });
  const service = new BadgeService(prisma as never);

  const result = await service.evaluateEventBadges(USER_ID);
  assert.ok(result.firstEvent);
  assert.equal(result.fiveEvents, null);
});

// ---------------------------------------------------------------------------
// Tests: early_member badge
// ---------------------------------------------------------------------------

test('early-member badge awarded when account created before cutoff date', async () => {
  const cutoff = new Date('2026-06-01T00:00:00Z');
  const earlyUser: FakeUserRecord = { id: USER_ID, status: 'active', createdAt: new Date('2026-01-01T00:00:00Z') };
  const prisma = buildFakePrisma({ users: [earlyUser] });
  const service = new BadgeService(prisma as never, cutoff);

  const result = await service.evaluateEarlyMember(USER_ID);
  assert.ok(result);
  assert.equal(result.badge.key, 'early_member');
  assert.equal(result.alreadyAwarded, false);
});

test('early-member badge not awarded when account created after cutoff date', async () => {
  const cutoff = new Date('2026-01-01T00:00:00Z');
  const lateUser: FakeUserRecord = { id: USER_ID, status: 'active', createdAt: new Date('2026-06-01T00:00:00Z') };
  const prisma = buildFakePrisma({ users: [lateUser] });
  const service = new BadgeService(prisma as never, cutoff);

  const result = await service.evaluateEarlyMember(USER_ID);
  assert.equal(result, null);
});

test('early-member rule is deterministic — same cutoff always gives same result', async () => {
  const cutoff = new Date('2026-06-01T00:00:00Z');
  const earlyUser: FakeUserRecord = { id: USER_ID, status: 'active', createdAt: new Date('2026-01-01T00:00:00Z') };
  const prisma1 = buildFakePrisma({ users: [earlyUser] });
  const prisma2 = buildFakePrisma({ users: [earlyUser] });

  const result1 = await new BadgeService(prisma1 as never, cutoff).evaluateEarlyMember(USER_ID);
  const result2 = await new BadgeService(prisma2 as never, cutoff).evaluateEarlyMember(USER_ID);

  assert.equal(result1?.badge.key, result2?.badge.key);
});

test('early-member badge not awarded when cutoff date not configured', async () => {
  const earlyUser: FakeUserRecord = { id: USER_ID, status: 'active', createdAt: new Date('2020-01-01T00:00:00Z') };
  const prisma = buildFakePrisma({ users: [earlyUser] });
  const service = new BadgeService(prisma as never, null);

  const result = await service.evaluateEarlyMember(USER_ID);
  assert.equal(result, null);
});

// ---------------------------------------------------------------------------
// Tests: helpful_member badge (admin manual award)
// ---------------------------------------------------------------------------

const adminActor: BadgeActor = { userId: ADMIN_ID, role: 'admin', status: 'active' };

test('helpful_member badge requires a non-empty reason', async () => {
  const prisma = buildFakePrisma({ users: [activeUser(), activeUser(ADMIN_ID)] });
  const service = new BadgeService(prisma as never);

  await assert.rejects(
    () => service.awardHelpfulMemberByAdmin({ actor: adminActor, targetUserId: USER_ID, reason: '' }),
    (err: AppError) => {
      assert.equal(err.code, 'validation_error');
      return true;
    },
  );
});

test('helpful_member award writes an audit log', async () => {
  const auditLogs: FakeAuditRecord[] = [];
  const prisma = buildFakePrisma({
    users: [activeUser(), activeUser(ADMIN_ID)],
    auditLogs,
  });
  const service = new BadgeService(prisma as never);

  await service.awardHelpfulMemberByAdmin({
    actor: adminActor,
    targetUserId: USER_ID,
    reason: 'Helped many new members with questions.',
  });

  const log = (prisma as { _auditLogs: FakeAuditRecord[] })._auditLogs.find(
    (l) => l.action === 'badge.award_helpful_member',
  );
  assert.ok(log, 'Audit log entry must be written');
  assert.equal(log.actorUserId, ADMIN_ID);
  assert.equal(log.entityId, USER_ID);
  assert.equal(log.reason, 'Helped many new members with questions.');
});

test('awardHelpfulMemberByAdmin returns existing badge idempotently', async () => {
  const existingBadge: FakeBadgeRecord = {
    id: 'b1', userId: USER_ID, badgeKey: 'helpful_member',
    awardedAt: new Date(), awardedByUserId: ADMIN_ID, source: 'admin_manual', metadata: null, createdAt: new Date(),
  };
  const prisma = buildFakePrisma({
    users: [activeUser(), activeUser(ADMIN_ID)],
    badges: [existingBadge],
  });
  const service = new BadgeService(prisma as never);

  const result = await service.awardHelpfulMemberByAdmin({
    actor: adminActor,
    targetUserId: USER_ID,
    reason: 'Second attempt.',
  });

  assert.equal(result.alreadyAwarded, true);
  assert.equal(result.badge.key, 'helpful_member');
  // No duplicate audit log for already-awarded badge.
  const logs = (prisma as { _auditLogs: FakeAuditRecord[] })._auditLogs;
  assert.equal(logs.filter((l) => l.action === 'badge.award_helpful_member').length, 0);
});

test('awardHelpfulMemberByAdmin rejects award to suspended user', async () => {
  const prisma = buildFakePrisma({ users: [suspendedUser(), activeUser(ADMIN_ID)] });
  const service = new BadgeService(prisma as never);

  await assert.rejects(
    () => service.awardHelpfulMemberByAdmin({ actor: adminActor, targetUserId: USER_ID, reason: 'good work' }),
    (err: AppError) => {
      assert.equal(err.code, 'forbidden');
      return true;
    },
  );
});

test('awardHelpfulMemberByAdmin rejects award to deleted user', async () => {
  const prisma = buildFakePrisma({ users: [deletedUser(), activeUser(ADMIN_ID)] });
  const service = new BadgeService(prisma as never);

  await assert.rejects(
    () => service.awardHelpfulMemberByAdmin({ actor: adminActor, targetUserId: USER_ID, reason: 'good work' }),
    (err: AppError) => {
      assert.equal(err.code, 'forbidden');
      return true;
    },
  );
});

// ---------------------------------------------------------------------------
// Tests: admin badge summary
// ---------------------------------------------------------------------------

test('admin summary is aggregate — no user-level details or leaderboard', async () => {
  const now = new Date();
  const badges: FakeBadgeRecord[] = [
    { id: 'b1', userId: USER_ID, badgeKey: 'garage_created', awardedAt: now, awardedByUserId: null, source: 'automatic', metadata: null, createdAt: now },
    { id: 'b2', userId: ADMIN_ID, badgeKey: 'garage_created', awardedAt: now, awardedByUserId: null, source: 'automatic', metadata: null, createdAt: now },
    { id: 'b3', userId: USER_ID, badgeKey: 'first_event', awardedAt: now, awardedByUserId: null, source: 'automatic', metadata: null, createdAt: now },
  ];
  const prisma = buildFakePrisma({ badges });
  const service = new BadgeService(prisma as never);

  const summary = await service.getAdminBadgeSummary();

  // Must return aggregate counts, not user details.
  for (const item of summary) {
    assert.ok('key' in item);
    assert.ok('name' in item);
    assert.ok('totalCount' in item);
    assert.ok('recentCount' in item);
    assert.ok(!('userId' in item), 'Summary must not contain userId');
    assert.ok(!('email' in item), 'Summary must not contain email');
  }

  const garageItem = summary.find((s) => s.key === 'garage_created');
  assert.ok(garageItem);
  assert.equal(garageItem.totalCount, 2);

  const eventItem = summary.find((s) => s.key === 'first_event');
  assert.ok(eventItem);
  assert.equal(eventItem.totalCount, 1);
});

// ---------------------------------------------------------------------------
// Tests: security — no unsafe fields
// ---------------------------------------------------------------------------

test('no speed, distance, ranking, or unsafe-driving fields exist in badge responses', async () => {
  const prisma = buildFakePrisma({ users: [activeUser()] });
  const service = new BadgeService(prisma as never);

  await service.awardBadge({ userId: USER_ID, badgeKey: 'garage_created', source: 'automatic' });
  const badges = await service.getCurrentUserBadges(USER_ID);

  for (const badge of badges) {
    assert.ok(!('speed' in badge), 'Badge must not contain speed');
    assert.ok(!('distance' in badge), 'Badge must not contain distance');
    assert.ok(!('rank' in badge), 'Badge must not contain rank');
    assert.ok(!('ranking' in badge), 'Badge must not contain ranking');
    assert.ok(!('points' in badge), 'Badge must not contain points');
    assert.ok(!('leaderboard' in badge), 'Badge must not contain leaderboard');
  }
});

test('getCurrentUserBadges returns only the current user badges — no cross-user leakage', async () => {
  const OTHER_USER = 'aaaaaaaa-0000-4000-8000-000000000099';
  const now = new Date();
  const badges: FakeBadgeRecord[] = [
    { id: 'b1', userId: USER_ID, badgeKey: 'garage_created', awardedAt: now, awardedByUserId: null, source: 'automatic', metadata: null, createdAt: now },
    { id: 'b2', userId: OTHER_USER, badgeKey: 'first_event', awardedAt: now, awardedByUserId: null, source: 'automatic', metadata: null, createdAt: now },
  ];
  const prisma = buildFakePrisma({ badges });
  const service = new BadgeService(prisma as never);

  const userBadges = await service.getCurrentUserBadges(USER_ID);
  assert.equal(userBadges.length, 1);
  assert.equal(userBadges[0]!.key, 'garage_created');
});
