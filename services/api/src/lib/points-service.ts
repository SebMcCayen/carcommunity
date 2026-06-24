/**
 * PointsService — backend business logic for the Kronpoäng (KP) points ledger.
 *
 * Design rules enforced here:
 *  - Backend is the sole authority for balances and transactions.
 *  - Clients must never calculate or overwrite balances.
 *  - Every balance change creates an append-only ledger entry.
 *  - Existing entries are never updated or deleted.
 *  - Corrections use compensating entries (reversal or adjustment).
 *  - A user's effective balance must never go negative.
 *  - Suspended or deleted users must not earn or spend new points.
 *  - Deleted users must not access the balance endpoint.
 *  - Existing balances are not silently removed due to suspension.
 *  - Debits use a PostgreSQL advisory lock inside a database transaction to
 *    prevent concurrent overdraft.
 *  - Idempotency keys prevent duplicate automated awards.
 *  - No client-submitted amounts are trusted for normal user endpoints.
 *  - Credit/debit functions are internal; not exposed as generic public endpoints.
 *
 * Balance architecture:
 *  - Balance is calculated as SUM(amount) from the ledger (MVP approach).
 *  - `balanceAfter` on each entry records the running balance for display and
 *    is set atomically within the same database transaction.
 *  - For debit/spend operations, a session-level PostgreSQL advisory lock per
 *    user is acquired inside the transaction to serialize concurrent debits.
 *    Lock key: pg_advisory_xact_lock(1, hashtext(userId)) — namespace 1 = points.
 *
 * Future preparation:
 *  - TODO: Add Kronjakt award integration once Crown Hunt is designed.
 *  - TODO: Add cosmetic reward spend types once reward catalog is designed.
 *  - TODO: Add badge-related award amounts once product amounts are decided.
 *  - TODO: Add daily/weekly point limits.
 *  - TODO: Add partner campaign award types.
 *  - TODO: Add anti-fraud risk scoring.
 */

import { Prisma, type PrismaClient } from '@prisma/client';

import {
  DEFAULT_POINTS_PAGE_SIZE,
  MAX_POINTS_PAGE_SIZE,
  type PointsTransactionSource,
  type PointsTransactionType,
  type PointsTransactionSummary,
  type PointsAccessDecision,
} from '@carcommunity/shared/points';
import { canAccessAdminFeatures, isSuspendedStatus, type UserRole, type UserStatus } from '@carcommunity/shared/users';

import { AppError } from './errors.js';

// ---------------------------------------------------------------------------
// Interfaces
// ---------------------------------------------------------------------------

export interface PointsActor {
  userId: string;
  role: UserRole;
  status: UserStatus;
}

export interface CreditPointsParams {
  userId: string;
  amount: number;
  transactionType: PointsTransactionType;
  source: PointsTransactionSource;
  description: string;
  idempotencyKey?: string | null;
  relatedEntityType?: string | null;
  relatedEntityId?: string | null;
  createdByUserId?: string | null;
  metadata?: Prisma.InputJsonValue | null;
}

export interface DebitPointsParams {
  userId: string;
  amount: number;
  transactionType: PointsTransactionType;
  source: PointsTransactionSource;
  description: string;
  idempotencyKey?: string | null;
  relatedEntityType?: string | null;
  relatedEntityId?: string | null;
  createdByUserId?: string | null;
  metadata?: Prisma.InputJsonValue | null;
}

export interface AdminAdjustmentParams {
  actor: PointsActor;
  targetUserId: string;
  type: 'adjustment_credit' | 'adjustment_debit';
  /** Positive integer amount. */
  amount: number;
  reason: string;
}

export interface ReverseTransactionParams {
  actor: PointsActor;
  originalTransactionId: string;
  reason: string;
}

export interface ListLedgerParams {
  userId: string;
  page?: number;
  pageSize?: number;
}

export interface ListLedgerResult {
  transactions: PointsTransactionSummary[];
  balance: number;
  total: number;
  hasNext: boolean;
  page: number;
  pageSize: number;
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

type LedgerRow = {
  id: string;
  transactionType: string;
  source: string;
  amount: number;
  balanceAfter: number;
  description: string;
  createdAt: Date;
};

function toTransactionSummary(row: LedgerRow): PointsTransactionSummary {
  return {
    transactionId: row.id,
    transactionType: row.transactionType as PointsTransactionType,
    source: row.source as PointsTransactionSource,
    amount: row.amount,
    balanceAfter: row.balanceAfter,
    description: row.description,
    createdAt: row.createdAt.toISOString(),
  };
}

/**
 * Returns a PointsAccessDecision for the user.
 * Deleted users are always denied; suspended users are denied for earn/spend.
 */
function checkPointsAccess(status: UserStatus): PointsAccessDecision {
  if (status === 'deleted') {
    return { allowed: false, reason: 'deleted' };
  }
  if (isSuspendedStatus(status)) {
    return { allowed: false, reason: 'suspended' };
  }
  return { allowed: true };
}

/**
 * Validates that an amount is a positive integer within the allowed range.
 */
function assertPositiveInteger(amount: number, max = 100_000): void {
  if (!Number.isInteger(amount) || amount <= 0) {
    throw new AppError(400, 'validation_error', 'Amount must be a positive integer.');
  }
  if (amount > max) {
    throw new AppError(
      400,
      'validation_error',
      `Amount must not exceed ${max.toLocaleString()} KP per action.`,
    );
  }
}

// ---------------------------------------------------------------------------
// PointsService
// ---------------------------------------------------------------------------

export class PointsService {
  constructor(private readonly prisma: PrismaClient) {}

  // -------------------------------------------------------------------------
  // Public: get current user's balance
  // -------------------------------------------------------------------------

  /**
   * Returns the current authenticated user's KP balance.
   *
   * Requirements:
   *  - Deleted users must not access the balance endpoint.
   *  - Suspended users may view their balance (account-limited UI).
   *  - Balance is always >= 0.
   *  - Never exposes another user's balance.
   */
  public async getPointsBalance(userId: string): Promise<number> {
    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { status: true },
    });

    if (!user) {
      throw new AppError(404, 'not_found', 'User not found.');
    }

    if ((user.status as UserStatus) === 'deleted') {
      throw new AppError(403, 'forbidden', 'Your account has been deleted.');
    }

    return this.calculateBalance(userId);
  }

  // -------------------------------------------------------------------------
  // Public: list current user's ledger entries
  // -------------------------------------------------------------------------

  /**
   * Returns the current user's paginated ledger, newest first.
   *
   * Requirements:
   *  - Returns only the current user's entries — never other users'.
   *  - Page size is bounded by MAX_POINTS_PAGE_SIZE.
   *  - Returns the authoritative balance alongside entries.
   */
  public async listPointsLedger(params: ListLedgerParams): Promise<ListLedgerResult> {
    const page = Math.max(1, params.page ?? 1);
    const pageSize = Math.min(
      MAX_POINTS_PAGE_SIZE,
      Math.max(1, params.pageSize ?? DEFAULT_POINTS_PAGE_SIZE),
    );
    const skip = (page - 1) * pageSize;

    const [rows, total, aggregateResult] = await this.prisma.$transaction([
      this.prisma.pointsLedgerEntry.findMany({
        where: { userId: params.userId },
        select: {
          id: true,
          transactionType: true,
          source: true,
          amount: true,
          balanceAfter: true,
          description: true,
          createdAt: true,
        },
        orderBy: { createdAt: 'desc' },
        skip,
        take: pageSize,
      }),
      this.prisma.pointsLedgerEntry.count({ where: { userId: params.userId } }),
      this.prisma.pointsLedgerEntry.aggregate({
        where: { userId: params.userId },
        _sum: { amount: true },
      }),
    ]);
    const balance = (aggregateResult as { _sum: { amount: number | null } })._sum.amount ?? 0;

    return {
      transactions: rows.map(toTransactionSummary),
      balance,
      total,
      hasNext: skip + rows.length < total,
      page,
      pageSize,
    };
  }

  // -------------------------------------------------------------------------
  // Internal: credit points
  // -------------------------------------------------------------------------

  /**
   * Credits points to a user's account.
   *
   * Requirements:
   *  - Not publicly callable by arbitrary clients.
   *  - Requires a valid source and description.
   *  - Supports idempotency keys — duplicate keys are ignored.
   *  - Suspended and deleted users must not receive new points.
   *  - Always performs a bounded DB write inside a transaction.
   */
  public async creditPoints(params: CreditPointsParams): Promise<PointsTransactionSummary> {
    assertPositiveInteger(params.amount);

    const user = await this.prisma.user.findUnique({
      where: { id: params.userId },
      select: { status: true },
    });

    if (!user) {
      throw new AppError(404, 'not_found', 'User not found.');
    }

    const access = checkPointsAccess(user.status as UserStatus);
    if (!access.allowed) {
      if (access.reason === 'deleted') {
        throw new AppError(403, 'forbidden', 'Cannot credit points to a deleted user.');
      }
      throw new AppError(403, 'suspended', 'Cannot credit points to a suspended user.');
    }

    // Check idempotency: if key is set and already exists, return existing entry.
    if (params.idempotencyKey) {
      const existing = await this.prisma.pointsLedgerEntry.findUnique({
        where: { idempotencyKey: params.idempotencyKey },
        select: {
          id: true,
          transactionType: true,
          source: true,
          amount: true,
          balanceAfter: true,
          description: true,
          createdAt: true,
        },
      });
      if (existing) {
        return toTransactionSummary(existing);
      }
    }

    const entry = await this.prisma.$transaction(async (tx) => {
      // Calculate current balance inside the transaction.
      const currentBalance = await this.calculateBalanceTx(tx, params.userId);
      const balanceAfter = currentBalance + params.amount;

      return tx.pointsLedgerEntry.create({
        data: {
          userId: params.userId,
          transactionType: params.transactionType,
          source: params.source,
          amount: params.amount,
          balanceAfter,
          description: params.description,
          idempotencyKey: params.idempotencyKey ?? null,
          relatedEntityType: params.relatedEntityType ?? null,
          relatedEntityId: params.relatedEntityId ?? null,
          createdByUserId: params.createdByUserId ?? null,
          metadata: params.metadata ?? Prisma.DbNull,
        },
        select: {
          id: true,
          transactionType: true,
          source: true,
          amount: true,
          balanceAfter: true,
          description: true,
          createdAt: true,
        },
      });
    });

    return toTransactionSummary(entry);
  }

  // -------------------------------------------------------------------------
  // Internal: debit points
  // -------------------------------------------------------------------------

  /**
   * Debits points from a user's account.
   *
   * Requirements:
   *  - Not publicly callable by arbitrary clients.
   *  - Uses a PostgreSQL advisory lock to prevent concurrent overdraft.
   *  - Rejects any debit that would produce a negative balance.
   *  - Suspended and deleted users must not spend points.
   *  - `amount` is the positive integer to debit; stored as negative in the ledger.
   */
  public async debitPoints(params: DebitPointsParams): Promise<PointsTransactionSummary> {
    assertPositiveInteger(params.amount);

    const user = await this.prisma.user.findUnique({
      where: { id: params.userId },
      select: { status: true },
    });

    if (!user) {
      throw new AppError(404, 'not_found', 'User not found.');
    }

    const access = checkPointsAccess(user.status as UserStatus);
    if (!access.allowed) {
      if (access.reason === 'deleted') {
        throw new AppError(403, 'forbidden', 'Cannot debit points from a deleted user.');
      }
      throw new AppError(403, 'suspended', 'Cannot debit points from a suspended user.');
    }

    // Check idempotency: if key is set and already exists, return existing entry.
    if (params.idempotencyKey) {
      const existing = await this.prisma.pointsLedgerEntry.findUnique({
        where: { idempotencyKey: params.idempotencyKey },
        select: {
          id: true,
          transactionType: true,
          source: true,
          amount: true,
          balanceAfter: true,
          description: true,
          createdAt: true,
        },
      });
      if (existing) {
        return toTransactionSummary(existing);
      }
    }

    const entry = await this.prisma.$transaction(async (tx) => {
      // Acquire per-user advisory lock to serialize concurrent debits.
      // Namespace 1 = points operations.
      await tx.$executeRaw`SELECT pg_advisory_xact_lock(1, hashtext(${params.userId}))`;

      const currentBalance = await this.calculateBalanceTx(tx, params.userId);

      if (currentBalance - params.amount < 0) {
        throw new AppError(
          400,
          'validation_error',
          'Insufficient Kronpoäng balance. The debit would produce a negative balance.',
        );
      }

      const balanceAfter = currentBalance - params.amount;

      return tx.pointsLedgerEntry.create({
        data: {
          userId: params.userId,
          transactionType: params.transactionType,
          source: params.source,
          amount: -params.amount, // stored as negative
          balanceAfter,
          description: params.description,
          idempotencyKey: params.idempotencyKey ?? null,
          relatedEntityType: params.relatedEntityType ?? null,
          relatedEntityId: params.relatedEntityId ?? null,
          createdByUserId: params.createdByUserId ?? null,
          metadata: params.metadata ?? Prisma.DbNull,
        },
        select: {
          id: true,
          transactionType: true,
          source: true,
          amount: true,
          balanceAfter: true,
          description: true,
          createdAt: true,
        },
      });
    });

    return toTransactionSummary(entry);
  }

  // -------------------------------------------------------------------------
  // Public: admin adjustment
  // -------------------------------------------------------------------------

  /**
   * Applies an admin points adjustment (credit or debit) to a target user.
   *
   * Requirements:
   *  - Requires admin or owner role (enforced at route layer — also checked here).
   *  - `amount` must be a positive integer.
   *  - A non-empty reason is mandatory and written to the audit log.
   *  - Debit rejects if the balance would go negative.
   *  - Does not allow setting an absolute balance.
   *  - Writes an audit log entry atomically.
   *  - Suspended users' existing balances are adjusted; new earn/spend blocked
   *    only through the normal creditPoints/debitPoints paths.
   */
  public async applyAdminPointsAdjustment(
    params: AdminAdjustmentParams,
  ): Promise<PointsTransactionSummary> {
    const { actor, targetUserId, type, amount, reason } = params;

    if (!canAccessAdminFeatures({ role: actor.role, status: actor.status })) {
      throw new AppError(403, 'forbidden', 'Admin access required.');
    }

    assertPositiveInteger(amount);

    if (!reason || reason.trim().length === 0) {
      throw new AppError(400, 'validation_error', 'Reason is required for admin adjustments.');
    }
    if (reason.trim().length > 500) {
      throw new AppError(400, 'validation_error', 'Reason must not exceed 500 characters.');
    }

    const targetUser = await this.prisma.user.findUnique({
      where: { id: targetUserId },
      select: { status: true, role: true },
    });

    if (!targetUser) {
      throw new AppError(404, 'not_found', 'Target user not found.');
    }

    if ((targetUser.status as UserStatus) === 'deleted') {
      throw new AppError(403, 'forbidden', 'Cannot adjust points for a deleted user.');
    }

    // Protect owner accounts: only an owner actor may adjust another owner's points.
    if (targetUser.role === 'owner' && actor.role !== 'owner') {
      throw new AppError(403, 'forbidden', 'Only an owner may adjust points for another owner.');
    }

    const trimmedReason = reason.trim();
    const isCredit = type === 'adjustment_credit';
    const signedAmount = isCredit ? amount : -amount;
    const description = isCredit
      ? `Administrativ kreditering: ${trimmedReason}`
      : `Administrativ debitering: ${trimmedReason}`;

    const entry = await this.prisma.$transaction(async (tx) => {
      if (!isCredit) {
        // Acquire per-user advisory lock for debit to prevent concurrent overdraft.
        await tx.$executeRaw`SELECT pg_advisory_xact_lock(1, hashtext(${targetUserId}))`;
      }

      const currentBalance = await this.calculateBalanceTx(tx, targetUserId);

      if (!isCredit && currentBalance + signedAmount < 0) {
        throw new AppError(
          400,
          'validation_error',
          'Debit would produce a negative balance. Reduce the amount.',
        );
      }

      const balanceAfter = currentBalance + signedAmount;

      const [created] = await Promise.all([
        tx.pointsLedgerEntry.create({
          data: {
            userId: targetUserId,
            transactionType: type,
            source: 'admin_adjustment',
            amount: signedAmount,
            balanceAfter,
            description,
            createdByUserId: actor.userId,
            metadata: Prisma.DbNull,
          },
          select: {
            id: true,
            transactionType: true,
            source: true,
            amount: true,
            balanceAfter: true,
            description: true,
            createdAt: true,
          },
        }),
        tx.auditLog.create({
          data: {
            actorUserId: actor.userId,
            action: isCredit ? 'points.adjustment_credit' : 'points.adjustment_debit',
            entityType: 'user',
            entityId: targetUserId,
            reason: trimmedReason,
            metadata: {
              targetUserId,
              adjustmentType: type,
              amount,
              balanceAfter: currentBalance + signedAmount,
            },
          },
        }),
      ]);

      return created;
    });

    return toTransactionSummary(entry);
  }

  // -------------------------------------------------------------------------
  // Internal: reverse a prior transaction
  // -------------------------------------------------------------------------

  /**
   * Reverses a prior transaction by creating a compensating ledger entry.
   *
   * Requirements:
   *  - Never edits or deletes the original entry.
   *  - Reversal amount is the negation of the original amount.
   *  - A debit reversal (crediting back) does not need a negative-balance check.
   *  - A credit reversal (debiting back) uses the advisory lock and checks balance.
   *  - Writes an audit log entry.
   */
  public async reversePointsTransaction(
    params: ReverseTransactionParams,
  ): Promise<PointsTransactionSummary> {
    const { actor, originalTransactionId, reason } = params;

    if (!reason || reason.trim().length === 0) {
      throw new AppError(400, 'validation_error', 'Reason is required for reversals.');
    }

    const original = await this.prisma.pointsLedgerEntry.findUnique({
      where: { id: originalTransactionId },
      select: {
        id: true,
        userId: true,
        amount: true,
        transactionType: true,
        source: true,
      },
    });

    if (!original) {
      throw new AppError(404, 'not_found', 'Original transaction not found.');
    }

    // The reversal amount is the negation of the original.
    const reversalAmount = -original.amount;
    const trimmedReason = reason.trim();

    const entry = await this.prisma.$transaction(async (tx) => {
      // If the reversal is a debit (crediting back a debit = positive reversal amount:
      // no overdraft risk). If reversalAmount is negative (reversing a credit), check balance.
      if (reversalAmount < 0) {
        await tx.$executeRaw`SELECT pg_advisory_xact_lock(1, hashtext(${original.userId}))`;

        const currentBalance = await this.calculateBalanceTx(tx, original.userId);
        if (currentBalance + reversalAmount < 0) {
          throw new AppError(
            400,
            'validation_error',
            'Reversal would produce a negative balance.',
          );
        }
      }

      const currentBalance = await this.calculateBalanceTx(tx, original.userId);
      const balanceAfter = currentBalance + reversalAmount;

      const [created] = await Promise.all([
        tx.pointsLedgerEntry.create({
          data: {
            userId: original.userId,
            transactionType: 'reversal',
            source: original.source,
            amount: reversalAmount,
            balanceAfter,
            description: `Återföring av transaktion ${original.id}: ${trimmedReason}`,
            relatedEntityId: original.id,
            relatedEntityType: 'points_ledger_entry',
            createdByUserId: actor.userId,
            metadata: Prisma.DbNull,
          },
          select: {
            id: true,
            transactionType: true,
            source: true,
            amount: true,
            balanceAfter: true,
            description: true,
            createdAt: true,
          },
        }),
        tx.auditLog.create({
          data: {
            actorUserId: actor.userId,
            action: 'points.reversal',
            entityType: 'points_ledger_entry',
            entityId: original.id,
            reason: trimmedReason,
            metadata: {
              originalTransactionId: original.id,
              targetUserId: original.userId,
              reversalAmount,
            },
          },
        }),
      ]);

      return created;
    });

    return toTransactionSummary(entry);
  }

  // -------------------------------------------------------------------------
  // Private: balance calculation helpers
  // -------------------------------------------------------------------------

  /**
   * Calculates the user's current balance as SUM(amount) from the ledger.
   * Returns 0 for a new user with no ledger entries.
   */
  public async calculateBalance(userId: string): Promise<number> {
    const result = await this.prisma.pointsLedgerEntry.aggregate({
      where: { userId },
      _sum: { amount: true },
    });
    return result._sum.amount ?? 0;
  }

  /**
   * Calculates balance inside a Prisma transaction context.
   */
  private async calculateBalanceTx(
    tx: Omit<PrismaClient, '$connect' | '$disconnect' | '$on' | '$transaction' | '$use' | '$extends'>,
    userId: string,
  ): Promise<number> {
    const result = await tx.pointsLedgerEntry.aggregate({
      where: { userId },
      _sum: { amount: true },
    });
    return result._sum.amount ?? 0;
  }
}
