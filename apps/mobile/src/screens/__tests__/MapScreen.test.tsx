/**
 * Tests for MapScreen.
 *
 * @rnmapbox/maps is mocked via moduleNameMapper so native Mapbox modules
 * are replaced with lightweight View wrappers. This allows the screen to
 * render in Jest without a native build.
 *
 * LiveLocationContext is mocked so the screen can render without a real
 * location session.
 *
 * useLiveLocationMarkers is mocked so the screen can render without a real
 * backend connection, auth context, or AppState dependency.
 *
 * useAuth is mocked to control authentication state.
 */

import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';
import type { ReactTestRendererJSON } from 'react-test-renderer';

import { AppThemeProvider } from '../../hooks/useAppTheme';

/**
 * Count nodes with a given testID in the toJSON() tree.
 *
 * We use toJSON() (host-component tree) instead of `root.findAll` because
 * `findAll` traverses the React fiber tree which includes both the composite
 * component wrapper AND the host View for each mock component, doubling the
 * count. toJSON() returns only the rendered host-component tree.
 */
function countTestIds(node: ReactTestRendererJSON | ReactTestRendererJSON[] | null, id: string): number {
  if (!node) return 0;
  if (Array.isArray(node)) return node.reduce((sum, n) => sum + countTestIds(n, id), 0);
  let count = node.props['testID'] === id ? 1 : 0;
  for (const child of node.children ?? []) {
    if (typeof child === 'object') {
      count += countTestIds(child, id);
    }
  }
  return count;
}

// Prevent console.warn noise from missing Mapbox token during tests.
jest.spyOn(console, 'warn').mockImplementation(() => undefined);

// Mock LiveLocationContext so MapScreen does not require a real provider.
jest.mock('../../context/LiveLocationContext', () => ({
  useLiveLocation: jest.fn().mockReturnValue({
    status: 'not_sharing',
    currentPosition: null,
  }),
}));

// Mock useLiveLocationMarkers so MapScreen does not require a real backend
// connection, auth context, or AppState subscription.
jest.mock('../../hooks/useLiveLocationMarkers', () => ({
  useLiveLocationMarkers: jest.fn().mockReturnValue({
    markers: [],
    isLoading: false,
    isMemberEligible: false,
  }),
}));

// Mock useAuth so MapScreen does not require a real AuthProvider.
jest.mock('../../hooks/useAuth', () => ({
  useAuth: jest.fn().mockReturnValue({
    currentUser: null,
    isAuthenticated: false,
    isLoading: false,
    error: null,
    login: jest.fn(),
    logout: jest.fn(),
    refreshCurrentUser: jest.fn(),
    withToken: jest.fn(),
  }),
  getPlatformAuthProvider: jest.fn().mockReturnValue(null),
}));

// Mock useI18n so MapScreen does not require a real I18nProvider.
jest.mock('../../hooks/useI18n', () => ({
  useI18n: jest.fn().mockReturnValue({
    t: (key: string) => key,
  }),
}));

// eslint-disable-next-line @typescript-eslint/no-require-imports
const { MapScreen } = require('../MapScreen') as typeof import('../MapScreen');

afterAll(() => {
  jest.restoreAllMocks();
});

function renderMapScreen() {
  return TestRenderer.create(
    <AppThemeProvider>
      <MapScreen />
    </AppThemeProvider>,
  );
}

describe('MapScreen', () => {
  it('renders without crashing', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderMapScreen();
    });

    expect(renderer).not.toBeNull();
  });

  it('renders the Mapbox map view placeholder', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderMapScreen();
    });

    // The mock renders a View with testID 'mapbox-mapview'.
    const mapView = renderer!.root.findAll(
      (node) => node.props['testID'] === 'mapbox-mapview',
    );
    expect(mapView.length).toBeGreaterThan(0);
  });

  it('renders no member markers when the markers array is empty', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderMapScreen();
    });

    // No markers returned by hook → no member PointAnnotation elements.
    const count = countTestIds(renderer!.toJSON(), 'mapbox-point-annotation');
    expect(count).toBe(0);
  });

  it('does not render self marker when not sharing', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderMapScreen();
    });

    const count = countTestIds(renderer!.toJSON(), 'mapbox-point-annotation');
    expect(count).toBe(0);
  });

  it('renders self marker when sharing with a valid position', () => {
    const { useLiveLocation } = jest.requireMock('../../context/LiveLocationContext') as {
      useLiveLocation: jest.Mock;
    };
    useLiveLocation.mockReturnValue({
      status: 'sharing',
      currentPosition: { latitude: 57.5086, longitude: 12.0742 },
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;
    act(() => {
      renderer = renderMapScreen();
    });

    // Only the self marker — no member markers from the hook.
    const count = countTestIds(renderer!.toJSON(), 'mapbox-point-annotation');
    expect(count).toBe(1);

    // Restore defaults.
    useLiveLocation.mockReturnValue({ status: 'not_sharing', currentPosition: null });
  });

  it('renders member markers returned by useLiveLocationMarkers when eligible', () => {
    const { useLiveLocationMarkers } = jest.requireMock('../../hooks/useLiveLocationMarkers') as {
      useLiveLocationMarkers: jest.Mock;
    };
    useLiveLocationMarkers.mockReturnValue({
      markers: [
        { id: 'session-1', coordinate: { latitude: 57.51, longitude: 12.08 }, type: 'member' },
        { id: 'session-2', coordinate: { latitude: 57.52, longitude: 12.09 }, type: 'member' },
      ],
      isLoading: false,
      isMemberEligible: true,
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;
    act(() => {
      renderer = renderMapScreen();
    });

    const count = countTestIds(renderer!.toJSON(), 'mapbox-point-annotation');
    expect(count).toBe(2);

    // Restore defaults.
    useLiveLocationMarkers.mockReturnValue({ markers: [], isLoading: false, isMemberEligible: false });
  });

  it('renders both self and member markers simultaneously', () => {
    const { useLiveLocation } = jest.requireMock('../../context/LiveLocationContext') as {
      useLiveLocation: jest.Mock;
    };
    const { useLiveLocationMarkers } = jest.requireMock('../../hooks/useLiveLocationMarkers') as {
      useLiveLocationMarkers: jest.Mock;
    };

    useLiveLocation.mockReturnValue({
      status: 'sharing',
      currentPosition: { latitude: 57.5086, longitude: 12.0742 },
    });
    useLiveLocationMarkers.mockReturnValue({
      markers: [
        { id: 'session-1', coordinate: { latitude: 57.51, longitude: 12.08 }, type: 'member' },
      ],
      isLoading: false,
      isMemberEligible: true,
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;
    act(() => {
      renderer = renderMapScreen();
    });

    // Self + 1 member marker.
    const count = countTestIds(renderer!.toJSON(), 'mapbox-point-annotation');
    expect(count).toBe(2);

    useLiveLocation.mockReturnValue({ status: 'not_sharing', currentPosition: null });
    useLiveLocationMarkers.mockReturnValue({ markers: [], isLoading: false, isMemberEligible: false });
  });

  it('does not render eligible empty state while member markers are loading', () => {
    const { useLiveLocationMarkers } = jest.requireMock('../../hooks/useLiveLocationMarkers') as {
      useLiveLocationMarkers: jest.Mock;
    };
    useLiveLocationMarkers.mockReturnValue({
      markers: [],
      isLoading: true,
      isMemberEligible: true,
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;
    act(() => {
      renderer = renderMapScreen();
    });

    expect(JSON.stringify(renderer!.toJSON())).not.toContain('map.noOtherMarkersTitle');

    useLiveLocationMarkers.mockReturnValue({ markers: [], isLoading: false, isMemberEligible: false });
  });
});
