/**
 * Partner Offers API routes.
 *
 * Routes:
 *  GET  /v1/partners/offers/teasers              — any auth (teasers, no code)
 *  GET  /v1/partners/:partnerId/offers/teasers   — any auth (teasers for one partner)
 *
 *  GET  /v1/partner-offers                       — requireMemberHook
 *  GET  /v1/partner-offers/:offerId              — requireMemberHook
 *  POST /v1/partner-offers/:offerId/show-code    — requireMemberHook
 *  POST /v1/partner-offers/:offerId/save         — requireMemberHook
 *  DELETE /v1/partner-offers/:offerId/save       — requireMemberHook
 *  GET  /v1/users/me/saved-partner-offers        — requireMemberHook
 *
 *  GET  /v1/admin/partner-offers                 — requireAdminHook
 *  GET  /v1/admin/partner-offers/:offerId        — requireAdminHook
 *  POST /v1/admin/partners/:partnerId/offers     — requireAdminHook
 *  PATCH /v1/admin/partner-offers/:offerId       — requireAdminHook
 *  POST /v1/admin/partner-offers/:offerId/activate — requireAdminHook
 *  POST /v1/admin/partner-offers/:offerId/pause  — requireAdminHook
 *  POST /v1/admin/partner-offers/:offerId/end    — requireAdminHook
 *
 * Security:
 *  - discountCode NEVER appears in teaser or list/detail responses.
 *  - discountCode ONLY returned from the show-code endpoint.
 *  - Status cannot be set via create or update body.
 *  - Unknown request fields are rejected (.strict()).
 *  - All inputs are plain text — never rendered as HTML.
 */

import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import {
  PARTNER_OFFER_TYPES,
  PARTNER_OFFER_STATUSES,
  PARTNER_OFFER_ROUTE_PATHS,
  buildPartnerOfferTeasersPath,
  buildMemberOfferPath,
  buildMemberOfferShowCodePath,
  buildMemberOfferSavePath,
  buildAdminOfferPath,
  buildAdminOfferActivatePath,
  buildAdminOfferPausePath,
  buildAdminOfferEndPath,
  buildAdminCreateOfferPath,
  MAX_OFFER_TITLE_LENGTH,
  MAX_OFFER_TEASER_TEXT_LENGTH,
  MAX_OFFER_DESCRIPTION_LENGTH,
  MAX_OFFER_REDEMPTION_INSTRUCTIONS_LENGTH,
  MAX_OFFER_TERMS_LENGTH,
  MAX_OFFER_DISCOUNT_CODE_LENGTH,
  MAX_OFFER_PERCENTAGE_DISCOUNT,
  DEFAULT_PARTNER_OFFER_PAGE_SIZE,
  MAX_PARTNER_OFFER_PAGE_SIZE,
  type PaginatedPartnerOfferTeasersResponse,
  type PaginatedMemberPartnerOffersResponse,
  type PaginatedAdminPartnerOffersResponse,
  type AdminPartnerOfferDetailResponse,
  type ShowCodeResponse,
  type SaveOfferResponse,
} from '@carcommunity/shared/partner-offers';

import { requireAuthHook, requireAdminHook, requireMemberHook } from '../lib/auth-context.js';
import { PartnerOfferService } from '../lib/partner-offer-service.js';

// ---------------------------------------------------------------------------
// Validation schemas
// ---------------------------------------------------------------------------

const paginationQuerySchema = z
  .object({
    page: z.string().optional().transform((v) => (v ? Math.max(1, parseInt(v, 10) || 1) : 1)),
    pageSize: z
      .string()
      .optional()
      .transform((v) =>
        v
          ? Math.min(
              MAX_PARTNER_OFFER_PAGE_SIZE,
              Math.max(1, parseInt(v, 10) || DEFAULT_PARTNER_OFFER_PAGE_SIZE),
            )
          : DEFAULT_PARTNER_OFFER_PAGE_SIZE,
      ),
  })
  .strict();

const memberOffersQuerySchema = z
  .object({
    page: z.string().optional().transform((v) => (v ? Math.max(1, parseInt(v, 10) || 1) : 1)),
    pageSize: z
      .string()
      .optional()
      .transform((v) =>
        v
          ? Math.min(
              MAX_PARTNER_OFFER_PAGE_SIZE,
              Math.max(1, parseInt(v, 10) || DEFAULT_PARTNER_OFFER_PAGE_SIZE),
            )
          : DEFAULT_PARTNER_OFFER_PAGE_SIZE,
      ),
    partnerId: z.string().uuid().optional(),
  })
  .strict();

const teaserQuerySchema = z
  .object({
    page: z.string().optional().transform((v) => (v ? Math.max(1, parseInt(v, 10) || 1) : 1)),
    pageSize: z
      .string()
      .optional()
      .transform((v) =>
        v
          ? Math.min(
              MAX_PARTNER_OFFER_PAGE_SIZE,
              Math.max(1, parseInt(v, 10) || DEFAULT_PARTNER_OFFER_PAGE_SIZE),
            )
          : DEFAULT_PARTNER_OFFER_PAGE_SIZE,
      ),
  })
  .strict();

const adminListQuerySchema = z
  .object({
    page: z.string().optional().transform((v) => (v ? Math.max(1, parseInt(v, 10) || 1) : 1)),
    pageSize: z
      .string()
      .optional()
      .transform((v) =>
        v
          ? Math.min(
              MAX_PARTNER_OFFER_PAGE_SIZE,
              Math.max(1, parseInt(v, 10) || DEFAULT_PARTNER_OFFER_PAGE_SIZE),
            )
          : DEFAULT_PARTNER_OFFER_PAGE_SIZE,
      ),
    status: z.enum(PARTNER_OFFER_STATUSES).optional(),
    partnerId: z.string().uuid().optional(),
  })
  .strict();

const offerIdParamsSchema = z.object({ offerId: z.string().uuid() }).strict();
const partnerIdParamsSchema = z.object({ partnerId: z.string().uuid() }).strict();

const createOfferBodySchema = z
  .object({
    title: z.string().min(1).max(MAX_OFFER_TITLE_LENGTH),
    teaserText: z.string().min(1).max(MAX_OFFER_TEASER_TEXT_LENGTH),
    description: z.string().min(1).max(MAX_OFFER_DESCRIPTION_LENGTH),
    offerType: z.enum(PARTNER_OFFER_TYPES),
    redemptionInstructions: z
      .string()
      .max(MAX_OFFER_REDEMPTION_INSTRUCTIONS_LENGTH)
      .nullable()
      .optional(),
    terms: z.string().max(MAX_OFFER_TERMS_LENGTH).nullable().optional(),
    discountCode: z.string().max(MAX_OFFER_DISCOUNT_CODE_LENGTH).nullable().optional(),
    percentageDiscount: z.number().gt(0).max(MAX_OFFER_PERCENTAGE_DISCOUNT).nullable().optional(),
    fixedDiscountMinorUnits: z.number().int().min(0).nullable().optional(),
    currencyCode: z.string().length(3).nullable().optional(),
    availableFrom: z.string().datetime().nullable().optional(),
    availableUntil: z.string().datetime().nullable().optional(),
  })
  .strict();

const updateOfferBodySchema = z
  .object({
    title: z.string().min(1).max(MAX_OFFER_TITLE_LENGTH).optional(),
    teaserText: z.string().min(1).max(MAX_OFFER_TEASER_TEXT_LENGTH).optional(),
    description: z.string().min(1).max(MAX_OFFER_DESCRIPTION_LENGTH).optional(),
    offerType: z.enum(PARTNER_OFFER_TYPES).optional(),
    redemptionInstructions: z
      .string()
      .max(MAX_OFFER_REDEMPTION_INSTRUCTIONS_LENGTH)
      .nullable()
      .optional(),
    terms: z.string().max(MAX_OFFER_TERMS_LENGTH).nullable().optional(),
    discountCode: z.string().max(MAX_OFFER_DISCOUNT_CODE_LENGTH).nullable().optional(),
    percentageDiscount: z.number().gt(0).max(MAX_OFFER_PERCENTAGE_DISCOUNT).nullable().optional(),
    fixedDiscountMinorUnits: z.number().int().min(0).nullable().optional(),
    currencyCode: z.string().length(3).nullable().optional(),
    availableFrom: z.string().datetime().nullable().optional(),
    availableUntil: z.string().datetime().nullable().optional(),
  })
  .strict();

const activateBodySchema = z
  .object({
    confirmed: z.boolean().refine((v) => v === true, {
      message: 'Activation must be explicitly confirmed.',
    }),
  })
  .strict();

const pauseEndBodySchema = z
  .object({
    reason: z.string().min(1).max(500),
  })
  .strict();

// ---------------------------------------------------------------------------
// Route registration
// ---------------------------------------------------------------------------

export async function registerPartnerOfferRoutes(
  app: FastifyInstance,
  deps: { partnerOfferService?: PartnerOfferService } = {},
): Promise<void> {
  const service = deps.partnerOfferService ?? new PartnerOfferService(app.prisma);

  // =========================================================================
  // Teaser routes (any authenticated user — teasers are safe)
  // =========================================================================

  /**
   * GET /v1/partners/offers/teasers
   *
   * Lists active offer teasers from all active partners.
   * Returns safe fields only — no discountCode, no description.
   */
  app.get(
    PARTNER_OFFER_ROUTE_PATHS.teasers,
    { preHandler: requireAuthHook },
    async (request): Promise<PaginatedPartnerOfferTeasersResponse> => {
      const query = teaserQuerySchema.parse(request.query);

      const result = await service.listOfferTeasers({
        page: query.page,
        pageSize: query.pageSize,
      });

      return {
        ok: true,
        data: { offers: result.offers },
        meta: {
          page: result.page,
          pageSize: result.pageSize,
          total: result.total,
          hasNext: result.hasNext,
        },
      };
    },
  );

  /**
   * GET /v1/partners/:partnerId/offers/teasers
   *
   * Lists active offer teasers for a specific partner company.
   * Returns safe fields only — no discountCode, no description.
   */
  app.get(
    buildPartnerOfferTeasersPath(':partnerId'),
    { preHandler: requireAuthHook },
    async (request): Promise<PaginatedPartnerOfferTeasersResponse> => {
      const params = partnerIdParamsSchema.parse(request.params);
      const query = teaserQuerySchema.parse(request.query);

      const result = await service.listOfferTeasers({
        page: query.page,
        pageSize: query.pageSize,
        partnerId: params.partnerId,
      });

      return {
        ok: true,
        data: { offers: result.offers },
        meta: {
          page: result.page,
          pageSize: result.pageSize,
          total: result.total,
          hasNext: result.hasNext,
        },
      };
    },
  );

  // =========================================================================
  // Member routes (requireMemberHook — active member_monthly required)
  // =========================================================================

  /**
   * GET /v1/partner-offers
   *
   * Lists all currently active member offers across all active partners.
   * Optionally filtered by partnerId to show offers for a specific partner.
   * Full detail returned but discountCode is NOT included.
   */
  app.get(
    PARTNER_OFFER_ROUTE_PATHS.memberOffers,
    { preHandler: requireMemberHook },
    async (request): Promise<PaginatedMemberPartnerOffersResponse> => {
      const auth = request.auth!;
      const query = memberOffersQuerySchema.parse(request.query);

      const result = await service.listMemberOffers(
        {
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        { page: query.page, pageSize: query.pageSize, partnerId: query.partnerId },
      );

      return {
        ok: true,
        data: { offers: result.offers },
        meta: {
          page: result.page,
          pageSize: result.pageSize,
          total: result.total,
          hasNext: result.hasNext,
        },
      };
    },
  );

  /**
   * GET /v1/partner-offers/:offerId
   *
   * Returns full offer detail for an active offer.
   * discountCode is NOT included — use show-code for that.
   */
  app.get(
    buildMemberOfferPath(':offerId'),
    { preHandler: requireMemberHook },
    async (request): Promise<{ ok: true; data: import('@carcommunity/shared/partner-offers').MemberPartnerOfferDetail }> => {
      const auth = request.auth!;
      const params = offerIdParamsSchema.parse(request.params);

      const detail = await service.getMemberOfferDetail(
        {
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        params.offerId,
      );

      return { ok: true, data: detail };
    },
  );

  /**
   * POST /v1/partner-offers/:offerId/show-code
   *
   * Returns the discount code for an active offer.
   * Requires member subscription. Code is only returned here — never in list/detail.
   */
  app.post(
    buildMemberOfferShowCodePath(':offerId'),
    { preHandler: requireMemberHook },
    async (request): Promise<{ ok: true; data: ShowCodeResponse }> => {
      const auth = request.auth!;
      const params = offerIdParamsSchema.parse(request.params);

      const result = await service.showCode(
        {
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        params.offerId,
      );

      return { ok: true, data: result };
    },
  );

  /**
   * POST /v1/partner-offers/:offerId/save
   *
   * Saves an offer to the user's saved list. Idempotent.
   */
  app.post(
    buildMemberOfferSavePath(':offerId'),
    { preHandler: requireMemberHook },
    async (request): Promise<SaveOfferResponse> => {
      const auth = request.auth!;
      const params = offerIdParamsSchema.parse(request.params);

      const result = await service.saveOffer(
        {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        params.offerId,
      );

      return { ok: true, data: result };
    },
  );

  /**
   * DELETE /v1/partner-offers/:offerId/save
   *
   * Removes an offer from the user's saved list. Idempotent.
   */
  app.delete(
    buildMemberOfferSavePath(':offerId'),
    { preHandler: requireMemberHook },
    async (request): Promise<{ ok: true }> => {
      const auth = request.auth!;
      const params = offerIdParamsSchema.parse(request.params);

      await service.unsaveOffer(
        {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        params.offerId,
      );

      return { ok: true };
    },
  );

  /**
   * GET /v1/users/me/saved-partner-offers
   *
   * Lists saved offers for the current member.
   * discountCode is NOT included.
   */
  app.get(
    PARTNER_OFFER_ROUTE_PATHS.savedOffers,
    { preHandler: requireMemberHook },
    async (request): Promise<PaginatedMemberPartnerOffersResponse> => {
      const auth = request.auth!;
      const query = paginationQuerySchema.parse(request.query);

      const result = await service.listSavedOffers(
        {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        { page: query.page, pageSize: query.pageSize },
      );

      return {
        ok: true,
        data: { offers: result.offers },
        meta: {
          page: result.page,
          pageSize: result.pageSize,
          total: result.total,
          hasNext: result.hasNext,
        },
      };
    },
  );

  // =========================================================================
  // Admin routes (requireAdminHook — admin or owner role required)
  // =========================================================================

  /**
   * GET /v1/admin/partner-offers
   *
   * Lists all partner offers (all statuses) for admin view.
   * discountCode is NOT included in response.
   */
  app.get(
    PARTNER_OFFER_ROUTE_PATHS.adminOffers,
    { preHandler: requireAdminHook },
    async (request): Promise<PaginatedAdminPartnerOffersResponse> => {
      const query = adminListQuerySchema.parse(request.query);

      const result = await service.listAdminOffers({
        page: query.page,
        pageSize: query.pageSize,
        status: query.status,
        partnerId: query.partnerId,
      });

      return {
        ok: true,
        data: { offers: result.offers },
        meta: {
          page: result.page,
          pageSize: result.pageSize,
          total: result.total,
          hasNext: result.hasNext,
        },
      };
    },
  );

  /**
   * GET /v1/admin/partner-offers/:offerId
   *
   * Returns full admin detail for a single offer.
   * discountCode is intentionally NOT included.
   */
  app.get(
    buildAdminOfferPath(':offerId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminPartnerOfferDetailResponse> => {
      const params = offerIdParamsSchema.parse(request.params);
      const detail = await service.getAdminOfferDetail(params.offerId);
      return { ok: true, data: detail };
    },
  );

  /**
   * POST /v1/admin/partners/:partnerId/offers
   *
   * Creates a new offer in draft status.
   * Status cannot be set via this endpoint — always starts as draft.
   * Unknown fields are rejected.
   */
  app.post(
    buildAdminCreateOfferPath(':partnerId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminPartnerOfferDetailResponse> => {
      const auth = request.auth!;
      const params = partnerIdParamsSchema.parse(request.params);
      const body = createOfferBodySchema.parse(request.body);

      const detail = await service.createOffer({
        actorUserId: auth.userId,
        partnerId: params.partnerId,
        ...body,
      });

      return { ok: true, data: detail };
    },
  );

  /**
   * PATCH /v1/admin/partner-offers/:offerId
   *
   * Updates an existing draft or paused offer.
   * Status cannot be changed here — use dedicated action endpoints.
   * Unknown fields are rejected.
   */
  app.patch(
    buildAdminOfferPath(':offerId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminPartnerOfferDetailResponse> => {
      const auth = request.auth!;
      const params = offerIdParamsSchema.parse(request.params);
      const body = updateOfferBodySchema.parse(request.body);

      const detail = await service.updateOffer({
        actorUserId: auth.userId,
        offerId: params.offerId,
        ...body,
      });

      return { ok: true, data: detail };
    },
  );

  /**
   * POST /v1/admin/partner-offers/:offerId/activate
   *
   * Activates a draft or paused offer.
   * Requires confirmed=true, active partner company, and non-empty description.
   * Backend writes an audit entry.
   */
  app.post(
    buildAdminOfferActivatePath(':offerId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminPartnerOfferDetailResponse> => {
      const auth = request.auth!;
      const params = offerIdParamsSchema.parse(request.params);
      const body = activateBodySchema.parse(request.body);

      const detail = await service.activateOffer({
        actorUserId: auth.userId,
        offerId: params.offerId,
        confirmed: body.confirmed,
      });

      return { ok: true, data: detail };
    },
  );

  /**
   * POST /v1/admin/partner-offers/:offerId/pause
   *
   * Pauses an active offer. A non-empty reason is required.
   * Backend writes an audit entry.
   */
  app.post(
    buildAdminOfferPausePath(':offerId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminPartnerOfferDetailResponse> => {
      const auth = request.auth!;
      const params = offerIdParamsSchema.parse(request.params);
      const body = pauseEndBodySchema.parse(request.body);

      const detail = await service.pauseOffer({
        actorUserId: auth.userId,
        offerId: params.offerId,
        reason: body.reason,
      });

      return { ok: true, data: detail };
    },
  );

  /**
   * POST /v1/admin/partner-offers/:offerId/end
   *
   * Ends an offer permanently. A non-empty reason is required.
   * Backend writes an audit entry.
   */
  app.post(
    buildAdminOfferEndPath(':offerId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminPartnerOfferDetailResponse> => {
      const auth = request.auth!;
      const params = offerIdParamsSchema.parse(request.params);
      const body = pauseEndBodySchema.parse(request.body);

      const detail = await service.endOffer({
        actorUserId: auth.userId,
        offerId: params.offerId,
        reason: body.reason,
      });

      return { ok: true, data: detail };
    },
  );
}
