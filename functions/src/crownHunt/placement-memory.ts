import type { Query, Transaction } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { haversineDistanceMeters } from './crown-hunt-geo';
import type { CrownPosition } from './crown-spawn-core';

// Covers spawn jitter (up to 5 m) and minor changes to a cached stop on re-ingest.
export const PLACEMENT_BLOCK_RADIUS_METERS = 30;
const BLOCK_LATITUDE_MARGIN = 0.0003; // > 30 m, independent of longitude.

export const placementBlocks = db.collection('crownPlacementBlocks');
export const automaticPlacementMoves = db.collection('crownPlacementAutomaticMoves');
export const manualPlacementMoves = db.collection('crownPlacementManualMoves');

export interface AutomaticPlacementMove extends CrownPosition {
  areaId: string;
  areaCell: string;
  poiId: string;
  sourceSpawnId: string;
}

export function distanceBetween(a: CrownPosition, b: CrownPosition): number {
  return haversineDistanceMeters(a.latitude, a.longitude, b.latitude, b.longitude);
}

export function isPlacementBlocked(position: CrownPosition, blocks: readonly CrownPosition[]) {
  return blocks.some((block) => distanceBetween(position, block) <= PLACEMENT_BLOCK_RADIUS_METERS);
}

export async function readPlacementBlocks(minLat: number, maxLat: number, tx?: Transaction) {
  const query: Query = placementBlocks
    .where('latitude', '>=', minLat - BLOCK_LATITUDE_MARGIN)
    .where('latitude', '<=', maxLat + BLOCK_LATITUDE_MARGIN)
    .select('latitude', 'longitude');
  const snapshot = tx ? await tx.get(query) : await query.get();
  return snapshot.docs.map((doc) => doc.data() as CrownPosition);
}
