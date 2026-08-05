/**
 * Kronjakt spawn/collect HEAT MAP (admin stats dashboard).
 *
 * A small read-only Mapbox GL map that plots where auto-spawned crowns appear
 * and are collected, from the admin-only `crownHuntCellStats` aggregate. Each
 * ~1.1 km grid cell is a graduated circle at the cell centre, sized by spawn
 * volume and coloured by how many were collected there — a lightweight heat
 * layer without a raster tile pipeline.
 *
 * Same CSP-safe, lazy-loaded, degrade-gracefully pattern as MapLocationPicker:
 * mapbox-gl is a bundled self-hosted dep, imported dynamically inside an effect,
 * and the whole map is replaced by a notice when no token is configured or the
 * style fails to load.
 */

import { useEffect, useRef, useState } from 'react';

import {
  DEFAULT_CENTER,
  DEFAULT_ZOOM,
  MAPBOX_STANDARD_STYLE,
  getMapboxToken,
} from './coordinates';
import { cellKeyCenter, type CrownHuntCellStat } from '@/features/crown-hunt';

import styles from './AreaDrawMap.module.css';

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

export interface CrownHeatMapProps {
  cellStats: readonly CrownHuntCellStat[];
  labels: { attribution: string; unavailable: string; loadError: string };
}

const HEAT_SOURCE = 'crown-heat';

/** Build the point FeatureCollection (one point per resolvable cell). */
export function cellStatsToFeatures(cellStats: readonly CrownHuntCellStat[]): {
  type: 'FeatureCollection';
  features: Array<{
    type: 'Feature';
    geometry: { type: 'Point'; coordinates: [number, number] };
    properties: { spawned: number; collected: number };
  }>;
} {
  const features = cellStats
    .map((c) => {
      const center = cellKeyCenter(c.cellKey);
      if (!center) return null;
      return {
        type: 'Feature' as const,
        geometry: { type: 'Point' as const, coordinates: [center.lon, center.lat] as [number, number] },
        properties: { spawned: c.spawned, collected: c.collected },
      };
    })
    .filter((f): f is NonNullable<typeof f> => f !== null);
  return { type: 'FeatureCollection', features };
}

export function CrownHeatMap({ cellStats, labels }: CrownHeatMapProps): React.ReactElement {
  const token = getMapboxToken();
  const mapReady = token !== '';
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<GlMap | null>(null);
  const [mapError, setMapError] = useState(false);
  const [styleLoaded, setStyleLoaded] = useState(false);

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
    if (!map) return;
    if (map.isStyleLoaded && !map.isStyleLoaded()) return;
    const data = cellStatsToFeatures(cellStats);
    const src = map.getSource(HEAT_SOURCE) as { setData?: (d: unknown) => void } | undefined;
    try {
      if (src?.setData) {
        src.setData(data);
      } else {
        map.addSource(HEAT_SOURCE, { type: 'geojson', data });
        map.addLayer({
          id: `${HEAT_SOURCE}-circles`,
          type: 'circle',
          source: HEAT_SOURCE,
          paint: {
            'circle-radius': [
              'interpolate',
              ['linear'],
              ['get', 'spawned'],
              0,
              4,
              50,
              20,
            ],
            'circle-color': [
              'interpolate',
              ['linear'],
              ['get', 'collected'],
              0,
              '#f59e0b',
              25,
              '#dc2626',
            ],
            'circle-opacity': 0.55,
            'circle-stroke-width': 1,
            'circle-stroke-color': '#7c2d12',
          },
        });
      }
    } catch {
      // style mid-load
    }
     
  }, [cellStats, styleLoaded]);

  if (!mapReady || mapError) {
    return (
      <p className={styles.unavailable} role="note">
        {mapError ? labels.loadError : labels.unavailable}
      </p>
    );
  }

  return (
    <div className={styles.wrapper}>
      <div ref={containerRef} className={styles.map} data-testid="crown-heat-canvas" />
      <p className={styles.attribution}>{labels.attribution}</p>
    </div>
  );
}

export default CrownHeatMap;
