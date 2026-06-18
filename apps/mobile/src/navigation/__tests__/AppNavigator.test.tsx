/**
 * Tests for AppNavigator routing flow.
 *
 * Covers:
 *   - Loading state shows the loading indicator.
 *   - Logged-out users are routed to the Login screen.
 *   - Logged-in users who have not completed onboarding are routed to Onboarding.
 *   - Fully onboarded users are routed to the main app shell (home screen).
 *
 * All screen components are replaced with minimal stubs, and the React Navigation
 * layer is mocked as simple passthroughs so that routing tests do not trigger the
 * react / react-native-renderer version mismatch that occurs when NavigationContainer
 * is rendered with TestRenderer in this test environment.
 */

import TestRenderer, { act } from 'react-test-renderer';

import { AppThemeProvider } from '../../hooks/useAppTheme';
import { I18nProvider } from '../../hooks/useI18n';

// ── Navigation mocks ──────────────────────────────────────────────────────────
// Bypass NavigationContainer entirely so the native renderer is never invoked.
// Stack.Navigator renders its children directly; Stack.Screen renders its component.
// Tab.Navigator renders only its first Screen child so the Home tab is active.

jest.mock('@react-navigation/native', () => ({
  NavigationContainer: ({ children }: { children: unknown }) => children,
  DarkTheme: {
    dark: true,
    colors: { primary: '#fff', background: '#000', card: '#000', text: '#fff', border: '#333', notification: '#f00' },
    fonts: {},
  },
  DefaultTheme: {
    dark: false,
    colors: { primary: '#000', background: '#fff', card: '#fff', text: '#000', border: '#ccc', notification: '#f00' },
    fonts: {},
  },
  useNavigation: () => ({ navigate: jest.fn() }),
}));

jest.mock('@react-navigation/native-stack', () => ({
  createNativeStackNavigator: () => ({
    Navigator: ({ children }: { children: unknown }) => children,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    Screen: ({ component: Comp }: { component: any }) =>
      // eslint-disable-next-line @typescript-eslint/no-require-imports
      require('react').createElement(Comp),
  }),
}));

jest.mock('@react-navigation/bottom-tabs', () => ({
  createBottomTabNavigator: () => ({
    Navigator: ({ children }: { children: unknown }) => {
      // eslint-disable-next-line @typescript-eslint/no-require-imports
      const React = require('react') as typeof import('react');
      const arr = React.Children.toArray(children as React.ReactNode);
      return (arr[0] ?? null) as React.ReactElement | null;
    },
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    Screen: ({ component: Comp }: { component: any }) =>
      // eslint-disable-next-line @typescript-eslint/no-require-imports
      require('react').createElement(Comp),
  }),
}));

// ── Screen stubs ──────────────────────────────────────────────────────────────
// jest.mock factories are hoisted before imports, so React is accessed via require().

jest.mock('../../screens/LoginScreen', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  LoginScreen: () => require('react').createElement('View', { testID: 'stub-login-screen' }),
}));

jest.mock('../../screens/OnboardingScreen', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  OnboardingScreen: () => require('react').createElement('View', { testID: 'stub-onboarding-screen' }),
}));

jest.mock('../../screens/HomeScreen', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  HomeScreen: () => require('react').createElement('View', { testID: 'stub-home-screen' }),
}));

jest.mock('../../screens/MapScreen', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  MapScreen: () => require('react').createElement('View', { testID: 'stub-map-screen' }),
}));

jest.mock('../../screens/EventsScreen', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  EventsScreen: () => require('react').createElement('View', { testID: 'stub-events-screen' }),
}));

jest.mock('../../screens/ChatScreen', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  ChatScreen: () => require('react').createElement('View', { testID: 'stub-chat-screen' }),
}));

jest.mock('../../screens/ProfileScreen', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  ProfileScreen: () => require('react').createElement('View', { testID: 'stub-profile-screen' }),
}));

jest.mock('../../screens/SettingsScreen', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  SettingsScreen: () => require('react').createElement('View', { testID: 'stub-settings-screen' }),
}));

jest.mock('../../screens/AboutAppScreen', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  AboutAppScreen: () => require('react').createElement('View', { testID: 'stub-about-screen' }),
}));

jest.mock('../../screens/LiveLocationScreen', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  LiveLocationScreen: () => require('react').createElement('View', { testID: 'stub-live-location-screen' }),
}));

jest.mock('../../screens/PrivacySettingsScreen', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  PrivacySettingsScreen: () => require('react').createElement('View', { testID: 'stub-privacy-settings-screen' }),
}));

// ── useAuth mock ──────────────────────────────────────────────────────────────
const mockUseAuth = jest.fn();

jest.mock('../../hooks/useAuth', () => ({
  useAuth: (...args: unknown[]) => mockUseAuth(...args),
}));

// ── Import after mocks ────────────────────────────────────────────────────────
// eslint-disable-next-line @typescript-eslint/no-require-imports
const { AppNavigator } = require('../AppNavigator') as typeof import('../AppNavigator');

function renderNavigator() {
  return TestRenderer.create(
    <AppThemeProvider>
      <I18nProvider locale="sv">
        <AppNavigator />
      </I18nProvider>
    </AppThemeProvider>,
  );
}

/** Fully onboarded user. */
const onboardedUser = {
  id: 'user-1',
  role: 'user' as const,
  status: 'active' as const,
  subscriptionEntitlement: 'none' as const,
  onboardingCompletedAt: new Date().toISOString(),
  displayName: 'Test User',
};

beforeEach(() => {
  jest.clearAllMocks();
});

// ── Loading state ─────────────────────────────────────────────────────────────

describe('AppNavigator — loading state', () => {
  it('shows the loading indicator while the session is being restored', () => {
    mockUseAuth.mockReturnValue({
      currentUser: null,
      isAuthenticated: false,
      isLoading: true,
      error: null,
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderNavigator();
    });

    const loading = renderer!.root.findAll(
      (node) => node.props['testID'] === 'app-loading',
    );
    expect(loading.length).toBeGreaterThan(0);
  });

  it('does not show the Login screen while loading', () => {
    mockUseAuth.mockReturnValue({
      currentUser: null,
      isAuthenticated: false,
      isLoading: true,
      error: null,
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderNavigator();
    });

    const login = renderer!.root.findAll(
      (node) => node.props['testID'] === 'stub-login-screen',
    );
    expect(login.length).toBe(0);
  });
});

// ── Logged-out flow ───────────────────────────────────────────────────────────

describe('AppNavigator — logged-out flow', () => {
  it('shows the Login screen when the user is not authenticated', () => {
    mockUseAuth.mockReturnValue({
      currentUser: null,
      isAuthenticated: false,
      isLoading: false,
      error: null,
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderNavigator();
    });

    const login = renderer!.root.findAll(
      (node) => node.props['testID'] === 'stub-login-screen',
    );
    expect(login.length).toBeGreaterThan(0);
  });

  it('does not show the home screen when the user is not authenticated', () => {
    mockUseAuth.mockReturnValue({
      currentUser: null,
      isAuthenticated: false,
      isLoading: false,
      error: null,
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderNavigator();
    });

    const home = renderer!.root.findAll(
      (node) => node.props['testID'] === 'stub-home-screen',
    );
    expect(home.length).toBe(0);
  });
});

// ── Onboarding flow ───────────────────────────────────────────────────────────

describe('AppNavigator — onboarding flow', () => {
  it('shows the Onboarding screen when authenticated but onboarding is incomplete', () => {
    mockUseAuth.mockReturnValue({
      currentUser: { ...onboardedUser, onboardingCompletedAt: null },
      isAuthenticated: true,
      isLoading: false,
      error: null,
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderNavigator();
    });

    const onboarding = renderer!.root.findAll(
      (node) => node.props['testID'] === 'stub-onboarding-screen',
    );
    expect(onboarding.length).toBeGreaterThan(0);
  });

  it('does not show the home screen when onboarding is incomplete', () => {
    mockUseAuth.mockReturnValue({
      currentUser: { ...onboardedUser, onboardingCompletedAt: null },
      isAuthenticated: true,
      isLoading: false,
      error: null,
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderNavigator();
    });

    const home = renderer!.root.findAll(
      (node) => node.props['testID'] === 'stub-home-screen',
    );
    expect(home.length).toBe(0);
  });

  it('does not show the Login screen when onboarding is incomplete', () => {
    mockUseAuth.mockReturnValue({
      currentUser: { ...onboardedUser, onboardingCompletedAt: null },
      isAuthenticated: true,
      isLoading: false,
      error: null,
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderNavigator();
    });

    const login = renderer!.root.findAll(
      (node) => node.props['testID'] === 'stub-login-screen',
    );
    expect(login.length).toBe(0);
  });
});

// ── Main app shell ────────────────────────────────────────────────────────────

describe('AppNavigator — main app shell', () => {
  it('shows the home screen when authenticated and onboarding is complete', () => {
    mockUseAuth.mockReturnValue({
      currentUser: onboardedUser,
      isAuthenticated: true,
      isLoading: false,
      error: null,
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderNavigator();
    });

    const home = renderer!.root.findAll(
      (node) => node.props['testID'] === 'stub-home-screen',
    );
    expect(home.length).toBeGreaterThan(0);
  });

  it('does not show the Login screen when onboarded', () => {
    mockUseAuth.mockReturnValue({
      currentUser: onboardedUser,
      isAuthenticated: true,
      isLoading: false,
      error: null,
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderNavigator();
    });

    const login = renderer!.root.findAll(
      (node) => node.props['testID'] === 'stub-login-screen',
    );
    expect(login.length).toBe(0);
  });

  it('does not show the Onboarding screen when onboarded', () => {
    mockUseAuth.mockReturnValue({
      currentUser: onboardedUser,
      isAuthenticated: true,
      isLoading: false,
      error: null,
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderNavigator();
    });

    const onboarding = renderer!.root.findAll(
      (node) => node.props['testID'] === 'stub-onboarding-screen',
    );
    expect(onboarding.length).toBe(0);
  });
});
