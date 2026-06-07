import type { FastifyInstance } from 'fastify';
import { DEFAULT_FEATURE_FLAGS, type FeatureFlagResponse } from '@carcommunity/shared/feature-flags';

// Fixed timestamp for the static source; will be replaced when a dynamic source is wired up.
const STATIC_UPDATED_AT = new Date().toISOString();

export async function registerFeatureFlagRoutes(app: FastifyInstance): Promise<void> {
  app.get('/v1/feature-flags', async (): Promise<FeatureFlagResponse> => {
    return {
      ok: true,
      data: {
        flags: DEFAULT_FEATURE_FLAGS,
        updatedAt: STATIC_UPDATED_AT,
        source: 'static',
      },
    };
  });
}

