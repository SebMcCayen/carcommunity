/**
 * Tests for background location behavior in useLiveLocationSession hook.
 *
 * Verifies the background permission flow, task lifecycle, and privacy guarantees
 * for the background location feature.
 *
 * expo-location and expo-task-manager are mocked via moduleNameMapper.
 * expo-secure-store is mocked via moduleNameMapper.
 */

import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';

import type { UseLiveLocationSessionResult } from '../useLiveLocationSession';
import { useLiveLocationSession } from '../useLiveLocationSession';
import * as liveLocationApi from '../../api/live-location';
import * as ExpoLocation from 'expo-location';
import * as SecureStore from 'expo-secure-store';

// Mock the live location API client.
jest.mock('../../api/live-location', () => ({
  startLiveLocationSession: jest.fn(),
  stopLiveLocationSession: jest.fn(),
  hideMeNow: jest.fn(),
  loadLiveLocationMarkers: jest.fn(),
  updateLiveLocationPosition: jest.fn(),
}));

// Helper: capture the hook return value from a minimal test component.
function renderHook(): { result: { current: UseLiveLocationSessionResult } } {
  const result: { current: UseLiveLocationSessionResult } = {
    current: undefined as unknown as UseLiveLocationSessionResult,
  };

  function TestComponent() {
    result.current = useLiveLocationSession();
    return null;
  }

  act(() => {
    TestRenderer.create(<TestComponent />);
  });

  return { result };
}

const mockStart = liveLocationApi.startLiveLocationSession as jest.MockedFunction<
  typeof liveLocationApi.startLiveLocationSession
>;
const mockStop = liveLocationApi.stopLiveLocationSession as jest.MockedFunction<
  typeof liveLocationApi.stopLiveLocationSession
>;
const mockHide = liveLocationApi.hideMeNow as jest.MockedFunction<
  typeof liveLocationApi.hideMeNow
>;
const mockUpdatePosition = liveLocationApi.updateLiveLocationPosition as jest.MockedFunction<
  typeof liveLocationApi.updateLiveLocationPosition
>;
const mockRequestForeground =
  ExpoLocation.requestForegroundPermissionsAsync as jest.MockedFunction<
    typeof ExpoLocation.requestForegroundPermissionsAsync
  >;
const mockRequestBackground =
  ExpoLocation.requestBackgroundPermissionsAsync as jest.MockedFunction<
    typeof ExpoLocation.requestBackgroundPermissionsAsync
  >;
const mockGetBackground =
  ExpoLocation.getBackgroundPermissionsAsync as jest.MockedFunction<
    typeof ExpoLocation.getBackgroundPermissionsAsync
  >;
const mockStartLocationUpdates =
  ExpoLocation.startLocationUpdatesAsync as jest.MockedFunction<
    typeof ExpoLocation.startLocationUpdatesAsync
  >;
const mockStopLocationUpdates =
  ExpoLocation.stopLocationUpdatesAsync as jest.MockedFunction<
    typeof ExpoLocation.stopLocationUpdatesAsync
  >;
const mockHasStartedLocationUpdates =
  ExpoLocation.hasStartedLocationUpdatesAsync as jest.MockedFunction<
    typeof ExpoLocation.hasStartedLocationUpdatesAsync
  >;
const mockWatchPosition = ExpoLocation.watchPositionAsync as jest.MockedFunction<
  typeof ExpoLocation.watchPositionAsync
>;

const mockSecureStoreGet = SecureStore.getItemAsync as jest.MockedFunction<
  typeof SecureStore.getItemAsync
>;

const MOCK_SESSION_RESPONSE = {
  ok: true as const,
  data: {
    session: {
      id: 'session-bg-test',
      status: 'active' as const,
      duration: '1h' as const,
      startedAt: new Date().toISOString(),
      expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
      stoppedAt: null,
    },
    latestPosition: null,
    latestPositionRemoved: false,
  },
  meta: { source: 'database' as const, productionReady: false, ttlCleanupPrepared: false },
};

const MOCK_STOP_RESPONSE = {
  ok: true as const,
  data: {
    session: {
      id: 'session-bg-test',
      status: 'stopped' as const,
      duration: '1h' as const,
      startedAt: new Date().toISOString(),
      expiresAt: new Date().toISOString(),
      stoppedAt: new Date().toISOString(),
    },
    latestPosition: null,
    latestPositionRemoved: true,
  },
  meta: { source: 'database' as const, productionReady: false, ttlCleanupPrepared: false },
};

const MOCK_HIDE_RESPONSE = {
  ok: true as const,
  data: { stoppedSessionCount: 1, removedLatestPositionCount: 1 },
  meta: { source: 'database' as const, productionReady: false, ttlCleanupPrepared: false },
};

beforeEach(() => {
  jest.clearAllMocks();
  // Default: foreground permission granted, no background permission by default.
  mockRequestForeground.mockResolvedValue({
    status: ExpoLocation.PermissionStatus.GRANTED,
    granted: true,
    expires: 'never',
    canAskAgain: true,
  });
  mockRequestBackground.mockResolvedValue({
    status: ExpoLocation.PermissionStatus.DENIED,
    granted: false,
    expires: 'never',
    canAskAgain: true,
  });
  mockGetBackground.mockResolvedValue({
    status: ExpoLocation.PermissionStatus.DENIED,
    granted: false,
    expires: 'never',
    canAskAgain: true,
  });
  mockStartLocationUpdates.mockResolvedValue(undefined);
  mockStopLocationUpdates.mockResolvedValue(undefined);
  mockHasStartedLocationUpdates.mockResolvedValue(false);
  mockWatchPosition.mockResolvedValue({ remove: jest.fn() });
  mockUpdatePosition.mockResolvedValue(MOCK_SESSION_RESPONSE);
  // No stored session by default.
  mockSecureStoreGet.mockResolvedValue(null);
});

// ────────────────────────────────────────────────────────────────────────────
// Background permission is never requested at startup or without user action
// ────────────────────────────────────────────────────────────────────────────

describe('background permission — not requested at startup', () => {
  it('does not request background permission when the hook mounts', async () => {
    await act(async () => {
      renderHook();
      // Allow async mount effects to settle.
      await new Promise((r) => setTimeout(r, 0));
    });

    expect(mockRequestBackground).not.toHaveBeenCalled();
  });

  it('starts with backgroundPermissionMode = not_requested', () => {
    const { result } = renderHook();
    expect(result.current.backgroundPermissionMode).toBe('not_requested');
  });

  it('does not request background permission when startSession is called', async () => {
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);

    const { result } = renderHook();

    await act(async () => {
      await result.current.startSession();
    });

    expect(mockRequestBackground).not.toHaveBeenCalled();
  });

  it('does not start background location updates when startSession is called', async () => {
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);

    const { result } = renderHook();

    await act(async () => {
      await result.current.startSession();
    });

    expect(mockStartLocationUpdates).not.toHaveBeenCalled();
  });
});

// ────────────────────────────────────────────────────────────────────────────
// Background permission is only requested on explicit user action
// ────────────────────────────────────────────────────────────────────────────

describe('background permission — requested only on explicit user action', () => {
  it('requests background permission only when requestBackgroundPermission() is called', async () => {
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);
    mockRequestBackground.mockResolvedValueOnce({
      status: ExpoLocation.PermissionStatus.GRANTED,
      granted: true,
      expires: 'never',
      canAskAgain: true,
    });

    const { result } = renderHook();

    await act(async () => {
      await result.current.startSession();
    });

    expect(mockRequestBackground).not.toHaveBeenCalled();

    await act(async () => {
      await result.current.requestBackgroundPermission();
    });

    expect(mockRequestBackground).toHaveBeenCalledTimes(1);
  });

  it('sets backgroundPermissionMode to granted when background permission is granted', async () => {
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);
    mockRequestBackground.mockResolvedValueOnce({
      status: ExpoLocation.PermissionStatus.GRANTED,
      granted: true,
      expires: 'never',
      canAskAgain: true,
    });

    const { result } = renderHook();

    await act(async () => {
      await result.current.startSession();
    });

    await act(async () => {
      await result.current.requestBackgroundPermission();
    });

    expect(result.current.backgroundPermissionMode).toBe('granted');
  });

  it('starts background location updates when background permission is granted during active session', async () => {
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);
    mockRequestBackground.mockResolvedValueOnce({
      status: ExpoLocation.PermissionStatus.GRANTED,
      granted: true,
      expires: 'never',
      canAskAgain: true,
    });

    const { result } = renderHook();

    await act(async () => {
      await result.current.startSession();
    });

    await act(async () => {
      await result.current.requestBackgroundPermission();
    });

    expect(mockStartLocationUpdates).toHaveBeenCalledTimes(1);
  });
});

// ────────────────────────────────────────────────────────────────────────────
// Foreground-only mode when background permission is denied
// ────────────────────────────────────────────────────────────────────────────

describe('foreground-only mode when background permission is denied', () => {
  it('sets backgroundPermissionMode to foreground_only when background permission is denied', async () => {
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);
    // mockRequestBackground already defaults to DENIED.

    const { result } = renderHook();

    await act(async () => {
      await result.current.startSession();
    });

    await act(async () => {
      await result.current.requestBackgroundPermission();
    });

    expect(result.current.backgroundPermissionMode).toBe('foreground_only');
  });

  it('does not start background location updates when background permission is denied', async () => {
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);

    const { result } = renderHook();

    await act(async () => {
      await result.current.startSession();
    });

    await act(async () => {
      await result.current.requestBackgroundPermission();
    });

    expect(mockStartLocationUpdates).not.toHaveBeenCalled();
  });

  it('remains in sharing status when background permission is denied (foreground-only continues)', async () => {
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);

    const { result } = renderHook();

    await act(async () => {
      await result.current.startSession();
    });

    await act(async () => {
      await result.current.requestBackgroundPermission();
    });

    expect(result.current.status).toBe('sharing');
    expect(result.current.sessionId).toBe('session-bg-test');
  });

  it('sets backgroundPermissionMode to foreground_only when skipBackgroundPermission is called', async () => {
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);

    const { result } = renderHook();

    await act(async () => {
      await result.current.startSession();
    });

    act(() => {
      result.current.skipBackgroundPermission();
    });

    expect(result.current.backgroundPermissionMode).toBe('foreground_only');
    // Must not have prompted the OS dialog.
    expect(mockRequestBackground).not.toHaveBeenCalled();
  });
});

// ────────────────────────────────────────────────────────────────────────────
// Background task only starts for an active session
// ────────────────────────────────────────────────────────────────────────────

describe('background task starts only for an active session', () => {
  it('does not start background task when no session is active', async () => {
    mockRequestBackground.mockResolvedValueOnce({
      status: ExpoLocation.PermissionStatus.GRANTED,
      granted: true,
      expires: 'never',
      canAskAgain: true,
    });

    const { result } = renderHook();

    // Call requestBackgroundPermission when not sharing.
    await act(async () => {
      await result.current.requestBackgroundPermission();
    });

    // Permission mode is set but no task started (no active session).
    expect(result.current.backgroundPermissionMode).toBe('granted');
    expect(mockStartLocationUpdates).not.toHaveBeenCalled();
  });

  it('starts background task after session starts and permission is granted', async () => {
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);
    mockRequestBackground.mockResolvedValueOnce({
      status: ExpoLocation.PermissionStatus.GRANTED,
      granted: true,
      expires: 'never',
      canAskAgain: true,
    });

    const { result } = renderHook();

    await act(async () => {
      await result.current.startSession();
    });

    await act(async () => {
      await result.current.requestBackgroundPermission();
    });

    expect(result.current.status).toBe('sharing');
    expect(mockStartLocationUpdates).toHaveBeenCalledTimes(1);
  });
});

// ────────────────────────────────────────────────────────────────────────────
// Duplicate background tasks are not registered
// ────────────────────────────────────────────────────────────────────────────

describe('duplicate background tasks are not registered', () => {
  it('does not start a second background task if one is already running', async () => {
    // Simulate task already running.
    mockHasStartedLocationUpdates.mockResolvedValue(true);
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);
    mockRequestBackground.mockResolvedValueOnce({
      status: ExpoLocation.PermissionStatus.GRANTED,
      granted: true,
      expires: 'never',
      canAskAgain: true,
    });

    const { result } = renderHook();

    await act(async () => {
      await result.current.startSession();
    });

    await act(async () => {
      await result.current.requestBackgroundPermission();
    });

    // startLocationUpdatesAsync should not be called because hasStartedLocationUpdatesAsync
    // returns true — the start helper skips registering a duplicate.
    expect(mockStartLocationUpdates).not.toHaveBeenCalled();
  });
});

// ────────────────────────────────────────────────────────────────────────────
// Stopping sharing stops the background task immediately
// ────────────────────────────────────────────────────────────────────────────

describe('stopping sharing stops the background task', () => {
  it('stops background location updates when stopSession is called', async () => {
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);
    mockStop.mockResolvedValueOnce(MOCK_STOP_RESPONSE);
    mockRequestBackground.mockResolvedValueOnce({
      status: ExpoLocation.PermissionStatus.GRANTED,
      granted: true,
      expires: 'never',
      canAskAgain: true,
    });
    // Simulate task currently running so stopLocationUpdatesAsync is called.
    mockHasStartedLocationUpdates.mockResolvedValue(true);

    const { result } = renderHook();

    await act(async () => {
      await result.current.startSession();
    });

    await act(async () => {
      await result.current.requestBackgroundPermission();
    });

    await act(async () => {
      await result.current.stopSession();
    });

    expect(mockStopLocationUpdates).toHaveBeenCalledTimes(1);
    expect(result.current.status).toBe('not_sharing');
    expect(result.current.backgroundPermissionMode).toBe('not_requested');
  });

  it('stops background updates before calling the backend stop endpoint', async () => {
    const callOrder: string[] = [];
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);
    mockStop.mockImplementationOnce(async (..._args) => {
      callOrder.push('backend-stop');
      return MOCK_STOP_RESPONSE;
    });
    mockHasStartedLocationUpdates.mockResolvedValue(true);
    mockStopLocationUpdates.mockImplementationOnce(async () => {
      callOrder.push('bg-stop');
    });
    mockRequestBackground.mockResolvedValueOnce({
      status: ExpoLocation.PermissionStatus.GRANTED,
      granted: true,
      expires: 'never',
      canAskAgain: true,
    });

    const { result } = renderHook();

    await act(async () => {
      await result.current.startSession();
    });

    await act(async () => {
      await result.current.requestBackgroundPermission();
    });

    await act(async () => {
      await result.current.stopSession();
    });

    // Background task must be stopped before the backend call.
    expect(callOrder.indexOf('bg-stop')).toBeLessThan(callOrder.indexOf('backend-stop'));
  });
});

// ────────────────────────────────────────────────────────────────────────────
// hide-me-now stops the background task immediately
// ────────────────────────────────────────────────────────────────────────────

describe('hide-me-now stops the background task immediately', () => {
  it('stops background location updates when hideMeNow is called', async () => {
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);
    mockHide.mockResolvedValueOnce(MOCK_HIDE_RESPONSE);
    mockRequestBackground.mockResolvedValueOnce({
      status: ExpoLocation.PermissionStatus.GRANTED,
      granted: true,
      expires: 'never',
      canAskAgain: true,
    });
    mockHasStartedLocationUpdates.mockResolvedValue(true);

    const { result } = renderHook();

    await act(async () => {
      await result.current.startSession();
    });

    await act(async () => {
      await result.current.requestBackgroundPermission();
    });

    await act(async () => {
      await result.current.hideMeNow();
    });

    expect(mockStopLocationUpdates).toHaveBeenCalledTimes(1);
    expect(result.current.status).toBe('not_sharing');
  });

  it('stops background updates before calling the backend hide endpoint', async () => {
    const callOrder: string[] = [];
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);
    mockHide.mockImplementationOnce(async () => {
      callOrder.push('backend-hide');
      return MOCK_HIDE_RESPONSE;
    });
    mockHasStartedLocationUpdates.mockResolvedValue(true);
    mockStopLocationUpdates.mockImplementationOnce(async () => {
      callOrder.push('bg-stop');
    });
    mockRequestBackground.mockResolvedValueOnce({
      status: ExpoLocation.PermissionStatus.GRANTED,
      granted: true,
      expires: 'never',
      canAskAgain: true,
    });

    const { result } = renderHook();

    await act(async () => {
      await result.current.startSession();
    });

    await act(async () => {
      await result.current.requestBackgroundPermission();
    });

    await act(async () => {
      await result.current.hideMeNow();
    });

    expect(callOrder.indexOf('bg-stop')).toBeLessThan(callOrder.indexOf('backend-hide'));
  });
});

// ────────────────────────────────────────────────────────────────────────────
// Logout (unmount) stops the background task
// ────────────────────────────────────────────────────────────────────────────

describe('logout stops the background task', () => {
  it('stops background location updates when the hook unmounts', async () => {
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);
    mockRequestBackground.mockResolvedValueOnce({
      status: ExpoLocation.PermissionStatus.GRANTED,
      granted: true,
      expires: 'never',
      canAskAgain: true,
    });
    mockHasStartedLocationUpdates.mockResolvedValue(true);

    let renderer!: ReturnType<typeof TestRenderer.create>;

    function TestComponent() {
      useLiveLocationSession();
      return null;
    }

    await act(async () => {
      renderer = TestRenderer.create(<TestComponent />);
    });

    // Unmount — simulates logout via AuthenticatedLiveLocationProvider.
    await act(async () => {
      renderer.unmount();
      await new Promise((r) => setTimeout(r, 0));
    });

    // stopBackgroundLocationUpdates is called on cleanup.
    expect(mockStopLocationUpdates).toHaveBeenCalled();
  });
});

// ────────────────────────────────────────────────────────────────────────────
// Session restoration — expired backend session stops local task
// ────────────────────────────────────────────────────────────────────────────

describe('session restoration — expired session clears state and stops task', () => {
  it('clears state and stops background task when stored session is expired on mount', async () => {
    const expiredSession = JSON.stringify({
      sessionId: 'expired-session',
      expiresAt: new Date(Date.now() - 1000).toISOString(), // 1 second in the past
      apiBaseUrl: 'http://localhost:3000',
    });
    mockSecureStoreGet.mockResolvedValueOnce(expiredSession);
    mockHasStartedLocationUpdates.mockResolvedValue(true);

    await act(async () => {
      renderHook();
      await new Promise((r) => setTimeout(r, 10));
    });

    // Expired session should not restore sharing state.
    // (The mock settle time allows the async restoration to complete.)
    expect(mockStopLocationUpdates).toHaveBeenCalled();
  });

  it('restores session state when stored session is still valid on mount', async () => {
    const validSession = JSON.stringify({
      sessionId: 'valid-session',
      expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
      apiBaseUrl: 'http://localhost:3000',
    });
    mockSecureStoreGet.mockResolvedValueOnce(validSession);
    // No background permission.
    mockGetBackground.mockResolvedValueOnce({
      status: ExpoLocation.PermissionStatus.DENIED,
      granted: false,
      expires: 'never',
      canAskAgain: true,
    });

    const result: { current: UseLiveLocationSessionResult } = {
      current: undefined as unknown as UseLiveLocationSessionResult,
    };

    function TestComponent() {
      result.current = useLiveLocationSession();
      return null;
    }

    await act(async () => {
      TestRenderer.create(<TestComponent />);
      await new Promise((r) => setTimeout(r, 10));
    });

    expect(result.current.sessionId).toBe('valid-session');
    expect(result.current.status).toBe('sharing');
  });
});

// ────────────────────────────────────────────────────────────────────────────
// Coordinate and token privacy — nothing sensitive is logged
// ────────────────────────────────────────────────────────────────────────────

describe('coordinate and token privacy', () => {
  it('does not log exact coordinates during background permission flow', async () => {
    const consoleSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);
    mockRequestBackground.mockResolvedValueOnce({
      status: ExpoLocation.PermissionStatus.GRANTED,
      granted: true,
      expires: 'never',
      canAskAgain: true,
    });

    const { result } = renderHook();

    await act(async () => {
      await result.current.startSession();
    });

    await act(async () => {
      await result.current.requestBackgroundPermission();
    });

    // No log calls should contain coordinate-like values.
    const loggedSensitive = consoleSpy.mock.calls.some((args) =>
      args.some(
        (arg) =>
          typeof arg === 'string' &&
          (/\d{2}\.\d{4,}/.test(arg) || arg.toLowerCase().includes('token')),
      ),
    );
    expect(loggedSensitive).toBe(false);

    consoleSpy.mockRestore();
  });

  it('does not log exact coordinates in the background mode state', async () => {
    const consoleSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);
    mockStop.mockResolvedValueOnce(MOCK_STOP_RESPONSE);
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);

    const { result } = renderHook();

    await act(async () => {
      await result.current.startSession();
    });

    await act(async () => {
      await result.current.stopSession();
    });

    const loggedCoords = consoleSpy.mock.calls.some((args) =>
      args.some(
        (arg) =>
          typeof arg === 'string' && /\d{2,}\.\d{4,}/.test(arg),
      ),
    );
    expect(loggedCoords).toBe(false);

    consoleSpy.mockRestore();
  });
});
