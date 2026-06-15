/**
 * Tests for LoginScreen.
 *
 * Mocks useAuth and Platform.OS so UI states can be tested
 * without a real backend or native Apple/Google SDKs.
 */

import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';
import { Platform } from 'react-native';

import { AppThemeProvider } from '../../hooks/useAppTheme';
import { I18nProvider } from '../../hooks/useI18n';

// Mock useAuth so tests control auth state independently.
const mockLogin = jest.fn();
const mockLogout = jest.fn();
const mockRefresh = jest.fn();

jest.mock('../../hooks/useAuth', () => ({
  useAuth: jest.fn(() => ({
    currentUser: null,
    isAuthenticated: false,
    isLoading: false,
    error: null,
    login: mockLogin,
    logout: mockLogout,
    refreshCurrentUser: mockRefresh,
  })),
  getPlatformAuthProvider: () => {
    // Inline implementation so requireActual doesn't load the real module (which imports api/auth).
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { Platform } = require('react-native') as typeof import('react-native');
    if (Platform.OS === 'ios') return 'apple';
    if (Platform.OS === 'android') return 'google';
    return null;
  },
}));

// eslint-disable-next-line @typescript-eslint/no-require-imports
const { LoginScreen } = require('../LoginScreen') as typeof import('../LoginScreen');
// eslint-disable-next-line @typescript-eslint/no-require-imports
const { useAuth } = require('../../hooks/useAuth') as typeof import('../../hooks/useAuth');

function renderLoginScreen() {
  return TestRenderer.create(
    <AppThemeProvider>
      <I18nProvider locale="sv">
        <LoginScreen />
      </I18nProvider>
    </AppThemeProvider>,
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  (useAuth as jest.Mock).mockReturnValue({
    currentUser: null,
    isAuthenticated: false,
    isLoading: false,
    error: null,
    login: mockLogin,
    logout: mockLogout,
    refreshCurrentUser: mockRefresh,
  });
});

describe('LoginScreen — renders', () => {
  it('renders the login screen without crashing', () => {
    Platform.OS = 'ios';
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderLoginScreen();
    });

    expect(renderer).not.toBeNull();
  });

  it('shows the logged-out login screen', () => {
    Platform.OS = 'ios';
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderLoginScreen();
    });

    const screen = renderer!.root.findAll(
      (node) => node.props['testID'] === 'login-screen',
    );
    expect(screen.length).toBeGreaterThan(0);
  });
});

describe('LoginScreen — iOS shows Apple login', () => {
  it('shows the Apple login button on iOS', () => {
    Platform.OS = 'ios';
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderLoginScreen();
    });

    const appleButtons = renderer!.root.findAll(
      (node) => node.props['testID'] === 'login-apple-button',
    );
    expect(appleButtons.length).toBeGreaterThan(0);
  });

  it('does not show the Google login button on iOS', () => {
    Platform.OS = 'ios';
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderLoginScreen();
    });

    const googleButtons = renderer!.root.findAll(
      (node) => node.props['testID'] === 'login-google-button',
    );
    expect(googleButtons.length).toBe(0);
  });
});

describe('LoginScreen — Android shows Google login', () => {
  it('shows the Google login button on Android', () => {
    Platform.OS = 'android';
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderLoginScreen();
    });

    const googleButtons = renderer!.root.findAll(
      (node) => node.props['testID'] === 'login-google-button',
    );
    expect(googleButtons.length).toBeGreaterThan(0);
  });

  it('does not show the Apple login button on Android', () => {
    Platform.OS = 'android';
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderLoginScreen();
    });

    const appleButtons = renderer!.root.findAll(
      (node) => node.props['testID'] === 'login-apple-button',
    );
    expect(appleButtons.length).toBe(0);
  });
});

describe('LoginScreen — unsupported platform', () => {
  it('shows unsupported platform message on web', () => {
    Platform.OS = 'web' as typeof Platform.OS;
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderLoginScreen();
    });

    const unsupported = renderer!.root.findAll(
      (node) => node.props['testID'] === 'login-platform-unsupported',
    );
    expect(unsupported.length).toBeGreaterThan(0);
  });
});

describe('LoginScreen — loading state', () => {
  it('shows loading indicator when isLoading is true', () => {
    Platform.OS = 'ios';
    (useAuth as jest.Mock).mockReturnValue({
      currentUser: null,
      isAuthenticated: false,
      isLoading: true,
      error: null,
      login: mockLogin,
      logout: mockLogout,
      refreshCurrentUser: mockRefresh,
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderLoginScreen();
    });

    const loading = renderer!.root.findAll(
      (node) => node.props['testID'] === 'login-loading',
    );
    expect(loading.length).toBeGreaterThan(0);
  });
});

describe('LoginScreen — error state', () => {
  it('shows error message when error is set and not loading', () => {
    Platform.OS = 'ios';
    (useAuth as jest.Mock).mockReturnValue({
      currentUser: null,
      isAuthenticated: false,
      isLoading: false,
      error: 'auth.errorGeneric',
      login: mockLogin,
      logout: mockLogout,
      refreshCurrentUser: mockRefresh,
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderLoginScreen();
    });

    const errors = renderer!.root.findAll(
      (node) => node.props['testID'] === 'login-error',
    );
    expect(errors.length).toBeGreaterThan(0);
  });

  it('does not show error when isLoading is true', () => {
    Platform.OS = 'ios';
    (useAuth as jest.Mock).mockReturnValue({
      currentUser: null,
      isAuthenticated: false,
      isLoading: true,
      error: 'auth.errorGeneric',
      login: mockLogin,
      logout: mockLogout,
      refreshCurrentUser: mockRefresh,
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderLoginScreen();
    });

    const errors = renderer!.root.findAll(
      (node) => node.props['testID'] === 'login-error',
    );
    expect(errors.length).toBe(0);
  });
});

describe('LoginScreen — login action', () => {
  it('calls login with apple provider when Apple button is pressed on iOS', async () => {
    Platform.OS = 'ios';
    mockLogin.mockResolvedValue(undefined);

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderLoginScreen();
    });

    const appleButton = renderer!.root.findAll(
      (node) => node.props['testID'] === 'login-apple-button',
    )[0];

    await act(async () => {
      (appleButton!.props['onPress'] as () => void)();
    });

    expect(mockLogin).toHaveBeenCalledTimes(1);
    expect(mockLogin).toHaveBeenCalledWith('apple', expect.any(String));
  });

  it('calls login with google provider when Google button is pressed on Android', async () => {
    Platform.OS = 'android';
    mockLogin.mockResolvedValue(undefined);

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderLoginScreen();
    });

    const googleButton = renderer!.root.findAll(
      (node) => node.props['testID'] === 'login-google-button',
    )[0];

    await act(async () => {
      (googleButton!.props['onPress'] as () => void)();
    });

    expect(mockLogin).toHaveBeenCalledTimes(1);
    expect(mockLogin).toHaveBeenCalledWith('google', expect.any(String));
  });
});
