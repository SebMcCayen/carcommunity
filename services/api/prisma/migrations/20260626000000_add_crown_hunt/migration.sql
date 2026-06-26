-- Kronjakt (Crown Hunt) migration
-- Adds: crown_hunt value to PointsTransactionSource enum
--       CrownHuntPointStatus enum
--       CrownHuntRepeatRule enum
--       CrownHuntClaimResult enum
--       crown_hunt_points table
--       crown_hunt_claims table

-- Add new value to existing PointsTransactionSource enum
ALTER TYPE "PointsTransactionSource" ADD VALUE IF NOT EXISTS 'crown_hunt';

-- CrownHuntPointStatus enum
DO $$ BEGIN
  CREATE TYPE "CrownHuntPointStatus" AS ENUM ('draft', 'active', 'paused', 'ended');
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;

-- CrownHuntRepeatRule enum
DO $$ BEGIN
  CREATE TYPE "CrownHuntRepeatRule" AS ENUM ('once', 'daily', 'weekly');
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;

-- CrownHuntClaimResult enum
DO $$ BEGIN
  CREATE TYPE "CrownHuntClaimResult" AS ENUM (
    'awarded',
    'already_claimed',
    'outside_geofence',
    'moving_too_fast',
    'position_too_old',
    'point_inactive',
    'cooldown_active',
    'daily_limit_reached',
    'risk_review',
    'feature_disabled',
    'not_eligible'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;

-- crown_hunt_points table
CREATE TABLE "crown_hunt_points" (
  "id"                    UUID        NOT NULL DEFAULT gen_random_uuid(),
  "title"                 VARCHAR(100) NOT NULL,
  "description"           VARCHAR(500),
  "latitude"              DOUBLE PRECISION NOT NULL,
  "longitude"             DOUBLE PRECISION NOT NULL,
  "geofence_radius_meters" INTEGER    NOT NULL,
  "reward_points"         INTEGER     NOT NULL,
  "status"                "CrownHuntPointStatus" NOT NULL DEFAULT 'draft',
  "repeat_rule"           "CrownHuntRepeatRule"  NOT NULL DEFAULT 'once',
  "available_from"        TIMESTAMPTZ,
  "available_until"       TIMESTAMPTZ,
  "approved_at"           TIMESTAMPTZ,
  "approved_by_user_id"   UUID,
  "created_by_user_id"    UUID        NOT NULL,
  "created_at"            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  "updated_at"            TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  CONSTRAINT "crown_hunt_points_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "crown_hunt_points_approved_by_user_id_fkey"
    FOREIGN KEY ("approved_by_user_id") REFERENCES "users"("id") ON DELETE SET NULL,
  CONSTRAINT "crown_hunt_points_created_by_user_id_fkey"
    FOREIGN KEY ("created_by_user_id") REFERENCES "users"("id") ON DELETE RESTRICT
);

CREATE INDEX "crown_hunt_points_status_idx"          ON "crown_hunt_points"("status");
CREATE INDEX "crown_hunt_points_available_from_idx"  ON "crown_hunt_points"("available_from");
CREATE INDEX "crown_hunt_points_available_until_idx" ON "crown_hunt_points"("available_until");
CREATE INDEX "crown_hunt_points_lat_lon_idx"         ON "crown_hunt_points"("latitude", "longitude");

-- crown_hunt_claims table
CREATE TABLE "crown_hunt_claims" (
  "id"                               UUID        NOT NULL DEFAULT gen_random_uuid(),
  "point_id"                         UUID        NOT NULL,
  "user_id"                          UUID        NOT NULL,
  "points_ledger_entry_id"           UUID        UNIQUE,
  "result"                           "CrownHuntClaimResult" NOT NULL,
  "claimed_at"                       TIMESTAMPTZ NOT NULL,
  "distance_meters"                  DOUBLE PRECISION,
  "reported_speed_meters_per_second" DOUBLE PRECISION,
  "position_recorded_at"             TIMESTAMPTZ,
  "risk_score"                       DOUBLE PRECISION,
  "risk_reasons"                     JSONB,
  "idempotency_key"                  VARCHAR(200) NOT NULL,
  "created_at"                       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  CONSTRAINT "crown_hunt_claims_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "crown_hunt_claims_idempotency_key_key" UNIQUE ("idempotency_key"),
  CONSTRAINT "crown_hunt_claims_point_id_fkey"
    FOREIGN KEY ("point_id") REFERENCES "crown_hunt_points"("id") ON DELETE RESTRICT,
  CONSTRAINT "crown_hunt_claims_user_id_fkey"
    FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE
);

CREATE INDEX "crown_hunt_claims_user_id_claimed_at_idx"  ON "crown_hunt_claims"("user_id", "claimed_at" DESC);
CREATE INDEX "crown_hunt_claims_point_id_claimed_at_idx" ON "crown_hunt_claims"("point_id", "claimed_at" DESC);
CREATE INDEX "crown_hunt_claims_result_idx"              ON "crown_hunt_claims"("result");
