/**
 * Tests for EventsScreen.
 */

import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';

import { AppThemeProvider } from '../../hooks/useAppTheme';
import { I18nProvider } from '../../hooks/useI18n';

// eslint-disable-next-line @typescript-eslint/no-require-imports
const { EventsScreen } = require('../EventsScreen') as typeof import('../EventsScreen');

function renderEventsScreen() {
  return TestRenderer.create(
    <AppThemeProvider>
      <I18nProvider locale="sv">
        <EventsScreen />
      </I18nProvider>
    </AppThemeProvider>,
  );
}

describe('EventsScreen', () => {
  it('renders without crashing', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderEventsScreen();
    });

    expect(renderer).not.toBeNull();
  });

  it('shows the member required banner for free users', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderEventsScreen();
    });

    const banners = renderer!.root.findAll(
      (node) => node.props['testID'] === 'events-member-upgrade-banner',
    );
    expect(banners.length).toBeGreaterThan(0);
  });

  it('shows the no upcoming events placeholder', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderEventsScreen();
    });

    const texts = renderer!.root.findAll(
      (node) => typeof node.props['children'] === 'string',
    );

    const noUpcomingText = texts.some((node) =>
      String(node.props['children']).includes('Inga kommande träffar'),
    );
    expect(noUpcomingText).toBe(true);
  });
});
