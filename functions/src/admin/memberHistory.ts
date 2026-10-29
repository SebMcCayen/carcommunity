/** Narrow member audit projection: never returns raw details or message evidence. */
import { z } from 'zod';
import { HttpsError } from 'firebase-functions/v2/https';
import { onCall } from '../access/callable';
import { FieldPath, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from './actorContext';
import {
  pageInput,
  historyCursor,
  timestamp,
  documentPosition,
  nextHistoryCursor,
} from './history-pagination';
import { CPU_ADMIN, MAX_INSTANCES_ADMIN } from '../shared/instanceLimits';
const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};
export const listMemberHistory = onCall(CALLABLE_OPTS, async (request) => {
  await requireAdminActor(request);
  const parsed = z
    .object({
      ...pageInput,
      targetUid: z
        .string()
        .min(1)
        .max(128)
        .refine((v) => !v.includes('/') && v !== '.' && v !== '..'),
    })
    .strict()
    .safeParse(request.data);
  if (!parsed.success) throw new HttpsError('invalid-argument', 'Invalid member history request.');
  const input = parsed.data,
    cursor = historyCursor(input, ['memberAudit', input.targetUid]);
  const query = db
    .collection('adminAuditEvents')
    .where('targetId', '==', input.targetUid)
    .where('createdAt', '<=', timestamp(cursor.asOf))
    .orderBy('createdAt', 'desc')
    .orderBy(FieldPath.documentId(), 'desc');
  const after = cursor.after.first;
  const [page, count] = await Promise.all([
    (after ? query.startAfter(timestamp(after), after.id) : query).limit(input.pageSize + 1).get(),
    query.count().get(),
  ]);
  const docs = page.docs.slice(0, input.pageSize);
  const actors = [
    ...new Set(
      docs
        .map((doc) => doc.get('adminId'))
        .filter(
          (value): value is string =>
            typeof value === 'string' && value.length > 0 && !value.includes('/'),
        ),
    ),
  ];
  const profiles = actors.length
    ? await db.getAll(...actors.map((uid) => db.collection('users').doc(uid)))
    : [];
  const names = new Map(
    profiles.map((doc) => [
      doc.id,
      typeof doc.get('displayName') === 'string' ? doc.get('displayName') : null,
    ]),
  );
  if (docs.length) cursor.after.first = documentPosition(docs.at(-1)!, 'createdAt');
  return {
    items: docs.map((doc) => ({
      id: doc.id,
      action: String(doc.get('action') ?? ''),
      reason: typeof doc.get('reason') === 'string' ? doc.get('reason') : null,
      actorName: names.get(doc.get('adminId')) || 'Tidigare administratör',
      createdAt:
        doc.get('createdAt') instanceof Timestamp
          ? doc.get('createdAt').toDate().toISOString()
          : null,
    })),
    meta: {
      total: count.data().count,
      nextCursor: page.size > input.pageSize ? nextHistoryCursor(cursor) : null,
      asOf: timestamp(cursor.asOf).toDate().toISOString(),
    },
  };
});
