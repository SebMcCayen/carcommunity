/**
 * Convoy domain core (pure logic): input parsing, document builders, summary
 * computation, and client-summary mappers for the convoy callables
 * (convoy.create / convoy.respond / convoy.start / convoy.end / convoy.list).
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

export const CONVOY_MEMBER_ROLES = ['owner', 'member'] as const;
export type ConvoyMemberRole = (typeof CONVOY_MEMBER_ROLES)[number];

export const CONVOY_INVITE_STATUSES = ['invited', 'accepted', 'declined'] as const;
export type ConvoyInviteStatus = (typeof CONVOY_INVITE_STATUSES)[number];

/** Max length of the optional convoy title. */
export const CONVOY_TITLE_MAX_LENGTH = 80;

/** Upper bound on invitees per convoy.create call (guards fan-out cost). */
export const MAX_CONVOY_INVITEES = 50;

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

/** User-facing messages (clients branch on the HttpsError code, never text). */
export const CONVOY_NOT_FOUND_MESSAGE = 'Convoy not found.';
export const NOT_INVITED_MESSAGE = 'You have no pending invite for this convoy.';
export const INVITE_ALREADY_HANDLED_MESSAGE = 'This invite has already been answered.';
export const CONVOY_ENDED_MESSAGE = 'This convoy has ended.';
export const CONVOY_NOT_FORMING_MESSAGE = 'This convoy can no longer be started.';
export const CONVOY_ALREADY_ENDED_MESSAGE = 'This convoy has already ended.';
export const NO_VALID_INVITEES_MESSAGE = 'No one could be added to the convoy.';

/** Why a requested invitee was skipped by convoy.create. */
export type InviteeSkipReason = 'self' | 'not_friend' | 'blocked' | 'not_found' | 'duplicate';

export interface SkippedInvitee {
  uid: string;
  reason: InviteeSkipReason;
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
    summary,
    createdAt: toIso(data.createdAt),
    startedAt: toIso(data.startedAt),
    endedAt: toIso(data.endedAt),
  };
}
