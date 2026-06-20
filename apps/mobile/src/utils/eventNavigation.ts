/**
 * External map navigation utility.
 *
 * Opens Apple Maps on iOS and a geo: URI (Google Maps) on Android.
 * Uses event coordinates when available, falls back to address.
 *
 * Security notes:
 *   - Exact coordinates are never logged.
 *   - URL parameters are encoded before use.
 */

import { Linking, Platform } from 'react-native';

export interface EventNavigationData {
  /** Exact latitude — member-only, never log. */
  latitude: number | null;
  /** Exact longitude — member-only, never log. */
  longitude: number | null;
  /** Human-readable address. May be null. */
  address: string | null;
  /** Location name (venue). May be null. */
  locationName: string | null;
}

/**
 * Build the Apple Maps URL for iOS.
 * Uses coordinates when available, falls back to encoded address.
 */
function buildAppleMapsUrl(data: EventNavigationData): string {
  const hasCoords = data.latitude !== null && data.longitude !== null;
  if (hasCoords) {
    const label = encodeURIComponent(data.locationName ?? 'Event');
    return `https://maps.apple.com/?ll=${data.latitude},${data.longitude}&q=${label}`;
  }
  const query = encodeURIComponent(data.address ?? data.locationName ?? '');
  return `https://maps.apple.com/?q=${query}`;
}

/**
 * Build the geo URI for Android (opens Google Maps or any maps app).
 * Uses coordinates when available, falls back to encoded address.
 */
function buildGeoUri(data: EventNavigationData): string {
  const hasCoords = data.latitude !== null && data.longitude !== null;
  if (hasCoords) {
    const label = encodeURIComponent(data.locationName ?? 'Event');
    return `geo:${data.latitude},${data.longitude}?q=${data.latitude},${data.longitude}(${label})`;
  }
  const query = encodeURIComponent(data.address ?? data.locationName ?? '');
  return `geo:0,0?q=${query}`;
}

/**
 * Open the platform map application to navigate to the given event location.
 *
 * @throws {Error} if no navigation data is available or the URL cannot be opened.
 */
export async function openExternalNavigation(data: EventNavigationData): Promise<void> {
  const hasCoords = data.latitude !== null && data.longitude !== null;
  const hasAddress = Boolean(data.address ?? data.locationName);

  if (!hasCoords && !hasAddress) {
    throw new Error('No navigation data available for this event.');
  }

  const url =
    Platform.OS === 'ios' ? buildAppleMapsUrl(data) : buildGeoUri(data);

  const canOpen = await Linking.canOpenURL(url);

  if (!canOpen) {
    throw new Error(`Cannot open URL: ${Platform.OS === 'ios' ? 'Apple Maps' : 'maps app'} is not available.`);
  }

  await Linking.openURL(url);
}
