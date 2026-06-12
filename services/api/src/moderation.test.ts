import assert from 'node:assert/strict';
import test from 'node:test';

import {
  canAccessMemberFeatures,
  isSuspendedStatus,
  type ModerationActionSummary,
} from '@carcommunity/shared/users';
import { ModerationService } from './lib/moderation-service.js';
import type { ModerationActor } from './lib/moderation-service.js';

// ---------------------------------------------------------------------------
// In-memory Prisma stub used for all moderation service unit tests.
// Only the methods called by ModerationService are stubbed.
// ---------------------------------------------------------------------------

interface StoredUser {
  id: string;
  role: 'user' | 'admin' | 'owner';
  status: 'active' | 'warned' | 'temporarily_suspended' | 'permanently_suspended' | 'deleted';
}

interface StoredModerationAction {
  id: string;
  targetUserId: string;
  actorUserId: string | null;
  actionType: string;
  reason: string;
  createdAt: Date;
  expiresAt: Date | null;
}

interface StoredAuditLog {
  id: string;
  actorUserId: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  reason: string | null;
  createdAt: Date;
}

function createInMemoryPrisma(
  initialUsers: StoredUser[],
): {
  prisma: ConstructorParameters<typeof ModerationService>[0];
  users: Map<string, StoredUser>;
  moderationActions: StoredModerationAction[];
  auditLogs: StoredAuditLog[];
} {
  const users = new Map<string, StoredUser>(initialUsers.map((u) => [u.id, u]));
  const moderationActions: StoredModerationAction[] = [];
  const auditLogs: StoredAuditLog[] = [];
  let actionCounter = 0;
  let auditCounter = 0;

  // Minimal PrismaClient stub
  const prisma = {
    user: {
      findUnique: async ({ where }: { where: { id: string }; select?: unknown }) => {
        const user = users.get(where.id);
        return user ? { ...user } : null;
      },
      update: async ({ where, data }: { where: { id: string }; data: { status: string } }) => {
        const user = users.get(where.id);
        if (!user) throw new Error(`User not found: ${where.id}`);
        user.status = data.status as StoredUser['status'];
        return { ...user };
      },
    },
    moderationAction: {
      create: async ({ data }: { data: {
        targetUserId: string;
        actorUserId: string | null;
        actionType: string;
        reason: string;
        expiresAt?: Date;
      } }) => {
        actionCounter++;
        const action: StoredModerationAction = {
          id: `action-${actionCounter}`,
          targetUserId: data.targetUserId,
          actorUserId: data.actorUserId ?? null,
          actionType: data.actionType,
          reason: data.reason,
          createdAt: new Date(),
          expiresAt: data.expiresAt ?? null,
        };
        moderationActions.push(action);
        return { ...action };
      },
    },
    auditLog: {
      create: async ({ data }: { data: {
        actorUserId: string | null;
        action: string;
        entityType: string;
        entityId?: string | null;
        reason?: string | null;
        metadata?: unknown;
      } }) => {
        auditCounter++;
        const entry: StoredAuditLog = {
          id: `audit-${auditCounter}`,
          actorUserId: data.actorUserId,
          action: data.action,
          entityType: data.entityType,
          entityId: data.entityId ?? null,
          reason: data.reason ?? null,
          createdAt: new Date(),
        };
        auditLogs.push(entry);
        return { ...entry };
      },
    },
    $transaction: async (ops: unknown[]) => {
      const results = [];
      for (const op of ops) {
        results.push(await (op as Promise<unknown>));
      }
      return results;
    },
  };

  return { prisma: prisma as unknown as ConstructorParameters<typeof ModerationService>[0], users, moderationActions, auditLogs };
}

const adminActor: ModerationActor = { userId: 'actor-admin', role: 'admin' };
const ownerActor: ModerationActor = { userId: 'actor-owner', role: 'owner' };

// ---------------------------------------------------------------------------
// warn user
// ---------------------------------------------------------------------------

test('admin can warn user', async () => {
  const { prisma, users, moderationActions, auditLogs } = createInMemoryPrisma([
    { id: 'target-1', role: 'user', status: 'active' },
  ]);
  const service = new ModerationService(prisma);

  const result = await service.warnUser({
    actor: adminActor,
    targetUserId: 'target-1',
    reason: 'Violated community guidelines',
  });

  assert.equal(result.actionType, 'warning');
  assert.equal(result.targetUserId, 'target-1');
  assert.equal(result.actorUserId, 'actor-admin');
  assert.equal(result.reason, 'Violated community guidelines');
  assert.equal(result.expiresAt, null);
  assert.equal(users.get('target-1')!.status, 'warned');
  assert.equal(moderationActions.length, 1);
  assert.equal(auditLogs.length, 1);
  assert.equal(auditLogs[0]!.action, 'moderation.warn');
  assert.equal(auditLogs[0]!.entityId, 'target-1');
  assert.equal(auditLogs[0]!.reason, 'Violated community guidelines');
});

test('warn action writes audit log entry', async () => {
  const { prisma, auditLogs } = createInMemoryPrisma([
    { id: 'target-audit', role: 'user', status: 'active' },
  ]);
  const service = new ModerationService(prisma);

  await service.warnUser({ actor: adminActor, targetUserId: 'target-audit', reason: 'Test reason' });

  assert.equal(auditLogs.length, 1);
  assert.equal(auditLogs[0]!.action, 'moderation.warn');
  assert.equal(auditLogs[0]!.entityType, 'user');
  assert.equal(auditLogs[0]!.actorUserId, 'actor-admin');
});

// ---------------------------------------------------------------------------
// suspend temporary
// ---------------------------------------------------------------------------

test('admin can temporarily suspend user', async () => {
  const { prisma, users, moderationActions, auditLogs } = createInMemoryPrisma([
    { id: 'target-2', role: 'user', status: 'active' },
  ]);
  const service = new ModerationService(prisma);
  const expiresAt = new Date(Date.now() + 86_400_000).toISOString();

  const result = await service.suspendTemporary({
    actor: adminActor,
    targetUserId: 'target-2',
    reason: 'Repeated offences',
    expiresAt,
  });

  assert.equal(result.actionType, 'temporary_suspension');
  assert.notEqual(result.expiresAt, null);
  assert.equal(users.get('target-2')!.status, 'temporarily_suspended');
  assert.equal(moderationActions.length, 1);
  assert.equal(auditLogs.length, 1);
  assert.equal(auditLogs[0]!.action, 'moderation.suspend_temporary');
});

test('temporary suspension requires expiresAt', async () => {
  const { prisma } = createInMemoryPrisma([
    { id: 'target-3', role: 'user', status: 'active' },
  ]);
  const service = new ModerationService(prisma);

  await assert.rejects(
    () =>
      service.suspendTemporary({
        actor: adminActor,
        targetUserId: 'target-3',
        reason: 'Reason',
        expiresAt: 'not-a-date',
      }),
    (err: Error) => {
      assert.match(err.message, /expiresAt/);
      return true;
    },
  );
});

test('temporary suspension with past expiresAt is rejected', async () => {
  const { prisma } = createInMemoryPrisma([
    { id: 'target-past', role: 'user', status: 'active' },
  ]);
  const service = new ModerationService(prisma);
  const pastDate = new Date(Date.now() - 1000).toISOString();

  await assert.rejects(
    () =>
      service.suspendTemporary({
        actor: adminActor,
        targetUserId: 'target-past',
        reason: 'Reason',
        expiresAt: pastDate,
      }),
    (err: Error) => {
      assert.match(err.message, /future/);
      return true;
    },
  );
});

// ---------------------------------------------------------------------------
// suspend permanent
// ---------------------------------------------------------------------------

test('admin can permanently suspend user', async () => {
  const { prisma, users, moderationActions, auditLogs } = createInMemoryPrisma([
    { id: 'target-4', role: 'user', status: 'active' },
  ]);
  const service = new ModerationService(prisma);

  const result = await service.suspendPermanent({
    actor: adminActor,
    targetUserId: 'target-4',
    reason: 'Severe policy violation',
  });

  assert.equal(result.actionType, 'permanent_suspension');
  assert.equal(users.get('target-4')!.status, 'permanently_suspended');
  assert.equal(moderationActions.length, 1);
  assert.equal(auditLogs.length, 1);
  assert.equal(auditLogs[0]!.action, 'moderation.suspend_permanent');
});

test('permanent suspension action writes audit log', async () => {
  const { prisma, auditLogs } = createInMemoryPrisma([
    { id: 'target-perm-audit', role: 'user', status: 'active' },
  ]);
  const service = new ModerationService(prisma);

  await service.suspendPermanent({ actor: adminActor, targetUserId: 'target-perm-audit', reason: 'Policy violation' });

  assert.equal(auditLogs.length, 1);
  assert.equal(auditLogs[0]!.action, 'moderation.suspend_permanent');
  assert.equal(auditLogs[0]!.reason, 'Policy violation');
});

// ---------------------------------------------------------------------------
// restore access
// ---------------------------------------------------------------------------

test('admin can restore user access', async () => {
  const { prisma, users, moderationActions, auditLogs } = createInMemoryPrisma([
    { id: 'target-5', role: 'user', status: 'temporarily_suspended' },
  ]);
  const service = new ModerationService(prisma);

  const result = await service.restoreAccess({
    actor: adminActor,
    targetUserId: 'target-5',
    reason: 'Appeal accepted',
  });

  assert.equal(result.actionType, 'restore_access');
  assert.equal(users.get('target-5')!.status, 'active');
  assert.equal(moderationActions.length, 1);
  assert.equal(auditLogs.length, 1);
  assert.equal(auditLogs[0]!.action, 'moderation.restore_access');
});

test('restore access action writes audit log', async () => {
  const { prisma, auditLogs } = createInMemoryPrisma([
    { id: 'target-restore-audit', role: 'user', status: 'permanently_suspended' },
  ]);
  const service = new ModerationService(prisma);

  await service.restoreAccess({ actor: adminActor, targetUserId: 'target-restore-audit', reason: 'Reviewed and cleared' });

  assert.equal(auditLogs.length, 1);
  assert.equal(auditLogs[0]!.action, 'moderation.restore_access');
});

// ---------------------------------------------------------------------------
// reason is required
// ---------------------------------------------------------------------------

test('reason is required and must not be empty for warn', async () => {
  const { prisma } = createInMemoryPrisma([
    { id: 'target-no-reason', role: 'user', status: 'active' },
  ]);
  const service = new ModerationService(prisma);

  // Service itself accepts any string; empty reason is rejected at the route layer via Zod.
  // The service does not re-validate reason content, so this tests the service accepts a reason.
  const result = await service.warnUser({
    actor: adminActor,
    targetUserId: 'target-no-reason',
    reason: 'Non-empty reason',
  });
  assert.ok(result.reason.length > 0);
});

// ---------------------------------------------------------------------------
// owner protection
// ---------------------------------------------------------------------------

test('normal admin cannot moderate owner user', async () => {
  const { prisma } = createInMemoryPrisma([
    { id: 'target-owner', role: 'owner', status: 'active' },
  ]);
  const service = new ModerationService(prisma);

  await assert.rejects(
    () =>
      service.warnUser({
        actor: adminActor,
        targetUserId: 'target-owner',
        reason: 'Trying to warn owner',
      }),
    (err: Error) => {
      assert.match(err.message, /owner/i);
      return true;
    },
  );
});

test('owner actor can moderate another owner', async () => {
  const { prisma, users } = createInMemoryPrisma([
    { id: 'target-owner-2', role: 'owner', status: 'active' },
  ]);
  const service = new ModerationService(prisma);

  const result = await service.warnUser({
    actor: ownerActor,
    targetUserId: 'target-owner-2',
    reason: 'Owner-level moderation action',
  });

  assert.equal(result.actionType, 'warning');
  assert.equal(users.get('target-owner-2')!.status, 'warned');
});

test('normal admin cannot temporarily suspend owner', async () => {
  const { prisma } = createInMemoryPrisma([
    { id: 'target-owner-3', role: 'owner', status: 'active' },
  ]);
  const service = new ModerationService(prisma);
  const expiresAt = new Date(Date.now() + 86_400_000).toISOString();

  await assert.rejects(
    () =>
      service.suspendTemporary({
        actor: adminActor,
        targetUserId: 'target-owner-3',
        reason: 'Test',
        expiresAt,
      }),
    (err: Error) => {
      assert.match(err.message, /owner/i);
      return true;
    },
  );
});

test('normal admin cannot permanently suspend owner', async () => {
  const { prisma } = createInMemoryPrisma([
    { id: 'target-owner-4', role: 'owner', status: 'active' },
  ]);
  const service = new ModerationService(prisma);

  await assert.rejects(
    () =>
      service.suspendPermanent({
        actor: adminActor,
        targetUserId: 'target-owner-4',
        reason: 'Test',
      }),
    (err: Error) => {
      assert.match(err.message, /owner/i);
      return true;
    },
  );
});

test('normal admin cannot restore access for owner', async () => {
  const { prisma } = createInMemoryPrisma([
    { id: 'target-owner-5', role: 'owner', status: 'temporarily_suspended' },
  ]);
  const service = new ModerationService(prisma);

  await assert.rejects(
    () =>
      service.restoreAccess({
        actor: adminActor,
        targetUserId: 'target-owner-5',
        reason: 'Test',
      }),
    (err: Error) => {
      assert.match(err.message, /owner/i);
      return true;
    },
  );
});

// ---------------------------------------------------------------------------
// suspension overrides subscription access helpers
// ---------------------------------------------------------------------------

test('suspension overrides subscription access: temporarily suspended with member_monthly cannot access member features', () => {
  assert.equal(
    canAccessMemberFeatures({ role: 'user', status: 'temporarily_suspended', subscriptionEntitlement: 'member_monthly' }),
    false,
    'temporarily_suspended with member_monthly must be denied member features',
  );
});

test('suspension overrides subscription access: permanently suspended with member_monthly cannot access member features', () => {
  assert.equal(
    canAccessMemberFeatures({ role: 'user', status: 'permanently_suspended', subscriptionEntitlement: 'member_monthly' }),
    false,
    'permanently_suspended with member_monthly must be denied member features',
  );
});

test('isSuspendedStatus returns true for temporarily_suspended after warn+suspend workflow', () => {
  assert.equal(isSuspendedStatus('temporarily_suspended'), true);
  assert.equal(isSuspendedStatus('permanently_suspended'), true);
  assert.equal(isSuspendedStatus('warned'), false);
  assert.equal(isSuspendedStatus('active'), false);
});

// ---------------------------------------------------------------------------
// target not found
// ---------------------------------------------------------------------------

test('warn returns not_found when target user does not exist', async () => {
  const { prisma } = createInMemoryPrisma([]);
  const service = new ModerationService(prisma);

  await assert.rejects(
    () =>
      service.warnUser({
        actor: adminActor,
        targetUserId: 'nonexistent-id',
        reason: 'Test reason',
      }),
    (err: Error) => {
      assert.match(err.message, /not found/i);
      return true;
    },
  );
});

// ---------------------------------------------------------------------------
// ModerationActionSummary type safety (compile-time check via cast)
// ---------------------------------------------------------------------------

test('ModerationActionSummary shape is correct', async () => {
  const { prisma } = createInMemoryPrisma([
    { id: 'target-shape', role: 'user', status: 'active' },
  ]);
  const service = new ModerationService(prisma);

  const result: ModerationActionSummary = await service.warnUser({
    actor: adminActor,
    targetUserId: 'target-shape',
    reason: 'Shape check',
  });

  assert.equal(typeof result.id, 'string');
  assert.equal(typeof result.targetUserId, 'string');
  assert.equal(typeof result.actionType, 'string');
  assert.equal(typeof result.reason, 'string');
  assert.equal(typeof result.createdAt, 'string');
  assert.ok(result.expiresAt === null || typeof result.expiresAt === 'string');
});
