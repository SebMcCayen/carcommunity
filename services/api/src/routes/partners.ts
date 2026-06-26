/**
 * Partner (KCC Företagspartner) API routes.
 *
 * Routes:
 *  POST /v1/partner-applications           — submit an application (authenticated user)
 *
 *  GET  /v1/partners                       — list active public partners (any auth)
 *  GET  /v1/partners/map-markers           — active partner map markers (any auth)
 *  GET  /v1/partners/:partnerId            — active public partner detail (any auth)
 *
 *  GET  /v1/admin/partner-applications     — list applications (admin)
 *  GET  /v1/admin/partner-applications/:id — application detail (admin)
 *  POST /v1/admin/partner-applications/:id/start-review — start review (admin)
 *  POST /v1/admin/partner-applications/:id/approve      — approve application (admin)
 *  POST /v1/admin/partner-applications/:id/reject       — reject application (admin)
 *
 *  GET  /v1/admin/partners                 — list all companies (admin)
 *  GET  /v1/admin/partners/:id             — company detail (admin)
 *  POST /v1/admin/partners                 — create draft company (admin)
 *  PATCH /v1/admin/partners/:id            — update draft/paused company (admin)
 *  POST /v1/admin/partners/:id/activate    — activate company (admin)
 *  POST /v1/admin/partners/:id/pause       — pause company (admin)
 *  POST /v1/admin/partners/:id/end         — end partnership (admin)
 *
 * Access control:
 *  - Application submission:  requireAuthHook (active authenticated user).
 *  - Public partner routes:   no auth required.
 *  - Admin routes:            requireAdminHook (admin or owner role).
 *
 * Privacy:
 *  - Public routes never expose application contacts, admin notes, review reasons,
 *    billing data, or audit metadata.
 *  - Admin routes must not be called by mobile clients.
 *  - Submitted text is validated as plain text — never rendered as HTML.
 *  - Unknown request fields are rejected (.strict()).
 */

import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import {
  PARTNER_CATEGORIES,
  PARTNER_APPLICATION_STATUSES,
  PARTNER_COMPANY_STATUSES,
  PARTNER_ROUTE_PATHS,
  buildPartnerPath,
  buildAdminApplicationPath,
  buildAdminApplicationStartReviewPath,
  buildAdminApplicationApprovePath,
  buildAdminApplicationRejectPath,
  buildAdminPartnerPath,
  buildAdminPartnerActivatePath,
  buildAdminPartnerPausePath,
  buildAdminPartnerEndPath,
  MAX_PARTNER_COMPANY_NAME_LENGTH,
  MAX_PARTNER_CONTACT_NAME_LENGTH,
  MAX_PARTNER_EMAIL_LENGTH,
  MAX_PARTNER_DESCRIPTION_LENGTH,
  MAX_PARTNER_MESSAGE_LENGTH,
  MAX_PARTNER_ADDRESS_LENGTH,
  MAX_PARTNER_PHONE_LENGTH,
  MAX_PARTNER_WEBSITE_URL_LENGTH,
  DEFAULT_PARTNER_PAGE_SIZE,
  MAX_PARTNER_PAGE_SIZE,
  type PaginatedPartnerCompaniesResponse,
  type PartnerMapMarkersResponse,
  type PartnerCompanyPublicDetailResponse,
  type PartnerApplicationAckResponse,
  type PaginatedAdminPartnerApplicationsResponse,
  type AdminPartnerApplicationDetailResponse,
  type PaginatedAdminPartnerCompaniesResponse,
  type AdminPartnerCompanyDetailResponse,
} from '@carcommunity/shared/partners';

import { requireAuthHook, requireAdminHook } from '../lib/auth-context.js';
import { AppError } from '../lib/errors.js';
import { PartnerApplicationService } from '../lib/partner-application-service.js';
import { PartnerCompanyService } from '../lib/partner-company-service.js';

// ---------------------------------------------------------------------------
// Validation helpers
// ---------------------------------------------------------------------------

const urlSchema = z.string().max(MAX_PARTNER_WEBSITE_URL_LENGTH).url().refine(
  (v) => v.startsWith('http://') || v.startsWith('https://'),
  { message: 'URL must use http or https.' },
);

const paginationSchema = z
  .object({
    page: z.string().optional().transform((v) => (v ? Math.max(1, parseInt(v, 10) || 1) : 1)),
    pageSize: z.string().optional().transform((v) =>
      v
        ? Math.min(MAX_PARTNER_PAGE_SIZE, Math.max(1, parseInt(v, 10) || DEFAULT_PARTNER_PAGE_SIZE))
        : DEFAULT_PARTNER_PAGE_SIZE,
    ),
  })
  .strict();

const viewportQuerySchema = z
  .object({
    page: z.string().optional().transform((v) => (v ? Math.max(1, parseInt(v, 10) || 1) : 1)),
    pageSize: z.string().optional().transform((v) =>
      v
        ? Math.min(MAX_PARTNER_PAGE_SIZE, Math.max(1, parseInt(v, 10) || DEFAULT_PARTNER_PAGE_SIZE))
        : DEFAULT_PARTNER_PAGE_SIZE,
    ),
    category: z.enum(PARTNER_CATEGORIES).optional(),
    minLat: z.string().optional().transform((v) => (v ? parseFloat(v) : undefined)),
    maxLat: z.string().optional().transform((v) => (v ? parseFloat(v) : undefined)),
    minLon: z.string().optional().transform((v) => (v ? parseFloat(v) : undefined)),
    maxLon: z.string().optional().transform((v) => (v ? parseFloat(v) : undefined)),
  })
  .strict();

const submitApplicationBodySchema = z
  .object({
    companyName: z.string().min(1).max(MAX_PARTNER_COMPANY_NAME_LENGTH),
    organizationNumber: z.string().max(30).nullable().optional(),
    category: z.enum(PARTNER_CATEGORIES),
    contactName: z.string().min(1).max(MAX_PARTNER_CONTACT_NAME_LENGTH),
    contactEmail: z.string().email().max(MAX_PARTNER_EMAIL_LENGTH),
    contactPhone: z.string().max(MAX_PARTNER_PHONE_LENGTH).nullable().optional(),
    websiteUrl: urlSchema.nullable().optional(),
    proposedDescription: z.string().max(MAX_PARTNER_DESCRIPTION_LENGTH).nullable().optional(),
    proposedAddress: z.string().max(MAX_PARTNER_ADDRESS_LENGTH).nullable().optional(),
    message: z.string().max(MAX_PARTNER_MESSAGE_LENGTH).nullable().optional(),
  })
  .strict();

const rejectBodySchema = z
  .object({
    reason: z.string().min(1).max(500),
  })
  .strict();

const adminCreatePartnerBodySchema = z
  .object({
    companyName: z.string().min(1).max(MAX_PARTNER_COMPANY_NAME_LENGTH),
    category: z.enum(PARTNER_CATEGORIES),
    publicDescription: z.string().min(1).max(MAX_PARTNER_DESCRIPTION_LENGTH),
    address: z.string().min(1).max(MAX_PARTNER_ADDRESS_LENGTH),
    latitude: z.number().gte(-90).lte(90),
    longitude: z.number().gte(-180).lte(180),
    publicPhone: z.string().max(MAX_PARTNER_PHONE_LENGTH).nullable().optional(),
    publicWebsiteUrl: urlSchema.nullable().optional(),
    applicationId: z.string().uuid().nullable().optional(),
  })
  .strict();

const adminUpdatePartnerBodySchema = z
  .object({
    companyName: z.string().min(1).max(MAX_PARTNER_COMPANY_NAME_LENGTH).optional(),
    category: z.enum(PARTNER_CATEGORIES).optional(),
    publicDescription: z.string().min(1).max(MAX_PARTNER_DESCRIPTION_LENGTH).optional(),
    address: z.string().min(1).max(MAX_PARTNER_ADDRESS_LENGTH).optional(),
    latitude: z.number().gte(-90).lte(90).optional(),
    longitude: z.number().gte(-180).lte(180).optional(),
    publicPhone: z.string().max(MAX_PARTNER_PHONE_LENGTH).nullable().optional(),
    publicWebsiteUrl: urlSchema.nullable().optional(),
  })
  .strict();

const adminActivateBodySchema = z
  .object({
    actualLocationConfirmed: z.boolean().refine((v) => v === true, {
      message: 'Location confirmation is required.',
    }),
  })
  .strict();

const adminStatusActionBodySchema = z
  .object({
    reason: z.string().max(500).nullable().optional(),
  })
  .strict();

const partnerIdParamsSchema = z.object({ partnerId: z.string().uuid() }).strict();
const applicationIdParamsSchema = z.object({ applicationId: z.string().uuid() }).strict();

const adminListApplicationsQuerySchema = z
  .object({
    page: z.string().optional().transform((v) => (v ? Math.max(1, parseInt(v, 10) || 1) : 1)),
    pageSize: z.string().optional().transform((v) =>
      v
        ? Math.min(MAX_PARTNER_PAGE_SIZE, Math.max(1, parseInt(v, 10) || DEFAULT_PARTNER_PAGE_SIZE))
        : DEFAULT_PARTNER_PAGE_SIZE,
    ),
    status: z.enum(PARTNER_APPLICATION_STATUSES).optional(),
  })
  .strict();

const adminListPartnersQuerySchema = z
  .object({
    page: z.string().optional().transform((v) => (v ? Math.max(1, parseInt(v, 10) || 1) : 1)),
    pageSize: z.string().optional().transform((v) =>
      v
        ? Math.min(MAX_PARTNER_PAGE_SIZE, Math.max(1, parseInt(v, 10) || DEFAULT_PARTNER_PAGE_SIZE))
        : DEFAULT_PARTNER_PAGE_SIZE,
    ),
    status: z.enum(PARTNER_COMPANY_STATUSES).optional(),
    category: z.enum(PARTNER_CATEGORIES).optional(),
  })
  .strict();

// ---------------------------------------------------------------------------
// Dependencies
// ---------------------------------------------------------------------------

export interface RegisterPartnerRoutesDependencies {
  partnerApplicationService?: PartnerApplicationService;
  partnerCompanyService?: PartnerCompanyService;
}

// ---------------------------------------------------------------------------
// Route registration
// ---------------------------------------------------------------------------

export async function registerPartnerRoutes(
  app: FastifyInstance,
  dependencies: RegisterPartnerRoutesDependencies = {},
): Promise<void> {
  const applicationService =
    dependencies.partnerApplicationService ?? new PartnerApplicationService(app.prisma);
  const companyService =
    dependencies.partnerCompanyService ?? new PartnerCompanyService(app.prisma);

  // =========================================================================
  // Application routes
  // =========================================================================

  /**
   * POST /v1/partner-applications
   *
   * Submit a partner application. Requires an active authenticated session.
   * Apply rate limiting (global) and duplicate-submission detection.
   * Returns an opaque application ID — never contact details or admin data.
   */
  app.post(
    PARTNER_ROUTE_PATHS.submitApplication,
    { preHandler: requireAuthHook },
    async (request): Promise<PartnerApplicationAckResponse> => {
      const auth = request.auth!;
      const body = submitApplicationBodySchema.parse(request.body);

      const result = await applicationService.submitApplication({
        ...body,
        submittedByUserId: auth.userId,
      });

      return {
        ok: true,
        data: {
          applicationId: result.applicationId,
          status: 'submitted',
          submittedAt: result.submittedAt,
        },
      };
    },
  );

  // =========================================================================
  // Public partner routes
  // =========================================================================

  /**
   * GET /v1/partners
   *
   * Lists active public partners. No auth required.
   * Supports optional category filter and bounding-box viewport filter.
   * Returns public-safe fields only.
   */
  app.get(
    PARTNER_ROUTE_PATHS.partners,
    async (request): Promise<PaginatedPartnerCompaniesResponse> => {
      const query = viewportQuerySchema.parse(request.query);

      const result = await companyService.listActivePartners({
        page: query.page,
        pageSize: query.pageSize,
        category: query.category,
        minLat: query.minLat,
        maxLat: query.maxLat,
        minLon: query.minLon,
        maxLon: query.maxLon,
      });

      return {
        ok: true,
        data: { partners: result.partners },
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
   * GET /v1/partners/map-markers
   *
   * Returns minimal map-marker data for all active partners.
   * No auth required. Bounded at MAX_MAP_MARKERS results.
   * Returns only: partnerId, companyName, category, lat, lon, label.
   */
  app.get(
    PARTNER_ROUTE_PATHS.partnerMapMarkers,
    async (): Promise<PartnerMapMarkersResponse> => {
      const markers = await companyService.getMapMarkers();
      return { ok: true, data: { markers } };
    },
  );

  /**
   * GET /v1/partners/:partnerId
   *
   * Returns public detail for a single active partner.
   * 404 if not found or not active.
   */
  app.get(
    buildPartnerPath(':partnerId'),
    async (request): Promise<PartnerCompanyPublicDetailResponse> => {
      const params = partnerIdParamsSchema.parse(request.params);
      const partner = await companyService.getActivePartnerDetail(params.partnerId);
      return { ok: true, data: partner };
    },
  );

  // =========================================================================
  // Admin application routes
  // =========================================================================

  /**
   * GET /v1/admin/partner-applications
   *
   * Lists partner applications with optional status filter.
   * Returns internal contact details — admin only.
   */
  app.get(
    PARTNER_ROUTE_PATHS.adminApplications,
    { preHandler: requireAdminHook },
    async (request): Promise<PaginatedAdminPartnerApplicationsResponse> => {
      const query = adminListApplicationsQuerySchema.parse(request.query);

      const result = await applicationService.listApplications({
        page: query.page,
        pageSize: query.pageSize,
        status: query.status,
      });

      return {
        ok: true,
        data: { applications: result.applications },
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
   * GET /v1/admin/partner-applications/:applicationId
   *
   * Returns full application detail including contact information.
   * Admin only.
   */
  app.get(
    buildAdminApplicationPath(':applicationId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminPartnerApplicationDetailResponse> => {
      const params = applicationIdParamsSchema.parse(request.params);
      const detail = await applicationService.getApplicationDetail(params.applicationId);
      return { ok: true, data: detail };
    },
  );

  /**
   * POST /v1/admin/partner-applications/:applicationId/start-review
   *
   * Moves a submitted application to under_review.
   * Admin only.
   */
  app.post(
    buildAdminApplicationStartReviewPath(':applicationId'),
    { preHandler: requireAdminHook },
    async (request): Promise<{ ok: true }> => {
      const auth = request.auth!;
      const params = applicationIdParamsSchema.parse(request.params);

      await applicationService.startReview({
        actorUserId: auth.userId,
        applicationId: params.applicationId,
      });

      return { ok: true };
    },
  );

  /**
   * POST /v1/admin/partner-applications/:applicationId/approve
   *
   * Approves the application and creates a DRAFT partner company.
   * Public activation requires a separate explicit admin action.
   * Admin only.
   */
  app.post(
    buildAdminApplicationApprovePath(':applicationId'),
    { preHandler: requireAdminHook },
    async (request): Promise<{ ok: true; data: { partnerCompanyId: string } }> => {
      const auth = request.auth!;
      const params = applicationIdParamsSchema.parse(request.params);

      const result = await applicationService.approveApplication({
        actorUserId: auth.userId,
        applicationId: params.applicationId,
      });

      return { ok: true, data: result };
    },
  );

  /**
   * POST /v1/admin/partner-applications/:applicationId/reject
   *
   * Rejects the application. A reason is required.
   * Admin only.
   */
  app.post(
    buildAdminApplicationRejectPath(':applicationId'),
    { preHandler: requireAdminHook },
    async (request): Promise<{ ok: true }> => {
      const auth = request.auth!;
      const params = applicationIdParamsSchema.parse(request.params);
      const body = rejectBodySchema.parse(request.body);

      await applicationService.rejectApplication({
        actorUserId: auth.userId,
        applicationId: params.applicationId,
        reason: body.reason,
      });

      return { ok: true };
    },
  );

  // =========================================================================
  // Admin partner company routes
  // =========================================================================

  /**
   * GET /v1/admin/partners
   *
   * Lists all partner companies (all statuses). Admin only.
   */
  app.get(
    PARTNER_ROUTE_PATHS.adminPartners,
    { preHandler: requireAdminHook },
    async (request): Promise<PaginatedAdminPartnerCompaniesResponse> => {
      const query = adminListPartnersQuerySchema.parse(request.query);

      const result = await companyService.listAdminPartners({
        page: query.page,
        pageSize: query.pageSize,
        status: query.status,
        category: query.category,
      });

      return {
        ok: true,
        data: { partners: result.partners },
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
   * GET /v1/admin/partners/:partnerId
   *
   * Returns full admin detail for a partner company. Admin only.
   */
  app.get(
    buildAdminPartnerPath(':partnerId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminPartnerCompanyDetailResponse> => {
      const params = partnerIdParamsSchema.parse(request.params);
      const detail = await companyService.getAdminPartnerDetail(params.partnerId);
      return { ok: true, data: detail };
    },
  );

  /**
   * POST /v1/admin/partners
   *
   * Creates a draft partner company manually. Admin only.
   * New partners always start as draft.
   */
  app.post(
    PARTNER_ROUTE_PATHS.adminPartners,
    { preHandler: requireAdminHook },
    async (request): Promise<AdminPartnerCompanyDetailResponse> => {
      const auth = request.auth!;
      const body = adminCreatePartnerBodySchema.parse(request.body);

      const detail = await companyService.createDraftPartner({
        actorUserId: auth.userId,
        ...body,
      });

      return { ok: true, data: detail };
    },
  );

  /**
   * PATCH /v1/admin/partners/:partnerId
   *
   * Updates an existing draft or paused partner company.
   * Status changes are NOT allowed through this endpoint.
   * Admin only.
   */
  app.patch(
    buildAdminPartnerPath(':partnerId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminPartnerCompanyDetailResponse> => {
      const auth = request.auth!;
      const params = partnerIdParamsSchema.parse(request.params);
      const body = adminUpdatePartnerBodySchema.parse(request.body);

      const detail = await companyService.updatePartner({
        actorUserId: auth.userId,
        partnerId: params.partnerId,
        ...body,
      });

      return { ok: true, data: detail };
    },
  );

  /**
   * POST /v1/admin/partners/:partnerId/activate
   *
   * Activates a draft or paused partner. Requires location confirmation.
   * Validates all required fields before making the partner public.
   * Admin only.
   */
  app.post(
    buildAdminPartnerActivatePath(':partnerId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminPartnerCompanyDetailResponse> => {
      const auth = request.auth!;
      const params = partnerIdParamsSchema.parse(request.params);
      const body = adminActivateBodySchema.parse(request.body);

      const detail = await companyService.activatePartner({
        actorUserId: auth.userId,
        partnerId: params.partnerId,
        actualLocationConfirmed: body.actualLocationConfirmed,
      });

      return { ok: true, data: detail };
    },
  );

  /**
   * POST /v1/admin/partners/:partnerId/pause
   *
   * Pauses an active partner. Admin only.
   */
  app.post(
    buildAdminPartnerPausePath(':partnerId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminPartnerCompanyDetailResponse> => {
      const auth = request.auth!;
      const params = partnerIdParamsSchema.parse(request.params);
      const body = adminStatusActionBodySchema.parse(request.body);

      const detail = await companyService.pausePartner({
        actorUserId: auth.userId,
        partnerId: params.partnerId,
        reason: body.reason,
      });

      return { ok: true, data: detail };
    },
  );

  /**
   * POST /v1/admin/partners/:partnerId/end
   *
   * Ends a partnership. Admin only.
   * Cannot be reversed. Do not hard-delete.
   */
  app.post(
    buildAdminPartnerEndPath(':partnerId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminPartnerCompanyDetailResponse> => {
      const auth = request.auth!;
      const params = partnerIdParamsSchema.parse(request.params);
      const body = adminStatusActionBodySchema.parse(request.body);

      const detail = await companyService.endPartnership({
        actorUserId: auth.userId,
        partnerId: params.partnerId,
        reason: body.reason,
      });

      return { ok: true, data: detail };
    },
  );

  void buildPartnerPath; // used above in route registration
  void buildAdminApplicationPath;
  void buildAdminApplicationStartReviewPath;
  void buildAdminApplicationApprovePath;
  void buildAdminApplicationRejectPath;
  void buildAdminPartnerPath;
  void buildAdminPartnerActivatePath;
  void buildAdminPartnerPausePath;
  void buildAdminPartnerEndPath;
}
