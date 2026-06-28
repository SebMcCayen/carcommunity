-- CreateEnum
CREATE TYPE "BillboardStatus" AS ENUM ('draft', 'active', 'paused', 'ended');

-- CreateEnum
CREATE TYPE "BillboardPlacementType" AS ENUM ('map_billboard', 'event_area', 'partner_area', 'other_approved_location');

-- CreateEnum
CREATE TYPE "BillboardCallToActionType" AS ENUM ('navigate', 'phone', 'website', 'offer_view', 'partner_profile');

-- CreateTable
CREATE TABLE "sponsored_billboards" (
    "id" UUID NOT NULL,
    "partner_company_id" UUID NOT NULL,
    "headline" VARCHAR(100) NOT NULL,
    "message" VARCHAR(300) NOT NULL,
    "placement_type" "BillboardPlacementType" NOT NULL,
    "latitude" DOUBLE PRECISION NOT NULL,
    "longitude" DOUBLE PRECISION NOT NULL,
    "status" "BillboardStatus" NOT NULL DEFAULT 'draft',
    "available_from" TIMESTAMP(3),
    "available_until" TIMESTAMP(3),
    "call_to_action_type" "BillboardCallToActionType",
    "call_to_action_value" VARCHAR(500),
    "safety_note" VARCHAR(500),
    "approval_reason" TEXT,
    "approved_at" TIMESTAMP(3),
    "approved_by_user_id" UUID,
    "activated_at" TIMESTAMP(3),
    "paused_at" TIMESTAMP(3),
    "ended_at" TIMESTAMP(3),
    "created_by_user_id" UUID NOT NULL,
    "updated_by_user_id" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "sponsored_billboards_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "sponsored_billboards_status_idx" ON "sponsored_billboards"("status");

-- CreateIndex
CREATE INDEX "sponsored_billboards_partner_company_id_idx" ON "sponsored_billboards"("partner_company_id");

-- CreateIndex
CREATE INDEX "sponsored_billboards_available_from_idx" ON "sponsored_billboards"("available_from");

-- CreateIndex
CREATE INDEX "sponsored_billboards_available_until_idx" ON "sponsored_billboards"("available_until");

-- CreateIndex
CREATE INDEX "sponsored_billboards_latitude_longitude_idx" ON "sponsored_billboards"("latitude", "longitude");

-- AddForeignKey
ALTER TABLE "sponsored_billboards" ADD CONSTRAINT "sponsored_billboards_partner_company_id_fkey" FOREIGN KEY ("partner_company_id") REFERENCES "partner_companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "sponsored_billboards" ADD CONSTRAINT "sponsored_billboards_approved_by_user_id_fkey" FOREIGN KEY ("approved_by_user_id") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "sponsored_billboards" ADD CONSTRAINT "sponsored_billboards_created_by_user_id_fkey" FOREIGN KEY ("created_by_user_id") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "sponsored_billboards" ADD CONSTRAINT "sponsored_billboards_updated_by_user_id_fkey" FOREIGN KEY ("updated_by_user_id") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;
