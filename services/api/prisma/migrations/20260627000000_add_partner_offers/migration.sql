-- CreateEnum
CREATE TYPE "PartnerOfferStatus" AS ENUM ('draft', 'active', 'paused', 'ended', 'expired');

-- CreateEnum
CREATE TYPE "PartnerOfferType" AS ENUM ('discount_code', 'percentage_discount', 'fixed_discount', 'member_benefit', 'special_offer', 'other');

-- CreateTable
CREATE TABLE "partner_offers" (
    "id" UUID NOT NULL,
    "partner_company_id" UUID NOT NULL,
    "title" VARCHAR(150) NOT NULL,
    "teaser_text" VARCHAR(250) NOT NULL,
    "description" TEXT,
    "offer_type" "PartnerOfferType" NOT NULL,
    "status" "PartnerOfferStatus" NOT NULL DEFAULT 'draft',
    "discount_code" VARCHAR(100),
    "redemption_instructions" TEXT,
    "terms" TEXT,
    "percentage_discount" DOUBLE PRECISION,
    "fixed_discount_minor_units" INTEGER,
    "currency_code" VARCHAR(3),
    "available_from" TIMESTAMP(3),
    "available_until" TIMESTAMP(3),
    "activated_at" TIMESTAMP(3),
    "paused_at" TIMESTAMP(3),
    "ended_at" TIMESTAMP(3),
    "created_by_user_id" UUID NOT NULL,
    "updated_by_user_id" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "partner_offers_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "saved_partner_offers" (
    "id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "offer_id" UUID NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "saved_partner_offers_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "partner_offers_partner_company_id_idx" ON "partner_offers"("partner_company_id");

-- CreateIndex
CREATE INDEX "partner_offers_status_idx" ON "partner_offers"("status");

-- CreateIndex
CREATE INDEX "partner_offers_available_from_idx" ON "partner_offers"("available_from");

-- CreateIndex
CREATE INDEX "partner_offers_available_until_idx" ON "partner_offers"("available_until");

-- CreateIndex
CREATE INDEX "partner_offers_partner_company_id_status_idx" ON "partner_offers"("partner_company_id", "status");

-- CreateIndex
CREATE UNIQUE INDEX "saved_partner_offers_user_id_offer_id_key" ON "saved_partner_offers"("user_id", "offer_id");

-- CreateIndex
CREATE INDEX "saved_partner_offers_user_id_created_at_idx" ON "saved_partner_offers"("user_id", "created_at" DESC);

-- CreateIndex
CREATE INDEX "saved_partner_offers_offer_id_idx" ON "saved_partner_offers"("offer_id");

-- AddForeignKey
ALTER TABLE "partner_offers" ADD CONSTRAINT "partner_offers_partner_company_id_fkey" FOREIGN KEY ("partner_company_id") REFERENCES "partner_companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "partner_offers" ADD CONSTRAINT "partner_offers_created_by_user_id_fkey" FOREIGN KEY ("created_by_user_id") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "partner_offers" ADD CONSTRAINT "partner_offers_updated_by_user_id_fkey" FOREIGN KEY ("updated_by_user_id") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "saved_partner_offers" ADD CONSTRAINT "saved_partner_offers_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "saved_partner_offers" ADD CONSTRAINT "saved_partner_offers_offer_id_fkey" FOREIGN KEY ("offer_id") REFERENCES "partner_offers"("id") ON DELETE CASCADE ON UPDATE CASCADE;
