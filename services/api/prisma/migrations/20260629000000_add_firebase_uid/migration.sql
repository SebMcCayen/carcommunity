-- Add firebase_uid column to users table.
-- This column stores the Firebase UID assigned by Firebase Authentication.
-- It is only populated from a verified Firebase ID token — never from client input.

ALTER TABLE "users" ADD COLUMN "firebase_uid" VARCHAR(128);

CREATE UNIQUE INDEX "users_firebase_uid_key" ON "users"("firebase_uid");
