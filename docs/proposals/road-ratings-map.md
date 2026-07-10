# Proposal: "Vägkartan" — community road ratings & photo-spot map

> **Status:** Design proposal / feasibility study — **NOT approved for build.** Parked on the Future Ideas board pending explicit go-ahead.
> **Author:** Claude (delegated feasibility spike)
> **Date:** 2026-07-10
> **Recommendation (short version):** Feasible with unusually high reuse (drives, map, media, points, moderation are all in place) and a strong community fit — but ship it only with a hard privacy gate (mandatory endpoint trimming + timestamp stripping before any route goes public) and a hard safety stance (quality/scenic ratings only; no times, no speed, no leaderboards). Park until explicit go-ahead; if approved, build the private rate-and-tag slice first.

## 1. Summary

**Vägkartan** ("the road map") turns the drives members already record into a
community asset: after saving a drive, a member can tag it (*kurvig*,
*naturskön*, *bra vägbana*, *grusväg*, *laddvänlig*) and rate it 1–5. If — and
only if — the member explicitly shares it, a privacy-trimmed version of the
route becomes a public community route that other members can rate, browse on
a new map layer, and drive themselves. A second, lighter content type —
**fotoplatser** (photo spots) — lets members pin photogenic locations
(parking structures, golden-hour coastal spots along the Halland coast) with
a sample photo, riding the current car-photography wave on Instagram/TikTok.

The concept is proven in adjacent markets — calimoto and Detecht for
motorcycles, ROADS by Porsche at the premium end — but nothing serves a local
Swedish car community around Kungsbacka/Halland. It gives every member
segment something: cruisers get curated curvy roads, EV tourers get
charging-friendly route tags, photographers get spots, and the club gets an
evergreen content flywheel ("Månadens väg") that admins can run through the
existing announcements surface.

Two constraints shape everything below:

1. **Privacy:** recorded drives start and end at homes. Nothing recorded may
   become public without explicit sharing **and** backend-enforced trimming/
   fuzzing of endpoints. Today no sharing path exists at all —
   `rides/{rideId}` is strictly owner-scoped and `docs/product-decisions.md`
   already locks "Delning av saved drives ska inte inkludera exakt rutt som
   standard."
2. **Safety/legal:** no lap times, no speed leaderboards, no
   "fastest through segment" — ever. Ratings are scenic/quality only, so the
   feature cannot be read as encouraging *vårdslöshet i trafik*. This aligns
   with locked decisions ("Ingen toppfarts-/speedranking i MVP"; badges must
   not reward risky driving) and with the existing backend, which pointedly
   never stores a top-speed field.

## 2. Existing infrastructure we can reuse

This idea composes almost entirely out of shipped building blocks. Backend is
`functions/src` (Cloud Functions 2nd gen, `europe-west1`, App Check on
callables); Android is Kotlin/Compose under
`apps/android/app/src/main/java/com/kungsbackacarcommunity/app/`.

### 2.1 Drive recording + saved drives (the substrate)

- **Backend** — `functions/src/drives/saveDrive.ts` (`drives.save` callable):
  member-only, validates the recording, computes distance/duration/average
  speed server-side (`drive-calculations.ts` — clients never write stats),
  creates `rides/{rideId}`, idempotent per `sourceSessionId` via a
  deterministic doc ID + transaction. Its header is explicit: "**No top-speed
  field is ever stored or returned**" — the safety stance in §3 is already
  encoded here.
- **Route storage** — `functions/src/drives/drives-core.ts`:
  `rides/{rideId}` holds summary metadata only; GPS data is a client-uploaded
  Cloud Storage file at `rideRoutes/{uid}/{rideId}/route.bin` plus a
  `preview.png` map image, owner+member-gated by Storage rules. Route points
  are bounded (`MAX_ROUTE_POINTS = 20_000`) and schema-validated (zod).
- **Android** — `drives/SavedDrive.kt`, `DrivesRepository.kt`,
  `FirebaseDrivesRepository.kt`, `RecordDriveScreen.kt`,
  `DriveRecordingCoordinator.kt`: recording, the save flow, and the
  owner-scoped saved-drives list. The post-save moment in
  `RecordDriveScreen.kt` is exactly where the tag+rating prompt slots in.
- **Gap that matters:** there is **no sharing/trimming/fuzzing code anywhere
  in `functions/src/drives/`** — drives are private, full stop. The entire
  §5 privacy pipeline is new build, not adaptation.

### 2.2 Map — `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/map/`

`MapMarkers.kt` + `MapScreen.kt`/`MapRoute.kt`: a Mapbox-backed map screen
with a pure, JVM-testable marker/camera model (`MapMarker`, `MapMarkerKind`,
`MapCameraPosition`) currently rendering live-location markers. A Vägkartan
layer adds two new marker kinds (route pin, photo-spot pin) and — beyond
markers — the first **polyline** rendering in the app (route geometry). The
package's pure-logic-vs-Mapbox split is the right seam to extend.
`SavedDrive.kt` also notes the Mapbox route-overview for the drive detail
screen hasn't landed yet — building it once serves both the private detail
view and the public route preview.

### 2.3 Media pipeline — `.../app/media/`

`MediaUploader.kt` / `FirebaseMediaUploader.kt` /
`ImageUploadCoordinator.kt` / `ImagePicker.kt`: pick → size-cap check →
upload bytes to an exact Storage path → persist the **path** (never a URL;
`StorageImageUrl.kt` resolves lazily at render time). Used today for avatars
and vehicle photos; photo-spot sample photos are a third caller with a new
path family (e.g. `photoSpots/{spotId}/...`) and matching Storage rules.

### 2.4 Points & badges (community mechanics)

- **Kronpoäng** — `functions/src/points/ledger.ts` + `points-core.ts`:
  `creditPoints()` with transactional ledger append + denormalized balance,
  idempotency key = entry doc ID (replay-safe automated awards), suspended/
  deleted users never earn. A "first to share a route that reaches a high
  aggregate rating" award is one `creditPoints` call with a deterministic key
  (e.g. `road_rating_featured_{routeId}`). Requires adding a
  `PointsTransactionSource` value to the closed enum in `points-core.ts`.
- **Badges** — `functions/src/badges/badge-core.ts` + `awards.ts`:
  closed `BADGE_KEYS` catalog, idempotent award-if-absent, and two design
  rules already written into the file that bind us: "no speed/distance/racing
  badges; nothing may encourage unsafe driving" and all user-facing text in
  Swedish. A *fotoplats* badge family (first spot shared / N spots / featured
  spot) fits; anything speed- or distance-shaped does not.

### 2.5 Announcements ("Månadens väg" surface)

`apps/admin/src/features/announcements/index.ts`: admin CRUD on
`announcements/{id}` (`title`, `body`, `active`, timestamps), member query
`active == true` ordered by `createdAt desc`, already read by Android.
"Road of the month" can launch as a plain announcement (zero new backend) and
later graduate to a `featuredRouteId` link on the route document.

### 2.6 Moderation & reporting

`functions/src/events/reportChatMessage.ts` + `moderateReports.ts`: the
report pattern to copy — deterministic report doc ID for (target, reporter,
reason) dedupe, reports never client-readable, admin-only moderation queue,
audited admin actions. Public routes and photo spots are user-generated
content visible community-wide; they need this pipeline from day one, plus
the existing blocking domain (`functions/src/blocking/`) to filter blocked
authors' content.

### 2.7 Cross-cutting

- **Access** — `functions/src/shared/memberActor.ts` / `access.ts`:
  `requireMemberActor` for all rating/sharing/pinning callables (saving
  drives is already member-only, so this is consistent).
- **Feature flags** — `functions/src/shared/featureFlags-core.ts` +
  `featureFlags.ts`, admin-set via the audited `admin.setFeatureFlag`: add a
  `roadRatings` key, **default `false`** (UGC + location feature ⇒ kill
  switch mandatory per the performance-first/flag rule in
  `docs/product-decisions.md`).
- **Geo validation** — `functions/src/crownHunt/crown-hunt-geo.ts`:
  `haversineDistanceMeters`, `isValidCoordinate`, plausibility guards —
  reusable verbatim for photo-spot coordinate validation and for the
  endpoint-trimming distance math in §5.
- **Audit** — admin curation actions (feature/unfeature/remove) follow the
  `adminAuditEvents` pattern used by `functions/src/notifications/adminSend.ts`
  and the `functions/src/admin/` callables.

## 3. Safety, legal & market framing

### 3.1 Safety stance: scenic quality, never speed (non-negotiable)

The gating design rule: **nothing in Vägkartan may rank, reward, or display
anything derived from how fast a route was driven.**

- **No lap times, no segment times, no speed leaderboards, no "fastest
  through" anything.** A Strava-segments-for-cars mechanic would directly
  encourage *vårdslöshet i trafik* (reckless driving, Brottsbalken 1962:700
  via Trafikbrottslagen 1951:649) on public roads, expose the club to the
  exact liability the product decisions already exclude ("Ingen
  toppfarts-/speedranking i MVP"; Kronjakt "får inte uppmuntra fortkörning …
  snabbast-till-plats-beteende"), and would likely be fatal in app-store
  review framing. This proposal treats it as permanently out of scope, not
  merely deferred.
- **Timestamps are stripped from shared routes** (§5.2). This is the
  structural enforcement: without per-point times, neither we nor anyone
  scraping the data can reconstruct segment speeds. The public artifact is
  geometry + tags + ratings, nothing kinetic.
- **Rating dimensions are quality adjectives**, not performance: *kurvig*
  (curvy), *naturskön* (scenic), *bra vägbana* (surface quality), *grusväg*
  (gravel), *laddvänlig* (EV-charger-friendly) + a single 1–5 overall
  "sevärdhet/körglädje" score. Copy stays in the "njut av vägen" register.
- **Driving-mode rule applies:** rating/tagging happens **after** the drive,
  on the saved-drive screen — never mid-recording (product decision: no
  interaction that increases risk while driving).

### 3.2 Speed-camera tagging: recommend AGAINST

Legality in Sweden, for the record: **radar detectors are banned** (lag
1988:15 om förbud mot vissa radarvarnare), but **apps showing fixed
speed-camera (ATK) locations are legal** — Trafikverket publishes fixed
camera locations openly, and mainstream navigation apps display them in
Sweden. So a "fartkamera" tag would probably be lawful.

Recommend against it anyway. It contributes nothing to a scenic-roads value
proposition; its only realistic use-signal is "where can I speed safely,"
which contradicts §3.1's framing, undermines the club's safety posture with
partners/municipality, and invites exactly the app-review and press framing
we are designing away from. Keep it, at most, as an open product question
(§8) — the default answer should be no.

### 3.3 Market context

- **calimoto / Detecht** — motorcycle route recording + community-rated curvy
  roads; validates the record→rate→share loop and the "curviness" tag as the
  hero attribute.
- **ROADS by Porsche** — premium curated driving-roads app; validates the
  editorial "featured route" mechanic (our "Månadens väg").
- **Gap:** none of these serve a local Swedish *car* community, none have a
  photo-spot layer, and none integrate with a club's own points/badges/events
  economy. The photo-spot layer is differentiated and rides an active trend
  (car-photography content on Instagram/TikTok); the local scale
  (Kungsbacka/Halland) is a strength — 20–30 members can realistically seed
  10–20 quality routes and spots, which is a useful map, whereas a global app
  at that density is empty.
- **Adjacent parked ideas:** hyperlocal EV-charger status intel is
  deliberately **not** scoped in (see §8 and the `laddvänlig` tag note in
  §4.5); nearby-notifications (`docs/proposals/nearby-notifications.md`)
  could later notify "you're near a top-rated route/photo spot" but is its
  own parked proposal.

## 4. Proposed design

### 4.1 Content lifecycle

```
record drive ──save──► rides/{rideId} (private, existing)
                          │
                          │  post-save prompt: tags + 1–5 rating (private)
                          ▼
                    rated private drive
                          │
                          │  explicit "Dela till Vägkartan" flow:
                          │  preview → MANDATORY endpoint trim/fuzz →
                          │  timestamp strip → title/tags confirm
                          ▼
                 sharedRoutes/{routeId} (community-visible, pending|live)
                          │                          │
              other members rate/tag it       report → moderation queue
                          │                          │
                          ▼                          ▼
              aggregate rating on doc      admin remove / author ban
                          │
                          ▼
        admin features "Månadens väg" (announcement + featured flag)

photo spot: pick location on map + photo + category ──► photoSpots/{spotId}
            (same share-explicitly / moderate / feature path, no route data)
```

Unsharing must always work: the author (or an admin) can pull a route or spot
at any time; the public doc and its Storage artifacts are deleted, while the
private `rides/{rideId}` original is untouched.

### 4.2 Firestore data model

- **`rides/{rideId}` (existing, extended):** add optional private fields
  `myRating: number(1–5)?`, `myTags: string[]?` (closed tag enum). Written
  only via a new callable (not client-direct — rules keep rides writes
  backend-only), never visible to anyone but the owner.
- **`sharedRoutes/{routeId}` (new, backend-only writes):**
  `authorUid`, `authorDisplayName` (denormalized), `title`, `tags[]`,
  `status: pending|live|removed`, `sourceRideId` (never exposed to clients —
  admin/debug linkage only), `region` (coarse, e.g. kommun),
  `distanceMeters` (of the trimmed geometry), `startArea`/`endArea` (coarse
  labels, never precise endpoints), `ratingCount`, `ratingSum`,
  `avgRating` (denormalized, maintained transactionally like the points
  balance), `featured: boolean`, `featuredMonth?`, `createdAt`, `updatedAt`.
  Route geometry itself lives in Storage: `sharedRoutes/{routeId}/route.bin`
  (trimmed, timestamp-free) + `preview.png`, member-read Storage rules.
- **`sharedRoutes/{routeId}/ratings/{uid}` (new, backend-only writes):**
  `stars: 1–5`, `tags[]?`, `updatedAt`. Doc ID = rater UID ⇒ one rating per
  member, idempotent updates, no self-rating (`authorUid` check in the
  callable).
- **`photoSpots/{spotId}` (new, backend-only writes):** `authorUid`,
  `displayName`, `title`, `category` (enum: `parkeringshus`, `kustlinje`,
  `industri`, `natur`, `övrigt`), `lat`/`lng` (validated via
  `crown-hunt-geo.ts` helpers; spots are chosen deliberately so precision is
  fine — but never auto-derived from a drive), `photoPath` (Storage,
  `photoSpots/{spotId}/photo.jpg`), `note` (bounded), `status`, `featured`,
  aggregate rating fields as above, timestamps.
- **Reports:** `sharedRoutes/{routeId}/reports/{deterministicId}` and
  `photoSpots/{spotId}/reports/{...}` following `chatReportDocId`-style
  dedupe from `functions/src/events/chat-core.ts`; never client-readable.
- **Reads:** `sharedRoutes` and `photoSpots` with `status == live` get
  authenticated-member read rules (composite indexes: `status ASC +
  avgRating DESC`, `status ASC + createdAt DESC`); everything else
  backend-only. Blocked-author filtering happens client-side against the
  member's block list (same approach as chat).

### 4.3 Backend callables (sketch — deployed as a new `roadRatings` export group)

All member-gated (`requireMemberActor`), App Check enforced, flag-gated on
`roadRatings`, zod-validated in a pure `roadRatings-core.ts`:

- `roadRatings.rateOwnDrive` — `{rideId, stars?, tags?}`; owner check; writes
  the private fields on `rides/{rideId}`.
- `roadRatings.shareRoute` — `{rideId, title, tags, trimStartMeters,
  trimEndMeters}`; owner check; **server** loads the private route file,
  enforces minimum trim (§5.1), strips timestamps, snaps/fuzzes the new
  endpoints, writes the trimmed artifact to `sharedRoutes/{routeId}/`,
  creates the doc (`status: pending` or `live` — see §8 Q2). Idempotent per
  `rideId` (deterministic route ID `shared_{rideId}`); re-share after unshare
  replaces. Rejects routes too short to trim safely.
- `roadRatings.unshareRoute` — author or admin; deletes doc + Storage
  artifacts.
- `roadRatings.rateSharedRoute` — `{routeId, stars, tags?}`; not the author;
  transactional upsert of `ratings/{uid}` + aggregate recompute (read old
  rating → apply delta → update `ratingCount/ratingSum/avgRating` in one
  transaction — the `points/ledger.ts` pattern).
- `roadRatings.submitPhotoSpot` / `updatePhotoSpot` / `removePhotoSpot` —
  create (returns the Storage upload path for the photo, like
  `drives.save` returns `routePath`), edit own, remove own-or-admin.
- `roadRatings.reportContent` — `{targetType, targetId, reason, details?}` →
  report doc, `reportChatMessage.ts` semantics (dedup, opaque response).
- `roadRatings.adminModerate` — admin-only: approve/remove/feature/unfeature;
  writes `adminAuditEvents`; featuring optionally credits the author's
  Kronpoäng (idempotency key = `road_featured_{routeId}_{month}`) and can
  create the "Månadens väg" announcement.

### 4.4 Client UX (Android; admin web)

- **Post-save prompt (Android):** after a successful save in
  `RecordDriveScreen.kt`, a lightweight sheet: "Hur var vägen?" — star row +
  tag chips, skippable. Also editable later from the saved-drive detail.
- **Share flow:** from drive detail: "Dela till Vägkartan" → map preview
  showing the route with trim handles at both ends (minimum trim enforced and
  visualized — the user sees exactly what becomes public) → title + tags →
  privacy notice → share. Never more than one screen away from "avpublicera".
- **Vägkartan layer (Android `map/`):** a layer toggle on the existing map
  screen: route polylines colored by average rating, photo-spot pins with
  photo thumbnails; tapping opens a detail sheet (preview image, tags,
  ratings, "Öppna i Google Maps" deep link per the no-in-app-navigation
  decision). List view sorted by rating/newness for browsing.
- **Photo-spot pinning:** long-press on map (or "från min position") → photo
  pick (existing `ImagePicker`/`ImageUploadCoordinator`) → category + note.
- **Admin web (`apps/admin/src/features/road-ratings/` — new):** moderation
  queue (pending items + reports), feature/unfeature with month picker,
  remove with audit reason; mirrors the event-chat moderation +
  announcements module shapes.
- **All user-facing text in Swedish**, i18n-structured; no "KCC" hardcoding
  (brand-ready rule) — "Vägkartan" itself is brand-neutral.

### 4.5 Community mechanics

- **Månadens väg:** admin picks a featured route monthly → announcement (§2.5)
  + `featured` flag renders a crown marker on the map. Zero-backend v1.
- **Kronpoäng:** a bounded, non-gameable award: author earns points when
  their shared route is **featured** (admin-triggered, audited, idempotent) —
  not merely when it accumulates ratings, which a small friend group could
  farm. Backend-authoritative per the locked Kronpoäng decisions.
- **Badge family (additions to `BADGE_KEYS`):** e.g. `first_shared_route`
  ("Vägvisare"), `first_photo_spot` ("Fotospanare"), `featured_route`
  ("Månadens väg"). All positive/non-competitive, Swedish wording, no
  speed/distance mechanics — per the rules already documented in
  `badge-core.ts`.
- **`laddvänlig` tag** is just a tag. Charger-status notes on pins are **out
  of scope** (open question §8 Q4 / possible sibling proposal) — live charger
  status is a data-freshness and source-licensing problem this feature should
  not inherit.

## 5. Privacy

This is the make-or-break section: a recorded drive is a location diary whose
endpoints are usually **home addresses**.

### 5.1 Mandatory, backend-enforced endpoint protection

- **Private by default, share is explicit, per-route, and reversible** —
  consistent with the locked decision that saved-drive sharing must not
  include the exact route by default. There is no "share all my drives"
  switch.
- **Minimum endpoint trim enforced server-side** in
  `roadRatings.shareRoute`: the first and last **≥500 m** (tunable constant)
  of the recorded track are always removed; the user can trim more via the
  UI but never less. Client-supplied trim values are treated as requests —
  the server recomputes distances (`haversineDistanceMeters`) and rejects or
  extends insufficient trims. Routes shorter than ~2 km can't be shared
  (nothing meaningful survives a safe trim).
- **Endpoint fuzzing on top of trimming:** the trimmed route's new endpoints
  are snapped to a coarse grid (~250 m) so the cut point itself doesn't leak
  "500 m from home, on my street." Publicly displayed start/end are coarse
  area labels (`startArea`/`endArea`), never coordinates.
- **The public artifact is a new file**, never the original:
  `sharedRoutes/{routeId}/route.bin` is server-derived; the private
  `rideRoutes/{uid}/{rideId}/route.bin` remains owner+member-gated and
  untouched. `sourceRideId` is stored for admin lineage but never returned to
  clients.

### 5.2 Timestamp stripping (privacy AND safety)

Per-point timestamps are removed from the shared artifact. This anonymizes
*when* the member drives (commute patterns) **and** structurally prevents
speed reconstruction (§3.1). The shared route carries geometry + trimmed
distance only — no duration, no average speed, even though the private ride
doc has them.

### 5.3 Photo spots and photos

- Spots are **deliberately chosen public places**, entered by pin — never
  auto-suggested from recorded tracks (which would leak where a member
  stops).
- **EXIF is stripped** client-side before upload (GPS, timestamps, device
  IDs) — the pin's coordinate is the only location data, chosen consciously.
- Content policy surfaced at upload + enforced by moderation: no readable
  license plates of others' cars without consent, no identifiable people, no
  private land presented as accessible. Photos removed with the spot.
- Photo-spot photos are member-read (Storage rules), matching the app's
  member-gated media posture.

### 5.4 Attribution, deletion, moderation

- **Attribution is an author choice at share time:** display name or
  "En medlem" (anonymous). Anonymous still stores `authorUid` server-side for
  moderation accountability.
- **Account deletion** (existing `functions/src/account/` flow) must cascade:
  delete or anonymize the member's shared routes, ratings, and photo spots —
  add this to the deletion checklist in the same change that creates the
  collections.
- **Blocked users:** content from members you've blocked is filtered from
  your Vägkartan (client-side against `userBlocks`, as chat does).
- **No coordinates in logs** (Kronjakt rule, adopted verbatim).
- Cost note: at 20–30 members, all of this is trivially inside the SEK
  500/month budget (ADR-001) — tens of small docs, a handful of Storage
  objects, no scheduled jobs required. The one CPU-shaped task
  (trim/strip/rewrite a ≤20k-point route file) runs in a single callable
  invocation comfortably under a 256 MiB / 30 s budget.

## 6. Effort estimate & phasing

Rough order-of-magnitude for one engineer familiar with the codebase. Phases
are independently shippable; everything behind the `roadRatings` flag
(default off).

- **Phase 1 — Private tags & ratings (~3–4 days):** tag/star fields on
  `rides`, `rateOwnDrive` callable + rules/tests, post-save prompt + editing
  on the drive detail screen. Zero privacy exposure (nothing public yet);
  immediately useful ("which of my drives was that great gravel loop?").
  Also a live experiment: if members don't rate privately, stop here.
- **Phase 2 — Route sharing + Vägkartan layer (~1.5–2 weeks):** the core:
  `shareRoute`/`unshareRoute` with trim/fuzz/strip pipeline (+ thorough unit
  tests on the trim math — this is the security-sensitive code),
  `sharedRoutes` model/rules/indexes, `rateSharedRoute`, Android share flow
  with trim preview, map layer with polylines + detail sheet. Includes the
  Mapbox polyline rendering the drive-detail screen wants anyway.
- **Phase 3 — Photo spots (~1 week):** `photoSpots` model + callables,
  Storage rules + EXIF stripping, pin/submit UX, pins on the layer.
- **Phase 4 — Moderation & admin (~4–5 days):** report callable, admin-web
  moderation queue + feature/remove + audit; blocked-author filtering.
  (If Phase 2 ships with `status: pending` pre-moderation, a minimal admin
  approve action moves into Phase 2.)
- **Phase 5 — Community mechanics (~2–3 days):** Månadens väg flow
  (announcement + featured flag), Kronpoäng on feature, badge additions.

**Total: roughly 4–5 weeks** end-to-end, but Phase 1 alone is a meaningful,
risk-free first slice and a genuine kill-early checkpoint.

## 7. Risks

| # | Risk | Severity | Notes |
|---|------|----------|-------|
| R1 | **Home-location leak** — trimming bug, insufficient trim, or endpoint inference exposes where a member lives | **Critical** | Backend-enforced minimum trim + grid-snap fuzz + coarse labels only + new derived artifact; unit-test the pipeline hard; pre-launch review of first shared routes. |
| R2 | **Speed/competition drift** — community norms push toward "how fast," or a future request adds times/leaderboards | **High** | Structural: no timestamps in public data; §3.1 declared permanently out of scope; copy discipline; product-decisions.md already locks it. |
| R3 | **UGC liability/abuse** — inappropriate photos, plates/people in shots, routes over private roads, trespass-y photo spots (parking structures are private property) | **Medium-High** | Moderation pipeline from day one, content policy at upload, report + admin remove + audit; consider pre-moderation (`pending`) for photos (§8 Q2). |
| R4 | **Cold start / low density** — with 20–30 members the map may look empty and die quietly | **Medium** | Admin-seeded starter routes ("klassiker": Onsalahalvön, Fjärås Bräcka, coast road) at launch; Phase 1 kill-checkpoint before public surface is built. |
| R5 | **Rating gaming** — friends five-starring each other's routes for status/points | **Low-Medium** | One rating per member (doc ID = uid), no self-rating, points tied to admin featuring only, small-community social pressure. |
| R6 | **Scope creep toward navigation/charging data** — turn-by-turn, live charger status | **Medium** | Locked decision: deep-link to Google Maps only; charger status explicitly out of scope (§8 Q4). |
| R7 | **Mapbox rendering effort underestimated** — first polyline layer in the app | **Low** | Contained; also pays down the already-noted drive-detail route-overview gap. |

## 8. Open product questions (for the human)

1. **Visibility tier:** is Vägkartan browsing member-only (consistent with
   "exact live positions require subscription" and member-gated drive
   storage), or is a read-only teaser visible to free users as a conversion
   hook? Recommendation: member-only in v1 — simplest rules, consistent
   posture.
2. **Pre- or post-moderation:** do shared routes/photo spots go live
   immediately (`live`, post-moderated via reports) or queue for admin
   approval (`pending`)? At 20–30 members, pre-moderation is cheap and safest
   for photos; routes could go live immediately. Recommendation: pre-moderate
   photo spots, post-moderate routes.
3. **Speed-camera tag:** §3.2 recommends against. Confirm it stays out (our
   strong recommendation), or explicitly accept the framing risk.
4. **Charger-status notes on pins:** keep the `laddvänlig` tag only, and spin
   hyperlocal charger intel (live status, plug types, "kön vid Ionity
   Kungsbacka") into a sibling proposal if there's appetite? Recommendation:
   yes — separate concern, separate data problem.
5. **Anonymous sharing default:** display name or "En medlem" as the default
   attribution at share time? (Privacy-forward says anonymous default;
   community-building says named default with an easy toggle.)
6. **Minimum trim value:** is 500 m per end + 2 km minimum route the right
   privacy floor for the Kungsbacka geography (rural stretches may warrant
   more)? Needs a decision before Phase 2.

## 9. Recommendation

**Park on the Future Ideas board; do not build without explicit go-ahead.**
When prioritized, this is one of the strongest-fit ideas in the backlog:

- **Reuse is exceptional.** Recording, saved drives, the map screen, the
  media pipeline, points, badges, announcements, moderation patterns, feature
  flags, and geo validation all exist — the genuinely new pieces are the
  trim/fuzz/strip pipeline, two content collections, and one map layer.
  Nothing here depends on unbuilt platform work (unlike push-dependent
  ideas): drives and the map are shipped Android features.
- **It compounds the app's core loop.** Recorded drives currently terminate
  in a private list; Vägkartan gives them a community payoff and gives the
  map screen a reason to open when nobody is live-sharing. The photo-spot
  layer is cheap, on-trend, and unserved by any competitor.
- **The two gates are known and designable, not open-ended:** the privacy
  pipeline (R1) is a bounded engineering problem with a testable spec, and
  the safety framing (R2) is already consistent with locked product
  decisions — this proposal just needs those two stances adopted as
  non-negotiable acceptance criteria.

**Concretely, if approved:** build Phase 1 (private tags/ratings) first as a
cheap engagement probe; proceed to the public map only if members actually
rate their drives; require a focused review of the trim/fuzz implementation
before the first route ever goes public; launch with 3–5 admin-seeded
starter routes; keep speed cameras and charger status out.

## Appendix A — files referenced

- `functions/src/drives/saveDrive.ts`, `functions/src/drives/drives-core.ts`,
  `functions/src/drives/drive-calculations.ts`,
  `functions/src/drives/deleteDrive.ts`
- `functions/src/points/ledger.ts`, `functions/src/points/points-core.ts`
- `functions/src/badges/badge-core.ts`, `functions/src/badges/awards.ts`
- `functions/src/events/reportChatMessage.ts`,
  `functions/src/events/moderateReports.ts`, `functions/src/events/chat-core.ts`
- `functions/src/notifications/adminSend.ts`
- `functions/src/shared/featureFlags.ts`,
  `functions/src/shared/featureFlags-core.ts`,
  `functions/src/shared/memberActor.ts`, `functions/src/shared/access.ts`
- `functions/src/crownHunt/crown-hunt-geo.ts`
- `functions/src/blocking/`, `functions/src/account/`
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/drives/`
  (`SavedDrive.kt`, `DrivesRepository.kt`, `FirebaseDrivesRepository.kt`,
  `RecordDriveScreen.kt`, `DriveRecordingCoordinator.kt`)
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/map/`
  (`MapMarkers.kt`, `MapScreen.kt`, `MapRoute.kt`)
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/media/`
  (`MediaUploader.kt`, `FirebaseMediaUploader.kt`,
  `ImageUploadCoordinator.kt`, `ImagePicker.kt`, `StorageImageUrl.kt`)
- `apps/admin/src/features/announcements/index.ts`
- `docs/product-decisions.md`
- `docs/proposals/nearby-notifications.md` (adjacent parked idea)
