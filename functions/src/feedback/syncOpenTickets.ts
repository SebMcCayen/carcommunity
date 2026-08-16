/**
 * feedback-syncOpenTickets — scheduled mirror of OPEN public GitHub issues.
 *
 * Every few minutes this fetches the OPEN issues labelled `android-issue` from
 * the PUBLIC repo and mirrors each into `openTickets/{issueNumber}` (member
 * readable — firestore.rules), so the in-app "open tickets" browser reads
 * Firestore rather than making a GitHub call per open. A closed / unlabelled /
 * deleted issue is reconciled OUT: any `openTickets` doc not in the freshly
 * fetched open set is removed, so the app's list can never show a ticket that is
 * no longer open.
 *
 * BEST-EFFORT: listOpenIssues never throws. It PAGINATES the open set and
 * returns `null` on any failure (network/non-2xx on ANY page, unexpected shape,
 * missing token, emulator) or `{ issues, complete }` on success. A `null`
 * result makes NO changes at all — a transient outage must never wipe the
 * mirror. A COMPLETE successful fetch reconciles fully, INCLUDING a genuine
 * empty open set (every stale doc removed). A `complete: false` set is
 * potentially TRUNCATED (the page cap was hit with full pages), so the delete
 * pass is SKIPPED — the sync upserts what it saw but never deletes tickets it
 * may simply not have fetched, so a repo with >1000 open tickets can never lose
 * still-open rows from the mirror.
 *
 * The per-ticket `plusOneCount` / `commentCount` tallies are the app-facing
 * counts maintained by feedback-interactWithIssue; the sync writes them with a
 * `FieldValue.increment(0)` so a first insert initialises them to 0 while an
 * existing doc's live tally is preserved (never clobbered by a resync).
 *
 * `runOpenTicketsSync(fetchIssues?)` is exported so an emulator test can inject
 * a deterministic result — `{ issues, complete }` or null — since the real
 * GitHub call short-circuits to null in the emulator. Mirrors
 * badges/scheduled.ts runBadgeBacklogSweep.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { defineSecret } from 'firebase-functions/params';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { listOpenIssues, type OpenIssuesResult } from '../shared/githubIssues';
import {
  OPEN_TICKETS_COLLECTION,
  OPEN_TICKETS_LABEL,
  isMirrorableIssue,
  mapIssueToTicketFields,
  type MappableIssue,
} from './openTickets-core';
import { CPU_SCHEDULED } from '../shared/instanceLimits';
import { withServerErrorReporting } from '../errors/serverErrors';

/** Same secret the feedback callable binds — reused for list + comment scopes. */
const GITHUB_ISSUE_TOKEN = defineSecret('GITHUB_ISSUE_TOKEN');

export interface OpenTicketsSyncResult {
  fetched: number;
  mirrored: number;
  removed: number;
}

/**
 * Injectable GitHub fetcher (defaults to the real, best-effort list call).
 * Resolves to `null` on any failure (make no changes), or `{ issues, complete }`
 * on success — `complete: false` marks a potentially-truncated set on which the
 * delete/reconcile pass is SKIPPED (upsert only) so unseen open tickets are
 * never deleted.
 */
export type IssueFetcher = () => Promise<OpenIssuesResult | null>;

/**
 * Upserts one ticket, preserving the live interaction tallies. `createdAt` is
 * parsed from the issue's ISO string; an unparseable value falls back to the
 * server clock so the field is never NaN/Invalid.
 */
async function upsertTicket(issue: MappableIssue): Promise<void> {
  const fields = mapIssueToTicketFields(issue);
  const parsed = new Date(fields.createdAtIso);
  const createdAt = Number.isNaN(parsed.getTime())
    ? FieldValue.serverTimestamp()
    : Timestamp.fromDate(parsed);
  await db
    .collection(OPEN_TICKETS_COLLECTION)
    .doc(String(fields.number))
    .set(
      {
        number: fields.number,
        title: fields.title,
        summary: fields.summary,
        htmlUrl: fields.htmlUrl,
        createdAt,
        state: fields.state,
        // increment(0): initialise to 0 on first insert, preserve on resync.
        plusOneCount: FieldValue.increment(0),
        commentCount: FieldValue.increment(0),
        syncedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
}

export async function runOpenTicketsSync(
  fetchIssues: IssueFetcher = () =>
    listOpenIssues(OPEN_TICKETS_LABEL, GITHUB_ISSUE_TOKEN.value(), 'carcommunity-feedback-bot'),
): Promise<OpenTicketsSyncResult> {
  const result = await fetchIssues();

  // A null result is the FAILURE signal (outage / emulator / bad token): make
  // no changes at all so a transient blip can never touch the mirror.
  if (result === null) {
    logger.info('syncOpenTickets skipped: GitHub fetch unavailable');
    return { fetched: 0, mirrored: 0, removed: 0 };
  }

  const { issues, complete } = result;
  const mirrorable = issues.filter(isMirrorableIssue);

  let mirrored = 0;
  for (const issue of mirrorable) {
    try {
      await upsertTicket(issue);
      mirrored += 1;
    } catch (error) {
      // One bad row must not abort the whole mirror; the rest still sync.
      logger.error('syncOpenTickets: upsert failed', {
        issueNumber: issue.number,
        error: String(error),
      });
    }
  }

  // Reconcile OUT anything no longer open — but ONLY on a COMPLETE fetch. A
  // truncated set (page cap hit, complete=false) has open tickets we never saw,
  // so deleting docs "not in the set" would wrongly drop still-open tickets:
  // upsert only, skip deletion. A complete set — including a genuine empty one —
  // reconciles fully (every stale doc removed).
  let removed = 0;
  if (complete) {
    const keep = new Set(mirrorable.map((i) => String(i.number)));
    const existing = await db.collection(OPEN_TICKETS_COLLECTION).get();
    for (const doc of existing.docs) {
      if (!keep.has(doc.id)) {
        try {
          await doc.ref.delete();
          removed += 1;
        } catch (error) {
          logger.error('syncOpenTickets: stale delete failed', {
            issueNumber: doc.id,
            error: String(error),
          });
        }
      }
    }
  } else {
    logger.warn('syncOpenTickets: truncated open set — skipping stale reconciliation', {
      fetched: issues.length,
    });
  }

  logger.info('syncOpenTickets complete', { fetched: issues.length, mirrored, removed });
  return { fetched: issues.length, mirrored, removed };
}

export const syncOpenTickets = onSchedule(
  {
    region: 'europe-west1',
    // Strict SINGLETON (not MAX_INSTANCES_SCHEDULED=2): this job does a full
    // reconciliation INCLUDING deletes over the whole openTickets collection,
    // so two overlapping ticks would double the GitHub list call and churn the
    // mirror. It is not cursor-paged/idempotent like badges.evaluateBacklog, so
    // it must never overlap itself (see instanceLimits.ts guidance).
    maxInstances: 1,
    cpu: CPU_SCHEDULED,
    concurrency: 1,
    schedule: 'every 5 minutes',
    timeZone: 'Europe/Stockholm',
    memory: '256MiB',
    timeoutSeconds: 120,
    secrets: [GITHUB_ISSUE_TOKEN],
  },
  withServerErrorReporting('feedback.syncOpenTickets', async () => {
    await runOpenTicketsSync();
  }),
);
