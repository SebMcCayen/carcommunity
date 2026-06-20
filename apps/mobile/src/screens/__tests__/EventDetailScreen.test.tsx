/**
 * Tests for EventDetailScreen.
 *
 * Covers:
 *   - Member sees full event details after load
 *   - Free user sees only teaser + membership gate (no API call for protected data)
 *   - RSVP controls are rendered for members
 *   - RSVP button fires the update and refetches details
 *   - Entitlement loss while detail is shown clears protected data
 *   - Cancelled event shows cancelled notice
 *   - Navigate button is present when location data exists
 */

import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';

import { AppThemeProvider } from '../../hooks/useAppTheme';
import { I18nProvider } from '../../hooks/useI18n';
import type { EventTeaser, EventDetailResponse } from '@carcommunity/shared/events';

// ---------------------------------------------------------------------------
// Test fixtures (declared early so mock factories can reference them)
// ---------------------------------------------------------------------------

const mockTeaser: EventTeaser = {
  id: 'event-1',
  title: 'Träff i Kungsbacka',
  startsAt: '2027-07-01T16:00:00.000Z',
  endsAt: '2027-07-01T20:00:00.000Z',
  approximateArea: 'Kungsbacka centrum',
  isOfficial: true,
  status: 'published',
};

// Mutable route params — allows individual tests to override teaser (e.g. cancelled)
// eslint-disable-next-line prefer-const
let mockRouteParams: { eventId: string; teaser: EventTeaser } = {
  eventId: 'event-1',
  teaser: mockTeaser,
};

// Mock navigation — uses mutable mockRouteParams so tests can override teaser
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: jest.fn(), goBack: jest.fn() }),
  useRoute: () => ({ params: mockRouteParams }),
}));

// Mock Alert
jest.mock('react-native/Libraries/Alert/Alert', () => ({
  alert: jest.fn(),
}));

// Mock useAuth
const mockWithToken = jest.fn();
const mockUseAuth = jest.fn();
jest.mock('../../hooks/useAuth', () => ({
  useAuth: (...args: unknown[]) => mockUseAuth(...args),
}));

// Mock events API
const mockLoadEventDetails = jest.fn();
const mockUpdateEventRsvp = jest.fn();
jest.mock('../../api/events', () => ({
  loadEventDetails: (...args: unknown[]) => mockLoadEventDetails(...args),
  updateEventRsvp: (...args: unknown[]) => mockUpdateEventRsvp(...args),
}));

// Mock external navigation utility
const mockOpenExternalNavigation = jest.fn();
jest.mock('../../utils/eventNavigation', () => ({
  openExternalNavigation: (...args: unknown[]) => mockOpenExternalNavigation(...args),
}));

// eslint-disable-next-line @typescript-eslint/no-require-imports
const { EventDetailScreen } = require('../EventDetailScreen') as typeof import('../EventDetailScreen');

const mockDetailResponse: EventDetailResponse = {
  ok: true,
  data: {
    event: {
      id: 'event-1',
      title: 'Träff i Kungsbacka',
      summary: 'En härlig träff',
      description: 'Kom och häng med oss vid torget.',
      startsAt: '2027-07-01T16:00:00.000Z',
      endsAt: '2027-07-01T20:00:00.000Z',
      locationName: 'Kungsbacka Torg',
      address: 'Stortorget 1, Kungsbacka',
      latitude: 57.4875,
      longitude: 12.0762,
      isOfficial: true,
      status: 'published',
      rsvpSummary: { going: 5, maybe: 3, not_going: 1 },
      currentUserRsvp: 'going',
    },
  },
};

const freeUser = {
  userId: 'user-1',
  roles: ['user' as const],
  status: 'active' as const,
  subscriptionEntitlement: 'none' as const,
  onboardingCompletedAt: new Date().toISOString(),
};

const memberUser = {
  ...freeUser,
  subscriptionEntitlement: 'member_monthly' as const,
};

function renderEventDetailScreen() {
  return TestRenderer.create(
    <AppThemeProvider>
      <I18nProvider locale="sv">
        <EventDetailScreen />
      </I18nProvider>
    </AppThemeProvider>,
  );
}

// ---------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------

beforeEach(() => {
  jest.clearAllMocks();
  // Reset route params to the standard published teaser
  mockRouteParams = { eventId: 'event-1', teaser: mockTeaser };
  mockWithToken.mockImplementation((fn: (token: string) => unknown) => fn('test-token'));
  mockLoadEventDetails.mockResolvedValue(mockDetailResponse);
  mockUpdateEventRsvp.mockResolvedValue({
    ok: true,
    data: { rsvp: { eventId: 'event-1', userId: 'user-1', status: 'going', updatedAt: '2027-06-01T10:00:00.000Z' } },
  });
  mockOpenExternalNavigation.mockResolvedValue(undefined);
});

// ---------------------------------------------------------------------------
// Free user
// ---------------------------------------------------------------------------

describe('EventDetailScreen — free user', () => {
  beforeEach(() => {
    mockUseAuth.mockReturnValue({
      currentUser: freeUser,
      isAuthenticated: true,
      isLoading: false,
      error: null,
      withToken: mockWithToken,
      login: jest.fn(),
      logout: jest.fn(),
      refreshCurrentUser: jest.fn(),
    });
  });

  it('shows the membership gate notice for free users', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventDetailScreen();
    });

    const gates = renderer!.root.findAll(
      (node) => node.props['testID'] === 'event-detail-member-gate',
    );
    expect(gates.length).toBeGreaterThan(0);
  });

  it('does not call loadEventDetails for free users', async () => {
    await act(async () => {
      renderEventDetailScreen();
    });

    // Free users must never trigger a protected detail API call
    expect(mockLoadEventDetails).not.toHaveBeenCalled();
  });

  it('does not show RSVP controls for free users', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventDetailScreen();
    });

    const rsvpButtons = renderer!.root.findAll(
      (node) => node.props['testID'] === 'rsvp-button-going',
    );
    expect(rsvpButtons.length).toBe(0);
  });

  it('does not show the navigate button for free users', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventDetailScreen();
    });

    const navButtons = renderer!.root.findAll(
      (node) => node.props['testID'] === 'event-navigate-button',
    );
    expect(navButtons.length).toBe(0);
  });

  it('still shows the teaser header (title, date, area)', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventDetailScreen();
    });

    const allText = renderer!.root.findAll(
      (n) => typeof n.props['children'] === 'string',
    );
    const texts = allText.map((n) => n.props['children'] as string);
    expect(texts.some((t) => t.includes('Träff i Kungsbacka'))).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// Member user
// ---------------------------------------------------------------------------

describe('EventDetailScreen — member user', () => {
  beforeEach(() => {
    mockUseAuth.mockReturnValue({
      currentUser: memberUser,
      isAuthenticated: true,
      isLoading: false,
      error: null,
      withToken: mockWithToken,
      login: jest.fn(),
      logout: jest.fn(),
      refreshCurrentUser: jest.fn(),
    });
  });

  it('calls loadEventDetails for members', async () => {
    await act(async () => {
      renderEventDetailScreen();
    });

    expect(mockLoadEventDetails).toHaveBeenCalledWith('event-1', 'test-token');
  });

  it('does not show the membership gate for members', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventDetailScreen();
    });

    const gates = renderer!.root.findAll(
      (node) => node.props['testID'] === 'event-detail-member-gate',
    );
    expect(gates.length).toBe(0);
  });

  it('shows full location details for members', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventDetailScreen();
    });

    const location = renderer!.root.findAll(
      (node) => node.props['testID'] === 'event-location-detail',
    );
    expect(location.length).toBeGreaterThan(0);
  });

  it('shows RSVP controls for members', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventDetailScreen();
    });

    const rsvpButtons = renderer!.root.findAll(
      (node) => node.props['testID'] === 'rsvp-button-going',
    );
    expect(rsvpButtons.length).toBeGreaterThan(0);
  });

  it('shows the navigate button when location data exists', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventDetailScreen();
    });

    const navButtons = renderer!.root.findAll(
      (node) => node.props['testID'] === 'event-navigate-button',
    );
    expect(navButtons.length).toBeGreaterThan(0);
  });

  it('shows the RSVP summary', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventDetailScreen();
    });

    const summary = renderer!.root.findAll(
      (node) => node.props['testID'] === 'event-rsvp-summary',
    );
    expect(summary.length).toBeGreaterThan(0);
  });
});

// ---------------------------------------------------------------------------
// RSVP
// ---------------------------------------------------------------------------

describe('EventDetailScreen — RSVP', () => {
  beforeEach(() => {
    mockUseAuth.mockReturnValue({
      currentUser: memberUser,
      isAuthenticated: true,
      isLoading: false,
      error: null,
      withToken: mockWithToken,
      login: jest.fn(),
      logout: jest.fn(),
      refreshCurrentUser: jest.fn(),
    });
  });

  it('calls updateEventRsvp when member presses an RSVP button', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventDetailScreen();
    });

    const maybeButton = renderer!.root.findAll(
      (node) => node.props['testID'] === 'rsvp-button-maybe',
    )[0];

    await act(async () => {
      (maybeButton!.props['onPress'] as () => void)();
    });

    expect(mockUpdateEventRsvp).toHaveBeenCalledWith(
      'event-1',
      { status: 'maybe' },
      'test-token',
    );
  });

  it('shows an RSVP error when the backend rejects', async () => {
    mockUpdateEventRsvp.mockRejectedValue(new Error('Forbidden'));

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventDetailScreen();
    });

    const maybeButton = renderer!.root.findAll(
      (node) => node.props['testID'] === 'rsvp-button-maybe',
    )[0];

    await act(async () => {
      (maybeButton!.props['onPress'] as () => void)();
    });

    const errors = renderer!.root.findAll(
      (node) => node.props['testID'] === 'rsvp-error',
    );
    expect(errors.length).toBeGreaterThan(0);
  });
});

// ---------------------------------------------------------------------------
// Entitlement loss
// ---------------------------------------------------------------------------

describe('EventDetailScreen — entitlement loss', () => {
  it('clears protected detail data when membership is lost', async () => {
    // Start as member
    mockUseAuth.mockReturnValue({
      currentUser: memberUser,
      isAuthenticated: true,
      isLoading: false,
      error: null,
      withToken: mockWithToken,
      login: jest.fn(),
      logout: jest.fn(),
      refreshCurrentUser: jest.fn(),
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventDetailScreen();
    });

    // Verify location detail is shown
    const locationBeforeLoss = renderer!.root.findAll(
      (node) => node.props['testID'] === 'event-location-detail',
    );
    expect(locationBeforeLoss.length).toBeGreaterThan(0);

    // Lose membership
    mockUseAuth.mockReturnValue({
      currentUser: freeUser,
      isAuthenticated: true,
      isLoading: false,
      error: null,
      withToken: mockWithToken,
      login: jest.fn(),
      logout: jest.fn(),
      refreshCurrentUser: jest.fn(),
    });

    await act(async () => {
      renderer!.update(
        <AppThemeProvider>
          <I18nProvider locale="sv">
            <EventDetailScreen />
          </I18nProvider>
        </AppThemeProvider>,
      );
    });

    // Protected data should be cleared — gate notice should now be visible
    const gateAfterLoss = renderer!.root.findAll(
      (node) => node.props['testID'] === 'event-detail-member-gate',
    );
    expect(gateAfterLoss.length).toBeGreaterThan(0);
  });
});

// ---------------------------------------------------------------------------
// Cancelled event
// ---------------------------------------------------------------------------

describe('EventDetailScreen — cancelled event', () => {
  beforeEach(() => {
    // Override route params to use a cancelled teaser
    mockRouteParams = { eventId: 'event-1', teaser: { ...mockTeaser, status: 'cancelled' } };
    mockUseAuth.mockReturnValue({
      currentUser: freeUser,
      isAuthenticated: true,
      isLoading: false,
      error: null,
      withToken: mockWithToken,
      login: jest.fn(),
      logout: jest.fn(),
      refreshCurrentUser: jest.fn(),
    });
  });

  it('shows the cancelled notice for cancelled events', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventDetailScreen();
    });

    const cancelled = renderer!.root.findAll(
      (node) => node.props['testID'] === 'event-cancelled-notice',
    );
    expect(cancelled.length).toBeGreaterThan(0);
  });
});
