import type { FastifyInstance } from 'fastify';
import { DEFAULT_FEATURE_FLAGS, type FeatureFlagResponse } from '@carcommunity/shared/feature-flags';

// Fixed ISO timestamp for the static source.
// Update this when the static defaults change.
const STATIC_UPDATED_AT = '2025-01-01T00:00:00.000Z';

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

