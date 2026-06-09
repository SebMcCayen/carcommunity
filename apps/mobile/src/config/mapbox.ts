/**
 * Mapbox access token configuration helper.
 *
 * Reads the Mapbox public access token from the EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN
 * environment variable, which is inlined by Metro at build time.
 *
 * Security rules:
 *  - Do NOT log the token value.
 *  - Do NOT hardcode real tokens in source code.
 *  - Set the token in .env (EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN=pk.eyJ…) for local dev.
 *  - For EAS builds, set it via EAS Secrets or the build environment.
 *
 * TODO: For production builds, supply EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN via EAS Secrets
 *       or your CI environment variables. Never commit real tokens.
 */

/** Token value inlined by Metro at build time from EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN. */
const MAPBOX_TOKEN: string = process.env.EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN ?? '';

/**
 * Returns the Mapbox public access token.
 *
 * Returns an empty string and emits a development-only warning if the token
 * is not configured. Does NOT throw — the map screen degrades gracefully.
 *
 * @returns The Mapbox access token, or an empty string if not configured.
 */
export function getMapboxAccessToken(): string {
  if (!MAPBOX_TOKEN) {
    // Warn in development only. Never log the token value.
    if (__DEV__) {
      console.warn(
        '[Maps] EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN is not set. ' +
          'The map will not render correctly. ' +
          'Add the token to your .env file and rebuild the app.',
      );
    }
    return '';
  }
  return MAPBOX_TOKEN;
}

/**
 * Returns true if the Mapbox access token appears to be configured.
 *
 * Does NOT validate the token format or make any network call.
 */
export function isMapboxTokenConfigured(): boolean {
  return MAPBOX_TOKEN.length > 0;
}
