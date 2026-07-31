/**
 * Shared, reusable admin location picker.
 *
 * One component used by every admin surface where an operator chooses a
 * position (Billboards, Crown Hunt / Kronjakt points, Partners, Events). It
 * renders:
 *   - a draggable Mapbox GL marker (click the map or drag the pin to place it),
 *     with an optional geofence-radius circle; and
 *   - the manual latitude/longitude number inputs, always visible and kept in
 *     sync with the pin, as the precise-entry path and the graceful fallback.
 *
 * Design constraints honoured here:
 *   - Degrades gracefully with NO Mapbox token: the map is never loaded, a
 *     clear notice is shown, and the manual inputs remain fully usable.
 *   - No coordinate is emitted until the operator explicitly places the pin (or
 *     types a value); a fresh map never silently submits (0, 0) / Null Island.
 *   - All Mapbox GL JS is loaded via dynamic import inside an effect, so this
 *     component (and the pure helpers in ./coordinates) stay importable and
 *     testable under jsdom where WebGL is unavailable.
 *
 * The component is a controlled input over the paired lat/lng *strings* so it
 * drops into the existing form state of each caller with no float round-trips.
 */

import { useEffect, useId, useRef, useState } from 'react';

import {
  DEFAULT_CENTER,
  DEFAULT_ZOOM,
  clampCoordinate,
  formatLatLng,
  getMapboxToken,
  parseLatLng,
  roundCoordinate,
  type LatLng,
} from './coordinates';

import styles from './MapLocationPicker.module.css';

// Minimal structural types for the slice of the Mapbox GL JS API we use. We
// deliberately avoid a hard type dependency on `mapbox-gl` (it is loaded lazily
// and may be absent from the type graph in some tooling paths).
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
  addLayer(layer: unknown): void;
  getLayer(id: string): unknown;
  easeTo(options: { center: [number, number] }): void;
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

/**
 * Build a GeoJSON polygon approximating a circle of `radiusMeters` around
 * `center`. Kept pure and outside the GL callbacks.
 */
function circlePolygon(center: LatLng, radiusMeters: number, steps = 64) {
  const coords: [number, number][] = [];
  const earth = 6378137; // metres
  const latRad = (center.lat * Math.PI) / 180;
  for (let i = 0; i <= steps; i += 1) {
    const angle = (i / steps) * 2 * Math.PI;
    const dx = (radiusMeters * Math.cos(angle)) / (earth * Math.cos(latRad));
    const dy = (radiusMeters * Math.sin(angle)) / earth;
    coords.push([
      center.lng + (dx * 180) / Math.PI,
      center.lat + (dy * 180) / Math.PI,
    ]);
  }
  return {
    type: 'Feature' as const,
    geometry: { type: 'Polygon' as const, coordinates: [coords] },
    properties: {},
  };
}

export function MapLocationPicker({
  latitude,
  longitude,
  onChange,
  labelLat,
  labelLng,
  helpText,
  unavailableText,
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

        const mapboxgl = (mod as { default?: unknown }).default ?? mod;
        (mapboxgl as { accessToken: string }).accessToken = token;

        const center = value ?? initialCenter ?? DEFAULT_CENTER;
        const MapCtor = (mapboxgl as { Map: new (opts: unknown) => GlMap }).Map;
        map = new MapCtor({
          container: containerRef.current,
          style: 'mapbox://styles/mapbox/streets-v12',
          center: [center.lng, center.lat],
          zoom: value ? 14 : DEFAULT_ZOOM,
        });
        mapRef.current = map;

        const MarkerCtor = (
          mapboxgl as { Marker: new (opts: unknown) => GlMarker }
        ).Marker;
        const marker = new MarkerCtor({ draggable: !disabled, color: '#2563eb' });
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
      markerRef.current?.remove();
      markerRef.current = null;
      mapRef.current?.remove();
      mapRef.current = null;
    };
    // Intentionally run once for the map instance; external value changes are
    // reflected via the separate sync effect below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mapReady, token, disabled]);

  // --- Keep the pin in sync when the operator types into the inputs -------
  useEffect(() => {
    const map = mapRef.current;
    const marker = markerRef.current;
    if (!map || !marker) return;
    if (!value) return;
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

  // --- Draw / update the optional geofence circle -------------------------
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !radiusMeters || !value) return;
    const data = circlePolygon(value, radiusMeters);
    const existing = map.getSource('geofence') as
      | { setData?: (d: unknown) => void }
      | undefined;
    try {
      if (existing?.setData) {
        existing.setData(data);
      } else {
        map.addSource('geofence', { type: 'geojson', data });
        if (!map.getLayer('geofence-fill')) {
          map.addLayer({
            id: 'geofence-fill',
            type: 'fill',
            source: 'geofence',
            paint: { 'fill-color': '#2563eb', 'fill-opacity': 0.12 },
          });
          map.addLayer({
            id: 'geofence-outline',
            type: 'line',
            source: 'geofence',
            paint: { 'line-color': '#2563eb', 'line-width': 1.5 },
          });
        }
      }
    } catch {
      // Source may not be ready before the style loads; the next value/radius
      // change re-attempts. Non-fatal.
    }
  }, [value?.lat, value?.lng, radiusMeters]); // eslint-disable-line react-hooks/exhaustive-deps

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
          {unavailableText ??
            'Map unavailable — enter the coordinates manually below.'}
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
