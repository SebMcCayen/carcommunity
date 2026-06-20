import type { PrismaClient } from '@prisma/client';
import type { BlockedUserSummary } from '@carcommunity/shared/blocking';

import { AppError } from './errors.js';

export interface BlockUserInput {
  blockerUserId: string;
  targetUserId: string;
}

export interface UnblockUserInput {
  blockerUserId: string;
  targetUserId: string;
}

export interface ListBlockedUsersInput {
  blockerUserId: string;
  page: number;
  pageSize: number;
}

export interface ListBlockedUsersResult {
  blockedUsers: BlockedUserSummary[];
  total: number;
  hasNext: boolean;
}

export interface BlockUserResult {
  block: BlockedUserSummary;
}

export interface UnblockUserResult {
  unblocked: boolean;
}

/**
 * Service responsible for all user blocking operations.
 *
 * Backend is the source of truth — all blocking rules are enforced here,
 * not on the client.
 *
 * Privacy rules:
 * - Never reveal that a user has been blocked by someone else.
 * - Never expose sensitive user data in block summaries.
 * - Admin access does not silently bypass blocking for surveillance.
 *
 * TODO: Enforce the same blocking relationship for:
 *   - event chat
 *   - group driving interactions
 *   - mentions
 *   - private interactions if ever introduced
 *   - partner/community interaction features where relevant
 */
export class BlockingService {
  constructor(private readonly prisma: PrismaClient) {}

  /**
   * Creates a block from `blockerUserId` against `targetUserId`.
   * Idempotent: if the block already exists, returns the existing record.
   * Throws if the target user does not exist or if self-blocking is attempted.
   */
  public async blockUser(input: BlockUserInput): Promise<BlockUserResult> {
    if (input.blockerUserId === input.targetUserId) {
      throw new AppError(400, 'self_block', 'You cannot block yourself.');
    }

    const target = await this.prisma.user.findUnique({
      where: { id: input.targetUserId },
      select: { id: true, displayName: true, deletedAt: true },
    });

    if (!target || target.deletedAt !== null) {
      throw new AppError(404, 'not_found', 'User not found.');
    }

    const block = await this.prisma.userBlock.upsert({
      where: {
        blockerUserId_blockedUserId: {
          blockerUserId: input.blockerUserId,
          blockedUserId: input.targetUserId,
        },
      },
      create: {
        blockerUserId: input.blockerUserId,
        blockedUserId: input.targetUserId,
      },
      update: {},
      select: {
        blockedUserId: true,
        createdAt: true,
        blocked: {
          select: { displayName: true },
        },
      },
    });

    return {
      block: {
        userId: block.blockedUserId,
        displayName: block.blocked.displayName,
        blockedAt: block.createdAt.toISOString(),
      },
    };
  }

  /**
   * Removes the block from `blockerUserId` against `targetUserId`.
   * Idempotent: if no block exists, returns `{ unblocked: false }` rather than throwing.
   */
  public async unblockUser(input: UnblockUserInput): Promise<UnblockUserResult> {
    if (input.blockerUserId === input.targetUserId) {
      return { unblocked: false };
    }

    const deleted = await this.prisma.userBlock.deleteMany({
      where: {
        blockerUserId: input.blockerUserId,
        blockedUserId: input.targetUserId,
      },
    });

    return { unblocked: deleted.count > 0 };
  }

  /**
   * Returns a paginated list of users blocked by `blockerUserId`.
   * Does NOT include users who have blocked the caller — that would reveal
   * private blocking decisions and is intentionally excluded.
   */
  public async listBlockedUsers(input: ListBlockedUsersInput): Promise<ListBlockedUsersResult> {
    const skip = (input.page - 1) * input.pageSize;

    const [total, blocks] = await this.prisma.$transaction([
      this.prisma.userBlock.count({
        where: { blockerUserId: input.blockerUserId },
      }),
      this.prisma.userBlock.findMany({
        where: { blockerUserId: input.blockerUserId },
        orderBy: { createdAt: 'desc' },
        skip,
        take: input.pageSize,
        select: {
          blockedUserId: true,
          createdAt: true,
          blocked: {
            select: { displayName: true },
          },
        },
      }),
    ]);

    const blockedUsers: BlockedUserSummary[] = blocks.map((b) => ({
      userId: b.blockedUserId,
      displayName: b.blocked.displayName,
      blockedAt: b.createdAt.toISOString(),
    }));

    return {
      blockedUsers,
      total,
      hasNext: skip + blocks.length < total,
    };
  }

  /**
   * Returns all user IDs that are invisible to `viewerId`:
   * - Users that `viewerId` has blocked
   * - Users that have blocked `viewerId`
   *
   * Intended for efficient visibility filtering in live location and other
   * shared-space queries. Uses two separate indexed queries to avoid N+1.
   *
   * Privacy: the combined result is used for filtering only; the caller must
   * not expose which direction the block came from.
   */
  public async getInvisibleUserIds(viewerId: string): Promise<string[]> {
    const [blockedByViewer, blockedViewer] = await this.prisma.$transaction([
      this.prisma.userBlock.findMany({
        where: { blockerUserId: viewerId },
        select: { blockedUserId: true },
      }),
      this.prisma.userBlock.findMany({
        where: { blockedUserId: viewerId },
        select: { blockerUserId: true },
      }),
    ]);

    const ids = new Set<string>();
    for (const b of blockedByViewer) ids.add(b.blockedUserId);
    for (const b of blockedViewer) ids.add(b.blockerUserId);
    return Array.from(ids);
  }
}
