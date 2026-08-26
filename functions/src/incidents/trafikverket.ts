/**
 * incidents-syncTrafikverket — scheduled importer of Swedish roadwork / traffic
 * situations from the Trafikverket open API into the shared incidents layer.
 *
 * GUARDED on the `TRAFIKVERKET_API_KEY` secret: without it the sync no-ops
 * (logs a skip) so the deploy is safe before the free key is provisioned. When
 * present, it POSTs the query (trafikverket-core), maps each deviation to an
 * incident, and stores it at a deterministic `tv_<id>` doc. A versioned stable
 * content fingerprint prevents unchanged incidents from being rewritten. One
 * small metadata document is the freshness authority; complete responses also
 * reconcile documents that truly vanished upstream.
 *
 * runTrafikverketSync takes an injectable fetcher so emulator/unit tests drive
 * it with a mocked response and NEVER hit the live API.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { defineSecret } from 'firebase-functions/params';
import { FieldPath, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { createHash } from 'node:crypto';
import { db } from '../firebase';
import { IMPORT_TTL_MS, buildIncidentFields } from './incidents-core';
import {
  TRAFIKVERKET_ENDPOINT,
  TRAFIKVERKET_FINGERPRINT_VERSION,
  TRAFIKVERKET_QUERY_LIMIT,
  TRAFIKVERKET_SYNC_METADATA_COLLECTION,
  TRAFIKVERKET_SYNC_METADATA_DOC,
  IMPORT_PERSISTENT_EXPIRES_AT_MS,
  buildTrafikverketRequestBody,
  importedIncidentDocId,
  inspectTrafikverketResponse,
  parseTrafikverketResponse,
  type ImportedIncident,
  type TrafikverketReconciliationSkipReason,
  type TrafikverketResponse,
} from './trafikverket-core';
import { MAX_INSTANCES_SCHEDULED, CPU_SCHEDULED } from '../shared/instanceLimits';
import { withServerErrorReporting } from '../errors/serverErrors';

const TRAFIKVERKET_API_KEY = defineSecret('TRAFIKVERKET_API_KEY');

const UPSERT_BATCH_SIZE = 400;
const READ_PAGE_SIZE = 400;
const DELETE_CONCURRENCY = 10;

/**
 * Bound the importer-owned collection scan. The upstream limit is Situations,
 * while each Situation can contain several Deviations; 5x comfortably covers
 * the measured ratio without permitting an unbounded/corrupt collection walk.
 */
const MAX_EXISTING_IMPORTED_DOCS = TRAFIKVERKET_QUERY_LIMIT * 5;

/**
 * A national response retaining less than half of a meaningful existing set is
 * treated as suspicious even if its JSON shape is valid and below the API cap.
 * Small test/new deployments are exempt so normal single-incident removals can
 * reconcile. This guards e.g. a syntactically valid 100-of-4,600 partial feed.
 */
export const TRAFIKVERKET_DROP_GUARD_MIN_EXISTING = 100;
export const TRAFIKVERKET_DROP_GUARD_MIN_RETAINED_RATIO = 0.5;

export type SituationFetcher = (authenticationKey: string) => Promise<TrafikverketResponse>;

export interface TrafikverketSyncResult {
  skipped: boolean;
  situationsReceived: number;
  deviationsParsed: number;
  created: number;
  changed: number;
  unchangedSkipped: number;
  missingDeleted: number;
  legacyMigrated: number;
  /** Backward-compatible aggregate for older diagnostics/tests. */
  upserted: number;
  reconciliationSkipped: TrafikverketReconciliationSkipReason | null;
}

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
 * Canonical fingerprint of every stable importer-owned field. It deliberately
 * excludes lifecycle/sync timestamps (`createdAt`, `expiresAt`, metadata
 * freshness) so time passing never turns an unchanged incident into a write.
 * The version is stored beside the hash; bumping it forces one safe migration.
 */
export function importedIncidentFingerprint(item: ImportedIncident): string {
  const fields = buildIncidentFields({
    type: item.type,
    latitude: item.latitude,
    longitude: item.longitude,
    source: 'trafikverket',
    reporterUid: null,
    note: item.note,
  });
  const canonical = [
    TRAFIKVERKET_FINGERPRINT_VERSION,
    item.sourceId,
    fields.type,
    fields.latitude,
    fields.longitude,
    fields.geoCell,
    fields.status,
    fields.source,
    fields.reporterUid,
    fields.note,
    item.postedAtMs,
  ];
  return createHash('sha256').update(JSON.stringify(canonical)).digest('base64url');
}

export interface ExistingImportedDocs {
  docs: Map<string, FirebaseFirestore.QueryDocumentSnapshot>;
  overflow: boolean;
}

export interface ExistingImportedDocsReadOptions {
  maxDocs?: number;
  pageSize?: number;
}

const TRAFIKVERKET_DOC_ID_PREFIX = 'tv_';
// "`" immediately follows "_" in Unicode, so this exclusive bound admits
// every document ID beginning with `tv_` and no IDs outside that prefix.
const TRAFIKVERKET_DOC_ID_PREFIX_END = 'tv`';

export async function readExistingImportedDocs(
  options: ExistingImportedDocsReadOptions = {},
): Promise<ExistingImportedDocs> {
  const maxDocs = options.maxDocs ?? MAX_EXISTING_IMPORTED_DOCS;
  const configuredPageSize = options.pageSize ?? READ_PAGE_SIZE;
  const docs = new Map<string, FirebaseFirestore.QueryDocumentSnapshot>();
  let cursor: FirebaseFirestore.QueryDocumentSnapshot | undefined;
  let scanned = 0;

  while (scanned < maxDocs) {
    const pageSize = Math.min(configuredPageSize, maxDocs - scanned);
    let query = db
      .collection('incidents')
      .orderBy(FieldPath.documentId())
      .endBefore(TRAFIKVERKET_DOC_ID_PREFIX_END)
      .limit(pageSize);
    query = cursor ? query.startAfter(cursor) : query.startAt(TRAFIKVERKET_DOC_ID_PREFIX);
    const page = await query.get();
    for (const doc of page.docs) {
      scanned += 1;
      // The deterministic prefix is the index-free ownership boundary, while
      // the stored source check prevents a colliding non-importer document
      // from entering fingerprint comparison or reconciliation/deletion.
      if (doc.data().source === 'trafikverket') docs.set(doc.id, doc);
    }
    if (page.size < pageSize) return { docs, overflow: false };
    cursor = page.docs.at(-1);
  }

  let overflowQuery = db
    .collection('incidents')
    .orderBy(FieldPath.documentId())
    .endBefore(TRAFIKVERKET_DOC_ID_PREFIX_END)
    .limit(1);
  overflowQuery = cursor
    ? overflowQuery.startAfter(cursor)
    : overflowQuery.startAt(TRAFIKVERKET_DOC_ID_PREFIX);
  return { docs, overflow: !(await overflowQuery.get()).empty };
}

async function readIncomingDocs(
  imported: ImportedIncident[],
): Promise<Map<string, FirebaseFirestore.DocumentSnapshot>> {
  const docs = new Map<string, FirebaseFirestore.DocumentSnapshot>();
  const refs = imported.map((item) =>
    db.collection('incidents').doc(importedIncidentDocId(item.sourceId)),
  );
  for (let i = 0; i < refs.length; i += READ_PAGE_SIZE) {
    const page = await db.getAll(...refs.slice(i, i + READ_PAGE_SIZE));
    for (const doc of page) docs.set(doc.id, doc);
  }
  return docs;
}

async function recursivelyDeleteBounded(
  refs: FirebaseFirestore.DocumentReference[],
): Promise<void> {
  for (let i = 0; i < refs.length; i += DELETE_CONCURRENCY) {
    await Promise.all(refs.slice(i, i + DELETE_CONCURRENCY).map((ref) => db.recursiveDelete(ref)));
  }
}

export function isImplausibleUpstreamDrop(
  existingImportedCount: number,
  upstreamIdCount: number,
): boolean {
  return (
    existingImportedCount >= TRAFIKVERKET_DROP_GUARD_MIN_EXISTING &&
    upstreamIdCount < Math.ceil(existingImportedCount * TRAFIKVERKET_DROP_GUARD_MIN_RETAINED_RATIO)
  );
}

function zeroResult(skipped: boolean): TrafikverketSyncResult {
  return {
    skipped,
    situationsReceived: 0,
    deviationsParsed: 0,
    created: 0,
    changed: 0,
    unchangedSkipped: 0,
    missingDeleted: 0,
    legacyMigrated: 0,
    upserted: 0,
    reconciliationSkipped: null,
  };
}

/** Runs one bounded import/reconciliation pass. */
export async function runTrafikverketSync(
  now: Date,
  apiKey: string | undefined,
  fetcher: SituationFetcher = httpFetcher,
): Promise<TrafikverketSyncResult> {
  if (!apiKey) {
    logger.info('Trafikverket sync skipped — no TRAFIKVERKET_API_KEY configured.');
    return zeroResult(true);
  }

  const response = await fetcher(apiKey);
  const inspection = inspectTrafikverketResponse(response);
  if (!inspection.structurallyValid || inspection.reconciliationSkipReason === 'empty-response') {
    logger.error('Trafikverket sync rejected an invalid/incomplete response', {
      situationsReceived: inspection.situationsReceived,
      deviationsReceived: inspection.deviationsReceived,
      reconciliationSkipped: inspection.reconciliationSkipReason,
    });
    // No incident or metadata writes: the previous freshness window remains the
    // authority and eventually suppresses stale imported data.
    throw new Error(
      `Trafikverket response is not safe to import (${inspection.reconciliationSkipReason})`,
    );
  }

  const imported = parseTrafikverketResponse(response, (code) => {
    logger.info('Trafikverket: unrecognized MessageCodeValue (imported as hazard)', { code });
  });
  let reconciliationSkipped = inspection.reconciliationSkipReason;

  // Complete responses need one bounded collection scan both to compare
  // fingerprints and to identify vanished docs. Capped responses may not
  // reconcile, so they read only the incoming deterministic ids.
  let existing: Map<string, FirebaseFirestore.DocumentSnapshot>;
  if (reconciliationSkipped === null) {
    const scanned = await readExistingImportedDocs();
    existing = scanned.docs;
    if (scanned.overflow) {
      reconciliationSkipped = 'existing-scan-limit-reached';
      const missingIncoming = imported.filter(
        (item) => !existing.has(importedIncidentDocId(item.sourceId)),
      );
      const additional = await readIncomingDocs(missingIncoming);
      for (const [id, doc] of additional) existing.set(id, doc);
    } else if (isImplausibleUpstreamDrop(existing.size, inspection.upstreamIncidentDocIds.size)) {
      reconciliationSkipped = 'implausible-upstream-drop';
      logger.warn('Trafikverket reconciliation withheld after implausible upstream drop', {
        existingImported: existing.size,
        upstreamIdsReceived: inspection.upstreamIncidentDocIds.size,
        minimumRetainedRatio: TRAFIKVERKET_DROP_GUARD_MIN_RETAINED_RATIO,
      });
    }
  } else {
    existing = await readIncomingDocs(imported);
  }

  const persistentExpiresAt = Timestamp.fromMillis(IMPORT_PERSISTENT_EXPIRES_AT_MS);
  let created = 0;
  let changed = 0;
  let unchangedSkipped = 0;
  let legacyMigrated = 0;
  const writes: Array<{
    ref: FirebaseFirestore.DocumentReference;
    data: Record<string, unknown>;
  }> = [];

  for (const item of imported) {
    const id = importedIncidentDocId(item.sourceId);
    const ref = db.collection('incidents').doc(id);
    const previous = existing.get(id);
    const previousData = previous?.data();
    const fingerprint = importedIncidentFingerprint(item);
    const previousExpiry = previousData?.expiresAt;
    const current =
      previous?.exists === true &&
      previousData?.importFingerprintVersion === TRAFIKVERKET_FINGERPRINT_VERSION &&
      previousData?.importFingerprint === fingerprint &&
      previousData?.sourceId === item.sourceId &&
      previousExpiry instanceof Timestamp &&
      previousExpiry.toMillis() === IMPORT_PERSISTENT_EXPIRES_AT_MS;
    if (current) {
      unchangedSkipped += 1;
      continue;
    }

    const fields = buildIncidentFields({
      type: item.type,
      latitude: item.latitude,
      longitude: item.longitude,
      source: 'trafikverket',
      reporterUid: null,
      note: item.note,
    });
    const previousCreatedAt = previousData?.createdAt;
    const createdAt =
      previousCreatedAt instanceof Timestamp
        ? previousCreatedAt
        : Timestamp.fromMillis(item.postedAtMs ?? now.getTime());
    const data: Record<string, unknown> = {
      ...fields,
      sourceId: item.sourceId,
      createdAt,
      expiresAt: persistentExpiresAt,
      importFingerprintVersion: TRAFIKVERKET_FINGERPRINT_VERSION,
      importFingerprint: fingerprint,
    };
    if (item.postedAtMs !== null) data.postedAt = Timestamp.fromMillis(item.postedAtMs);
    writes.push({ ref, data });

    if (previous?.exists) {
      changed += 1;
      if (previousData?.importFingerprintVersion !== TRAFIKVERKET_FINGERPRINT_VERSION) {
        legacyMigrated += 1;
      }
    } else {
      created += 1;
    }
  }

  for (let i = 0; i < writes.length; i += UPSERT_BATCH_SIZE) {
    const batch = db.batch();
    for (const write of writes.slice(i, i + UPSERT_BATCH_SIZE)) batch.set(write.ref, write.data);
    await batch.commit();
  }

  let missingDeleted = 0;
  if (reconciliationSkipped === null) {
    const missing = [...existing.values()]
      .filter((doc) => !inspection.upstreamIncidentDocIds.has(doc.id))
      .map((doc) => doc.ref);
    await recursivelyDeleteBounded(missing);
    missingDeleted = missing.length;
  }

  const result: TrafikverketSyncResult = {
    skipped: false,
    situationsReceived: inspection.situationsReceived,
    deviationsParsed: imported.length,
    created,
    changed,
    unchangedSkipped,
    missingDeleted,
    legacyMigrated,
    upserted: created + changed,
    reconciliationSkipped,
  };

  // LAST write of a successful run. If persistence/reconciliation fails, this
  // never advances and both read paths eventually suppress the stale imports.
  const metadata: Record<string, unknown> = {
    source: 'trafikverket',
    lastSuccessfulAt: Timestamp.fromDate(now),
    freshUntil: Timestamp.fromMillis(now.getTime() + IMPORT_TTL_MS),
    fingerprintVersion: TRAFIKVERKET_FINGERPRINT_VERSION,
    responseComplete: reconciliationSkipped === null,
    lastRun: result,
  };
  if (reconciliationSkipped === null) metadata.lastReconciledAt = Timestamp.fromDate(now);
  await db
    .collection(TRAFIKVERKET_SYNC_METADATA_COLLECTION)
    .doc(TRAFIKVERKET_SYNC_METADATA_DOC)
    .set(metadata, { merge: true });

  logger.info('Trafikverket sync complete', result);
  return result;
}

/** Every 30 minutes (the open feed refreshes on that order). */
export const syncTrafikverket = onSchedule(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_SCHEDULED,
    cpu: CPU_SCHEDULED,
    concurrency: 1,
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
