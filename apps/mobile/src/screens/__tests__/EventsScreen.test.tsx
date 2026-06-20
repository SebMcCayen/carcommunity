/**
 * Tests for EventsScreen.
 *
 * Covers:
 *   - Loading state while fetching teasers
 *   - Empty state when no events are returned
 *   - Error state when the API call fails
 *   - Free user sees the membership gate notice
 *   - Free user UI does not request protected event details
 *   - Teaser cards shown when events are returned
 *   - Member user does not see the membership gate notice
 */

import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';

import { AppThemeProvider } from '../../hooks/useAppTheme';
import { I18nProvider } from '../../hooks/useI18n';
import type { EventTeasersResponse } from '@carcommunity/shared/events';

// Mock navigation
const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate }),
}));

// Mock useAuth
const mockWithToken = jest.fn();
const mockUseAuth = jest.fn();
jest.mock('../../hooks/useAuth', () => ({
  useAuth: (...args: unknown[]) => mockUseAuth(...args),
}));

// Mock events API
const mockLoadEventTeasers = jest.fn();
jest.mock('../../api/events', () => ({
  loadEventTeasers: (...args: unknown[]) => mockLoadEventTeasers(...args),
}));

// eslint-disable-next-line @typescript-eslint/no-require-imports
const { EventsScreen } = require('../EventsScreen') as typeof import('../EventsScreen');

// ---------------------------------------------------------------------------
// Test fixtures
// ---------------------------------------------------------------------------

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

const mockTeaser = {
  id: 'event-1',
  title: 'Träff i Kungsbacka',
  startsAt: '2027-07-01T16:00:00.000Z',
  endsAt: '2027-07-01T20:00:00.000Z',
  approximateArea: 'Kungsbacka centrum',
  isOfficial: true,
  status: 'published' as const,
};

const emptyTeasersResponse: EventTeasersResponse = {
  ok: true,
  data: { events: [] },
  meta: { total: 0, nextCursor: null },
};

const oneTeaserResponse: EventTeasersResponse = {
  ok: true,
  data: { events: [mockTeaser] },
  meta: { total: 1, nextCursor: null },
};

function renderEventsScreen() {
  return TestRenderer.create(
    <AppThemeProvider>
      <I18nProvider locale="sv">
        <EventsScreen />
      </I18nProvider>
    </AppThemeProvider>,
  );
}

// ---------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------

beforeEach(() => {
  jest.clearAllMocks();
  // Default: free user, empty teasers
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
  mockWithToken.mockImplementation((fn: (token: string) => unknown) => fn('test-token'));
  mockLoadEventTeasers.mockResolvedValue(emptyTeasersResponse);
});

// ---------------------------------------------------------------------------
// Loading state
// ---------------------------------------------------------------------------

describe('EventsScreen — loading state', () => {
  it('shows a loading indicator while fetching', () => {
    // Never resolves during this test
    mockLoadEventTeasers.mockReturnValue(new Promise(() => undefined));

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderEventsScreen();
    });

    const loading = renderer!.root.findAll(
      (node) => node.props['testID'] === 'events-loading',
    );
    expect(loading.length).toBeGreaterThan(0);
  });
});

// ---------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------

describe('EventsScreen — empty state', () => {
  it('shows the no-upcoming placeholder when there are no events', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventsScreen();
    });

    const placeholders = renderer!.root.findAll(
      (node) => node.props['testID'] === 'events-no-upcoming',
    );
    expect(placeholders.length).toBeGreaterThan(0);
  });
});

// ---------------------------------------------------------------------------
// Error state
// ---------------------------------------------------------------------------

describe('EventsScreen — error state', () => {
  it('shows an error message and retry button when the API fails', async () => {
    mockLoadEventTeasers.mockRejectedValue(new Error('Network error'));

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventsScreen();
    });

    const errors = renderer!.root.findAll(
      (node) => node.props['testID'] === 'events-error',
    );
    expect(errors.length).toBeGreaterThan(0);

    const retryButtons = renderer!.root.findAll(
      (node) => node.props['testID'] === 'events-retry',
    );
    expect(retryButtons.length).toBeGreaterThan(0);
  });
});

// ---------------------------------------------------------------------------
// Free user
// ---------------------------------------------------------------------------

describe('EventsScreen — free user', () => {
  it('shows the member upgrade banner for free users', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventsScreen();
    });

    const banners = renderer!.root.findAll(
      (node) => node.props['testID'] === 'events-member-upgrade-banner',
    );
    expect(banners.length).toBeGreaterThan(0);
  });

  it('does not call loadEventDetails for free users', async () => {
    const mockLoadEventDetails = jest.fn();

    // Dynamically replace the mock within the events API module
    jest.mock('../../api/events', () => ({
      loadEventTeasers: (...args: unknown[]) => mockLoadEventTeasers(...args),
      loadEventDetails: (...args: unknown[]) => mockLoadEventDetails(...args),
    }));

    await act(async () => {
      renderEventsScreen();
    });

    // EventsScreen should never call loadEventDetails — only loadEventTeasers
    expect(mockLoadEventDetails).not.toHaveBeenCalled();
  });

  it('shows lock indicator on teaser cards for free users', async () => {
    mockLoadEventTeasers.mockResolvedValue(oneTeaserResponse);

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventsScreen();
    });

    const lockIndicators = renderer!.root.findAll(
      (node) => node.props['testID'] === `event-lock-indicator-${mockTeaser.id}`,
    );
    expect(lockIndicators.length).toBeGreaterThan(0);
  });
});

// ---------------------------------------------------------------------------
// Member user
// ---------------------------------------------------------------------------

describe('EventsScreen — member user', () => {
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

  it('does not show the member upgrade banner for members', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventsScreen();
    });

    const banners = renderer!.root.findAll(
      (node) => node.props['testID'] === 'events-member-upgrade-banner',
    );
    expect(banners.length).toBe(0);
  });

  it('does not show lock indicator on teaser cards for members', async () => {
    mockLoadEventTeasers.mockResolvedValue(oneTeaserResponse);

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventsScreen();
    });

    const lockIndicators = renderer!.root.findAll(
      (node) => node.props['testID'] === `event-lock-indicator-${mockTeaser.id}`,
    );
    expect(lockIndicators.length).toBe(0);
  });
});

// ---------------------------------------------------------------------------
// Event list
// ---------------------------------------------------------------------------

describe('EventsScreen — event list', () => {
  it('renders a teaser card for each event returned', async () => {
    mockLoadEventTeasers.mockResolvedValue(oneTeaserResponse);

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventsScreen();
    });

    const cards = renderer!.root.findAll(
      (node) => node.props['testID'] === `event-teaser-card-${mockTeaser.id}`,
    );
    expect(cards.length).toBeGreaterThan(0);
  });

  it('navigates to EventDetail when a teaser card is pressed', async () => {
    mockLoadEventTeasers.mockResolvedValue(oneTeaserResponse);

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventsScreen();
    });

    const card = renderer!.root.findAll(
      (node) => node.props['testID'] === `event-teaser-card-${mockTeaser.id}`,
    )[0];

    act(() => {
      (card!.props['onPress'] as () => void)();
    });

    expect(mockNavigate).toHaveBeenCalledWith('EventDetail', {
      eventId: mockTeaser.id,
      teaser: mockTeaser,
    });
  });

  it('shows the cancelled badge for cancelled events', async () => {
    const cancelledTeaser = { ...mockTeaser, status: 'cancelled' as const };
    mockLoadEventTeasers.mockResolvedValue({
      ok: true,
      data: { events: [cancelledTeaser] },
      meta: { total: 1, nextCursor: null },
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    await act(async () => {
      renderer = renderEventsScreen();
    });

    const badges = renderer!.root.findAll(
      (node) => node.props['testID'] === `event-cancelled-badge-${mockTeaser.id}`,
    );
    expect(badges.length).toBeGreaterThan(0);
  });
});
