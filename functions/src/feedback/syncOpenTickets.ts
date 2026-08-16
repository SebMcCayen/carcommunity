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
 * BEST-EFFORT: listOpenIssues never throws (it returns `[]` on any GitHub
 * failure and in the emulator). An empty result is treated as "GitHub gave us
 * nothing to reconcile against" and the sync makes NO deletions in that case —
 * a transient GitHub outage must never wipe the whole mirror. Real deletions
 * happen only when GitHub returned a non-empty open set that a given doc is
 * absent from.
 *
 * The per-ticket `plusOneCount` / `commentCount` tallies are the app-facing
 * counts maintained by feedback-interactWithIssue; the sync writes them with a
 * `FieldValue.increment(0)` so a first insert initialises them to 0 while an
 * existing doc's live tally is preserved (never clobbered by a resync).
 *
 * `runOpenTicketsSync(fetchIssues?)` is exported so an emulator test can inject
 * a deterministic issue set (the real GitHub call is short-circuited in the
 * emulator). Mirrors badges/scheduled.ts runBadgeBacklogSweep.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { defineSecret } from 'firebase-functions/params';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { listOpenIssues, type GitHubOpenIssue } from '../shared/githubIssues';
import {
  OPEN_TICKETS_COLLECTION,
  OPEN_TICKETS_LABEL,
  isMirrorableIssue,
  mapIssueToTicketFields,
  type MappableIssue,
} from './openTickets-core';
import { MAX_INSTANCES_SCHEDULED, CPU_SCHEDULED } from '../shared/instanceLimits';
import { withServerErrorReporting } from '../errors/serverErrors';

/** Same secret the feedback callable binds — reused for list + comment scopes. */
const GITHUB_ISSUE_TOKEN = defineSecret('GITHUB_ISSUE_TOKEN');

export interface OpenTicketsSyncResult {
  fetched: number;
  mirrored: number;
  removed: number;
}

/** Injectable GitHub fetcher (defaults to the real, best-effort list call). */
export type IssueFetcher = () => Promise<GitHubOpenIssue[]>;

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
  const issues = await fetchIssues();
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

  // Reconcile OUT anything no longer open — but ONLY when GitHub actually
  // returned an open set. An empty fetch (outage / emulator) makes no deletions,
  // so a transient failure can never empty the mirror.
  let removed = 0;
  if (mirrorable.length > 0) {
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
  }

  logger.info('syncOpenTickets complete', { fetched: issues.length, mirrored, removed });
  return { fetched: issues.length, mirrored, removed };
}

export const syncOpenTickets = onSchedule(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_SCHEDULED,
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
