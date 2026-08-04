/**
 * Shared, reusable admin location picker.
 *
 * One component used by every admin surface where an operator chooses a
 * position (Billboards, Crown Hunt / Kronjakt points, Partners, Events). It
 * renders:
 *   - a draggable Mapbox GL marker (click the map or drag the pin to place
 *     it), with an optional geofence-radius circle; and
 *   - the manual latitude/longitude number inputs, always visible and kept in
 *     sync with the pin, as the precise-entry path and the graceful fallback.
 *
 * Renderer: Mapbox GL JS v3 loading `mapbox://styles/mapbox/standard` — the
 * SAME Mapbox Standard style the member Android app renders (Style.STANDARD).
 * MapLibre GL cannot render the v3 Standard style, so this component uses the
 * proprietary-licensed `mapbox-gl` package (a deliberate, accepted trade-off
 * for an internal admin tool) with a public `pk.` access token
 * (`mapboxgl.accessToken`, from VITE_MAPBOX_TOKEN).
 *
 * Design constraints honoured here:
 *   - Degrades gracefully with NO Mapbox token: the map is never loaded, a
 *     clear notice is shown, and the manual inputs remain fully usable.
 *   - No coordinate is emitted until the operator explicitly places the pin (or
 *     types a value); a fresh map never silently submits (0, 0) / Null Island.
 *   - All Mapbox GL JS is loaded via dynamic import inside an effect, so this
 *     component (and the pure helpers in ./coordinates) stay importable and
 *     testable under jsdom where WebGL is unavailable.
 *   - Camera: flat 2D (pitch 0, bearing 0), day light preset. A picker needs an
 *     undistorted top-down mapping between screen clicks and coordinates, so we
 *     match the app's Standard STYLE without adopting a 3D/pitched camera.
 *
 * The component is a controlled input over the paired lat/lng *strings* so it
 * drops into the existing form state of each caller with no float round-trips.
 */

import { useEffect, useId, useRef, useState } from 'react';

import {
  DEFAULT_CENTER,
  DEFAULT_ZOOM,
  MAPBOX_STANDARD_STYLE,
  clampCoordinate,
  formatLatLng,
  geofenceCirclePolygon,
  getMapboxToken,
  parseLatLng,
  roundCoordinate,
  type LatLng,
} from './coordinates';

import styles from './MapLocationPicker.module.css';

// Minimal structural types for the slice of the Mapbox GL JS API we use. We
// deliberately avoid a hard type dependency on `mapbox-gl` (it is loaded
// lazily and may be absent from the type graph in some tooling paths).
interface GlLngLat {
  lng: number;
  lat: number;
}
interface GlMarker {
  setLngLat(value: [number, number]): GlMarker;
  addTo(map: GlMap): GlMarker;
  getLngLat(): GlLngLat;
  on(type: string, listener: () => void): void;
  remove(): void;
}
interface GlMap {
  on(type: string, listener: (ev: { lngLat: GlLngLat }) => void): void;
  addSource(id: string, source: unknown): void;
  getSource(id: string): unknown;
  removeSource(id: string): void;
  addLayer(layer: unknown): void;
  getLayer(id: string): unknown;
  removeLayer(id: string): void;
  isStyleLoaded?(): boolean;
  setConfigProperty?(importId: string, configName: string, value: unknown): void;
  easeTo(options: { center: [number, number] }): void;
  addControl(control: unknown, position?: string): void;
  remove(): void;
  resize(): void;
}

export interface MapLocationPickerProps {
  /** Current latitude form value (string, may be empty). */
  latitude: string;
  /** Current longitude form value (string, may be empty). */
  longitude: string;
  /** Emitted whenever the operator drags the pin, clicks the map, or types. */
  onChange: (latitude: string, longitude: string) => void;
  /** Accessible label for the latitude input. */
  labelLat: string;
  /** Accessible label for the longitude input. */
  labelLng: string;
  /** Localised helper text shown under the map ("drag the pin to place…"). */
  helpText?: string;
  /** Localised notice shown when no Mapbox token is configured. */
  unavailableText?: string;
  /**
   * Localised notice shown when a token IS configured but the map fails to
   * load (e.g. an invalid token → 401, a CSP host block, or an unreachable
   * style). Surfaces the failure instead of a silent blank box.
   */
  loadErrorText?: string;
  /** Optional geofence radius (metres) to visualise as a circle around the pin. */
  radiusMeters?: number;
  /** Viewport centre used before any coordinate is picked. */
  initialCenter?: LatLng;
  /** Marks the inputs as required. */
  required?: boolean;
  /** Disables the inputs and pin interaction. */
  disabled?: boolean;
  /** Validation error to surface under the inputs. */
  error?: string;
  /** Class applied to each field's label (to match host form styling). */
  labelClassName?: string;
  /** Class applied to each number input (to match host form styling). */
  inputClassName?: string;
}

const GEOFENCE_SOURCE = 'geofence';
const GEOFENCE_LAYERS = ['geofence-fill', 'geofence-outline'] as const;

/**
 * Remove the geofence layers + source if present. Mapbox GL throws when asked
 * to remove a layer/source that does not exist, so each removal is guarded.
 */
function clearGeofence(map: GlMap): void {
  try {
    for (const layerId of GEOFENCE_LAYERS) {
      if (map.getLayer(layerId)) map.removeLayer(layerId);
    }
    if (map.getSource(GEOFENCE_SOURCE)) map.removeSource(GEOFENCE_SOURCE);
  } catch {
    // Non-fatal — style may be mid-load; the next update re-attempts.
  }
}

export function MapLocationPicker({
  latitude,
  longitude,
  onChange,
  labelLat,
  labelLng,
  helpText,
  unavailableText,
  loadErrorText,
  radiusMeters,
  initialCenter,
  required,
  disabled,
  error,
  labelClassName,
  inputClassName,
}: MapLocationPickerProps): React.ReactElement {
  const token = getMapboxToken();
  const mapReady = token !== '';

  const reactId = useId();
  const latId = `${reactId}-lat`;
  const lngId = `${reactId}-lng`;

  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<GlMap | null>(null);
  const markerRef = useRef<GlMarker | null>(null);
  // Latest onChange without forcing the GL effect to re-run on every render.
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  const [mapError, setMapError] = useState(false);
  // Bumped once the Standard style finishes loading, so the geofence effect
  // (which adds sources/layers) re-runs when the style is actually ready.
  const [styleLoaded, setStyleLoaded] = useState(false);

  const value = parseLatLng(latitude, longitude);

  // --- Mapbox GL lifecycle (only when a token is configured) --------------
  useEffect(() => {
    if (!mapReady || !containerRef.current) return;

    let cancelled = false;
    let map: GlMap | null = null;

    void (async () => {
      try {
        const mod = await import('mapbox-gl');
        await import('mapbox-gl/dist/mapbox-gl.css');
        if (cancelled || !containerRef.current) return;

        const mapboxgl = ((mod as { default?: unknown }).default ?? mod) as {
          accessToken: string;
          Map: new (opts: unknown) => GlMap;
          Marker: new (opts: unknown) => GlMarker;
          NavigationControl: new (opts?: unknown) => unknown;
        };
        // Mapbox GL JS reads the public token off the module singleton before
        // it fetches the mapbox:// style + tiles.
        mapboxgl.accessToken = token;

        const center = value ?? initialCenter ?? DEFAULT_CENTER;
        map = new mapboxgl.Map({
          container: containerRef.current,
          style: MAPBOX_STANDARD_STYLE,
          center: [center.lng, center.lat],
          zoom: value ? 14 : DEFAULT_ZOOM,
          // Flat 2D top-down view: a picker maps screen clicks to coordinates,
          // so pitch/bearing are pinned to 0 (we match the STYLE, not a 3D
          // camera). Standard defaults to the "day" light preset; set it
          // explicitly once the style is ready for a deterministic look.
          pitch: 0,
          bearing: 0,
        });
        mapRef.current = map;

        // On-screen zoom in/out buttons (the + / − control). Scroll-wheel and
        // drag zoom keep working; this just adds always-visible controls for
        // operators who prefer clicking. Compass/pitch toggle is omitted because
        // this is a flat 2D top-down picker (bearing/pitch are pinned to 0), so
        // only the zoom buttons are meaningful. The control's button styling
        // comes from mapbox-gl.css, which is already imported above. Placed
        // top-right, the conventional Mapbox control corner.
        try {
          const nav = new mapboxgl.NavigationControl({
            showZoom: true,
            showCompass: false,
          });
          map.addControl(nav, 'top-right');
        } catch {
          // NavigationControl is non-essential chrome; never let it break the
          // map (scroll/drag zoom still work without the buttons).
        }

        // Tracks whether the initial style + first render completed. Used to
        // distinguish a fatal load failure (blank tiles) from harmless
        // post-load tile hiccups, without tearing down a working map.
        let loaded = false;

        map.on('load', () => {
          if (cancelled) return;
          loaded = true;
          try {
            map?.setConfigProperty?.('basemap', 'lightPreset', 'day');
          } catch {
            // setConfigProperty is Standard-only; ignore on other styles.
          }
          setStyleLoaded(true);
        });

        // A GL error BEFORE the style/tiles finish loading means the map will
        // never render (an invalid token → 401 on the style + tile requests, a
        // CSP host block, or an unreachable style URL) — exactly the "controls
        // and logo show but the canvas is a blank dark box" failure mode. Trip
        // the error state so the fallback notice explains it, instead of
        // leaving a silently empty map. Errors AFTER a successful load are
        // ignored: transient per-tile fetch hiccups must not kill a live map.
        map.on('error', () => {
          if (!cancelled && !loaded) setMapError(true);
        });

        const marker = new mapboxgl.Marker({
          draggable: !disabled,
          color: '#2563eb',
        });
        markerRef.current = marker;

        const emit = (lngLat: GlLngLat) => {
          const next = clampCoordinate({ lat: lngLat.lat, lng: lngLat.lng });
          const formatted = formatLatLng(next);
          onChangeRef.current(formatted.latitude, formatted.longitude);
        };

        if (value) {
          marker.setLngLat([value.lng, value.lat]).addTo(map);
        }

        marker.on('dragend', () => emit(marker.getLngLat()));

        map.on('click', (ev) => {
          if (disabled) return;
          marker.setLngLat([ev.lngLat.lng, ev.lngLat.lat]).addTo(map as GlMap);
          emit(ev.lngLat);
        });
      } catch {
        if (!cancelled) setMapError(true);
      }
    })();

    return () => {
      cancelled = true;
      setStyleLoaded(false);
      markerRef.current?.remove();
      markerRef.current = null;
      mapRef.current?.remove();
      mapRef.current = null;
    };
    // Intentionally run once for the map instance; external value changes are
    // reflected via the separate sync effect below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mapReady, token, disabled]);

  // --- Keep the pin in sync with the inputs (BOTH directions) -------------
  useEffect(() => {
    const map = mapRef.current;
    const marker = markerRef.current;
    if (!map || !marker) return;
    if (!value) {
      // Inputs were cleared → reflect the cleared state by detaching the pin.
      marker.remove();
      return;
    }
    const current = marker.getLngLat();
    if (
      roundCoordinate(current.lat) === roundCoordinate(value.lat) &&
      roundCoordinate(current.lng) === roundCoordinate(value.lng)
    ) {
      return;
    }
    marker.setLngLat([value.lng, value.lat]).addTo(map);
    map.easeTo({ center: [value.lng, value.lat] });
  }, [value?.lat, value?.lng]); // eslint-disable-line react-hooks/exhaustive-deps

  // --- Draw / update / clear the optional geofence circle -----------------
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const hasRadius = typeof radiusMeters === 'number' && radiusMeters > 0;
    if (!value || !hasRadius) {
      // No coordinate or no positive radius → remove any stale ring.
      clearGeofence(map);
      return;
    }
    // Sources/layers can only be added after the style is loaded; skip until
    // then (the styleLoaded dependency re-runs this effect once it is).
    if (map.isStyleLoaded && !map.isStyleLoaded()) return;
    const data = geofenceCirclePolygon(value, radiusMeters);
    const existing = map.getSource(GEOFENCE_SOURCE) as
      | { setData?: (d: unknown) => void }
      | undefined;
    try {
      if (existing?.setData) {
        existing.setData(data);
      } else {
        map.addSource(GEOFENCE_SOURCE, { type: 'geojson', data });
        if (!map.getLayer('geofence-fill')) {
          map.addLayer({
            id: 'geofence-fill',
            type: 'fill',
            source: GEOFENCE_SOURCE,
            paint: { 'fill-color': '#2563eb', 'fill-opacity': 0.12 },
          });
          map.addLayer({
            id: 'geofence-outline',
            type: 'line',
            source: GEOFENCE_SOURCE,
            paint: { 'line-color': '#2563eb', 'line-width': 1.5 },
          });
        }
      }
    } catch {
      // Source may not be ready before the style loads; the next value/radius
      // change re-attempts. Non-fatal.
    }
  }, [value?.lat, value?.lng, radiusMeters, styleLoaded]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className={styles.picker}>
      {mapReady && !mapError ? (
        <div
          ref={containerRef}
          className={styles.map}
          data-testid="map-canvas"
          aria-label={helpText}
        />
      ) : (
        <p className={styles.unavailable} role="note">
          {mapError
            ? (loadErrorText ??
              'Map unavailable — it failed to load. Enter the coordinates manually below.')
            : (unavailableText ??
              'Map unavailable — enter the coordinates manually below.')}
        </p>
      )}

      {mapReady && !mapError && helpText && (
        <p className={styles.help}>{helpText}</p>
      )}

      <div className={styles.inputs}>
        <label className={labelClassName} htmlFor={latId}>
          {labelLat}
          {required ? ' *' : ''}
          <input
            id={latId}
            className={inputClassName}
            type="number"
            step="any"
            min={-90}
            max={90}
            value={latitude}
            onChange={(e) => onChange(e.target.value, longitude)}
            required={required}
            disabled={disabled}
          />
        </label>
        <label className={labelClassName} htmlFor={lngId}>
          {labelLng}
          {required ? ' *' : ''}
          <input
            id={lngId}
            className={inputClassName}
            type="number"
            step="any"
            min={-180}
            max={180}
            value={longitude}
            onChange={(e) => onChange(latitude, e.target.value)}
            required={required}
            disabled={disabled}
          />
        </label>
      </div>

      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

export default MapLocationPicker;
