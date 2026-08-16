/**
 * Open-tickets domain — pure logic for the in-app "Report a problem → browse
 * open tickets" feature (input parsing, GitHub-issue → mirror mapping,
 * deterministic interaction ids, document builders, rate-limit helpers).
 *
 * TWO backend surfaces sit on top of this module:
 *
 *  - feedback-syncOpenTickets (scheduled): fetches OPEN issues labelled
 *    `android-issue` from the PUBLIC repo and mirrors each into
 *    `openTickets/{issueNumber}` (member-readable) so the app reads Firestore
 *    rather than making a GitHub call per open. [mapIssueToTicketFields] does
 *    the shape conversion; the sync fn adds the timestamps + tally transforms.
 *
 *  - feedback-interactWithIssue (callable): lets an active member, ONCE per
 *    (issue, type), +1 an issue (posts a fixed "another user is affected"
 *    comment) or add their own comment (neutralized + length-bounded, posted to
 *    the public issue AND mirrored to the moderationReports admin queue). Dedup
 *    is a backend-only `issueInteractions/{issueNumber}__{uid}__{type}` document
 *    created transactionally: a repeat is `already-exists` → failed-precondition.
 *
 * Pure module — no Firebase Admin SDK and no network imports, so every branch is
 * unit-testable without emulators (mirrors feedback/feedback-core.ts). The
 * callable/scheduled fn own all Firestore I/O, transactions and the GitHub call.
 */

import { z } from 'zod';
import { boundText, FEEDBACK_ISSUE_LABEL } from './feedback-core';
import {
  MODERATION_REPORT_INITIAL_STATUS,
  MODERATION_REPORTS_COLLECTION,
} from '../moderation/moderation-core';

// ---------------------------------------------------------------------------
// Feature flag (contract default OFF — see featureFlags-core.ts)
// ---------------------------------------------------------------------------

/** Gates the whole open-tickets browser + interaction path. */
export const REPORT_TICKETS_FLAG_KEY = 'reportTicketsBrowser' as const;
export const REPORT_TICKETS_FLAG_DEFAULT = false;

// ---------------------------------------------------------------------------
// Collections
// ---------------------------------------------------------------------------

/** Member-readable mirror of open public GitHub issues (backend-write only). */
export const OPEN_TICKETS_COLLECTION = 'openTickets';
/** Backend-only per-(issue,user,type) dedup ledger (no client access at all). */
export const ISSUE_INTERACTIONS_COLLECTION = 'issueInteractions';
/** Re-exported so the callable's moderation write names one constant. */
export { MODERATION_REPORTS_COLLECTION };

// ---------------------------------------------------------------------------
// Limits
// ---------------------------------------------------------------------------

/** The short blurb shown under a ticket title in the app. */
export const MAX_TICKET_SUMMARY_LENGTH = 140;
/** A member's typed comment before it is posted to the public issue. */
export const MAX_TICKET_COMMENT_LENGTH = 1000;

// ---------------------------------------------------------------------------
// Rate limit (per user) — mirrors feedback.reportIssue (5 / rolling hour)
// ---------------------------------------------------------------------------

export const INTERACT_RATE_LIMIT_MAX = 5;
export const INTERACT_RATE_LIMIT_WINDOW_MS = 60 * 60 * 1000;

/** True when a fresh interaction would exceed the per-user cap. */
export function isInteractRateLimited(recentCount: number): boolean {
  return recentCount >= INTERACT_RATE_LIMIT_MAX;
}

// ---------------------------------------------------------------------------
// Interaction input
// ---------------------------------------------------------------------------

export const INTERACTION_TYPES = ['plus_one', 'comment'] as const;
export type InteractionType = (typeof INTERACTION_TYPES)[number];

/**
 * The fixed comment posted on a +1. It is deliberately content-free — a +1
 * conveys "me too", nothing more — so it can never carry user text into the
 * public issue. Static template text; not neutralized (no `@`/`#`).
 */
export const PLUS_ONE_COMMENT_BODY = 'Another user is affected by this issue.';

/**
 * `clientId` is an app-supplied idempotency/debug tag ([A-Za-z0-9_-]{1,64}); it
 * is recorded on the interaction doc but is NOT part of the dedup identity (the
 * (issue, uid, type) triple is). Bounded so it can never be a path-injection or
 * an unbounded write.
 */
const clientIdSchema = z
  .string()
  .regex(/^[A-Za-z0-9_-]{1,64}$/);

const interactInputSchema = z
  .object({
    // GitHub issue numbers are positive integers; the doc id is String(number).
    issueNumber: z.number().int().positive(),
    type: z.enum(INTERACTION_TYPES),
    text: z.string().max(MAX_TICKET_COMMENT_LENGTH * 2).optional(),
    clientId: clientIdSchema,
  })
  .strict();

export type InteractInput = z.infer<typeof interactInputSchema>;

/** Normalized interaction request after bounding. */
export interface Interaction {
  issueNumber: number;
  type: InteractionType;
  /** Bounded comment text; null for a plus_one, non-empty for a comment. */
  commentText: string | null;
  clientId: string;
}

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export const INTERACT_INPUT_EXPECTED =
  "Expected { issueNumber: positive int, type: 'plus_one'|'comment', text? (required for comment), clientId: [A-Za-z0-9_-]{1,64} }.";
export const COMMENT_TEXT_REQUIRED_MESSAGE = 'A comment cannot be empty.';

export function parseInteractInput(data: unknown): ParseResult<Interaction> {
  const result = interactInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: INTERACT_INPUT_EXPECTED };
  }
  const { issueNumber, type, text, clientId } = result.data;

  if (type === 'plus_one') {
    // Any text on a +1 is ignored — the comment is fixed template text.
    return { ok: true, input: { issueNumber, type, commentText: null, clientId } };
  }

  const commentText = boundText(text ?? '', MAX_TICKET_COMMENT_LENGTH);
  if (commentText.length === 0) {
    return { ok: false, message: COMMENT_TEXT_REQUIRED_MESSAGE };
  }
  return { ok: true, input: { issueNumber, type, commentText, clientId } };
}

// ---------------------------------------------------------------------------
// Deterministic dedup doc id
// ---------------------------------------------------------------------------

/**
 * `issueInteractions/{issueNumber}__{uid}__{type}` — the ONCE-per-(issue, user,
 * type) key. A member may do a +1 AND a comment on one issue (two distinct
 * types → two distinct ids), but not two of either. Created transactionally;
 * an `already-exists` on the create is the dedup signal the callable turns into
 * failed-precondition.
 *
 * uid is a Firebase Auth uid (alphanumeric) and type is a fixed enum, so
 * `__` can never be forged out of them into a colliding key; issueNumber is a
 * number stringified. Kept as one readable segment (not hashed) — unlike the
 * moderation ids there is no `__`-bearing free-text part here.
 */
export function issueInteractionDocId(
  issueNumber: number,
  uid: string,
  type: InteractionType,
): string {
  return `${issueNumber}__${uid}__${type}`;
}

// ---------------------------------------------------------------------------
// GitHub issue → openTickets mirror
// ---------------------------------------------------------------------------

/** The subset of a GitHub issue this module maps (matches githubIssues.ts). */
export interface MappableIssue {
  number: number;
  title: string;
  body: string | null;
  html_url: string;
  created_at: string;
  state: string;
  comments: number;
  pull_request?: unknown;
}

/** The scalar fields of an `openTickets/{issueNumber}` document (no timestamps/tallies). */
export interface OpenTicketFields {
  number: number;
  title: string;
  summary: string;
  htmlUrl: string;
  createdAtIso: string;
  state: string;
}

/** First non-empty, whitespace-collapsed line of a body. */
function firstLine(text: string): string {
  return (
    text
      .split('\n')
      .map((s) => s.replace(/\s+/g, ' ').trim())
      .find((s) => s.length > 0) ?? ''
  );
}

/**
 * A row is mirrorable only if it is a genuine OPEN issue (not a pull request —
 * the issues endpoint returns both — and not already closed). Returns false for
 * anything the mirror must drop.
 */
export function isMirrorableIssue(issue: MappableIssue): boolean {
  return issue.pull_request === undefined && issue.state === 'open';
}

/**
 * Maps a GitHub issue to the scalar `openTickets` fields. `summary` is a short,
 * control-stripped blurb: the first line of the body, else the title, capped at
 * [MAX_TICKET_SUMMARY_LENGTH]. `title` is the issue title verbatim (already the
 * `[Android] …` form the feedback path files). The number is the doc id.
 *
 * NOTE: title/summary are echoes of PUBLIC issue text (already world-readable),
 * so no neutralization is applied — they are display strings for the app, never
 * re-posted to GitHub.
 */
export function mapIssueToTicketFields(issue: MappableIssue): OpenTicketFields {
  const bodyBlurb = firstLine(boundText(issue.body ?? '', MAX_TICKET_SUMMARY_LENGTH * 2));
  const summarySource = bodyBlurb.length > 0 ? bodyBlurb : issue.title;
  const summary = summarySource.replace(/\s+/g, ' ').trim().slice(0, MAX_TICKET_SUMMARY_LENGTH);
  return {
    number: issue.number,
    title: issue.title.slice(0, 300),
    summary,
    htmlUrl: issue.html_url,
    createdAtIso: issue.created_at,
    state: issue.state,
  };
}

// ---------------------------------------------------------------------------
// Document builders
// ---------------------------------------------------------------------------

/**
 * `issueInteractions/{id}` — the backend-only dedup ledger row. Carries the uid
 * (so the per-user rate-limit count query has a field to filter on), the issue,
 * the type, the clientId and a timestamp. Read/write denied to every client.
 */
export function buildInteractionDocument(
  input: { issueNumber: number; uid: string; type: InteractionType; clientId: string },
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    uid: input.uid,
    issueNumber: input.issueNumber,
    type: input.type,
    clientId: input.clientId,
    createdAt: serverTimestamp(),
  };
}

/**
 * `moderationReports/{autoId}` for a member's ticket COMMENT — routed to the
 * SAME admin queue chat/DM reports land in, so a comment a member pushed to the
 * public tracker is triageable (and the member actionable) even though it is
 * already public. Uses the legacy field vocabulary the admin queue renders
 * (`reportedBy`/`targetType`/`targetId`/`reason`/`status`/`createdAt`) plus the
 * same added fields the callable-backed message reports carry (`surface`,
 * `scopeId`, `reportedUserId`, `snapshot`, `occurrences`, `lastReportedAt`).
 *
 *  - `surface: 'ticket'` distinguishes it from community/convoy/dm without
 *    touching the moderation-core surface enum (this is a routing tag, not a
 *    reported-message surface); `targetType: 'message'` keeps it in the
 *    recognized message-report lane the admin UI already renders.
 *  - `reportedBy` / `reportedUserId` are BOTH the commenting member — there is
 *    no third-party "reporter"; the content author is who a moderator acts on.
 *  - `snapshot.text` is the bounded comment as posted (the neutralization is a
 *    GitHub-only anti-ping measure; admins see the readable text).
 */
export function buildTicketCommentReportDocument(
  input: {
    issueNumber: number;
    uid: string;
    commentText: string;
    authorDisplayName: string | null;
  },
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  const ts = serverTimestamp();
  return {
    reportedBy: input.uid,
    targetType: 'message',
    targetId: `ticket-${input.issueNumber}`,
    reason: 'other',
    details: null,
    status: MODERATION_REPORT_INITIAL_STATUS,
    createdAt: ts,
    surface: 'ticket',
    scopeId: String(input.issueNumber),
    reportedUserId: input.uid,
    snapshot: {
      text: input.commentText,
      authorUserId: input.uid,
      authorDisplayName: input.authorDisplayName,
      createdAt: null,
    },
    occurrences: 1,
    lastReportedAt: ts,
  };
}

// ---------------------------------------------------------------------------
// User-facing messages (clients branch on the HttpsError code, never text)
// ---------------------------------------------------------------------------

export const TICKETS_DISABLED_MESSAGE = 'Ticket browsing is not available right now.';
export const ISSUE_NOT_OPEN_MESSAGE = 'This issue is not open.';
export const ALREADY_INTERACTED_MESSAGE = 'You have already done this on this issue.';
export const INTERACT_RATE_LIMITED_MESSAGE =
  'Too many interactions — please wait a while before trying again.';

/** The label the sync fetches (single source: feedback-core). */
export const OPEN_TICKETS_LABEL = FEEDBACK_ISSUE_LABEL;
