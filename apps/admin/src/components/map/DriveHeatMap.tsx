/**
 * Partner DRIVE HEATMAP map (admin/partner view).
 *
 * A read-only Mapbox GL map that renders the anonymised H3 drive-density
 * aggregate (partnerInsights-driveHeat) as coloured hexagon polygons: each cell
 * is drawn from its H3 boundary and shaded by relative drive density (weight),
 * with a LEGEND explaining the colour bands. Partners use it to see the most-
 * driven areas and buy in-app digital billboards there — it exposes no
 * individual drive, route, endpoint, identity or timestamp (every cell is a
 * ≥10-contributor aggregate).
 *
 * Same CSP-safe, lazy-loaded, degrade-gracefully pattern as CrownHeatMap:
 * mapbox-gl (and h3-js, for the boundary maths) are bundled self-hosted deps
 * imported dynamically inside an effect, and the whole map is replaced by a
 * notice when no token is configured or the style fails to load.
 */

import { useEffect, useRef, useState } from 'react';

import {
  DEFAULT_CENTER,
  DEFAULT_ZOOM,
  MAPBOX_STANDARD_STYLE,
  getMapboxToken,
} from './coordinates';
import {
  driveHeatBands,
  driveHeatColorStops,
  type DriveHeatBand,
} from '@/features/partner-drive-heat/heat-colors';
import type { DriveHeatCell } from '@/features/partner-drive-heat';

import styles from './AreaDrawMap.module.css';
import legendStyles from './DriveHeatMap.module.css';

interface GlMap {
  on(type: string, listener: (ev: unknown) => void): void;
  addSource(id: string, source: unknown): void;
  getSource(id: string): unknown;
  addLayer(layer: unknown): void;
  getLayer(id: string): unknown;
  isStyleLoaded?(): boolean;
  addControl(control: unknown, position?: string): void;
  setConfigProperty?(importId: string, configName: string, value: unknown): void;
  remove(): void;
}

/** Boundary maths from h3-js — dynamically imported so it never bloats first paint. */
interface H3Module {
  cellToBoundary(h3Index: string, formatAsGeoJson?: boolean): number[][];
}

export interface DriveHeatMapProps {
  cells: readonly DriveHeatCell[];
  labels: {
    attribution: string;
    unavailable: string;
    loadError: string;
    legendTitle: string;
    legendNote: string;
    bands: Record<string, string>;
  };
}

const HEAT_SOURCE = 'drive-heat';

/**
 * Builds the polygon FeatureCollection for the given cells, converting each H3
 * index to its (already-closed, [lng,lat]) boundary ring via h3-js. Pure aside
 * from the injected `cellToBoundary`, so it is unit-testable with a stub.
 */
export function cellsToFeatures(
  cells: readonly DriveHeatCell[],
  cellToBoundary: H3Module['cellToBoundary'],
): {
  type: 'FeatureCollection';
  features: Array<{
    type: 'Feature';
    geometry: { type: 'Polygon'; coordinates: number[][][] };
    properties: { weight: number; contributorCount: number };
  }>;
} {
  const features = cells
    .map((cell) => {
      const ring = cellToBoundary(cell.h3Index, true);
      if (!ring || ring.length < 4) return null;
      return {
        type: 'Feature' as const,
        geometry: { type: 'Polygon' as const, coordinates: [ring] },
        properties: { weight: cell.weight, contributorCount: cell.contributorCount },
      };
    })
    .filter((f): f is NonNullable<typeof f> => f !== null);
  return { type: 'FeatureCollection', features };
}

function Legend({
  bands,
  labels,
}: {
  bands: DriveHeatBand[];
  labels: DriveHeatMapProps['labels'];
}): React.ReactElement {
  return (
    <div className={legendStyles.legend} role="note" aria-label={labels.legendTitle}>
      <p className={legendStyles.legendTitle}>{labels.legendTitle}</p>
      {/* Densest band first so the legend reads top-down high → low. */}
      {[...bands].reverse().map((band) => (
        <div key={band.labelKey} className={legendStyles.legendRow}>
          <span className={legendStyles.legendSwatch} style={{ background: band.color }} />
          <span className={legendStyles.legendLabel}>{labels.bands[band.labelKey] ?? band.labelKey}</span>
        </div>
      ))}
      <p className={legendStyles.legendNote}>{labels.legendNote}</p>
    </div>
  );
}

export function DriveHeatMap({ cells, labels }: DriveHeatMapProps): React.ReactElement {
  const token = getMapboxToken();
  const mapReady = token !== '';
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<GlMap | null>(null);
  const h3Ref = useRef<H3Module | null>(null);
  const [mapError, setMapError] = useState(false);
  const [styleLoaded, setStyleLoaded] = useState(false);

  useEffect(() => {
    if (!mapReady || !containerRef.current) return;
    let cancelled = false;
    let map: GlMap | null = null;
    void (async () => {
      try {
        const [mod, h3mod] = await Promise.all([import('mapbox-gl'), import('h3-js')]);
        await import('mapbox-gl/dist/mapbox-gl.css');
        if (cancelled || !containerRef.current) return;
        h3Ref.current = h3mod as unknown as H3Module;
        const mapboxgl = ((mod as { default?: unknown }).default ?? mod) as {
          accessToken: string;
          Map: new (opts: unknown) => GlMap;
          NavigationControl: new (opts?: unknown) => unknown;
        };
        mapboxgl.accessToken = token;
        map = new mapboxgl.Map({
          container: containerRef.current,
          style: MAPBOX_STANDARD_STYLE,
          center: [DEFAULT_CENTER.lng, DEFAULT_CENTER.lat],
          zoom: DEFAULT_ZOOM - 1,
          pitch: 0,
          bearing: 0,
        });
        mapRef.current = map;
        try {
          map.addControl(new mapboxgl.NavigationControl({ showCompass: false }), 'top-right');
        } catch {
          // non-essential
        }
        let loaded = false;
        map.on('load', () => {
          if (cancelled) return;
          loaded = true;
          try {
            map?.setConfigProperty?.('basemap', 'lightPreset', 'day');
          } catch {
            // Standard-only
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
          map = null;
          setMapError(true);
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
        // gone
      }
      mapRef.current = null;
    };
  }, [mapReady, token]);

  useEffect(() => {
    const map = mapRef.current;
    const h3 = h3Ref.current;
    if (!map || !h3) return;
    if (map.isStyleLoaded && !map.isStyleLoaded()) return;
    const data = cellsToFeatures(cells, h3.cellToBoundary);
    // Anchor the fill ramp to the same relative bands the legend shows.
    const stops = driveHeatColorStops(cells);
    const src = map.getSource(HEAT_SOURCE) as { setData?: (d: unknown) => void } | undefined;
    try {
      if (src?.setData) {
        src.setData(data);
      } else {
        map.addSource(HEAT_SOURCE, { type: 'geojson', data });
        map.addLayer({
          id: `${HEAT_SOURCE}-fill`,
          type: 'fill',
          source: HEAT_SOURCE,
          paint: {
            'fill-color': ['interpolate', ['linear'], ['get', 'weight'], ...stops],
            'fill-opacity': 0.55,
          },
        });
        map.addLayer({
          id: `${HEAT_SOURCE}-outline`,
          type: 'line',
          source: HEAT_SOURCE,
          paint: {
            'line-color': '#7f1d1d',
            'line-width': 0.4,
            'line-opacity': 0.35,
          },
        });
      }
    } catch {
      // style mid-load
    }
  }, [cells, styleLoaded]);

  if (!mapReady || mapError) {
    return (
      <p className={styles.unavailable} role="note">
        {mapError ? labels.loadError : labels.unavailable}
      </p>
    );
  }

  return (
    <div className={styles.wrapper}>
      <div ref={containerRef} className={styles.map} data-testid="drive-heat-canvas" />
      <p className={styles.attribution}>{labels.attribution}</p>
      {cells.length > 0 ? <Legend bands={driveHeatBands(cells)} labels={labels} /> : null}
    </div>
  );
}

export default DriveHeatMap;
