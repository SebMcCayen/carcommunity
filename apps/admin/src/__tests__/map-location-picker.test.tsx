/**
 * Component tests for the shared MapLocationPicker.
 *
 * Mapbox GL JS cannot render under jsdom (no WebGL), so these tests exercise
 * the always-present, testable surface: the graceful no-map fallback (manual
 * latitude/longitude inputs + an "unavailable" notice) and that editing the
 * inputs surfaces the chosen coordinate through onChange. The GL map render,
 * marker drag, and geofence circle can only be verified in a real browser.
 *
 * `mapbox-gl` is mocked so an accidental map-path import never tries to touch
 * WebGL under jsdom; with an empty token the picker takes the fallback path and
 * never imports it, but the mock keeps the suite hermetic regardless.
 *
 * Rendered with react-dom/client + React.act (no testing-library dependency,
 * matching the app's zero-extra-deps test setup).
 */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { DEFAULT_CENTER, DEFAULT_ZOOM } from '@/components/map/coordinates';
import { MapLocationPicker } from '@/components/map/MapLocationPicker';

// Shared capture buffers for the Mapbox GL mock, so the map-path tests can
// assert what camera/controls the component asked GL for (the real GL render
// needs WebGL and cannot run under jsdom). `vi.hoisted` runs before the hoisted
// `vi.mock` factory below, so the factory can close over these.
const gl = vi.hoisted(() => ({
  mapOpts: [] as Array<{ center: [number, number]; zoom: number }>,
  addedControls: [] as string[],
  navControlCount: 0,
}));

// Hermetic stub for the proprietary Mapbox GL JS runtime (no WebGL in jsdom).
vi.mock('mapbox-gl', () => {
  class FakeMarker {
    setLngLat() {
      return this;
    }
    addTo() {
      return this;
    }
    getLngLat() {
      return { lng: 0, lat: 0 };
    }
    on() {}
    remove() {}
  }
  class FakeNavigationControl {
    constructor() {
      gl.navControlCount += 1;
    }
  }
  class FakeMap {
    constructor(opts: { center: [number, number]; zoom: number }) {
      gl.mapOpts.push({ center: opts.center, zoom: opts.zoom });
    }
    on() {}
    addSource() {}
    getSource() {}
    removeSource() {}
    addLayer() {}
    getLayer() {}
    removeLayer() {}
    isStyleLoaded() {
      return true;
    }
    setConfigProperty() {}
    easeTo() {}
    addControl(_control: unknown, position?: string) {
      gl.addedControls.push(position ?? '');
    }
    remove() {}
    resize() {}
  }
  const api = {
    accessToken: '',
    Map: FakeMap,
    Marker: FakeMarker,
    NavigationControl: FakeNavigationControl,
  };
  return { default: api, ...api };
});
vi.mock('mapbox-gl/dist/mapbox-gl.css', () => ({}));

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
  // Ensure the no-map fallback path (no Mapbox GL import under jsdom).
  vi.stubEnv('VITE_MAPBOX_TOKEN', '');
  gl.mapOpts.length = 0;
  gl.addedControls.length = 0;
  gl.navControlCount = 0;
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

function render(node: React.ReactElement) {
  act(() => root.render(node));
}

const latInput = () =>
  container.querySelector<HTMLInputElement>('input[min="-90"]')!;
const lngInput = () =>
  container.querySelector<HTMLInputElement>('input[min="-180"]')!;

describe('MapLocationPicker (no map / fallback)', () => {
  it('renders the manual inputs and an unavailable notice, not a map canvas', () => {
    render(
      <MapLocationPicker
        latitude=""
        longitude=""
        onChange={() => {}}
        labelLat="Latitude"
        labelLng="Longitude"
        unavailableText="Map unavailable"
      />,
    );

    expect(latInput()).toBeTruthy();
    expect(lngInput()).toBeTruthy();
    expect(container.querySelector('[data-testid="map-canvas"]')).toBeNull();
    expect(container.textContent).toContain('Map unavailable');
  });

  it('reflects the current value in the inputs', () => {
    render(
      <MapLocationPicker
        latitude="57.4874"
        longitude="12.0761"
        onChange={() => {}}
        labelLat="Latitude"
        labelLng="Longitude"
      />,
    );

    expect(latInput().value).toBe('57.4874');
    expect(lngInput().value).toBe('12.0761');
  });

  it('surfaces an edited latitude through onChange (longitude preserved)', () => {
    const onChange = vi.fn();
    render(
      <MapLocationPicker
        latitude=""
        longitude="12.0761"
        onChange={onChange}
        labelLat="Latitude"
        labelLng="Longitude"
      />,
    );

    const input = latInput();
    act(() => {
      const setter = Object.getOwnPropertyDescriptor(
        window.HTMLInputElement.prototype,
        'value',
      )!.set!;
      setter.call(input, '57.4874');
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });

    expect(onChange).toHaveBeenCalledWith('57.4874', '12.0761');
  });

  it('marks inputs required when required is set', () => {
    render(
      <MapLocationPicker
        latitude=""
        longitude=""
        onChange={() => {}}
        labelLat="Latitude"
        labelLng="Longitude"
        required
      />,
    );
    expect(latInput().required).toBe(true);
    expect(lngInput().required).toBe(true);
  });

  it('renders a validation error when provided', () => {
    render(
      <MapLocationPicker
        latitude=""
        longitude=""
        onChange={() => {}}
        labelLat="Latitude"
        labelLng="Longitude"
        error="Both coordinates or none"
      />,
    );
    expect(container.textContent).toContain('Both coordinates or none');
  });
});

describe('MapLocationPicker (map path — camera + zoom controls)', () => {
  // The GL effect dynamically imports the (mocked) mapbox-gl and builds the map
  // asynchronously, so each test renders with a token set and then flushes the
  // async effect before inspecting what the component asked GL for.
  async function renderWithMap(node: React.ReactElement) {
    vi.stubEnv('VITE_MAPBOX_TOKEN', 'pk.test.token');
    render(node);
    // Let the dynamic import()s + async IIFE inside the effect settle.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
  }

  it('centres a NEW/empty picker on Kungsbacka at the town-level default zoom', async () => {
    await renderWithMap(
      <MapLocationPicker
        latitude=""
        longitude=""
        onChange={() => {}}
        labelLat="Latitude"
        labelLng="Longitude"
      />,
    );

    expect(gl.mapOpts).toHaveLength(1);
    // center is passed to Mapbox as [lng, lat].
    expect(gl.mapOpts[0]!.center).toEqual([DEFAULT_CENTER.lng, DEFAULT_CENTER.lat]);
    expect(gl.mapOpts[0]!.zoom).toBe(DEFAULT_ZOOM);
  });

  it('centres on an EXISTING coordinate (does not override it with Kungsbacka)', async () => {
    await renderWithMap(
      <MapLocationPicker
        latitude="59.3293"
        longitude="18.0686"
        onChange={() => {}}
        labelLat="Latitude"
        labelLng="Longitude"
      />,
    );

    expect(gl.mapOpts).toHaveLength(1);
    // Stockholm, not Kungsbacka — the saved value wins.
    expect(gl.mapOpts[0]!.center).toEqual([18.0686, 59.3293]);
    expect(gl.mapOpts[0]!.center).not.toEqual([DEFAULT_CENTER.lng, DEFAULT_CENTER.lat]);
  });

  it('adds a NavigationControl (the on-screen zoom +/- buttons) top-right', async () => {
    await renderWithMap(
      <MapLocationPicker
        latitude=""
        longitude=""
        onChange={() => {}}
        labelLat="Latitude"
        labelLng="Longitude"
      />,
    );

    expect(gl.navControlCount).toBe(1);
    expect(gl.addedControls).toContain('top-right');
  });
});
