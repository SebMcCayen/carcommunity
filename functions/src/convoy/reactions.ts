/**
 * convoy.sendReaction — an ACCEPTED convoy member broadcasts a transient
 * REACTION (police alert / hello-goodbye / follow-me) to the rest of the convoy
 * (contracts/functions/functions.json).
 *
 * Deployed via the `convoy` export group (functions/src/index.ts) as
 * `convoy-sendReaction`. It writes ONE document to the convoy-scoped
 * `convoyChats/{convoyId}/reactions` subcollection — the SAME real-time channel
 * the convoy chat already uses, so every accepted member's existing per-convoy
 * Firestore listener delivers it, and each client pops a mid-screen animation for
 * a second or two. Nothing is stored long-term (a short `expireAt` TTL sweeps it)
 * and there is no notification fan-out: a reaction is a live nudge, not a message.
 *
 * Invariants:
 *  - Backend is the sole writer of convoyChats/{convoyId}/reactions
 *    (firebase/firestore.rules grants reads only to accepted convoy members via a
 *    get() of the convoy doc — the same gate as the messages subcollection — and
 *    denies all client writes).
 *  - Membership is re-checked by loading the convoy doc: a still-invited/declined
 *    member or a non-member is rejected (not-found for a missing convoy or an
 *    outsider, so a convoy can't be probed — parity with convoyChat.post).
 *  - ANTI-SPAM is SERVER-ENFORCED. Each send reads-and-writes a per-(convoy,
 *    member) cooldown document (convoyReactionCooldowns, backend-only) inside the
 *    SAME transaction that writes the reaction: a send arriving inside the kind's
 *    cooldown window is refused (resource-exhausted) and writes nothing. The
 *    police window is 60s; hello/follow-me are shorter (reaction-core
 *    REACTION_COOLDOWN_MS). The transaction is what makes the limit real under
 *    concurrency — two rapid taps serialise on the cooldown doc and the second
 *    loses. The client greys the button for the same window for UX, but this is
 *    the source of truth.
 *  - Idempotent on an optional `clientId` (verbatim as the reaction doc id): a
 *    retried optimistic send replays the committed reaction WITHOUT bumping the
 *    cooldown or re-broadcasting, so a flaky network never double-pops receivers.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import { requireAcceptedConvoyMember } from '../chatchannels/convoyMembership';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import {
  REACTION_NOT_DELIVERABLE_MESSAGE,
  REACTION_RATE_LIMITED_MESSAGE,
  buildReactionDocument,
  cooldownExpiry,
  isWithinReactionCooldown,
  parseSendReactionInput,
  reactionCooldownDocId,
  reactionCooldownRemainingMs,
  reactionExpiry,
  reactionLastSentField,
  type ConvoyReactionKind,
  type ReactionSenderProfile,
} from './reaction-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

function reactionsRef(convoyId: string) {
  return db.collection('convoyChats').doc(convoyId).collection('reactions');
}

function cooldownRef(convoyId: string, uid: string) {
  return db.collection('convoyReactionCooldowns').doc(reactionCooldownDocId(convoyId, uid));
}

/**
 * The sender's denormalized profile, off the convoy doc the membership gate
 * already loaded (no extra read). Reactions are cosmetic, so a missing profile
 * falls back to nulls rather than failing the send — the receiver just renders
 * the icon without a name.
 */
function senderProfileFrom(convoy: Record<string, unknown>, uid: string): ReactionSenderProfile {
  const profiles = convoy.memberProfiles as Record<string, unknown> | undefined;
  const profile = profiles?.[uid] as Record<string, unknown> | undefined;
  const displayName = typeof profile?.displayName === 'string' ? profile.displayName : null;
  const avatarPath = typeof profile?.avatarPath === 'string' ? profile.avatarPath : null;
  return { displayName, avatarPath };
}

export interface SendReactionResponse {
  reactionId: string;
}

export const sendReaction = onCall(CALLABLE_OPTS, async (request): Promise<SendReactionResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parseSendReactionInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { convoyId, kind, clientId } = parsed.input;

  const convoy = await requireAcceptedConvoyMember(convoyId, actor.uid);
  const senderProfile = senderProfileFrom(convoy, actor.uid);

  const reactionDocRef =
    clientId !== undefined ? reactionsRef(convoyId).doc(clientId) : reactionsRef(convoyId).doc();
  const cooldownDocRef = cooldownRef(convoyId, actor.uid);
  const lastSentField = reactionLastSentField(kind);

  const now = new Date();
  const nowMs = now.getTime();
  const createdAt = Timestamp.fromDate(now);

  // The cooldown check and the reaction write share ONE transaction so the limit
  // holds under concurrency: two rapid taps serialise on the cooldown doc, the
  // first commits (writing the reaction + stamping the cooldown), and the second
  // reads the fresh stamp and is refused. All reads precede all writes.
  const reactionId = await db.runTransaction(async (tx) => {
    // Idempotency: a retry with the same clientId replays the committed reaction
    // WITHOUT bumping the cooldown or writing again — so a network retry never
    // double-pops receivers or burns the sender's cooldown twice. A doc at this
    // id from a DIFFERENT sender is a key collision / buggy client: surface it.
    if (clientId !== undefined) {
      const existing = await tx.get(reactionDocRef);
      if (existing.exists) {
        if (existing.data()?.senderUid !== actor.uid) {
          throw new HttpsError('already-exists', 'Reaction id already used.');
        }
        return reactionDocRef.id;
      }
    }

    const cooldownSnap = await tx.get(cooldownDocRef);
    const lastSentAt = cooldownSnap.data()?.[lastSentField] as Timestamp | undefined;
    const lastSentAtMs = lastSentAt instanceof Timestamp ? lastSentAt.toMillis() : null;
    if (isWithinReactionCooldown(kind, lastSentAtMs, nowMs)) {
      // Hand the client the remaining time so its button greys for exactly the
      // right window rather than guessing the server's policy.
      throw new HttpsError('resource-exhausted', REACTION_RATE_LIMITED_MESSAGE, {
        kind,
        retryAfterMs: reactionCooldownRemainingMs(kind, lastSentAtMs, nowMs),
      });
    }

    tx.set(
      reactionDocRef,
      buildReactionDocument({
        kind: kind as ConvoyReactionKind,
        senderUid: actor.uid,
        senderProfile,
        createdAt,
        expireAt: reactionExpiry(now),
      }),
    );
    tx.set(
      cooldownDocRef,
      {
        convoyId,
        uid: actor.uid,
        [lastSentField]: createdAt,
        expireAt: cooldownExpiry(now),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );

    return reactionDocRef.id;
  });

  if (!reactionId) {
    // Unreachable in practice (the transaction always returns an id or throws);
    // narrows the type and refuses to invent a success.
    throw new HttpsError('aborted', REACTION_NOT_DELIVERABLE_MESSAGE);
  }

  return { reactionId };
});
