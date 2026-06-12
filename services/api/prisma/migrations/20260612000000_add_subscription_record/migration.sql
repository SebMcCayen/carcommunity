-- CreateEnum
CREATE TYPE "SubscriptionPlatform" AS ENUM ('apple', 'google', 'manual');

-- CreateEnum
CREATE TYPE "SubscriptionRecordStatus" AS ENUM ('inactive', 'active', 'grace_period', 'expired', 'revoked', 'cancelled');

-- CreateTable
CREATE TABLE "subscription_records" (
    "id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "platform" "SubscriptionPlatform" NOT NULL,
    "status" "SubscriptionRecordStatus" NOT NULL DEFAULT 'inactive',
    "entitlement" "SubscriptionEntitlement" NOT NULL DEFAULT 'none',
    "external_product_id" VARCHAR(255),
    "external_original_transaction_id" VARCHAR(255),
    "external_purchase_token_hash" VARCHAR(128),
    "starts_at" TIMESTAMP(3),
    "expires_at" TIMESTAMP(3),
    "revoked_at" TIMESTAMP(3),
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,
    "metadata" JSONB,

    CONSTRAINT "subscription_records_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "subscription_records_user_id_idx" ON "subscription_records"("user_id");

-- CreateIndex
CREATE INDEX "subscription_records_user_id_status_idx" ON "subscription_records"("user_id", "status");

-- CreateIndex
CREATE INDEX "subscription_records_status_idx" ON "subscription_records"("status");

-- CreateIndex
CREATE INDEX "subscription_records_entitlement_idx" ON "subscription_records"("entitlement");

-- CreateIndex
CREATE INDEX "subscription_records_expires_at_idx" ON "subscription_records"("expires_at");

-- AddForeignKey
ALTER TABLE "subscription_records" ADD CONSTRAINT "subscription_records_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;
