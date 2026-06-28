/**
 * Partner Insights API routes.
 *
 * Routes:
 *  POST /v1/partners/:partnerId/interactions         — requireAuthHook
 *  GET  /v1/admin/partners/:partnerId/insights       — requireAdminHook
 *  GET  /v1/admin/partners/:partnerId/insights/summary — requireAdminHook
 *
 * Privacy rules:
 *  - Interaction recording does not accept userId, count, coordinates, or raw metadata.
 *  - Admin responses return aggregate metrics only.
 *  - Global rate limiting is already handled in registerSecurity.
 */

import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import {
  ADMIN_INSIGHTS_PERIODS,
  PARTNER_INSIGHTS_ROUTE_PATHS,
  PARTNER_INTERACTION_TYPES,
  buildAdminInsightsPath,
  buildAdminInsightsSummaryPath,
  type AdminPartnerInsightsResponse,
  type AdminPartnerInsightsSummaryResponse,
  type RecordPartnerInteractionResponse,
} from '@carcommunity/shared/partner-insights';

import { requireAdminHook, requireAuthHook } from '../lib/auth-context.js';
import { PartnerInsightsService } from '../lib/partner-insights-service.js';

const partnerIdParamsSchema = z.object({ partnerId: z.string().uuid() }).strict();

const recordInteractionBodySchema = z
  .object({
    interactionType: z.enum(PARTNER_INTERACTION_TYPES),
    relatedOfferId: z.string().uuid().optional(),
    idempotencyKey: z.string().min(1).max(120).optional(),
  })
  .strict();

const adminInsightsQuerySchema = z
  .object({
    period: z.enum(ADMIN_INSIGHTS_PERIODS).default('last_30_days'),
  })
  .strict();

export async function registerPartnerInsightsRoutes(
  app: FastifyInstance,
  deps: {
    partnerInsightsService?: PartnerInsightsService;
    config?: import('../config.js').AppConfig;
  } = {},
): Promise<void> {
  const partnerInsightsService =
    deps.partnerInsightsService ?? new PartnerInsightsService(app.prisma, deps.config);

  app.post(
    PARTNER_INSIGHTS_ROUTE_PATHS.recordInteraction,
    { preHandler: requireAuthHook },
    async (request): Promise<RecordPartnerInteractionResponse> => {
      const params = partnerIdParamsSchema.parse(request.params);
      const body = recordInteractionBodySchema.parse(request.body);
      const auth = request.auth!;

      const result = await partnerInsightsService.recordInteraction({
        partnerCompanyId: params.partnerId,
        interactionType: body.interactionType,
        userId: auth.userId,
        userStatus: auth.status,
        relatedOfferId: body.relatedOfferId,
        idempotencyKey: body.idempotencyKey,
      });

      return {
        ok: true,
        data: result,
      };
    },
  );

  app.get(
    buildAdminInsightsPath(':partnerId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminPartnerInsightsResponse> => {
      const params = partnerIdParamsSchema.parse(request.params);
      const query = adminInsightsQuerySchema.parse(request.query);
      const result = await partnerInsightsService.getAdminInsights({
        partnerId: params.partnerId,
        period: query.period,
      });

      return {
        ok: true,
        data: result,
      };
    },
  );

  app.get(
    buildAdminInsightsSummaryPath(':partnerId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminPartnerInsightsSummaryResponse> => {
      const params = partnerIdParamsSchema.parse(request.params);
      const query = adminInsightsQuerySchema.parse(request.query);
      const result = await partnerInsightsService.getAdminInsightsSummary({
        partnerId: params.partnerId,
        period: query.period,
      });

      return {
        ok: true,
        data: result,
      };
    },
  );
}
