/**
 * Pure logic for convoy REACTIONS — the transient, real-time "flash your lights"
 * broadcasts a member fires to the rest of their convoy (contracts/functions/
 * functions.json: convoy.sendReaction).
 *
 * A reaction is NOT a chat message: it is a short-lived event that pops a
 * mid-screen animation on every other member's map for a second or two and then
 * disappears. It rides the SAME real-time channel the convoy chat already uses —
 * a Firestore subcollection under the convoy-scoped `convoyChats/{convoyId}`
 * document (here: the sibling `reactions` subcollection) — so the client reuses
 * the accepted-member read gate and the per-convoy listener it already holds,
 * and no parallel presence system is invented. See functions/src/convoy/
 * reactions.ts for the callable and firebase/firestore.rules for the read gate.
 *
 * ANTI-SPAM: the police alert (and, more loosely, the others) is rate-limited
 * SERVER-SIDE. Every send reads-and-writes a per-(convoy, member) cooldown
 * document inside the same transaction that writes the reaction, refusing a send
 * that arrives inside the kind's cooldown window (resource-exhausted). Modeled on
 * the crownHunt perk-drain victim cooldown (functions/src/crownHunt/perks-core.ts
 * + pvp-drain.ts): a deterministic backend-only doc, an `expireAt` TTL field, and
 * a pure `isWithinReactionCooldown` predicate. The client greys the button for
 * the same window for UX, but the SERVER is the source of truth.
 *
 * Everything here is pure (no Firestore, no admin SDK) so the kind set, the
 * cooldown windows, the doc/payload builders and the parser are unit-testable
 * without the emulator — reaction-core.test.ts.
 */

import { z } from 'zod';
import { Timestamp } from 'firebase-admin/firestore';
import type { ParseResult } from '../chatchannels/chat-core';

// ---------------------------------------------------------------------------
// Kinds
// ---------------------------------------------------------------------------

/**
 * The three convoy reactions. Wire values are snake_case strings stored verbatim
 * on the reaction document and matched by the client to pick the icon/caption:
 *  - `police`    — "police ahead" alert (the rate-limited, anti-spam one).
 *  - `hello`     — the digital replacement for flashing your lights hello/goodbye.
 *  - `follow_me` — "follow me".
 */
export const CONVOY_REACTION_KINDS = ['police', 'hello', 'follow_me'] as const;

export type ConvoyReactionKind = (typeof CONVOY_REACTION_KINDS)[number];

export function isConvoyReactionKind(value: unknown): value is ConvoyReactionKind {
  return typeof value === 'string' && (CONVOY_REACTION_KINDS as readonly string[]).includes(value);
}

// ---------------------------------------------------------------------------
// Cooldowns (server-enforced anti-spam)
// ---------------------------------------------------------------------------

const SECOND_MS = 1_000;

/**
 * Per-kind cooldown windows, in milliseconds. A send of a kind is refused while
 * the member's previous send of THAT SAME kind is younger than its window — the
 * kinds do not share a budget, so a hello never blocks a police alert.
 *
 * Police is the strict one (the anti-spam requirement): once per 60s per member
 * per convoy — enough to warn a real hazard without letting the alert be
 * hammered into meaninglessness. Hello/follow-me are lighter social taps and get
 * shorter windows that still stop a jittery double-tap from firing twice.
 */
export const REACTION_COOLDOWN_MS: Record<ConvoyReactionKind, number> = {
  police: 60 * SECOND_MS,
  hello: 15 * SECOND_MS,
  follow_me: 30 * SECOND_MS,
};

/** The cooldown window for one kind, in milliseconds. */
export function reactionCooldownMs(kind: ConvoyReactionKind): number {
  return REACTION_COOLDOWN_MS[kind];
}

/**
 * True while a member is still inside the cooldown for [kind] — a send is
 * refused. `lastSentAtMs === null` (never sent this kind) is never in cooldown.
 * Guards against a non-finite stored value so a corrupt timestamp cannot wedge a
 * member out of ever sending again.
 */
export function isWithinReactionCooldown(
  kind: ConvoyReactionKind,
  lastSentAtMs: number | null,
  nowMs: number,
): boolean {
  if (lastSentAtMs === null || !Number.isFinite(lastSentAtMs)) return false;
  return nowMs - lastSentAtMs < reactionCooldownMs(kind);
}

/**
 * Milliseconds until a member may next send [kind], given their last send — 0
 * when they may send now. Surfaced to the client on a refusal so its button can
 * grey for exactly the remaining time rather than guessing.
 */
export function reactionCooldownRemainingMs(
  kind: ConvoyReactionKind,
  lastSentAtMs: number | null,
  nowMs: number,
): number {
  if (lastSentAtMs === null || !Number.isFinite(lastSentAtMs)) return 0;
  const remaining = reactionCooldownMs(kind) - (nowMs - lastSentAtMs);
  return remaining > 0 ? remaining : 0;
}

// ---------------------------------------------------------------------------
// Deterministic document ids + field names (backend-only collections)
// ---------------------------------------------------------------------------

/**
 * convoyReactionCooldowns doc id — one per (convoy, member). Scoped to the convoy
 * so leaving one convoy and reacting in another is never throttled by the other's
 * cooldown, and so the doc is naturally short-lived (it TTL-expires with the
 * convoy's activity). Both ids are validated (convoyId by the schema, uid by
 * requireMemberActor) so the join is Firestore-safe by construction.
 */
export function reactionCooldownDocId(convoyId: string, uid: string): string {
  return `${convoyId}__${uid}`;
}

/**
 * Field on the cooldown doc holding the last-send Timestamp for [kind]. One field
 * per kind, so each kind's window is independent and a single doc read/write per
 * send covers all of them.
 */
export function reactionLastSentField(kind: ConvoyReactionKind): string {
  return `lastSentAt_${kind}`;
}

// ---------------------------------------------------------------------------
// TTL
// ---------------------------------------------------------------------------

/**
 * How long a reaction document (and a cooldown doc) is retained before the
 * field-scoped Firestore TTL policy on `expireAt` sweeps it. Reactions are
 * consumed within seconds of arriving; a few minutes is ample slack for a client
 * that reconnects, and keeps the subcollection from accumulating. The cooldown
 * doc uses a longer window (it must outlive the longest cooldown) — see
 * `cooldownExpiry`.
 */
export const REACTION_RETENTION_MINUTES = 5;

/** The reaction document's `expireAt`, [REACTION_RETENTION_MINUTES] from now. */
export function reactionExpiry(now: Date): Timestamp {
  return Timestamp.fromMillis(now.getTime() + REACTION_RETENTION_MINUTES * 60 * SECOND_MS);
}

/**
 * The cooldown document's `expireAt`. Must comfortably outlive the longest
 * cooldown window so a doc is never swept while it is still throttling; an hour
 * past the last send is far more than any window, and a member reacting keeps
 * bumping it, so an active member's doc never expires under them.
 */
export function cooldownExpiry(now: Date): Timestamp {
  return Timestamp.fromMillis(now.getTime() + 60 * 60 * SECOND_MS);
}

// ---------------------------------------------------------------------------
// Reaction document payload
// ---------------------------------------------------------------------------

/** The sender's denormalized profile stamped on the reaction (no per-event lookup). */
export interface ReactionSenderProfile {
  displayName: string | null;
  avatarPath: string | null;
}

/**
 * The stored reaction document. Denormalizes the sender so the receiving client
 * can render "Anna: Polis" with no profile read, exactly like a chat message.
 * `createdAt` is a fixed logical time (not a server sentinel) so the client can
 * order and de-duplicate deterministically, and `expireAt` drives the TTL sweep.
 */
export interface ReactionDocument {
  kind: ConvoyReactionKind;
  senderUid: string;
  senderDisplayName: string | null;
  senderAvatarPath: string | null;
  createdAt: Timestamp;
  expireAt: Timestamp;
}

export function buildReactionDocument(params: {
  kind: ConvoyReactionKind;
  senderUid: string;
  senderProfile: ReactionSenderProfile;
  createdAt: Timestamp;
  expireAt: Timestamp;
}): ReactionDocument {
  return {
    kind: params.kind,
    senderUid: params.senderUid,
    senderDisplayName: params.senderProfile.displayName,
    senderAvatarPath: params.senderProfile.avatarPath,
    createdAt: params.createdAt,
    expireAt: params.expireAt,
  };
}

// ---------------------------------------------------------------------------
// Input parsing
// ---------------------------------------------------------------------------

const convoyIdSchema = z
  .string()
  .trim()
  .min(1)
  .max(300)
  .regex(/^[A-Za-z0-9._-]+$/)
  .refine((id) => id !== '.' && id !== '..');

const clientReactionIdSchema = z.string().regex(/^[A-Za-z0-9_-]{1,64}$/);

const sendReactionSchema = z
  .object({
    convoyId: convoyIdSchema,
    kind: z.enum(CONVOY_REACTION_KINDS),
    // Optional idempotency key: a retried optimistic send lands on the same
    // reaction doc (exactly-once), so a flaky network can't double-pop the
    // animation on receivers.
    clientId: clientReactionIdSchema.optional(),
  })
  .strict();

export type SendReactionInput = z.infer<typeof sendReactionSchema>;

export const SEND_REACTION_EXPECTED = `Expected { convoyId, kind, clientId? } where kind is one of ${CONVOY_REACTION_KINDS.join(
  ', ',
)} and clientId matches [A-Za-z0-9_-]{1,64}.`;

export function parseSendReactionInput(data: unknown): ParseResult<SendReactionInput> {
  const result = sendReactionSchema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: SEND_REACTION_EXPECTED };
  }
  return { ok: true, input: result.data };
}

/** User-facing messages (clients branch on the HttpsError code, never text). */
export const REACTION_RATE_LIMITED_MESSAGE = 'Slow down — that reaction is on cooldown.';
export const REACTION_NOT_DELIVERABLE_MESSAGE = 'This reaction cannot be sent right now.';
