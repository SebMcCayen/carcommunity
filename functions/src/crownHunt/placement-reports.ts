import { createHash } from 'node:crypto';
import { FieldPath, FieldValue, Timestamp, type DocumentData } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import { z } from 'zod';
import { onCall } from '../access/callable';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { db } from '../firebase';
import { isRestricted, toUserAccessState } from '../shared/access';
import { CPU_ADMIN, MAX_INSTANCES_ADMIN, MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import { isPointInShape, shapeBoundingBox, type CrownSpawnAreaShape } from './crown-area-core';
import { crownCellKey, type CrownPosition } from './crown-spawn-core';
import { MAX_POIS_PER_AREA } from './poiIngestion';
import {
  automaticPlacementMoves,
  distanceBetween,
  isPlacementBlocked,
  manualPlacementMoves,
  placementBlocks,
  readPlacementBlocks,
} from './placement-memory';

const REPORT_REASONS = ['unsafe', 'hard_to_reach', 'private_or_closed', 'other'] as const;
const SOURCES = ['automatic', 'hand_placed'] as const;
type Source = (typeof SOURCES)[number];
type Reason = (typeof REPORT_REASONS)[number];
type Decision = 'ignore' | 'move' | 'remove';
type Case = CrownPosition & {
  crownId: string;
  source: Source;
  areaId: string | null;
  areaName: string | null;
  rarity: string | null;
  reportCount: number;
  reasons: Partial<Record<Reason, number>>;
  version: number;
  round: number;
  decision: Decision | null;
  replacement?: CrownPosition;
  lastReportedAt: Timestamp;
};

const CASES = db.collection('crownPlacementCases');
const id = z
  .string()
  .min(1)
  .max(160)
  .regex(/^[\w-]+$/);
const position = z.object({
  latitude: z.number().min(-90).max(90),
  longitude: z.number().min(-180).max(180),
});
const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};
const CALLABLE_OPTS_ADMIN = {
  ...CALLABLE_OPTS,
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
};
const reportInput = position
  .extend({
    source: z.enum(SOURCES),
    crownId: id,
    reason: z.enum(REPORT_REASONS),
    comment: z.string().trim().max(400).optional(),
  })
  .strict();
const caseInput = z.object({ caseId: id }).strict();
const cursorInput = z.object({
  id,
  seconds: z.number().int().nonnegative(),
  nanos: z.number().int().min(0).max(999_999_999),
});
const listInput = z
  .object({ closed: z.boolean().default(false), cursor: cursorInput.optional() })
  .strict();
const resolution = { caseId: id, version: z.number().int().positive() };
const resolveInput = z.discriminatedUnion('action', [
  z.object({ ...resolution, action: z.literal('ignore') }).strict(),
  z.object({ ...resolution, action: z.literal('remove') }).strict(),
  z
    .object({
      ...resolution,
      action: z.literal('move'),
      destination: z.union([
        z.object({ poiId: id }).strict(),
        position.extend({ safeLocationConfirmed: z.literal(true) }).strict(),
      ]),
    })
    .strict(),
]);

function parse<T>(schema: z.ZodType<T>, input: unknown): T {
  const parsed = schema.safeParse(input);
  if (!parsed.success) throw new HttpsError('invalid-argument', 'Invalid crown placement request.');
  return parsed.data;
}

function key(...parts: (string | number)[]) {
  return createHash('sha256').update(JSON.stringify(parts)).digest('hex');
}

function crownRef(source: Source, crownId: string) {
  return db.collection(source === 'automatic' ? 'crownSpawns' : 'crownHuntPoints').doc(crownId);
}

function samePosition(a: CrownPosition, b: DocumentData | undefined) {
  return a.latitude === b?.latitude && a.longitude === b?.longitude;
}

function active(source: Source, data: DocumentData | undefined, now: number) {
  if (source === 'automatic') {
    return (
      data?.status === 'live' &&
      data.expiresAt instanceof Timestamp &&
      data.expiresAt.toMillis() > now
    );
  }
  return (
    data?.status === 'active' &&
    (!(data.availableFrom instanceof Timestamp) || data.availableFrom.toMillis() <= now) &&
    (!(data.availableUntil instanceof Timestamp) || data.availableUntil.toMillis() > now)
  );
}

function summary(caseId: string, data: Case) {
  return {
    caseId,
    crownId: data.crownId,
    source: data.source,
    areaId: data.areaId,
    areaName: data.areaName,
    rarity: data.rarity,
    latitude: data.latitude,
    longitude: data.longitude,
    reportCount: data.reportCount,
    reasons: data.reasons,
    version: data.version,
    decision: data.decision,
    replacement: data.replacement ?? null,
    lastReportedAt: data.lastReportedAt.toDate().toISOString(),
  };
}

export const reportPlacement = onCall(CALLABLE_OPTS, async (request) => {
  if (!request.auth)
    throw new HttpsError('unauthenticated', 'Sign in to report a crown placement.');
  const uid = request.auth.uid;
  const input = parse(reportInput, request.data);
  const ref = CASES.doc(key(input.source, input.crownId, input.latitude, input.longitude));
  const accountKey = key(uid);
  const reporter = ref.collection('reporters').doc(accountKey);
  const limitRef = db.collection('crownPlacementReportLimits').doc(accountKey);
  const userRef = db.collection('users').doc(uid);

  return db.runTransaction(async (tx) => {
    const user = await tx.get(userRef);
    if (
      !user.exists ||
      request.auth?.token.suspended === true ||
      isRestricted(toUserAccessState(user.data()))
    ) {
      throw new HttpsError(
        'permission-denied',
        'Crown placement reports require an active account.',
      );
    }
    if ((await tx.get(reporter)).exists) return { reported: true };

    const [crown, existing, limit] = await Promise.all([
      tx.get(crownRef(input.source, input.crownId)),
      tx.get(ref),
      tx.get(limitRef),
    ]);
    const data = crown.data();
    const current = existing.data() as Case | undefined;
    const now = Date.now();
    if (
      !data ||
      !samePosition(input, data) ||
      !active(input.source, data, now) ||
      current?.decision === 'move' ||
      current?.decision === 'remove'
    ) {
      throw new HttpsError('failed-precondition', 'This crown placement is no longer available.');
    }

    const day = Math.floor(now / 86_400_000);
    const used = limit.get('day') === day ? Number(limit.get('used')) : 0;
    if (used >= 20)
      throw new HttpsError('resource-exhausted', 'Daily crown placement report limit reached.');

    const areaId = typeof data.areaId === 'string' ? data.areaId : null;
    const areaName =
      areaId && !current
        ? (await tx.get(db.collection('crownSpawnAreas').doc(areaId))).get('name')
        : current?.areaName;
    const open = current && current.decision === null ? current : null;
    const round = open?.round ?? (current?.round ?? 0) + 1;
    const reasons = { ...open?.reasons };
    reasons[input.reason] = (reasons[input.reason] ?? 0) + 1;
    const stamp = FieldValue.serverTimestamp();
    tx.set(ref, {
      crownId: input.crownId,
      source: input.source,
      latitude: data.latitude,
      longitude: data.longitude,
      areaId,
      areaName: typeof areaName === 'string' ? areaName : null,
      rarity: typeof data.rarity === 'string' ? data.rarity : null,
      decision: null,
      reportCount: (open?.reportCount ?? 0) + 1,
      reasons,
      round,
      version: (current?.version ?? 0) + 1,
      lastReportedAt: stamp,
      openAt: stamp,
    });
    tx.create(reporter, { round });
    tx.create(ref.collection('rounds').doc(String(round)).collection('reports').doc(), {
      reason: input.reason,
      comment: input.comment || null,
      createdAt: stamp,
    });
    tx.set(limitRef, { day, used: used + 1 });
    return { reported: true };
  });
});

export const listPlacementCases = onCall(CALLABLE_OPTS_ADMIN, async (request) => {
  await requireAdminActor(request);
  const { closed, cursor } = parse(listInput, request.data ?? {});
  const field = closed ? 'closedAt' : 'openAt';
  const all = CASES.orderBy(field, 'desc').orderBy(FieldPath.documentId(), 'desc');
  const page = cursor
    ? all.startAfter(new Timestamp(cursor.seconds, cursor.nanos), cursor.id)
    : all;
  const [snap, count] = await Promise.all([
    page.limit(26).get(),
    CASES.orderBy('openAt').count().get(),
  ]);
  const docs = snap.docs.slice(0, 25);
  const last = docs.at(-1);
  const stamp = last?.get(field) as Timestamp | undefined;
  return {
    cases: docs.map((doc) => summary(doc.id, doc.data() as Case)),
    openCount: count.data().count,
    nextCursor:
      snap.size > 25 && last && stamp
        ? { id: last.id, seconds: stamp.seconds, nanos: stamp.nanoseconds }
        : null,
  };
});

export const getPlacementCase = onCall(CALLABLE_OPTS_ADMIN, async (request) => {
  await requireAdminActor(request);
  const { caseId } = parse(caseInput, request.data);
  const ref = CASES.doc(caseId);
  const snap = await ref.get();
  if (!snap.exists) throw new HttpsError('not-found', 'Crown placement case not found.');
  const data = snap.data() as Case;
  const [crown, area, reports] = await Promise.all([
    crownRef(data.source, data.crownId).get(),
    data.areaId ? db.collection('crownSpawnAreas').doc(data.areaId).get() : null,
    ref
      .collection('rounds')
      .doc(String(data.round))
      .collection('reports')
      .orderBy('createdAt', 'desc')
      .limit(30)
      .get(),
  ]);
  const matches = samePosition(data, crown.data());
  return {
    ...summary(caseId, data),
    areaName: area?.get('name') ?? data.areaName,
    active: matches && active(data.source, crown.data(), Date.now()),
    canMove:
      data.source === 'automatic'
        ? area?.get('active') === true && area.get('safeAreaConfirmed') === true
        : matches && crown.get('status') !== 'ended',
    reports: reports.docs.map((doc) => ({
      id: doc.id,
      reason: doc.get('reason'),
      comment: doc.get('comment'),
      createdAt: (doc.get('createdAt') as Timestamp).toDate().toISOString(),
    })),
  };
});

export const listPlacementDestinations = onCall(CALLABLE_OPTS_ADMIN, async (request) => {
  await requireAdminActor(request);
  const { caseId } = parse(caseInput, request.data);
  const snap = await CASES.doc(caseId).get();
  const data = snap.data() as Case | undefined;
  if (!data || data.source !== 'automatic' || !data.areaId || data.decision) {
    throw new HttpsError('failed-precondition', 'No automatic placement to move.');
  }
  const area = await db.collection('crownSpawnAreas').doc(data.areaId).get();
  const shape = area.get('shape') as CrownSpawnAreaShape | undefined;
  if (!shape || area.get('active') !== true || area.get('safeAreaConfirmed') !== true)
    return { destinations: [] };
  const { south, north } = shapeBoundingBox(shape);
  const [pois, blocks] = await Promise.all([
    db
      .collection('crownSpawnAreaPois')
      .doc(data.areaId)
      .collection('pois')
      .select('lat', 'lon', 'category')
      .limit(MAX_POIS_PER_AREA)
      .get(),
    readPlacementBlocks(south, north),
  ]);
  const excluded = [...blocks, data];
  return {
    destinations: pois.docs
      .flatMap((doc) => {
        const candidate = position.safeParse({
          latitude: doc.get('lat'),
          longitude: doc.get('lon'),
        });
        if (!candidate.success) return [];
        const point = candidate.data;
        if (
          !isPointInShape(point.latitude, point.longitude, shape) ||
          isPlacementBlocked(point, excluded)
        )
          return [];
        return [
          {
            ...point,
            poiId: doc.id,
            category: doc.get('category'),
            distanceMeters: Math.round(distanceBetween(point, data)),
          },
        ];
      })
      .sort((a, b) => a.distanceMeters - b.distanceMeters)
      .slice(0, 30),
  };
});

export const resolvePlacementCase = onCall(CALLABLE_OPTS_ADMIN, async (request) => {
  const actor = await requireAdminActor(request);
  const input = parse(resolveInput, request.data);
  const ref = CASES.doc(input.caseId);

  return db.runTransaction(async (tx) => {
    const data = (await tx.get(ref)).data() as Case | undefined;
    if (!data) throw new HttpsError('not-found', 'Crown placement case not found.');
    if (data.decision === input.action && data.version === input.version) return { resolved: true };
    if (data.decision || data.version !== input.version) {
      throw new HttpsError('failed-precondition', 'Crown placement case has changed. Reload it.');
    }

    const crown = crownRef(data.source, data.crownId);
    const current = input.action === 'ignore' ? null : await tx.get(crown);
    const matches = samePosition(data, current?.data());
    const stamp = FieldValue.serverTimestamp();
    let replacement: CrownPosition | undefined;
    if (input.action === 'move') {
      if (data.source === 'automatic' && data.areaId && 'poiId' in input.destination) {
        const poiId = input.destination.poiId;
        const [area, poi] = await Promise.all([
          tx.get(db.collection('crownSpawnAreas').doc(data.areaId)),
          tx.get(
            db.collection('crownSpawnAreaPois').doc(data.areaId).collection('pois').doc(poiId),
          ),
        ]);
        const shape = area.get('shape') as CrownSpawnAreaShape | undefined;
        const candidate = position.safeParse({
          latitude: poi.get('lat'),
          longitude: poi.get('lon'),
        });
        if (
          !shape ||
          area.get('active') !== true ||
          area.get('safeAreaConfirmed') !== true ||
          !candidate.success ||
          !isPointInShape(candidate.data.latitude, candidate.data.longitude, shape)
        ) {
          throw new HttpsError('failed-precondition', 'The selected stop is no longer available.');
        }
        replacement = candidate.data;
        const blocks = await readPlacementBlocks(replacement.latitude, replacement.latitude, tx);
        if (isPlacementBlocked(replacement, [...blocks, data])) {
          throw new HttpsError('failed-precondition', 'The selected stop is blocked.');
        }
        tx.set(automaticPlacementMoves.doc(input.caseId), {
          ...replacement,
          areaId: data.areaId,
          poiId,
          sourceSpawnId: data.crownId,
          areaCell: `${data.areaId}/${crownCellKey(replacement.latitude, replacement.longitude)}`,
        });
      } else if (
        data.source === 'hand_placed' &&
        'latitude' in input.destination &&
        matches &&
        current?.get('status') !== 'ended'
      ) {
        replacement = {
          latitude: input.destination.latitude,
          longitude: input.destination.longitude,
        };
        const blocks = await readPlacementBlocks(replacement.latitude, replacement.latitude, tx);
        if (isPlacementBlocked(replacement, [...blocks, data])) {
          throw new HttpsError('failed-precondition', 'The selected placement is blocked.');
        }
        if (current?.get('status') === 'active') {
          tx.set(manualPlacementMoves.doc(data.crownId), {
            ...replacement,
            fromLatitude: data.latitude,
            fromLongitude: data.longitude,
          });
        } else {
          // Draft/paused points aren't visible to players. Show the new
          // coordinates in the standard safety review before reactivation.
          tx.update(crown, { ...replacement, updatedAt: stamp });
          tx.delete(manualPlacementMoves.doc(data.crownId));
        }
      } else {
        throw new HttpsError('failed-precondition', 'This crown cannot be moved.');
      }
    }

    if (input.action !== 'ignore') {
      tx.set(placementBlocks.doc(input.caseId), {
        latitude: data.latitude,
        longitude: data.longitude,
        source: data.source,
        createdAt: stamp,
      });
    }
    if (input.action === 'remove' && matches) {
      if (data.source === 'automatic') {
        tx.delete(crown);
      } else {
        // End the point atomically with the block. Its claim/collector history stays intact.
        tx.update(crown, { status: 'ended', updatedAt: stamp });
        tx.delete(manualPlacementMoves.doc(data.crownId));
      }
    }
    tx.update(ref, {
      decision: input.action,
      openAt: FieldValue.delete(),
      closedAt: stamp,
      ...(replacement ? { replacement } : {}),
    });
    tx.create(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'crownHunt.resolvePlacementCase',
          targetType: 'crownPlacementCase',
          targetId: input.caseId,
          reason: input.action,
          details: {
            crownId: data.crownId,
            source: data.source,
            reportCount: data.reportCount,
            reasons: data.reasons,
          },
        },
        () => stamp,
      ),
    );
    return { resolved: true };
  });
});
