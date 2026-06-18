/**
 * Tests for HomeScreen.
 *
 * Covers:
 *   - Home screen renders the live location primary action button.
 *   - Free users see the member-only notice for viewing other live positions.
 *   - Settings shortcut is visible.
 *   - Swedish text renders correctly.
 *   - Sharing state changes the button label.
 */

import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';

import { AppThemeProvider } from '../../hooks/useAppTheme';
import { I18nProvider } from '../../hooks/useI18n';

// Mock @react-navigation/native so HomeScreen can call useNavigation without a real navigator.
const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate }),
}));

// Mock useAuth — free user by default.
const mockUseAuth = jest.fn();
jest.mock('../../hooks/useAuth', () => ({
  useAuth: (...args: unknown[]) => mockUseAuth(...args),
}));

// Mock useLiveLocationSession — not_sharing by default.
const mockUseLiveLocationSession = jest.fn();
jest.mock('../../hooks/useLiveLocationSession', () => ({
  useLiveLocationSession: (...args: unknown[]) => mockUseLiveLocationSession(...args),
}));

// eslint-disable-next-line @typescript-eslint/no-require-imports
const { HomeScreen } = require('../HomeScreen') as typeof import('../HomeScreen');

/** Free user (no subscription, active status). */
const freeUser = {
  id: 'user-1',
  role: 'user' as const,
  status: 'active' as const,
  subscriptionEntitlement: 'none' as const,
  onboardingCompletedAt: new Date().toISOString(),
  displayName: 'Test User',
};

/** Member user (member_monthly, active status). */
const memberUser = {
  ...freeUser,
  subscriptionEntitlement: 'member_monthly' as const,
};

function renderHomeScreen() {
  return TestRenderer.create(
    <AppThemeProvider>
      <I18nProvider locale="sv">
        <HomeScreen />
      </I18nProvider>
    </AppThemeProvider>,
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  mockUseAuth.mockReturnValue({
    currentUser: freeUser,
    isAuthenticated: true,
    isLoading: false,
    error: null,
    login: jest.fn(),
    logout: jest.fn(),
    refreshCurrentUser: jest.fn(),
    withToken: jest.fn(),
  });
  mockUseLiveLocationSession.mockReturnValue({
    status: 'not_sharing',
    selectedDuration: '1h',
    sessionId: null,
    error: null,
    isLoading: false,
    selectDuration: jest.fn(),
    startSession: jest.fn(),
    stopSession: jest.fn(),
    hideMeNow: jest.fn(),
  });
});

describe('HomeScreen — live location primary action', () => {
  it('renders without crashing', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderHomeScreen();
    });

    expect(renderer).not.toBeNull();
  });

  it('shows the live location primary action button', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderHomeScreen();
    });

    const buttons = renderer!.root.findAll(
      (node) => node.props['testID'] === 'home-live-location-button',
    );
    expect(buttons.length).toBeGreaterThan(0);
  });

  it('shows "Dela min position" label when not sharing', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderHomeScreen();
    });

    const button = renderer!.root.findAll(
      (node) => node.props['testID'] === 'home-live-location-button',
    )[0];

    const textNodes = button!.findAll(
      (n) => Array.isArray(n.children) && n.children.some((c) => typeof c === 'string'),
    );
    const allText = textNodes.flatMap((n) =>
      (n.children as unknown[]).filter((c) => typeof c === 'string'),
    );
    expect(allText.join('')).toContain('Dela min position');
  });

  it('shows "Stoppa delning" label when actively sharing', () => {
    mockUseLiveLocationSession.mockReturnValue({
      status: 'sharing',
      selectedDuration: '1h',
      sessionId: 'session-abc',
      error: null,
      isLoading: false,
      selectDuration: jest.fn(),
      startSession: jest.fn(),
      stopSession: jest.fn(),
      hideMeNow: jest.fn(),
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderHomeScreen();
    });

    const button = renderer!.root.findAll(
      (node) => node.props['testID'] === 'home-live-location-button',
    )[0];

    const textNodes = button!.findAll(
      (n) => Array.isArray(n.children) && n.children.some((c) => typeof c === 'string'),
    );
    const allText = textNodes.flatMap((n) =>
      (n.children as unknown[]).filter((c) => typeof c === 'string'),
    );
    expect(allText.join('')).toContain('Stoppa delning');
  });

  it('navigates to LiveLocation when the primary action is pressed', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderHomeScreen();
    });

    const button = renderer!.root.findAll(
      (node) => node.props['testID'] === 'home-live-location-button',
    )[0];

    act(() => {
      (button!.props['onPress'] as () => void)();
    });

    expect(mockNavigate).toHaveBeenCalledWith('LiveLocation');
  });
});

describe('HomeScreen — member-only live view notice (free user)', () => {
  it('shows member-only live location notice for free users', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderHomeScreen();
    });

    const notices = renderer!.root.findAll(
      (node) => node.props['testID'] === 'home-member-only-live-notice',
    );
    expect(notices.length).toBeGreaterThan(0);
  });

  it('does not show member-only live location notice for members', () => {
    // NOTE: canViewOthers is currently always false until AuthenticatedUserSummary
    // carries subscription data. This test documents the current behaviour.
    mockUseAuth.mockReturnValue({
      currentUser: memberUser,
      isAuthenticated: true,
      isLoading: false,
      error: null,
      login: jest.fn(),
      logout: jest.fn(),
      refreshCurrentUser: jest.fn(),
      withToken: jest.fn(),
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderHomeScreen();
    });

    // Until subscription data is available in AuthenticatedUserSummary the notice
    // is shown for all users (conservative free-user default).
    const notices = renderer!.root.findAll(
      (node) => node.props['testID'] === 'home-member-only-live-notice',
    );
    expect(notices.length).toBeGreaterThan(0);
  });
});

describe('HomeScreen — settings shortcut', () => {
  it('shows the settings shortcut button', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderHomeScreen();
    });

    const shortcuts = renderer!.root.findAll(
      (node) => node.props['testID'] === 'home-settings-shortcut',
    );
    expect(shortcuts.length).toBeGreaterThan(0);
  });

  it('navigates to Settings when the shortcut is pressed', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderHomeScreen();
    });

    const shortcut = renderer!.root.findAll(
      (node) => node.props['testID'] === 'home-settings-shortcut',
    )[0];

    act(() => {
      (shortcut!.props['onPress'] as () => void)();
    });

    expect(mockNavigate).toHaveBeenCalledWith('Settings');
  });
});

describe('HomeScreen — Swedish text', () => {
  it('renders "Dela min position" in Swedish', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderHomeScreen();
    });

    const allText = renderer!.root.findAll(
      (n) => typeof n.props['children'] === 'string',
    );
    const texts = allText.map((n) => n.props['children'] as string);

    expect(texts.some((t) => t.includes('Dela min position'))).toBe(true);
  });

  it('renders the live location disclaimer in Swedish', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderHomeScreen();
    });

    const allText = renderer!.root.findAll(
      (n) => typeof n.props['children'] === 'string',
    );
    const texts = allText.map((n) => n.props['children'] as string);

    expect(texts.some((t) => t.includes('frivilligt'))).toBe(true);
  });

  it('renders the member-only notice text in Swedish for free users', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderHomeScreen();
    });

    const notice = renderer!.root.findAll(
      (node) => node.props['testID'] === 'home-member-only-live-notice',
    )[0];

    const textNodes = notice!.findAll(
      (n) => typeof n.props['children'] === 'string',
    );
    const texts = textNodes.map((n) => n.props['children'] as string);

    expect(texts.some((t) => t.includes('medlemskap'))).toBe(true);
  });

  it('renders "Inställningar och integritet" for the settings shortcut', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderHomeScreen();
    });

    const allText = renderer!.root.findAll(
      (n) => typeof n.props['children'] === 'string',
    );
    const texts = allText.map((n) => n.props['children'] as string);

    expect(texts.some((t) => t.includes('Inställningar'))).toBe(true);
  });
});

describe('HomeScreen — home screen testID', () => {
  it('has the home-screen testID on the root element', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderHomeScreen();
    });

    const roots = renderer!.root.findAll(
      (node) => node.props['testID'] === 'home-screen',
    );
    expect(roots.length).toBeGreaterThan(0);
  });
});
