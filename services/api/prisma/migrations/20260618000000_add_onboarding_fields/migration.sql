-- AlterTable: add onboarding and privacy settings fields to users
ALTER TABLE "users"
  ADD COLUMN "onboarding_completed_at" TIMESTAMP(3),
  ADD COLUMN "age_confirmed_at"        TIMESTAMP(3),
  ADD COLUMN "terms_accepted_at"       TIMESTAMP(3),
  ADD COLUMN "privacy_policy_accepted_at" TIMESTAMP(3),
  ADD COLUMN "anonymous_partner_stats_opt_in" BOOLEAN NOT NULL DEFAULT false;
