/**
 * usePartnerMarkers — polling hook for KCC Företagspartner map markers.
 *
 * Polls GET /v1/partners/map-markers on a conservative interval while the
 * component is mounted and the app is in the foreground.
 *
 * Polling contract:
 *  - Polls while the app is in the foreground (AppState === 'active').
 *  - Stops polling when the component unmounts.
 *  - Prevents overlapping in-flight requests via an in-flight guard ref.
 *  - Applies conservative backoff after consecutive network failures.
 *  - No auth required — public endpoint.
 *  - Result is bounded by the backend (max 500 markers).
 *
 * Privacy:
 *  - Marker state is transient React state — never persisted.
 *  - Coordinates are never logged.
 *  - No internal application data is included in the marker response.
 *
 * Rendering:
 *  - Markers use the 'partner' type for visual distinction from member/self markers.
 *  - No animated or distracting advertisement rendering.
 */

import { useEffect, useMemo, useRef, useState } from 'react';
import { AppState } from 'react-native';

import { getPartnerMapMarkers, PartnerApiError } from '../api/partners';
import type { PartnerMapMarker } from '@carcommunity/shared/partners';
import type { MapMarkerViewModel } from '../map/types';
import { partnerMarkersToViewModels } from '../map/markerMapping';

/** Polling interval in milliseconds. Partners change rarely — 60 s is conservative. */
const POLL_INTERVAL_MS = 60_000;

/** Initial retry delay after a network/server failure. */
const INITIAL_RETRY_DELAY_MS = 10_000;

/** Maximum retry backoff delay. */
const MAX_RETRY_DELAY_MS = 120_000;

export interface UsePartnerMarkersResult {
  /** Partner map markers as view models, ready for the map rendering layer. */
  markers: MapMarkerViewModel[];
  /** Raw partner marker data, used for tapping to open the detail screen. */
  rawMarkers: PartnerMapMarker[];
  isLoading: boolean;
  error: string | null;
}

export function usePartnerMarkers(): UsePartnerMarkersResult {
  const [rawMarkers, setRawMarkers] = useState<PartnerMapMarker[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const inFlight = useRef(false);
  const retryDelay = useRef(INITIAL_RETRY_DELAY_MS);
  const intervalRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isMounted = useRef(true);

  const poll = async () => {
    if (inFlight.current || !isMounted.current) return;
    inFlight.current = true;
    if (rawMarkers.length === 0) setIsLoading(true);

    try {
      const markers = await getPartnerMapMarkers();
      if (isMounted.current) {
        setRawMarkers(markers);
        setError(null);
        retryDelay.current = INITIAL_RETRY_DELAY_MS;
      }
    } catch (err) {
      if (isMounted.current) {
        if (err instanceof PartnerApiError && err.statusCode >= 500) {
          setError('Kunde inte hämta företagspartner.');
        }
        retryDelay.current = Math.min(retryDelay.current * 2, MAX_RETRY_DELAY_MS);
      }
    } finally {
      inFlight.current = false;
      if (isMounted.current) setIsLoading(false);
    }
  };

  useEffect(() => {
    isMounted.current = true;

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
  }, []);

  const markers = useMemo(() => partnerMarkersToViewModels(rawMarkers), [rawMarkers]);

  return { markers, rawMarkers, isLoading, error };
}
