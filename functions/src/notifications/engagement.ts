import { onDocumentCreated, onDocumentWritten } from 'firebase-functions/v2/firestore';
import { FieldPath, Timestamp, type Firestore, type Transaction } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { isRestricted, toUserAccessState } from '../shared/access';
import { readFeatureFlag } from '../shared/featureFlags';
import { MAX_INSTANCES_TRIGGER_FANOUT, CPU_TRIGGER_FANOUT } from '../shared/instanceLimits';
import { chunk, geoCellsForRadius, FIRESTORE_IN_CHUNK } from '../incidents/incidents-core';
import { writeInAppNotification } from './deliver';
import type { InAppNotificationInput } from './notifications-core';
import {
  engagementId,
  nextEngagementBudget,
  epicWithinRange,
  EPIC_RADIUS_METERS,
  ENGAGEMENT_MAX_AGE_MS,
} from './engagement-core';

const OPTIONS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_TRIGGER_FANOUT,
  cpu: CPU_TRIGGER_FANOUT,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 120,
  retry: true,
};
const PAGE_SIZE = 100;
const millis = (v: unknown): number => (v instanceof Timestamp ? v.toMillis() : NaN);
function activeLive(data: FirebaseFirestore.DocumentData | undefined, nowMs: number) {
  return data?.status === 'active' && millis(data.expiresAt) > nowMs;
}

async function deliverEngagement(
  store: Firestore,
  uid: string,
  input: InAppNotificationInput,
  id: string,
  nowMs: number,
  guard: (tx: Transaction) => Promise<boolean>,
) {
  await store.runTransaction(async (tx) => {
    if (!(await guard(tx))) return;
    const budgetRef = store.doc(`userPrivate/${uid}/engagementBudgets/${input.category}`);
    const budget = nextEngagementBudget((await tx.get(budgetRef)).data(), nowMs);
    if (!budget) return;
    const result = await writeInAppNotification(uid, input, id, {
      transaction: tx,
      firestore: store,
    });
    // Opt-outs, deleted accounts and duplicate deliveries never spend the budget.
    if (result.delivered) tx.set(budgetRef, budget);
  });
}

/** Called only on the first position of a new session, not on position refreshes. */
export async function notifyFriendsLive(
  store: Firestore,
  uid: string,
  sessionId: string,
  nowMs = Date.now(),
) {
  const sourceRef = store.doc(`liveSessions/${uid}`);
  const friends = store.collection(`users/${uid}/friends`);
  let cursor: FirebaseFirestore.QueryDocumentSnapshot | undefined;
  for (;;) {
    let query = friends.orderBy(FieldPath.documentId()).limit(PAGE_SIZE);
    if (cursor) query = query.startAfter(cursor);
    const page = await query.get();
    for (const group of chunk(page.docs, 10)) {
      await Promise.all(
        group
          .filter((doc) => doc.id !== uid)
          .map(async (friend) => {
            const recipientUid = friend.id;
            await deliverEngagement(
              store,
              recipientUid,
              {
                category: 'friend_live',
                title: 'Det händer något på kartan',
                previewText:
                  'En av dina vänner har startat en live-session. Öppna CarCommunity och kika!',
                actionType: 'open_notifications',
                // No sender name, uid or session id in the notification or push payload.
              },
              `friend-live-${engagementId(uid, sessionId)}`,
              nowMs,
              async (tx) => {
                const [source, actor, forward, reverse, blockA, blockB] = await tx.getAll(
                  sourceRef,
                  store.doc(`users/${uid}`),
                  friend.ref,
                  store.doc(`users/${recipientUid}/friends/${uid}`),
                  store.doc(`userBlocks/${uid}/blocked/${recipientUid}`),
                  store.doc(`userBlocks/${recipientUid}/blocked/${uid}`),
                );
                const data = source!.data();
                return (
                  activeLive(data, nowMs) &&
                  data?.sessionId === sessionId &&
                  typeof data.refreshRequestedAtMs === 'number' &&
                  nowMs - data.refreshRequestedAtMs <= ENGAGEMENT_MAX_AGE_MS &&
                  actor!.exists &&
                  !isRestricted(toUserAccessState(actor!.data())) &&
                  forward!.exists &&
                  reverse!.exists &&
                  !blockA!.exists &&
                  !blockB!.exists
                );
              },
            );
          }),
      );
    }
    if (page.size < PAGE_SIZE) break;
    cursor = page.docs.at(-1);
  }
}

export const onFriendLiveStarted = onDocumentWritten(
  { ...OPTIONS, document: 'liveSessions/{uid}' },
  async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();
    if (!after || typeof after.sessionId !== 'string' || before?.sessionId === after.sessionId)
      return;
    if (!(await readFeatureFlag('liveLocation'))) return;
    await notifyFriendsLive(db, event.params.uid, after.sessionId);
  },
);

/** Uses only the existing, expiring, explicitly shared position; no new tracking. */
export async function notifyNearbyEpic(store: Firestore, spawnId: string, nowMs = Date.now()) {
  const crownRef = store.doc(`crownSpawns/${spawnId}`);
  const crown = (await crownRef.get()).data();
  if (
    !crown ||
    crown.rarity !== 'epic' ||
    crown.status !== 'live' ||
    millis(crown.expiresAt) <= nowMs ||
    !Number.isFinite(millis(crown.createdAt)) ||
    nowMs - millis(crown.createdAt) > ENGAGEMENT_MAX_AGE_MS ||
    !epicWithinRange(crown, crown)
  )
    return;
  const cells = geoCellsForRadius(crown.latitude, crown.longitude, EPIC_RADIUS_METERS);
  // Existing live clients and members with a private last-known foreground fix.
  // A shared budget and deterministic item id also deduplicate across these sources.
  for (const privatePosition of [false, true]) {
    for (const cellsChunk of chunk(cells, FIRESTORE_IN_CHUNK)) {
      let cursor: FirebaseFirestore.QueryDocumentSnapshot | undefined;
      for (;;) {
        const prefix = privatePosition ? 'epicNotificationLocation.' : '';
        let query = store
          .collection(privatePosition ? 'userPrivate' : 'liveSessions')
          .where(`${prefix}geoCell`, 'in', cellsChunk)
          .where(`${prefix}expiresAt`, '>', Timestamp.fromMillis(nowMs));
        if (!privatePosition) query = query.where('status', '==', 'active');
        query = query.orderBy(`${prefix}expiresAt`).limit(PAGE_SIZE);
        if (cursor) query = query.startAfter(cursor);
        const page = await query.get();
        for (const group of chunk(page.docs, 10)) {
          await Promise.all(
            group.map(async (recipient) => {
              const position = privatePosition
                ? recipient.get('epicNotificationLocation')
                : recipient.data();
              if (!epicWithinRange(crown, position ?? {})) return;
              await deliverEngagement(
                store,
                recipient.id,
                {
                  category: 'epic_crown',
                  title: 'En Epic-krona har dykt upp nära dig',
                  previewText:
                    'En Epic-krona har spawnat inom 3 km från din senast kända position. Öppna kartan för att se den!',
                  actionType: 'open_notifications',
                  relatedEntityId: spawnId,
                },
                `epic-crown-${engagementId(spawnId)}`,
                nowMs,
                async (tx) => {
                  const [currentCrown, currentPosition] = await tx.getAll(crownRef, recipient.ref);
                  const c = currentCrown!.data();
                  let p = privatePosition
                    ? currentPosition!.get('epicNotificationLocation')
                    : currentPosition!.data();
                  if (privatePosition) {
                    const live = (await tx.get(store.doc(`liveSessions/${recipient.id}`))).data();
                    if (
                      activeLive(live, nowMs) &&
                      typeof live?.refreshRequestedAtMs === 'number' &&
                      nowMs - live.refreshRequestedAtMs <= ENGAGEMENT_MAX_AGE_MS
                    )
                      p = live;
                  }
                  const fresh = privatePosition
                    ? millis(p?.expiresAt) > nowMs &&
                      (p?.status === 'active' ||
                        nowMs - millis(p?.recordedAt) <= 24 * 60 * 60 * 1000)
                    : activeLive(p, nowMs) &&
                      typeof p?.refreshRequestedAtMs === 'number' &&
                      nowMs - p.refreshRequestedAtMs <= ENGAGEMENT_MAX_AGE_MS;
                  return (
                    c?.rarity === 'epic' &&
                    c.status === 'live' &&
                    millis(c.expiresAt) > nowMs &&
                    nowMs - millis(c.createdAt) <= ENGAGEMENT_MAX_AGE_MS &&
                    fresh &&
                    epicWithinRange(c, p ?? {})
                  );
                },
              );
            }),
          );
        }
        if (page.size < PAGE_SIZE) break;
        cursor = page.docs.at(-1);
      }
    }
  }
}

export const onEpicCrownSpawned = onDocumentCreated(
  { ...OPTIONS, document: 'crownSpawns/{spawnId}' },
  async (event) => {
    if (event.data?.get('rarity') !== 'epic') return;
    if (!(await readFeatureFlag('crownHunt')) || !(await readFeatureFlag('crownHuntSpawn'))) return;
    await notifyNearbyEpic(db, event.params.spawnId);
  },
);
