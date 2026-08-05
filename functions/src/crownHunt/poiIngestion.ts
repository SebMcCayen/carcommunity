/**
 * Kronjakt AUTO-SPAWN — OpenStreetMap POI ingestion (I/O half of the safe-stop
 * placement feature; the pure half is osm-poi-core.ts).
 *
 * For every ACTIVE, safe-confirmed marked area (`crownSpawnAreas/{areaId}`), this
 * queries the free Overpass API once for the safe-stop POIs inside the area's
 * bounding box — parking lots, fuel stations, charging stations — filters them to
 * the ones actually INSIDE the drawn shape, and caches them under
 * `crownSpawnAreaPois/{areaId}/pois/{poiId}`. The area document gets a `poiCount`
 * and `poisRefreshedAt` stamp so the admin UI can show "N safe spots found in
 * this area" and so staleness is visible.
 *
 * Ingestion is triggered TWO ways:
 *  1. {@link onSpawnAreaWrittenIngestPois} — a Firestore trigger that runs when an
 *     area is created active, activated, or re-drawn, so a freshly marked area
 *     gets its POIs promptly (guarded against re-firing on its own poiCount
 *     write);
 *  2. {@link refreshAreaPois} — a WEEKLY scheduled refresh (POIs rarely move, so
 *     weekly keeps Overpass usage tiny while catching new/removed stops).
 *
 * SAFETY: an Overpass failure/timeout NEVER breaks anything. The fetch is
 * time-bounded, and on any failure the last cached POIs are kept untouched (and
 * `poisRefreshedAt` is left where it was, so the staleness shows) — the spawn
 * pass reads the cache, so a failed refresh simply means it keeps placing at the
 * previous safe stops. If an area has NO cached POIs, the spawn pass places
 * NOTHING there (spawnScheduled.ts) rather than falling back to random points.
 *
 * The Overpass endpoint is a configurable env var (`OVERPASS_ENDPOINT`, default
 * the public `https://overpass-api.de/api/interpreter`) — NO secret and no key
 * required. The fetcher is injected so unit/emulator tests drive ingestion with a
 * mocked response and never hit the network.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { onDocumentWritten } from 'firebase-functions/v2/firestore';
import { Timestamp, type DocumentData } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { MAX_INSTANCES_SCHEDULED, MAX_INSTANCES_TRIGGER } from '../shared/instanceLimits';
import { withServerErrorReporting } from '../errors/serverErrors';
import { shapeBoundingBox, type CrownSpawnAreaShape } from './crown-area-core';
import { crownCellKey } from './crown-spawn-core';
import {
  OVERPASS_ENDPOINT_DEFAULT,
  OVERPASS_QUERY_TIMEOUT_SECONDS,
  buildOverpassQuery,
  crownPoiDocId,
  filterPoisInShape,
  parseOverpassResponse,
  type NormalizedPoi,
  type OverpassResponse,
} from './osm-poi-core';

/**
 * Resolves the Overpass endpoint. Configurable via the `OVERPASS_ENDPOINT`
 * environment variable (a plain env var — NOT a secret, since the endpoint is
 * public and carries no key), defaulting to the public interpreter. Read from
 * `process.env` rather than a `defineString` param on purpose: a param prompts
 * interactively in the emulator / on deploy when unset, whereas an env var with a
 * code default ships with zero provisioning and can still be overridden from a
 * `.env` file or deploy config to point at a self-hosted/mirror instance.
 */
function resolveOverpassEndpoint(): string {
  const configured = process.env.OVERPASS_ENDPOINT?.trim();
  return configured && configured.length > 0 ? configured : OVERPASS_ENDPOINT_DEFAULT;
}

const POIS_COLLECTION = 'crownSpawnAreaPois';
const AREAS_COLLECTION = 'crownSpawnAreas';

/** Documents per Firestore batched write (under the 500 limit). */
const POI_WRITE_BATCH_SIZE = 400;

/**
 * Upper bound on POIs cached per area. A 50 km-radius circle over a dense city
 * could return thousands; the density budget is per-cell and separation is 150 m,
 * so a few thousand anchor points already saturate any plausible spawn target.
 * Bounds the cache write and the per-area read in the spawn pass.
 */
export const MAX_POIS_PER_AREA = 5000;

/** Areas refreshed per weekly scheduled run. Admins draw a handful, so generous. */
const MAX_AREAS_PER_REFRESH = 500;

/**
 * Upper bound on one Overpass round-trip before we give up and keep the cache.
 * Well under the function timeout. Exported so the unit test can assert the
 * fetcher requests exactly this bound.
 */
export const FETCH_TIMEOUT_MS = 30_000;

export type OverpassFetcher = (query: string, endpoint: string) => Promise<OverpassResponse>;

/**
 * Live fetcher: POSTs an Overpass QL query to the endpoint and parses the JSON.
 * Exported for unit testing the fetch-resilience paths (timeout / status /
 * non-JSON body); production wiring uses it via the ingestion default fetcher.
 */
export const httpFetcher: OverpassFetcher = async (query, endpoint) => {
  const res = await fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
    body: query,
    signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
  });
  if (!res.ok) {
    // Overpass returns the offending line/reason in the body on a 400/429; bound
    // it to 200 chars. The query carries no secret, so it is safe to surface.
    const body = await res.text().catch(() => '');
    throw new Error(`Overpass API responded ${res.status}: ${body.slice(0, 200)}`);
  }
  const text = await res.text();
  try {
    return JSON.parse(text) as OverpassResponse;
  } catch (parseError) {
    // A non-JSON 200 (an HTML rate-limit / maintenance page, a truncated body)
    // is a distinct failure from a bad status — surface a snippet, keep the
    // parse error on `cause`.
    throw new Error(`Overpass API returned non-JSON body: ${text.slice(0, 200)}`, {
      cause: parseError,
    });
  }
};

export interface AreaPoiIngestionResult {
  areaId: string;
  /** Cached in-shape POI count after this run; -1 when the fetch failed (cache kept). */
  poiCount: number;
  /** Raw normalised POIs returned by Overpass (before the in-shape filter). */
  fetched: number;
  /** Stale cached POIs (moved/removed upstream) deleted this run. */
  removedStale: number;
  /** True when the Overpass fetch/parse failed and the previous cache was kept. */
  failed: boolean;
}

/**
 * Runs ONE area's POI ingestion against `now`.
 *
 * Queries Overpass for the area's bounding box, keeps only POIs inside the drawn
 * shape, and reconciles the cache: new/unchanged POIs are upserted at their
 * deterministic id, and cached POIs no longer present upstream are deleted (a
 * diff, not a wipe-and-rewrite, so there is never a window where the area has
 * zero cached POIs mid-refresh). The area document is stamped with the resulting
 * `poiCount` and `poisRefreshedAt`.
 *
 * NEVER throws on an Overpass failure: it logs, leaves the cache and the
 * refresh stamp untouched, and returns `{ failed: true }`. The caller (trigger
 * or scheduled refresh) treats that as a benign skip.
 */
export async function runAreaPoiIngestion(
  areaId: string,
  shape: CrownSpawnAreaShape,
  now: Date,
  fetcher: OverpassFetcher = httpFetcher,
  endpoint: string = OVERPASS_ENDPOINT_DEFAULT,
): Promise<AreaPoiIngestionResult> {
  const query = buildOverpassQuery(shapeBoundingBox(shape), OVERPASS_QUERY_TIMEOUT_SECONDS);

  let normalized: NormalizedPoi[];
  try {
    const response = await fetcher(query, endpoint);
    normalized = parseOverpassResponse(response);
  } catch (error) {
    logger.warn('Crown POI ingestion failed; keeping cached POIs', {
      areaId,
      error: error instanceof Error ? error.message : String(error),
    });
    return { areaId, poiCount: -1, fetched: 0, removedStale: 0, failed: true };
  }

  const inShape = filterPoisInShape(normalized, shape);

  // Deterministic id per coordinate → the new cache set. Bounded, and de-duped by
  // id so two POIs that hash equal collapse to one.
  const newDocs = new Map<string, DocumentData>();
  for (const poi of inShape) {
    if (newDocs.size >= MAX_POIS_PER_AREA) break;
    const id = crownPoiDocId(areaId, poi.lat, poi.lon);
    if (newDocs.has(id)) continue;
    newDocs.set(id, {
      lat: poi.lat,
      lon: poi.lon,
      category: poi.category,
      // Stored so the spawn pass can group POIs by cell without recomputing.
      cellKey: crownCellKey(poi.lat, poi.lon),
      refreshedAt: Timestamp.fromDate(now),
    });
  }

  const poisRef = db.collection(POIS_COLLECTION).doc(areaId).collection('pois');
  const existing = await poisRef.select().get();
  const staleIds = existing.docs.map((d) => d.id).filter((id) => !newDocs.has(id));

  // Upsert the new set + delete the stale, in bounded batches.
  const ops: Array<{ type: 'set' | 'delete'; id: string; data?: DocumentData }> = [];
  for (const [id, data] of newDocs) ops.push({ type: 'set', id, data });
  for (const id of staleIds) ops.push({ type: 'delete', id });
  for (let i = 0; i < ops.length; i += POI_WRITE_BATCH_SIZE) {
    const batch = db.batch();
    for (const op of ops.slice(i, i + POI_WRITE_BATCH_SIZE)) {
      const ref = poisRef.doc(op.id);
      if (op.type === 'set') batch.set(ref, op.data!);
      else batch.delete(ref);
    }
    await batch.commit();
  }

  // Stamp the area with the count + refresh time. UPDATE (not set+merge) so a
  // concurrently-deleted area is NOT resurrected as a partial document; a
  // NOT_FOUND here means the area went away mid-ingestion, which is benign.
  try {
    await db
      .collection(AREAS_COLLECTION)
      .doc(areaId)
      .update({ poiCount: newDocs.size, poisRefreshedAt: Timestamp.fromDate(now) });
  } catch (error) {
    const code = (error as { code?: number | string }).code;
    if (code === 5 || code === 'not-found') {
      logger.info('Crown POI ingestion: area deleted mid-run, count not stamped', { areaId });
    } else {
      throw error;
    }
  }

  logger.info('Crown POI ingestion complete', {
    areaId,
    poiCount: newDocs.size,
    removedStale: staleIds.length,
  });
  return {
    areaId,
    poiCount: newDocs.size,
    fetched: normalized.length,
    removedStale: staleIds.length,
    failed: false,
  };
}

// ---------------------------------------------------------------------------
// Trigger: ingest on area create / activate / reshape
// ---------------------------------------------------------------------------

/**
 * Whether an area write should trigger POI ingestion — pure so the loop guard is
 * unit-testable without the emulator.
 *
 * Ingests only when the area is ACTIVE and safe-confirmed AND something relevant
 * changed: it was just activated (or created active), its shape changed, or it
 * has never been ingested. Crucially this returns FALSE for ingestion's OWN
 * `poiCount`/`poisRefreshedAt` write (active+shape unchanged, refresh stamp now
 * present), so the trigger cannot loop on itself.
 */
export function shouldIngestOnAreaWrite(
  before: DocumentData | undefined,
  after: DocumentData | undefined,
): boolean {
  if (!after) return false;
  if (after.active !== true || after.safeAreaConfirmed !== true) return false;
  if (!after.shape) return false;
  const activatedNow = !before || before.active !== true;
  const shapeChanged = !before || JSON.stringify(before.shape) !== JSON.stringify(after.shape);
  const neverIngested = after.poisRefreshedAt === undefined || after.poisRefreshedAt === null;
  return activatedNow || shapeChanged || neverIngested;
}

export const onSpawnAreaWrittenIngestPois = onDocumentWritten(
  {
    region: 'europe-west1',
    document: 'crownSpawnAreas/{areaId}',
    memory: '256MiB',
    timeoutSeconds: 60,
    maxInstances: MAX_INSTANCES_TRIGGER,
    // Serialize per instance so two writes to the same area cannot ingest in
    // parallel and race the cache diff.
    concurrency: 1,
  },
  withServerErrorReporting('crownHunt.ingestAreaPois', async (event) => {
    const afterSnap = event.data?.after;
    const beforeSnap = event.data?.before;
    const after = afterSnap?.exists ? (afterSnap.data() as DocumentData) : undefined;
    const before = beforeSnap?.exists ? (beforeSnap.data() as DocumentData) : undefined;
    if (!shouldIngestOnAreaWrite(before, after)) return;

    await runAreaPoiIngestion(
      event.params.areaId,
      after!.shape as CrownSpawnAreaShape,
      new Date(),
      httpFetcher,
      resolveOverpassEndpoint(),
    );
  }),
);

// ---------------------------------------------------------------------------
// Scheduled weekly refresh
// ---------------------------------------------------------------------------

export interface AreaPoiRefreshResult {
  areasScanned: number;
  areasRefreshed: number;
  areasFailed: number;
}

/**
 * Refreshes the POI cache for every active, safe-confirmed area, one Overpass
 * query each. Each area is isolated: a failure (Overpass down, or an unexpected
 * write error) is logged and counted, and the loop continues — one bad area can
 * never abort the whole refresh, and a failed fetch keeps that area's last cache.
 */
export async function runAreaPoiRefresh(
  now: Date,
  fetcher: OverpassFetcher = httpFetcher,
  endpoint: string = OVERPASS_ENDPOINT_DEFAULT,
  limits: { maxAreas: number } = { maxAreas: MAX_AREAS_PER_REFRESH },
): Promise<AreaPoiRefreshResult> {
  const result: AreaPoiRefreshResult = { areasScanned: 0, areasRefreshed: 0, areasFailed: 0 };

  const areas = await db
    .collection(AREAS_COLLECTION)
    .where('active', '==', true)
    .limit(Math.max(1, limits.maxAreas))
    .get();

  for (const areaDoc of areas.docs) {
    const data = areaDoc.data();
    if (data.safeAreaConfirmed !== true || !data.shape) continue;
    result.areasScanned += 1;
    try {
      const res = await runAreaPoiIngestion(
        areaDoc.id,
        data.shape as CrownSpawnAreaShape,
        now,
        fetcher,
        endpoint,
      );
      if (res.failed) result.areasFailed += 1;
      else result.areasRefreshed += 1;
    } catch (error) {
      result.areasFailed += 1;
      logger.warn('Crown POI refresh: area errored, continuing', {
        areaId: areaDoc.id,
        error: error instanceof Error ? error.message : String(error),
      });
    }
  }

  logger.info('Crown POI refresh complete', { ...result });
  return result;
}

/** Weekly (Mondays 03:00 Europe/Stockholm) — POIs rarely move, so this is plenty. */
export const refreshAreaPois = onSchedule(
  {
    region: 'europe-west1',
    timeZone: 'Europe/Stockholm',
    memory: '256MiB',
    timeoutSeconds: 300,
    maxInstances: MAX_INSTANCES_SCHEDULED,
    schedule: '0 3 * * 1',
  },
  withServerErrorReporting('crownHunt.refreshAreaPois', async () => {
    await runAreaPoiRefresh(new Date(), httpFetcher, resolveOverpassEndpoint());
  }),
);
