/**
 * Digital billboard API routes.
 *
 * Routes:
 *  GET  /v1/digital-billboards              — public list (auth required, feature flag)
 *  GET  /v1/digital-billboards/map-markers  — public markers (auth required, feature flag)
 *  GET  /v1/digital-billboards/:billboardId — public detail (auth required, feature flag)
 *  POST /v1/digital-billboards/:billboardId/interactions — record interaction (auth required)
 *  GET  /v1/admin/digital-billboards                    — admin list
 *  GET  /v1/admin/digital-billboards/:billboardId       — admin detail
 *  POST /v1/admin/digital-billboards                    — admin create draft
 *  PATCH /v1/admin/digital-billboards/:billboardId      — admin update draft/paused
 *  POST /v1/admin/digital-billboards/:billboardId/activate — admin activate
 *  POST /v1/admin/digital-billboards/:billboardId/pause    — admin pause
 *  POST /v1/admin/digital-billboards/:billboardId/end      — admin end
 *
 * Privacy:
 *  - Public responses never include safetyNote, approvalReason, or internal metadata.
 *  - Admin GET routes remain accessible when feature flag is disabled.
 *  - Feature flag gates public routes only.
 */

import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import {
  DIGITAL_BILLBOARD_ROUTE_PATHS,
  BILLBOARD_PLACEMENT_TYPES,
  BILLBOARD_CTA_TYPES,
  BILLBOARD_INTERACTION_TYPES,
  DEFAULT_BILLBOARD_PAGE_SIZE,
  MAX_BILLBOARD_PAGE_SIZE,
  MAX_BILLBOARD_HEADLINE_LENGTH,
  MAX_BILLBOARD_MESSAGE_LENGTH,
  MAX_BILLBOARD_SAFETY_NOTE_LENGTH,
  MAX_BILLBOARD_CTA_VALUE_LENGTH,
  type PaginatedPublicBillboardsResponse,
  type PublicBillboardMapMarkersResponse,
  type PublicBillboardDetailResponse,
  type PaginatedAdminBillboardsResponse,
  type AdminBillboardDetailResponse,
  type AdminActivateBillboardResponse,
  type AdminPauseBillboardResponse,
  type AdminEndBillboardResponse,
  type RecordBillboardInteractionResponse,
} from '@carcommunity/shared/digital-billboards';

import { requireAuthHook, requireAdminHook } from '../lib/auth-context.js';
import { AppError } from '../lib/errors.js';
import { BillboardService } from '../lib/billboard-service.js';
import { PartnerInsightsService } from '../lib/partner-insights-service.js';

const paginationQuerySchema = z
  .object({
    page: z
      .string()
      .optional()
      .transform((value) => (value ? Math.max(1, parseInt(value, 10) || 1) : 1)),
    pageSize: z
      .string()
      .optional()
      .transform((value) =>
        value
          ? Math.min(MAX_BILLBOARD_PAGE_SIZE, Math.max(1, parseInt(value, 10) || DEFAULT_BILLBOARD_PAGE_SIZE))
          : DEFAULT_BILLBOARD_PAGE_SIZE,
      ),
  })
  .strict();

const billboardIdParamsSchema = z.object({ billboardId: z.string().uuid() }).strict();

const adminCreateBodySchema = z
  .object({
    partnerCompanyId: z.string().uuid(),
    headline: z.string().min(1).max(MAX_BILLBOARD_HEADLINE_LENGTH),
    message: z.string().min(1).max(MAX_BILLBOARD_MESSAGE_LENGTH),
    placementType: z.enum(BILLBOARD_PLACEMENT_TYPES),
    latitude: z.number().gte(-90).lte(90),
    longitude: z.number().gte(-180).lte(180),
    availableFrom: z.string().datetime({ offset: true }).nullable().optional(),
    availableUntil: z.string().datetime({ offset: true }).nullable().optional(),
    callToActionType: z.enum(BILLBOARD_CTA_TYPES).nullable().optional(),
    callToActionValue: z.string().max(MAX_BILLBOARD_CTA_VALUE_LENGTH).nullable().optional(),
    safetyNote: z.string().max(MAX_BILLBOARD_SAFETY_NOTE_LENGTH).nullable().optional(),
  })
  .strict();

const adminUpdateBodySchema = z
  .object({
    headline: z.string().min(1).max(MAX_BILLBOARD_HEADLINE_LENGTH).optional(),
    message: z.string().min(1).max(MAX_BILLBOARD_MESSAGE_LENGTH).optional(),
    placementType: z.enum(BILLBOARD_PLACEMENT_TYPES).optional(),
    latitude: z.number().gte(-90).lte(90).optional(),
    longitude: z.number().gte(-180).lte(180).optional(),
    availableFrom: z.string().datetime({ offset: true }).nullable().optional(),
    availableUntil: z.string().datetime({ offset: true }).nullable().optional(),
    callToActionType: z.enum(BILLBOARD_CTA_TYPES).nullable().optional(),
    callToActionValue: z.string().max(MAX_BILLBOARD_CTA_VALUE_LENGTH).nullable().optional(),
    safetyNote: z.string().max(MAX_BILLBOARD_SAFETY_NOTE_LENGTH).nullable().optional(),
  })
  .strict();

const adminActivateBodySchema = z
  .object({
    notBusinessLocationConfirmed: z.literal(true),
    notRoadLaneConfirmed: z.literal(true),
    notRoadSignConfirmed: z.literal(true),
    notObstructingMapConfirmed: z.literal(true),
    markedAsAdvertisingConfirmed: z.literal(true),
    suitableForMapConfirmed: z.literal(true),
    approvalReason: z.string().min(3).max(1000),
  })
  .strict();

const adminPauseBodySchema = z
  .object({
    reason: z.string().min(1).max(500),
  })
  .strict();

const adminEndBodySchema = z
  .object({
    reason: z.string().min(1).max(500),
  })
  .strict();

const interactionBodySchema = z
  .object({
    interactionType: z.enum(BILLBOARD_INTERACTION_TYPES),
    idempotencyKey: z.string().min(1).max(200).optional(),
  })
  .strict();

export interface RegisterDigitalBillboardRoutesDependencies {
  billboardService?: BillboardService;
  partnerInsightsService?: PartnerInsightsService;
  digitalBillboardsFeatureEnabled?: boolean;
}

export async function registerDigitalBillboardRoutes(
  app: FastifyInstance,
  dependencies: RegisterDigitalBillboardRoutesDependencies = {},
): Promise<void> {
  const billboardService = dependencies.billboardService ?? new BillboardService(app.prisma);
  const featureEnabled = dependencies.digitalBillboardsFeatureEnabled ?? true;

  app.get(
    DIGITAL_BILLBOARD_ROUTE_PATHS.list,
    { preHandler: [requireAuthHook] },
    async (request, reply) => {
      if (!featureEnabled) {
        throw new AppError(403, 'billboard_feature_disabled', 'Digital billboards feature is disabled.');
      }
      const query = paginationQuerySchema.parse(request.query);
      const result = await billboardService.listPublic({ page: query.page, pageSize: query.pageSize });

      const response: PaginatedPublicBillboardsResponse = {
        ok: true,
        data: { billboards: result.billboards },
        meta: {
          page: result.page,
          pageSize: result.pageSize,
          total: result.total,
          hasNext: result.hasNext,
        },
      };
      return reply.status(200).send(response);
    },
  );

  app.get(
    DIGITAL_BILLBOARD_ROUTE_PATHS.mapMarkers,
    { preHandler: [requireAuthHook] },
    async (request, reply) => {
      if (!featureEnabled) {
        throw new AppError(403, 'billboard_feature_disabled', 'Digital billboards feature is disabled.');
      }
      const query = paginationQuerySchema.parse(request.query);
      const result = await billboardService.listPublicMarkers({ page: query.page, pageSize: query.pageSize });

      const response: PublicBillboardMapMarkersResponse = {
        ok: true,
        data: { markers: result.markers, generatedAt: result.generatedAt },
        meta: {
          page: result.page,
          pageSize: result.pageSize,
          total: result.total,
          hasNext: result.hasNext,
        },
      };
      return reply.status(200).send(response);
    },
  );

  app.get(
    '/v1/digital-billboards/:billboardId',
    { preHandler: [requireAuthHook] },
    async (request, reply) => {
      if (!featureEnabled) {
        throw new AppError(403, 'billboard_feature_disabled', 'Digital billboards feature is disabled.');
      }
      const { billboardId } = billboardIdParamsSchema.parse(request.params);
      const detail = await billboardService.getPublicDetail(billboardId);

      const response: PublicBillboardDetailResponse = { ok: true, data: detail };
      return reply.status(200).send(response);
    },
  );

  app.post(
    '/v1/digital-billboards/:billboardId/interactions',
    { preHandler: [requireAuthHook] },
    async (request, reply) => {
      const { billboardId } = billboardIdParamsSchema.parse(request.params);
      const body = interactionBodySchema.parse(request.body);

      let partnerId: string;
      try {
        const detail = await billboardService.adminGetDetail(billboardId);
        partnerId = detail.partnerId;
      } catch {
        throw new AppError(404, 'not_found', 'Billboard not found.');
      }

      const partnerInsightsService = dependencies.partnerInsightsService;
      if (partnerInsightsService) {
        const typeMap = {
          navigate: 'navigate',
          phone: 'phone',
          website: 'website',
          offer_view: 'offer_view',
          open: 'profile_view',
          impression: 'map_view',
        } as const;
        const mappedType = typeMap[body.interactionType];
        if (mappedType) {
          try {
            await partnerInsightsService.recordInteraction({
              partnerCompanyId: partnerId,
              interactionType: mappedType,
              userId: request.auth?.userId,
              userStatus: request.auth?.status,
              idempotencyKey: body.idempotencyKey,
            });
          } catch (insightsErr) {
            // Analytics failure must never block the user's main action.
            // Log at debug level so the error is visible without alarming production monitors.
            app.log.debug({ err: insightsErr, billboardId, partnerId }, 'billboard interaction analytics failed');
          }
        }
      }

      const response: RecordBillboardInteractionResponse = {
        ok: true,
        data: { recorded: true },
      };
      return reply.status(200).send(response);
    },
  );

  app.get(
    DIGITAL_BILLBOARD_ROUTE_PATHS.adminList,
    { preHandler: [requireAdminHook] },
    async (request, reply) => {
      const query = paginationQuerySchema.parse(request.query);
      const result = await billboardService.adminList({ page: query.page, pageSize: query.pageSize });

      const response: PaginatedAdminBillboardsResponse = {
        ok: true,
        data: { billboards: result.billboards },
        meta: {
          page: result.page,
          pageSize: result.pageSize,
          total: result.total,
          hasNext: result.hasNext,
        },
      };
      return reply.status(200).send(response);
    },
  );

  app.get(
    '/v1/admin/digital-billboards/:billboardId',
    { preHandler: [requireAdminHook] },
    async (request, reply) => {
      const { billboardId } = billboardIdParamsSchema.parse(request.params);
      const detail = await billboardService.adminGetDetail(billboardId);

      const response: AdminBillboardDetailResponse = { ok: true, data: detail };
      return reply.status(200).send(response);
    },
  );

  app.post(
    DIGITAL_BILLBOARD_ROUTE_PATHS.adminList,
    { preHandler: [requireAdminHook] },
    async (request, reply) => {
      const body = adminCreateBodySchema.parse(request.body);
      const actorUserId = request.auth!.userId;

      const result = await billboardService.createDraft({
        actorUserId,
        ...body,
      });

      const response: AdminBillboardDetailResponse = { ok: true, data: result };
      return reply.status(201).send(response);
    },
  );

  app.patch(
    '/v1/admin/digital-billboards/:billboardId',
    { preHandler: [requireAdminHook] },
    async (request, reply) => {
      const { billboardId } = billboardIdParamsSchema.parse(request.params);
      const body = adminUpdateBodySchema.parse(request.body);
      const actorUserId = request.auth!.userId;

      const result = await billboardService.updateDraftOrPaused(billboardId, actorUserId, body);

      const response: AdminBillboardDetailResponse = { ok: true, data: result };
      return reply.status(200).send(response);
    },
  );

  app.post(
    '/v1/admin/digital-billboards/:billboardId/activate',
    { preHandler: [requireAdminHook] },
    async (request, reply) => {
      if (!featureEnabled) {
        throw new AppError(403, 'billboard_feature_disabled', 'Digital billboards feature is disabled.');
      }
      const { billboardId } = billboardIdParamsSchema.parse(request.params);
      const body = adminActivateBodySchema.parse(request.body);
      const actorUserId = request.auth!.userId;

      const result = await billboardService.activate(billboardId, actorUserId, body);

      const response: AdminActivateBillboardResponse = { ok: true, data: result };
      return reply.status(200).send(response);
    },
  );

  app.post(
    '/v1/admin/digital-billboards/:billboardId/pause',
    { preHandler: [requireAdminHook] },
    async (request, reply) => {
      const { billboardId } = billboardIdParamsSchema.parse(request.params);
      const body = adminPauseBodySchema.parse(request.body);
      const actorUserId = request.auth!.userId;

      const result = await billboardService.pause(billboardId, actorUserId, body.reason);

      const response: AdminPauseBillboardResponse = { ok: true, data: result };
      return reply.status(200).send(response);
    },
  );

  app.post(
    '/v1/admin/digital-billboards/:billboardId/end',
    { preHandler: [requireAdminHook] },
    async (request, reply) => {
      const { billboardId } = billboardIdParamsSchema.parse(request.params);
      const body = adminEndBodySchema.parse(request.body);
      const actorUserId = request.auth!.userId;

      const result = await billboardService.end(billboardId, actorUserId, body.reason);

      const response: AdminEndBillboardResponse = { ok: true, data: result };
      return reply.status(200).send(response);
    },
  );
}
