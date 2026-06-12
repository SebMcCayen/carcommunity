import { Prisma, type PrismaClient } from '@prisma/client';
import {
  isOwnerRole,
  type ModerationActionSummary,
  type UserRole,
  type UserStatus,
} from '@carcommunity/shared/users';

import { AppError } from './errors.js';

export interface ModerationActor {
  userId: string;
  role: UserRole;
}

export interface WarnUserInput {
  actor: ModerationActor;
  targetUserId: string;
  reason: string;
}

export interface SuspendTemporaryInput {
  actor: ModerationActor;
  targetUserId: string;
  reason: string;
  /** ISO 8601 datetime string */
  expiresAt: string;
}

export interface SuspendPermanentInput {
  actor: ModerationActor;
  targetUserId: string;
  reason: string;
}

export interface RestoreAccessInput {
  actor: ModerationActor;
  targetUserId: string;
  reason: string;
}

export interface WriteAuditLogInput {
  actorUserId: string | null;
  action: string;
  entityType: string;
  entityId?: string | null;
  reason?: string | null;
  metadata?: Prisma.InputJsonValue;
}

function toModerationActionSummary(row: {
  id: string;
  targetUserId: string;
  actorUserId: string | null;
  actionType: string;
  reason: string;
  createdAt: Date;
  expiresAt: Date | null;
}): ModerationActionSummary {
  return {
    id: row.id,
    targetUserId: row.targetUserId,
    actorUserId: row.actorUserId,
    actionType: row.actionType as ModerationActionSummary['actionType'],
    reason: row.reason,
    createdAt: row.createdAt.toISOString(),
    expiresAt: row.expiresAt ? row.expiresAt.toISOString() : null,
  };
}

/**
 * Verifies the target user exists and that a normal admin cannot moderate an owner.
 * Returns the target user's current role.
 */
async function resolveTarget(
  prisma: PrismaClient,
  actor: ModerationActor,
  targetUserId: string,
): Promise<{ role: UserRole; status: UserStatus }> {
  const target = await prisma.user.findUnique({
    where: { id: targetUserId },
    select: { role: true, status: true },
  });

  if (!target) {
    throw new AppError(404, 'not_found', 'Target user not found.');
  }

  // Normal admins cannot moderate owner users. Only owners can moderate owners.
  if (isOwnerRole(target.role as UserRole) && !isOwnerRole(actor.role)) {
    throw new AppError(403, 'forbidden', 'Admin users cannot moderate owner accounts.');
  }

  return { role: target.role as UserRole, status: target.status as UserStatus };
}

export class ModerationService {
  constructor(private readonly prisma: PrismaClient) {}

  /**
   * Issues a warning to a user and writes an audit log entry.
   * Updates user status to 'warned'.
   */
  async warnUser(input: WarnUserInput): Promise<ModerationActionSummary> {
    await resolveTarget(this.prisma, input.actor, input.targetUserId);

    const action = await this.prisma.$transaction(async (tx) => {
      const created = await tx.moderationAction.create({
        data: {
          targetUserId: input.targetUserId,
          actorUserId: input.actor.userId,
          actionType: 'warning',
          reason: input.reason,
        },
      });

      await tx.user.update({
        where: { id: input.targetUserId },
        data: { status: 'warned' },
      });

      await tx.auditLog.create({
        data: {
          actorUserId: input.actor.userId,
          action: 'moderation.warn',
          entityType: 'user',
          entityId: input.targetUserId,
          reason: input.reason,
          metadata: Prisma.DbNull,
        },
      });

      return created;
    });

    return toModerationActionSummary(action);
  }

  /**
   * Temporarily suspends a user and writes an audit log entry.
   * Updates user status to 'temporarily_suspended'.
   */
  async suspendTemporary(input: SuspendTemporaryInput): Promise<ModerationActionSummary> {
    await resolveTarget(this.prisma, input.actor, input.targetUserId);

    const expiresAt = new Date(input.expiresAt);
    if (isNaN(expiresAt.getTime()) || expiresAt <= new Date()) {
      throw new AppError(400, 'validation_error', 'expiresAt must be a valid future datetime.');
    }

    const action = await this.prisma.$transaction(async (tx) => {
      const created = await tx.moderationAction.create({
        data: {
          targetUserId: input.targetUserId,
          actorUserId: input.actor.userId,
          actionType: 'temporary_suspension',
          reason: input.reason,
          expiresAt,
        },
      });

      await tx.user.update({
        where: { id: input.targetUserId },
        data: { status: 'temporarily_suspended' },
      });

      await tx.auditLog.create({
        data: {
          actorUserId: input.actor.userId,
          action: 'moderation.suspend_temporary',
          entityType: 'user',
          entityId: input.targetUserId,
          reason: input.reason,
          metadata: Prisma.DbNull,
        },
      });

      return created;
    });

    return toModerationActionSummary(action);
  }

  /**
   * Permanently suspends a user and writes an audit log entry.
   * Updates user status to 'permanently_suspended'.
   */
  async suspendPermanent(input: SuspendPermanentInput): Promise<ModerationActionSummary> {
    await resolveTarget(this.prisma, input.actor, input.targetUserId);

    const action = await this.prisma.$transaction(async (tx) => {
      const created = await tx.moderationAction.create({
        data: {
          targetUserId: input.targetUserId,
          actorUserId: input.actor.userId,
          actionType: 'permanent_suspension',
          reason: input.reason,
        },
      });

      await tx.user.update({
        where: { id: input.targetUserId },
        data: { status: 'permanently_suspended' },
      });

      await tx.auditLog.create({
        data: {
          actorUserId: input.actor.userId,
          action: 'moderation.suspend_permanent',
          entityType: 'user',
          entityId: input.targetUserId,
          reason: input.reason,
          metadata: Prisma.DbNull,
        },
      });

      return created;
    });

    return toModerationActionSummary(action);
  }

  /**
   * Restores access for a suspended or warned user and writes an audit log entry.
   * Updates user status to 'active'.
   */
  async restoreAccess(input: RestoreAccessInput): Promise<ModerationActionSummary> {
    await resolveTarget(this.prisma, input.actor, input.targetUserId);

    const action = await this.prisma.$transaction(async (tx) => {
      const created = await tx.moderationAction.create({
        data: {
          targetUserId: input.targetUserId,
          actorUserId: input.actor.userId,
          actionType: 'restore_access',
          reason: input.reason,
        },
      });

      await tx.user.update({
        where: { id: input.targetUserId },
        data: { status: 'active' },
      });

      await tx.auditLog.create({
        data: {
          actorUserId: input.actor.userId,
          action: 'moderation.restore_access',
          entityType: 'user',
          entityId: input.targetUserId,
          reason: input.reason,
          metadata: Prisma.DbNull,
        },
      });

      return created;
    });

    return toModerationActionSummary(action);
  }

  /** Writes a single audit log entry. */
  async writeAuditLog(input: WriteAuditLogInput): Promise<void> {
    await this.prisma.auditLog.create({
      data: {
        actorUserId: input.actorUserId ?? null,
        action: input.action,
        entityType: input.entityType,
        entityId: input.entityId ?? null,
        reason: input.reason ?? null,
        metadata: input.metadata ?? Prisma.DbNull,
      },
    });
  }
}
