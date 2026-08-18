/**
 * notifications.markRead / notifications.markAllRead / notifications.delete /
 * notifications.deleteAll — authenticated callables
 * (contracts/functions/functions.json).
 *
 * The inbox is backend-write-only, so read-state changes and removals go
 * through these callables instead of direct document writes. Ownership is
 * structural for ALL FOUR: they only ever address
 * `notifications/{caller}/items/...`, so a notification ID belonging to
 * another user resolves to a document that does not exist in the caller's
 * inbox and can never be touched — there is no code path that takes a uid
 * from the request. All four are idempotent.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import {
  parseDeleteNotificationInput,
  parseMarkNotificationReadInput,
} from './notifications-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

const MARK_ALL_BATCH_SIZE = 500;
const DELETE_ALL_BATCH_SIZE = 500;

export interface MarkReadResponse {
  notificationId: string;
  /** False when the notification was already read (idempotent replay). */
  marked: boolean;
}

export const markRead = onCall(CALLABLE_OPTS, async (request): Promise<MarkReadResponse> => {
  const actor = await requireActiveActor(request);

  const parsed = parseMarkNotificationReadInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const ref = db
    .collection('notifications')
    .doc(actor.uid)
    .collection('items')
    .doc(parsed.input.notificationId);

  const marked = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) {
      throw new HttpsError('not-found', 'Notification not found.');
    }
    if (snap.data()!.read === true) {
      return false;
    }
    tx.update(ref, { read: true, readAt: FieldValue.serverTimestamp() });
    return true;
  });

  return { notificationId: parsed.input.notificationId, marked };
});

export interface MarkAllReadResponse {
  markedCount: number;
}

export const markAllRead = onCall(
  CALLABLE_OPTS,
  async (request): Promise<MarkAllReadResponse> => {
    const actor = await requireActiveActor(request);

    const items = db.collection('notifications').doc(actor.uid).collection('items');
    let markedCount = 0;
    for (;;) {
      const unread = await items
        .where('read', '==', false)
        .limit(MARK_ALL_BATCH_SIZE)
        .get();
      if (unread.empty) {
        break;
      }
      const batch = db.batch();
      for (const doc of unread.docs) {
        batch.update(doc.ref, { read: true, readAt: FieldValue.serverTimestamp() });
      }
      await batch.commit();
      markedCount += unread.size;
      if (unread.size < MARK_ALL_BATCH_SIZE) {
        break;
      }
    }

    return { markedCount };
  },
);

export interface MarkSeenResponse {
  /** The stamped marker, echoed back (ISO-8601). */
  lastSeenAt: string;
}

/**
 * notifications.markSeen — stamps the caller's inbox-seen marker at
 * userPrivate/{uid}.notificationsLastSeenAt = now (owner-only readable, the same
 * per-user private doc that holds communityChatLastReadAt).
 *
 * This is the "the inbox has something new" marker behind the Notifications
 * red dot (map chat bubble + the hub's Notifications tab), the exact mirror of
 * communityChat.markRead: the client's newest-item live listener lights the dot
 * when the newest notification post-dates this marker and clears it once the
 * user opens the inbox (which calls this). It is DELIBERATELY separate from the
 * per-item `read` flag that markRead / markAllRead maintain — opening the inbox
 * clears the dot without marking every row read, so each row keeps its own
 * unread styling until the user actually taps it (again mirroring chat, where
 * opening the channel clears the dot without touching individual messages).
 *
 * Takes {} and derives the uid from auth, so there is no value a caller could
 * supply that would stamp another member's marker. Idempotent: re-stamping just
 * advances the timestamp. Best-effort bookkeeping — the client swallows failures.
 */
export const markSeen = onCall(CALLABLE_OPTS, async (request): Promise<MarkSeenResponse> => {
  const actor = await requireActiveActor(request);

  const ref = db.collection('userPrivate').doc(actor.uid);
  await ref.set({ notificationsLastSeenAt: FieldValue.serverTimestamp() }, { merge: true });

  const fresh = await ref.get();
  const stamped = fresh.data()?.notificationsLastSeenAt;
  const iso =
    stamped && typeof stamped.toDate === 'function'
      ? stamped.toDate().toISOString()
      : new Date().toISOString();
  return { lastSeenAt: iso };
});

export interface DeleteNotificationResponse {
  notificationId: string;
  /** False when there was nothing to delete (idempotent replay). */
  deleted: boolean;
}

/**
 * Deletes ONE of the caller's own inbox items (the per-row swipe-to-delete on
 * the Android inbox).
 *
 * Ownership needs no check because it is structural: the reference is built
 * from `actor.uid`, never from the request, so the only collection this
 * callable can address is the caller's own. Another member's notification id
 * therefore names a document that does not exist HERE — their item is left
 * untouched and the response is the same as any other miss.
 *
 * That miss is a silent `{ deleted: false }` rather than a not-found error
 * (deliberately unlike markRead, which reports not-found because failing to
 * mark something read is worth surfacing). A delete has nothing to report:
 * the requested end state — "this notification is gone from my inbox" —
 * already holds. It makes a double-swipe, a retry after a dropped response,
 * and a race with the retention sweep all no-ops instead of errors the client
 * would have to special-case, and it keeps the response from being an oracle
 * for whether an id exists in someone else's inbox.
 */
export const deleteNotification = onCall(
  CALLABLE_OPTS,
  async (request): Promise<DeleteNotificationResponse> => {
    const actor = await requireActiveActor(request);

    const parsed = parseDeleteNotificationInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const ref = db
      .collection('notifications')
      .doc(actor.uid)
      .collection('items')
      .doc(parsed.input.notificationId);

    const deleted = await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      if (!snap.exists) {
        return false;
      }
      tx.delete(ref);
      return true;
    });

    return { notificationId: parsed.input.notificationId, deleted };
  },
);

export interface DeleteAllNotificationsResponse {
  deletedCount: number;
}

/**
 * Empties the caller's inbox (the "delete all" action, which the client puts
 * behind a confirmation because it is irreversible).
 *
 * Same structural ownership as the single delete: the collection is derived
 * from `actor.uid` and the callable takes no input at all, so there is no
 * value a caller could supply that would reach another member's inbox.
 *
 * Batched and re-queried the way markAllRead is, because a batch is capped at
 * 500 writes; the loop terminates because each committed batch removes the
 * documents the next query would return. Idempotent — a replay finds an empty
 * collection and reports 0. A run that exhausts the 30s timeout part-way is
 * safe for the same reason: the deletes it did commit stand, and re-running
 * finishes the rest.
 */
export const deleteAllNotifications = onCall(
  CALLABLE_OPTS,
  async (request): Promise<DeleteAllNotificationsResponse> => {
    const actor = await requireActiveActor(request);

    const items = db.collection('notifications').doc(actor.uid).collection('items');
    let deletedCount = 0;
    for (;;) {
      const page = await items.limit(DELETE_ALL_BATCH_SIZE).get();
      if (page.empty) {
        break;
      }
      const batch = db.batch();
      for (const doc of page.docs) {
        batch.delete(doc.ref);
      }
      await batch.commit();
      deletedCount += page.size;
      if (page.size < DELETE_ALL_BATCH_SIZE) {
        break;
      }
    }

    return { deletedCount };
  },
);
