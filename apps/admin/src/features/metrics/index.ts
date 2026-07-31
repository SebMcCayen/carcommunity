/**
 * Community growth metrics (admin portal).
 *
 * Reads the daily snapshot series the scheduled `metrics-captureDaily` job
 * writes to `metrics/{YYYY-MM-DD}` and shapes it for the charts on
 * /metrics. Direct rules-gated SDK read (admin-only; see firestore.rules),
 * ordered by document id (the date), so no composite index is required.
 *
 * Every snapshot is a PII-free aggregate — totals and per-brand counts, never
 * anything per-member. This module only reads and reshapes; it adds no field
 * that could identify a member.
 *
 * The MetricsSnapshot shape mirrors functions/src/metrics/metrics-core.ts by
 * hand: the Cloud Functions codebase is standalone (it does not depend on
 * @carcommunity/shared), so — like DashboardStats and the partner-insights
 * types — the writer and reader each declare the shape. Keep the two in sync.
 */
import {
  collection,
  getDocs,
  orderBy,
  query,
  type Query,
} from 'firebase/firestore';
import { getAdminFirestore } from '../../lib/firestore';

/** One daily snapshot (mirror of functions/src/metrics/metrics-core.ts). */
export interface MetricsSnapshot {
  date: string;
  capturedAtMs: number;
  totalUsers: number;
  convoysCreated: number;
  totalDistanceMeters: number;
  eventsHeld: number;
  drivesSaved: number;
  crownsCollected: number;
  friendConnections: number;
  activeConvoys: number;
  vehicleProfiles: number;
  brandDistribution: Record<string, number>;
}

/** A numeric metric that has a value on every snapshot (used for trend charts). */
export type TrendMetric =
  | 'totalUsers'
  | 'convoysCreated'
  | 'totalDistanceMeters'
  | 'eventsHeld'
  | 'drivesSaved'
  | 'crownsCollected'
  | 'friendConnections'
  | 'activeConvoys'
  | 'vehicleProfiles';

/** One (date, value) point on a chart. */
export interface SeriesPoint {
  date: string;
  value: number;
}

/** One brand bucket for the distribution chart. */
export interface BrandSlice {
  makeId: string;
  label: string;
  count: number;
}

const NUMERIC_FIELDS: readonly (keyof MetricsSnapshot)[] = [
  'capturedAtMs',
  'totalUsers',
  'convoysCreated',
  'totalDistanceMeters',
  'eventsHeld',
  'drivesSaved',
  'crownsCollected',
  'friendConnections',
  'activeConvoys',
  'vehicleProfiles',
];

/** Coerces a Firestore document to a MetricsSnapshot, defaulting missing numbers to 0. */
function toSnapshot(id: string, data: Record<string, unknown>): MetricsSnapshot {
  const snapshot = {
    date: typeof data.date === 'string' ? data.date : id,
    brandDistribution:
      data.brandDistribution && typeof data.brandDistribution === 'object'
        ? (data.brandDistribution as Record<string, number>)
        : {},
  } as MetricsSnapshot;
  for (const field of NUMERIC_FIELDS) {
    (snapshot as unknown as Record<string, number>)[field] =
      typeof data[field] === 'number' ? (data[field] as number) : 0;
  }
  return snapshot;
}

/**
 * Loads the full snapshot series, oldest first. Ordered by document id (date),
 * which Firestore serves from the automatic `__name__` index — no composite
 * index to deploy.
 */
export async function loadMetricsSeries(): Promise<MetricsSnapshot[]> {
  const db = getAdminFirestore();
  const q: Query = query(collection(db, 'metrics'), orderBy('__name__', 'asc'));
  const snap = await getDocs(q);
  return snap.docs.map((d) => toSnapshot(d.id, d.data()));
}

/** Extracts a (date → value) trend for one metric. */
export function trendOf(series: MetricsSnapshot[], metric: TrendMetric): SeriesPoint[] {
  return series.map((s) => ({ date: s.date, value: s[metric] }));
}

/**
 * Derives "new users per day" as the delta between consecutive snapshots'
 * cumulative `totalUsers`. The first snapshot has no predecessor, so it is
 * omitted rather than shown as a spurious spike equal to the running total.
 * Negative deltas (e.g. account deletions) are clamped to 0 for the bar chart.
 */
export function newUsersPerDay(series: MetricsSnapshot[]): SeriesPoint[] {
  const points: SeriesPoint[] = [];
  for (let i = 1; i < series.length; i += 1) {
    const delta = series[i]!.totalUsers - series[i - 1]!.totalUsers;
    points.push({ date: series[i]!.date, value: Math.max(0, delta) });
  }
  return points;
}

/** Prettifies a catalogue make id for display without importing the catalogue. */
export function brandLabel(makeId: string): string {
  if (makeId === 'other') return 'Other / not listed';
  const words = makeId.split(/[-_]/);
  return words
    .map((w) => (w.length <= 3 ? w.toUpperCase() : w.charAt(0).toUpperCase() + w.slice(1)))
    .join(' ');
}

/** Brand distribution of the latest snapshot, largest first. */
export function brandSlices(latest: MetricsSnapshot | null): BrandSlice[] {
  if (!latest) return [];
  return Object.entries(latest.brandDistribution)
    .map(([makeId, count]) => ({ makeId, label: brandLabel(makeId), count }))
    .sort((a, b) => b.count - a.count);
}

/** Metres → whole kilometres. */
export function metersToKm(meters: number): number {
  return Math.round(meters / 1000);
}
