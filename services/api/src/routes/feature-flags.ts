import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

const featureFlagsQuerySchema = z.object({
  page: z.coerce.number().int().min(1).default(1),
  pageSize: z.coerce.number().int().min(1).max(50).default(20),
});

const placeholderFeatureFlags = [
  {
    key: 'chat',
    enabled: false,
    description: 'Reserved for a future gated community chat rollout.',
  },
  {
    key: 'live_location',
    enabled: false,
    description: 'Reserved for future live location rollout behind backend checks.',
  },
  {
    key: 'partner_statistics',
    enabled: false,
    description: 'Reserved for future privacy-safe partner reporting.',
  },
] as const;

export async function registerFeatureFlagRoutes(app: FastifyInstance): Promise<void> {
  app.get('/v1/feature-flags', async (request) => {
    const { page, pageSize } = featureFlagsQuerySchema.parse(request.query);
    const start = (page - 1) * pageSize;
    const data = placeholderFeatureFlags.slice(start, start + pageSize);

    return {
      ok: true,
      data,
      meta: {
        page,
        pageSize,
        total: placeholderFeatureFlags.length,
        hasNext: start + pageSize < placeholderFeatureFlags.length,
      },
    };
  });
}
