import { callAdmin } from '@/lib/callables';

export type PlacementReason = 'unsafe' | 'hard_to_reach' | 'private_or_closed' | 'other';
export type PlacementDecision = 'ignore' | 'move' | 'remove';

export interface PlacementPosition {
  latitude: number;
  longitude: number;
}

export interface PlacementCase extends PlacementPosition {
  caseId: string;
  crownId: string;
  source: 'automatic' | 'hand_placed';
  areaId: string | null;
  areaName: string | null;
  rarity: string | null;
  reportCount: number;
  reasons: Partial<Record<PlacementReason, number>>;
  version: number;
  decision: PlacementDecision | null;
  replacement: PlacementPosition | null;
  lastReportedAt: string;
}

export interface PlacementCaseDetail extends PlacementCase {
  active: boolean;
  canMove: boolean;
  reports: { id: string; reason: PlacementReason; comment: string | null; createdAt: string }[];
}

export interface PlacementDestination extends PlacementPosition {
  poiId: string;
  category: 'parking' | 'fuel' | 'charging';
  distanceMeters: number;
}

interface PlacementCursor {
  id: string;
  seconds: number;
  nanos: number;
}

interface PlacementPage {
  cases: PlacementCase[];
  openCount: number;
  nextCursor: PlacementCursor | null;
}

type PlacementResolution =
  | { action: 'ignore' | 'remove' }
  | {
      action: 'move';
      destination: { poiId: string } | (PlacementPosition & { safeLocationConfirmed: true });
    };

export function listPlacementCases(closed: boolean, cursor?: PlacementCursor) {
  return callAdmin<PlacementPage>('crownHunt-listPlacementCases', {
    closed,
    ...(cursor ? { cursor } : {}),
  });
}

export function getPlacementCase(caseId: string) {
  return callAdmin<PlacementCaseDetail>('crownHunt-getPlacementCase', { caseId });
}

export function listPlacementDestinations(caseId: string) {
  return callAdmin<{ destinations: PlacementDestination[] }>(
    'crownHunt-listPlacementDestinations',
    { caseId },
  );
}

export function resolvePlacementCase(report: PlacementCase, resolution: PlacementResolution) {
  return callAdmin<{ resolved: true }>('crownHunt-resolvePlacementCase', {
    caseId: report.caseId,
    version: report.version,
    ...resolution,
  });
}
