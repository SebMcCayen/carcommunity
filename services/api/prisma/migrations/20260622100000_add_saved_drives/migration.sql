-- ---------------------------------------------------------------------------
-- Add saved_drives table
--
-- Privacy notes:
--   - No top-speed column: never stored or returned.
--   - approximateStartArea / approximateEndArea are labels only, never coordinates.
--   - routeOverview stores a minimized JSON array for members; null otherwise.
--   - Raw temporary route points are never stored in this table.
--   - sourceLiveLocationSessionId is optional (session may be deleted) and opaque.
--
-- Summary-only MVP:
--   distance_meters, average_speed_meters_per_second, and route_overview are
--   nullable until the TemporaryDrivePoint architecture is implemented.
-- ---------------------------------------------------------------------------

CREATE TABLE "saved_drives" (
  "id"                              UUID         NOT NULL DEFAULT gen_random_uuid(),
  "user_id"                         UUID         NOT NULL,
  "source_live_location_session_id" UUID,
  "started_at"                      TIMESTAMPTZ  NOT NULL,
  "ended_at"                        TIMESTAMPTZ  NOT NULL,
  "duration_seconds"                INTEGER      NOT NULL,
  "distance_meters"                 DOUBLE PRECISION,
  "average_speed_meters_per_second" DOUBLE PRECISION,
  "approximate_start_area"          VARCHAR(200),
  "approximate_end_area"            VARCHAR(200),
  "route_overview"                  JSONB,
  "created_at"                      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  "updated_at"                      TIMESTAMPTZ  NOT NULL,

  CONSTRAINT "saved_drives_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "saved_drives_user_id_fkey"
    FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE
);

-- Prevent duplicate saves for the same session per user.
CREATE UNIQUE INDEX "saved_drives_user_id_source_live_location_session_id_key"
  ON "saved_drives" ("user_id", "source_live_location_session_id")
  WHERE "source_live_location_session_id" IS NOT NULL;

-- Owner-scoped list sorted newest first.
CREATE INDEX "saved_drives_user_id_started_at_idx"
  ON "saved_drives" ("user_id", "started_at" DESC);

-- Cleanup / audit index.
CREATE INDEX "saved_drives_created_at_idx"
  ON "saved_drives" ("created_at");
