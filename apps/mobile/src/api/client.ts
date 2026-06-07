import { publicEnv } from '../config/env';
import type { FeatureFlagResponse } from '@carcommunity/shared/feature-flags';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

if (!base) {
  throw new Error('EXPO_PUBLIC_API_BASE_URL is not set. Set it in your .env file.');
}

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;

export const apiClient = {
  async health() {
    const response = await fetch(buildUrl('/health'));

    if (!response.ok) {
      throw new Error(`Health request failed with status ${response.status}`);
    }

    return response.json() as Promise<{ status: string }>;
  },

  async featureFlags(): Promise<FeatureFlagResponse> {
    const response = await fetch(buildUrl('/v1/feature-flags'));

    if (!response.ok) {
      throw new Error(`Feature flags request failed with status ${response.status}`);
    }

    return response.json() as Promise<FeatureFlagResponse>;
  },
};
