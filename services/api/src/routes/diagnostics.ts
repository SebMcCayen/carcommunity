import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import { optionalAuthHook, requireAdminHook } from '../lib/auth-context.js';
import { fromUnknownError } from '../lib/errors.js';
import {
  DiagnosticsService,
  DIAGNOSTICS_SEVERITIES,
  DIAGNOSTICS_PLATFORMS,
  DIAGNOSTICS_FEATURE_AREAS,
} from '../lib/diagnostics-service.js';
import type {
  AdminDiagnosticsListResponse,
  DiagnosticsReportResponse,
} from '@carcommunity/shared/diagnostics';

const MAX_SAFE_MESSAGE_LENGTH = 2000;
const MAX_APP_VERSION_LENGTH = 50;
const MAX_BUILD_NUMBER_LENGTH = 50;
const MAX_OS_VERSION_LENGTH = 100;
const MAX_ERROR_CODE_LENGTH = 100;
const DEFAULT_PAGE_SIZE = 25;
const MAX_PAGE_SIZE = 100;

const diagnosticsReportBodySchema = z
  .object({
    severity: z.enum(DIAGNOSTICS_SEVERITIES),
    platform: z.enum(DIAGNOSTICS_PLATFORMS),
    featureArea: z.enum(DIAGNOSTICS_FEATURE_AREAS),
    safeMessage: z.string().min(1).max(MAX_SAFE_MESSAGE_LENGTH),
    appVersion: z.string().max(MAX_APP_VERSION_LENGTH).optional(),
    buildNumber: z.string().max(MAX_BUILD_NUMBER_LENGTH).optional(),
    osVersion: z.string().max(MAX_OS_VERSION_LENGTH).optional(),
    errorCode: z.string().max(MAX_ERROR_CODE_LENGTH).optional(),
    metadata: z.record(z.unknown()).optional(),
  })
  .strict();

const adminDiagnosticsQuerySchema = z
  .object({
    page: z.coerce.number().int().min(1).default(1),
    pageSize: z.coerce.number().int().min(1).max(MAX_PAGE_SIZE).default(DEFAULT_PAGE_SIZE),
  })
  .strict();

export interface RegisterDiagnosticsRoutesDependencies {
  diagnosticsService?: DiagnosticsService;
}

export async function registerDiagnosticsRoutes(
  app: FastifyInstance,
  dependencies: RegisterDiagnosticsRoutesDependencies = {},
): Promise<void> {
  const diagnosticsService = dependencies.diagnosticsService ?? new DiagnosticsService(app.prisma);

  /**
   * POST /v1/diagnostics/report
   *
   * Accepts a sanitized error/crash report from the app.
   * Authentication is optional — unauthenticated reports are stored without a userId.
   * If authenticated, the report is associated with the current user.
   *
   * Privacy guarantees enforced by the service layer:
   * - Metadata is sanitized to strip tokens, credentials, and coordinate fields.
   * - Stack traces are not stored.
   * - Raw headers are not stored.
   */
  app.post(
    '/v1/diagnostics/report',
    { preHandler: optionalAuthHook },
    async (request, reply): Promise<void> => {
      try {
        const body = diagnosticsReportBodySchema.parse(request.body);

        const result = await diagnosticsService.createReport({
          userId: request.auth?.userId ?? null,
          severity: body.severity,
          platform: body.platform,
          featureArea: body.featureArea,
          safeMessage: body.safeMessage,
          appVersion: body.appVersion ?? null,
          buildNumber: body.buildNumber ?? null,
          osVersion: body.osVersion ?? null,
          errorCode: body.errorCode ?? null,
          metadata: body.metadata ?? null,
        });

        await reply.code(201).send({
          ok: true,
          data: {
            id: result.id,
            fingerprint: result.fingerprint,
          },
        } satisfies DiagnosticsReportResponse);
      } catch (err) {
        const appError = fromUnknownError(err);
        await reply.code(appError.statusCode).send({
          ok: false,
          error: {
            code: appError.code,
            message: appError.message,
            ...(appError.details && appError.statusCode < 500
              ? { details: appError.details }
              : {}),
          },
        });
      }
    },
  );

  /**
   * GET /v1/admin/diagnostics
   *
   * Returns a paginated list of recent diagnostics reports.
   * Requires admin or owner role.
   *
   * Privacy guarantees:
   * - Metadata is excluded from the list view.
   * - No raw auth tokens, headers, exact coordinates, or raw logs are exposed.
   *
   * TODO: Add deduplication and grouping by fingerprint.
   * TODO: Add severity-based alerting thresholds.
   * TODO: Add GitHub Issue creation (future step only — no GitHub token here).
   * TODO: Add privacy review before exposing metadata to admin users.
   */
  app.get(
    '/v1/admin/diagnostics',
    { preHandler: requireAdminHook },
    async (request, reply): Promise<void> => {
      try {
        const query = adminDiagnosticsQuerySchema.parse(request.query);

        const { reports, total } = await diagnosticsService.listReports({
          page: query.page,
          pageSize: query.pageSize,
        });

        const hasNext = query.page * query.pageSize < total;

        await reply.code(200).send({
          ok: true,
          data: { reports },
          meta: {
            page: query.page,
            pageSize: query.pageSize,
            total,
            hasNext,
          },
        } satisfies AdminDiagnosticsListResponse);
      } catch (err) {
        const appError = fromUnknownError(err);
        await reply.code(appError.statusCode).send({
          ok: false,
          error: {
            code: appError.code,
            message: appError.message,
          },
        });
      }
    },
  );
}
