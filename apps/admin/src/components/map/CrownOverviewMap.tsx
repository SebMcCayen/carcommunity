import { useEffect, useRef, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { Crown } from 'lucide-react';
import type { Map as GlMap, GeoJSONSource, Marker } from 'mapbox-gl';
import type {
  AdminCrownHuntPointSummary,
  AdminCrownSpawnArea,
} from '@carcommunity/shared/crown-hunt';
import type { OverviewCrown } from '@/features/crown-hunt/overview';
import { shapeToGeoJson, validateAreaShape } from '@/features/crown-hunt/spawn-areas';
import { DEFAULT_CENTER, getMapboxToken } from './coordinates';
import styles from './CrownOverviewMap.module.css';
export function overviewAreaFeatures(
  areas: readonly AdminCrownSpawnArea[],
  selected: string | null,
) {
  return {
    type: 'FeatureCollection' as const,
    features: areas.flatMap((area) => {
      try {
        return validateAreaShape(area.shape).ok
          ? [
              shapeToGeoJson(area.shape, {
                id: area.areaId,
                selected: area.areaId === selected,
                active: area.active,
              }),
            ]
          : [];
      } catch {
        return [];
      }
    }),
  };
}
export function CrownOverviewMap({
  areas,
  crowns,
  points,
  selectedArea,
  onArea,
  onPoint,
}: {
  areas: AdminCrownSpawnArea[];
  crowns: OverviewCrown[];
  points: AdminCrownHuntPointSummary[];
  selectedArea: string | null;
  onArea: (id: string) => void;
  onPoint: (point: AdminCrownHuntPointSummary) => void;
}) {
  const node = useRef<HTMLDivElement>(null),
    map = useRef<GlMap | null>(null),
    [ready, setReady] = useState(false),
    [error, setError] = useState(false);
  const latest = useRef({ onArea, onPoint, areas });
  latest.current = { onArea, onPoint, areas };
  const token = getMapboxToken();
  const areaData = JSON.stringify(areas),
    crownData = JSON.stringify(crowns),
    pointData = JSON.stringify(points);
  // A deliberate local geometry mode for cost-free QA. Never enabled in production.
  const geometryOnly =
    import.meta.env.DEV &&
    import.meta.env.VITE_FIREBASE_PROJECT_ID?.startsWith('demo-') &&
    import.meta.env.VITE_LOCAL_MAP_GEOMETRY_ONLY === 'true';
  useEffect(() => {
    if ((!token && !geometryOnly) || !node.current) return;
    let current = true,
      instance: GlMap | null = null;
    void import('mapbox-gl')
      .then(async (module) => {
        await import('mapbox-gl/dist/mapbox-gl.css');
        if (!current || !node.current) return;
        const gl = module.default;
        instance = new gl.Map({
          container: node.current,
          accessToken: geometryOnly ? '' : token,
          testMode: !!geometryOnly,
          performanceMetricsCollection: !geometryOnly,
          style: geometryOnly
            ? {
                version: 8,
                sources: {},
                layers: [
                  {
                    id: 'background',
                    type: 'background',
                    paint: { 'background-color': '#111b2d' },
                  },
                ],
              }
            : 'mapbox://styles/mapbox/dark-v11',
          center: [DEFAULT_CENTER.lng, DEFAULT_CENTER.lat],
          zoom: 10.4,
          pitch: 0,
        });
        map.current = instance;
        instance.addControl(new gl.NavigationControl({ showCompass: false }), 'top-left');
        instance.addControl(new gl.ScaleControl({ unit: 'metric' }), 'bottom-left');
        instance.on('load', () => {
          if (current) setReady(true);
        });
        instance.on('error', () => {
          if (current) setError(true);
        });
      })
      .catch(() => {
        if (current) setError(true);
      });
    return () => {
      current = false;
      setReady(false);
      instance?.remove();
      map.current = null;
    };
  }, [token, geometryOnly]);
  useEffect(() => {
    const m = map.current;
    if (!m || !ready) return;
    const data = overviewAreaFeatures(JSON.parse(areaData) as AdminCrownSpawnArea[], selectedArea);
    const source = m.getSource('overview-areas') as GeoJSONSource | undefined;
    if (source) source.setData(data);
    else {
      m.addSource('overview-areas', { type: 'geojson', data });
      m.addLayer({
        id: 'overview-areas-fill',
        type: 'fill',
        source: 'overview-areas',
        paint: {
          'fill-color': ['case', ['get', 'selected'], '#eab54b', '#a49ad1'],
          'fill-opacity': ['case', ['get', 'selected'], 0.14, 0.05],
        },
      });
      m.addLayer({
        id: 'overview-areas-line',
        type: 'line',
        source: 'overview-areas',
        paint: {
          'line-color': ['case', ['get', 'selected'], '#eab54b', '#a49ad1'],
          'line-width': ['case', ['get', 'selected'], 2.5, 1.5],
        },
      });
      m.on('click', 'overview-areas-fill', (event) => {
        const id = event.features?.[0]?.properties?.id;
        if (typeof id === 'string') latest.current.onArea(id);
      });
    }
  }, [areaData, selectedArea, ready]);
  useEffect(() => {
    const m = map.current,
      area = latest.current.areas.find((value) => value.areaId === selectedArea);
    if (!m || !ready || !area) return;
    const feature = overviewAreaFeatures([area], selectedArea).features[0];
    const ring = feature?.geometry.coordinates[0];
    if (!ring?.length) return;
    const xs = ring.map((point) => point[0]),
      ys = ring.map((point) => point[1]);
    m.fitBounds(
      [
        [Math.min(...xs), Math.min(...ys)],
        [Math.max(...xs), Math.max(...ys)],
      ],
      { padding: 60, maxZoom: 13, duration: 300 },
    );
  }, [selectedArea, ready]);
  useEffect(() => {
    const m = map.current;
    if (!m || !ready) return;
    let current = true;
    const markers: Marker[] = [],
      roots: Root[] = [];
    void import('mapbox-gl').then(({ default: gl }) => {
      if (!current) return;
      const add = (
        latitude: number,
        longitude: number,
        label: string,
        manual: boolean,
        select?: () => void,
      ) => {
        if (
          !Number.isFinite(latitude) ||
          !Number.isFinite(longitude) ||
          Math.abs(latitude) > 90 ||
          Math.abs(longitude) > 180
        )
          return;
        const element = document.createElement(select ? 'button' : 'span');
        element.className = (manual ? styles.manual : styles.automatic) ?? '';
        element.setAttribute('aria-label', label);
        element.title = label;
        if (select) {
          element.setAttribute('type', 'button');
          element.addEventListener('click', select);
        }
        const root = createRoot(element);
        root.render(<Crown size={23} fill={manual ? 'none' : 'currentColor'} strokeWidth={1.5} />);
        roots.push(root);
        markers.push(new gl.Marker({ element }).setLngLat([longitude, latitude]).addTo(m));
      };
      for (const crown of JSON.parse(crownData) as OverviewCrown[])
        if (crown.latitude !== null && crown.longitude !== null)
          add(
            crown.latitude,
            crown.longitude,
            `Automatisk krona · ${crown.rewardPoints ?? 'Okänt antal'} KP`,
            false,
            crown.areaId ? () => latest.current.onArea(crown.areaId!) : undefined,
          );
      for (const point of JSON.parse(pointData) as AdminCrownHuntPointSummary[])
        add(
          point.latitude,
          point.longitude,
          `Handplacerad krona · ${point.rewardPoints} KP`,
          true,
          () => latest.current.onPoint(point),
        );
    });
    return () => {
      current = false;
      for (const marker of markers) marker.remove();
      for (const root of roots) root.unmount();
    };
  }, [crownData, pointData, ready]);
  return (
    <div className={styles.wrapper}>
      <div
        ref={node}
        className={styles.canvas}
        aria-label="Karta med Kronjaktens områden och kronor"
        data-testid="crown-overview-map"
      />
      {((!token && !geometryOnly) || error) && (
        <p className={styles.notice} role="note">
          Kartan kan inte visas. Områden och kronor går fortfarande att hantera i listorna.
        </p>
      )}
      {geometryOnly && <p className={styles.local}>Lokal geometrivy · utan vägunderlag</p>}
    </div>
  );
}
