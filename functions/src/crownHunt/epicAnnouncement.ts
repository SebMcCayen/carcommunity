import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { FieldValue, Timestamp, type Firestore } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { isRestricted, toUserAccessState } from '../shared/access';
import { MAX_INSTANCES_TRIGGER_FANOUT, CPU_TRIGGER_FANOUT } from '../shared/instanceLimits';
import {
  buildChatMessageDocument,
  chatMessageExpiry,
  COMMUNITY_CHAT_RETENTION_DAYS,
} from '../chatchannels/chat-core';
import { engagementId, ENGAGEMENT_MAX_AGE_MS } from '../notifications/engagement-core';

/** A successful claim is the authority. Failed attempts never announce a catch. */
export async function announceEpicClaim(store: Firestore, claimId: string, nowMs = Date.now()) {
  const claimRef = store.doc(`crownSpawnClaims/${claimId}`);
  await store.runTransaction(async (tx) => {
    const claim = (await tx.get(claimRef)).data();
    if (
      claim?.result !== 'awarded' ||
      claim.rarity !== 'epic' ||
      typeof claim.userId !== 'string' ||
      typeof claim.spawnId !== 'string' ||
      !(claim.claimedAt instanceof Timestamp) ||
      nowMs - claim.claimedAt.toMillis() > ENGAGEMENT_MAX_AGE_MS
    )
      return;
    const messageRef = store.doc(
      `communityChat/global/messages/epic-${engagementId(claim.spawnId, claim.userId)}`,
    );
    const [message, user] = await tx.getAll(messageRef, store.doc(`users/${claim.userId}`));
    if (message!.exists || !user!.exists || isRestricted(toUserAccessState(user!.data()))) return;
    const displayName =
      typeof user!.get('displayName') === 'string'
        ? (user!.get('displayName') as string)
        : 'En medlem';
    tx.create(messageRef, {
      ...buildChatMessageDocument(
        {
          senderUid: claim.userId,
          senderProfile: { displayName, avatarPath: null },
          text: `${displayName} fångade en Epic-krona! 👑`,
          expireAt: Timestamp.fromDate(
            chatMessageExpiry(new Date(nowMs), COMMUNITY_CHAT_RETENTION_DAYS),
          ),
        },
        () => FieldValue.serverTimestamp(),
      ),
      // Backend-only discriminator. post()'s strict input schema cannot forge it.
      systemEvent: 'epic_crown_claimed',
    });
  });
}

export const onEpicCrownClaimed = onDocumentCreated(
  {
    region: 'europe-west1',
    document: 'crownSpawnClaims/{claimId}',
    maxInstances: MAX_INSTANCES_TRIGGER_FANOUT,
    cpu: CPU_TRIGGER_FANOUT,
    concurrency: 1,
    memory: '256MiB',
    timeoutSeconds: 60,
    retry: true,
  },
  async (event) => {
    if (event.data?.get('result') !== 'awarded' || event.data?.get('rarity') !== 'epic') return;
    await announceEpicClaim(db, event.params.claimId);
  },
);
