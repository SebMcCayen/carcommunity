-- Migration: add_points_ledger
-- Adds the PointsLedgerEntry table for the Kronpoäng (KP) points system.
--
-- Design notes:
--   - Append-only: no UPDATE or DELETE operations in normal application flows.
--   - amount is a signed integer; positive = credit, negative = debit.
--   - balance_after is set atomically with each insert inside a transaction.
--   - idempotency_key has a unique index (partial: where not null) to prevent
--     duplicate automated awards.
--   - Advisory lock per user is used at the service layer to prevent concurrent
--     overdraft (see PointsService).

CREATE TYPE "PointsTransactionType" AS ENUM (
    'earn',
    'spend',
    'adjustment_credit',
    'adjustment_debit',
    'reversal'
);

CREATE TYPE "PointsTransactionSource" AS ENUM (
    'badge',
    'event',
    'garage',
    'admin_adjustment',
    'system',
    'future_crown_hunt'
);

CREATE TABLE "points_ledger_entries" (
    "id"                   UUID         NOT NULL DEFAULT gen_random_uuid(),
    "user_id"              UUID         NOT NULL,
    "transaction_type"     "PointsTransactionType" NOT NULL,
    "source"               "PointsTransactionSource" NOT NULL,
    "amount"               INTEGER      NOT NULL,
    "balance_after"        INTEGER      NOT NULL,
    "description"          VARCHAR(500) NOT NULL,
    "idempotency_key"      VARCHAR(200),
    "related_entity_type"  VARCHAR(100),
    "related_entity_id"    UUID,
    "created_by_user_id"   UUID,
    "metadata"             JSONB,
    "created_at"           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "points_ledger_entries_pkey" PRIMARY KEY ("id")
);

-- Enforce uniqueness on idempotency_key where it is present.
CREATE UNIQUE INDEX "points_ledger_entries_idempotency_key_key"
    ON "points_ledger_entries"("idempotency_key")
    WHERE "idempotency_key" IS NOT NULL;

-- Lookup by user sorted by newest-first (primary query pattern).
CREATE INDEX "points_ledger_entries_user_id_created_at_idx"
    ON "points_ledger_entries"("user_id", "created_at" DESC);

-- Lookup by user only (for balance SUM and count queries).
CREATE INDEX "points_ledger_entries_user_id_idx"
    ON "points_ledger_entries"("user_id");

-- Lookup by source (admin / audit queries).
CREATE INDEX "points_ledger_entries_source_idx"
    ON "points_ledger_entries"("source");

-- Foreign key: user
ALTER TABLE "points_ledger_entries"
    ADD CONSTRAINT "points_ledger_entries_user_id_fkey"
    FOREIGN KEY ("user_id")
    REFERENCES "users"("id")
    ON DELETE CASCADE ON UPDATE CASCADE;

-- Foreign key: created_by_user (optional, nullable)
ALTER TABLE "points_ledger_entries"
    ADD CONSTRAINT "points_ledger_entries_created_by_user_id_fkey"
    FOREIGN KEY ("created_by_user_id")
    REFERENCES "users"("id")
    ON DELETE SET NULL ON UPDATE CASCADE;
