/**
 * notifications-onNotificationCreated — FCM delivery for the in-app inbox.
 *
 * WHY A TRIGGER RATHER THAN EDITING EVERY PRODUCER
 * ------------------------------------------------
 * There are eight producers today (DM, community @mention, convoy chat, friend
 * request, convoy invite, admin warn, adminSend broadcast, inactivity warning)
 * and every one of them already funnels through writeInAppNotification. A
 * Firestore create trigger on `notifications/{uid}/items/{id}` attaches to the
 * OUTPUT of that funnel, which buys three things editing producers cannot:
 *
 *  1. The opt-out decision is inherited structurally. The document only exists
 *     because decideInAppDelivery already returned deliver:true — a member who
 *     silenced `convoy_chat` produces no document, so there is nothing for this
 *     trigger to fire on. Push cannot drift out of sync with the in-app rules
 *     because it is downstream of them, not beside them.
 *  2. Any future producer gets push for free and, more importantly, CANNOT
 *     forget to honour preferences.
 *  3. Push failures are isolated from the writer. FCM being slow or down must
 *     never fail a member's chat message; a separate trigger invocation retries
 *     and fails on its own.
 *
 * The decision is nonetheless re-derived here via decidePushDelivery (which
 * calls decideInAppDelivery internally) rather than assumed: state can change
 * between the inbox write and this invocation, and re-running it is what makes
 * "push is a subset of in-app" true by construction instead of by comment.
 *
 * Sends are data-only multicasts (see buildPushPayload) in batches of up to 500
 * tokens, and tokens FCM reports as dead are deleted from the registry in the
 * same pass so they cannot accumulate.
 */

import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { logger } from 'firebase-functions';
import { getMessaging, type TokenMessage } from 'firebase-admin/messaging';
import { db } from '../firebase';
import { toUserAccessState } from '../shared/access';
import { readFeatureFlag } from '../shared/featureFlags';
import {
  NOTIFICATION_CATEGORIES,
  PUSH_NOTIFICATIONS_FLAG_KEY,
  buildPushPayload,
  chunkTokens,
  decidePushDelivery,
  isDeadTokenError,
  isEssentialCategory,
  type NotificationCategory,
} from './notifications-core';
import { MAX_INSTANCES_TRIGGER_FANOUT } from '../shared/instanceLimits';

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * How long FCM should keep trying to deliver to a device that is offline.
 *
 * Social chatter is only useful while fresh — a day-old "new message" banner
 * arriving in a burst when the phone reconnects is noise, and the inbox item is
 * still there to read. Account notices are the opposite: a suspension or
 * warning must reach the member whenever they next come online, so those get
 * FCM's four-week maximum.
 */
function pushTtlMs(category: NotificationCategory): number {
  return isEssentialCategory(category) ? 28 * DAY_MS : DAY_MS;
}

/** Registry row we need to send: the raw token plus its doc id for cleanup. */
interface RegisteredToken {
  tokenId: string;
  token: string;
}

function toCategory(value: unknown): NotificationCategory | null {
  return typeof value === 'string' &&
    (NOTIFICATION_CATEGORIES as readonly string[]).includes(value)
    ? (value as NotificationCategory)
    : null;
}

function toStringOrNull(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null;
}

async function loadTokens(uid: string): Promise<RegisteredToken[]> {
  const snap = await db.collection('userPrivate').doc(uid).collection('pushTokens').get();
  const tokens: RegisteredToken[] = [];
  for (const doc of snap.docs) {
    const token = toStringOrNull(doc.data()?.token);
    // Registrations written before the raw token was stored are unsendable;
    // they are left alone and simply skipped (the device re-registers on next
    // launch, which upgrades the document in place).
    if (token) {
      tokens.push({ tokenId: doc.id, token });
    }
  }
  return tokens;
}

/**
 * Deletes registrations FCM rejected as permanently dead. Never throws.
 *
 * One batch is deliberate and safe. `tokenIds` is a subset of what loadTokens
 * returned, and registerPushToken caps a member at MAX_PUSH_TOKENS_PER_USER
 * (12) — so this commits at most 12 deletes. Firestore no longer imposes a
 * per-commit write COUNT limit (the once-quoted 500 applies to field
 * transformations on a single document); the live constraint is the 10 MiB
 * request size, which a dozen document-path deletes cannot approach. The cap,
 * not chunking here, is what keeps that true.
 */
async function pruneDeadTokens(uid: string, tokenIds: readonly string[]): Promise<void> {
  if (tokenIds.length === 0) return;
  try {
    const collection = db.collection('userPrivate').doc(uid).collection('pushTokens');
    const batch = db.batch();
    for (const tokenId of tokenIds) {
      batch.delete(collection.doc(tokenId));
    }
    await batch.commit();
    logger.info('Pruned dead push tokens', { count: tokenIds.length });
  } catch (error) {
    // Cleanup is opportunistic: a failure here must not fail the send.
    logger.warn('Failed to prune dead push tokens', {
      count: tokenIds.length,
      error: error instanceof Error ? error.message : String(error),
    });
  }
}

export const onNotificationCreated = onDocumentCreated(
  {
    region: 'europe-west1',
    // Above the ordinary trigger tier on purpose: one admin broadcast writes a
    // notification document per member, so this is the highest-volume trigger
    // here and a queued instance is a push that arrives late.
    maxInstances: MAX_INSTANCES_TRIGGER_FANOUT,
    document: 'notifications/{uid}/items/{notificationId}',
    memory: '256MiB',
    timeoutSeconds: 60,
  },
  async (event) => {
    const snapshot = event.data;
    if (!snapshot) return;

    const uid = event.params.uid;
    const notificationId = event.params.notificationId;
    const data = snapshot.data();

    const category = toCategory(data?.category);
    if (!category) {
      logger.warn('Notification created with unknown category — no push sent', { notificationId });
      return;
    }

    // Global push kill-switch FIRST — the cheapest possible bail. When push is
    // disabled platform-wide this returns before ANY per-recipient read (no
    // token read, no user/preference reads).
    if (!(await readFeatureFlag(PUSH_NOTIFICATIONS_FLAG_KEY))) {
      return;
    }

    // FAST-EXIT (push enabled) for a recipient with no registered device: push
    // is impossible without a token, so read the token registry NEXT and bail
    // before the eligibility reads (users/{uid} + userPrivate/{uid}) and the
    // decision. On this flag-enabled path a tokenless recipient — the whole
    // seeded test population, and any real member who never registered a device —
    // costs just the flag read plus one empty subcollection read, rather than
    // that plus two doc reads and the decision. This trigger is a per-recipient
    // fan-out (a broadcast — admin send, or the new "event created" notice —
    // writes one notification document PER member), so trimming the tokenless
    // majority keeps a broadcast from amplifying into a read storm. A member WHO
    // HAS a token takes the same path as before: the re-derived eligibility
    // decision below still runs before anything is sent.
    const tokens = await loadTokens(uid);
    if (tokens.length === 0) return;

    // Re-derive the SAME decision the inbox write made (decidePushDelivery
    // calls decideInAppDelivery), then apply the push-specific opt-out.
    const [userSnap, privateSnap] = await Promise.all([
      db.collection('users').doc(uid).get(),
      db.collection('userPrivate').doc(uid).get(),
    ]);
    if (!userSnap.exists) return;

    const preferences = privateSnap.data()?.notificationPreferences;
    const decision = decidePushDelivery(category, toUserAccessState(userSnap.data()), preferences);
    if (!decision.deliver) {
      logger.debug('Push suppressed', { category, reason: decision.reason });
      return;
    }

    const payload = buildPushPayload({
      category,
      title: toStringOrNull(data?.title) ?? '',
      previewText: toStringOrNull(data?.previewText) ?? '',
      notificationId,
      relatedEntityId: toStringOrNull(data?.relatedEntityId),
      recipientUid: uid,
      includePreview: decision.includePreview,
    });

    const messaging = getMessaging();
    const dead: string[] = [];
    let successCount = 0;

    for (const chunk of chunkTokens(tokens)) {
      const messages: TokenMessage[] = chunk.map((entry) => ({
        token: entry.token,
        data: payload,
        android: {
          ttl: pushTtlMs(category),
          priority: 'high',
        },
      }));

      let responses;
      try {
        // sendEach = ONE batched HTTP call for up to 500 messages, rather than
        // N single sends, and it reports per-token outcomes so dead tokens can
        // be identified. (sendMulticast is deprecated in favour of this.)
        responses = (await messaging.sendEach(messages)).responses;
      } catch (error) {
        // Whole-batch failure (network/credentials): retryable, so nothing is
        // pruned — deleting live tokens here would be unrecoverable.
        logger.error('FCM batch send failed', {
          category,
          size: chunk.length,
          error: error instanceof Error ? error.message : String(error),
        });
        continue;
      }

      responses.forEach((response, index) => {
        if (response.success) {
          successCount += 1;
          return;
        }
        const code = response.error?.code;
        const entry = chunk[index];
        if (entry && isDeadTokenError(code)) {
          dead.push(entry.tokenId);
        } else {
          logger.warn('Push send failed for one token', { category, code });
        }
      });
    }

    await pruneDeadTokens(uid, dead);

    logger.info('Push delivery complete', {
      category,
      attempted: tokens.length,
      delivered: successCount,
      pruned: dead.length,
    });
  },
);
