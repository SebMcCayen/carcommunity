/**
 * useBillboardMarkers — polling hook for sponsored digital billboard map markers.
 *
 * Polls GET /v1/digital-billboards/map-markers on a conservative interval while the
 * component is mounted and the app is in the foreground.
 *
 * Polling contract:
 *  - Polls while the app is in the foreground (AppState === 'active').
 *  - Stops polling when the component unmounts.
 *  - Prevents overlapping in-flight requests via an in-flight guard ref.
 *  - Applies conservative backoff after consecutive network failures.
 *  - Requires auth token — billboard endpoint requires authentication.
 *  - Only fetches when digitalBillboards feature flag is enabled.
 *  - Clears state when flag is disabled.
 *
 * Privacy:
 *  - Marker state is transient React state — never persisted.
 *  - Coordinates are never logged.
 */

import { useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';

import type { PublicBillboardMapMarker } from '@carcommunity/shared/digital-billboards';

import { BillboardApiError, fetchBillboardMapMarkers } from '../api/digital-billboards';
import { useAuth } from './useAuth';
import { useI18n } from './useI18n';

const POLL_INTERVAL_MS = 60_000;
const INITIAL_RETRY_DELAY_MS = 10_000;
const MAX_RETRY_DELAY_MS = 120_000;

export interface UseBillboardMarkersResult {
  markers: PublicBillboardMapMarker[];
  isLoading: boolean;
  error: string | null;
}

export function useBillboardMarkers(featureEnabled = true): UseBillboardMarkersResult {
  const { withToken } = useAuth();
  const { t } = useI18n();
  const [markers, setMarkers] = useState<PublicBillboardMapMarker[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const inFlight = useRef(false);
  const retryDelay = useRef(INITIAL_RETRY_DELAY_MS);
  const intervalRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isMounted = useRef(true);
  // Tracks whether any markers have been fetched; avoids showing the loading
  // spinner on subsequent polls when markers are already visible.
  const hasEverFetchedRef = useRef(false);

  useEffect(() => {
    if (!featureEnabled) {
      hasEverFetchedRef.current = false;
      // eslint-disable-next-line react-hooks/set-state-in-effect -- feature flag cleared; state must reset immediately when feature is disabled
      setMarkers([]);
      setError(null);
      return;
    }

    isMounted.current = true;

    const poll = async () => {
      if (inFlight.current || !isMounted.current) return;
      inFlight.current = true;
      if (!hasEverFetchedRef.current) setIsLoading(true);

      try {
        await withToken(async (token) => {
          const response = await fetchBillboardMapMarkers(token);
          if (isMounted.current) {
            hasEverFetchedRef.current = true;
            setMarkers(response.data.markers);
            setError(null);
            retryDelay.current = INITIAL_RETRY_DELAY_MS;
          }
        });
      } catch (err) {
        if (isMounted.current) {
          if (err instanceof BillboardApiError && err.statusCode >= 500) {
            setError(t('billboard.loadError'));
          }
          retryDelay.current = Math.min(retryDelay.current * 2, MAX_RETRY_DELAY_MS);
        }
      } finally {
        inFlight.current = false;
        if (isMounted.current) setIsLoading(false);
      }
    };

    const start = () => {
      void poll();
      intervalRef.current = setInterval(() => void poll(), POLL_INTERVAL_MS);
    };

    const stop = () => {
      if (intervalRef.current !== null) {
        clearInterval(intervalRef.current);
        intervalRef.current = null;
      }
    };

    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') {
        start();
      } else {
        stop();
      }
    });

    start();

    return () => {
      isMounted.current = false;
      stop();
      subscription.remove();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [featureEnabled]);

  return { markers, isLoading, error };
}
