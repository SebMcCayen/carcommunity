CREATE INDEX IF NOT EXISTS "live_location_sessions_active_user_expires_idx"
ON "live_location_sessions" ("user_id", "expires_at")
WHERE "status" = 'active';
