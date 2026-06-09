/**
 * Tests for useLiveLocationSession hook.
 *
 * Uses react-test-renderer (bundled with React Native / jest-expo) to render
 * the hook in a test component without requiring additional testing libraries.
 */

import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';

import type { UseLiveLocationSessionResult } from '../useLiveLocationSession';
import { useLiveLocationSession } from '../useLiveLocationSession';
import * as liveLocationApi from '../../api/live-location';

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

beforeEach(() => {
  jest.clearAllMocks();
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

describe('useLiveLocationSession — startSession', () => {
  it('calls startLiveLocationSession with the selected duration', async () => {
    mockStart.mockResolvedValueOnce({
      ok: true,
      data: {
        session: {
          id: 'session-abc',
          status: 'active',
          duration: '1h',
          startedAt: new Date().toISOString(),
          expiresAt: new Date().toISOString(),
          stoppedAt: null,
        },
        latestPosition: null,
        latestPositionRemoved: false,
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

    expect(mockStart).toHaveBeenCalledTimes(1);
    expect(mockStart).toHaveBeenCalledWith({ duration: '1h' });
    expect(result.current.status).toBe('sharing');
    expect(result.current.sessionId).toBe('session-abc');
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
    mockStart.mockResolvedValueOnce({
      ok: true,
      data: {
        session: {
          id: 'session-xyz',
          status: 'active',
          duration: '1h',
          startedAt: new Date().toISOString(),
          expiresAt: new Date().toISOString(),
          stoppedAt: null,
        },
        latestPosition: null,
        latestPositionRemoved: false,
      },
      meta: { source: 'database', productionReady: false, ttlCleanupPrepared: false },
    });
    mockStop.mockResolvedValueOnce({
      ok: true,
      data: {
        session: {
          id: 'session-xyz',
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
    expect(mockStop).toHaveBeenCalledWith('session-xyz', { reason: 'user_stop' });
    expect(result.current.status).toBe('not_sharing');
    expect(result.current.sessionId).toBeNull();
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
