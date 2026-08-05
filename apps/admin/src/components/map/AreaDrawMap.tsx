/**
 * Kronjakt spawn-AREA draw map (admin only).
 *
 * A Mapbox GL map with DRAW TOOLS for the three contract area shapes:
 *   - polygon   — @mapbox/mapbox-gl-draw's built-in `draw_polygon` mode.
 *   - rectangle — a minimal custom mapbox-gl-draw mode (two clicks: one corner,
 *     then the opposite corner), since mapbox-gl-draw has no rectangle
 *     primitive. Emitted as axis-aligned bounds.
 *   - circle    — center-then-radius: click to drop the centre, set the radius
 *     with the numeric input (mapbox-gl-draw has no circle), rendered as a live
 *     overlay ring. Emitted as { center, radiusMeters }.
 *
 * CSP-safe: mapbox-gl and mapbox-gl-draw are BUNDLED self-hosted deps (no CDN /
 * external <script>), so the admin's strict `script-src 'self'` is satisfied;
 * mapbox's worker uses `blob:` which `worker-src 'self' blob:` already allows.
 * Everything (GL + Draw + their CSS) is loaded via dynamic import inside an
 * effect, mirroring MapLocationPicker, so the module stays importable under
 * jsdom where WebGL is unavailable.
 *
 * Attribution: the safe-stop / POI data behind these areas comes from
 * OpenStreetMap, so an "© OpenStreetMap contributors" credit is rendered over
 * the map.
 */

import { useEffect, useRef, useState } from 'react';

import {
  DEFAULT_CENTER,
  DEFAULT_ZOOM,
  MAPBOX_STANDARD_STYLE,
  getMapboxToken,
} from './coordinates';
import {
  circleToShape,
  ringToPolygonShape,
  ringToRectangleShape,
  shapeToGeoJson,
  type AdminCrownSpawnArea,
  type CrownSpawnAreaShape,
  type GeoJsonPosition,
} from '@/features/crown-hunt';

import styles from './AreaDrawMap.module.css';

export type AreaDrawTool = 'polygon' | 'rectangle' | 'circle';

// Loose structural GL types (mapbox-gl is loaded lazily; avoid a hard dep).
interface GlLngLat {
  lng: number;
  lat: number;
}
interface GlMap {
  on(type: string, listener: (ev: unknown) => void): void;
  off(type: string, listener: (ev: unknown) => void): void;
  addSource(id: string, source: unknown): void;
  getSource(id: string): unknown;
  removeSource(id: string): void;
  addLayer(layer: unknown): void;
  getLayer(id: string): unknown;
  removeLayer(id: string): void;
  isStyleLoaded?(): boolean;
  addControl(control: unknown, position?: string): void;
  setConfigProperty?(importId: string, configName: string, value: unknown): void;
  easeTo(options: { center: [number, number]; zoom?: number }): void;
  remove(): void;
}

// Minimal structural types for the slice of the mapbox-gl-draw mode API the
// custom rectangle mode uses (the package's own types are not in the graph).
interface DrawFeature {
  id: string;
  updateCoordinate(path: string, lng: number, lat: number): void;
  removeCoordinate(path: string): void;
  isValid(): boolean;
  toGeoJSON(): unknown;
}
interface DrawModeContext {
  newFeature(geojson: unknown): DrawFeature;
  addFeature(feature: DrawFeature): void;
  clearSelectedFeatures(): void;
  updateUIClasses(classes: Record<string, string>): void;
  setActionableState(state: Record<string, boolean>): void;
  changeMode(mode: string, opts?: unknown, eventOpts?: unknown): void;
  getFeature(id: string): DrawFeature | undefined;
  deleteFeature(ids: string[], opts?: unknown): void;
  activateUIButton(): void;
  map: { fire(type: string, payload: unknown): void };
}
interface RectangleState {
  rectangle: DrawFeature;
  startPoint?: [number, number];
}
interface DrawMouseEvent {
  lngLat: { lng: number; lat: number };
}
interface DrawDisplayFeature {
  properties: Record<string, unknown>;
}
interface DrawInstance {
  getAll(): { features: Array<{ geometry?: { type?: string; coordinates?: unknown } }> };
  deleteAll(): void;
  changeMode(mode: string): void;
}

export interface AreaDrawMapProps {
  tool: AreaDrawTool;
  /** Circle radius (metres) from the numeric input; drives the live overlay. */
  circleRadiusMeters: number;
  /** The dropped circle centre, or null before the operator clicks. */
  circleCenter: { lat: number; lon: number } | null;
  /** Called when the operator clicks the map in circle mode to set the centre. */
  onCircleCenterChange: (center: { lat: number; lon: number }) => void;
  /** The current drawn shape converted to the contract, or null when cleared. */
  onShapeDrawn: (shape: CrownSpawnAreaShape | null) => void;
  /** Existing areas to render read-only (active vs draft styled differently). */
  existingAreas: readonly AdminCrownSpawnArea[];
  /** Optional coordinate to frame the map on (e.g. an area being edited). */
  focusCenter?: { lat: number; lon: number } | null;
  labels: {
    attribution: string;
    unavailable: string;
    loadError: string;
    hint: string;
  };
}

const EXISTING_SOURCE = 'crown-areas-existing';
const CIRCLE_SOURCE = 'crown-area-circle';

/** A minimal two-click rectangle mode for mapbox-gl-draw (bundled, MIT-style). */
function makeRectangleMode(): Record<string, unknown> {
  return {
    onSetup(this: DrawModeContext): RectangleState {
      const rectangle = this.newFeature({
        type: 'Feature',
        properties: {},
        geometry: { type: 'Polygon', coordinates: [[]] },
      });
      this.addFeature(rectangle);
      this.clearSelectedFeatures();
      this.updateUIClasses({ mouse: 'add' });
      this.setActionableState({ trash: true });
      return { rectangle, startPoint: undefined };
    },
    onClick(this: DrawModeContext, state: RectangleState, e: DrawMouseEvent) {
      if (
        state.startPoint &&
        (state.startPoint[0] !== e.lngLat.lng || state.startPoint[1] !== e.lngLat.lat)
      ) {
        this.updateUIClasses({ mouse: 'pointer' });
        this.changeMode('simple_select', { featureIds: [state.rectangle.id] });
      } else {
        state.startPoint = [e.lngLat.lng, e.lngLat.lat];
      }
    },
    onMouseMove(this: DrawModeContext, state: RectangleState, e: DrawMouseEvent) {
      if (!state.startPoint) return;
      const [sx, sy] = state.startPoint;
      const ex = e.lngLat.lng;
      const ey = e.lngLat.lat;
      state.rectangle.updateCoordinate('0.0', sx, sy);
      state.rectangle.updateCoordinate('0.1', ex, sy);
      state.rectangle.updateCoordinate('0.2', ex, ey);
      state.rectangle.updateCoordinate('0.3', sx, ey);
      state.rectangle.updateCoordinate('0.4', sx, sy);
    },
    onKeyUp(this: DrawModeContext, _state: RectangleState, e: { keyCode: number }) {
      if (e.keyCode === 27) this.changeMode('simple_select');
    },
    onStop(this: DrawModeContext, state: RectangleState) {
      this.updateUIClasses({ mouse: 'none' });
      this.activateUIButton();
      if (this.getFeature(state.rectangle.id) === undefined) return;
      state.rectangle.removeCoordinate('0.4');
      if (state.rectangle.isValid()) {
        this.map.fire('draw.create', { features: [state.rectangle.toGeoJSON()] });
      } else {
        this.deleteFeature([state.rectangle.id], { silent: true });
        this.changeMode('simple_select', {}, { silent: true });
      }
    },
    toDisplayFeatures(
      this: DrawModeContext,
      state: RectangleState,
      geojson: DrawDisplayFeature,
      display: (g: DrawDisplayFeature) => void,
    ) {
      const isActive = geojson.properties.id === state.rectangle.id;
      geojson.properties.active = isActive ? 'true' : 'false';
      if (!isActive) return display(geojson);
      if (!state.startPoint) return;
      return display(geojson);
    },
    onTrash(this: DrawModeContext, state: RectangleState) {
      this.deleteFeature([state.rectangle.id], { silent: true });
      this.changeMode('simple_select');
    },
  };
}

export function AreaDrawMap({
  tool,
  circleRadiusMeters,
  circleCenter,
  onCircleCenterChange,
  onShapeDrawn,
  existingAreas,
  focusCenter,
  labels,
}: AreaDrawMapProps): React.ReactElement {
  const token = getMapboxToken();
  const mapReady = token !== '';

  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<GlMap | null>(null);
  const drawRef = useRef<DrawInstance | null>(null);
  const [mapError, setMapError] = useState(false);
  const [styleLoaded, setStyleLoaded] = useState(false);

  // Keep the freshest callbacks/state without re-running the GL effect.
  const onShapeDrawnRef = useRef(onShapeDrawn);
  onShapeDrawnRef.current = onShapeDrawn;
  const onCircleCenterChangeRef = useRef(onCircleCenterChange);
  onCircleCenterChangeRef.current = onCircleCenterChange;
  const toolRef = useRef(tool);
  toolRef.current = tool;

  // --- GL + Draw lifecycle (once per mount, when a token is configured) -----
  useEffect(() => {
    if (!mapReady || !containerRef.current) return;
    let cancelled = false;
    let map: GlMap | null = null;

    void (async () => {
      try {
        const glMod = await import('mapbox-gl');
        await import('mapbox-gl/dist/mapbox-gl.css');
        const drawMod = await import('@mapbox/mapbox-gl-draw');
        await import('@mapbox/mapbox-gl-draw/dist/mapbox-gl-draw.css');
        if (cancelled || !containerRef.current) return;

        const mapboxgl = ((glMod as { default?: unknown }).default ?? glMod) as {
          accessToken: string;
          Map: new (opts: unknown) => GlMap;
          NavigationControl: new (opts?: unknown) => unknown;
        };
        const MapboxDraw = ((drawMod as { default?: unknown }).default ?? drawMod) as unknown as {
          new (opts: unknown): DrawInstance;
          modes: Record<string, unknown>;
        };
        mapboxgl.accessToken = token;

        const center = focusCenter
          ? ([focusCenter.lon, focusCenter.lat] as [number, number])
          : ([DEFAULT_CENTER.lng, DEFAULT_CENTER.lat] as [number, number]);

        map = new mapboxgl.Map({
          container: containerRef.current,
          style: MAPBOX_STANDARD_STYLE,
          center,
          zoom: focusCenter ? 13 : DEFAULT_ZOOM,
          pitch: 0,
          bearing: 0,
        });
        mapRef.current = map;

        try {
          map.addControl(new mapboxgl.NavigationControl({ showCompass: false }), 'top-right');
        } catch {
          // Non-essential chrome.
        }

        const draw = new MapboxDraw({
          displayControlsDefault: false,
          modes: { ...MapboxDraw.modes, draw_rectangle: makeRectangleMode() },
        });
        drawRef.current = draw;
        map.addControl(draw as unknown as object);

        const emitFromDraw = () => {
          const d = drawRef.current;
          if (!d) return;
          const all = d.getAll();
          const feature = all.features[all.features.length - 1];
          if (!feature || feature.geometry?.type !== 'Polygon') {
            onShapeDrawnRef.current(null);
            return;
          }
          const ring = (feature.geometry.coordinates as GeoJsonPosition[][])[0];
          if (!ring) {
            onShapeDrawnRef.current(null);
            return;
          }
          const shape =
            toolRef.current === 'rectangle'
              ? ringToRectangleShape(ring)
              : ringToPolygonShape(ring);
          onShapeDrawnRef.current(shape);
        };

        let loaded = false;
        map.on('load', () => {
          if (cancelled) return;
          loaded = true;
          try {
            map?.setConfigProperty?.('basemap', 'lightPreset', 'day');
          } catch {
            // Standard-only; ignore elsewhere.
          }
          setStyleLoaded(true);
        });
        map.on('error', (() => {
          if (cancelled || loaded) return;
          try {
            mapRef.current?.remove();
          } catch {
            // mid-teardown
          }
          mapRef.current = null;
          drawRef.current = null;
          map = null;
          setMapError(true);
        }) as (ev: unknown) => void);

        map.on('draw.create', emitFromDraw as (ev: unknown) => void);
        map.on('draw.update', emitFromDraw as (ev: unknown) => void);
        map.on('draw.delete', (() => onShapeDrawnRef.current(null)) as (ev: unknown) => void);

        // Circle mode: a plain map click drops/moves the centre.
        map.on('click', ((ev: { lngLat: GlLngLat }) => {
          if (toolRef.current !== 'circle') return;
          onCircleCenterChangeRef.current({ lat: ev.lngLat.lat, lon: ev.lngLat.lng });
        }) as (ev: unknown) => void);
      } catch {
        if (!cancelled) setMapError(true);
      }
    })();

    return () => {
      cancelled = true;
      setStyleLoaded(false);
      try {
        mapRef.current?.remove();
      } catch {
        // already gone
      }
      mapRef.current = null;
      drawRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mapReady, token]);

  // --- Switch the active draw tool -----------------------------------------
  useEffect(() => {
    const draw = drawRef.current;
    if (!draw) return;
    try {
      draw.deleteAll();
      onShapeDrawn(null);
      if (tool === 'polygon') draw.changeMode('draw_polygon');
      else if (tool === 'rectangle') draw.changeMode('draw_rectangle');
      else draw.changeMode('simple_select');
    } catch {
      // Draw not ready yet; the next tool change re-attempts.
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tool, styleLoaded]);

  // --- Circle overlay + emit shape -----------------------------------------
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (map.isStyleLoaded && !map.isStyleLoaded()) return;
    const hasCircle = tool === 'circle' && circleCenter && circleRadiusMeters > 0;
    const src = map.getSource(CIRCLE_SOURCE) as { setData?: (d: unknown) => void } | undefined;
    if (!hasCircle) {
      if (src?.setData) src.setData({ type: 'FeatureCollection', features: [] });
      if (tool === 'circle') onShapeDrawn(null);
      return;
    }
    const shape = circleToShape(circleCenter, circleRadiusMeters);
    const feature = shapeToGeoJson(shape);
    try {
      if (src?.setData) {
        src.setData(feature);
      } else {
        map.addSource(CIRCLE_SOURCE, { type: 'geojson', data: feature });
        map.addLayer({
          id: `${CIRCLE_SOURCE}-fill`,
          type: 'fill',
          source: CIRCLE_SOURCE,
          paint: { 'fill-color': '#2563eb', 'fill-opacity': 0.15 },
        });
        map.addLayer({
          id: `${CIRCLE_SOURCE}-line`,
          type: 'line',
          source: CIRCLE_SOURCE,
          paint: { 'line-color': '#2563eb', 'line-width': 2 },
        });
      }
      onShapeDrawn(shape);
    } catch {
      // Style mid-load; the next change re-attempts.
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tool, circleCenter?.lat, circleCenter?.lon, circleRadiusMeters, styleLoaded]);

  // --- Render existing areas (read-only) -----------------------------------
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (map.isStyleLoaded && !map.isStyleLoaded()) return;
    const features = existingAreas.map((a) =>
      shapeToGeoJson(a.shape, { areaId: a.areaId, active: a.active }),
    );
    const collection = { type: 'FeatureCollection', features };
    const src = map.getSource(EXISTING_SOURCE) as { setData?: (d: unknown) => void } | undefined;
    try {
      if (src?.setData) {
        src.setData(collection);
      } else {
        map.addSource(EXISTING_SOURCE, { type: 'geojson', data: collection });
        map.addLayer({
          id: `${EXISTING_SOURCE}-fill`,
          type: 'fill',
          source: EXISTING_SOURCE,
          paint: {
            'fill-color': ['case', ['get', 'active'], '#16a34a', '#94a3b8'],
            'fill-opacity': 0.12,
          },
        });
        map.addLayer({
          id: `${EXISTING_SOURCE}-line`,
          type: 'line',
          source: EXISTING_SOURCE,
          paint: {
            'line-color': ['case', ['get', 'active'], '#16a34a', '#64748b'],
            'line-width': 1.5,
          },
        });
      }
    } catch {
      // Style mid-load.
    }
     
  }, [existingAreas, styleLoaded]);

  // --- Frame on the focus centre when it changes ---------------------------
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !focusCenter) return;
    try {
      map.easeTo({ center: [focusCenter.lon, focusCenter.lat], zoom: 13 });
    } catch {
      // non-fatal
    }
  }, [focusCenter?.lat, focusCenter?.lon]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!mapReady || mapError) {
    return (
      <p className={styles.unavailable} role="note">
        {mapError ? labels.loadError : labels.unavailable}
      </p>
    );
  }

  return (
    <div className={styles.wrapper}>
      <div ref={containerRef} className={styles.map} data-testid="area-draw-canvas" />
      <p className={styles.attribution}>{labels.attribution}</p>
      <p className={styles.hint}>{labels.hint}</p>
    </div>
  );
}

export default AreaDrawMap;
