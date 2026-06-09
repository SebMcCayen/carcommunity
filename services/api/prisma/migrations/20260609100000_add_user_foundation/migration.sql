-- CreateEnum
CREATE TYPE "UserRole" AS ENUM ('user', 'admin', 'owner');

-- CreateEnum
CREATE TYPE "UserStatus" AS ENUM ('active', 'warned', 'temporarily_suspended', 'permanently_suspended', 'deleted');

-- CreateEnum
CREATE TYPE "SubscriptionEntitlement" AS ENUM ('none', 'member_monthly');

-- CreateEnum
CREATE TYPE "ModerationActionType" AS ENUM ('warning', 'temporary_suspension', 'permanent_suspension', 'restriction', 'restore_access');

-- AlterTable
ALTER TABLE "users" ADD COLUMN "email" VARCHAR(320),
ADD COLUMN "role" "UserRole" NOT NULL DEFAULT 'user',
ADD COLUMN "status" "UserStatus" NOT NULL DEFAULT 'active',
ADD COLUMN "subscription_entitlement" "SubscriptionEntitlement" NOT NULL DEFAULT 'none',
ADD COLUMN "last_active_at" TIMESTAMP(3);

-- CreateIndex
CREATE INDEX "users_status_idx" ON "users"("status");

-- CreateIndex
CREATE INDEX "users_subscription_entitlement_idx" ON "users"("subscription_entitlement");

-- CreateTable
CREATE TABLE "moderation_actions" (
    "id" UUID NOT NULL,
    "target_user_id" UUID NOT NULL,
    "actor_user_id" UUID,
    "action_type" "ModerationActionType" NOT NULL,
    "reason" TEXT NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "expires_at" TIMESTAMP(3),
    "metadata" JSONB,

    CONSTRAINT "moderation_actions_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "moderation_actions_target_user_id_idx" ON "moderation_actions"("target_user_id");

-- CreateIndex
CREATE INDEX "moderation_actions_actor_user_id_idx" ON "moderation_actions"("actor_user_id");

-- CreateIndex
CREATE INDEX "moderation_actions_action_type_idx" ON "moderation_actions"("action_type");

-- CreateIndex
CREATE INDEX "moderation_actions_created_at_idx" ON "moderation_actions"("created_at");

-- AddForeignKey
ALTER TABLE "moderation_actions" ADD CONSTRAINT "moderation_actions_target_user_id_fkey" FOREIGN KEY ("target_user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "moderation_actions" ADD CONSTRAINT "moderation_actions_actor_user_id_fkey" FOREIGN KEY ("actor_user_id") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- CreateTable
CREATE TABLE "audit_logs" (
    "id" UUID NOT NULL,
    "actor_user_id" UUID,
    "action" VARCHAR(120) NOT NULL,
    "entity_type" VARCHAR(120) NOT NULL,
    "entity_id" UUID,
    "reason" TEXT,
    "metadata" JSONB,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "audit_logs_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "audit_logs_actor_user_id_idx" ON "audit_logs"("actor_user_id");

-- CreateIndex
CREATE INDEX "audit_logs_entity_type_entity_id_idx" ON "audit_logs"("entity_type", "entity_id");

-- CreateIndex
CREATE INDEX "audit_logs_action_idx" ON "audit_logs"("action");

-- CreateIndex
CREATE INDEX "audit_logs_created_at_idx" ON "audit_logs"("created_at");

-- AddForeignKey
ALTER TABLE "audit_logs" ADD CONSTRAINT "audit_logs_actor_user_id_fkey" FOREIGN KEY ("actor_user_id") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;
