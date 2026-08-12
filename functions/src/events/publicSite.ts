/**
 * events.setPublicSite + the homepage regeneration pipeline.
 *
 * THE MODEL — one creator-controlled switch, three sync paths:
 *
 * `events/{eventId}.publicSiteEnabled` (see publicSite-core.ts) is set by the
 * event's creator (the app's create-form checkbox → events.create, or this
 * callable later) or by an admin (moderation safety valve, e.g. force-
 * unpublish). While an enabled event is `published` and upcoming it appears
 * in the community homepage's data/app-events.json feed and (once the public
 * pages ship) at its /e/{eventId} public page.
 *
 * The generated file is kept honest by three regeneration paths, all funnelled
 * through ONE implementation ([regenerateHomepageEvents]):
 *
 * 1. events-onPublicSiteWrite — Firestore trigger on events/{eventId}. Fires
 *    the regen whenever a write can change the file (flag flips, cancel,
 *    complete — including the autoClose sweep's — reschedule, retitle …);
 *    isHomepageSyncRelevant makes every other write a cheap no-op. NO LOOP is
 *    possible: regeneration writes to GitHub only, never to Firestore.
 * 2. events-syncHomepage — daily scheduled sweep. Time itself changes the feed
 *    (an event whose start passed must fall out even when nothing writes to
 *    Firestore), and the sweep also self-heals any missed/failed trigger regen.
 *    With no changes it costs one GitHub GET and commits nothing
 *    (homepageFileEquivalent ignores the generatedAt stamp).
 * 3. The callable itself performs NO regeneration — its flag write fires path 1,
 *    which keeps exactly one regen per change instead of a callable+trigger
 *    double-commit race.
 *
 * FAILURE POSTURE: syncHomepageEventsFile never throws; a GitHub outage is a
 * logged 'failed' and the next write or the daily sweep repairs the file. The
 * trigger deliberately does NOT rethrow to request redelivery — the platform
 * retry would hammer a downed GitHub for the same regeneration the sweep
 * already guarantees.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { onDocumentWritten } from 'firebase-functions/v2/firestore';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { defineSecret } from 'firebase-functions/params';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { requireMemberOrAdminActor } from '../shared/memberActor';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { withServerErrorReporting } from '../errors/serverErrors';
import {
  MAX_INSTANCES_MEMBER,
  MAX_INSTANCES_SCHEDULED,
  MAX_INSTANCES_TRIGGER,
  CPU_SCHEDULED,
  CPU_TRIGGER,
} from '../shared/instanceLimits';
import type { EventStatus } from './events-core';
import {
  guardPublicSiteActor,
  guardPublicSiteTogglable,
  isHomepageSyncRelevant,
  parseSetPublicSiteInput,
  selectHomepageEvents,
  buildHomepageEventsFile,
  type PublicSiteEventSource,
} from './publicSite-core';
import { syncHomepageEventsFile, type HomepageSyncStatus } from './homepageRepo';

/**
 * Fine-grained GitHub PAT with `contents: write` on ONLY
 * SebMcCayen/kungsbacka-car-community-homepage. Deliberately a SEPARATE secret
 * from GITHUB_ISSUE_TOKEN (issues: write on the app repo) so neither token
 * carries the other's power. Provided via
 * `firebase functions:secrets:set HOMEPAGE_REPO_TOKEN`. Never committed,
 * logged, or returned.
 */
const HOMEPAGE_REPO_TOKEN = defineSecret('HOMEPAGE_REPO_TOKEN');

interface StoredEvent {
  status: EventStatus;
  createdByUserId?: string | null;
  publicSiteEnabled?: boolean;
}

// ---------------------------------------------------------------------------
// Shared regeneration
// ---------------------------------------------------------------------------

/** What one regeneration did — the sync outcome plus the feed size shipped. */
export interface HomepageRegenResult {
  status: HomepageSyncStatus;
  eventCount: number;
}

/**
 * Regenerates data/app-events.json from the CURRENT Firestore state and syncs
 * it to the homepage repo (skipping the commit when equivalent).
 *
 * The candidate query is two equality filters
 * (`publicSiteEnabled == true AND status == 'published'`) — served by
 * Firestore's built-in single-field index merge, NO composite index and
 * therefore no hand-deploy. The only-upcoming cut and the ascending sort are
 * applied in memory (selectHomepageEvents): the enabled-and-published set for
 * a single-town community is a handful of documents.
 *
 * The 160-char `desc` prefers the teaser `summary`; only events lacking one
 * have their details/private description fetched (one batched getAll) as the
 * fallback — the creator opted the event onto the public site, where the
 * public page shows the description anyway.
 *
 * Exported for deterministic tests and reused by every sync path above.
 */
export async function regenerateHomepageEvents(
  token: string,
  now: Date = new Date(),
): Promise<HomepageRegenResult> {
  const snapshot = await db
    .collection('events')
    .where('publicSiteEnabled', '==', true)
    .where('status', '==', 'published')
    .get();

  const sources: PublicSiteEventSource[] = snapshot.docs.map((doc) => {
    const data = doc.data() as {
      status: EventStatus;
      title?: string;
      startsAt: Timestamp;
      locationName?: string | null;
      approximateArea?: string | null;
      summary?: string | null;
    };
    return {
      eventId: doc.id,
      status: data.status,
      title: String(data.title ?? ''),
      startsAt: data.startsAt.toDate(),
      locationName: data.locationName ?? null,
      approximateArea: data.approximateArea ?? null,
      summary: data.summary ?? null,
      description: null,
    };
  });

  // Fallback desc: batched read of details/private for events with no summary.
  const needingDescription = sources.filter(
    (source) => !source.summary || source.summary.trim().length === 0,
  );
  if (needingDescription.length > 0) {
    const privateSnaps = await db.getAll(
      ...needingDescription.map((source) =>
        db.collection('events').doc(source.eventId).collection('details').doc('private'),
      ),
    );
    privateSnaps.forEach((snap, index) => {
      const description = snap.data()?.description;
      needingDescription[index]!.description = typeof description === 'string' ? description : null;
    });
  }

  const events = selectHomepageEvents(sources, now);
  const content = buildHomepageEventsFile(events, now);
  const status = await syncHomepageEventsFile(content, token, { eventCount: events.length });
  // One summary line per regeneration (same convention as the other sweeps);
  // 'unchanged' days stay quiet.
  if (status !== 'unchanged') {
    logger.info('Homepage events sync', { status, eventCount: events.length });
  }
  return { status, eventCount: events.length };
}

// ---------------------------------------------------------------------------
// events.setPublicSite — creator-or-admin toggle
// ---------------------------------------------------------------------------

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface SetPublicSiteResponse {
  eventId: string;
  publicSiteEnabled: boolean;
}

export const setPublicSite = onCall(CALLABLE_OPTS, async (request): Promise<SetPublicSiteResponse> => {
  // Creator-or-admin (mirrors events.complete): an active member may toggle an
  // event THEY created; an admin may toggle any — including force-unpublishing
  // someone else's event as the moderation safety valve.
  // requireMemberOrAdminActor rejects suspended/deleted/non-member callers
  // before ownership is even considered.
  const actor = await requireMemberOrAdminActor(request);

  const parsed = parseSetPublicSiteInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { eventId, enabled } = parsed.input;
  const eventRef = db.collection('events').doc(eventId);

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(eventRef);
    if (!snap.exists) {
      throw new HttpsError('not-found', 'Event not found.');
    }
    const event = snap.data() as StoredEvent;

    const actorGuard = guardPublicSiteActor(
      { uid: actor.uid, isAdmin: actor.isAdmin },
      { createdByUserId: event.createdByUserId },
    );
    if (!actorGuard.ok) {
      throw new HttpsError(actorGuard.code, actorGuard.message);
    }
    const statusGuard = guardPublicSiteTogglable(event.status, enabled);
    if (!statusGuard.ok) {
      throw new HttpsError(statusGuard.code, statusGuard.message);
    }

    // Idempotent no-op: the flag already holds the requested value. Skipping
    // the write keeps a repeated tap from churning updatedAt and firing a
    // pointless (if harmless) regeneration.
    if ((event.publicSiteEnabled === true) === enabled) {
      return;
    }

    const serverTimestamp = () => FieldValue.serverTimestamp();
    tx.update(eventRef, {
      publicSiteEnabled: enabled,
      publicSiteEnabledAt: enabled ? serverTimestamp() : null,
      updatedAt: serverTimestamp(),
    });
    // adminAuditEvents stays a record of ADMIN actions only (same rule as
    // events.complete): a creator toggling their own event writes no record —
    // the flag + publicSiteEnabledAt on the document are the member trace.
    if (actor.isAdmin) {
      tx.set(
        db.collection('adminAuditEvents').doc(),
        buildAdminAuditEvent(
          {
            adminId: actor.uid,
            action: enabled ? 'event.public_site_enable' : 'event.public_site_disable',
            targetType: 'event',
            targetId: eventId,
            reason: enabled ? 'Public event page enabled.' : 'Public event page disabled.',
          },
          serverTimestamp,
        ),
      );
    }
  });

  // No regeneration here — the flag write (when any) just fired
  // events-onPublicSiteWrite, which owns the sync (see the file header).
  return { eventId, publicSiteEnabled: enabled };
});

// ---------------------------------------------------------------------------
// events-onPublicSiteWrite — sync on change
// ---------------------------------------------------------------------------

export const onPublicSiteWrite = onDocumentWritten(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_TRIGGER,
    cpu: CPU_TRIGGER,
    concurrency: 1,
    document: 'events/{eventId}',
    memory: '256MiB',
    timeoutSeconds: 120,
    secrets: [HOMEPAGE_REPO_TOKEN],
  },
  async (firestoreEvent) => {
    const before = firestoreEvent.data?.before.data();
    const after = firestoreEvent.data?.after.data();
    if (!isHomepageSyncRelevant(before, after)) {
      return;
    }
    // Hermetic emulator suite: never regenerate (and never reach GitHub —
    // homepageRepo also guards) from tests. Same env switch the callables use
    // for enforceAppCheck and onEventPublished uses for its fan-out.
    if (process.env.FUNCTIONS_EMULATOR === 'true') {
      return;
    }
    // Not rethrown: a redelivery would re-run the same regeneration a downed
    // GitHub already failed; the daily events-syncHomepage sweep is the
    // guaranteed repair path (see the file header's failure posture).
    const result = await regenerateHomepageEvents(HOMEPAGE_REPO_TOKEN.value());
    if (result.status === 'failed') {
      logger.warn('Homepage sync failed after event write; daily sweep will repair', {
        eventId: firestoreEvent.params.eventId,
      });
    }
  },
);

// ---------------------------------------------------------------------------
// events-syncHomepage — daily prune/self-heal
// ---------------------------------------------------------------------------

export const syncHomepage = onSchedule(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_SCHEDULED,
    cpu: CPU_SCHEDULED,
    concurrency: 1,
    timeZone: 'Europe/Stockholm',
    memory: '256MiB' as const,
    timeoutSeconds: 300,
    // 04:40 local: after the 04:00-06:00 window in which the hourly autoClose
    // sweep typically completes yesterday's events (their default endsAt is
    // end-of-day, +6h grace), so the daily regen usually sees the final state.
    schedule: '40 4 * * *',
    secrets: [HOMEPAGE_REPO_TOKEN],
  },
  withServerErrorReporting('events.syncHomepage', async () => {
    await regenerateHomepageEvents(HOMEPAGE_REPO_TOKEN.value());
  }),
);
