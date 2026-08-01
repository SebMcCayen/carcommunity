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

import { MapLocationPicker } from '@/components/map/MapLocationPicker';

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
  class FakeMap {
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
    remove() {}
    resize() {}
  }
  return {
    default: { accessToken: '', Map: FakeMap, Marker: FakeMarker },
    accessToken: '',
    Map: FakeMap,
    Marker: FakeMarker,
  };
});
vi.mock('mapbox-gl/dist/mapbox-gl.css', () => ({}));

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
  // Ensure the no-map fallback path (no Mapbox GL import under jsdom).
  vi.stubEnv('VITE_MAPBOX_TOKEN', '');
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
