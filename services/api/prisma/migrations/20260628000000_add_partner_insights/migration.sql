-- Partner Insights foundation migration
-- Adds privacy-preserving analytics models for partner interactions.
-- Raw events are short-lived (TTL-based cleanup). Aggregates are retained.
-- No user IDs, coordinates, IP addresses, or device identifiers are stored.

-- Enums
CREATE TYPE "PartnerInteractionType" AS ENUM (
  'map_view',
  'profile_view',
  'navigate',
  'phone',
  'website',
  'offer_view',
  'show_code',
  'save_offer',
  'anonymous_pass_by'
);

CREATE TYPE "AggregationPeriodType" AS ENUM ('day', 'week', 'month');

CREATE TYPE "InsightResultStatus" AS ENUM ('available', 'insufficient_data', 'no_data');

-- PartnerInteractionEvent: short-lived raw event rows
CREATE TABLE "partner_interaction_events" (
  "id"                  UUID NOT NULL DEFAULT gen_random_uuid(),
  "partner_company_id"  UUID NOT NULL,
  "interaction_type"    "PartnerInteractionType" NOT NULL,
  "user_reference_hash" VARCHAR(64),
  "occurred_at"         TIMESTAMPTZ NOT NULL,
  "aggregation_date"    DATE NOT NULL,
  "expires_at"          TIMESTAMPTZ NOT NULL,
  "metadata"            JSONB,
  "created_at"          TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT "partner_interaction_events_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "partner_interaction_events_partner_company_id_fkey"
    FOREIGN KEY ("partner_company_id") REFERENCES "partner_companies"("id") ON DELETE CASCADE
);
CREATE INDEX "partner_interaction_events_partner_company_id_aggregation_date_idx"
  ON "partner_interaction_events"("partner_company_id", "aggregation_date");
CREATE INDEX "partner_interaction_events_interaction_type_idx"
  ON "partner_interaction_events"("interaction_type");
CREATE INDEX "partner_interaction_events_expires_at_idx"
  ON "partner_interaction_events"("expires_at");
CREATE INDEX "partner_interaction_events_partner_company_id_interaction_type_aggregation_date_idx"
  ON "partner_interaction_events"("partner_company_id", "interaction_type", "aggregation_date");

-- PartnerMetricAggregate: retained aggregated metrics
CREATE TABLE "partner_metric_aggregates" (
  "id"                      UUID NOT NULL DEFAULT gen_random_uuid(),
  "partner_company_id"      UUID NOT NULL,
  "interaction_type"        "PartnerInteractionType" NOT NULL,
  "period_type"             "AggregationPeriodType" NOT NULL,
  "period_start"            DATE NOT NULL,
  "period_end"              DATE NOT NULL,
  "total_count"             INTEGER NOT NULL DEFAULT 0,
  "unique_contributor_count" INTEGER,
  "result_status"           "InsightResultStatus" NOT NULL DEFAULT 'no_data',
  "created_at"              TIMESTAMPTZ NOT NULL DEFAULT now(),
  "updated_at"              TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT "partner_metric_aggregates_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "partner_metric_aggregates_partner_company_id_interaction_type_period_type_period_start_key"
    UNIQUE ("partner_company_id", "interaction_type", "period_type", "period_start"),
  CONSTRAINT "partner_metric_aggregates_partner_company_id_fkey"
    FOREIGN KEY ("partner_company_id") REFERENCES "partner_companies"("id") ON DELETE CASCADE
);
CREATE INDEX "partner_metric_aggregates_partner_company_id_period_start_idx"
  ON "partner_metric_aggregates"("partner_company_id", "period_start");
CREATE INDEX "partner_metric_aggregates_interaction_type_idx"
  ON "partner_metric_aggregates"("interaction_type");
CREATE INDEX "partner_metric_aggregates_period_type_idx"
  ON "partner_metric_aggregates"("period_type");

-- PartnerPassByContribution: temporary deduplication tracker
CREATE TABLE "partner_pass_by_contributions" (
  "id"                     UUID NOT NULL DEFAULT gen_random_uuid(),
  "partner_company_id"     UUID NOT NULL,
  "scoped_contributor_hash" VARCHAR(64) NOT NULL,
  "aggregation_date"       DATE NOT NULL,
  "expires_at"             TIMESTAMPTZ NOT NULL,
  "created_at"             TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT "partner_pass_by_contributions_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "partner_pass_by_contributions_partner_company_id_scoped_contributor_hash_aggregation_date_key"
    UNIQUE ("partner_company_id", "scoped_contributor_hash", "aggregation_date"),
  CONSTRAINT "partner_pass_by_contributions_partner_company_id_fkey"
    FOREIGN KEY ("partner_company_id") REFERENCES "partner_companies"("id") ON DELETE CASCADE
);
CREATE INDEX "partner_pass_by_contributions_partner_company_id_aggregation_date_idx"
  ON "partner_pass_by_contributions"("partner_company_id", "aggregation_date");
CREATE INDEX "partner_pass_by_contributions_expires_at_idx"
  ON "partner_pass_by_contributions"("expires_at");
