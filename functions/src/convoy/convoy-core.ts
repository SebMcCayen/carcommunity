/**
 * Convoy domain core (pure logic): input parsing, document builders, summary
 * computation, and client-summary mappers for the convoy callables
 * (convoy.create / convoy.respond / convoy.start / convoy.end / convoy.list /
 * convoy.leave / convoy.invite / convoy.setDestination / convoy.clearDestination).
 *
 * This is the FOUNDATION for the larger convoy + 3-channel-chat epic — chat
 * channels are a SEPARATE follow-up and are intentionally NOT modelled here.
 *
 * Data model (member-readable, backend-only writes — firebase/firestore.rules):
 *  - `convoys/{convoyId}` — one document per convoy:
 *      ownerUid: string
 *      title: string | null
 *      status: 'forming' | 'active' | 'ended'
 *      memberUids: string[]                 (owner + every invited uid; drives
 *                                            the array-contains list query AND
 *                                            the rules membership read gate)
 *      members: { [uid]: {                  (the authoritative membership map)
 *        uid, role: 'owner'|'member',
 *        inviteStatus: 'invited'|'accepted'|'declined',
 *        invitedAt: Timestamp,
 *        joinedAt: Timestamp | null } }      (set when the invitee accepts)
 *      memberProfiles: { [uid]: { displayName, avatarPath } }  (denormalized
 *                                            safe projection for the roster UI)
 *      createdAt: Timestamp
 *      startedAt: Timestamp | null           (set on convoy.start)
 *      endedAt: Timestamp | null             (set on convoy.end)
 *      destination: {                        (the SHARED destination — absent
 *        latitude, longitude,                 until a member sets one; SURVIVES
 *        label: string (ABSENT when the        convoy.end untouched as a record
 *          pick had none, never null),
 *        setByUid, setByDisplayName,          of where the convoy was headed)
 *        setAt: Timestamp } | absent
 *      summary: ConvoySummaryStats | null    (computed + stored on convoy.end,
 *                                            readable by ALL members)
 *
 * The owner is always members[owner] = { role:'owner', inviteStatus:'accepted' }.
 * Only FRIENDS of the owner (users/{owner}/friends/{uid}) may be invited, and
 * blocking is honoured both ways at invite time (blocked/non-friend invitees are
 * silently skipped).
 *
 * LIVE POSITIONS: convoys deliberately do NOT duplicate GPS storage. Each
 * accepted member shares their position through the existing live-location
 * domain (RTDB `liveLocation/{uid}/latest`, member-gated + block-enforced). The
 * convoy UI subscribes to that node for every accepted member — the uids to
 * read are surfaced as ConvoySummary.livePositionUids and the path helper is
 * liveLocationLatestPath below.
 *
 * Kept Firebase-free so it stays unit-testable without the emulator (mirrors
 * friends/friends-core.ts + dm/dm-core.ts). The callables in manageConvoy.ts own
 * all Firestore I/O, friendship + block checks, notifications, and transactions.
 */

import { z } from 'zod';

export const CONVOY_STATUSES = ['forming', 'active', 'ended'] as const;
export type ConvoyStatus = (typeof CONVOY_STATUSES)[number];

/**
 * The convoy statuses that count as "live" for the one-convoy-at-a-time rule:
 * a convoy still being assembled (`forming`) or on the road (`active`). An
 * `ended` convoy is history and never blocks a new one. This is the status set
 * the "am I already in a convoy" query filters on (convoy.create /
 * convoy.respond in manageConvoy.ts).
 */
export const ACTIVE_CONVOY_STATUSES = ['forming', 'active'] as const;

export const CONVOY_MEMBER_ROLES = ['owner', 'member'] as const;
export type ConvoyMemberRole = (typeof CONVOY_MEMBER_ROLES)[number];

export const CONVOY_INVITE_STATUSES = ['invited', 'accepted', 'declined'] as const;
export type ConvoyInviteStatus = (typeof CONVOY_INVITE_STATUSES)[number];

/** Max length of the optional convoy title. */
export const CONVOY_TITLE_MAX_LENGTH = 80;

/** Upper bound on invitees per convoy.create call (guards fan-out cost). */
export const MAX_CONVOY_INVITEES = 50;

/**
 * Hard ceiling on TOTAL convoy membership (memberUids length — owner + every
 * invited/accepted member), enforced by convoy.invite.
 *
 * 25 is chosen from the physical thing being modelled rather than from a
 * database limit: a convoy is a line of cars driving together, and 25 cars is
 * already an unusually large local meet — beyond that the group stops behaving
 * like one convoy on the road. It also bounds the two costs that grow with
 * membership: the live-position fan-out is one subscription per member PER
 * member (each member's map subscribes to every other accepted member's RTDB
 * node — 25 members is 600 edges, 50 would be 2450), and the invite
 * notification fan-out is one write per invitee.
 *
 * convoy.create is deliberately NOT retro-capped by this: its own
 * MAX_CONVOY_INVITEES bound is the shipped contract, and tightening it would
 * break calls that work today. This cap governs GROWTH, which is what invite
 * does.
 */
export const MAX_CONVOY_SIZE = 25;

/**
 * Upper bound on invitees per convoy.invite call. Equal to MAX_CONVOY_SIZE
 * because a single call can never usefully add more than a whole convoy's worth
 * of people — anything larger is a client bug, and rejecting it at the schema
 * costs nothing.
 */
export const MAX_CONVOY_INVITE_BATCH = 25;

/**
 * Max length of the optional shared-destination label. 120 chars is long enough
 * for a full formatted street address and short enough that the field cannot
 * become a second chat channel. Over-length is REJECTED, never truncated (a
 * silently shortened address is a wrong address).
 */
export const CONVOY_DESTINATION_LABEL_MAX_LENGTH = 120;

/** Upper bound on convoys returned by convoy.list (bounded read safety). */
export const MAX_CONVOYS_RETURNED = 200;

/**
 * Minimal safe profile projection denormalized onto convoy documents and
 * returned to clients. Never includes email, provider identity, subscription,
 * moderation, or other sensitive fields (mirrors friends/dm ProfileProjection).
 */
export interface ProfileProjection {
  displayName: string | null;
  avatarPath: string | null;
}

/** A convoy member as surfaced to the caller's UI (green-dot = inviteStatus). */
export interface ConvoyMemberSummary {
  uid: string;
  role: ConvoyMemberRole;
  inviteStatus: ConvoyInviteStatus;
  joinedAt: string | null;
  displayName: string | null;
  avatarPath: string | null;
}

/** Post-convoy summary, computed + stored on convoy.end (all members read it). */
export interface ConvoySummaryStats {
  durationSeconds: number;
  participantUids: string[];
  participantCount: number;
  /**
   * Null in this foundation: the convoy backend does not aggregate a shared
   * route. Members' individual drives can be persisted via drives.save; a
   * convoy-scoped route roll-up is a possible follow-up.
   */
  distanceMeters: number | null;
}

/**
 * The convoy's SHARED DESTINATION as carried on the convoy document and
 * serialized into every ConvoySummary.
 *
 * Hung off the summary rather than exposed as a separate read: members already
 * receive the summary through the one convoy read path, so the destination
 * arrives with everything else and no client grows a second source of truth.
 * `setByUid`/`setAt` are SERVER-stamped (a client-chosen setter uid would let
 * someone attribute a destination to another member) and `setByDisplayName` is
 * denormalized exactly like `members[].displayName`, so the UI can attribute it
 * without a profile fetch per render.
 */
export interface ConvoyDestination {
  latitude: number;
  longitude: number;
  /** Human-readable place name; null when the pick had none (map long-press). */
  label: string | null;
  setByUid: string;
  setByDisplayName: string | null;
  setAt: string | null;
}

/** One convoy as returned by convoy.list / convoy.create (the wire contract). */
export interface ConvoySummary {
  convoyId: string;
  ownerUid: string;
  title: string | null;
  status: ConvoyStatus;
  members: ConvoyMemberSummary[];
  memberUids: string[];
  /** The caller's own membership (role + inviteStatus), or null if not a member. */
  viewer: { role: ConvoyMemberRole; inviteStatus: ConvoyInviteStatus } | null;
  /**
   * Accepted members (owner included) whose live position the map should
   * subscribe to at RTDB liveLocation/{uid}/latest (liveLocationLatestPath).
   */
  livePositionUids: string[];
  /**
   * The shared destination every member navigates to, or null when none is set.
   * SURVIVES convoy.end untouched — it is part of the record of where the
   * convoy was headed, and the UI simply stops rendering destination controls
   * for an ended convoy.
   */
  destination: ConvoyDestination | null;
  summary: ConvoySummaryStats | null;
  createdAt: string | null;
  startedAt: string | null;
  endedAt: string | null;
}

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

/** Firebase Auth UIDs are opaque, non-empty, bounded strings. */
const uidSchema = z.string().trim().min(1).max(128);

/** Firestore-safe convoy id (auto-generated; validated on the respond/start/end path). */
const convoyIdSchema = z
  .string()
  .trim()
  .min(1)
  .max(300)
  .regex(/^[A-Za-z0-9._-]+$/)
  .refine((id) => id !== '.' && id !== '..');

const createConvoySchema = z
  .object({
    title: z.string().trim().min(1).max(CONVOY_TITLE_MAX_LENGTH).optional(),
    inviteeUids: z.array(uidSchema).min(1).max(MAX_CONVOY_INVITEES),
  })
  .strict();

export type CreateConvoyInput = z.infer<typeof createConvoySchema>;

const respondConvoySchema = z
  .object({
    convoyId: convoyIdSchema,
    action: z.enum(['accept', 'decline']),
  })
  .strict();

export type RespondConvoyInput = z.infer<typeof respondConvoySchema>;

const convoyIdInputSchema = z.object({ convoyId: convoyIdSchema }).strict();

export type ConvoyIdInput = z.infer<typeof convoyIdInputSchema>;

const inviteToConvoySchema = z
  .object({
    convoyId: convoyIdSchema,
    inviteeUids: z.array(uidSchema).min(1).max(MAX_CONVOY_INVITE_BATCH),
  })
  .strict();

export type InviteToConvoyInput = z.infer<typeof inviteToConvoySchema>;

/**
 * z.number() already rejects NaN, but NOT Infinity — and both survive a JSON
 * round-trip in some clients. An infinite coordinate would poison every
 * downstream distance computation, so finiteness is checked explicitly before
 * the range check.
 */
const finiteNumber = z.number().refine((n) => Number.isFinite(n));

const setConvoyDestinationSchema = z
  .object({
    convoyId: convoyIdSchema,
    latitude: finiteNumber.pipe(z.number().min(-90).max(90)),
    longitude: finiteNumber.pipe(z.number().min(-180).max(180)),
    // Trimmed, bounded, and blank-after-trim collapses to undefined so a blank
    // label is stored as ABSENT rather than as an empty string.
    label: z
      .string()
      .trim()
      .max(CONVOY_DESTINATION_LABEL_MAX_LENGTH)
      .transform((value) => (value.length === 0 ? undefined : value))
      .optional(),
  })
  .strict();

export type SetConvoyDestinationInput = z.infer<typeof setConvoyDestinationSchema>;

const listConvoysSchema = z.object({}).strict();

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export const CREATE_CONVOY_EXPECTED = `Expected { inviteeUids: [uid] (1..${MAX_CONVOY_INVITEES}), title? (1..${CONVOY_TITLE_MAX_LENGTH}) }.`;
export const RESPOND_CONVOY_EXPECTED = "Expected { convoyId, action: 'accept'|'decline' }.";
export const CONVOY_ID_EXPECTED = 'Expected { convoyId }.';

export function parseCreateConvoyInput(data: unknown): ParseResult<CreateConvoyInput> {
  return parse(createConvoySchema, data, CREATE_CONVOY_EXPECTED);
}

export function parseRespondConvoyInput(data: unknown): ParseResult<RespondConvoyInput> {
  return parse(respondConvoySchema, data, RESPOND_CONVOY_EXPECTED);
}

export function parseConvoyIdInput(data: unknown): ParseResult<ConvoyIdInput> {
  return parse(convoyIdInputSchema, data, CONVOY_ID_EXPECTED);
}

export function parseListConvoysInput(data: unknown): ParseResult<Record<string, never>> {
  return parse(listConvoysSchema, data, 'Expected an empty object.');
}

export const INVITE_TO_CONVOY_EXPECTED = `Expected { convoyId, inviteeUids: [uid] (1..${MAX_CONVOY_INVITE_BATCH}) }.`;
// `label` is described as the schema actually behaves: trimmed, bounded above,
// and blank-after-trim ACCEPTED (stored absent) rather than rejected. Saying
// "1..max" would send a caller hunting for a length problem they don't have
// when the invalid-argument came from a coordinate.
export const SET_CONVOY_DESTINATION_EXPECTED = `Expected { convoyId, latitude (-90..90), longitude (-180..180), label? (trimmed, max ${CONVOY_DESTINATION_LABEL_MAX_LENGTH}; blank is accepted and stored as no label) }.`;

export function parseInviteToConvoyInput(data: unknown): ParseResult<InviteToConvoyInput> {
  return parse(inviteToConvoySchema, data, INVITE_TO_CONVOY_EXPECTED);
}

export function parseSetConvoyDestinationInput(
  data: unknown,
): ParseResult<SetConvoyDestinationInput> {
  return parse(setConvoyDestinationSchema, data, SET_CONVOY_DESTINATION_EXPECTED);
}

/** User-facing messages (clients branch on the HttpsError code, never text). */
export const CONVOY_NOT_FOUND_MESSAGE = 'Convoy not found.';
export const NOT_INVITED_MESSAGE = 'You have no pending invite for this convoy.';
export const INVITE_ALREADY_HANDLED_MESSAGE = 'This invite has already been answered.';
export const CONVOY_ENDED_MESSAGE = 'This convoy has ended.';
export const CONVOY_NOT_FORMING_MESSAGE = 'This convoy can no longer be started.';
export const CONVOY_ALREADY_ENDED_MESSAGE = 'This convoy has already ended.';
export const NO_VALID_INVITEES_MESSAGE = 'No one could be added to the convoy.';
export const OWNER_CANNOT_LEAVE_MESSAGE =
  'The convoy owner cannot leave — end the convoy instead.';
export const NOT_ACCEPTED_MEMBER_MESSAGE = 'You have not joined this convoy.';
export const CONVOY_FULL_MESSAGE = `A convoy can hold at most ${MAX_CONVOY_SIZE} people.`;
export const DESTINATION_CLEAR_FORBIDDEN_MESSAGE =
  'Only the member who set the destination, or the convoy owner, can clear it.';
export const ALREADY_IN_CONVOY_MESSAGE =
  'You are already in a convoy. Leave or end it before joining another.';

/**
 * Why a requested invitee was skipped by convoy.create. There is deliberately NO
 * `blocked` reason: a block edge (in either direction) is surfaced as the neutral
 * `not_found`, identical to a missing/non-member invitee, so the inviter can't
 * infer who blocked whom (privacy parity with friends/dm).
 *
 * `already_member` is only ever produced by convoy.invite (the invitee is
 * already in this convoy's memberUids — invited, accepted, or declined). It is
 * NOT a privacy leak: the inviter is themselves a member and can already see
 * the roster. It is kept distinct from `duplicate` (which means "listed twice
 * in THIS request") so the client can say the honest thing.
 */
export type InviteeSkipReason =
  | 'self'
  | 'not_friend'
  | 'not_found'
  | 'duplicate'
  | 'already_member';

export interface SkippedInvitee {
  uid: string;
  reason: InviteeSkipReason;
}

// ---------------------------------------------------------------------------
// Peer block resolution (convoy.invite fan-out)
// ---------------------------------------------------------------------------

/**
 * How many uids may be asked for in ONE blocked-subcollection lookup.
 *
 * 30 is not a taste call: it is Firestore's hard limit on the number of
 * disjunction values in a `documentId() in [...]` query. It is the largest
 * chunk the query API will accept, so it is the smallest number of queries the
 * matrix can be answered in.
 *
 * Both convoy bounds already sit under it — MAX_CONVOY_INVITE_BATCH is 25 and a
 * convoy holds at most MAX_CONVOY_SIZE (25) members, so at most 24 accepted
 * peers — which means today every lookup is exactly one chunk. The chunking is
 * kept anyway so that raising either constant degrades into more queries rather
 * than into an INVALID_ARGUMENT at runtime.
 */
export const BLOCK_LOOKUP_CHUNK_SIZE = 30;

/** Splits into chunks of at most `size` (never emits an empty chunk). */
export function chunkUids(uids: string[], size: number): string[][] {
  const chunks: string[][] = [];
  for (let i = 0; i < uids.length; i += size) {
    chunks.push(uids.slice(i, i + size));
  }
  return chunks;
}

/** Directed block-edge key: `blocker|blocked`. */
export function blockPairKey(blockerUid: string, blockedUid: string): string {
  return `${blockerUid}|${blockedUid}`;
}

/**
 * Resolves which of the candidate × peer pairs have a block edge in EITHER
 * direction, in a number of reads that grows with candidates + peers rather
 * than with candidates × peers.
 *
 * The naive shape — a point read of `userBlocks/{a}/blocked/{b}` for both
 * directions of every pair — costs 2·C·P reads: at the shipped bounds (25
 * invitees, 24 accepted peers) that is 1200 document reads inside a single
 * callable, on top of the per-invitee friend/profile reads. Firestore bills a
 * `documentId() in [...]` query by the documents it RETURNS (an empty result
 * still bills one read), so asking one blocker for all the uids it might have
 * blocked collapses a whole row (or column) of the matrix into one read in the
 * common case where nobody has blocked anyone. That makes the cost
 * C + P (≈49 here) instead of 2·C·P.
 *
 * The Firestore access is injected (`queryBlocked`) rather than imported, so
 * the read COUNT is observable in a unit test — an outcome-only assertion would
 * pass just as happily against the 1200-read version.
 *
 * @param queryBlocked given a blocker uid and up to BLOCK_LOOKUP_CHUNK_SIZE
 *   candidate uids, returns the subset that blocker has blocked.
 */
export async function resolvePeerBlockPairs(
  candidateUids: string[],
  peerUids: string[],
  queryBlocked: (blockerUid: string, blockedUids: string[]) => Promise<string[]>,
): Promise<Set<string>> {
  const candidates = [...new Set(candidateUids)];
  // Deliberately NOT filtered against `candidates`: a requested uid that is
  // also an existing member must still be block-checked as a PEER of the other
  // candidates, or a uid appearing on both sides would silently disable the
  // peer check for everyone else in the batch.
  const peers = [...new Set(peerUids)];
  if (candidates.length === 0 || peers.length === 0) {
    return new Set<string>();
  }

  const lookups: Array<Promise<Array<[string, string]>>> = [];
  const enqueue = (blockerUid: string, blocked: string[]) => {
    for (const ids of chunkUids(blocked, BLOCK_LOOKUP_CHUNK_SIZE)) {
      lookups.push(
        queryBlocked(blockerUid, ids).then((hits) =>
          hits.map((hit): [string, string] => [blockerUid, hit]),
        ),
      );
    }
  };
  // Direction 1: did the candidate block any peer?  Direction 2: did any peer
  // block the candidate?  Both are needed — a block is honoured whichever way
  // round it was made.
  for (const candidate of candidates) enqueue(candidate, peers);
  for (const peer of peers) enqueue(peer, candidates);

  const pairs = new Set<string>();
  for (const found of await Promise.all(lookups)) {
    for (const [blocker, blocked] of found) {
      pairs.add(blockPairKey(blocker, blocked));
    }
  }
  return pairs;
}

/** True when a block edge exists in either direction between `uid` and any peer. */
export function isBlockedAgainstAnyPeer(
  uid: string,
  peerUids: string[],
  pairs: Set<string>,
): boolean {
  return peerUids.some(
    (peerUid) => pairs.has(blockPairKey(uid, peerUid)) || pairs.has(blockPairKey(peerUid, uid)),
  );
}

/** Reads a profile doc into the minimal safe projection (missing → null). */
export function toProfileProjection(doc: Record<string, unknown> | undefined): ProfileProjection {
  const displayName = doc?.displayName;
  const avatarPath = doc?.avatarPath;
  return {
    displayName: typeof displayName === 'string' ? displayName : null,
    avatarPath: typeof avatarPath === 'string' ? avatarPath : null,
  };
}

/**
 * RTDB path of a member's latest live-location marker. The convoy UI reads this
 * (per accepted member) rather than the convoy duplicating any GPS storage —
 * the live-location domain already enforces the member gate + blocking on it.
 */
export function liveLocationLatestPath(uid: string): string {
  return `liveLocation/${uid}/latest`;
}

/** One entry of the convoys/{id}.members map. */
export function buildMemberEntry(
  uid: string,
  role: ConvoyMemberRole,
  inviteStatus: ConvoyInviteStatus,
  serverTimestamp: () => unknown,
  joined: boolean,
): Record<string, unknown> {
  const ts = serverTimestamp();
  return {
    uid,
    role,
    inviteStatus,
    invitedAt: ts,
    joinedAt: joined ? ts : null,
  };
}

/**
 * Full convoys/{convoyId} document for a brand-new convoy. The owner is seeded
 * as an accepted owner-role member; every accepted invitee is seeded as an
 * invited member-role entry. `memberUids` carries the owner + all invitees for
 * the array-contains read + the rules membership gate.
 */
export function buildConvoyDocument(
  input: {
    ownerUid: string;
    title: string | null;
    ownerProfile: ProfileProjection;
    invitees: Array<{ uid: string; profile: ProfileProjection }>;
  },
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  const ts = serverTimestamp();
  const memberUids = [input.ownerUid, ...input.invitees.map((i) => i.uid)];

  const members: Record<string, unknown> = {
    [input.ownerUid]: buildMemberEntry(input.ownerUid, 'owner', 'accepted', serverTimestamp, true),
  };
  const memberProfiles: Record<string, unknown> = {
    [input.ownerUid]: {
      displayName: input.ownerProfile.displayName,
      avatarPath: input.ownerProfile.avatarPath,
    },
  };
  for (const invitee of input.invitees) {
    members[invitee.uid] = buildMemberEntry(invitee.uid, 'member', 'invited', serverTimestamp, false);
    memberProfiles[invitee.uid] = {
      displayName: invitee.profile.displayName,
      avatarPath: invitee.profile.avatarPath,
    };
  }

  return {
    ownerUid: input.ownerUid,
    title: input.title,
    status: 'forming' satisfies ConvoyStatus,
    memberUids,
    members,
    memberProfiles,
    summary: null,
    createdAt: ts,
    startedAt: null,
    endedAt: null,
  };
}

/** True when `uid` is one of the convoy's stored members (owner or invitee). */
export function isConvoyMember(data: Record<string, unknown> | undefined, uid: string): boolean {
  const memberUids = Array.isArray(data?.memberUids) ? (data!.memberUids as unknown[]) : [];
  return memberUids.includes(uid);
}

/** The stored membership entry for `uid`, or undefined. */
export function memberEntry(
  data: Record<string, unknown> | undefined,
  uid: string,
): Record<string, unknown> | undefined {
  const members = (data?.members ?? {}) as Record<string, Record<string, unknown> | undefined>;
  return members[uid];
}

/**
 * True when `uid` is a member whose invite is ACCEPTED (the owner is seeded
 * accepted). This — not bare membership — is the gate for the actions that act
 * on the group: invite, setDestination, leave.
 */
export function isAcceptedConvoyMember(
  data: Record<string, unknown> | undefined,
  uid: string,
): boolean {
  return memberEntry(data, uid)?.inviteStatus === 'accepted';
}

/**
 * True when `uid` is an ACTIVE PARTICIPANT of this convoy — the precise
 * definition the one-convoy-at-a-time rule (item 1) gates on:
 *
 *  - the convoy is not `ended` (a finished convoy is history, never a conflict), AND
 *  - the caller is an ACCEPTED member of it. The owner is seeded
 *    members[owner]={role:'owner',inviteStatus:'accepted'}, so this covers "the
 *    leader who started it"; an accepted invitee covers "a member who accepted
 *    an invite". A merely-INVITED (still-pending) or DECLINED member is NOT an
 *    active participant — they have not committed to this convoy, so it must not
 *    stop them creating or accepting elsewhere.
 *
 * This is the per-document predicate; the callable pairs it with a query over
 * convoys the caller belongs to (memberUids array-contains) filtered to
 * ACTIVE_CONVOY_STATUSES, and runs both inside the membership-writing
 * transaction so two concurrent creates/accepts cannot both slip through.
 */
export function isActiveConvoyParticipant(
  data: Record<string, unknown> | undefined,
  uid: string,
): boolean {
  return data?.status !== 'ended' && isAcceptedConvoyMember(data, uid);
}

/**
 * The document patch that removes `uid` from a convoy (convoy.leave).
 *
 * The member is removed OUTRIGHT rather than flagged `left`: dropping out of
 * `memberUids` is what actually revokes their Firestore read on the convoy doc
 * AND their convoy-chat access (both rules gate on memberUids), and it takes
 * them out of `livePositionUids` so the rest of the group stops subscribing to
 * a car that is no longer there. A tombstoned `left` entry would keep all three.
 *
 * The three collections are rewritten WHOLE (not patched key-by-key) so the
 * caller can write them in one atomic update — a convoy whose memberUids and
 * members map disagree is exactly the state every gate in this domain reads.
 *
 * Returns the remaining ACCEPTED member count so the callable can report it
 * without re-deriving membership.
 */
export function buildLeaveConvoyUpdate(
  data: Record<string, unknown>,
  uid: string,
): {
  memberUids: string[];
  members: Record<string, unknown>;
  memberProfiles: Record<string, unknown>;
  remainingAcceptedCount: number;
} {
  const memberUids = (Array.isArray(data.memberUids) ? (data.memberUids as string[]) : []).filter(
    (candidate) => candidate !== uid,
  );
  const members = { ...((data.members ?? {}) as Record<string, unknown>) };
  delete members[uid];
  const memberProfiles = { ...((data.memberProfiles ?? {}) as Record<string, unknown>) };
  delete memberProfiles[uid];

  const remainingAcceptedCount = Object.values(members).filter(
    (entry) => !!entry && (entry as Record<string, unknown>).inviteStatus === 'accepted',
  ).length;

  return { memberUids, members, memberProfiles, remainingAcceptedCount };
}

/** Uids of accepted members (owner included) — the live-position subscription set. */
export function acceptedMemberUids(data: Record<string, unknown> | undefined): string[] {
  const members = (data?.members ?? {}) as Record<string, Record<string, unknown> | undefined>;
  return Object.values(members)
    .filter((m): m is Record<string, unknown> => !!m && m.inviteStatus === 'accepted')
    .map((m) => String(m.uid));
}

/**
 * Computes the end-of-convoy summary. Duration spans startedAt→endedAt (falling
 * back to createdAt when the convoy was ended straight from 'forming');
 * participants are the accepted members. Distance is null (no shared-route
 * aggregation in this foundation — see ConvoySummaryStats).
 */
export function computeConvoySummary(
  data: Record<string, unknown>,
  endedAt: Date,
  toDate: (value: unknown) => Date | null,
): ConvoySummaryStats {
  const started = toDate(data.startedAt) ?? toDate(data.createdAt) ?? endedAt;
  const durationSeconds = Math.max(0, Math.round((endedAt.getTime() - started.getTime()) / 1000));
  const participantUids = acceptedMemberUids(data);
  return {
    durationSeconds,
    participantUids,
    participantCount: participantUids.length,
    distanceMeters: null,
  };
}

/**
 * Maps the stored `destination` field into the wire shape, or null when absent
 * or structurally unusable.
 *
 * A stored destination missing a finite lat/lng is treated as ABSENT rather
 * than surfaced with a coerced 0/0 coordinate — silently handing a driver the
 * Gulf of Guinea is worse than showing no destination at all.
 */
export function toConvoyDestination(
  value: unknown,
  toIso: (value: unknown) => string | null,
): ConvoyDestination | null {
  if (!value || typeof value !== 'object') return null;
  const raw = value as Record<string, unknown>;
  const latitude = raw.latitude;
  const longitude = raw.longitude;
  if (typeof latitude !== 'number' || !Number.isFinite(latitude)) return null;
  if (typeof longitude !== 'number' || !Number.isFinite(longitude)) return null;
  return {
    latitude,
    longitude,
    label: typeof raw.label === 'string' && raw.label.length > 0 ? raw.label : null,
    setByUid: typeof raw.setByUid === 'string' ? raw.setByUid : '',
    setByDisplayName: typeof raw.setByDisplayName === 'string' ? raw.setByDisplayName : null,
    setAt: toIso(raw.setAt),
  };
}

function toMemberSummary(
  entry: Record<string, unknown>,
  profiles: Record<string, Record<string, unknown> | undefined>,
  toIso: (value: unknown) => string | null,
): ConvoyMemberSummary {
  const uid = String(entry.uid);
  const profile = toProfileProjection(profiles[uid]);
  const role: ConvoyMemberRole = entry.role === 'owner' ? 'owner' : 'member';
  const inviteStatus = CONVOY_INVITE_STATUSES.includes(entry.inviteStatus as ConvoyInviteStatus)
    ? (entry.inviteStatus as ConvoyInviteStatus)
    : 'invited';
  return {
    uid,
    role,
    inviteStatus,
    joinedAt: toIso(entry.joinedAt),
    displayName: profile.displayName,
    avatarPath: profile.avatarPath,
  };
}

/**
 * Maps a stored convoy doc into the caller-oriented summary: the full roster
 * (each member's inviteStatus for the green dot), the caller's own membership,
 * the accepted-member live-position set, and the summary when ended. `toIso`
 * converts a stored timestamp value to an ISO string (or null).
 */
export function toConvoySummary(
  convoyId: string,
  data: Record<string, unknown>,
  callerUid: string,
  toIso: (value: unknown) => string | null,
): ConvoySummary {
  const profiles = (data.memberProfiles ?? {}) as Record<string, Record<string, unknown> | undefined>;
  const membersMap = (data.members ?? {}) as Record<string, Record<string, unknown> | undefined>;
  const members = Object.values(membersMap)
    .filter((m): m is Record<string, unknown> => !!m && typeof m.uid === 'string')
    .map((entry) => toMemberSummary(entry, profiles, toIso))
    .sort((a, b) => {
      // Owner first, then by uid for a stable roster order.
      if (a.role !== b.role) return a.role === 'owner' ? -1 : 1;
      return a.uid.localeCompare(b.uid);
    });

  const memberUids = Array.isArray(data.memberUids) ? (data.memberUids as string[]) : [];
  const ownEntry = membersMap[callerUid];
  const viewer = ownEntry
    ? {
        role: (ownEntry.role === 'owner' ? 'owner' : 'member') as ConvoyMemberRole,
        inviteStatus: (CONVOY_INVITE_STATUSES.includes(ownEntry.inviteStatus as ConvoyInviteStatus)
          ? ownEntry.inviteStatus
          : 'invited') as ConvoyInviteStatus,
      }
    : null;

  const rawSummary = data.summary as Record<string, unknown> | null | undefined;
  const summary: ConvoySummaryStats | null =
    rawSummary && typeof rawSummary === 'object'
      ? {
          durationSeconds: typeof rawSummary.durationSeconds === 'number' ? rawSummary.durationSeconds : 0,
          participantUids: Array.isArray(rawSummary.participantUids)
            ? (rawSummary.participantUids as string[])
            : [],
          participantCount: typeof rawSummary.participantCount === 'number' ? rawSummary.participantCount : 0,
          distanceMeters: typeof rawSummary.distanceMeters === 'number' ? rawSummary.distanceMeters : null,
        }
      : null;

  const status: ConvoyStatus = CONVOY_STATUSES.includes(data.status as ConvoyStatus)
    ? (data.status as ConvoyStatus)
    : 'forming';

  return {
    convoyId,
    ownerUid: String(data.ownerUid ?? ''),
    title: typeof data.title === 'string' ? data.title : null,
    status,
    members,
    memberUids,
    viewer,
    livePositionUids: members.filter((m) => m.inviteStatus === 'accepted').map((m) => m.uid),
    destination: toConvoyDestination(data.destination, toIso),
    summary,
    createdAt: toIso(data.createdAt),
    startedAt: toIso(data.startedAt),
    endedAt: toIso(data.endedAt),
  };
}
