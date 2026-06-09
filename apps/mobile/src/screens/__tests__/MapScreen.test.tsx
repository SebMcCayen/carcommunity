/**
 * Tests for MapScreen.
 *
 * @rnmapbox/maps is mocked via moduleNameMapper so native Mapbox modules
 * are replaced with lightweight View wrappers. This allows the screen to
 * render in Jest without a native build.
 */

import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';

import { AppThemeProvider } from '../../hooks/useAppTheme';

// Prevent console.warn noise from missing Mapbox token during tests.
jest.spyOn(console, 'warn').mockImplementation(() => undefined);

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
    // There should be at least one placeholder marker rendered.
    expect(annotations.length).toBeGreaterThan(0);
  });
});
