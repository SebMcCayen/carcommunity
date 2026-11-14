import {
  FieldValue,
  Timestamp,
  type Firestore,
  type Transaction,
  type DocumentSnapshot,
} from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import {
  digest,
  messagePath,
  nextCounts,
  unavailable,
  type ReactionInput,
  type ReactionRoom,
  type ReactionState,
} from './core';

export type ReactionAuthority = (
  tx: Transaction,
  uid: string,
  room: ReactionRoom,
  adding: boolean,
) => Promise<void>;
const epoch = (doc: DocumentSnapshot): string => {
  const time = doc.get('createdAt');
  if (!(time instanceof Timestamp)) return unavailable();
  return `${time.seconds}:${time.nanoseconds}`;
};
export function visible(doc: DocumentSnapshot): boolean {
  const data = doc.data();
  return (
    !!data &&
    !['removed', 'hidden', 'auto_hidden'].includes(String(data.status)) &&
    !['removed', 'auto_hidden'].includes(String(data.moderationState)) &&
    (!(data.expireAt instanceof Timestamp) || data.expireAt.toMillis() > Date.now()) &&
    data.removed !== true &&
    data.hidden !== true &&
    data.deleted !== true &&
    (typeof data.text === 'string'
      ? data.text.trim().length > 0
      : typeof data.message === 'string' && data.message.trim().length > 0) &&
    (!(data.expiresAt instanceof Timestamp) || data.expiresAt.toMillis() > Date.now())
  );
}
export async function requireTarget(
  tx: Transaction,
  db: Firestore,
  uid: string,
  path: string,
): Promise<DocumentSnapshot> {
  const doc = await tx.get(db.doc(path));
  if (!visible(doc)) return unavailable();
  const author = doc.get('senderUid') ?? doc.get('authorUserId') ?? doc.get('authorUid');
  if (typeof author !== 'string' || !author) return unavailable();
  if (author !== uid) {
    const blocks = await tx.getAll(
      db.doc(`userBlocks/${uid}/blocked/${author}`),
      db.doc(`userBlocks/${author}/blocked/${uid}`),
    );
    if (blocks.some((block) => block.exists)) return unavailable();
  }
  return doc;
}
function state(doc: DocumentSnapshot, parentEpoch: string): ReactionState {
  if (!doc.exists || doc.get('parentEpoch') !== parentEpoch) return { emojiId: null, version: 0 };
  const version = doc.get('version'),
    emojiId = doc.get('emojiId');
  if (
    !Number.isSafeInteger(version) ||
    version < 0 ||
    !(emojiId === null || typeof emojiId === 'string')
  )
    throw new HttpsError('failed-precondition', 'Reaction state unavailable.');
  return { emojiId, version };
}
/** A shared request budget also bounds read/no-op/replay abuse; committed changes have their own limit. */
function budget(data: FirebaseFirestore.DocumentData | undefined, changing: boolean, now: number) {
  const windowStart = Number(data?.windowStart ?? 0);
  const old = now - windowStart < 60_000 && windowStart <= now ? data : undefined;
  const requests = Number(old?.requests ?? 0),
    changes = Number(old?.changes ?? 0);
  if (requests >= 120 || (changing && changes >= 20))
    throw new HttpsError('resource-exhausted', 'Try again later.', {
      retryAfterMs: Math.max(1000, 60_000 - (now - windowStart)),
    });
  return {
    windowStart: old ? windowStart : now,
    requests: requests + 1,
    changes: changes + Number(changing),
  };
}
export class MessageReactionEngine {
  constructor(
    private readonly db: Firestore,
    private readonly authorize: ReactionAuthority,
  ) {}
  async set(uid: string, input: ReactionInput) {
    const path = messagePath(input, input.messageId),
      record = this.db.doc(`chatMessageReactions/${digest(uid + '\0' + path)}`);
    const receipt = this.db.doc(`chatReactionOperations/${digest(uid + '\0' + input.operationId)}`);
    const fingerprint = digest(JSON.stringify(input));
    return this.db.runTransaction(async (tx) => {
      await this.authorize(tx, uid, input, input.emojiId !== null);
      const parent = await requireTarget(tx, this.db, uid, path),
        parentEpoch = epoch(parent);
      const guard = this.db.doc(`chatReactionRateLimits/${uid}`);
      const [own, previousOp, rate] = await Promise.all([
        tx.get(record),
        tx.get(receipt),
        tx.get(guard),
      ]);
      const current = state(own, parentEpoch);
      if (previousOp.exists && previousOp.get('fingerprint') !== fingerprint)
        throw new HttpsError('already-exists', 'Operation already used.');
      // Replay is authorized again, returns CURRENT state, and never reapplies old state.
      const duplicate = previousOp.exists;
      if (!duplicate && current.version !== input.expectedVersion)
        throw new HttpsError('aborted', 'Reaction changed. Refresh before retrying.');
      const changed = !duplicate && current.emojiId !== input.emojiId;
      const now = Date.now(),
        nextBudget = budget(rate.data(), changed, now);
      const result = changed ? { emojiId: input.emojiId, version: current.version + 1 } : current;
      const counts = changed
        ? nextCounts(parent.get('reactionCounts'), current.emojiId, input.emojiId)
        : (parent.get('reactionCounts') ?? {});
      tx.set(guard, { ...nextBudget, expireAt: Timestamp.fromMillis(now + 120_000) });
      if (changed) {
        tx.set(record, {
          uid,
          parentPath: path,
          parentEpoch,
          roomKey: `${input.scope}:${input.roomId}`,
          ...result,
          updatedAt: FieldValue.serverTimestamp(),
        });
        tx.update(parent.ref, { reactionCounts: counts, reactionCatalogVersion: '18.0' });
      }
      if (!duplicate)
        tx.create(receipt, {
          uid,
          fingerprint,
          parentPath: path,
          createdAt: FieldValue.serverTimestamp(),
          expireAt: Timestamp.fromMillis(now + 7 * 86400_000),
        });
      return { messageId: input.messageId, ...result, counts };
    });
  }
  async getMine(uid: string, room: ReactionRoom, messageIds: string[]) {
    return this.db.runTransaction(async (tx) => {
      await this.authorize(tx, uid, room, false);
      const items = [];
      for (const messageId of [...new Set(messageIds)]) {
        const path = messagePath(room, messageId);
        const parent = await requireTarget(tx, this.db, uid, path);
        const own = await tx.get(this.db.doc(`chatMessageReactions/${digest(uid + '\0' + path)}`));
        items.push({ messageId, ...state(own, epoch(parent)) });
      }
      const guard = this.db.doc(`chatReactionRateLimits/${uid}`),
        rate = await tx.get(guard),
        now = Date.now();
      tx.set(guard, {
        ...budget(rate.data(), false, now),
        expireAt: Timestamp.fromMillis(now + 120_000),
      });
      return { items };
    });
  }
}

/** Erasure is paged, idempotent, and never recreates missing parents. Call after account revocation. */
export async function eraseReactionRecord(
  db: Firestore,
  recordPath: string,
  expectedEpoch?: string,
): Promise<void> {
  await db.runTransaction(async (tx) => {
    const own = await tx.get(db.doc(recordPath));
    if (!own.exists || (expectedEpoch !== undefined && own.get('parentEpoch') !== expectedEpoch))
      return;
    const path = own.get('parentPath');
    if (
      typeof path !== 'string' ||
      !/^(communityChat|convoyChats|conversations|events|driverClubs)\/[^/]+\/messages\/[^/]+$/.test(
        path,
      )
    )
      throw new Error('Invalid reaction parent.');
    const parent = await tx.get(db.doc(path));
    if (parent.exists && epoch(parent) === own.get('parentEpoch') && own.get('emojiId'))
      tx.update(parent.ref, {
        reactionCounts: nextCounts(parent.get('reactionCounts'), own.get('emojiId'), null),
      });
    tx.delete(own.ref);
  });
}
export async function eraseUserReactions(db: Firestore, uid: string): Promise<void> {
  for (let pass = 0; ; pass++) {
    if (pass >= 20) throw new Error('Reaction erasure pending; retry.');
    const page = await db
      .collection('chatMessageReactions')
      .where('uid', '==', uid)
      .limit(50)
      .get();
    if (page.empty) break;
    for (const row of page.docs) await eraseReactionRecord(db, row.ref.path);
  }
  for (let pass = 0; ; pass++) {
    if (pass >= 20) throw new Error('Reaction receipt erasure pending; retry.');
    const page = await db
      .collection('chatReactionOperations')
      .where('uid', '==', uid)
      .limit(100)
      .get();
    if (page.empty) break;
    const batch = db.batch();
    page.docs.forEach((row) => batch.delete(row.ref));
    await batch.commit();
  }
}
