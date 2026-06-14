-- CreateEnum
CREATE TYPE "DiagnosticsSeverity" AS ENUM ('info', 'warning', 'error', 'critical');

-- CreateEnum
CREATE TYPE "DiagnosticsPlatform" AS ENUM ('ios', 'android', 'web', 'unknown');

-- CreateEnum
CREATE TYPE "DiagnosticsFeatureArea" AS ENUM ('auth', 'live_location', 'events', 'subscription', 'admin', 'map', 'network', 'unknown');

-- CreateTable
CREATE TABLE "diagnostics_reports" (
    "id" UUID NOT NULL,
    "user_id" UUID,
    "platform" "DiagnosticsPlatform" NOT NULL,
    "feature_area" "DiagnosticsFeatureArea" NOT NULL,
    "app_version" VARCHAR(50),
    "build_number" VARCHAR(50),
    "os_version" VARCHAR(100),
    "severity" "DiagnosticsSeverity" NOT NULL DEFAULT 'info',
    "error_code" VARCHAR(100),
    "safe_message" VARCHAR(2000) NOT NULL,
    "fingerprint" VARCHAR(128),
    "metadata" JSONB,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "diagnostics_reports_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "diagnostics_reports_severity_idx" ON "diagnostics_reports"("severity");

-- CreateIndex
CREATE INDEX "diagnostics_reports_platform_idx" ON "diagnostics_reports"("platform");

-- CreateIndex
CREATE INDEX "diagnostics_reports_feature_area_idx" ON "diagnostics_reports"("feature_area");

-- CreateIndex
CREATE INDEX "diagnostics_reports_created_at_idx" ON "diagnostics_reports"("created_at");

-- CreateIndex
CREATE INDEX "diagnostics_reports_fingerprint_idx" ON "diagnostics_reports"("fingerprint");

-- AddForeignKey
ALTER TABLE "diagnostics_reports" ADD CONSTRAINT "diagnostics_reports_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;
