/**
 * PartnerOfferService — business logic for partner offers and member benefits.
 *
 * Design rules enforced here:
 *  - discountCode is NEVER included in teaser, list, or detail responses.
 *  - discountCode is ONLY returned from showCode().
 *  - discountCode is NEVER logged or included in audit metadata.
 *  - New offers always start as `draft`. Status never set via create/update.
 *  - Activation requires: confirmed=true, active partner company, non-empty description.
 *  - Pause/end require a non-empty reason string.
 *  - Suspended and deleted users cannot access protected offer details.
 *  - availableUntil must be after availableFrom when both present.
 *  - Percentage discount must be > 0 and <= 100.
 *  - Fixed discount must be non-negative integer.
 *  - Currency code is required when fixed discount is used.
 *  - Backend is the sole authority for availability and entitlement checks.
 *  - Status transitions use dedicated action methods only.
 *  - Important status changes are audited (without code or sensitive fields).
 */

import type { PrismaClient } from '@prisma/client';

import {
  type PartnerOfferStatus,
  type PartnerOfferType,
  type PublicPartnerOfferTeaser,
  type MemberPartnerOfferDetail,
  type AdminPartnerOfferSummary,
  type AdminPartnerOfferDetail,
  type ShowCodeResponse,
  DEFAULT_PARTNER_OFFER_PAGE_SIZE,
  MAX_PARTNER_OFFER_PAGE_SIZE,
} from '@carcommunity/shared/partner-offers';

import {
  canManagePartnerOffers,
  canViewPartnerOfferDetails,
  canViewPartnerOfferTeaser,
} from '@carcommunity/shared/users';

import type { UserRole, UserStatus, SubscriptionEntitlement } from '@carcommunity/shared/users';
import { AppError } from './errors.js';
import type { WriteAuditLogInput } from './moderation-service.js';

// ---------------------------------------------------------------------------
// Input interfaces
// ---------------------------------------------------------------------------

export interface ListOfferTeasersInput {
  page?: number;
  pageSize?: number;
  partnerId?: string;
}

export interface ListMemberOffersInput {
  page?: number;
  pageSize?: number;
}

export interface ListAdminOffersInput {
  page?: number;
  pageSize?: number;
  partnerId?: string;
  status?: PartnerOfferStatus;
}

export interface CreateOfferInput {
  actorUserId: string;
  partnerId: string;
  title: string;
  teaserText: string;
  description: string;
  offerType: PartnerOfferType;
  redemptionInstructions?: string | null;
  terms?: string | null;
  discountCode?: string | null;
  percentageDiscount?: number | null;
  fixedDiscountMinorUnits?: number | null;
  currencyCode?: string | null;
  availableFrom?: string | null;
  availableUntil?: string | null;
}

export interface UpdateOfferInput {
  actorUserId: string;
  offerId: string;
  title?: string;
  teaserText?: string;
  description?: string;
  offerType?: PartnerOfferType;
  redemptionInstructions?: string | null;
  terms?: string | null;
  discountCode?: string | null;
  percentageDiscount?: number | null;
  fixedDiscountMinorUnits?: number | null;
  currencyCode?: string | null;
  availableFrom?: string | null;
  availableUntil?: string | null;
}

export interface ActivateOfferInput {
  actorUserId: string;
  offerId: string;
  confirmed: boolean;
}

export interface StatusActionInput {
  actorUserId: string;
  offerId: string;
  reason: string;
}

// ---------------------------------------------------------------------------
// Row type helpers (subset of Prisma PartnerOffer row)
// ---------------------------------------------------------------------------

interface OfferRow {
  id: string;
  partnerCompanyId: string;
  title: string;
  teaserText: string;
  description: string | null;
  offerType: string;
  status: string;
  discountCode: string | null;
  redemptionInstructions: string | null;
  terms: string | null;
  percentageDiscount: number | null;
  fixedDiscountMinorUnits: number | null;
  currencyCode: string | null;
  availableFrom: Date | null;
  availableUntil: Date | null;
  activatedAt: Date | null;
  pausedAt: Date | null;
  endedAt: Date | null;
  createdByUserId: string;
  updatedByUserId: string | null;
  createdAt: Date;
  updatedAt: Date;
}

// ---------------------------------------------------------------------------
// Mapping helpers — discountCode NEVER included
// ---------------------------------------------------------------------------

function toTeaser(row: OfferRow, companyName: string): PublicPartnerOfferTeaser {
  return {
    offerId: row.id,
    partnerId: row.partnerCompanyId,
    partnerCompanyName: companyName,
    title: row.title,
    teaserText: row.teaserText,
    offerType: row.offerType as PartnerOfferType,
    availableUntil: row.availableUntil ? row.availableUntil.toISOString() : null,
    requiresMembership: true,
  };
}

function toMemberDetail(row: OfferRow, companyName: string): MemberPartnerOfferDetail {
  return {
    offerId: row.id,
    partnerId: row.partnerCompanyId,
    partnerCompanyName: companyName,
    title: row.title,
    teaserText: row.teaserText,
    offerType: row.offerType as PartnerOfferType,
    description: row.description ?? '',
    redemptionInstructions: row.redemptionInstructions,
    terms: row.terms,
    percentageDiscount: row.percentageDiscount,
    fixedDiscountMinorUnits: row.fixedDiscountMinorUnits,
    currencyCode: row.currencyCode,
    availableFrom: row.availableFrom ? row.availableFrom.toISOString() : null,
    availableUntil: row.availableUntil ? row.availableUntil.toISOString() : null,
    // discountCode intentionally excluded
  };
}

function toAdminSummary(row: OfferRow, companyName: string): AdminPartnerOfferSummary {
  return {
    offerId: row.id,
    partnerId: row.partnerCompanyId,
    partnerCompanyName: companyName,
    title: row.title,
    offerType: row.offerType as PartnerOfferType,
    status: row.status as PartnerOfferStatus,
    availableFrom: row.availableFrom ? row.availableFrom.toISOString() : null,
    availableUntil: row.availableUntil ? row.availableUntil.toISOString() : null,
    activatedAt: row.activatedAt ? row.activatedAt.toISOString() : null,
    createdAt: row.createdAt.toISOString(),
    updatedAt: row.updatedAt.toISOString(),
  };
}

function toAdminDetail(row: OfferRow, companyName: string): AdminPartnerOfferDetail {
  return {
    offerId: row.id,
    partnerId: row.partnerCompanyId,
    partnerCompanyName: companyName,
    title: row.title,
    teaserText: row.teaserText,
    description: row.description,
    offerType: row.offerType as PartnerOfferType,
    status: row.status as PartnerOfferStatus,
    redemptionInstructions: row.redemptionInstructions,
    terms: row.terms,
    percentageDiscount: row.percentageDiscount,
    fixedDiscountMinorUnits: row.fixedDiscountMinorUnits,
    currencyCode: row.currencyCode,
    availableFrom: row.availableFrom ? row.availableFrom.toISOString() : null,
    availableUntil: row.availableUntil ? row.availableUntil.toISOString() : null,
    activatedAt: row.activatedAt ? row.activatedAt.toISOString() : null,
    pausedAt: row.pausedAt ? row.pausedAt.toISOString() : null,
    endedAt: row.endedAt ? row.endedAt.toISOString() : null,
    createdByUserId: row.createdByUserId,
    updatedByUserId: row.updatedByUserId,
    createdAt: row.createdAt.toISOString(),
    updatedAt: row.updatedAt.toISOString(),
    // discountCode intentionally excluded
  };
}

// ---------------------------------------------------------------------------
// Validation helpers
// ---------------------------------------------------------------------------

function validateDiscountFields(input: {
  percentageDiscount?: number | null;
  fixedDiscountMinorUnits?: number | null;
  currencyCode?: string | null;
}): void {
  if (input.percentageDiscount !== undefined && input.percentageDiscount !== null) {
    if (input.percentageDiscount <= 0 || input.percentageDiscount > 100) {
      throw new AppError(
        422,
        'offer_invalid_percentage_discount',
        'Percentage discount must be greater than 0 and at most 100.',
      );
    }
  }
  if (input.fixedDiscountMinorUnits !== undefined && input.fixedDiscountMinorUnits !== null) {
    if (!Number.isInteger(input.fixedDiscountMinorUnits) || input.fixedDiscountMinorUnits < 0) {
      throw new AppError(
        422,
        'offer_invalid_fixed_discount',
        'Fixed discount must be a non-negative integer.',
      );
    }
    if (!input.currencyCode) {
      throw new AppError(
        422,
        'offer_currency_required',
        'Currency code is required when fixed discount is set.',
      );
    }
  }
}

function validateDateRange(input: {
  availableFrom?: string | null;
  availableUntil?: string | null;
}): void {
  if (input.availableFrom && input.availableUntil) {
    if (new Date(input.availableUntil) <= new Date(input.availableFrom)) {
      throw new AppError(
        422,
        'offer_date_range_invalid',
        'availableUntil must be after availableFrom.',
      );
    }
  }
}

function requireValidActivationData(row: OfferRow): void {
  if (!row.teaserText.trim()) {
    throw new AppError(422, 'offer_teaser_required', 'Teaser text is required for activation.');
  }
  if (!row.description || !row.description.trim()) {
    throw new AppError(422, 'offer_description_required', 'Description is required for activation.');
  }
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

export class PartnerOfferService {
  constructor(private readonly prisma: PrismaClient) {}

  // -----------------------------------------------------------------------
  // Public: list offer teasers (all authenticated users, no code)
  // -----------------------------------------------------------------------

  async listOfferTeasers(input: ListOfferTeasersInput = {}): Promise<{
    offers: PublicPartnerOfferTeaser[];
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  }> {
    const page = Math.max(1, input.page ?? 1);
    const pageSize = Math.min(
      MAX_PARTNER_OFFER_PAGE_SIZE,
      Math.max(1, input.pageSize ?? DEFAULT_PARTNER_OFFER_PAGE_SIZE),
    );
    const skip = (page - 1) * pageSize;
    const now = new Date();

    type WhereClause = {
      status: 'active';
      availableFrom?: { lte: Date } | undefined;
      availableUntil?: { gte: Date } | undefined;
      partnerCompanyId?: string;
      partnerCompany: { status: 'active' };
    };

    const where: WhereClause = {
      status: 'active',
      partnerCompany: { status: 'active' },
    };

    // Filter out offers whose availability window hasn't started
    (where as Record<string, unknown>).availableFrom = { lte: now };
    // Filter out expired offers — only return offers whose availableUntil is null or in the future
    (where as Record<string, unknown>).OR = [
      { availableUntil: null },
      { availableUntil: { gte: now } },
    ];
    delete (where as Record<string, unknown>).availableFrom;

    const baseWhere = {
      status: 'active' as const,
      partnerCompany: { status: 'active' as const },
      OR: [
        { availableUntil: null },
        { availableUntil: { gte: now } },
      ],
      ...(input.partnerId ? { partnerCompanyId: input.partnerId } : {}),
    };

    const [rows, total] = await Promise.all([
      this.prisma.partnerOffer.findMany({
        where: baseWhere,
        orderBy: { createdAt: 'desc' },
        skip,
        take: pageSize,
        select: {
          id: true,
          partnerCompanyId: true,
          title: true,
          teaserText: true,
          description: true,
          offerType: true,
          status: true,
          discountCode: false, // NEVER select discountCode
          redemptionInstructions: true,
          terms: true,
          percentageDiscount: true,
          fixedDiscountMinorUnits: true,
          currencyCode: true,
          availableFrom: true,
          availableUntil: true,
          activatedAt: true,
          pausedAt: true,
          endedAt: true,
          createdByUserId: true,
          updatedByUserId: true,
          createdAt: true,
          updatedAt: true,
          partnerCompany: { select: { companyName: true } },
        },
      }),
      this.prisma.partnerOffer.count({ where: baseWhere }),
    ]);

    return {
      offers: rows.map((r) =>
        toTeaser({ ...r, discountCode: null }, r.partnerCompany.companyName),
      ),
      page,
      pageSize,
      total,
      hasNext: skip + rows.length < total,
    };
  }

  // -----------------------------------------------------------------------
  // Public: get single offer teaser
  // -----------------------------------------------------------------------

  async getOfferTeaser(offerId: string): Promise<PublicPartnerOfferTeaser | null> {
    const row = await this.prisma.partnerOffer.findUnique({
      where: { id: offerId },
      select: {
        id: true,
        partnerCompanyId: true,
        title: true,
        teaserText: true,
        description: true,
        offerType: true,
        status: true,
        redemptionInstructions: true,
        terms: true,
        percentageDiscount: true,
        fixedDiscountMinorUnits: true,
        currencyCode: true,
        availableFrom: true,
        availableUntil: true,
        activatedAt: true,
        pausedAt: true,
        endedAt: true,
        createdByUserId: true,
        updatedByUserId: true,
        createdAt: true,
        updatedAt: true,
        partnerCompany: { select: { companyName: true, status: true } },
      },
    });

    if (!row || row.status !== 'active' || row.partnerCompany.status !== 'active') {
      return null;
    }

    return toTeaser({ ...row, discountCode: null }, row.partnerCompany.companyName);
  }

  // -----------------------------------------------------------------------
  // Member: list offers (protected, no code)
  // -----------------------------------------------------------------------

  async listMemberOffers(
    user: { role: UserRole; status: UserStatus; subscriptionEntitlement: SubscriptionEntitlement },
    input: ListMemberOffersInput = {},
  ): Promise<{
    offers: MemberPartnerOfferDetail[];
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  }> {
    if (!canViewPartnerOfferDetails(user)) {
      throw new AppError(403, 'forbidden', 'Member subscription required to view offer details.');
    }

    const page = Math.max(1, input.page ?? 1);
    const pageSize = Math.min(
      MAX_PARTNER_OFFER_PAGE_SIZE,
      Math.max(1, input.pageSize ?? DEFAULT_PARTNER_OFFER_PAGE_SIZE),
    );
    const skip = (page - 1) * pageSize;
    const now = new Date();

    const where = {
      status: 'active' as const,
      partnerCompany: { status: 'active' as const },
      OR: [
        { availableUntil: null },
        { availableUntil: { gte: now } },
      ],
    };

    const [rows, total] = await Promise.all([
      this.prisma.partnerOffer.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip,
        take: pageSize,
        select: {
          id: true,
          partnerCompanyId: true,
          title: true,
          teaserText: true,
          description: true,
          offerType: true,
          status: true,
          redemptionInstructions: true,
          terms: true,
          percentageDiscount: true,
          fixedDiscountMinorUnits: true,
          currencyCode: true,
          availableFrom: true,
          availableUntil: true,
          activatedAt: true,
          pausedAt: true,
          endedAt: true,
          createdByUserId: true,
          updatedByUserId: true,
          createdAt: true,
          updatedAt: true,
          partnerCompany: { select: { companyName: true } },
        },
      }),
      this.prisma.partnerOffer.count({ where }),
    ]);

    return {
      offers: rows.map((r) =>
        toMemberDetail({ ...r, discountCode: null }, r.partnerCompany.companyName),
      ),
      page,
      pageSize,
      total,
      hasNext: skip + rows.length < total,
    };
  }

  // -----------------------------------------------------------------------
  // Member: get single offer detail (protected, no code)
  // -----------------------------------------------------------------------

  async getMemberOfferDetail(
    user: { role: UserRole; status: UserStatus; subscriptionEntitlement: SubscriptionEntitlement },
    offerId: string,
  ): Promise<MemberPartnerOfferDetail> {
    if (!canViewPartnerOfferDetails(user)) {
      throw new AppError(403, 'forbidden', 'Member subscription required to view offer details.');
    }

    const row = await this.prisma.partnerOffer.findUnique({
      where: { id: offerId },
      select: {
        id: true,
        partnerCompanyId: true,
        title: true,
        teaserText: true,
        description: true,
        offerType: true,
        status: true,
        redemptionInstructions: true,
        terms: true,
        percentageDiscount: true,
        fixedDiscountMinorUnits: true,
        currencyCode: true,
        availableFrom: true,
        availableUntil: true,
        activatedAt: true,
        pausedAt: true,
        endedAt: true,
        createdByUserId: true,
        updatedByUserId: true,
        createdAt: true,
        updatedAt: true,
        partnerCompany: { select: { companyName: true, status: true } },
      },
    });

    if (!row || row.status !== 'active' || row.partnerCompany.status !== 'active') {
      throw new AppError(404, 'not_found', 'Offer not found.');
    }

    return toMemberDetail({ ...row, discountCode: null }, row.partnerCompany.companyName);
  }

  // -----------------------------------------------------------------------
  // Member: show-code (returns code for active offer only, never logs code)
  // -----------------------------------------------------------------------

  async showCode(
    user: { role: UserRole; status: UserStatus; subscriptionEntitlement: SubscriptionEntitlement },
    offerId: string,
  ): Promise<ShowCodeResponse> {
    if (!canViewPartnerOfferDetails(user)) {
      throw new AppError(403, 'forbidden', 'Member subscription required to view offer codes.');
    }

    const row = await this.prisma.partnerOffer.findUnique({
      where: { id: offerId },
      select: {
        id: true,
        status: true,
        discountCode: true,
        redemptionInstructions: true,
        availableUntil: true,
        partnerCompany: { select: { status: true } },
      },
    });

    if (!row || row.partnerCompany.status !== 'active') {
      throw new AppError(404, 'not_found', 'Offer not found.');
    }

    if (row.status !== 'active') {
      throw new AppError(403, 'offer_not_active', 'This offer is no longer active.');
    }

    // Check expiry
    if (row.availableUntil && new Date(row.availableUntil) < new Date()) {
      throw new AppError(403, 'offer_not_active', 'This offer has expired.');
    }

    // discountCode is returned here ONLY — never logged
    return {
      offerId: row.id,
      code: row.discountCode,
      redemptionInstructions: row.redemptionInstructions,
      expiresAt: row.availableUntil ? row.availableUntil.toISOString() : null,
    };
  }

  // -----------------------------------------------------------------------
  // Member: save offer (idempotent)
  // -----------------------------------------------------------------------

  async saveOffer(
    user: { userId: string; role: UserRole; status: UserStatus; subscriptionEntitlement: SubscriptionEntitlement },
    offerId: string,
  ): Promise<{ offerId: string; savedAt: string }> {
    if (!canViewPartnerOfferDetails(user)) {
      throw new AppError(403, 'forbidden', 'Member subscription required to save offers.');
    }

    const offer = await this.prisma.partnerOffer.findUnique({
      where: { id: offerId },
      select: { id: true, status: true },
    });

    if (!offer || offer.status !== 'active') {
      throw new AppError(404, 'not_found', 'Offer not found.');
    }

    const existing = await this.prisma.savedPartnerOffer.findUnique({
      where: { userId_offerId: { userId: user.userId, offerId } },
      select: { id: true, createdAt: true },
    });

    if (existing) {
      return { offerId, savedAt: existing.createdAt.toISOString() };
    }

    const saved = await this.prisma.savedPartnerOffer.create({
      data: { userId: user.userId, offerId },
    });

    return { offerId, savedAt: saved.createdAt.toISOString() };
  }

  // -----------------------------------------------------------------------
  // Member: unsave offer (idempotent)
  // -----------------------------------------------------------------------

  async unsaveOffer(
    user: { userId: string; role: UserRole; status: UserStatus; subscriptionEntitlement: SubscriptionEntitlement },
    offerId: string,
  ): Promise<void> {
    if (!canViewPartnerOfferDetails(user)) {
      throw new AppError(403, 'forbidden', 'Member subscription required.');
    }

    await this.prisma.savedPartnerOffer
      .delete({ where: { userId_offerId: { userId: user.userId, offerId } } })
      .catch(() => undefined); // idempotent — ignore not found
  }

  // -----------------------------------------------------------------------
  // Member: list saved offers
  // -----------------------------------------------------------------------

  async listSavedOffers(
    user: { userId: string; role: UserRole; status: UserStatus; subscriptionEntitlement: SubscriptionEntitlement },
    input: ListMemberOffersInput = {},
  ): Promise<{
    offers: MemberPartnerOfferDetail[];
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  }> {
    if (!canViewPartnerOfferDetails(user)) {
      throw new AppError(403, 'forbidden', 'Member subscription required.');
    }

    const page = Math.max(1, input.page ?? 1);
    const pageSize = Math.min(
      MAX_PARTNER_OFFER_PAGE_SIZE,
      Math.max(1, input.pageSize ?? DEFAULT_PARTNER_OFFER_PAGE_SIZE),
    );
    const skip = (page - 1) * pageSize;
    const now = new Date();

    const where = {
      userId: user.userId,
      offer: {
        status: 'active' as const,
        partnerCompany: { status: 'active' as const },
        OR: [
          { availableUntil: null },
          { availableUntil: { gte: now } },
        ],
      },
    };

    const [rows, total] = await Promise.all([
      this.prisma.savedPartnerOffer.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip,
        take: pageSize,
        select: {
          offer: {
            select: {
              id: true,
              partnerCompanyId: true,
              title: true,
              teaserText: true,
              description: true,
              offerType: true,
              status: true,
              redemptionInstructions: true,
              terms: true,
              percentageDiscount: true,
              fixedDiscountMinorUnits: true,
              currencyCode: true,
              availableFrom: true,
              availableUntil: true,
              activatedAt: true,
              pausedAt: true,
              endedAt: true,
              createdByUserId: true,
              updatedByUserId: true,
              createdAt: true,
              updatedAt: true,
              partnerCompany: { select: { companyName: true } },
            },
          },
        },
      }),
      this.prisma.savedPartnerOffer.count({ where }),
    ]);

    return {
      offers: rows.map((r) =>
        toMemberDetail(
          { ...r.offer, discountCode: null },
          r.offer.partnerCompany.companyName,
        ),
      ),
      page,
      pageSize,
      total,
      hasNext: skip + rows.length < total,
    };
  }

  // -----------------------------------------------------------------------
  // Admin: list all offers
  // -----------------------------------------------------------------------

  async listAdminOffers(input: ListAdminOffersInput = {}): Promise<{
    offers: AdminPartnerOfferSummary[];
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  }> {
    const page = Math.max(1, input.page ?? 1);
    const pageSize = Math.min(
      MAX_PARTNER_OFFER_PAGE_SIZE,
      Math.max(1, input.pageSize ?? DEFAULT_PARTNER_OFFER_PAGE_SIZE),
    );
    const skip = (page - 1) * pageSize;

    const where: Record<string, unknown> = {};
    if (input.status) where.status = input.status;
    if (input.partnerId) where.partnerCompanyId = input.partnerId;

    const [rows, total] = await Promise.all([
      this.prisma.partnerOffer.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip,
        take: pageSize,
        select: {
          id: true,
          partnerCompanyId: true,
          title: true,
          teaserText: true,
          description: true,
          offerType: true,
          status: true,
          redemptionInstructions: true,
          terms: true,
          percentageDiscount: true,
          fixedDiscountMinorUnits: true,
          currencyCode: true,
          availableFrom: true,
          availableUntil: true,
          activatedAt: true,
          pausedAt: true,
          endedAt: true,
          createdByUserId: true,
          updatedByUserId: true,
          createdAt: true,
          updatedAt: true,
          partnerCompany: { select: { companyName: true } },
        },
      }),
      this.prisma.partnerOffer.count({ where }),
    ]);

    return {
      offers: rows.map((r) =>
        toAdminSummary({ ...r, discountCode: null }, r.partnerCompany.companyName),
      ),
      page,
      pageSize,
      total,
      hasNext: skip + rows.length < total,
    };
  }

  // -----------------------------------------------------------------------
  // Admin: get single offer detail (discountCode NOT included in response)
  // -----------------------------------------------------------------------

  async getAdminOfferDetail(offerId: string): Promise<AdminPartnerOfferDetail> {
    const row = await this.prisma.partnerOffer.findUnique({
      where: { id: offerId },
      select: {
        id: true,
        partnerCompanyId: true,
        title: true,
        teaserText: true,
        description: true,
        offerType: true,
        status: true,
        redemptionInstructions: true,
        terms: true,
        percentageDiscount: true,
        fixedDiscountMinorUnits: true,
        currencyCode: true,
        availableFrom: true,
        availableUntil: true,
        activatedAt: true,
        pausedAt: true,
        endedAt: true,
        createdByUserId: true,
        updatedByUserId: true,
        createdAt: true,
        updatedAt: true,
        partnerCompany: { select: { companyName: true } },
      },
    });

    if (!row) {
      throw new AppError(404, 'not_found', 'Offer not found.');
    }

    // discountCode intentionally excluded from admin detail response
    return toAdminDetail({ ...row, discountCode: null }, row.partnerCompany.companyName);
  }

  // -----------------------------------------------------------------------
  // Admin: create draft offer (always starts as draft)
  // -----------------------------------------------------------------------

  async createOffer(input: CreateOfferInput): Promise<AdminPartnerOfferDetail> {
    validateDiscountFields(input);
    validateDateRange(input);

    const partner = await this.prisma.partnerCompany.findUnique({
      where: { id: input.partnerId },
      select: { id: true, companyName: true },
    });

    if (!partner) {
      throw new AppError(404, 'not_found', 'Partner company not found.');
    }

    const offer = await this.prisma.partnerOffer.create({
      data: {
        partnerCompanyId: input.partnerId,
        title: input.title.trim(),
        teaserText: input.teaserText.trim(),
        description: input.description.trim(),
        offerType: input.offerType,
        status: 'draft',
        discountCode: input.discountCode?.trim() ?? null,
        redemptionInstructions: input.redemptionInstructions?.trim() ?? null,
        terms: input.terms?.trim() ?? null,
        percentageDiscount: input.percentageDiscount ?? null,
        fixedDiscountMinorUnits: input.fixedDiscountMinorUnits ?? null,
        currencyCode: input.currencyCode?.trim().toUpperCase() ?? null,
        availableFrom: input.availableFrom ? new Date(input.availableFrom) : null,
        availableUntil: input.availableUntil ? new Date(input.availableUntil) : null,
        createdByUserId: input.actorUserId,
      },
    });

    await this.prisma.auditLog.create({
      data: this.buildAuditEntry({
        actorUserId: input.actorUserId,
        action: 'partner_offer.create',
        entityType: 'partner_offer',
        entityId: offer.id,
      }),
    });

    return toAdminDetail({ ...offer, discountCode: null }, partner.companyName);
  }

  // -----------------------------------------------------------------------
  // Admin: update draft or paused offer
  // -----------------------------------------------------------------------

  async updateOffer(input: UpdateOfferInput): Promise<AdminPartnerOfferDetail> {
    const offer = await this.prisma.partnerOffer.findUnique({
      where: { id: input.offerId },
      select: {
        id: true,
        status: true,
        percentageDiscount: true,
        fixedDiscountMinorUnits: true,
        currencyCode: true,
        availableFrom: true,
        availableUntil: true,
        partnerCompany: { select: { companyName: true } },
      },
    });

    if (!offer) {
      throw new AppError(404, 'not_found', 'Offer not found.');
    }

    if (!['draft', 'paused'].includes(offer.status)) {
      throw new AppError(
        409,
        'offer_invalid_status_for_update',
        `Cannot edit an offer in status "${offer.status}". Pause it first.`,
      );
    }

    // Validate using merged values
    const mergedPct =
      input.percentageDiscount !== undefined
        ? input.percentageDiscount
        : offer.percentageDiscount;
    const mergedFixed =
      input.fixedDiscountMinorUnits !== undefined
        ? input.fixedDiscountMinorUnits
        : offer.fixedDiscountMinorUnits;
    const mergedCurrency =
      input.currencyCode !== undefined ? input.currencyCode : offer.currencyCode;
    const mergedFrom =
      input.availableFrom !== undefined
        ? input.availableFrom
        : offer.availableFrom?.toISOString() ?? null;
    const mergedUntil =
      input.availableUntil !== undefined
        ? input.availableUntil
        : offer.availableUntil?.toISOString() ?? null;

    validateDiscountFields({
      percentageDiscount: mergedPct,
      fixedDiscountMinorUnits: mergedFixed,
      currencyCode: mergedCurrency,
    });
    validateDateRange({ availableFrom: mergedFrom, availableUntil: mergedUntil });

    const updated = await this.prisma.partnerOffer.update({
      where: { id: input.offerId },
      data: {
        ...(input.title !== undefined ? { title: input.title.trim() } : {}),
        ...(input.teaserText !== undefined ? { teaserText: input.teaserText.trim() } : {}),
        ...(input.description !== undefined ? { description: input.description.trim() } : {}),
        ...(input.offerType !== undefined ? { offerType: input.offerType } : {}),
        ...(input.redemptionInstructions !== undefined
          ? { redemptionInstructions: input.redemptionInstructions?.trim() ?? null }
          : {}),
        ...(input.terms !== undefined ? { terms: input.terms?.trim() ?? null } : {}),
        ...(input.discountCode !== undefined
          ? { discountCode: input.discountCode?.trim() ?? null }
          : {}),
        ...(input.percentageDiscount !== undefined
          ? { percentageDiscount: input.percentageDiscount }
          : {}),
        ...(input.fixedDiscountMinorUnits !== undefined
          ? { fixedDiscountMinorUnits: input.fixedDiscountMinorUnits }
          : {}),
        ...(input.currencyCode !== undefined
          ? { currencyCode: input.currencyCode?.trim().toUpperCase() ?? null }
          : {}),
        ...(input.availableFrom !== undefined
          ? { availableFrom: input.availableFrom ? new Date(input.availableFrom) : null }
          : {}),
        ...(input.availableUntil !== undefined
          ? { availableUntil: input.availableUntil ? new Date(input.availableUntil) : null }
          : {}),
        updatedByUserId: input.actorUserId,
      },
    });

    await this.prisma.auditLog.create({
      data: this.buildAuditEntry({
        actorUserId: input.actorUserId,
        action: 'partner_offer.update',
        entityType: 'partner_offer',
        entityId: input.offerId,
      }),
    });

    return toAdminDetail({ ...updated, discountCode: null }, offer.partnerCompany.companyName);
  }

  // -----------------------------------------------------------------------
  // Admin: activate offer
  // -----------------------------------------------------------------------

  async activateOffer(input: ActivateOfferInput): Promise<AdminPartnerOfferDetail> {
    if (!input.confirmed) {
      throw new AppError(
        422,
        'offer_activation_not_confirmed',
        'Activation must be explicitly confirmed.',
      );
    }

    const offer = await this.prisma.partnerOffer.findUnique({
      where: { id: input.offerId },
      select: {
        id: true,
        title: true,
        teaserText: true,
        description: true,
        offerType: true,
        status: true,
        discountCode: true,
        redemptionInstructions: true,
        terms: true,
        percentageDiscount: true,
        fixedDiscountMinorUnits: true,
        currencyCode: true,
        availableFrom: true,
        availableUntil: true,
        activatedAt: true,
        pausedAt: true,
        endedAt: true,
        createdByUserId: true,
        updatedByUserId: true,
        createdAt: true,
        updatedAt: true,
        partnerCompanyId: true,
        partnerCompany: { select: { companyName: true, status: true } },
      },
    });

    if (!offer) {
      throw new AppError(404, 'not_found', 'Offer not found.');
    }

    if (!['draft', 'paused'].includes(offer.status)) {
      throw new AppError(
        409,
        'offer_invalid_status_transition',
        `Cannot activate an offer in status "${offer.status}".`,
      );
    }

    if (offer.partnerCompany.status !== 'active') {
      throw new AppError(
        409,
        'offer_partner_not_active',
        'Partner company must be active before activating an offer.',
      );
    }

    requireValidActivationData(offer as OfferRow);

    const now = new Date();
    const [activated] = await this.prisma.$transaction([
      this.prisma.partnerOffer.update({
        where: { id: input.offerId },
        data: {
          status: 'active',
          activatedAt: offer.activatedAt ?? now,
          updatedByUserId: input.actorUserId,
        },
      }),
      this.prisma.auditLog.create({
        data: this.buildAuditEntry({
          actorUserId: input.actorUserId,
          action: 'partner_offer.activate',
          entityType: 'partner_offer',
          entityId: input.offerId,
        }),
      }),
    ]);

    return toAdminDetail(
      { ...activated, discountCode: null },
      offer.partnerCompany.companyName,
    );
  }

  // -----------------------------------------------------------------------
  // Admin: pause offer
  // -----------------------------------------------------------------------

  async pauseOffer(input: StatusActionInput): Promise<AdminPartnerOfferDetail> {
    if (!input.reason.trim()) {
      throw new AppError(422, 'offer_reason_required', 'A reason is required to pause an offer.');
    }

    const offer = await this.prisma.partnerOffer.findUnique({
      where: { id: input.offerId },
      select: {
        id: true,
        status: true,
        partnerCompany: { select: { companyName: true } },
      },
    });

    if (!offer) {
      throw new AppError(404, 'not_found', 'Offer not found.');
    }

    if (offer.status !== 'active') {
      throw new AppError(
        409,
        'offer_invalid_status_transition',
        `Cannot pause an offer in status "${offer.status}".`,
      );
    }

    const [paused] = await this.prisma.$transaction([
      this.prisma.partnerOffer.update({
        where: { id: input.offerId },
        data: {
          status: 'paused',
          pausedAt: new Date(),
          updatedByUserId: input.actorUserId,
        },
      }),
      this.prisma.auditLog.create({
        data: this.buildAuditEntry({
          actorUserId: input.actorUserId,
          action: 'partner_offer.pause',
          entityType: 'partner_offer',
          entityId: input.offerId,
          reason: input.reason,
        }),
      }),
    ]);

    return toAdminDetail({ ...paused, discountCode: null }, offer.partnerCompany.companyName);
  }

  // -----------------------------------------------------------------------
  // Admin: end offer
  // -----------------------------------------------------------------------

  async endOffer(input: StatusActionInput): Promise<AdminPartnerOfferDetail> {
    if (!input.reason.trim()) {
      throw new AppError(422, 'offer_reason_required', 'A reason is required to end an offer.');
    }

    const offer = await this.prisma.partnerOffer.findUnique({
      where: { id: input.offerId },
      select: {
        id: true,
        status: true,
        partnerCompany: { select: { companyName: true } },
      },
    });

    if (!offer) {
      throw new AppError(404, 'not_found', 'Offer not found.');
    }

    if (offer.status === 'ended') {
      throw new AppError(409, 'offer_invalid_status_transition', 'Offer has already ended.');
    }

    const [ended] = await this.prisma.$transaction([
      this.prisma.partnerOffer.update({
        where: { id: input.offerId },
        data: {
          status: 'ended',
          endedAt: new Date(),
          updatedByUserId: input.actorUserId,
        },
      }),
      this.prisma.auditLog.create({
        data: this.buildAuditEntry({
          actorUserId: input.actorUserId,
          action: 'partner_offer.end',
          entityType: 'partner_offer',
          entityId: input.offerId,
          reason: input.reason,
        }),
      }),
    ]);

    return toAdminDetail({ ...ended, discountCode: null }, offer.partnerCompany.companyName);
  }

  // -----------------------------------------------------------------------
  // Private helpers
  // -----------------------------------------------------------------------

  private buildAuditEntry(input: WriteAuditLogInput) {
    return {
      actorUserId: input.actorUserId,
      action: input.action,
      entityType: input.entityType,
      entityId: input.entityId ?? null,
      reason: input.reason ?? null,
      // discountCode and redemptionInstructions NEVER included in metadata
    };
  }
}
