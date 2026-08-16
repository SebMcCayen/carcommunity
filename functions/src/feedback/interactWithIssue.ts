/**
 * feedback.interactWithIssue — AUTHENTICATED callable
 * (contracts/functions/functions.json).
 *
 * The in-app "open tickets" browser's write path. An active signed-in user may,
 * ONCE per (issue, type), either:
 *   - `plus_one`: register "I'm affected too" — posts a FIXED, content-free
 *     comment ("Another user is affected by this issue.") to the public issue;
 *   - `comment`: post their own note — neutralized (@/# defanged) and
 *     length-bounded, posted to the public issue AND mirrored into the shared
 *     `moderationReports` admin queue so a member's public-tracker comment is
 *     triageable.
 *
 * A member may do a +1 AND a comment on one issue (two types), but not two of
 * either. The once-per-(issue,user,type) guarantee is a backend-only
 * `issueInteractions/{issueNumber}__{uid}__{type}` document created inside the
 * same transaction that reserves it — a second attempt finds the doc and is
 * rejected with `failed-precondition`. A per-user 5/hour cap (mirroring
 * feedback.reportIssue) is enforced in the same transaction via a windowed
 * count() aggregate, so concurrent submissions cannot race the cap.
 *
 * ORDERING (Firestore first, GitHub second — feedback.reportIssue parity): the
 * dedup doc + tally + moderation mirror commit in ONE transaction; the GitHub
 * comment is posted AFTER and is best-effort — a GitHub failure never fails the
 * callable and never surfaces a raw GitHub error, and the interaction is still
 * recorded (so a member cannot double-post by retrying a network blip). The
 * token is bound as a secret and never logged or returned.
 *
 * Gated on the contract-default-OFF `reportTicketsBrowser` flag: while off the
 * callable rejects (failed-precondition) so nothing is ever posted publicly.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions';
import { defineSecret } from 'firebase-functions/params';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import { createIssueComment, neutralizeMentions } from '../shared/githubIssues';
import { readFeatureFlag } from '../shared/featureFlags';
import {
  ALREADY_INTERACTED_MESSAGE,
  INTERACT_RATE_LIMIT_WINDOW_MS,
  ISSUE_NOT_OPEN_MESSAGE,
  ISSUE_INTERACTIONS_COLLECTION,
  INTERACT_RATE_LIMITED_MESSAGE,
  MODERATION_REPORTS_COLLECTION,
  OPEN_TICKETS_COLLECTION,
  PLUS_ONE_COMMENT_BODY,
  REPORT_TICKETS_FLAG_KEY,
  TICKETS_DISABLED_MESSAGE,
  buildInteractionDocument,
  buildTicketCommentReportDocument,
  isInteractRateLimited,
  issueInteractionDocId,
  parseInteractInput,
} from './openTickets-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

/** Fine-grained GitHub token (`issues: write` → covers list + comment). */
const GITHUB_ISSUE_TOKEN = defineSecret('GITHUB_ISSUE_TOKEN');

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  secrets: [GITHUB_ISSUE_TOKEN],
};

export interface InteractWithIssueResponse {
  issueNumber: number;
  type: 'plus_one' | 'comment';
  /** True when the comment was accepted by GitHub (false in emulator / on GitHub failure). */
  posted: boolean;
}

export const interactWithIssue = onCall(
  CALLABLE_OPTS,
  async (request): Promise<InteractWithIssueResponse> => {
    // Member gate — matches the openTickets read rules the browser reads from
    // (isActiveMember) and the other member-only surfaces. requireMemberActor
    // rejects unauthenticated (`unauthenticated`) and suspended/deleted; the
    // entitlement term is behaviour-neutral today (MEMBER_GATING_ENABLED=false)
    // and correctly locks the write path when membership is re-enabled.
    const actor = await requireMemberActor(request);

    // Flag gate BEFORE any work — while off, nothing may reach the public repo.
    if (!(await readFeatureFlag(REPORT_TICKETS_FLAG_KEY))) {
      throw new HttpsError('failed-precondition', TICKETS_DISABLED_MESSAGE);
    }

    const parsed = parseInteractInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const input = parsed.input;

    const ticketRef = db.collection(OPEN_TICKETS_COLLECTION).doc(String(input.issueNumber));
    const interactionRef = db
      .collection(ISSUE_INTERACTIONS_COLLECTION)
      .doc(issueInteractionDocId(input.issueNumber, actor.uid, input.type));
    const interactions = db.collection(ISSUE_INTERACTIONS_COLLECTION);
    const windowStart = Timestamp.fromMillis(Date.now() - INTERACT_RATE_LIMIT_WINDOW_MS);
    const tallyField = input.type === 'plus_one' ? 'plusOneCount' : 'commentCount';

    // ONE transaction reserves the interaction, bumps the app-facing tally and
    // (for a comment) mirrors it to moderation — all reads precede all writes.
    await db.runTransaction(async (tx) => {
      // The issue must exist in the mirror AND be open. Reading it inside the tx
      // makes the openness check and the tally increment consistent.
      const ticketSnap = await tx.get(ticketRef);
      if (!ticketSnap.exists || ticketSnap.data()?.state !== 'open') {
        throw new HttpsError('failed-precondition', ISSUE_NOT_OPEN_MESSAGE);
      }

      // Dedup: a repeat of this exact (issue, user, type) is rejected.
      const existing = await tx.get(interactionRef);
      if (existing.exists) {
        throw new HttpsError('failed-precondition', ALREADY_INTERACTED_MESSAGE);
      }

      // Per-user windowed cap (5/hour across all issues+types) — count() read
      // serialized with the write below so concurrency cannot race the cap.
      const countSnap = await tx.get(
        interactions.where('uid', '==', actor.uid).where('createdAt', '>=', windowStart).count(),
      );
      if (isInteractRateLimited(countSnap.data().count)) {
        throw new HttpsError('resource-exhausted', INTERACT_RATE_LIMITED_MESSAGE);
      }

      tx.set(
        interactionRef,
        buildInteractionDocument(
          { issueNumber: input.issueNumber, uid: actor.uid, type: input.type, clientId: input.clientId },
          () => FieldValue.serverTimestamp(),
        ),
      );
      tx.update(ticketRef, { [tallyField]: FieldValue.increment(1) });

      if (input.type === 'comment' && input.commentText) {
        tx.set(
          db.collection(MODERATION_REPORTS_COLLECTION).doc(),
          buildTicketCommentReportDocument(
            {
              issueNumber: input.issueNumber,
              uid: actor.uid,
              commentText: input.commentText,
              authorDisplayName: null,
            },
            () => FieldValue.serverTimestamp(),
          ),
        );
      }
    });

    // GitHub comment — AFTER the commit, best-effort. A failure here never fails
    // the callable (the interaction is already recorded) and never leaks a raw
    // GitHub error. The +1 body is fixed template text; a member comment is
    // neutralized so it cannot @-ping a maintainer or #-link an arbitrary issue.
    const body =
      input.type === 'plus_one'
        ? PLUS_ONE_COMMENT_BODY
        : neutralizeMentions(input.commentText ?? '');
    let posted = false;
    try {
      posted = await createIssueComment(
        input.issueNumber,
        body,
        GITHUB_ISSUE_TOKEN.value(),
        'carcommunity-feedback-bot',
        { issueNumber: input.issueNumber },
      );
    } catch (error) {
      // createIssueComment already swallows its own errors; this guard is belt
      // and braces so a secret-resolution throw can't fail an already-recorded
      // interaction.
      logger.error('interactWithIssue: GitHub comment threw after commit', {
        issueNumber: input.issueNumber,
        error: String(error),
      });
    }

    return { issueNumber: input.issueNumber, type: input.type, posted };
  },
);
