/**
 * publicweb-eventPage — the server-rendered public event page (issue #768).
 *
 * Served through a Firebase Hosting rewrite on the DEDICATED public hosting
 * site (target `events`, site `kcc-events` — isolated from the admin site):
 * /e/{eventId} → this function; /e/{eventId}.ics → the calendar file. SSR
 * (rather than a public Firestore mirror + SPA) is the decided architecture:
 * events are `allow read: if isAuthenticated()` so an anonymous browser can
 * never read Firestore directly, and server-rendered OG tags give real link
 * previews in WhatsApp/Messenger/iMessage shares.
 *
 * WHAT THIS SERVES is decided per request by decidePublicPageState
 * (eventPage-core.ts): live only while the event is publicly enabled
 * (events/{eventId}.publicSiteEnabled — the creator's switch from the sibling
 * feature), `published`, and inside its effective end + the autoClose grace;
 * then the "ended" state; not-found for anything never public. Lifecycle is
 * per-request time math — no cleanup job.
 *
 * PRIVACY: the render model structurally has no attendee roster, no
 * organizer/creator identity and no street address (see eventPage-core.ts).
 * The member-gated details/private document is read ONLY for its description,
 * ONLY for a live page — the creator opted the event onto the public web.
 * The map is a Mapbox STATIC image with the pin + place name.
 *
 * This is an onRequest endpoint, not a callable: it is deliberately absent
 * from contracts/functions/functions.json (a registry of callables — the
 * health endpoint is absent on the same grounds), and App Check does not
 * apply (the whole point is anonymous, logged-out visitors). Abuse is bounded
 * by maxInstances and Hosting's CDN cache (Cache-Control below), and event
 * ids are unenumerable random Firestore ids.
 */

import { onRequest } from 'firebase-functions/v2/https';
import { defineSecret } from 'firebase-functions/params';
import { db } from '../firebase';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import {
  buildEventIcs,
  decidePublicPageState,
  mapboxStaticImageUrl,
  parseEventPagePath,
  renderEndedEventPage,
  renderLiveEventPage,
  renderNotFoundPage,
  toPageSource,
  type StoredEventDoc,
} from './eventPage-core';

/**
 * The PUBLIC (`pk.`) Mapbox token — the same public-by-design token the
 * Android app ships and the admin map uses (GitHub Actions secret
 * MAPBOX_ACCESS_TOKEN). Stored in Secret Manager for the function
 * (`firebase functions:secrets:set MAPBOX_ACCESS_TOKEN`); the OPERATOR must
 * also add the public site's domain to the token's URL restrictions. An empty
 * value degrades gracefully: the page renders without the map image.
 */
const MAPBOX_ACCESS_TOKEN = defineSecret('MAPBOX_ACCESS_TOKEN');

/**
 * CDN/browser caching: a live page may change (an edit, a cancellation, the
 * creator unpublishing), so keep it short but nonzero — Hosting's CDN absorbs
 * a link going viral without hammering the function, and a takedown
 * propagates within minutes.
 */
const CACHE_LIVE = 'public, max-age=300, s-maxage=600';
/** Ended pages are terminal; 404s may become live (a not-yet-public event). */
const CACHE_ENDED = 'public, max-age=600, s-maxage=3600';
const CACHE_NOT_FOUND = 'public, max-age=60, s-maxage=300';

const SECURITY_HEADERS: Record<string, string> = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
  // Self-contained page: inline styles only, images only from Mapbox statics.
  'Content-Security-Policy':
    "default-src 'none'; style-src 'unsafe-inline'; img-src https://api.mapbox.com",
};

export const eventPage = onRequest(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_MEMBER,
    memory: '256MiB',
    timeoutSeconds: 30,
    secrets: [MAPBOX_ACCESS_TOKEN],
  },
  async (req, res) => {
    for (const [header, value] of Object.entries(SECURITY_HEADERS)) {
      res.set(header, value);
    }
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.status(405).set('Allow', 'GET, HEAD').send('Method Not Allowed');
      return;
    }

    const parsed = parseEventPagePath(req.path);
    if (!parsed) {
      res
        .status(404)
        .set('Cache-Control', CACHE_NOT_FOUND)
        .type('html')
        .send(renderNotFoundPage());
      return;
    }
    const { eventId, ics } = parsed;

    const eventSnap = await db.collection('events').doc(eventId).get();
    const now = new Date();

    // The member-gated description is fetched ONLY when the page could be
    // live — never for a 404/ended answer, so a non-public event costs one
    // read and leaks nothing.
    let description: string | null = null;
    const data = eventSnap.exists ? (eventSnap.data() as StoredEventDoc) : null;
    const couldBeLive = data?.publicSiteEnabled === true && data.status === 'published';
    if (couldBeLive) {
      const privateSnap = await db
        .collection('events')
        .doc(eventId)
        .collection('details')
        .doc('private')
        .get();
      const stored = privateSnap.data()?.description;
      description = typeof stored === 'string' && stored.length > 0 ? stored : null;
    }

    const state = decidePublicPageState(data ? toPageSource(data) : null, eventId, now, description);

    switch (state.kind) {
      case 'live': {
        if (ics) {
          res
            .status(200)
            .set('Cache-Control', CACHE_LIVE)
            .set('Content-Disposition', `attachment; filename="event-${eventId}.ics"`)
            .type('text/calendar; charset=utf-8')
            .send(buildEventIcs(state.model, now));
          return;
        }
        res
          .status(200)
          .set('Cache-Control', CACHE_LIVE)
          .type('html')
          .send(
            renderLiveEventPage(state.model, {
              mapImageUrl: mapboxStaticImageUrl(state.model, MAPBOX_ACCESS_TOKEN.value()),
            }),
          );
        return;
      }
      case 'ended': {
        // The calendar file for a finished event is gone with the event.
        res
          .status(ics ? 404 : 410)
          .set('Cache-Control', CACHE_ENDED)
          .type('html')
          .send(renderEndedEventPage(state.title, state.cancelled));
        return;
      }
      case 'not-found': {
        res
          .status(404)
          .set('Cache-Control', CACHE_NOT_FOUND)
          .type('html')
          .send(renderNotFoundPage());
        return;
      }
    }
  },
);
