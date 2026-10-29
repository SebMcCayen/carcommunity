/** Bounded, fresh-admin-authorized history reads. No claim telemetry is returned. */
import { FieldPath, type Query } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import { onCall } from '../access/callable';
import { z } from 'zod';
import { db } from '../firebase';
import { requireAdminActor } from './actorContext';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';
import { CROWN_HUNT_CLAIM_RESULTS } from '../crownHunt/crownhunt-core';
import { CROWN_SPAWN_CLAIM_RESULTS } from '../crownHunt/crown-spawn-core';
import {
  documentPosition,
  historyCursor,
  nextHistoryCursor,
  pageInput,
  timestamp,
  type HistoryCursor,
} from './history-pagination';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};
const pointsInput = z
  .object({ ...pageInput, status: z.enum(['draft', 'active', 'paused', 'ended']).optional() })
  .strict();
const claimsInput = z
  .object({
    ...pageInput,
    source: z.enum(['all', 'hand_placed', 'automatic']).default('all'),
    result: z.enum([...CROWN_HUNT_CLAIM_RESULTS, ...CROWN_SPAWN_CLAIM_RESULTS]).optional(),
  })
  .strict();
const ledgerInput = z
  .object({
    ...pageInput,
    targetUid: z
      .string()
      .trim()
      .min(1)
      .max(128)
      .refine((id) => !id.includes('/') && id !== '.' && id !== '..'),
  })
  .strict();
function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data ?? {});
  if (!result.success) throw new HttpsError('invalid-argument', 'Invalid history request.');
  return result.data;
}
function basePage(query: Query, field: string, cursor: HistoryCursor): Query {
  return query
    .where(field, '<=', timestamp(cursor.asOf))
    .orderBy(field, 'desc')
    .orderBy(FieldPath.documentId(), 'desc');
}
async function readPage(
  query: Query,
  cursor: HistoryCursor,
  size: number,
  source: 'first' | 'second' = 'first',
) {
  const after = cursor.after[source];
  const next = after ? query.startAfter(timestamp(after), after.id) : query;
  const [snapshot, count] = await Promise.all([next.limit(size + 1).get(), query.count().get()]);
  return { docs: snapshot.docs, total: count.data().count };
}
function meta(cursor: HistoryCursor, size: number, total: number, hasNext: boolean) {
  return {
    page: cursor.page,
    pageSize: size,
    total,
    hasNext,
    nextCursor: hasNext ? nextHistoryCursor(cursor) : null,
    asOf: timestamp(cursor.asOf).toDate().toISOString(),
  };
}
function iso(value: unknown): string | null {
  if (typeof value === 'string' || value instanceof Date) {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? null : date.toISOString();
  }
  return value && typeof (value as { toDate?: unknown }).toDate === 'function'
    ? (value as FirebaseFirestore.Timestamp).toDate().toISOString()
    : null;
}
function numberOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

export const listCrownPoints = onCall(CALLABLE_OPTS, async (request) => {
  await requireAdminActor(request);
  const input = parse(pointsInput, request.data);
  const cursor = historyCursor(input, ['crownPoints', input.status ?? null]);
  let query: Query = db.collection('crownHuntPoints');
  if (input.status) query = query.where('status', '==', input.status);
  const page = await readPage(basePage(query, 'createdAt', cursor), cursor, input.pageSize);
  const docs = page.docs.slice(0, input.pageSize);
  if (docs.length) cursor.after.first = documentPosition(docs[docs.length - 1]!, 'createdAt');
  const points = docs.map((doc) => {
    const d = doc.data();
    return {
      pointId: doc.id,
      title: d.title ?? '',
      description: d.description ?? null,
      latitude: d.latitude ?? 0,
      longitude: d.longitude ?? 0,
      geofenceRadiusMeters: d.geofenceRadiusMeters ?? 0,
      rewardPoints: d.rewardPoints ?? 0,
      status: d.status ?? 'draft',
      repeatRule: d.repeatRule ?? 'once',
      availableFrom: iso(d.availableFrom),
      availableUntil: iso(d.availableUntil),
      approvedAt: iso(d.approvedAt),
      approvedByUserId: d.approvedByUserId ?? null,
      createdByUserId: d.createdByUserId ?? '',
      createdAt: iso(d.createdAt),
      updatedAt: iso(d.updatedAt),
      totalClaims: d.totalClaims ?? 0,
      maxCollectors: d.maxCollectors ?? null,
      collectorCount: d.collectorCount ?? 0,
    };
  });
  return {
    ok: true,
    data: { points },
    meta: meta(cursor, input.pageSize, page.total, page.docs.length > input.pageSize),
  };
});

export const listCrownClaims = onCall(CALLABLE_OPTS, async (request) => {
  await requireAdminActor(request);
  const input = parse(claimsInput, request.data);
  const cursor = historyCursor(input, ['crownClaims', input.source, input.result ?? null]);
  const sources = (
    [
      ['first', 'hand_placed', 'crownHuntClaims'],
      ['second', 'automatic', 'crownSpawnClaims'],
    ] as const
  ).filter(([, source]) => input.source === 'all' || source === input.source);
  const pages = await Promise.all(
    sources.map(async ([slot, source, collection]) => {
      let query: Query = db.collection(collection);
      if (input.result) query = query.where('result', '==', input.result);
      const page = await readPage(
        basePage(query, 'claimedAt', cursor).select(
          'claimedAt',
          'result',
          'pointId',
          'spawnId',
          'userId',
          'rarity',
          'pointsAwarded',
        ),
        cursor,
        input.pageSize,
        slot,
      );
      return { ...page, slot, source };
    }),
  );
  const rows = pages.flatMap((page) =>
    page.docs.map((doc) => ({
      doc,
      slot: page.slot,
      source: page.source,
      position: documentPosition(doc, 'claimedAt'),
    })),
  );
  rows.sort(
    (a, b) =>
      b.position.seconds - a.position.seconds ||
      b.position.nanoseconds - a.position.nanoseconds ||
      a.source.localeCompare(b.source) ||
      (a.position.id === b.position.id ? 0 : a.position.id > b.position.id ? -1 : 1),
  );
  const selected = rows.slice(0, input.pageSize);
  const claims = await Promise.all(
    selected.map(async ({ doc, source }) => {
      const d = doc.data();
      const pointId = String((source === 'automatic' ? d.spawnId : d.pointId) ?? '');
      // Only a safe title lookup; raw risk/position collections are never joined.
      let pointTitle = '';
      if (source === 'hand_placed' && pointId && !pointId.includes('/')) {
        const point = await db.collection('crownHuntPoints').doc(pointId).get();
        pointTitle = String(point.get('title') ?? '');
      }
      return {
        claimId: `${source}:${doc.id}`,
        pointId,
        pointTitle,
        userId: String(d.userId ?? ''),
        source,
        result: String(d.result),
        pointsAwarded: numberOrNull(d.pointsAwarded),
        rarity: d.rarity ?? null,
        claimedAt: iso(d.claimedAt),
        distanceMeters: null,
        riskReasonCategories: [],
      };
    }),
  );
  // Promise completion order must not determine a source frontier.
  for (const row of selected) cursor.after[row.slot] = row.position;
  return {
    ok: true,
    data: { claims },
    meta: meta(
      cursor,
      input.pageSize,
      pages.reduce((sum, page) => sum + page.total, 0),
      rows.length > input.pageSize,
    ),
  };
});

export const listPointsLedger = onCall(CALLABLE_OPTS, async (request) => {
  await requireAdminActor(request);
  const input = parse(ledgerInput, request.data);
  const cursor = historyCursor(input, ['pointsLedger', input.targetUid]);
  const wallet = db.collection('pointsLedger').doc(input.targetUid);
  const [balance, page] = await Promise.all([
    wallet.get(),
    readPage(basePage(wallet.collection('entries'), 'createdAt', cursor), cursor, input.pageSize),
  ]);
  const docs = page.docs.slice(0, input.pageSize);
  if (docs.length) cursor.after.first = documentPosition(docs[docs.length - 1]!, 'createdAt');
  const transactions = docs.map((doc) => {
    const d = doc.data();
    return {
      transactionId: doc.id,
      transactionType: d.transactionType,
      source: d.source,
      amount: d.amount,
      balanceAfter: d.balanceAfter,
      description: d.description,
      createdAt: iso(d.createdAt),
      relatedEntityType: d.relatedEntityType ?? null,
      relatedEntityId: d.relatedEntityId ?? null,
    };
  });
  return {
    ok: true,
    data: { balance: balance.data()?.balance ?? 0, transactions },
    meta: meta(cursor, input.pageSize, page.total, page.docs.length > input.pageSize),
  };
});
