/**
 * incidents-syncTrafikverket — scheduled importer of Swedish roadwork / traffic
 * situations from the Trafikverket open API into the shared incidents layer.
 *
 * GUARDED on the `TRAFIKVERKET_API_KEY` secret: without it the sync no-ops
 * (logs a skip) so the deploy is safe before the free key is provisioned. When
 * present, it POSTs the query (trafikverket-core), maps each deviation to an
 * incident, and upserts it at a deterministic `tv_<id>` doc so re-syncs refresh
 * rather than duplicate. Imported incidents carry `source: 'trafikverket'`,
 * `reporterUid: null`, and a rolling import TTL — a situation that vanishes
 * upstream ages out within one window.
 *
 * runTrafikverketSync takes an injectable fetcher so emulator/unit tests drive
 * it with a mocked response and NEVER hit the live API.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { defineSecret } from 'firebase-functions/params';
import { Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { IMPORT_TTL_MS, buildIncidentFields } from './incidents-core';
import {
  TRAFIKVERKET_ENDPOINT,
  buildTrafikverketRequestBody,
  importedIncidentDocId,
  parseTrafikverketResponse,
  type TrafikverketResponse,
} from './trafikverket-core';
import { MAX_INSTANCES_SCHEDULED } from '../shared/instanceLimits';
import { withServerErrorReporting } from '../errors/serverErrors';

const TRAFIKVERKET_API_KEY = defineSecret('TRAFIKVERKET_API_KEY');

const UPSERT_BATCH_SIZE = 400;

export type SituationFetcher = (authenticationKey: string) => Promise<TrafikverketResponse>;

/** Upper bound on a single Trafikverket API round-trip before we give up and let
 * the scheduled run fail fast (well under the 300s function timeout). Exported
 * so the unit test can assert httpFetcher requests exactly this bound. */
export const FETCH_TIMEOUT_MS = 30_000;

/** Live fetcher: POSTs the XML query to the Trafikverket open API.
 * Exported for unit testing of the fetch-resilience paths (timeout / status /
 * non-JSON body); production wiring uses it via the {@link runTrafikverketSync}
 * default fetcher. */
export const httpFetcher: SituationFetcher = async (authenticationKey) => {
  const res = await fetch(TRAFIKVERKET_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'text/xml' },
    body: buildTrafikverketRequestBody(authenticationKey),
    signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
  });
  if (!res.ok) {
    // Include a snippet of the response body: on a 400 the API names the
    // invalid field/reason (e.g. an unknown INCLUDE path), which is exactly what
    // made issue #678 hard to diagnose from the bare status alone. The body
    // carries no secret — the auth key lives only in the REQUEST body — so it is
    // safe to surface in the server-error report. Bounded to 200 chars.
    const body = await res.text().catch(() => '');
    throw new Error(`Trafikverket API responded ${res.status}: ${body.slice(0, 200)}`);
  }
  const text = await res.text();
  try {
    return JSON.parse(text) as TrafikverketResponse;
  } catch (parseError) {
    // A non-JSON 200 (HTML error/maintenance page, truncated body, …) is a
    // distinct failure mode from a bad status code — surface a snippet so a
    // future occurrence is diagnosable from the error report alone, and keep
    // the underlying parse error on `cause` for the full failure context.
    throw new Error(`Trafikverket API returned non-JSON body: ${text.slice(0, 200)}`, {
      cause: parseError,
    });
  }
};

/**
 * Runs one import pass. Returns { skipped } when no key is configured, else the
 * number of incidents upserted. Deterministic doc ids make re-runs idempotent.
 */
export async function runTrafikverketSync(
  now: Date,
  apiKey: string | undefined,
  fetcher: SituationFetcher = httpFetcher,
): Promise<{ skipped: boolean; upserted: number }> {
  if (!apiKey) {
    logger.info('Trafikverket sync skipped — no TRAFIKVERKET_API_KEY configured.');
    return { skipped: true, upserted: 0 };
  }

  const response = await fetcher(apiKey);
  const imported = parseTrafikverketResponse(response, (code) => {
    logger.info('Trafikverket: unrecognized MessageCodeValue (imported as hazard)', { code });
  });
  const expiresAt = Timestamp.fromDate(new Date(now.getTime() + IMPORT_TTL_MS));
  const createdAt = Timestamp.fromDate(now);

  let upserted = 0;
  for (let i = 0; i < imported.length; i += UPSERT_BATCH_SIZE) {
    const batch = db.batch();
    for (const item of imported.slice(i, i + UPSERT_BATCH_SIZE)) {
      const fields = buildIncidentFields({
        type: item.type,
        latitude: item.latitude,
        longitude: item.longitude,
        source: 'trafikverket',
        reporterUid: null,
        note: item.note,
      });
      const ref = db.collection('incidents').doc(importedIncidentDocId(item.sourceId));
      // Full overwrite (no merge): the tv_ doc is entirely importer-owned —
      // buildIncidentFields re-derives every field and there are no user-written
      // fields to preserve — so each pass rewrites the doc as a faithful mirror
      // of the current upstream situation. createdAt is deliberately re-stamped
      // to now so the record reflects the latest confirmation from upstream, AND
      // stays the load-bearing field for the TTL / lifetime-cap logic that reads
      // it (extendedExpiryFor); DO NOT swap the display time onto it.
      //
      // postedAt is the SEPARATE, authoritative "when Trafikverket posted about
      // it" the app shows as "x min ago". When upstream sent no usable original
      // time we OMIT the field entirely (each pass is a full overwrite, so there
      // is no stale value to clear) — listNearby then sends postedAt: null and
      // the client hides the age line rather than showing our sync time.
      const doc: Record<string, unknown> = { ...fields, createdAt, expiresAt };
      if (item.postedAtMs !== null) {
        doc.postedAt = Timestamp.fromMillis(item.postedAtMs);
      }
      batch.set(ref, doc);
      upserted += 1;
    }
    await batch.commit();
  }

  logger.info('Trafikverket sync complete', { upserted, situations: imported.length });
  return { skipped: false, upserted };
}

/** Every 30 minutes (the open feed refreshes on that order). */
export const syncTrafikverket = onSchedule(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_SCHEDULED,
    timeZone: 'Europe/Stockholm',
    memory: '256MiB' as const,
    timeoutSeconds: 300,
    schedule: '*/30 * * * *',
    secrets: [TRAFIKVERKET_API_KEY],
  },
  withServerErrorReporting('incidents.syncTrafikverket', async () => {
    await runTrafikverketSync(new Date(), TRAFIKVERKET_API_KEY.value() || undefined);
  }),
);
