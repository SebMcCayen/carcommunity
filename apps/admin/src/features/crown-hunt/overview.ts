/** Explicit server snapshot. No polling, user-location data or silent partial totals. */
import {
  collection,
  query,
  where,
  limit,
  getDocsFromServer,
  getDocFromServer,
  doc,
} from 'firebase/firestore';
import { getAdminFirestore } from '@/lib/firestore';
import { callAdmin } from '@/lib/callables';
import { adminListSpawnAreas } from './spawn-areas';
import { toLiveCrownSpawn } from './live-map';
import type {
  AdminCrownHuntPointSummary,
  AdminCrownSpawnArea,
} from '@carcommunity/shared/crown-hunt';
export type OverviewCrown = ReturnType<typeof toLiveCrownSpawn> & { areaId: string | null };
export type CrownOverviewSnapshot = {
  areas: AdminCrownSpawnArea[];
  points: AdminCrownHuntPointSummary[];
  pointTotal: number;
  crowns: OverviewCrown[];
  crownsTruncated: boolean;
  areasPossiblyTruncated: boolean;
  spawnEnabled: boolean;
  readAt: string;
};
export async function loadCrownOverview(): Promise<CrownOverviewSnapshot> {
  const db = getAdminFirestore();
  const [areas, points, crowns, flags] = await Promise.all([
    adminListSpawnAreas({ limit: 200 }),
    callAdmin<{ data: { points: AdminCrownHuntPointSummary[] }; meta: { total: number } }>(
      'admin-listCrownPoints',
      { pageSize: 100, status: 'active' },
    ),
    getDocsFromServer(
      query(collection(db, 'crownSpawns'), where('status', '==', 'live'), limit(501)),
    ),
    getDocFromServer(doc(db, 'config', 'featureFlags')),
  ]);
  return {
    areas,
    points: points.data.points,
    pointTotal: points.meta.total,
    crowns: crowns.docs
      .slice(0, 500)
      .map((d) => ({
        ...toLiveCrownSpawn(d.id, d.data()),
        areaId: typeof d.get('areaId') === 'string' ? d.get('areaId') : null,
      })),
    crownsTruncated: crowns.size > 500,
    areasPossiblyTruncated: areas.length === 200,
    spawnEnabled: flags.get('crownHuntSpawn') === true,
    readAt: new Date().toISOString(),
  };
}
export function visibleCrowns(crowns: readonly OverviewCrown[], now: number) {
  return crowns.filter(
    (c) =>
      c.latitude !== null &&
      c.longitude !== null &&
      Number.isFinite(c.latitude) &&
      Number.isFinite(c.longitude) &&
      Math.abs(c.latitude) <= 90 &&
      Math.abs(c.longitude) <= 180 &&
      (c.expiresAtMs === null || c.expiresAtMs > now),
  );
}
