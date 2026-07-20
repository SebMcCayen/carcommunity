/**
 * communityChat-digest — the periodic community-chat DIGEST sweep
 * (contracts/functions/functions.json; grouped export → `communityChat-digest`).
 *
 * Follows the onSchedule conventions of the other scheduled sweeps
 * (incidents-cleanupExpired, account-cleanupInactive): europe-west1,
 * Europe/Stockholm, `runCommunityChatDigest(now)` exported so emulator tests can
 * drive it deterministically. The decision per member is the pure, hard-unit-tested
 * logic in communityDigest-core.ts; this file is only the I/O around it.
 *
 * CADENCE — DAILY, 18:00 Europe/Stockholm.
 *
 * A digest's entire reason to exist is to be LOW frequency: it replaces per-message
 * spam with a single roll-up. Daily is the coarsest cadence that still feels timely
 * for a same-day town-square conversation, and 18:00 lands it in the evening when
 * members actually pick up their phones — one "the community chat was active today"
 * nudge, not a stream. (More frequent — hourly/twice-daily — would edge back toward
 * the fatigue the digest is meant to remove; the marker below means a slower cadence
 * loses nothing, it just delays a notice a member can already pre-empt by opening the
 * chat.)
 *
 * COST — O(behind-members) reads per run, NOT O(members × messages).
 *
 * Let B = members whose communityChatLastReadAt is OLDER than the channel's newest
 * message (the "behind" set — at most the member count, in practice a small fraction
 * of a single-town community's hundreds; members caught up or who never opened the
 * chat are never read):
 *   - 1 read:            newest-message probe (messages orderBy createdAt desc limit 1).
 *   - B reads:           paging the behind set (userPrivate where communityChatLastReadAt
 *                        < newest, which ALSO carries digestedUpTo — no extra marker read).
 *   - ≤ B count() reads: the per-member unread count, GATED by hasNewSinceBaseline so a
 *                        member already digested past the newest message costs none.
 *   - per notified N:    writeInAppNotification (2 reads + ≤1 write) + 1 marker write.
 * Total ≈ 1 + B + (≤B) + O(N). For hundreds of members that is low hundreds of reads
 * per DAILY run. The rejected per-message producer would instead be O(members) writes
 * on EVERY post. count() is billed per ~1000 index entries scanned, so each per-member
 * count is ~1 read at this scale — we never fetch the messages themselves.
 *
 * IDEMPOTENCY — see communityDigest-core.ts. Primary: the communityChatDigestedUpTo
 * marker advances to the run's newest instant on notify, so a member is re-digested
 * only when ≥ threshold NEW messages arrive after their last digest (or never, if the
 * chat stays quiet). Secondary: a per-UTC-day deterministic notificationId collapses a
 * same-day replay. Reading the chat advances lastReadAt and ends re-digesting.
 *
 * OPT-OUT is INHERITED, never re-checked here: every notice goes through
 * writeInAppNotification, whose decideInAppDelivery drops deleted/suspended recipients
 * and anyone who muted the `community_chat` category. This sweep does not read
 * notificationPreferences at all. NOTE we advance the digest marker on the DECISION to
 * notify (threshold crossed), regardless of whether delivery actually landed — so a
 * muted member is not re-evaluated every run for the same unread run, and un-muting
 * starts fresh from new activity rather than replaying a backlog they chose to silence.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { FieldPath, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { writeInAppNotification } from '../notifications/deliver';
import { COMMUNITY_CHANNEL_ID } from './chat-core';
import {
  COMMUNITY_DIGEST_MIN_UNREAD,
  COMMUNITY_DIGEST_TITLE,
  communityDigestNotificationId,
  communityDigestPreview,
  decideMemberDigest,
  digestBaseline,
  hasNewSinceBaseline,
} from './communityDigest-core';

/** userPrivate docs scanned per query round-trip (the candidate "behind" set). */
const CANDIDATE_PAGE_SIZE = 400;

/**
 * Safety valve on candidates examined per run — bounds the daily run's reads far
 * ABOVE any single-town behind set (hundreds). It is NOT expected to bind: unlike
 * the incidents/inactivity sweeps, digested members keep their (unchanged)
 * last-read and so re-appear in the candidate query, they are just gated out of the
 * expensive count() cheaply. If a community ever outgrows this, the extension is a
 * persisted scan cursor exactly like account-cleanupInactive's — deliberately not
 * added now (it would be dead complexity for a hundreds-scale audience).
 */
const MAX_CANDIDATES_PER_RUN = 20_000;

/** Per-user last-read + digest marker doc. */
function userPrivateRef(uid: string) {
  return db.collection('userPrivate').doc(uid);
}

/** The single global community channel's messages subcollection. */
function communityMessagesRef() {
  return db.collection('communityChat').doc(COMMUNITY_CHANNEL_ID).collection('messages');
}

function toMillis(value: unknown): number | null {
  return value instanceof Timestamp ? value.toMillis() : null;
}

export interface CommunityDigestSummary {
  /** True when the channel had no messages at all — the run was a no-op. */
  emptyChannel: boolean;
  /** userPrivate candidate docs examined (the behind set actually paged). */
  candidates: number;
  /** Members for whom the count() aggregation was run (gate passed). */
  counted: number;
  /** Digest notices decided (threshold crossed) — marker advanced for each. */
  notified: number;
  /** Members skipped because they were already digested past the newest message. */
  alreadyDigested: number;
  /** Members with new-but-below-threshold activity (accumulating silently). */
  belowThreshold: number;
  /** True when MAX_CANDIDATES_PER_RUN bound the scan (remainder next run). */
  capped: boolean;
}

export interface CommunityDigestLimits {
  threshold: number;
  maxCandidates: number;
  pageSize: number;
}

/**
 * Runs one digest sweep against `now`.
 *
 * `limits` exists so a test can seed a small scale and exercise the bounds; the
 * scheduled entry point never passes it, so production runs on the constants above.
 */
export async function runCommunityChatDigest(
  now: Date,
  limits: CommunityDigestLimits = {
    threshold: COMMUNITY_DIGEST_MIN_UNREAD,
    maxCandidates: MAX_CANDIDATES_PER_RUN,
    pageSize: CANDIDATE_PAGE_SIZE,
  },
): Promise<CommunityDigestSummary> {
  const summary: CommunityDigestSummary = {
    emptyChannel: false,
    candidates: 0,
    counted: 0,
    notified: 0,
    alreadyDigested: 0,
    belowThreshold: 0,
    capped: false,
  };

  // ONE probe for the newest message instant, shared by every member this run.
  const newestSnap = await communityMessagesRef()
    .orderBy('createdAt', 'desc')
    .limit(1)
    .get();
  const newest = newestSnap.docs[0];
  const latestMessageTs = newest ? (newest.get('createdAt') as unknown) : null;
  const latestMessageAtMs = toMillis(latestMessageTs);
  if (latestMessageAtMs === null) {
    // Empty channel (or a pending serverTimestamp with no value yet) — nobody can
    // be behind anything, so there is no work and no reads to spend on candidates.
    summary.emptyChannel = true;
    logger.info('Community digest: empty channel, nothing to do');
    return summary;
  }
  const latestMessageStamp = latestMessageTs as Timestamp;

  const pageSize = Math.max(1, limits.pageSize);
  // Cursor carries BOTH the ordering value and the doc id tiebreaker: digested
  // members keep their (unchanged) communityChatLastReadAt, so several candidates
  // can share an identical timestamp — a single-field startAfter could skip or
  // repeat them at a page boundary. documentId() disambiguates (same pattern as
  // account-cleanupInactive).
  let cursor: { lastReadAt: Timestamp; uid: string } | null = null;

  // CANDIDATE SET: only members who HAVE a last-read marker OLDER than the newest
  // message can possibly be behind. This single-field range query (auto-indexed —
  // no composite index) is the hard cost bound: caught-up members and members who
  // never opened the chat are never read. Paged oldest-behind first.
  for (;;) {
    if (summary.candidates >= limits.maxCandidates) {
      summary.capped = true;
      break;
    }
    let query = db
      .collection('userPrivate')
      .where('communityChatLastReadAt', '<', latestMessageStamp)
      .orderBy('communityChatLastReadAt', 'asc')
      .orderBy(FieldPath.documentId())
      .limit(Math.min(pageSize, limits.maxCandidates - summary.candidates));
    if (cursor !== null) {
      query = query.startAfter(cursor.lastReadAt, cursor.uid);
    }
    const page = await query.get();
    if (page.empty) {
      break;
    }

    for (const doc of page.docs) {
      summary.candidates += 1;
      const data = doc.data();
      const lastReadAtMs = toMillis(data.communityChatLastReadAt);
      const digestedUpToMs = toMillis(data.communityChatDigestedUpTo);
      const baseline = digestBaseline(lastReadAtMs, digestedUpToMs);

      // Gate the expensive count() aggregation: a member already digested past the
      // newest message costs zero aggregation reads.
      if (!hasNewSinceBaseline(latestMessageAtMs, baseline)) {
        summary.alreadyDigested += 1;
        continue;
      }

      // Cheap unread COUNT (aggregation) — never fetch the messages themselves.
      const baselineStamp = Timestamp.fromMillis(baseline as number);
      const countSnap = await communityMessagesRef()
        .where('createdAt', '>', baselineStamp)
        .count()
        .get();
      const unreadCount = countSnap.data().count;
      summary.counted += 1;

      const decision = decideMemberDigest({
        latestMessageAtMs,
        lastReadAtMs,
        digestedUpToMs,
        unreadCount,
        threshold: limits.threshold,
      });

      if (!decision.notify) {
        if (decision.reason === 'below_threshold') summary.belowThreshold += 1;
        else summary.alreadyDigested += 1;
        continue;
      }

      try {
        // Opt-out / suspended / deleted eligibility is OWNED by this call — not
        // re-checked here. The per-UTC-day id is the secondary idempotency guard.
        await writeInAppNotification(
          doc.id,
          {
            category: 'community_chat',
            title: COMMUNITY_DIGEST_TITLE,
            previewText: communityDigestPreview(decision.unreadCount),
            actionType: 'open_notifications',
            // Singleton channel — the channel id is the only deep-link target.
            relatedEntityId: COMMUNITY_CHANNEL_ID,
          },
          communityDigestNotificationId(now),
        );

        // PRIMARY idempotency guard: advance the digest marker to this run's newest
        // instant so this unread run is never re-digested. Stamped on the DECISION
        // to notify (threshold crossed), independent of delivery outcome, so a muted
        // recipient is not re-evaluated every run for the same backlog.
        await userPrivateRef(doc.id).set(
          { communityChatDigestedUpTo: latestMessageStamp },
          { merge: true },
        );
        summary.notified += 1;
      } catch (error) {
        // One member's failure must not abort the sweep; the marker was not
        // advanced, so the next run retries this member (all writes idempotent).
        logger.error('Community digest delivery failed; will retry next run', {
          uid: doc.id,
          error: String(error),
        });
      }
    }

    if (page.size < pageSize) {
      break;
    }
    const lastDoc = page.docs[page.docs.length - 1]!;
    cursor = {
      lastReadAt: lastDoc.get('communityChatLastReadAt') as Timestamp,
      uid: lastDoc.id,
    };
  }

  if (summary.capped) {
    logger.warn('Community digest candidate cap reached; deferring remainder to next run', {
      maxCandidates: limits.maxCandidates,
    });
  }
  logger.info('Community digest sweep complete', { ...summary });
  return summary;
}

/** Daily community-chat digest (18:00 Europe/Stockholm). */
export const digest = onSchedule(
  {
    region: 'europe-west1',
    timeZone: 'Europe/Stockholm',
    memory: '256MiB' as const,
    timeoutSeconds: 300,
    schedule: '0 18 * * *',
  },
  async () => {
    await runCommunityChatDigest(new Date());
  },
);
