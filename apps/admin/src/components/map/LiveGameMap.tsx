/**
 * Kronjakt LIVE game map (admin stats dashboard).
 *
 * A read-only Mapbox GL map that plots the CURRENT game state in real time:
 * live auto-spawned crowns (gold markers) and deployed traps / Spikmatta (red
 * markers). Unlike the spawn HEAT map (CrownHeatMap) — which aggregates where
 * crowns historically appear — this shows what is on the map right now, fed by
 * live `onSnapshot` subscriptions in the parent (see LiveGameMapSection).
 *
 * Cloned from CrownHeatMap and kept to the same CSP-safe, lazy-loaded,
 * degrade-gracefully contract: mapbox-gl is a bundled self-hosted dep imported
 * dynamically inside an effect, and the whole map is replaced by a notice when
 * no token is configured or the style fails to load.
 *
 * Live-users layer is intentionally NOT here (deferred): admin has no read path
 * to the RTDB live-location sessions yet.
 */

import { useEffect, useRef, useState } from 'react';

import {
  DEFAULT_CENTER,
  DEFAULT_ZOOM,
  MAPBOX_STANDARD_STYLE,
  getMapboxToken,
} from './coordinates';
import type { LiveCrownSpawn, LiveTrap } from '@/features/crown-hunt';

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

export interface LiveGameMapProps {
  crowns: readonly LiveCrownSpawn[];
  traps: readonly LiveTrap[];
  labels: { attribution: string; unavailable: string; loadError: string };
}

const CROWN_SOURCE = 'live-crowns';
const TRAP_SOURCE = 'live-traps';

// Gold coin theme for crowns; alert red for traps — the same palette the perk
// logos + heat map use, so the whole dashboard reads as one system.
const CROWN_FILL = '#ecb44c';
const CROWN_STROKE = '#c9922e';
const TRAP_FILL = '#dc2626';
const TRAP_STROKE = '#7c2d12';

/** Build the crown point FeatureCollection (one point per resolvable crown). */
export function crownsToFeatures(crowns: readonly LiveCrownSpawn[]): {
  type: 'FeatureCollection';
  features: Array<{
    type: 'Feature';
    geometry: { type: 'Point'; coordinates: [number, number] };
    properties: { id: string; rarity: string | null; rewardPoints: number | null };
  }>;
} {
  const features = crowns
    .filter((c) => Number.isFinite(c.latitude) && Number.isFinite(c.longitude))
    .map((c) => ({
      type: 'Feature' as const,
      geometry: { type: 'Point' as const, coordinates: [c.longitude, c.latitude] as [number, number] },
      properties: { id: c.id, rarity: c.rarity, rewardPoints: c.rewardPoints },
    }));
  return { type: 'FeatureCollection', features };
}

/** Build the trap point FeatureCollection (one point per resolvable trap). */
export function trapsToFeatures(traps: readonly LiveTrap[]): {
  type: 'FeatureCollection';
  features: Array<{
    type: 'Feature';
    geometry: { type: 'Point'; coordinates: [number, number] };
    properties: { id: string; victimCount: number };
  }>;
} {
  const features = traps
    .filter((tr) => Number.isFinite(tr.latitude) && Number.isFinite(tr.longitude))
    .map((tr) => ({
      type: 'Feature' as const,
      geometry: { type: 'Point' as const, coordinates: [tr.longitude, tr.latitude] as [number, number] },
      properties: { id: tr.id, victimCount: tr.victimCount },
    }));
  return { type: 'FeatureCollection', features };
}

export function LiveGameMap({ crowns, traps, labels }: LiveGameMapProps): React.ReactElement {
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

  // Crown + trap layers, kept in sync with the live data. Both sources are
  // (re)created on first style load, then updated in place via setData so live
  // spawns/traps appearing and expiring never rebuild the layers.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (map.isStyleLoaded && !map.isStyleLoaded()) return;
    const crownData = crownsToFeatures(crowns);
    const trapData = trapsToFeatures(traps);
    try {
      const crownSrc = map.getSource(CROWN_SOURCE) as { setData?: (d: unknown) => void } | undefined;
      if (crownSrc?.setData) {
        crownSrc.setData(crownData);
      } else {
        map.addSource(CROWN_SOURCE, { type: 'geojson', data: crownData });
        map.addLayer({
          id: `${CROWN_SOURCE}-circles`,
          type: 'circle',
          source: CROWN_SOURCE,
          paint: {
            'circle-radius': 7,
            'circle-color': CROWN_FILL,
            'circle-opacity': 0.9,
            'circle-stroke-width': 2,
            'circle-stroke-color': CROWN_STROKE,
          },
        });
      }
      const trapSrc = map.getSource(TRAP_SOURCE) as { setData?: (d: unknown) => void } | undefined;
      if (trapSrc?.setData) {
        trapSrc.setData(trapData);
      } else {
        map.addSource(TRAP_SOURCE, { type: 'geojson', data: trapData });
        map.addLayer({
          id: `${TRAP_SOURCE}-circles`,
          type: 'circle',
          source: TRAP_SOURCE,
          paint: {
            'circle-radius': 8,
            'circle-color': TRAP_FILL,
            'circle-opacity': 0.85,
            'circle-stroke-width': 2,
            'circle-stroke-color': TRAP_STROKE,
          },
        });
      }
    } catch {
      // style mid-load
    }

  }, [crowns, traps, styleLoaded]);

  if (!mapReady || mapError) {
    return (
      <p className={styles.unavailable} role="note">
        {mapError ? labels.loadError : labels.unavailable}
      </p>
    );
  }

  return (
    <div className={styles.wrapper}>
      <div ref={containerRef} className={styles.map} data-testid="live-game-canvas" />
      <p className={styles.attribution}>{labels.attribution}</p>
    </div>
  );
}

export default LiveGameMap;
