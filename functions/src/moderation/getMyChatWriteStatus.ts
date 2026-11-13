/** Own public-chat status, with server time for a monotonic client countdown. */
import { Timestamp } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import { z } from 'zod';
import { onCall } from '../access/callable';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { requireChatSender } from '../shared/chat-actor';
import { CPU_ADMIN, MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

export const getMyChatWriteStatus = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_MEMBER,
    cpu: CPU_ADMIN,
    concurrency: 1,
    memory: '256MiB',
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request) => {
    const actor = await requireActiveActor(request);
    if (
      !z
        .object({})
        .strict()
        .safeParse(request.data ?? {}).success
    )
      throw new HttpsError('invalid-argument', 'Endast den egna skrivstatusen kan hämtas.');
    return db.runTransaction(async (tx) => {
      // Check fresh account state but deliberately do not deny a paused member's
      // status read. PM/convoy writing also uses this private/account-only scope.
      await requireChatSender(tx, actor.uid, 'private');
      const restriction = await tx.get(db.doc(`carCommunityChatRestrictions/${actor.uid}`));
      const pauseUntil = restriction.get('pauseUntil');
      if (restriction.exists && !(pauseUntil instanceof Timestamp))
        throw new HttpsError('failed-precondition', 'Skrivbehörigheten kunde inte verifieras.');
      return {
        pauseUntilMillis: pauseUntil instanceof Timestamp ? pauseUntil.toMillis() : null,
        serverNowMillis: Date.now(),
        reason: typeof restriction.get('reason') === 'string' ? restriction.get('reason') : '',
      };
    });
  },
);
