/**
 * Tests for useLiveLocationSession hook.
 *
 * Uses react-test-renderer (bundled with React Native / jest-expo) to render
 * the hook in a test component without requiring additional testing libraries.
 *
 * expo-location is mocked via moduleNameMapper (see package.json jest config).
 */

import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';

import type { UseLiveLocationSessionResult } from '../useLiveLocationSession';
import { useLiveLocationSession } from '../useLiveLocationSession';
import * as liveLocationApi from '../../api/live-location';
import * as ExpoLocation from 'expo-location';

// Mock the live location API client module so no real HTTP requests are made.
jest.mock('../../api/live-location', () => ({
  startLiveLocationSession: jest.fn(),
  stopLiveLocationSession: jest.fn(),
  hideMeNow: jest.fn(),
  loadLiveLocationMarkers: jest.fn(),
  updateLiveLocationPosition: jest.fn(),
}));

// Helper: capture the hook return value from a minimal test component.
function renderLiveLocationHook(): { result: { current: UseLiveLocationSessionResult } } {
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
const mockRequestPermission =
  ExpoLocation.requestForegroundPermissionsAsync as jest.MockedFunction<
    typeof ExpoLocation.requestForegroundPermissionsAsync
  >;
const mockWatchPosition = ExpoLocation.watchPositionAsync as jest.MockedFunction<
  typeof ExpoLocation.watchPositionAsync
>;

const MOCK_SESSION_RESPONSE = {
  ok: true as const,
  data: {
    session: {
      id: 'session-abc',
      status: 'active' as const,
      duration: '1h' as const,
      startedAt: new Date().toISOString(),
      expiresAt: new Date().toISOString(),
      stoppedAt: null,
    },
    latestPosition: null,
    latestPositionRemoved: false,
  },
  meta: { source: 'database' as const, productionReady: false, ttlCleanupPrepared: false },
};

beforeEach(() => {
  jest.clearAllMocks();
  // Default: permission granted, watcher returns a removable subscription.
  mockRequestPermission.mockResolvedValue({
    status: ExpoLocation.PermissionStatus.GRANTED,
    granted: true,
    expires: 'never',
    canAskAgain: true,
  });
  mockWatchPosition.mockResolvedValue({ remove: jest.fn() });
  mockUpdatePosition.mockResolvedValue({ ...MOCK_SESSION_RESPONSE, data: { ...MOCK_SESSION_RESPONSE.data } });
});

describe('useLiveLocationSession — initial state', () => {
  it('starts in not_sharing status', () => {
    const { result } = renderLiveLocationHook();
    expect(result.current.status).toBe('not_sharing');
  });

  it('starts with 1h as the default selected duration', () => {
    const { result } = renderLiveLocationHook();
    expect(result.current.selectedDuration).toBe('1h');
  });

  it('starts with no session id', () => {
    const { result } = renderLiveLocationHook();
    expect(result.current.sessionId).toBeNull();
  });

  it('starts with no error', () => {
    const { result } = renderLiveLocationHook();
    expect(result.current.error).toBeNull();
  });

  it('starts with isLoading false', () => {
    const { result } = renderLiveLocationHook();
    expect(result.current.isLoading).toBe(false);
  });

  it('starts with no current position', () => {
    const { result } = renderLiveLocationHook();
    expect(result.current.currentPosition).toBeNull();
  });

  it('starts with no lastUpdatedAt', () => {
    const { result } = renderLiveLocationHook();
    expect(result.current.lastUpdatedAt).toBeNull();
  });
});

describe('useLiveLocationSession — duration selection', () => {
  it('allows selecting 2h duration', () => {
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

    act(() => {
      result.current.selectDuration('2h');
    });

    expect(result.current.selectedDuration).toBe('2h');
  });

  it('allows selecting 4h duration', () => {
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

    act(() => {
      result.current.selectDuration('4h');
    });

    expect(result.current.selectedDuration).toBe('4h');
  });
});

describe('useLiveLocationSession — permission', () => {
  it('requests foreground permission when starting sharing', async () => {
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);

    const { result } = renderLiveLocationHook();

    await act(async () => {
      await result.current.startSession();
    });

    expect(mockRequestPermission).toHaveBeenCalledTimes(1);
  });

  it('does not request permission before the user starts sharing', () => {
    renderLiveLocationHook();
    expect(mockRequestPermission).not.toHaveBeenCalled();
  });

  it('sets permission_denied status and does not start session when permission is denied', async () => {
    mockRequestPermission.mockResolvedValueOnce({
      status: ExpoLocation.PermissionStatus.DENIED,
      granted: false,
      expires: 'never',
      canAskAgain: false,
    });

    const { result } = renderLiveLocationHook();

    await act(async () => {
      await result.current.startSession();
    });

    expect(result.current.status).toBe('permission_denied');
    expect(mockStart).not.toHaveBeenCalled();
  });

  it('does not start watcher when permission is denied', async () => {
    mockRequestPermission.mockResolvedValueOnce({
      status: ExpoLocation.PermissionStatus.DENIED,
      granted: false,
      expires: 'never',
      canAskAgain: false,
    });

    const { result } = renderLiveLocationHook();

    await act(async () => {
      await result.current.startSession();
    });

    expect(mockWatchPosition).not.toHaveBeenCalled();
  });
});

describe('useLiveLocationSession — startSession', () => {
  it('calls startLiveLocationSession with the selected duration', async () => {
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);

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

    await act(async () => {
      await result.current.startSession();
    });

    expect(mockStart).toHaveBeenCalledTimes(1);
    expect(mockStart).toHaveBeenCalledWith({ duration: '1h' });
    expect(result.current.status).toBe('sharing');
    expect(result.current.sessionId).toBe('session-abc');
  });

  it('starts GPS watcher after successful session start', async () => {
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);

    const { result } = renderLiveLocationHook();

    await act(async () => {
      await result.current.startSession();
    });

    expect(mockWatchPosition).toHaveBeenCalledTimes(1);
    // Watcher should be started with throttle options.
    const [options] = mockWatchPosition.mock.calls[0] as Parameters<typeof ExpoLocation.watchPositionAsync>;
    expect(options.timeInterval).toBeGreaterThan(0);
    expect(options.distanceInterval).toBeGreaterThan(0);
  });

  it('sends position update only with expected coordinate fields when watcher fires', async () => {
    const mockRemove = jest.fn();
    let capturedCallback: Parameters<typeof ExpoLocation.watchPositionAsync>[1] | null = null;

    mockWatchPosition.mockImplementationOnce(async (_options, callback) => {
      capturedCallback = callback;
      return { remove: mockRemove };
    });
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);
    mockUpdatePosition.mockResolvedValue({ ...MOCK_SESSION_RESPONSE });

    const { result } = renderLiveLocationHook();

    await act(async () => {
      await result.current.startSession();
    });

    expect(capturedCallback).not.toBeNull();

    // Simulate a location event from the OS.
    await act(async () => {
      capturedCallback!({
        coords: {
          latitude: 57.51,
          longitude: 12.07,
          accuracy: 8,
          heading: 90,
          speed: 10,
          altitude: 0,
          altitudeAccuracy: 5,
        },
        timestamp: Date.now(),
        mocked: false,
      });
    });

    expect(mockUpdatePosition).toHaveBeenCalledTimes(1);
    const [, body] = mockUpdatePosition.mock.calls[0]!;
    // Only expected coordinate fields — no extra data, no logging.
    expect(body.coordinate).toMatchObject({
      latitude: 57.51,
      longitude: 12.07,
    });
    expect(typeof body.coordinate.recordedAt).toBe('string');
    // No raw coords object leakage beyond the expected fields.
    expect(Object.keys(body)).toEqual(['coordinate']);
  });

  it('sets error state when startLiveLocationSession rejects', async () => {
    mockStart.mockRejectedValueOnce(new Error('Network error'));

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

    await act(async () => {
      await result.current.startSession();
    });

    expect(result.current.status).toBe('error');
    expect(result.current.error).toBe('liveLocation.error');
  });
});

describe('useLiveLocationSession — stopSession', () => {
  it('calls stopLiveLocationSession with the active session id', async () => {
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);
    mockStop.mockResolvedValueOnce({
      ok: true,
      data: {
        session: {
          id: 'session-abc',
          status: 'stopped',
          duration: '1h',
          startedAt: new Date().toISOString(),
          expiresAt: new Date().toISOString(),
          stoppedAt: new Date().toISOString(),
        },
        latestPosition: null,
        latestPositionRemoved: true,
      },
      meta: { source: 'database', productionReady: false, ttlCleanupPrepared: false },
    });

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

    await act(async () => {
      await result.current.startSession();
    });

    await act(async () => {
      await result.current.stopSession();
    });

    expect(mockStop).toHaveBeenCalledTimes(1);
    expect(mockStop).toHaveBeenCalledWith('session-abc', { reason: 'user_stop' });
    expect(result.current.status).toBe('not_sharing');
    expect(result.current.sessionId).toBeNull();
  });

  it('removes GPS watcher when stopping the session', async () => {
    const mockRemove = jest.fn();
    mockWatchPosition.mockResolvedValueOnce({ remove: mockRemove });
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);
    mockStop.mockResolvedValueOnce({
      ok: true,
      data: {
        session: {
          id: 'session-abc',
          status: 'stopped',
          duration: '1h',
          startedAt: new Date().toISOString(),
          expiresAt: new Date().toISOString(),
          stoppedAt: new Date().toISOString(),
        },
        latestPosition: null,
        latestPositionRemoved: true,
      },
      meta: { source: 'database', productionReady: false, ttlCleanupPrepared: false },
    });

    const { result } = renderLiveLocationHook();

    await act(async () => {
      await result.current.startSession();
    });

    await act(async () => {
      await result.current.stopSession();
    });

    expect(mockRemove).toHaveBeenCalledTimes(1);
    expect(result.current.currentPosition).toBeNull();
    expect(result.current.lastUpdatedAt).toBeNull();
  });
});

describe('useLiveLocationSession — hideMeNow', () => {
  it('calls hideMeNow API and returns to not_sharing', async () => {
    mockHide.mockResolvedValueOnce({
      ok: true,
      data: { stoppedSessionCount: 1, removedLatestPositionCount: 1 },
      meta: { source: 'database', productionReady: false, ttlCleanupPrepared: false },
    });

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

    await act(async () => {
      await result.current.hideMeNow();
    });

    expect(mockHide).toHaveBeenCalledTimes(1);
    expect(result.current.status).toBe('not_sharing');
    expect(result.current.sessionId).toBeNull();
  });

  it('removes GPS watcher when hiding', async () => {
    const mockRemove = jest.fn();
    mockWatchPosition.mockResolvedValueOnce({ remove: mockRemove });
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);
    mockHide.mockResolvedValueOnce({
      ok: true,
      data: { stoppedSessionCount: 1, removedLatestPositionCount: 1 },
      meta: { source: 'database', productionReady: false, ttlCleanupPrepared: false },
    });

    const { result } = renderLiveLocationHook();

    await act(async () => {
      await result.current.startSession();
    });

    await act(async () => {
      await result.current.hideMeNow();
    });

    expect(mockRemove).toHaveBeenCalledTimes(1);
    expect(result.current.currentPosition).toBeNull();
  });

  it('sets error state when hideMeNow rejects', async () => {
    mockHide.mockRejectedValueOnce(new Error('Server unavailable'));

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

    await act(async () => {
      await result.current.hideMeNow();
    });

    expect(result.current.status).toBe('error');
    expect(result.current.error).toBe('liveLocation.error');
  });
});

describe('useLiveLocationSession — coordinate privacy', () => {
  it('does not log coordinates when the watcher fires', async () => {
    const consoleSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);

    let capturedCallback: Parameters<typeof ExpoLocation.watchPositionAsync>[1] | null = null;
    mockWatchPosition.mockImplementationOnce(async (_options, callback) => {
      capturedCallback = callback;
      return { remove: jest.fn() };
    });
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);
    mockUpdatePosition.mockResolvedValue({ ...MOCK_SESSION_RESPONSE });

    const { result } = renderLiveLocationHook();

    await act(async () => {
      await result.current.startSession();
    });

    await act(async () => {
      capturedCallback!({
        coords: {
          latitude: 57.51,
          longitude: 12.07,
          accuracy: 8,
          heading: null,
          speed: null,
          altitude: null,
          altitudeAccuracy: null,
        },
        timestamp: Date.now(),
        mocked: false,
      });
    });

    // console.log should not have been called with coordinate values.
    const loggedWithCoords = consoleSpy.mock.calls.some(
      (args) =>
        args.some(
          (arg) =>
            typeof arg === 'string' && (arg.includes('57.51') || arg.includes('12.07')),
        ),
    );
    expect(loggedWithCoords).toBe(false);

    consoleSpy.mockRestore();
  });
});

describe('useLiveLocationSession — throttle configuration', () => {
  it('starts watcher with non-zero interval and distance throttle', async () => {
    mockStart.mockResolvedValueOnce(MOCK_SESSION_RESPONSE);

    const { result } = renderLiveLocationHook();

    await act(async () => {
      await result.current.startSession();
    });

    expect(mockWatchPosition).toHaveBeenCalledTimes(1);
    const [options] = mockWatchPosition.mock.calls[0] as Parameters<typeof ExpoLocation.watchPositionAsync>;
    expect((options.timeInterval ?? 0)).toBeGreaterThanOrEqual(5000);
    expect((options.distanceInterval ?? 0)).toBeGreaterThanOrEqual(25);
  });
});
