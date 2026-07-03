/**
 * Kronpoäng (KP) points ledger — pure logic (Phase 9g).
 *
 * Ports packages/shared/src/points.ts and the pure parts of the legacy
 * points-service.ts to the Firestore model
 * (docs/migration/backend-domain-mapping.md "Points ledger → Firestore
 * transactions"):
 *
 * - `pointsLedger/{uid}` holds the denormalized `balance`;
 *   `pointsLedger/{uid}/entries/{entryId}` is the append-only ledger.
 * - Backend is the sole authority: clients never calculate or write
 *   balances; every change is a Firestore transaction (read balance → append
 *   entry → update balance) so concurrent writes cannot race.
 * - Entries are never updated or deleted; corrections use compensating
 *   entries (adjustment or reversal).
 * - A balance can never go negative; suspended/deleted users earn and spend
 *   nothing (existing balances are not removed).
 * - Idempotency keys make automated awards replay-safe: the key IS the
 *   entry document ID, so a duplicate award is a transactional no-op.
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { z } from 'zod';

export const POINTS_TRANSACTION_TYPES = [
  'earn',
  'spend',
  'adjustment_credit',
  'adjustment_debit',
  'reversal',
] as const;
export type PointsTransactionType = (typeof POINTS_TRANSACTION_TYPES)[number];

/**
 * `future_crown_hunt` is retained for backward-compatibility with legacy
 * ledger data and must not be used for new entries; `crown_hunt` is the live
 * Kronjakt source.
 */
export const POINTS_TRANSACTION_SOURCES = [
  'badge',
  'event',
  'garage',
  'admin_adjustment',
  'system',
  'crown_hunt',
  'future_crown_hunt',
] as const;
export type PointsTransactionSource = (typeof POINTS_TRANSACTION_SOURCES)[number];

export const ACTIVE_POINTS_TRANSACTION_SOURCES: readonly PointsTransactionSource[] = [
  'badge',
  'event',
  'garage',
  'admin_adjustment',
  'system',
  'crown_hunt',
];

export const POINTS_REASON_MAX_LENGTH = 500;
export const POINTS_DESCRIPTION_MAX_LENGTH = 500;

// ---------------------------------------------------------------------------
// Inputs (admin callables)
// ---------------------------------------------------------------------------

// Firestore-safe document/entry ID (same rationale as drives/garage).
const entryIdSchema = z
  .string()
  .trim()
  .min(1)
  .max(300)
  .regex(/^[A-Za-z0-9._-]+$/)
  .refine((id) => id !== '.' && id !== '..');

const adminAdjustInputSchema = z
  .object({
    targetUid: z.string().trim().min(1).max(128),
    type: z.enum(['adjustment_credit', 'adjustment_debit']),
    amount: z.number().int().positive(),
    reason: z.string().trim().min(1).max(POINTS_REASON_MAX_LENGTH),
  })
  .strict();

const adminReverseInputSchema = z
  .object({
    targetUid: z.string().trim().min(1).max(128),
    entryId: entryIdSchema,
    reason: z.string().trim().min(1).max(POINTS_REASON_MAX_LENGTH),
  })
  .strict();

export type AdminAdjustInput = z.infer<typeof adminAdjustInputSchema>;
export type AdminReverseInput = z.infer<typeof adminReverseInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export function parseAdminAdjustInput(data: unknown): ParseResult<AdminAdjustInput> {
  return parse(
    adminAdjustInputSchema,
    data,
    'Expected { targetUid, type: adjustment_credit|adjustment_debit, amount: positive integer, reason }.',
  );
}

export function parseAdminReverseInput(data: unknown): ParseResult<AdminReverseInput> {
  return parse(adminReverseInputSchema, data, 'Expected { targetUid, entryId, reason }.');
}

// ---------------------------------------------------------------------------
// Balance math
// ---------------------------------------------------------------------------

/** Reads a stored balance defensively (same guard as badgeProgress). */
export function toStoredBalance(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

export type BalanceCheck =
  | { ok: true; balanceAfter: number }
  | { ok: false; message: string };

/** A signed delta may never take the balance below zero. */
export function applyDelta(currentBalance: number, signedAmount: number): BalanceCheck {
  const balanceAfter = currentBalance + signedAmount;
  if (balanceAfter < 0) {
    return {
      ok: false,
      message: 'Debit would produce a negative balance. Reduce the amount.',
    };
  }
  return { ok: true, balanceAfter };
}

// ---------------------------------------------------------------------------
// Entry builder
// ---------------------------------------------------------------------------

export interface LedgerEntryInput {
  transactionType: PointsTransactionType;
  source: PointsTransactionSource;
  /** Signed amount: positive = credit, negative = debit. */
  amount: number;
  balanceAfter: number;
  description: string;
  idempotencyKey?: string | null;
  relatedEntityType?: string | null;
  relatedEntityId?: string | null;
  createdByUserId?: string | null;
}

/** pointsLedger/{uid}/entries/{entryId} document — append-only. */
export function buildLedgerEntry(
  input: LedgerEntryInput,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    transactionType: input.transactionType,
    source: input.source,
    amount: input.amount,
    balanceAfter: input.balanceAfter,
    description: input.description.slice(0, POINTS_DESCRIPTION_MAX_LENGTH),
    idempotencyKey: input.idempotencyKey ?? null,
    relatedEntityType: input.relatedEntityType ?? null,
    relatedEntityId: input.relatedEntityId ?? null,
    createdByUserId: input.createdByUserId ?? null,
    createdAt: serverTimestamp(),
  };
}

/**
 * Deterministic reversal entry ID: one reversal per original entry, so a
 * repeated reversal attempt is a transactional no-op instead of a double
 * compensation.
 */
export function reversalEntryId(originalEntryId: string): string {
  return `reversal_${originalEntryId}`;
}

/** Swedish description matching the legacy reversal wording. */
export function reversalDescription(originalEntryId: string, reason: string): string {
  return `Återföring av transaktion ${originalEntryId}: ${reason}`;
}
