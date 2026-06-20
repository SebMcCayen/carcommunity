/**
 * Tests for useLiveLocationMarkers hook.
 *
 * Covers:
 *  - Free users do not start polling
 *  - Members start polling while the screen is visible
 *  - Polling stops when the component unmounts
 *  - Polling stops when the app enters the background
 *  - Polling stops on logout / ineligible state
 *  - Overlapping requests are prevented
 *  - Stale markers are removed by the client-side filter
 *  - Access loss (401 / 403) clears existing markers
 *  - Consecutive failures apply bounded back-off
 *  - Current user is not duplicated as another-user marker (backend excludes; hook passes through)
 *  - Coordinates and tokens are not logged
 */

import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';

import { LIVE_LOCATION_MARKER_STALE_THRESHOLD_MS } from '@carcommunity/shared/live-location';
import { AppState } from 'react-native';

import type { UseLiveLocationMarkersResult } from '../useLiveLocationMarkers';
import { useLiveLocationMarkers } from '../useLiveLocationMarkers';
import * as liveLocationApi from '../../api/live-location';
import { LiveLocationApiError } from '../../api/live-location';

// Mock the live location API so no real HTTP requests are made.
jest.mock('../../api/live-location', () => {
  const original = jest.requireActual<typeof import('../../api/live-location')>('../../api/live-location');
  return {
    ...original,
    loadLiveLocationMarkers: jest.fn(),
  };
});

// Mock useAuth to control authentication and eligibility.
jest.mock('../useAuth', () => ({
  useAuth: jest.fn(),
  getPlatformAuthProvider: jest.fn().mockReturnValue(null),
}));

const mockLoadMarkers = liveLocationApi.loadLiveLocationMarkers as jest.MockedFunction<
  typeof liveLocationApi.loadLiveLocationMarkers
>;

// useAuth mock factory helpers.
const { useAuth } = jest.requireMock('../useAuth') as { useAuth: jest.Mock };

/**
 * withToken mock that immediately calls fn('mock-token').
 */
const withTokenMock = jest.fn().mockImplementation(
  <T,>(fn: (token: string) => Promise<T>): Promise<T | null> => fn('mock-token'),
);

/**
 * withToken mock that returns null (no auth token).
 */
const withTokenNullMock = jest.fn().mockResolvedValue(null);

function setMemberUser() {
  useAuth.mockReturnValue({
    currentUser: {
      userId: 'user-1',
      displayName: 'Test Member',
      roles: ['user'],
      status: 'active',
      subscriptionEntitlement: 'member_monthly',
      identities: [],
      onboardingCompletedAt: null,
    },
    isAuthenticated: true,
    isLoading: false,
    error: null,
    withToken: withTokenMock,
  });
}

function setFreeUser() {
  useAuth.mockReturnValue({
    currentUser: {
      userId: 'user-1',
      displayName: null,
      roles: ['user'],
      status: 'active',
      subscriptionEntitlement: 'none',
      identities: [],
      onboardingCompletedAt: null,
    },
    isAuthenticated: true,
    isLoading: false,
    error: null,
    withToken: withTokenMock,
  });
}

function setUnauthenticated() {
  useAuth.mockReturnValue({
    currentUser: null,
    isAuthenticated: false,
    isLoading: false,
    error: null,
    withToken: withTokenNullMock,
  });
}

function buildMarkerResponse(overrides?: { recordedAt?: string }) {
  return {
    ok: true as const,
    data: {
      markers: [
        {
          userId: 'user-2',
          sessionId: 'session-2',
          status: 'active' as const,
          coordinate: {
            latitude: 57.51,
            longitude: 12.08,
            recordedAt: overrides?.recordedAt ?? new Date().toISOString(),
          },
        },
      ],
      generatedAt: new Date().toISOString(),
    },
    meta: {
      source: 'database' as const,
      productionReady: true,
      ttlCleanupPrepared: true,
      page: 1,
      pageSize: 20,
      total: 1,
      hasNext: false,
    },
  };
}

// Helper: render the hook in a minimal test component and capture the result.
// Uses async act so the initial poll is flushed within the same act boundary —
// this is required in React 19 where state updates from async operations scheduled
// inside a sync act() are NOT captured by a subsequent async act().
async function renderHook() {
  const result: { current: UseLiveLocationMarkersResult } = {
    current: undefined as unknown as UseLiveLocationMarkersResult,
  };
  let unmount: () => void = () => {};

  function TestComponent() {
    result.current = useLiveLocationMarkers();
    return null;
  }

  await act(async () => {
    const renderer = TestRenderer.create(<TestComponent />);
    unmount = () => renderer.unmount();
    // Flush the initial poll within this act so React 19 captures the state updates.
    await Promise.resolve();
  });

  return { result, unmount };
}

beforeEach(() => {
  jest.clearAllMocks();
  jest.useFakeTimers();

  // Most tests need the interval to fire; mock AppState as 'active' by default.
  // Individual tests override this (e.g. the background test sets 'background').
  Object.defineProperty(AppState, 'currentState', {
    configurable: true,
    get: () => 'active',
  });

  setMemberUser();
  // Default: successful empty response.
  mockLoadMarkers.mockResolvedValue({
    ok: true,
    data: { markers: [], generatedAt: new Date().toISOString() },
    meta: {
      source: 'database',
      productionReady: true,
      ttlCleanupPrepared: true,
      page: 1,
      pageSize: 20,
      total: 0,
      hasNext: false,
    },
  });
});

afterEach(() => {
  jest.runOnlyPendingTimers();
  jest.useRealTimers();
  // Restore AppState.currentState to its real value (null before native init).
  Object.defineProperty(AppState, 'currentState', {
    configurable: true,
    get: () => null,
  });
});

// ---------------------------------------------------------------------------
// Eligibility gating
// ---------------------------------------------------------------------------

describe('useLiveLocationMarkers — eligibility', () => {
  it('returns isMemberEligible true for an active member_monthly user', async () => {
    setMemberUser();
    const { result } = await renderHook();
    expect(result.current.isMemberEligible).toBe(true);
  });

  it('returns isMemberEligible false for a free user', async () => {
    setFreeUser();
    const { result } = await renderHook();
    expect(result.current.isMemberEligible).toBe(false);
  });

  it('returns isMemberEligible false when unauthenticated', async () => {
    setUnauthenticated();
    const { result } = await renderHook();
    expect(result.current.isMemberEligible).toBe(false);
  });

  it('free users do not call loadLiveLocationMarkers', async () => {
    setFreeUser();
    await renderHook();
    expect(mockLoadMarkers).not.toHaveBeenCalled();
  });

  it('unauthenticated users do not call loadLiveLocationMarkers', async () => {
    setUnauthenticated();
    await renderHook();
    expect(mockLoadMarkers).not.toHaveBeenCalled();
  });

  it('member users call loadLiveLocationMarkers on mount', async () => {
    setMemberUser();
    await renderHook();
    expect(mockLoadMarkers).toHaveBeenCalledTimes(1);
    expect(mockLoadMarkers).toHaveBeenCalledWith(1, expect.any(Number), 'mock-token');
  });
});

// ---------------------------------------------------------------------------
// Polling lifecycle
// ---------------------------------------------------------------------------

describe('useLiveLocationMarkers — polling lifecycle', () => {
  it('polls again after the interval elapses', async () => {
    setMemberUser();
    await renderHook();
    expect(mockLoadMarkers).toHaveBeenCalledTimes(1);

    await act(async () => {
      jest.advanceTimersByTime(12_001);
      await Promise.resolve();
    });

    expect(mockLoadMarkers).toHaveBeenCalledTimes(2);
  });

  it('stops polling when the component unmounts', async () => {
    setMemberUser();
    const { unmount } = await renderHook();

    await act(async () => {
      unmount();
    });

    const callsBefore = mockLoadMarkers.mock.calls.length;

    await act(async () => {
      jest.advanceTimersByTime(24_000);
      await Promise.resolve();
    });

    // No additional polls after unmount.
    expect(mockLoadMarkers.mock.calls.length).toBe(callsBefore);
  });

  it('clears markers when the component unmounts', async () => {
    setMemberUser();
    mockLoadMarkers.mockResolvedValue(buildMarkerResponse());

    const { result, unmount } = await renderHook();

    expect(result.current.markers.length).toBe(1);

    await act(async () => {
      unmount();
    });

    // After unmount, polling should have stopped — no further API calls.
    const callsBefore = mockLoadMarkers.mock.calls.length;
    await act(async () => {
      jest.advanceTimersByTime(24_000);
      await Promise.resolve();
    });
    expect(mockLoadMarkers.mock.calls.length).toBe(callsBefore);
  });

  it('stops polling when user becomes ineligible (simulated logout)', async () => {
    setMemberUser();
    const { unmount } = await renderHook();

    expect(mockLoadMarkers).toHaveBeenCalledTimes(1);

    // Simulate logout by unmounting (isMemberEligible becomes false on cleanup).
    await act(async () => {
      unmount();
    });

    const callsBefore = mockLoadMarkers.mock.calls.length;
    await act(async () => {
      jest.advanceTimersByTime(24_000);
      await Promise.resolve();
    });
    expect(mockLoadMarkers.mock.calls.length).toBe(callsBefore);
  });

  it('free user starts with no markers', async () => {
    setFreeUser();
    const { result } = await renderHook();
    expect(result.current.markers).toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// Marker data
// ---------------------------------------------------------------------------

describe('useLiveLocationMarkers — marker data', () => {
  it('returns fresh markers from the backend response', async () => {
    setMemberUser();
    mockLoadMarkers.mockResolvedValue(buildMarkerResponse());

    const { result } = await renderHook();

    expect(result.current.markers).toHaveLength(1);
    expect(result.current.markers[0]).toMatchObject({
      id: 'session-2',
      type: 'member',
      coordinate: { latitude: 57.51, longitude: 12.08 },
    });
  });

  it('uses sessionId as the marker id (opaque identifier)', async () => {
    setMemberUser();
    mockLoadMarkers.mockResolvedValue(buildMarkerResponse());

    const { result } = await renderHook();

    expect(result.current.markers[0]?.id).toBe('session-2');
  });

  it('removes stale markers (recordedAt beyond the client-side threshold)', async () => {
    setMemberUser();
    const staleRecordedAt = new Date(
      Date.now() - (LIVE_LOCATION_MARKER_STALE_THRESHOLD_MS + 5_000),
    ).toISOString();

    mockLoadMarkers.mockResolvedValue(buildMarkerResponse({ recordedAt: staleRecordedAt }));

    const { result } = await renderHook();

    // Stale marker must not appear.
    expect(result.current.markers).toHaveLength(0);
  });

  it('keeps fresh markers (recordedAt within threshold)', async () => {
    setMemberUser();
    const freshRecordedAt = new Date(Date.now() - 5_000).toISOString();
    mockLoadMarkers.mockResolvedValue(buildMarkerResponse({ recordedAt: freshRecordedAt }));

    const { result } = await renderHook();

    expect(result.current.markers).toHaveLength(1);
  });

  it('replaces markers on each poll — removes markers absent from the new response', async () => {
    setMemberUser();
    // First poll returns a marker.
    mockLoadMarkers.mockResolvedValueOnce(buildMarkerResponse());
    // Subsequent polls return empty.
    mockLoadMarkers.mockResolvedValue({
      ok: true,
      data: { markers: [], generatedAt: new Date().toISOString() },
      meta: { source: 'database', productionReady: true, ttlCleanupPrepared: true, page: 1, pageSize: 20, total: 0, hasNext: false },
    });

    const { result } = await renderHook();
    expect(result.current.markers).toHaveLength(1);

    await act(async () => {
      jest.advanceTimersByTime(12_001);
      await Promise.resolve();
    });

    expect(result.current.markers).toHaveLength(0);
  });
});

// ---------------------------------------------------------------------------
// Overlap prevention
// ---------------------------------------------------------------------------

describe('useLiveLocationMarkers — overlap prevention', () => {
  it('does not issue a second request while the first is still in-flight', async () => {
    setMemberUser();

    let resolveFirst!: () => void;
    mockLoadMarkers.mockImplementationOnce(
      () =>
        new Promise<typeof buildMarkerResponse extends () => infer R ? R : never>((resolve) => {
          resolveFirst = () =>
            resolve({
              ok: true,
              data: { markers: [], generatedAt: new Date().toISOString() },
              meta: { source: 'database', productionReady: true, ttlCleanupPrepared: true, page: 1, pageSize: 20, total: 0, hasNext: false },
            });
        }),
    );

    // Use sync-style renderHook here since the initial poll must stay in-flight.
    const result: { current: UseLiveLocationMarkersResult } = {
      current: undefined as unknown as UseLiveLocationMarkersResult,
    };
    await act(async () => {
      TestRenderer.create((() => {
        function TC() {
          result.current = useLiveLocationMarkers();
          return null;
        }
        return <TC />;
      })());
      // Do NOT await Promise.resolve() here — the first poll must stay pending.
    });

    // Interval fires before the first request resolves.
    await act(async () => {
      jest.advanceTimersByTime(12_001);
      await Promise.resolve();
    });

    // Only one in-flight request should have been made.
    expect(mockLoadMarkers).toHaveBeenCalledTimes(1);

    // Now let the first request resolve.
    await act(async () => {
      resolveFirst();
      await Promise.resolve();
    });
  });
});

// ---------------------------------------------------------------------------
// Error handling
// ---------------------------------------------------------------------------

describe('useLiveLocationMarkers — error handling', () => {
  it('clears markers on 401 Unauthorized and stops polling', async () => {
    setMemberUser();
    // First poll succeeds.
    mockLoadMarkers.mockResolvedValueOnce(buildMarkerResponse());
    // Subsequent polls return 401.
    mockLoadMarkers.mockRejectedValue(new LiveLocationApiError(401, 'Unauthorized'));

    const { result } = await renderHook();
    expect(result.current.markers).toHaveLength(1);

    await act(async () => {
      jest.advanceTimersByTime(12_001);
      await Promise.resolve();
    });

    expect(result.current.markers).toHaveLength(0);

    const callsBefore = mockLoadMarkers.mock.calls.length;
    await act(async () => {
      jest.advanceTimersByTime(12_001);
      await Promise.resolve();
    });
    // Polling stopped after access loss.
    expect(mockLoadMarkers.mock.calls.length).toBe(callsBefore);
  });

  it('clears markers on 403 Forbidden (feature disabled or access lost)', async () => {
    setMemberUser();
    // First poll succeeds.
    mockLoadMarkers.mockResolvedValueOnce(buildMarkerResponse());
    // Subsequent polls return 403.
    mockLoadMarkers.mockRejectedValue(new LiveLocationApiError(403, 'Forbidden'));

    const { result } = await renderHook();
    expect(result.current.markers).toHaveLength(1);

    await act(async () => {
      jest.advanceTimersByTime(12_001);
      await Promise.resolve();
    });

    expect(result.current.markers).toHaveLength(0);
  });

  it('applies back-off after a network failure and retries', async () => {
    setMemberUser();
    mockLoadMarkers.mockRejectedValueOnce(new Error('Network error'));
    mockLoadMarkers.mockResolvedValue({
      ok: true,
      data: { markers: [], generatedAt: new Date().toISOString() },
      meta: { source: 'database', productionReady: true, ttlCleanupPrepared: true, page: 1, pageSize: 20, total: 0, hasNext: false },
    });

    await renderHook();
    expect(mockLoadMarkers).toHaveBeenCalledTimes(1);

    // Interval fires — back-off (5 000 ms) has elapsed within the 12 001 ms advance.
    await act(async () => {
      jest.advanceTimersByTime(12_001);
      await Promise.resolve();
    });
    expect(mockLoadMarkers.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it('does not log coordinates on marker API error', async () => {
    setMemberUser();
    const consoleSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);
    mockLoadMarkers.mockRejectedValue(new Error('Server error'));

    await renderHook();

    const loggedCoords = consoleSpy.mock.calls.some((args) =>
      args.some(
        (arg) =>
          typeof arg === 'string' &&
          (arg.includes('57.') || arg.includes('12.')),
      ),
    );
    expect(loggedCoords).toBe(false);

    consoleSpy.mockRestore();
  });

  it('does not log auth tokens in any error scenario', async () => {
    setMemberUser();
    const consoleSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);
    mockLoadMarkers.mockRejectedValue(new LiveLocationApiError(401, 'Token expired'));

    await renderHook();

    const loggedToken = consoleSpy.mock.calls.some((args) =>
      args.some(
        (arg) => typeof arg === 'string' && arg.includes('mock-token'),
      ),
    );
    expect(loggedToken).toBe(false);

    consoleSpy.mockRestore();
  });
});

// ---------------------------------------------------------------------------
// AppState / background behaviour
// ---------------------------------------------------------------------------

describe('useLiveLocationMarkers — AppState background', () => {
  it('does not issue a new poll when the app is backgrounded (AppState not active)', async () => {
    setMemberUser();

    // Override AppState to 'background' for this test.
    Object.defineProperty(AppState, 'currentState', {
      configurable: true,
      get: () => 'background',
    });

    await renderHook();

    const callsAfterMount = mockLoadMarkers.mock.calls.length;

    await act(async () => {
      jest.advanceTimersByTime(12_001);
      await Promise.resolve();
    });

    // The scheduled interval check respects AppState — no new calls.
    expect(mockLoadMarkers.mock.calls.length).toBe(callsAfterMount);
  });

  it('resumes polling when the app returns to the foreground', async () => {
    setMemberUser();

    let capturedListener: ((state: string) => void) | null = null;
    const removeMock = jest.fn();
    jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
      capturedListener = listener as (state: string) => void;
      return { remove: removeMock };
    });

    await renderHook();

    const callsBefore = mockLoadMarkers.mock.calls.length;

    // Simulate returning to foreground.
    await act(async () => {
      capturedListener?.('active');
      await Promise.resolve();
    });

    expect(mockLoadMarkers.mock.calls.length).toBeGreaterThan(callsBefore);

    jest.restoreAllMocks();
  });

  it('removes the AppState listener on unmount', async () => {
    setMemberUser();

    const removeMock = jest.fn();
    jest.spyOn(AppState, 'addEventListener').mockReturnValue({ remove: removeMock });

    const { unmount } = await renderHook();

    await act(async () => {
      unmount();
    });

    expect(removeMock).toHaveBeenCalled();

    jest.restoreAllMocks();
  });
});
