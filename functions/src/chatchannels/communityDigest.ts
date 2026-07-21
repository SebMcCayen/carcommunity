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
 * The marker is advanced BEFORE the notification write (the two are not atomic): a
 * failure between them costs a missed digest, never a duplicate — the deliberate
 * tradeoff documented at the write site.
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

/**
 * Behind-members processed CONCURRENTLY within a page. The per-member work is a
 * count() aggregation plus (on notify) a marker write and a delivery — several
 * independent network round-trips against per-member documents. Running a page
 * strictly serially makes a run's wall-clock scale with the behind set B and can
 * push a busy day past the 300s timeout; a small bounded fan-out makes it scale
 * with this concurrency instead. NOT an unbounded `Promise.all` over the whole
 * page — that would open B simultaneous streams and hammer Firestore (same
 * reasoning and chunked idiom as incidents-cleanup's DELETE_CONCURRENCY and the
 * event auto-close sweep's ATTENDANCE_CREDIT_CONCURRENCY). Each member's decision
 * is fully independent — a distinct userPrivate doc and a distinct notification
 * inbox, over a shared read-only count() — so parallelism cannot change any
 * outcome; only log lines interleave. count() aggregation throughput is the real
 * ceiling, so this caps the fan-out rather than eliminating the bound: it lowers
 * the wall-clock from O(B) round-trips to O(B / concurrency).
 */
const MEMBER_CONCURRENCY = 15;

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
  /**
   * Highest number of members processed concurrently in any one chunk. The ONLY
   * way to observe that the per-member fan-out stayed bounded (never exceeds the
   * `concurrency` limit); exposed like incidents-cleanup's peakConcurrency.
   */
  peakConcurrency: number;
}

export interface CommunityDigestLimits {
  threshold: number;
  maxCandidates: number;
  pageSize: number;
  /** Max members processed concurrently per chunk (MEMBER_CONCURRENCY in prod). */
  concurrency: number;
}

/**
 * Injectable I/O — production uses the real notification writer; a test can pass a
 * stub (e.g. one that throws) to exercise the marker/delivery failure ordering. Same
 * test-seam intent as `limits`; the scheduled entry point never passes it.
 */
export interface CommunityDigestDeps {
  deliver: typeof writeInAppNotification;
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
    concurrency: MEMBER_CONCURRENCY,
  },
  deps: CommunityDigestDeps = { deliver: writeInAppNotification },
): Promise<CommunityDigestSummary> {
  const summary: CommunityDigestSummary = {
    emptyChannel: false,
    candidates: 0,
    counted: 0,
    notified: 0,
    alreadyDigested: 0,
    belowThreshold: 0,
    capped: false,
    peakConcurrency: 0,
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
  const concurrency = Math.max(1, limits.concurrency);
  // Live count of in-flight per-member tasks, tracked ONLY to expose the bound via
  // summary.peakConcurrency (same pattern as incidents-cleanup). Safe as a plain
  // counter: JS is single-threaded, so the increment and the peak comparison are
  // atomic between awaits.
  let inFlight = 0;
  // Cursor carries BOTH the ordering value and the doc id tiebreaker: digested
  // members keep their (unchanged) communityChatLastReadAt, so several candidates
  // can share an identical timestamp — a single-field startAfter could skip or
  // repeat them at a page boundary. documentId() disambiguates (same pattern as
  // account-cleanupInactive).
  let cursor: { lastReadAt: Timestamp; uid: string } | null = null;

  // Per-member decision + I/O for ONE candidate. Returns what happened so the
  // caller can fold it into `summary` AFTER a chunk settles — the loop must not
  // mutate shared counters from parallel tasks (only inFlight/peakConcurrency,
  // which are the bound's own instrumentation). `counted` is true iff the count()
  // aggregation actually ran (the gate passed). Every member's target docs are
  // distinct, so tasks in a chunk never race each other's writes. This throws ONLY
  // if the count() aggregation itself fails (marker + delivery failures are caught
  // here); an unguarded count() failure rejects the chunk and aborts the run, the
  // same fail-and-retry-next-run behaviour the serial version had.
  type MemberOutcome = {
    counted: boolean;
    result: 'alreadyDigested' | 'belowThreshold' | 'notified' | 'markerFailed';
  };
  const processMember = async (
    doc: FirebaseFirestore.QueryDocumentSnapshot,
  ): Promise<MemberOutcome> => {
    inFlight += 1;
    summary.peakConcurrency = Math.max(summary.peakConcurrency, inFlight);
    try {
      const data = doc.data();
      const lastReadAtMs = toMillis(data.communityChatLastReadAt);
      const digestedUpToMs = toMillis(data.communityChatDigestedUpTo);
      const baseline = digestBaseline(lastReadAtMs, digestedUpToMs);

      // Gate the expensive count() aggregation: a member already digested past the
      // newest message costs zero aggregation reads. The `baseline === null` arm is
      // the defensive case: digestBaseline returns null only when NEITHER marker is a
      // usable Timestamp, which for a doc the candidate query returned means a
      // MALFORMED userPrivate.communityChatLastReadAt (Firestore's `<` can match a
      // value that type-sorts before a Timestamp, e.g. a number) — markRead only ever
      // writes a serverTimestamp, so this never happens with valid data. hasNewSince-
      // Baseline treats null as -infinity (returns true), so without this arm we would
      // fall through and count from the epoch on a bad type; skip the member instead.
      // (It also narrows `baseline` to a number for fromMillis below — no cast, and no
      // `undefined` can reach fromMillis, which is the only value that would throw.)
      if (!hasNewSinceBaseline(latestMessageAtMs, baseline) || baseline === null) {
        return { counted: false, result: 'alreadyDigested' };
      }

      // Cheap unread COUNT (aggregation) — never fetch the messages themselves.
      // UPPER-BOUNDED to the same probed instant the marker advances to, so the count
      // window (baseline, latestMessageStamp] is IDENTICAL to the window the marker
      // will cover on notify. A message posted after the probe but before this count
      // is therefore neither counted here nor marked as digested — the next run picks
      // it up cleanly — so the count can never reach past the marker (which would let
      // the same message be counted in two consecutive digests). Both range filters are
      // on the single createdAt field, served by its automatic index — no composite.
      const baselineStamp = Timestamp.fromMillis(baseline);
      const countSnap = await communityMessagesRef()
        .where('createdAt', '>', baselineStamp)
        .where('createdAt', '<=', latestMessageStamp)
        .count()
        .get();
      const unreadCount = countSnap.data().count;

      const decision = decideMemberDigest({
        latestMessageAtMs,
        lastReadAtMs,
        digestedUpToMs,
        unreadCount,
        threshold: limits.threshold,
      });

      if (!decision.notify) {
        return {
          counted: true,
          result: decision.reason === 'below_threshold' ? 'belowThreshold' : 'alreadyDigested',
        };
      }

      // PRIMARY idempotency guard, written BEFORE delivery. The two writes (marker +
      // notification) are not atomic, so ORDER decides the failure mode between them:
      //   - marker FIRST (here): a transient failure after the marker advances but
      //     before delivery costs at most a MISSED digest — the notice is silently
      //     dropped, never re-attempted.
      //   - notification first: a marker-write failure after a delivered notice leaves
      //     a delivered-but-unmarked state; next run mints a NEW per-UTC-day
      //     notificationId and re-delivers the SAME backlog — a DUPLICATE, recurring
      //     daily until the member reads the chat.
      // For a daily, low-value nudge a missed roll-up is invisible, whereas a duplicate
      // is precisely the notification fatigue the digest exists to remove and trains
      // members to ignore it — so we prefer the missed digest and advance the marker
      // first. Stamped on the DECISION to notify (threshold crossed), independent of
      // delivery outcome, so a muted recipient is not re-evaluated every run.
      try {
        await userPrivateRef(doc.id).set(
          { communityChatDigestedUpTo: latestMessageStamp },
          { merge: true },
        );
      } catch (error) {
        // Nothing was delivered and the marker did NOT advance, so the next run simply
        // retries this member (all writes idempotent) — no duplicate risk.
        logger.error('Community digest marker write failed; will retry next run', {
          uid: doc.id,
          error: String(error),
        });
        return { counted: true, result: 'markerFailed' };
      }

      try {
        // Opt-out / suspended / deleted eligibility is OWNED by this call — not
        // re-checked here. The per-UTC-day id is the secondary idempotency guard.
        await deps.deliver(
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
      } catch (error) {
        // The marker is ALREADY advanced, so we deliberately do NOT retry: a missed
        // daily nudge is the accepted cost of guaranteeing no duplicate (see above).
        logger.error('Community digest delivery failed after marker advanced; dropping to avoid a duplicate', {
          uid: doc.id,
          error: String(error),
        });
      }
      return { counted: true, result: 'notified' };
    } finally {
      inFlight -= 1;
    }
  };

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

    // Every doc in the page is a candidate examined this run (the query already
    // limited the page to the remaining maxCandidates budget, so this can't overrun
    // it). Counted up front; the per-member outcomes below only fold into the finer
    // buckets.
    summary.candidates += page.docs.length;

    // Process the page in bounded-concurrency CHUNKS (see MEMBER_CONCURRENCY), then
    // fold each chunk's outcomes into the shared summary AFTER it settles — parallel
    // tasks never touch the counters themselves, so there is no race on them.
    for (let i = 0; i < page.docs.length; i += concurrency) {
      const chunk = page.docs.slice(i, i + concurrency);
      const outcomes = await Promise.all(chunk.map((doc) => processMember(doc)));
      for (const outcome of outcomes) {
        if (outcome.counted) summary.counted += 1;
        switch (outcome.result) {
          case 'alreadyDigested':
            summary.alreadyDigested += 1;
            break;
          case 'belowThreshold':
            summary.belowThreshold += 1;
            break;
          case 'notified':
            summary.notified += 1;
            break;
          case 'markerFailed':
            // Counted (the gate passed) but neither notified nor bucketed elsewhere —
            // the marker write failed and this member is retried next run.
            break;
        }
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
