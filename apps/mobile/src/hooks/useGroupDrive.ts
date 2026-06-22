/**
 * useGroupDrive — hook for joining, leaving, and updating status in an event group drive.
 *
 * Access rules (enforced by backend; client-side check is UX only):
 *   - Active member_monthly entitlement required.
 *   - RSVP must be `going` or `maybe`.
 *   - Event must be published.
 *
 * Security notes:
 *   - Token is never logged.
 *   - Group drive state is held in transient React state; never persisted.
 *   - Backend is the source of truth for all access decisions.
 *   - Live location is NOT started automatically by joining.
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import type {
  GroupDriveParticipantStatus,
  GroupDriveParticipantSummary,
  GroupDriveSummaryResponse,
  GroupDriveUpdatableStatus,
} from '@carcommunity/shared/group-drive';

import {
  GroupDriveApiError,
  joinGroupDrive,
  leaveGroupDrive,
  loadGroupDriveSummary,
  updateGroupDriveStatus,
} from '../api/group-drive';
import { useAuth } from './useAuth';

export type GroupDriveScreenState =
  | 'loading'
  | 'loaded'
  | 'not_participating'
  | 'error'
  | 'access_lost';

export interface UseGroupDriveOptions {
  eventId: string;
  /** Whether the current user is eligible to participate (UX hint; backend enforces). */
  isEligible: boolean;
}

export interface UseGroupDriveResult {
  screenState: GroupDriveScreenState;
  currentStatus: GroupDriveParticipantStatus | null;
  currentUserHasActiveLiveLocation: boolean;
  totalActive: number;
  joinedCount: number;
  onTheWayCount: number;
  arrivedCount: number;
  participants: GroupDriveParticipantSummary[];
  isJoining: boolean;
  isLeaving: boolean;
  isUpdatingStatus: boolean;
  error: string | null;
  join: () => Promise<void>;
  leave: () => Promise<void>;
  setStatus: (status: GroupDriveUpdatableStatus) => Promise<void>;
  refresh: () => Promise<void>;
}

function parseSummary(data: GroupDriveSummaryResponse['data']): Omit<
  UseGroupDriveResult,
  'screenState' | 'isJoining' | 'isLeaving' | 'isUpdatingStatus' | 'error' | 'join' | 'leave' | 'setStatus' | 'refresh'
> {
  return {
    currentStatus: data.currentUserStatus,
    currentUserHasActiveLiveLocation: data.currentUserHasActiveLiveLocation,
    totalActive: data.totalActive,
    joinedCount: data.joinedCount,
    onTheWayCount: data.onTheWayCount,
    arrivedCount: data.arrivedCount,
    participants: data.participants,
  };
}

const INITIAL_SUMMARY_STATE = {
  currentStatus: null as GroupDriveParticipantStatus | null,
  currentUserHasActiveLiveLocation: false,
  totalActive: 0,
  joinedCount: 0,
  onTheWayCount: 0,
  arrivedCount: 0,
  participants: [] as GroupDriveParticipantSummary[],
};

export function useGroupDrive({ eventId, isEligible }: UseGroupDriveOptions): UseGroupDriveResult {
  const { withToken } = useAuth();
  const [screenState, setScreenState] = useState<GroupDriveScreenState>('loading');
  const [summary, setSummary] = useState(INITIAL_SUMMARY_STATE);
  const [error, setError] = useState<string | null>(null);
  const [isJoining, setIsJoining] = useState(false);
  const [isLeaving, setIsLeaving] = useState(false);
  const [isUpdatingStatus, setIsUpdatingStatus] = useState(false);

  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  // Clear state when access is lost
  useEffect(() => {
    if (!isEligible) {
      if (mountedRef.current) {
        setScreenState('access_lost');
        setSummary(INITIAL_SUMMARY_STATE);
        setError(null);
      }
    }
  }, [isEligible]);

  const refresh = useCallback(async () => {
    if (!isEligible) return;
    if (!mountedRef.current) return;

    setError(null);

    try {
      const result = await withToken((token) => loadGroupDriveSummary(eventId, token));

      if (!mountedRef.current) return;

      if (result === null) {
        setScreenState('access_lost');
        setSummary(INITIAL_SUMMARY_STATE);
        return;
      }

      setSummary(parseSummary(result.data));
      setScreenState('loaded');
    } catch (err) {
      if (!mountedRef.current) return;

      if (err instanceof GroupDriveApiError) {
        if (err.statusCode === 401 || err.statusCode === 403) {
          setScreenState('access_lost');
          setSummary(INITIAL_SUMMARY_STATE);
          return;
        }
      }

      console.error(
        'Failed to load group drive summary:',
        err instanceof Error ? err.message : String(err),
      );
      setError('error');
      setScreenState('error');
    }
  }, [eventId, isEligible, withToken]);

  // Load on mount and when eligibility changes
  useEffect(() => {
    if (isEligible) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- triggers async fetch; state updates happen in async callbacks
      setScreenState('loading');
      void refresh();
    }
  }, [isEligible, refresh]);

  const join = useCallback(async () => {
    if (!isEligible || isJoining) return;
    setIsJoining(true);
    setError(null);

    try {
      await withToken((token) => joinGroupDrive(eventId, token));
      if (!mountedRef.current) return;
      await refresh();
    } catch (err) {
      if (!mountedRef.current) return;
      console.error('Join group drive failed:', err instanceof Error ? err.message : String(err));
      setError('errorJoin');
    } finally {
      if (mountedRef.current) setIsJoining(false);
    }
  }, [eventId, isEligible, isJoining, refresh, withToken]);

  const leave = useCallback(async () => {
    if (!isEligible || isLeaving) return;
    setIsLeaving(true);
    setError(null);

    try {
      await withToken((token) => leaveGroupDrive(eventId, token));
      if (!mountedRef.current) return;
      await refresh();
    } catch (err) {
      if (!mountedRef.current) return;
      console.error('Leave group drive failed:', err instanceof Error ? err.message : String(err));
      setError('errorLeave');
    } finally {
      if (mountedRef.current) setIsLeaving(false);
    }
  }, [eventId, isEligible, isLeaving, refresh, withToken]);

  const setStatus = useCallback(
    async (status: GroupDriveUpdatableStatus) => {
      if (!isEligible || isUpdatingStatus) return;
      setIsUpdatingStatus(true);
      setError(null);

      try {
        await withToken((token) => updateGroupDriveStatus(eventId, { status }, token));
        if (!mountedRef.current) return;
        await refresh();
      } catch (err) {
        if (!mountedRef.current) return;
        console.error(
          'Update group drive status failed:',
          err instanceof Error ? err.message : String(err),
        );
        setError('errorUpdateStatus');
      } finally {
        if (mountedRef.current) setIsUpdatingStatus(false);
      }
    },
    [eventId, isEligible, isUpdatingStatus, refresh, withToken],
  );

  return {
    screenState,
    ...summary,
    isJoining,
    isLeaving,
    isUpdatingStatus,
    error,
    join,
    leave,
    setStatus,
    refresh,
  };
}
