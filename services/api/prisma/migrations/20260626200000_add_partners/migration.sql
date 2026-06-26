-- KCC Företagspartner (Business Partner) migration
-- Adds:
--   PartnerApplicationStatus enum
--   PartnerCompanyStatus enum
--   PartnerCategory enum
--   partner_applications table
--   partner_companies table

-- PartnerApplicationStatus enum
DO $$ BEGIN
  CREATE TYPE "PartnerApplicationStatus" AS ENUM (
    'submitted',
    'under_review',
    'approved',
    'rejected',
    'withdrawn'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;

-- PartnerCompanyStatus enum
DO $$ BEGIN
  CREATE TYPE "PartnerCompanyStatus" AS ENUM (
    'draft',
    'active',
    'paused',
    'ended'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;

-- PartnerCategory enum
DO $$ BEGIN
  CREATE TYPE "PartnerCategory" AS ENUM (
    'workshop',
    'car_care',
    'parts',
    'tires',
    'charging',
    'restaurant',
    'retail',
    'other'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;

-- partner_applications table
CREATE TABLE "partner_applications" (
  "id"                    UUID          NOT NULL DEFAULT gen_random_uuid(),
  "company_name"          VARCHAR(150)  NOT NULL,
  "organization_number"   VARCHAR(30),
  "category"              "PartnerCategory" NOT NULL,
  "contact_name"          VARCHAR(120)  NOT NULL,
  "contact_email"         VARCHAR(254)  NOT NULL,
  "contact_phone"         VARCHAR(30),
  "website_url"           VARCHAR(500),
  "proposed_description"  VARCHAR(1000),
  "proposed_address"      VARCHAR(300),
  "message"               VARCHAR(2000),
  "status"                "PartnerApplicationStatus" NOT NULL DEFAULT 'submitted',
  "submitted_by_user_id"  UUID,
  "reviewed_by_user_id"   UUID,
  "reviewed_at"           TIMESTAMPTZ,
  "review_reason"         TEXT,
  "created_at"            TIMESTAMPTZ   NOT NULL DEFAULT now(),
  "updated_at"            TIMESTAMPTZ   NOT NULL DEFAULT now(),

  CONSTRAINT "partner_applications_pkey" PRIMARY KEY ("id")
);

ALTER TABLE "partner_applications"
  ADD CONSTRAINT "partner_applications_submitted_by_user_id_fkey"
  FOREIGN KEY ("submitted_by_user_id") REFERENCES "users"("id") ON DELETE SET NULL;

ALTER TABLE "partner_applications"
  ADD CONSTRAINT "partner_applications_reviewed_by_user_id_fkey"
  FOREIGN KEY ("reviewed_by_user_id") REFERENCES "users"("id") ON DELETE SET NULL;

CREATE INDEX "partner_applications_status_idx"        ON "partner_applications"("status");
CREATE INDEX "partner_applications_created_at_idx"    ON "partner_applications"("created_at");
CREATE INDEX "partner_applications_contact_email_idx" ON "partner_applications"("contact_email");

-- partner_companies table
CREATE TABLE "partner_companies" (
  "id"                  UUID          NOT NULL DEFAULT gen_random_uuid(),
  "application_id"      UUID,
  "company_name"        VARCHAR(150)  NOT NULL,
  "category"            "PartnerCategory" NOT NULL,
  "public_description"  VARCHAR(1000) NOT NULL,
  "address"             VARCHAR(300)  NOT NULL,
  "latitude"            DOUBLE PRECISION NOT NULL,
  "longitude"           DOUBLE PRECISION NOT NULL,
  "public_phone"        VARCHAR(30),
  "public_website_url"  VARCHAR(500),
  "status"              "PartnerCompanyStatus" NOT NULL DEFAULT 'draft',
  "activated_at"        TIMESTAMPTZ,
  "paused_at"           TIMESTAMPTZ,
  "ended_at"            TIMESTAMPTZ,
  "created_by_user_id"  UUID          NOT NULL,
  "updated_by_user_id"  UUID,
  "created_at"          TIMESTAMPTZ   NOT NULL DEFAULT now(),
  "updated_at"          TIMESTAMPTZ   NOT NULL DEFAULT now(),

  CONSTRAINT "partner_companies_pkey"                PRIMARY KEY ("id"),
  CONSTRAINT "partner_companies_application_id_key"  UNIQUE ("application_id")
);

ALTER TABLE "partner_companies"
  ADD CONSTRAINT "partner_companies_application_id_fkey"
  FOREIGN KEY ("application_id") REFERENCES "partner_applications"("id") ON DELETE SET NULL;

ALTER TABLE "partner_companies"
  ADD CONSTRAINT "partner_companies_created_by_user_id_fkey"
  FOREIGN KEY ("created_by_user_id") REFERENCES "users"("id") ON DELETE RESTRICT;

ALTER TABLE "partner_companies"
  ADD CONSTRAINT "partner_companies_updated_by_user_id_fkey"
  FOREIGN KEY ("updated_by_user_id") REFERENCES "users"("id") ON DELETE SET NULL;

CREATE INDEX "partner_companies_status_idx"         ON "partner_companies"("status");
CREATE INDEX "partner_companies_category_idx"       ON "partner_companies"("category");
CREATE INDEX "partner_companies_company_name_idx"   ON "partner_companies"("company_name");
CREATE INDEX "partner_companies_lat_lon_idx"        ON "partner_companies"("latitude", "longitude");
