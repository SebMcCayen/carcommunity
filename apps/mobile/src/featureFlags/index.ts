/**
 * Feature flags client for the mobile app.
 *
 * Provides offline-safe defaults and a placeholder loader that fetches
 * flags from the API without blocking app startup.
 */

import {
  DEFAULT_FEATURE_FLAGS,
  type FeatureFlagKey,
  type FeatureFlags,
  type FeatureFlagResponse,
} from '@carcommunity/shared/feature-flags';

export { DEFAULT_FEATURE_FLAGS };
export type { FeatureFlagKey, FeatureFlags };

/**
 * Attempts to load feature flags from the API.
 * Returns the static defaults if the request fails or times out so that app
 * startup is never blocked by a network issue.
 *
 * @param apiBaseUrl - Base URL of the backend API (e.g. "https://api.example.com")
 */
export async function loadFeatureFlags(apiBaseUrl: string): Promise<FeatureFlags> {
  try {
    const url = `${apiBaseUrl.replace(/\/$/, '')}/v1/feature-flags`;
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 3_000);

    try {
      const response = await fetch(url, { signal: controller.signal });

      if (!response.ok) {
        return DEFAULT_FEATURE_FLAGS;
      }

      const body = (await response.json()) as FeatureFlagResponse;
      return body.data.flags;
    } finally {
      clearTimeout(timeoutId);
    }
  } catch {
    // Network error or parse failure — fall back to defaults.
    return DEFAULT_FEATURE_FLAGS;
  }
}
