-- CreateEnum
CREATE TYPE "NotificationCategory" AS ENUM ('event_reminder', 'event_updated', 'event_cancelled', 'admin_message', 'account_warning', 'account_suspension', 'subscription_status', 'system_notice');

-- CreateEnum
CREATE TYPE "NotificationChannel" AS ENUM ('in_app', 'push');

-- CreateEnum
CREATE TYPE "NotificationDeliveryStatus" AS ENUM ('pending', 'sent', 'delivered', 'failed', 'skipped');

-- CreateEnum
CREATE TYPE "NotificationDevicePlatform" AS ENUM ('ios', 'android');

-- CreateTable
CREATE TABLE "push_device_registrations" (
    "id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "platform" "NotificationDevicePlatform" NOT NULL,
    "push_token_hash" VARCHAR(128) NOT NULL,
    "encrypted_push_token" TEXT NOT NULL,
    "app_version" VARCHAR(50),
    "build_number" VARCHAR(50),
    "is_active" BOOLEAN NOT NULL DEFAULT true,
    "last_seen_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "revoked_at" TIMESTAMP(3),
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "push_device_registrations_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "notification_preferences" (
    "id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "category" "NotificationCategory" NOT NULL,
    "push_enabled" BOOLEAN NOT NULL DEFAULT false,
    "in_app_enabled" BOOLEAN NOT NULL DEFAULT true,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "notification_preferences_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "user_notifications" (
    "id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "category" "NotificationCategory" NOT NULL,
    "title" VARCHAR(100) NOT NULL,
    "preview_text" VARCHAR(200) NOT NULL,
    "body" VARCHAR(1000),
    "action_type" VARCHAR(50),
    "related_entity_type" VARCHAR(50),
    "related_entity_id" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "read_at" TIMESTAMP(3),
    "expires_at" TIMESTAMP(3),
    "batch_id" UUID,

    CONSTRAINT "user_notifications_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "notification_delivery_attempts" (
    "id" UUID NOT NULL,
    "user_notification_id" UUID NOT NULL,
    "device_registration_id" UUID,
    "channel" "NotificationChannel" NOT NULL,
    "status" "NotificationDeliveryStatus" NOT NULL DEFAULT 'pending',
    "provider_message_id" VARCHAR(255),
    "attempted_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "completed_at" TIMESTAMP(3),
    "safe_error_code" VARCHAR(100),

    CONSTRAINT "notification_delivery_attempts_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "admin_notification_batches" (
    "id" UUID NOT NULL,
    "category" "NotificationCategory" NOT NULL,
    "audience" VARCHAR(50) NOT NULL,
    "title" VARCHAR(100) NOT NULL,
    "preview_text" VARCHAR(200) NOT NULL,
    "body" VARCHAR(1000) NOT NULL,
    "action_type" VARCHAR(50),
    "related_entity_id" UUID,
    "recipient_count" INTEGER NOT NULL DEFAULT 0,
    "reason" TEXT NOT NULL,
    "idempotency_key" VARCHAR(255) NOT NULL,
    "created_by_user_id" UUID NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "admin_notification_batches_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "push_device_registrations_push_token_hash_key" ON "push_device_registrations"("push_token_hash");

-- CreateIndex
CREATE INDEX "push_device_registrations_user_id_idx" ON "push_device_registrations"("user_id");

-- CreateIndex
CREATE INDEX "push_device_registrations_platform_idx" ON "push_device_registrations"("platform");

-- CreateIndex
CREATE INDEX "push_device_registrations_is_active_idx" ON "push_device_registrations"("is_active");

-- CreateIndex
CREATE INDEX "push_device_registrations_user_id_is_active_idx" ON "push_device_registrations"("user_id", "is_active");

-- CreateIndex
CREATE UNIQUE INDEX "notification_preferences_user_id_category_key" ON "notification_preferences"("user_id", "category");

-- CreateIndex
CREATE INDEX "notification_preferences_user_id_idx" ON "notification_preferences"("user_id");

-- CreateIndex
CREATE INDEX "user_notifications_user_id_created_at_idx" ON "user_notifications"("user_id", "created_at");

-- CreateIndex
CREATE INDEX "user_notifications_user_id_read_at_idx" ON "user_notifications"("user_id", "read_at");

-- CreateIndex
CREATE INDEX "user_notifications_category_idx" ON "user_notifications"("category");

-- CreateIndex
CREATE INDEX "user_notifications_expires_at_idx" ON "user_notifications"("expires_at");

-- CreateIndex
CREATE INDEX "user_notifications_batch_id_idx" ON "user_notifications"("batch_id");

-- CreateIndex
CREATE INDEX "notification_delivery_attempts_user_notification_id_idx" ON "notification_delivery_attempts"("user_notification_id");

-- CreateIndex
CREATE INDEX "notification_delivery_attempts_device_registration_id_idx" ON "notification_delivery_attempts"("device_registration_id");

-- CreateIndex
CREATE INDEX "notification_delivery_attempts_status_idx" ON "notification_delivery_attempts"("status");

-- CreateIndex
CREATE UNIQUE INDEX "admin_notification_batches_idempotency_key_key" ON "admin_notification_batches"("idempotency_key");

-- CreateIndex
CREATE INDEX "admin_notification_batches_created_by_user_id_idx" ON "admin_notification_batches"("created_by_user_id");

-- CreateIndex
CREATE INDEX "admin_notification_batches_created_at_idx" ON "admin_notification_batches"("created_at");

-- AddForeignKey
ALTER TABLE "push_device_registrations" ADD CONSTRAINT "push_device_registrations_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "notification_preferences" ADD CONSTRAINT "notification_preferences_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "user_notifications" ADD CONSTRAINT "user_notifications_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "notification_delivery_attempts" ADD CONSTRAINT "notification_delivery_attempts_user_notification_id_fkey" FOREIGN KEY ("user_notification_id") REFERENCES "user_notifications"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "notification_delivery_attempts" ADD CONSTRAINT "notification_delivery_attempts_device_registration_id_fkey" FOREIGN KEY ("device_registration_id") REFERENCES "push_device_registrations"("id") ON DELETE SET NULL ON UPDATE CASCADE;
