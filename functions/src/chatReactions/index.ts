import { HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { onCall } from '../access/callable';
import { db } from '../firebase';
import { identityKeys, isCarCommunityRestricted } from '../access/carCommunity-core';
import { requireChatSender } from '../shared/chat-actor';
import { requireAcceptedConvoyMember } from '../chatchannels/convoyMembership';
import { requireChatParticipant } from '../events/chatParticipant';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import { MessageReactionEngine, type ReactionAuthority } from './engine';
import { parse, setSchema, mineSchema, unavailable } from './core';

export function reactionAuthority(request: CallableRequest): ReactionAuthority {
  return async (tx, uid, room, adding) => {
    if (request.auth?.uid !== uid) throw new HttpsError('unauthenticated', 'Sign in to continue.');
    if (room.scope === 'club') return unavailable();
    const [flags, policy, access] = await Promise.all([
      tx.get(db.doc('config/featureFlags')),
      tx.get(db.doc('carCommunityPolicy/access')),
      tx.get(db.doc(`carCommunityAccess/${uid}`)),
    ]);
    if (flags.get('chatReactions') !== true)
      throw new HttpsError('failed-precondition', 'Reactions unavailable.');
    if (policy.get('enforceInitialized') !== false) {
      const keys = identityKeys(access.data());
      const identities = keys.length
        ? await tx.getAll(...keys.map((key) => db.doc(`carCommunityIdentityBlocks/${key}`)))
        : [];
      if (
        isCarCommunityRestricted(
          access.data(),
          identities.map((item) => item.data() ?? {}),
        )
      )
        return unavailable();
    }
    await requireChatSender(
      tx,
      uid,
      adding && ['community', 'event'].includes(room.scope) ? 'public' : 'private',
    );
    if (room.scope === 'community' && room.roomId !== 'global') return unavailable();
    if (room.scope === 'convoy') await requireAcceptedConvoyMember(room.roomId, uid, tx);
    if (room.scope === 'event') {
      if (flags.get('chat') === false) return unavailable();
      await requireChatParticipant(request, room.roomId, tx);
    }
    if (room.scope === 'dm') {
      const conversation = await tx.get(db.doc(`conversations/${room.roomId}`));
      const members = conversation.get('members');
      if (
        !Array.isArray(members) ||
        members.length !== 2 ||
        new Set(members).size !== 2 ||
        !members.includes(uid) ||
        !members.every((item) => typeof item === 'string')
      )
        return unavailable();
      const other = members.find((item) => item !== uid) as string;
      const [friend, a, b] = await Promise.all([
        tx.get(db.doc(`users/${uid}/friends/${other}`)),
        tx.get(db.doc(`userBlocks/${uid}/blocked/${other}`)),
        tx.get(db.doc(`userBlocks/${other}/blocked/${uid}`)),
      ]);
      if (!friend.exists || a.exists || b.exists) return unavailable();
      await requireChatSender(tx, other);
    }
  };
}
const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};
export const set = onCall(CALLABLE_OPTS, async (request) => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Sign in to continue.');
  return new MessageReactionEngine(db, reactionAuthority(request)).set(
    request.auth.uid,
    parse(setSchema, request.data),
  );
});
export const getMine = onCall(CALLABLE_OPTS, async (request) => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Sign in to continue.');
  const input = parse(mineSchema, request.data);
  return new MessageReactionEngine(db, reactionAuthority(request)).getMine(
    request.auth.uid,
    input,
    input.messageIds,
  );
});
