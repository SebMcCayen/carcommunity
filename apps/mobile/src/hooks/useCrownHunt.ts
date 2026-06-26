/**
 * useCrownHunt — hook for the Kronjakt (Crown Hunt) feature.
 *
 * Safety and privacy rules:
 *  - Backend is the sole authority for eligibility, claims, and Kronpoäng awards.
 *  - Claims are never automatic — initiated only by explicit user tap.
 *  - Coordinates are passed to the backend only at claim time; not stored locally.
 *  - Tokens and coordinates are never logged.
 *  - Claim button is disabled while moving too fast, location unavailable,
 *    permissions missing, a claim is pending, or the feature flag is off.
 *  - No client-side Kronpoäng calculation.
 *  - No background scanning for points.
 *  - No second continuous location watcher is started here — position is read
 *    once only after explicit user interaction.
 *
 * Driving-mode safety:
 *  - Claim action is blocked when `isSafeToCollect` is false.
 *  - Speed from the last known location is used as a client-side pre-check
 *    (backend always re-validates with its own received speed).
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import * as ExpoLocation from 'expo-location';

import type {
  CrownHuntClaimResponse,
  CrownHuntPointDetail,
  CrownHuntPointSummary,
  CrownHuntClaimHistoryEntry,
} from '@carcommunity/shared/crown-hunt';
import { MAX_CLAIM_SPEED_MPS } from '@carcommunity/shared/crown-hunt';

import { claimCrownHuntPoint, getCrownHuntPoint, getCrownHuntPoints, getMyCrownHuntClaims } from '../api/crown-hunt';
import { loadSessionToken } from '../storage/tokenStorage';

/** Maximum speed (m/s) below which the collect button is enabled client-side. */
const CLIENT_SPEED_LIMIT_MS = MAX_CLAIM_SPEED_MPS;

/** Maximum age (ms) of a fresh position for the claim button pre-check. */
const POSITION_FRESHNESS_MS = 30_000;

export interface UseCrownHuntResult {
  /** Active, available Kronjakt points for the map. */
  points: CrownHuntPointSummary[];
  isLoadingPoints: boolean;
  pointsError: string | null;

  /** Currently selected point detail (shown in the bottom sheet). */
  selectedPoint: CrownHuntPointDetail | null;
  isLoadingDetail: boolean;

  /** Current best-effort GPS speed in m/s, null if unknown. Never logged. */
  currentSpeedMs: number | null;

  /**
   * Whether the collect button should be enabled.
   * False if: moving too fast, no position, permissions missing, or claim is pending.
   * Backend always performs its own independent validation.
   */
  isSafeToCollect: boolean;

  /** True while a claim request is in flight. */
  isClaiming: boolean;
  claimResult: CrownHuntClaimResponse | null;
  claimError: string | null;

  /** Current user's claim history, paginated. */
  claimHistory: CrownHuntClaimHistoryEntry[];
  isLoadingHistory: boolean;
  historyHasMore: boolean;
  historyPage: number;

  /** Select a point to view its detail sheet. Pass null to clear selection. */
  selectPoint: (pointId: string | null) => Promise<void>;

  /**
   * Claim the currently selected point.
   * User must explicitly trigger this — never called automatically.
   */
  collect: () => Promise<void>;

  /** Refresh the point list from the backend. */
  refreshPoints: () => Promise<void>;

  /** Load more claim history. */
  loadMoreHistory: () => Promise<void>;

  /** Clear any claim result (e.g. after dismissing the result sheet). */
  clearClaimResult: () => void;
}

export function useCrownHunt(): UseCrownHuntResult {
  const [points, setPoints] = useState<CrownHuntPointSummary[]>([]);
  const [isLoadingPoints, setIsLoadingPoints] = useState(false);
  const [pointsError, setPointsError] = useState<string | null>(null);

  const [selectedPoint, setSelectedPoint] = useState<CrownHuntPointDetail | null>(null);
  const [isLoadingDetail, setIsLoadingDetail] = useState(false);

  const [currentSpeedMs, setCurrentSpeedMs] = useState<number | null>(null);
  const [isSafeToCollect, setIsSafeToCollect] = useState(false);

  const [isClaiming, setIsClaiming] = useState(false);
  const [claimResult, setClaimResult] = useState<CrownHuntClaimResponse | null>(null);
  const [claimError, setClaimError] = useState<string | null>(null);

  const [claimHistory, setClaimHistory] = useState<CrownHuntClaimHistoryEntry[]>([]);
  const [isLoadingHistory, setIsLoadingHistory] = useState(false);
  const [historyHasMore, setHistoryHasMore] = useState(false);
  const [historyPage, setHistoryPage] = useState(1);

  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      // Clear any sensitive position data on unmount.
      setCurrentSpeedMs(null);
      setIsSafeToCollect(false);
      setClaimResult(null);
    };
  }, []);

  // ---------------------------------------------------------------------------
  // Points list
  // ---------------------------------------------------------------------------

  const loadPoints = useCallback(async () => {
    setIsLoadingPoints(true);
    setPointsError(null);
    try {
      const auth = await loadSessionToken().catch(() => null);
      const token = auth?.token ?? undefined;
      const res = await getCrownHuntPoints(token);
      if (!mountedRef.current) return;
      setPoints(res.data.points);
    } catch {
      if (!mountedRef.current) return;
      setPointsError('crownHunt.error');
    } finally {
      if (mountedRef.current) setIsLoadingPoints(false);
    }
  }, []);

  const refreshPoints = useCallback(async () => {
    await loadPoints();
  }, [loadPoints]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- triggers async fetch; state updates happen in async callbacks
    void loadPoints();
  }, [loadPoints]);

  // ---------------------------------------------------------------------------
  // Point detail + position freshness check
  // ---------------------------------------------------------------------------

  /**
   * Reads a fresh GPS fix and evaluates if the user is safely stopped.
   * Called when the user selects a point (before showing the collect button).
   * Never logs coordinates.
   */
  const readFreshPosition = useCallback(async (): Promise<void> => {
    try {
      const { status } = await ExpoLocation.getForegroundPermissionsAsync();
      if (status !== ExpoLocation.PermissionStatus.GRANTED) {
        if (mountedRef.current) {
          setCurrentSpeedMs(null);
          setIsSafeToCollect(false);
        }
        return;
      }

      const location = await ExpoLocation.getCurrentPositionAsync({
        accuracy: ExpoLocation.Accuracy.Balanced,
      });

      if (!mountedRef.current) return;

      const speed = location.coords.speed ?? null;
      setCurrentSpeedMs(speed);

      const ageMs = Date.now() - location.timestamp;
      const isFresh = ageMs <= POSITION_FRESHNESS_MS;
      const isSlowEnough = speed === null || speed <= CLIENT_SPEED_LIMIT_MS;

      setIsSafeToCollect(isFresh && isSlowEnough);
    } catch {
      if (!mountedRef.current) return;
      setCurrentSpeedMs(null);
      setIsSafeToCollect(false);
    }
  }, []);

  const selectPoint = useCallback(
    async (pointId: string | null) => {
      if (pointId === null) {
        setSelectedPoint(null);
        setClaimResult(null);
        setClaimError(null);
        return;
      }

      setIsLoadingDetail(true);
      setClaimResult(null);
      setClaimError(null);

      try {
        const auth = await loadSessionToken().catch(() => null);
        const token = auth?.token ?? undefined;

        // Fetch point detail and a fresh position simultaneously.
        const [detail] = await Promise.all([getCrownHuntPoint(pointId, token), readFreshPosition()]);

        if (!mountedRef.current) return;
        setSelectedPoint(detail);
      } catch {
        if (!mountedRef.current) return;
        setSelectedPoint(null);
      } finally {
        if (mountedRef.current) setIsLoadingDetail(false);
      }
    },
    [readFreshPosition],
  );

  // ---------------------------------------------------------------------------
  // Claim — user must explicitly trigger this
  // ---------------------------------------------------------------------------

  /**
   * Submits a claim to the backend.
   *
   * Only called after explicit user tap. Never automatic.
   * Backend performs all eligibility, geofence, speed, and fraud validation.
   * Coordinates are obtained fresh at claim time and never stored locally.
   */
  const collect = useCallback(async () => {
    if (!selectedPoint || isClaiming || !isSafeToCollect) return;

    setIsClaiming(true);
    setClaimError(null);
    setClaimResult(null);

    try {
      // Get a fresh GPS fix immediately before submitting the claim.
      // Never log coordinates.
      const { status } = await ExpoLocation.getForegroundPermissionsAsync();
      if (status !== ExpoLocation.PermissionStatus.GRANTED) {
        if (mountedRef.current) {
          setClaimError('crownHunt.errorLocationPermission');
          setIsClaiming(false);
        }
        return;
      }

      const location = await ExpoLocation.getCurrentPositionAsync({
        accuracy: ExpoLocation.Accuracy.Balanced,
      });

      if (!mountedRef.current) return;

      const auth = await loadSessionToken().catch(() => null);
      const token = auth?.token ?? undefined;

      const idempotencyKey = globalThis.crypto?.randomUUID?.()
        ?? `${Date.now()}-${Math.random().toString(36).slice(2)}${Math.random().toString(36).slice(2)}`;

      const result = await claimCrownHuntPoint(
        selectedPoint.pointId,
        {
          latitude: location.coords.latitude,
          longitude: location.coords.longitude,
          accuracyMeters: location.coords.accuracy ?? undefined,
          speedMetersPerSecond: location.coords.speed ?? undefined,
          recordedAt: new Date(location.timestamp).toISOString(),
          idempotencyKey,
        },
        token,
      );

      if (!mountedRef.current) return;

      setClaimResult(result);

      // Re-evaluate safe-to-collect state after a claim attempt.
      const speed = location.coords.speed ?? null;
      setCurrentSpeedMs(speed);
      setIsSafeToCollect(speed === null || speed <= CLIENT_SPEED_LIMIT_MS);

      // Refresh points to update claimed state on map markers.
      void loadPoints();
    } catch {
      if (!mountedRef.current) return;
      setClaimError('crownHunt.errorClaim');
    } finally {
      if (mountedRef.current) setIsClaiming(false);
    }
  }, [selectedPoint, isClaiming, isSafeToCollect, loadPoints]);

  const clearClaimResult = useCallback(() => {
    setClaimResult(null);
    setClaimError(null);
  }, []);

  // ---------------------------------------------------------------------------
  // Claim history
  // ---------------------------------------------------------------------------

  const loadHistory = useCallback(async (page: number, append: boolean) => {
    setIsLoadingHistory(true);
    try {
      const auth = await loadSessionToken().catch(() => null);
      const token = auth?.token ?? undefined;
      const res = await getMyCrownHuntClaims(token, page, 20);
      if (!mountedRef.current) return;
      if (append) {
        setClaimHistory((prev) => [...prev, ...res.data.claims]);
      } else {
        setClaimHistory(res.data.claims);
      }
      setHistoryHasMore(res.meta.hasNext);
      setHistoryPage(page);
    } catch {
      // Claim history load failure is non-critical; leave existing data.
    } finally {
      if (mountedRef.current) setIsLoadingHistory(false);
    }
  }, []);

  const loadMoreHistory = useCallback(async () => {
    if (isLoadingHistory || !historyHasMore) return;
    await loadHistory(historyPage + 1, true);
  }, [loadHistory, isLoadingHistory, historyHasMore, historyPage]);

  return {
    points,
    isLoadingPoints,
    pointsError,
    selectedPoint,
    isLoadingDetail,
    currentSpeedMs,
    isSafeToCollect,
    isClaiming,
    claimResult,
    claimError,
    claimHistory,
    isLoadingHistory,
    historyHasMore,
    historyPage,
    selectPoint,
    collect,
    refreshPoints,
    loadMoreHistory,
    clearClaimResult,
  };
}
