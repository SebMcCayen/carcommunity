-- AlterTable
ALTER TABLE "sessions"
ADD COLUMN "token_hash" VARCHAR(128),
ADD COLUMN "revoked_at" TIMESTAMP(3),
ADD COLUMN "last_used_at" TIMESTAMP(3);

-- Backfill temporary unique placeholders for existing rows before NOT NULL/UNIQUE.
UPDATE "sessions"
SET "token_hash" = md5("id"::text)
WHERE "token_hash" IS NULL;

-- AlterTable
ALTER TABLE "sessions"
ALTER COLUMN "token_hash" SET NOT NULL;

-- CreateIndex
CREATE UNIQUE INDEX "sessions_token_hash_key" ON "sessions"("token_hash");

-- CreateIndex
CREATE INDEX "sessions_revoked_at_idx" ON "sessions"("revoked_at");
