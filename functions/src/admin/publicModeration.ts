/** Admin-only public moderation. Paths are allowlisted; no DM/convoy reader. */
import { createHash } from 'node:crypto';
import {
  FieldPath,
  FieldValue,
  Timestamp,
  type Query,
  type DocumentSnapshot,
  type Transaction,
} from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import { z } from 'zod';
import { onCall } from '../access/callable';
import { db } from '../firebase';
import { identityKeys, isCarCommunityRestricted } from '../access/carCommunity-core';
import { toUserAccessState } from '../shared/access';
import { requireAdminActor } from './actorContext';
import { buildAdminAuditEvent, guardActorIsActiveAdmin } from './claims-core';
import { pageInput, historyCursor, timestamp, nextHistoryCursor } from './history-pagination';
import { redactPublicReplies } from '../chatchannels/redactPublicReplies';
import { CPU_ADMIN, MAX_INSTANCES_ADMIN } from '../shared/instanceLimits';
const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 60,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};
const id = z
  .string()
  .min(1)
  .max(128)
  .refine((v) => !v.includes('/') && v !== '.' && v !== '..');
const locator = z
  .object({
    source: z.enum(['event', 'community']),
    eventId: id.optional(),
    messageId: id,
    reportId: z
      .string()
      .min(1)
      .max(512)
      .refine((v) => !v.includes('/') && v !== '.' && v !== '..')
      .optional(),
  })
  .strict()
  .refine((v) => (v.source === 'event' ? !!v.eventId : !v.eventId));
type Locator = z.infer<typeof locator>;
const hash = (v: unknown) => createHash('sha256').update(JSON.stringify(v)).digest('hex');
function parse<T>(schema: z.ZodType<T>, value: unknown): T {
  const p = schema.safeParse(value ?? {});
  if (!p.success) throw new HttpsError('invalid-argument', 'Ogiltig modereringsförfrågan.');
  return p.data;
}
function refs(v: Locator) {
  const messages =
    v.source === 'event'
      ? db.collection('events').doc(v.eventId!).collection('messages')
      : db.collection('communityChat').doc('global').collection('messages');
  const report = !v.reportId
    ? null
    : v.source === 'event'
      ? db.collection('events').doc(v.eventId!).collection('messageReports').doc(v.reportId)
      : db.collection('moderationReports').doc(v.reportId);
  return { messages, message: messages.doc(v.messageId), report };
}
function validReport(v: Locator, report: DocumentSnapshot) {
  return (
    report.exists &&
    (v.source === 'event'
      ? report.get('messageId') === v.messageId
      : report.get('surface') === 'community' &&
        report.get('scopeId') === 'global' &&
        report.get('targetType') === 'message' &&
        report.get('targetId') === v.messageId)
  );
}
const iso = (v: unknown): string | null =>
  v instanceof Timestamp ? v.toDate().toISOString() : null;
const str = (v: unknown) => (typeof v === 'string' ? v : '');
function projectMessage(v: Locator, doc: DocumentSnapshot) {
  const d = doc.data() ?? {},
    event = v.source === 'event';
  return {
    messageId: v.messageId,
    source: v.source,
    eventId: v.eventId ?? null,
    exists: doc.exists,
    authorUid: str(event ? d.authorUserId : d.senderUid),
    authorName: str(event ? d.authorDisplayName : d.senderDisplayName),
    text: str(event ? d.message : d.text),
    state: doc.exists ? (event ? str(d.moderationState) || 'visible' : 'visible') : 'removed',
    createdAt: iso(d.createdAt),
  };
}
function version(message: DocumentSnapshot, report?: DocumentSnapshot | null) {
  return hash([
    message.ref.path,
    message.updateTime ? [message.updateTime.seconds, message.updateTime.nanoseconds] : null,
    report?.ref.path ?? null,
    report?.updateTime ? [report.updateTime.seconds, report.updateTime.nanoseconds] : null,
  ]);
}
// Commit-time authorization also serializes receipt replay with role/access revocation.
async function authorize(tx: Transaction, uid: string) {
  const [profile, access] = await Promise.all([
    tx.get(db.doc(`users/${uid}`)),
    tx.get(db.doc(`carCommunityAccess/${uid}`)),
  ]);
  const guard = guardActorIsActiveAdmin(toUserAccessState(profile.data()));
  if (!guard.ok) throw new HttpsError(guard.code, guard.message);
  const keys = identityKeys(access.data());
  const identities = keys.length
    ? await tx.getAll(...keys.map((key) => db.doc(`carCommunityIdentityBlocks/${key}`)))
    : [];
  if (
    isCarCommunityRestricted(
      access.data(),
      identities.map((d) => d.data() ?? {}),
    )
  )
    throw new HttpsError('permission-denied', 'Åtkomsten till CarCommunity har ändrats.');
}
const listing = z
  .object({
    ...pageInput,
    status: z.enum(['open', 'review', 'closed']).default('open'),
    source: z.enum(['all', 'event', 'community']).default('all'),
  })
  .strict();
const messagesInput = z
  .object({
    ...pageInput,
    targetUid: id,
    source: z.enum(['all', 'event', 'community']).default('all'),
  })
  .strict();
// Same timestamp and document-reference order for every page; each stream has its own frontier.
async function list(input: {
  pageSize: number;
  cursor?: string | null;
  source: string;
  status?: string;
  targetUid?: string;
}) {
  const cursor = historyCursor(input, [
    'publicModeration',
    input.targetUid ?? null,
    input.status ?? null,
    input.source,
  ]);
  const streams = (['event', 'community'] as const).filter(
    (s) => input.source === 'all' || input.source === s,
  );
  const pages = await Promise.all(
    streams.map(async (source) => {
      const slot = source === 'event' ? 'first' : 'second';
      let query: Query;
      if (input.targetUid)
        query =
          source === 'event'
            ? db.collectionGroup('messages').where('authorUserId', '==', input.targetUid)
            : db
                .collection('communityChat')
                .doc('global')
                .collection('messages')
                .where('senderUid', '==', input.targetUid);
      else if (source === 'event')
        query = db
          .collectionGroup('messageReports')
          .where(
            'status',
            'in',
            input.status === 'open'
              ? ['new']
              : input.status === 'review'
                ? ['under_review']
                : ['resolved', 'dismissed'],
          );
      else
        query = db
          .collection('moderationReports')
          .where('surface', '==', 'community')
          .where('scopeId', '==', 'global')
          .where('targetType', '==', 'message')
          .where(
            'status',
            'in',
            input.status === 'open'
              ? ['pending']
              : input.status === 'review'
                ? ['under_review']
                : ['reviewed', 'dismissed'],
          );
      query = query
        .where('createdAt', '<=', timestamp(cursor.asOf))
        .orderBy('createdAt', 'desc')
        .orderBy(FieldPath.documentId(), 'desc');
      const after = cursor.after[slot];
      if (after) {
        const path = Buffer.from(after.id, 'base64url').toString('utf8');
        const expected =
          source === 'event'
            ? input.targetUid
              ? /^events\/[^/]+\/messages\/[^/]+$/
              : /^events\/[^/]+\/messageReports\/[^/]+$/
            : input.targetUid
              ? /^communityChat\/global\/messages\/[^/]+$/
              : /^moderationReports\/[^/]+$/;
        if (!expected.test(path)) throw new HttpsError('invalid-argument', 'Ogiltig sidmarkör.');
        query = query.startAfter(timestamp(after), db.doc(path));
      }
      const page = await query.limit(input.pageSize + 1).get();
      return { slot, source, docs: page.docs };
    }),
  );
  const rows = pages.flatMap((p) =>
    p.docs.map((doc) => ({ ...p, doc, time: doc.get('createdAt') as Timestamp })),
  );
  rows.sort(
    (a, b) =>
      b.time.seconds - a.time.seconds ||
      b.time.nanoseconds - a.time.nanoseconds ||
      a.source.localeCompare(b.source) ||
      (a.doc.ref.path > b.doc.ref.path ? -1 : 1),
  );
  const selected = rows.slice(0, input.pageSize);
  // Fail closed on a future collection using the same group fields. Never project its contents.
  for (const row of selected)
    if (
      row.source === 'event' &&
      !(
        input.targetUid
          ? /^events\/[^/]+\/messages\/[^/]+$/
          : /^events\/[^/]+\/messageReports\/[^/]+$/
      ).test(row.doc.ref.path)
    )
      throw new HttpsError('failed-precondition', 'Kön behöver kontrolleras av systemansvarig.');
  const messageDocs =
    !input.targetUid && selected.length
      ? await db.getAll(
          ...selected.map(({ doc, source }) => {
            const messageId = str(doc.get(source === 'event' ? 'messageId' : 'targetId'));
            if (!id.safeParse(messageId).success)
              throw new HttpsError('failed-precondition', 'En anmälan har ogiltigt meddelande-id.');
            return refs({
              source,
              eventId: source === 'event' ? doc.ref.parent.parent!.id : undefined,
              messageId,
            }).message;
          }),
        )
      : [];
  const eventIds = [
    ...new Set(
      selected.filter((row) => row.source === 'event').map((row) => row.doc.ref.parent.parent!.id),
    ),
  ];
  const events = eventIds.length
    ? await db.getAll(...eventIds.map((eventId) => db.collection('events').doc(eventId)))
    : [];
  const titles = new Map(events.map((doc) => [doc.id, str(doc.get('title'))]));
  const items = selected.map(({ doc, source, slot, time }, index) => {
    cursor.after[slot as 'first' | 'second'] = {
      seconds: time.seconds,
      nanoseconds: time.nanoseconds,
      id: Buffer.from(doc.ref.path).toString('base64url'),
    };
    const eventId = source === 'event' ? doc.ref.parent.parent!.id : undefined;
    if (input.targetUid)
      return {
        ...projectMessage({ source, eventId, messageId: doc.id }, doc),
        id: `${source}:${doc.ref.path}`,
      };
    return {
      id: `${source}:${doc.ref.path}`,
      source,
      eventId: eventId ?? null,
      eventTitle: eventId ? titles.get(eventId) || eventId : null,
      authorName: str(
        messageDocs[index]?.get(source === 'event' ? 'authorDisplayName' : 'senderDisplayName') ||
          doc.get('snapshot')?.authorDisplayName,
      ),
      reportId: doc.id,
      messageId: str(doc.get(source === 'event' ? 'messageId' : 'targetId')),
      reason: str(doc.get('reason')),
      details: str(doc.get('details')),
      status: str(doc.get('status')),
      createdAt: iso(doc.get('createdAt')),
    };
  });
  return {
    items,
    meta: {
      hasNext: rows.length > input.pageSize,
      nextCursor: rows.length > input.pageSize ? nextHistoryCursor(cursor) : null,
      asOf: timestamp(cursor.asOf).toDate().toISOString(),
    },
  };
}
export const listPublicReports = onCall(CALLABLE_OPTS, async (request) => {
  await requireAdminActor(request);
  return list(parse(listing, request.data));
});
export const listMemberMessages = onCall(CALLABLE_OPTS, async (request) => {
  await requireAdminActor(request);
  return list(parse(messagesInput, request.data));
});
export const getPublicModerationCase = onCall(CALLABLE_OPTS, async (request) => {
  const actor = await requireAdminActor(request),
    input = parse(locator, request.data),
    r = refs(input);
  const [message, report] = await Promise.all([r.message.get(), r.report?.get()]);
  if (report && !validReport(input, report))
    throw new HttpsError('not-found', 'Anmälan finns inte i den valda publika chatten.');
  const audit = await db
    .collection('adminAuditEvents')
    .where('targetId', '==', input.messageId)
    .where('details.source', '==', input.source)
    .where('details.eventId', '==', input.eventId ?? null)
    .orderBy('createdAt', 'desc')
    .limit(10)
    .get();
  const scopedAudit = audit.docs.filter(
    (d) =>
      d.get('details')?.source === input.source &&
      (d.get('details')?.eventId ?? null) === (input.eventId ?? null),
  );
  const removedEvidence = scopedAudit
    .find((d) => d.get('action') === 'publicChat.remove')
    ?.get('details');
  const projected = projectMessage(input, message);
  const reportEvidence = report?.get('snapshot');
  if (!message.exists) {
    const author =
      report?.get('reportedUserId') ?? reportEvidence?.authorUserId ?? removedEvidence?.authorUid;
    projected.authorUid = id.safeParse(author).success ? str(author) : '';
    projected.authorName =
      str(reportEvidence?.authorDisplayName) || str(removedEvidence?.authorName);
    projected.createdAt =
      iso(removedEvidence?.messageCreatedAt) ||
      (typeof reportEvidence?.createdAt === 'string' ? reportEvidence.createdAt : null);
  }
  const createdAt =
    message.get('createdAt') instanceof Timestamp
      ? message.get('createdAt')
      : removedEvidence?.messageCreatedAt;
  const context =
    createdAt instanceof Timestamp
      ? await r.messages
          .where('createdAt', '<', createdAt)
          .orderBy('createdAt', 'desc')
          .limit(1)
          .get()
      : null;
  const event = input.eventId ? await db.collection('events').doc(input.eventId).get() : null;
  return {
    eventTitle: event ? str(event.get('title')) || input.eventId : null,
    actorUid: actor.uid,
    version: version(message, report),
    message: projected,
    report: report
      ? {
          id: report.id,
          reason: str(report.get('reason')),
          details: str(report.get('details')),
          status: str(report.get('status')),
          createdAt: iso(report.get('createdAt')),
        }
      : null,
    context: context?.docs.map((d) => projectMessage({ ...input, messageId: d.id }, d)) ?? [],
    history: scopedAudit.map((d) => ({
      id: d.id,
      action: str(d.get('action')),
      reason: str(d.get('reason')),
      createdAt: iso(d.get('createdAt')),
    })),
  };
});
const decision = z
  .object({
    target: locator,
    expectedActorUid: id,
    expectedVersion: z.string().length(64),
    action: z.enum(['review', 'resolve', 'dismiss', 'remove', 'allow', 'cleanup']),
    reason: z.string().trim().min(1).max(500),
    idempotencyKey: z.string().regex(/^[A-Za-z0-9._-]{16,128}$/),
  })
  .strict();
export const decidePublicModeration = onCall(CALLABLE_OPTS, async (request) => {
  const actor = await requireAdminActor(request),
    input = parse(decision, request.data),
    r = refs(input.target);
  if (input.expectedActorUid !== actor.uid)
    throw new HttpsError('permission-denied', 'Administratörens session har ändrats.');
  if (!r.report && ['review', 'resolve', 'dismiss'].includes(input.action))
    throw new HttpsError('invalid-argument', 'Välj en anmälan.');
  if (input.action === 'allow' && input.target.source !== 'event')
    throw new HttpsError(
      'invalid-argument',
      'Publika meddelanden kan behållas genom att avvisa anmälan.',
    );
  const receipt = db
    .collection('adminModerationOperations')
    .doc(hash([actor.uid, input.idempotencyKey]));
  const result = await db.runTransaction(async (tx) => {
    await authorize(tx, actor.uid);
    const [message, report, old] = await Promise.all([
      tx.get(r.message),
      r.report ? tx.get(r.report) : Promise.resolve(null),
      tx.get(receipt),
    ]);
    if (old.exists) {
      if (old.get('fingerprint') !== hash(input))
        throw new HttpsError('failed-precondition', 'Åtgärdsnyckeln avser ett annat beslut.');
      return { ...old.get('result'), alreadyApplied: true };
    }
    if (report && !validReport(input.target, report))
      throw new HttpsError('not-found', 'Anmälan finns inte i den valda publika chatten.');
    if (version(message, report) !== input.expectedVersion)
      throw new HttpsError(
        'failed-precondition',
        'Underlaget har ändrats. Uppdatera och granska beslutet igen.',
      );
    if (
      ['allow', 'remove'].includes(input.action) &&
      (!message.exists || message.get('moderationState') === 'removed')
    )
      throw new HttpsError(
        'failed-precondition',
        'Meddelandet är redan borttaget. Avsluta anmälan separat.',
      );
    if (input.action === 'cleanup' && (input.target.source !== 'community' || message.exists))
      throw new HttpsError(
        'failed-precondition',
        'Citat kan rensas efter att det publika meddelandet tagits bort.',
      );
    const stamp = FieldValue.serverTimestamp();
    if (input.action === 'remove') {
      if (input.target.source === 'event')
        tx.update(r.message, {
          message: '',
          moderationState: 'removed',
          removedAt: stamp,
          removedByUserId: actor.uid,
        });
      else {
        tx.delete(r.message);
        tx.set(db.collection('communityChatDeletedMessages').doc(input.target.messageId), {
          removedAt: stamp,
        });
      }
    }
    if (input.action === 'allow')
      tx.update(r.message, {
        moderationState: 'allowed',
        allowedAt: stamp,
        allowedByUserId: actor.uid,
      });
    const status =
      input.action === 'review'
        ? 'under_review'
        : input.action === 'dismiss' || input.action === 'allow'
          ? 'dismissed'
          : input.target.source === 'event'
            ? 'resolved'
            : 'reviewed';
    if (report && input.action !== 'cleanup')
      tx.update(report.ref, {
        status,
        reviewedAt: stamp,
        reviewedByUserId: actor.uid,
        reviewedBy: actor.uid,
        updatedAt: stamp,
      });
    tx.create(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: `publicChat.${input.action}`,
          targetType: 'publicChatMessage',
          targetId: input.target.messageId,
          reason: input.reason,
          details: {
            source: input.target.source,
            eventId: input.target.eventId ?? null,
            reportId: input.target.reportId ?? null,
            authorUid: projectMessage(input.target, message).authorUid,
            authorName: projectMessage(input.target, message).authorName,
            messageCreatedAt: message.get('createdAt') ?? null,
            originalText:
              input.action === 'remove' ? projectMessage(input.target, message).text : null,
            status: report
              ? input.action === 'cleanup'
                ? str(report.get('status'))
                : status
              : null,
          },
        },
        () => stamp,
      ),
    );
    const value = {
      messageId: input.target.messageId,
      status: report ? (input.action === 'cleanup' ? str(report.get('status')) : status) : null,
      alreadyApplied: false,
    };
    tx.create(receipt, { fingerprint: hash(input), result: value, createdAt: stamp });
    return value;
  });
  // A timed-out retry resumes bounded quote cleanup, even when the decision receipt exists.
  const cleanup =
    ['remove', 'cleanup'].includes(input.action) && input.target.source === 'community'
      ? await redactPublicReplies(input.target.messageId)
      : { redacted: 0, hasMore: false };
  return { ...result, cleanup };
});
