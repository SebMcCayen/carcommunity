-- CreateEnum
CREATE TYPE "LiveLocationSessionStatus" AS ENUM ('active', 'stopped', 'expired');

-- CreateTable
CREATE TABLE "live_location_sessions" (
    "id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "status" "LiveLocationSessionStatus" NOT NULL DEFAULT 'active',
    "started_at" TIMESTAMP(3) NOT NULL,
    "expires_at" TIMESTAMP(3) NOT NULL,
    "stopped_at" TIMESTAMP(3),
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "live_location_sessions_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "live_location_latest_positions" (
    "id" UUID NOT NULL,
    "session_id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "latitude" DOUBLE PRECISION NOT NULL,
    "longitude" DOUBLE PRECISION NOT NULL,
    "accuracy_meters" DOUBLE PRECISION,
    "heading_degrees" DOUBLE PRECISION,
    "speed_meters_per_second" DOUBLE PRECISION,
    "recorded_at" TIMESTAMP(3) NOT NULL,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "live_location_latest_positions_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "live_location_sessions_user_id_status_idx" ON "live_location_sessions"("user_id", "status");

-- CreateIndex
CREATE INDEX "live_location_sessions_status_expires_at_idx" ON "live_location_sessions"("status", "expires_at");

-- CreateIndex
CREATE INDEX "live_location_sessions_expires_at_idx" ON "live_location_sessions"("expires_at");

-- CreateIndex
CREATE UNIQUE INDEX "live_location_latest_positions_session_id_key" ON "live_location_latest_positions"("session_id");

-- CreateIndex
CREATE INDEX "live_location_latest_positions_user_id_idx" ON "live_location_latest_positions"("user_id");

-- CreateIndex
CREATE INDEX "live_location_latest_positions_recorded_at_idx" ON "live_location_latest_positions"("recorded_at");

-- AddForeignKey
ALTER TABLE "live_location_sessions" ADD CONSTRAINT "live_location_sessions_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "live_location_latest_positions" ADD CONSTRAINT "live_location_latest_positions_session_id_fkey" FOREIGN KEY ("session_id") REFERENCES "live_location_sessions"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "live_location_latest_positions" ADD CONSTRAINT "live_location_latest_positions_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;
