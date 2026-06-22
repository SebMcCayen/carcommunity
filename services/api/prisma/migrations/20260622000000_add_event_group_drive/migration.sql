-- Migration: add event group drive participants
-- Adds the EventGroupDriveParticipant model for the group drive MVP.
-- No location data is stored in this table; live location is reused from
-- live_location_latest_positions.

-- Create the participant status enum
CREATE TYPE "GroupDriveParticipantStatus" AS ENUM ('joined', 'on_the_way', 'arrived', 'left');

-- Create the participants table
CREATE TABLE "event_group_drive_participants" (
    "id" UUID NOT NULL,
    "event_id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "status" "GroupDriveParticipantStatus" NOT NULL DEFAULT 'joined',
    "joined_at" TIMESTAMP(3) NOT NULL,
    "left_at" TIMESTAMP(3),
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "event_group_drive_participants_pkey" PRIMARY KEY ("id")
);

-- Unique constraint: one record per participant per event
CREATE UNIQUE INDEX "event_group_drive_participants_event_id_user_id_key"
    ON "event_group_drive_participants"("event_id", "user_id");

-- Indexes for efficient lookup
CREATE INDEX "event_group_drive_participants_event_id_idx"
    ON "event_group_drive_participants"("event_id");

CREATE INDEX "event_group_drive_participants_user_id_idx"
    ON "event_group_drive_participants"("user_id");

CREATE INDEX "event_group_drive_participants_event_id_status_idx"
    ON "event_group_drive_participants"("event_id", "status");

-- Foreign keys
ALTER TABLE "event_group_drive_participants"
    ADD CONSTRAINT "event_group_drive_participants_event_id_fkey"
    FOREIGN KEY ("event_id") REFERENCES "events"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "event_group_drive_participants"
    ADD CONSTRAINT "event_group_drive_participants_user_id_fkey"
    FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;
