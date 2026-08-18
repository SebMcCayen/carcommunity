/**
 * crownHunt.reingestSpawnAreaPois — admin ON-DEMAND re-run of a marked area's
 * OpenStreetMap safe-stop POI ingestion.
 *
 * The POI cache for an area is otherwise only (re)built by
 * {@link onSpawnAreaWrittenIngestPois} (on create / activate / reshape) and the
 * WEEKLY {@link refreshAreaPois} schedule (Mondays 03:00). When Overpass is
 * momentarily down — a 504 / timeout during the trigger's one attempt — the
 * ingestion fails safe (the last cache, possibly empty, is kept) and an admin
 * would otherwise have to wait out the week or deactivate+reactivate the area
 * just to force a retry. This callable is that retry button: it re-runs the SAME
 * {@link runAreaPoiIngestion} for one area on demand.
 *
 * It REUSES the ingestion function verbatim (no second Overpass fetcher) and
 * therefore inherits its safety contract: an Overpass failure NEVER throws — the
 * previous cache and `poisRefreshedAt` stamp are left untouched. Rather than let
 * that surface as an opaque 500, this callable turns a failed run into a
 * STRUCTURED result (`ok: false` + a message) so the admin UI can say "Overpass
 * timed out, the old stops were kept — try again" instead of a bare error.
 *
 * Admin-gated (`requireAdminActor`), App-Check-enforced, region europe-west1 —
 * the same envelope as the area CRUD in spawnAreas.ts — and it writes (the POI
 * cache + the area's poiCount), so it leaves an `adminAuditEvents` record.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions';
import { FieldValue, type DocumentData } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';
import { crownSpawnAreaShapeSchema, type CrownSpawnAreaShape } from './crown-area-core';
import { httpFetcher, resolveOverpassEndpoint, runAreaPoiIngestion } from './poiIngestion';
import { toReingestResponse, type ReingestSpawnAreaPoisResponse } from './reingest-area-pois-core';

const AREAS_COLLECTION = 'crownSpawnAreas';
const AREA_ID_RE = /^[A-Za-z0-9_-]+$/;

/**
 * The Overpass fetch can genuinely take tens of seconds; give the callable a
 * headroom above the ingestion's own {@link FETCH_TIMEOUT_MS} (30 s) so a slow —
 * but ultimately successful — response is not cut off by the function timeout.
 */
const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 60,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export const reingestSpawnAreaPois = onCall(
  CALLABLE_OPTS,
  async (request): Promise<ReingestSpawnAreaPoisResponse> => {
    const actor = await requireAdminActor(request);

    const raw = (request.data ?? {}) as { areaId?: unknown };
    const areaId = typeof raw.areaId === 'string' ? raw.areaId.trim() : '';
    if (!areaId || areaId.length > 64 || !AREA_ID_RE.test(areaId)) {
      throw new HttpsError('invalid-argument', 'A valid areaId is required.');
    }

    const ref = db.collection(AREAS_COLLECTION).doc(areaId);
    const snap = await ref.get();
    if (!snap.exists) throw new HttpsError('not-found', 'Marked area not found.');
    const areaData = snap.data() as DocumentData;

    // Structurally validate the STORED shape against the same schema the CRUD
    // uses, not just `type`. A console-corrupted doc (e.g. a circle missing
    // center/radius) would otherwise make `runAreaPoiIngestion` → shapeBoundingBox
    // throw and surface as an opaque `internal` 500; instead reject it cleanly.
    const parsedShape = crownSpawnAreaShapeSchema.safeParse(areaData.shape);
    if (!parsedShape.success) {
      throw new HttpsError(
        'failed-precondition',
        'This area has no valid drawn shape to ingest POIs for.',
      );
    }
    const shape = parsedShape.data as CrownSpawnAreaShape;

    // The POI count currently cached, so a FAILED run can report the count that
    // was KEPT (runAreaPoiIngestion returns -1 on failure by design). Guard
    // against a corrupted non-finite stored value (still `typeof === 'number'`)
    // so it can never flow into the response and JSON-serialize to null.
    const previousPoiCount =
      typeof areaData.poiCount === 'number' && Number.isFinite(areaData.poiCount)
        ? Math.max(0, Math.trunc(areaData.poiCount))
        : 0;

    // REUSE the ingestion verbatim. It never throws on an Overpass failure; it
    // returns { failed: true, poiCount: -1 } and keeps the previous cache. Turn
    // that into a STRUCTURED response (ok:false + message), never an opaque 500.
    const result = await runAreaPoiIngestion(
      areaId,
      shape,
      new Date(),
      httpFetcher,
      resolveOverpassEndpoint(),
    );
    const response = toReingestResponse(areaId, previousPoiCount, result);

    // Surface the outcome to Cloud Logging. The failed run is otherwise SILENT
    // in logs — runAreaPoiIngestion never throws on an Overpass outage, it just
    // returns ok:false and the old cache is kept — so without this an operator
    // re-runs the retry button and cannot tell it ran or why it did nothing.
    // areaId is an admin-defined area identifier, not user PII.
    if (response.ok) {
      logger.info('crownHunt.reingestSpawnAreaPois complete', {
        areaId,
        poiCount: response.poiCount,
        fetched: response.fetched,
        removedStale: response.removedStale,
      });
    } else {
      logger.warn(
        'crownHunt.reingestSpawnAreaPois: Overpass ingestion failed, previous POI cache kept',
        { areaId, previousPoiCount },
      );
    }

    await db
      .collection('adminAuditEvents')
      .doc()
      .set(
        buildAdminAuditEvent(
          {
            adminId: actor.uid,
            action: 'crownHunt.reingestSpawnAreaPois',
            targetType: 'crownSpawnArea',
            targetId: areaId,
            reason: response.ok
              ? `On-demand POI re-ingestion: ${response.poiCount} safe stops cached.`
              : 'On-demand POI re-ingestion attempted; Overpass failed, cache kept.',
            details: {
              ok: response.ok,
              poiCount: response.poiCount,
              fetched: response.fetched,
              removedStale: response.removedStale,
            },
          },
          () => FieldValue.serverTimestamp(),
        ),
      );

    return response;
  },
);
