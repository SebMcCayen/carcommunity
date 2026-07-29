/**
 * Shared contracts for the Kronpoäng (KP) points ledger.
 *
 * Design rules encoded here:
 *  - Kronpoäng have no cash value.
 *  - Kronpoäng are not transferable between users.
 *  - Kronpoäng cannot be purchased, sold, exchanged, or withdrawn.
 *  - Kronpoäng are private to the user — no public leaderboards.
 *  - Backend is the sole authority for balances and transactions.
 *  - Clients must never calculate or overwrite balances.
 *  - Every balance change must produce an append-only ledger entry.
 *  - Existing ledger entries must not be edited or deleted.
 *  - A user's effective balance must never become negative.
 *  - Suspended or deleted users must not earn or spend new points.
 *
 * Excluded from these contracts:
 *  - Internal database metadata (row IDs beyond the opaque transaction ID)
 *  - Authentication or session data
 *  - Provider identity information
 *  - Raw audit record internals
 *  - Exact location data
 *  - Other users' balances
 *  - Real-money value, purchase controls, or withdrawal fields
 *
 * Future preparation:
 *  - TODO: Add Kronjakt award source and claim types once Crown Hunt is designed.
 *  - TODO: Add cosmetic reward spend types once reward catalog is designed.
 *  - TODO: Add badge-related award amounts once product amounts are decided.
 *  - TODO: Add partner campaign types once partner issuance rules are designed.
 *  - TODO: Add anti-fraud risk scoring fields if needed.
 *  - TODO: Add daily/weekly point limit enforcement.
 */

// ---------------------------------------------------------------------------
// Transaction types
// ---------------------------------------------------------------------------

const POINTS_TRANSACTION_TYPES = [
  'earn',
  'spend',
  'adjustment_credit',
  'adjustment_debit',
  'reversal',
] as const;
type PointsTransactionType = (typeof POINTS_TRANSACTION_TYPES)[number];

// ---------------------------------------------------------------------------
// Transaction sources
// ---------------------------------------------------------------------------

/**
 * Sources that may produce a ledger entry.
 *
 * `future_crown_hunt` is retained for backward-compatibility with existing ledger entries
 * and must not be used for new entries. Use `crown_hunt` for all Kronjakt awards.
 */
const POINTS_TRANSACTION_SOURCES = [
  'badge',
  'event',
  'garage',
  'admin_adjustment',
  'system',
  'crown_hunt',
  'future_crown_hunt',
] as const;
type PointsTransactionSource = (typeof POINTS_TRANSACTION_SOURCES)[number];

// ---------------------------------------------------------------------------
// Wallet: balance response
// ---------------------------------------------------------------------------

/**
 * Current KP balance for the authenticated user.
 *
 * Excluded: other users' balances, subscription data, real-money values.
 */
export interface PointsBalanceResponse {
  ok: true;
  data: {
    /** Current integer KP balance. Always >= 0. */
    balance: number;
    /** Swedish display string: "Kronpoäng". */
    displayName: string;
    /** Swedish short form: "KP". */
    shortForm: string;
  };
}

// ---------------------------------------------------------------------------
// Wallet: transaction summary (single ledger entry returned to the user)
// ---------------------------------------------------------------------------

/**
 * A single ledger entry as returned to the authenticated user.
 *
 * Excluded: internal database metadata, other users' data, exact location,
 *   raw audit information, session data, provider identity.
 */
export interface PointsTransactionSummary {
  /** Opaque transaction identifier. Never expose internal DB primary keys. */
  transactionId: string;
  transactionType: PointsTransactionType;
  source: PointsTransactionSource;
  /**
   * Signed integer amount.
   * Positive values represent a credit; negative values a debit.
   */
  amount: number;
  /** Integer balance after this transaction was applied. */
  balanceAfter: number;
  /** Swedish human-readable description of the transaction. */
  description: string;
  /** ISO 8601 timestamp when the entry was created. */
  createdAt: string;
}

// ---------------------------------------------------------------------------
// Wallet: paginated ledger response
// ---------------------------------------------------------------------------

/**
 * Paginated list of the current user's ledger entries, newest first.
 *
 * The `balance` field in `data` is the authoritative backend balance and must
 * be used by clients as-is. Clients must never sum the paginated entries to
 * derive a balance — the list is paginated and would produce incorrect results.
 */
export interface PaginatedPointsLedgerResponse {
  ok: true;
  data: {
    /** Authoritative current balance from the backend. Use this — do not sum entries. */
    balance: number;
    transactions: PointsTransactionSummary[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

// ---------------------------------------------------------------------------
// Admin: adjustment request and response
// ---------------------------------------------------------------------------

/**
 * Request body for an admin points adjustment.
 *
 * `amount` must always be a positive integer. The direction is determined by
 * `type`: `adjustment_credit` adds points; `adjustment_debit` subtracts.
 *
 * Excluded: absolute balance overrides — admins may not set a balance directly.
 */
export interface AdminPointsAdjustmentRequest {
  /** Must be `adjustment_credit` or `adjustment_debit`. */
  type: 'adjustment_credit' | 'adjustment_debit';
  /** Positive integer amount. Backend rejects zero, negative, or non-integer values. */
  amount: number;
  /**
   * Mandatory human-readable reason for the adjustment.
   * Written to the audit log. Min 1 char, max 500 chars.
   */
  reason: string;
}

/**
 * Response from a successful admin points adjustment.
 */
export interface AdminPointsAdjustmentResponse {
  ok: true;
  data: {
    /** Opaque transaction ID for the new ledger entry. */
    transactionId: string;
    /** Transaction type: always `adjustment_credit` or `adjustment_debit`. */
    transactionType: PointsTransactionType;
    /** Signed amount applied (positive for credit, negative for debit). */
    amount: number;
    /** Authoritative balance after the adjustment. */
    balanceAfter: number;
    /** ISO 8601 timestamp. */
    createdAt: string;
  };
}

