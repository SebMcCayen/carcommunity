/**
 * Tests for MapScreen.
 *
 * @rnmapbox/maps is mocked via moduleNameMapper so native Mapbox modules
 * are replaced with lightweight View wrappers. This allows the screen to
 * render in Jest without a native build.
 *
 * LiveLocationContext is mocked so the screen can render without a real
 * location session.
 */

import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';

import { AppThemeProvider } from '../../hooks/useAppTheme';

type JSONNode = { type: string; props: Record<string, unknown>; children: Array<JSONNode | string> | null };

/**
 * Count nodes with a given testID in the toJSON() tree.
 *
 * We use toJSON() (host-component tree) instead of `root.findAll` because
 * `findAll` traverses the React fiber tree which includes both the composite
 * component wrapper AND the host View for each mock component, doubling the
 * count. toJSON() returns only the rendered host-component tree.
 */
function countTestIds(node: JSONNode | JSONNode[] | null, id: string): number {
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

  it('renders placeholder marker annotations', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderMapScreen();
    });

    // The mock renders PointAnnotation as a View with testID 'mapbox-point-annotation'.
    const annotations = renderer!.root.findAll(
      (node) => node.props['testID'] === 'mapbox-point-annotation',
    );
    // The @fake member marker should always render.
    expect(annotations.length).toBeGreaterThan(0);
  });

  it('does not render self marker when not sharing', () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;

    act(() => {
      renderer = renderMapScreen();
    });

    // Only the fake member marker should be present — no self annotation when not sharing.
    // Use toJSON() (host-component tree) to get an accurate count; root.findAll traverses
    // the full fiber tree and returns duplicates for composite+host pairs in mock components.
    const count = countTestIds(renderer!.toJSON(), 'mapbox-point-annotation');
    expect(count).toBe(1);
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

    // Both the self marker and the fake member marker should render.
    const count = countTestIds(renderer!.toJSON(), 'mapbox-point-annotation');
    expect(count).toBe(2);

    // Restore default mock for subsequent tests.
    useLiveLocation.mockReturnValue({ status: 'not_sharing', currentPosition: null });
  });
});
