import type { Transaction } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import { db } from '../firebase';
import { Timestamp } from 'firebase-admin/firestore';
import { isChatPauseActive } from '../admin/chatModeration-core';
import { isRestricted, toUserAccessState } from './access';

/** Serialize sends and receipt replay with canonical account revocation. */
export async function requireChatSender(
  tx: Transaction,
  uid: string,
  scope: 'public' | 'private' = 'private',
): Promise<void> {
  const user = await tx.get(db.collection('users').doc(uid));
  if (!user.exists || isRestricted(toUserAccessState(user.data())))
    throw new HttpsError('permission-denied', 'Account access is restricted.');
  // This document is deliberately NOT read for PM/convoys/shared Driver clubs.
  // The decision writes this same document, serializing a send with a new pause.
  if (scope === 'public') {
    const restriction = await tx.get(db.collection('carCommunityChatRestrictions').doc(uid));
    if (!restriction.exists) return;
    const pauseUntil = restriction.get('pauseUntil');
    if (!(pauseUntil instanceof Timestamp))
      throw new HttpsError('permission-denied', 'Skrivbehörigheten kunde inte verifieras.');
    if (isChatPauseActive(pauseUntil.toMillis(), Date.now()))
      throw new HttpsError(
        'permission-denied',
        'Ditt skrivande i Community- och eventchattar är pausat.',
        {
          reason: 'chat-write-paused',
          pauseUntilMillis: pauseUntil.toMillis(),
        },
      );
  }
}
