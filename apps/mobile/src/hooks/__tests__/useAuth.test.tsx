/**
 * Tests for useAuth hook and AuthProvider.
 *
 * Uses react-test-renderer so no additional testing libraries are needed.
 * expo-secure-store is mocked via __mocks__/expo-secure-store.js.
 * auth API functions are mocked to isolate hook logic from HTTP calls.
 */

import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';

import type { AuthenticatedUserSummary } from '@carcommunity/shared/auth';

import { AuthProvider, useAuth } from '../useAuth';
import * as authApi from '../../api/auth';
import * as tokenStorage from '../../storage/tokenStorage';

jest.mock('../../config/env', () => ({
  publicEnv: {
    authMode: 'dev',
  },
}));

// Mock the auth API client so no real HTTP calls are made.
jest.mock('../../api/auth', () => ({
  getCurrentUser: jest.fn(),
  loginWithApple: jest.fn(),
  loginWithGoogle: jest.fn(),
  loginWithApplePlaceholder: jest.fn(),
  loginWithGooglePlaceholder: jest.fn(),
  logoutPlaceholder: jest.fn(),
}));

// Mock token storage to isolate from native modules.
jest.mock('../../storage/tokenStorage', () => ({
  loadSessionToken: jest.fn(),
  saveSessionToken: jest.fn(),
  clearSessionToken: jest.fn(),
}));

// eslint-disable-next-line @typescript-eslint/no-require-imports
const envMock = require('../../config/env') as { publicEnv: { authMode: 'dev' | 'native' } };

const mockGetCurrentUser = authApi.getCurrentUser as jest.MockedFunction<typeof authApi.getCurrentUser>;
const mockLoginAppleNative = authApi.loginWithApple as jest.MockedFunction<typeof authApi.loginWithApple>;
const mockLoginGoogleNative = authApi.loginWithGoogle as jest.MockedFunction<typeof authApi.loginWithGoogle>;
const mockLoginApple = authApi.loginWithApplePlaceholder as jest.MockedFunction<typeof authApi.loginWithApplePlaceholder>;
const mockLoginGoogle = authApi.loginWithGooglePlaceholder as jest.MockedFunction<typeof authApi.loginWithGooglePlaceholder>;
const mockLogout = authApi.logoutPlaceholder as jest.MockedFunction<typeof authApi.logoutPlaceholder>;
const mockLoadToken = tokenStorage.loadSessionToken as jest.MockedFunction<typeof tokenStorage.loadSessionToken>;
const mockSaveToken = tokenStorage.saveSessionToken as jest.MockedFunction<typeof tokenStorage.saveSessionToken>;
const mockClearToken = tokenStorage.clearSessionToken as jest.MockedFunction<typeof tokenStorage.clearSessionToken>;

const testUser: AuthenticatedUserSummary = {
  userId: 'user-001',
  identities: [{ provider: 'apple', providerSubject: 'apple-subject-001' }],
  roles: ['user'],
  status: 'active',
  subscriptionEntitlement: 'none',
  displayName: 'Test User',
  avatarUrl: null,
};

function renderAuthHook() {
  const result: { current: ReturnType<typeof useAuth> } = {
    current: undefined as unknown as ReturnType<typeof useAuth>,
  };

  function TestComponent() {
    result.current = useAuth();
    return null;
  }

  act(() => {
    TestRenderer.create(
      <AuthProvider>
        <TestComponent />
      </AuthProvider>,
    );
  });

  return { result };
}

beforeEach(() => {
  jest.clearAllMocks();
  envMock.publicEnv.authMode = 'dev';
  // Default: no stored session
  mockLoadToken.mockResolvedValue(null);
  mockSaveToken.mockResolvedValue(undefined);
  mockClearToken.mockResolvedValue(undefined);
  mockLogout.mockResolvedValue({ ok: true, data: { revoked: true } });
});

describe('useAuth — initial unauthenticated state', () => {
  it('starts unauthenticated when no stored session exists', async () => {
    const { result } = renderAuthHook();

    // Wait for async session restore to settle
    await act(async () => {
      await Promise.resolve();
    });

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.currentUser).toBeNull();
  });

  it('is not in loading state after session restore completes', async () => {
    const { result } = renderAuthHook();

    await act(async () => {
      await Promise.resolve();
    });

    expect(result.current.isLoading).toBe(false);
  });

  it('has no error on initial load', async () => {
    const { result } = renderAuthHook();

    await act(async () => {
      await Promise.resolve();
    });

    expect(result.current.error).toBeNull();
  });
});

describe('useAuth — session restore', () => {
  it('restores session from secure storage and verifies with backend', async () => {
    mockLoadToken.mockResolvedValue({ token: 'stored-token', sessionId: 'session-001' });
    mockGetCurrentUser.mockResolvedValue({ ok: true, data: { user: testUser, session: { sessionId: 'session-001', expiresAt: '' } } });

    const { result } = renderAuthHook();

    await act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });

    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.currentUser?.userId).toBe('user-001');
  });

  it('stays unauthenticated when auth/me returns an error', async () => {
    mockLoadToken.mockResolvedValue({ token: 'stale-token', sessionId: 'session-old' });
    mockGetCurrentUser.mockResolvedValue({ ok: false, error: { code: 'unauthenticated', message: 'Session expired' } });

    const { result } = renderAuthHook();

    await act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.currentUser).toBeNull();
    expect(mockClearToken).toHaveBeenCalled();
  });

  it('does not block app startup when session restore throws', async () => {
    mockLoadToken.mockRejectedValue(new Error('SecureStore unavailable'));

    const { result } = renderAuthHook();

    await act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });

    expect(result.current.isLoading).toBe(false);
    expect(result.current.isAuthenticated).toBe(false);
  });
});

describe('useAuth — login action', () => {
  it('calls Apple login and sets authenticated state on success', async () => {
    mockLoginApple.mockResolvedValue({
      ok: true,
      data: {
        user: testUser,
        session: { sessionId: 'session-new', expiresAt: '2099-01-01T00:00:00Z' },
      },
    });

    const { result } = renderAuthHook();

    await act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });

    await act(async () => {
      await result.current.login('apple', 'placeholder-identity-token');
    });

    expect(mockLoginApple).toHaveBeenCalledWith('placeholder-identity-token');
    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.currentUser?.userId).toBe('user-001');
  });

  it('calls Google login for the google provider', async () => {
    mockLoginGoogle.mockResolvedValue({
      ok: true,
      data: {
        user: testUser,
        session: { sessionId: 'session-google', expiresAt: '2099-01-01T00:00:00Z' },
      },
    });

    const { result } = renderAuthHook();

    await act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });

    await act(async () => {
      await result.current.login('google', 'placeholder-identity-token');
    });

    expect(mockLoginGoogle).toHaveBeenCalledWith('placeholder-identity-token');
    expect(result.current.isAuthenticated).toBe(true);
  });

  it('saves session token securely after successful login', async () => {
    mockLoginApple.mockResolvedValue({
      ok: true,
      data: {
        user: testUser,
        session: { sessionId: 'session-abc', expiresAt: '2099-01-01T00:00:00Z' },
      },
    });

    const { result } = renderAuthHook();

    await act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });

    await act(async () => {
      await result.current.login('apple', 'placeholder-identity-token');
    });

    expect(mockSaveToken).toHaveBeenCalled();
  });

  it('does not expose the session token via the auth context', async () => {
    mockLoginApple.mockResolvedValue({
      ok: true,
      data: {
        user: testUser,
        session: { sessionId: 'session-secret', expiresAt: '2099-01-01T00:00:00Z' },
      },
    });

    const { result } = renderAuthHook();

    await act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });

    await act(async () => {
      await result.current.login('apple', 'placeholder-identity-token');
    });

    const contextKeys = Object.keys(result.current);
    expect(contextKeys).not.toContain('sessionToken');
    expect(contextKeys).not.toContain('token');
  });

  it('sets error state when login returns an error response', async () => {
    mockLoginApple.mockResolvedValue({
      ok: false,
      error: { code: 'internal_error', message: 'Backend error' },
    });

    const { result } = renderAuthHook();

    await act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });

    await act(async () => {
      await result.current.login('apple', 'placeholder-identity-token');
    });

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.error).toBe('auth.errorGeneric');
  });

  it('sets error state when login throws a network error', async () => {
    mockLoginApple.mockRejectedValue(new Error('Network error'));

    const { result } = renderAuthHook();

    await act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });

    await act(async () => {
      await result.current.login('apple', 'placeholder-identity-token');
    });

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.error).toBe('auth.errorGeneric');
  });

  it('uses native Apple login when authMode is native', async () => {
    envMock.publicEnv.authMode = 'native';
    mockLoginAppleNative.mockResolvedValue({
      ok: true,
      data: {
        user: testUser,
        session: { sessionId: 'session-native-apple', expiresAt: '2099-01-01T00:00:00Z' },
      },
    });

    const { result } = renderAuthHook();

    await act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });

    await act(async () => {
      await result.current.login('apple', 'native-identity-token');
    });

    expect(mockLoginAppleNative).toHaveBeenCalledWith('native-identity-token');
    expect(mockLoginApple).not.toHaveBeenCalled();
  });

  it('uses native Google login when authMode is native', async () => {
    envMock.publicEnv.authMode = 'native';
    mockLoginGoogleNative.mockResolvedValue({
      ok: true,
      data: {
        user: testUser,
        session: { sessionId: 'session-native-google', expiresAt: '2099-01-01T00:00:00Z' },
      },
    });

    const { result } = renderAuthHook();

    await act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });

    await act(async () => {
      await result.current.login('google', 'native-identity-token');
    });

    expect(mockLoginGoogleNative).toHaveBeenCalledWith('native-identity-token');
    expect(mockLoginGoogle).not.toHaveBeenCalled();
  });
});

describe('useAuth — logout action', () => {
  it('clears local session state on logout', async () => {
    mockLoginApple.mockResolvedValue({
      ok: true,
      data: {
        user: testUser,
        session: { sessionId: 'session-to-clear', expiresAt: '2099-01-01T00:00:00Z' },
      },
    });

    const { result } = renderAuthHook();

    await act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });

    await act(async () => {
      await result.current.login('apple', 'placeholder-identity-token');
    });

    expect(result.current.isAuthenticated).toBe(true);

    await act(async () => {
      await result.current.logout();
    });

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.currentUser).toBeNull();
  });

  it('clears stored token on logout', async () => {
    mockLoginApple.mockResolvedValue({
      ok: true,
      data: {
        user: testUser,
        session: { sessionId: 'session-to-clear', expiresAt: '2099-01-01T00:00:00Z' },
      },
    });

    const { result } = renderAuthHook();

    await act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });

    await act(async () => {
      await result.current.login('apple', 'placeholder-identity-token');
    });

    mockClearToken.mockClear();

    await act(async () => {
      await result.current.logout();
    });

    expect(mockClearToken).toHaveBeenCalled();
  });

  it('clears local session even when logout API call fails', async () => {
    mockLoginApple.mockResolvedValue({
      ok: true,
      data: {
        user: testUser,
        session: { sessionId: 'session-to-clear', expiresAt: '2099-01-01T00:00:00Z' },
      },
    });
    mockLogout.mockRejectedValue(new Error('Backend unreachable'));

    const { result } = renderAuthHook();

    await act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });

    await act(async () => {
      await result.current.login('apple', 'placeholder-identity-token');
    });

    await act(async () => {
      await result.current.logout();
    });

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.currentUser).toBeNull();
  });
});

describe('useAuth — refreshCurrentUser', () => {
  it('signs out locally when auth/me fails during refresh', async () => {
    mockLoginApple.mockResolvedValue({
      ok: true,
      data: {
        user: testUser,
        session: { sessionId: 'session-refresh', expiresAt: '2099-01-01T00:00:00Z' },
      },
    });
    mockGetCurrentUser.mockResolvedValue({ ok: false, error: { code: 'unauthenticated', message: 'Session expired' } });

    const { result } = renderAuthHook();

    await act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });

    await act(async () => {
      await result.current.login('apple', 'placeholder-identity-token');
    });

    await act(async () => {
      await result.current.refreshCurrentUser();
    });

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.currentUser).toBeNull();
  });
});

describe('useAuth — token security', () => {
  it('does not log the session token during login', async () => {
    const consoleSpy = jest.spyOn(console, 'log');
    const consoleErrorSpy = jest.spyOn(console, 'error');
    const consoleWarnSpy = jest.spyOn(console, 'warn');

    mockLoginApple.mockResolvedValue({
      ok: true,
      data: {
        user: testUser,
        session: { sessionId: 'secret-session-id', expiresAt: '2099-01-01T00:00:00Z' },
      },
    });

    const { result } = renderAuthHook();

    await act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });

    await act(async () => {
      await result.current.login('apple', 'placeholder-identity-token');
    });

    const allLoggedArgs = [
      ...consoleSpy.mock.calls.flat(),
      ...consoleErrorSpy.mock.calls.flat(),
      ...consoleWarnSpy.mock.calls.flat(),
    ].map((arg) => (typeof arg === 'string' ? arg : JSON.stringify(arg)));

    for (const logged of allLoggedArgs) {
      expect(logged).not.toContain('secret-session-id');
    }

    consoleSpy.mockRestore();
    consoleErrorSpy.mockRestore();
    consoleWarnSpy.mockRestore();
  });
});
