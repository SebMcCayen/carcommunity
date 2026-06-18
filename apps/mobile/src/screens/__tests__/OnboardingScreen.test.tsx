/**
 * Tests for OnboardingScreen.
 *
 * Covers:
 *   - Continue button is disabled until all three required confirmations are checked.
 *   - Continue button is enabled once all three are checked.
 *   - Partner statistics opt-in switch is unchecked by default.
 *   - Required Swedish copy is present.
 *   - Submitting calls patchUserProfile and refreshCurrentUser.
 */

import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';

import { AppThemeProvider } from '../../hooks/useAppTheme';
import { I18nProvider } from '../../hooks/useI18n';

// Mock publicEnv to prevent real API calls.
jest.mock('../../config/env', () => ({
  publicEnv: {
    authMode: 'dev',
    apiBaseUrl: 'http://localhost:4000',
    appEnv: 'test',
    googleIosClientId: '',
    googleAndroidClientId: '',
    googleWebClientId: '',
  },
}));

// Mock the profile API module.
const mockPatchUserProfile = jest.fn();
const mockPatchPrivacySettings = jest.fn();

jest.mock('../../api/profile', () => ({
  getUserProfile: jest.fn(),
  patchUserProfile: (...args: unknown[]) => mockPatchUserProfile(...args),
  getPrivacySettings: jest.fn(),
  patchPrivacySettings: (...args: unknown[]) => mockPatchPrivacySettings(...args),
  getAppSettingsLinks: jest.fn(),
}));

// Mock useAuth with controllable withToken and refreshCurrentUser.
const mockWithToken = jest.fn();
const mockRefreshCurrentUser = jest.fn();

jest.mock('../../hooks/useAuth', () => ({
  useAuth: jest.fn(() => ({
    currentUser: null,
    isAuthenticated: true,
    isLoading: false,
    error: null,
    login: jest.fn(),
    logout: jest.fn(),
    refreshCurrentUser: mockRefreshCurrentUser,
    withToken: mockWithToken,
  })),
}));

// eslint-disable-next-line @typescript-eslint/no-require-imports
const { OnboardingScreen } = require('../OnboardingScreen') as typeof import('../OnboardingScreen');

function renderScreen() {
  return TestRenderer.create(
    <AppThemeProvider>
      <I18nProvider locale="sv">
        <OnboardingScreen />
      </I18nProvider>
    </AppThemeProvider>,
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  // Default: withToken delegates to fn with a dummy token.
  mockWithToken.mockImplementation((fn: (token: string) => Promise<unknown>) => fn('test-token'));
  mockRefreshCurrentUser.mockResolvedValue(undefined);
  mockPatchUserProfile.mockResolvedValue({
    ok: true,
    data: {
      onboarding: {
        onboardingCompletedAt: new Date().toISOString(),
        ageConfirmedAt: new Date().toISOString(),
        termsAcceptedAt: new Date().toISOString(),
        privacyPolicyAcceptedAt: new Date().toISOString(),
        anonymousPartnerStatsOptIn: false,
      },
    },
  });
  mockPatchPrivacySettings.mockResolvedValue({
    ok: true,
    data: { anonymousPartnerStatsOptIn: true },
  });
});

describe('OnboardingScreen — continue button state', () => {
  it('continue button is disabled when no confirmations are checked', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderScreen();
    });

    const buttons = renderer!.root.findAll(
      (node) => node.props['testID'] === 'onboarding-continue-button',
    );
    expect(buttons.length).toBeGreaterThan(0);
    expect(buttons[0]!.props['disabled']).toBe(true);
  });

  it('continue button remains disabled when only age is confirmed', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderScreen();
    });

    const ageSwitch = renderer!.root.findAll(
      (node) => node.props['testID'] === 'age-switch',
    )[0];

    act(() => {
      (ageSwitch!.props['onValueChange'] as (v: boolean) => void)(true);
    });

    const button = renderer!.root.findAll(
      (node) => node.props['testID'] === 'onboarding-continue-button',
    )[0];
    expect(button!.props['disabled']).toBe(true);
  });

  it('continue button remains disabled when only age + terms are confirmed', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderScreen();
    });

    const ageSwitch = renderer!.root.findAll((n) => n.props['testID'] === 'age-switch')[0];
    const termsSwitch = renderer!.root.findAll((n) => n.props['testID'] === 'terms-switch')[0];

    act(() => {
      (ageSwitch!.props['onValueChange'] as (v: boolean) => void)(true);
      (termsSwitch!.props['onValueChange'] as (v: boolean) => void)(true);
    });

    const button = renderer!.root.findAll(
      (n) => n.props['testID'] === 'onboarding-continue-button',
    )[0];
    expect(button!.props['disabled']).toBe(true);
  });

  it('continue button is enabled when all three required confirmations are checked', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderScreen();
    });

    const ageSwitch = renderer!.root.findAll((n) => n.props['testID'] === 'age-switch')[0];
    const termsSwitch = renderer!.root.findAll((n) => n.props['testID'] === 'terms-switch')[0];
    const privacySwitch = renderer!.root.findAll((n) => n.props['testID'] === 'privacy-switch')[0];

    act(() => {
      (ageSwitch!.props['onValueChange'] as (v: boolean) => void)(true);
      (termsSwitch!.props['onValueChange'] as (v: boolean) => void)(true);
      (privacySwitch!.props['onValueChange'] as (v: boolean) => void)(true);
    });

    const button = renderer!.root.findAll(
      (n) => n.props['testID'] === 'onboarding-continue-button',
    )[0];
    expect(button!.props['disabled']).toBe(false);
  });
});

describe('OnboardingScreen — partner statistics opt-in default', () => {
  it('partner statistics switch is unchecked by default', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderScreen();
    });

    const partnerSwitch = renderer!.root.findAll(
      (n) => n.props['testID'] === 'partner-stats-switch',
    )[0];
    expect(partnerSwitch!.props['value']).toBe(false);
  });
});

describe('OnboardingScreen — Swedish copy', () => {
  it('renders age confirmation text in Swedish', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderScreen();
    });

    const allText = renderer!.root.findAll(
      (n) => typeof n.props['children'] === 'string',
    );
    const texts = allText.map((n) => n.props['children'] as string);

    expect(texts.some((t) => t.includes('18 år'))).toBe(true);
  });

  it('renders terms acceptance text in Swedish', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderScreen();
    });

    const allText = renderer!.root.findAll(
      (n) => typeof n.props['children'] === 'string',
    );
    const texts = allText.map((n) => n.props['children'] as string);

    expect(texts.some((t) => t.includes('villkoren'))).toBe(true);
  });

  it('renders privacy policy acceptance text in Swedish', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderScreen();
    });

    const allText = renderer!.root.findAll(
      (n) => typeof n.props['children'] === 'string',
    );
    const texts = allText.map((n) => n.props['children'] as string);

    expect(texts.some((t) => t.includes('integritetspolicyn'))).toBe(true);
  });

  it('renders partner statistics opt-in note about changing preference later', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderScreen();
    });

    const allText = renderer!.root.findAll(
      (n) => typeof n.props['children'] === 'string',
    );
    const texts = allText.map((n) => n.props['children'] as string);

    expect(texts.some((t) => t.includes('Integritet'))).toBe(true);
  });
});

describe('OnboardingScreen — submission', () => {
  it('calls patchUserProfile and refreshCurrentUser on successful submit', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderScreen();
    });

    const ageSwitch = renderer!.root.findAll((n) => n.props['testID'] === 'age-switch')[0];
    const termsSwitch = renderer!.root.findAll((n) => n.props['testID'] === 'terms-switch')[0];
    const privacySwitch = renderer!.root.findAll((n) => n.props['testID'] === 'privacy-switch')[0];

    act(() => {
      (ageSwitch!.props['onValueChange'] as (v: boolean) => void)(true);
      (termsSwitch!.props['onValueChange'] as (v: boolean) => void)(true);
      (privacySwitch!.props['onValueChange'] as (v: boolean) => void)(true);
    });

    const button = renderer!.root.findAll(
      (n) => n.props['testID'] === 'onboarding-continue-button',
    )[0];

    await act(async () => {
      (button!.props['onPress'] as () => void)();
    });

    expect(mockPatchUserProfile).toHaveBeenCalledTimes(1);
    expect(mockPatchUserProfile).toHaveBeenCalledWith(
      'test-token',
      expect.objectContaining({
        ageConfirmed: true,
        termsAccepted: true,
        privacyPolicyAccepted: true,
      }),
    );
    expect(mockRefreshCurrentUser).toHaveBeenCalledTimes(1);
  });

  it('does not call patchPrivacySettings when partner stats opt-in is unchecked', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderScreen();
    });

    const ageSwitch = renderer!.root.findAll((n) => n.props['testID'] === 'age-switch')[0];
    const termsSwitch = renderer!.root.findAll((n) => n.props['testID'] === 'terms-switch')[0];
    const privacySwitch = renderer!.root.findAll((n) => n.props['testID'] === 'privacy-switch')[0];

    act(() => {
      (ageSwitch!.props['onValueChange'] as (v: boolean) => void)(true);
      (termsSwitch!.props['onValueChange'] as (v: boolean) => void)(true);
      (privacySwitch!.props['onValueChange'] as (v: boolean) => void)(true);
    });

    const button = renderer!.root.findAll(
      (n) => n.props['testID'] === 'onboarding-continue-button',
    )[0];

    await act(async () => {
      (button!.props['onPress'] as () => void)();
    });

    // Partner stats opt-in was not toggled, so patchPrivacySettings should not be called.
    expect(mockPatchPrivacySettings).not.toHaveBeenCalled();
  });

  it('calls patchPrivacySettings when partner stats opt-in is explicitly checked', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderScreen();
    });

    const ageSwitch = renderer!.root.findAll((n) => n.props['testID'] === 'age-switch')[0];
    const termsSwitch = renderer!.root.findAll((n) => n.props['testID'] === 'terms-switch')[0];
    const privacySwitch = renderer!.root.findAll((n) => n.props['testID'] === 'privacy-switch')[0];
    const partnerSwitch = renderer!.root.findAll((n) => n.props['testID'] === 'partner-stats-switch')[0];

    act(() => {
      (ageSwitch!.props['onValueChange'] as (v: boolean) => void)(true);
      (termsSwitch!.props['onValueChange'] as (v: boolean) => void)(true);
      (privacySwitch!.props['onValueChange'] as (v: boolean) => void)(true);
      (partnerSwitch!.props['onValueChange'] as (v: boolean) => void)(true);
    });

    const button = renderer!.root.findAll(
      (n) => n.props['testID'] === 'onboarding-continue-button',
    )[0];

    await act(async () => {
      (button!.props['onPress'] as () => void)();
    });

    expect(mockPatchPrivacySettings).toHaveBeenCalledTimes(1);
    expect(mockPatchPrivacySettings).toHaveBeenCalledWith('test-token', {
      anonymousPartnerStatsOptIn: true,
    });
  });
});
