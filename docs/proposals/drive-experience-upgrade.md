# Proposal: "Drivelogg 2.0" — post-drive experience, streaks & share cards

> **Status:** Design proposal / feasibility study — **NOT approved for build.** Parked on the Future Ideas board pending explicit go-ahead.
> **Author:** Claude (delegated feasibility spike)
> **Date:** 2026-07-12
> **Recommendation (short version):** Build Phase 1 (route persistence + post-drive stat card + animated replay) as soon as a slot opens — it is the highest-leverage competitive response to Competitor X, closes an existing product gap (the drive-detail map placeholder), and carries near-zero privacy or safety exposure. Streaks/milestones and share cards follow as separate flag-gated slices; everything speed-shaped stays out permanently.

## 1. Summary

**Drivelogg 2.0** upgrades the app's existing drive recording from "a list of
rows with three numbers" into a rewarding post-drive experience: a designed
**stat card** the moment a drive is saved, an **animated route replay** on the
Mapbox map, **streaks and cumulative milestones** wired into the
backend-authoritative Kronpoäng ledger, a private cumulative **"Mina vägar"**
map layer of every road a member has recorded, and **on-device share cards**
sized for Instagram stories / TikTok.

This is a direct competitive response. **Competitor X** (a US indie
drive-tracking app, "Strava for driving") has built a fast-growing product on exactly this loop —
park → stat card → celebration → share — and its designed share cards are its
growth engine. A full teardown is being written in parallel as
`docs/competitor-analysis/competitor-x.md`; the short version that matters
here:

- Competitor X's core loop (record → replay → stats/streaks → share card) is
  **cheap for us to match** because our recording, saved-drives backend, map
  stack, points ledger, and media pipeline already exist (§2).
- Competitor X's **Android app is its weak flank** — reviews cite wildly wrong
  speed detection, broken friend-adding, a broken theme switcher, broken
  Android Auto search, and buggy sharing. We are Android-first; matching their
  loop *well* on Android beats them where they are worst.
- Competitor X's speed gamification (top speed on cards, g-force, "speed traps",
  speed-colored share routes) is its **legal and brand liability**, not its
  moat. We deliberately do not copy it (§3.2) — our locked product decisions
  already forbid it, and our backend pointedly never stores a top speed.

Together with two already-parked sibling proposals —
`docs/proposals/convoy-mode.md` (their flagship convoy feature) and
`docs/proposals/road-ratings-map.md` (their "roads" content layer, done as
community ratings instead) — this proposal completes coverage of Competitor X's
entire feature set, while we keep everything they lack: events, RSVP, chat,
partners, offers, and a real local community.

Scope discipline: this proposal is **experience on top of existing
recording**. It does not touch how drives are recorded (no auto-start, no
background tracking), adds no public/social surface inside the app, and adds
no speed mechanics of any kind.

## 2. Existing infrastructure we can reuse

Backend is `functions/src` (Cloud Functions 2nd gen, `europe-west1`, App Check
on callables); Android is Kotlin/Compose under
`apps/android/app/src/main/java/com/kungsbackacarcommunity/app/`.

### 2.1 Drive recording + saved drives (the substrate)

- **Backend** — `functions/src/drives/saveDrive.ts` (`drives.save` callable):
  member-only (`requireMemberActor`), zod-validated, idempotent per
  `sourceSessionId` (deterministic doc ID + transaction), creates
  `rides/{rideId}` with server-computed stats. Its header is explicit: "**No
  top-speed field is ever stored or returned**."
- **Server-side plausibility validation already exists** —
  `functions/src/drives/drive-calculations.ts` discards segments implying
  > 200 km/h (`MAX_PLAUSIBLE_SPEED_MPS`) and non-positive time deltas;
  `drives-core.ts` enforces ordered timestamps (`guardRoutePoints`),
  `endedAt > startedAt` (`guardDriveTimes`), a 20 000-point cap
  (`MAX_ROUTE_POINTS`), and coordinate ranges. This is the anti-fake-GPS
  foundation §4.4 builds on — the marginal work is *thresholds for
  points-bearing drives*, not a validation pipeline from scratch.
- **Route storage contract exists but is half-implemented — the gap that
  matters:** `drives-core.ts` defines canonical Storage paths
  (`rideRoutes/{uid}/{rideId}/route.bin` + `preview.png`,
  owner+member-gated in `firebase/storage.rules`), and `drives.save` returns
  them — but the Android client **never uploads either file** (no
  `rideRoutes`/`routePath` reference anywhere under `apps/android/`), and the
  drive detail screen ships a placeholder card
  (`savedDrives_routeOverviewPlaceholder` in `drives/DrivesScreen.kt`).
  **Route geometry is currently thrown away after save.** Everything in this
  proposal that shows a map — stat card thumbnail, replay, heatmap, share
  card — depends on finally landing that upload. It is Phase 1's first task
  and needs no new backend: the callable contract and Storage rules are done.
- **Android write side** — `drives/DriveRecording.kt` (defines the pure
  `DriveRecorder`, backend parity constants), `DriveRecordingCoordinator.kt`
  (state machine: `Recording → PromptSave → Saving → Saved`),
  `RecordDriveScreen.kt`. The
  `RecordingState.Saved` branch — today a single line of body text — is
  exactly where the stat card replaces a sentence with a moment.
- **Android read side** — `drives/SavedDrive.kt` + `DriveFormatters`
  (locale-stable km / h min / km h formatters, reused verbatim on the card),
  `FirebaseDrivesRepository.kt`, `DrivesScreen.kt` (list + detail).

### 2.2 Map & polyline rendering — already shipped

`shell/MapboxMapSurface.kt` (the map-first shell's real Mapbox v11 surface)
already contains everything the replay and heatmap need at the rendering
level: a `PolylineAnnotationManager` (`routeLineManager`), an
`applyRouteOverlay()` that draws a route line + destination marker and
camera-fits to the whole route with density-corrected padding, and defensive
`runCatching` wrappers so a partial draw degrades instead of crashing. The
`MapSurface` seam with a `StubMapSurface` fallback keeps config-less CI builds
green — new map features inherit that for free. The older
`map/MapScreen.kt`/`MapMarkers.kt` package shows the pure-model-vs-Mapbox
split to preserve. Animated draw-on replay (§4.5) is an incremental extension
of the existing polyline path, not new plumbing.

### 2.3 Kronpoäng ledger (streak/milestone rewards)

`functions/src/points/ledger.ts` + `points-core.ts`: `creditPoints()` appends
a ledger entry and updates the denormalized balance in one transaction;
**idempotency key = entry document ID**, so a replayed milestone award is a
transactional no-op (`milestone_{uid}_dist_1000km` can simply never
double-pay); `AtomicReadGuard` lets a caller enforce caps/uniqueness inside
the same transaction (the Kronjakt anti-double-claim pattern);
suspended/deleted users never earn. Milestone awards are single
`creditPoints` calls with deterministic keys. One enum change required:
`POINTS_TRANSACTION_SOURCES` is closed — add a `'drive'` source (the same
change `road-ratings-map.md` §2.4 flagged for its domain).

### 2.4 Badges — reusable, but with a documented rule in tension

`functions/src/badges/badge-core.ts` + `awards.ts`: closed `BADGE_KEYS`
catalog, idempotent award-if-absent (doc ID = badge key), `tryAutomaticAward`
fire-and-log hook, Swedish wording. **But `badge-core.ts` encodes a design
rule that binds us: "no speed/distance/racing badges; nothing may encourage
unsafe driving."** Speed badges are obviously out. Whether *cumulative,
non-competitive* distance milestones ("100 mil körda") violate the spirit of
the "no distance badges" clause is a genuine product call — this proposal
recommends milestones as Kronpoäng credits + a non-badge "Milstolpar" list in
v1, with the badge-catalog question escalated to the human (§8 Q3) rather
than quietly relaxed.

### 2.5 Media pipeline & design system (share cards, previews)

- `media/FirebaseMediaUploader.kt` (`putBytes` + content-type metadata,
  `createIfAvailable` guard) uploads the `preview.png` the backend already
  expects; `media/ImageCompressor.kt` handles bitmap → bounded JPEG/PNG
  bytes. Share-card export itself is *not* an upload — the card renders
  on-device and goes out through the Android share sheet — so it adds no
  Storage surface at all.
- `design/` (KccTheme, KccPalette) is the brand-ready token source: card
  branding comes from theme tokens + a logo resource, never a hardcoded
  "KCC" string (locked brand-ready rule in `docs/product-decisions.md`).

### 2.6 Cross-cutting

- **Feature flags** — `functions/src/shared/featureFlags-core.ts` (closed
  `FEATURE_FLAG_DEFAULTS` map, admin-set via audited `admin.setFeatureFlag`)
  + the Android `config/` package (`FeatureFlags.kt`, `FeatureGate.kt`): add
  a `driveExperience` key, default `false`, gating the celebration/points
  and share surfaces (risky-feature flag rule in `docs/product-decisions.md`).
- **Notifications** — the existing inbox (`notifications/` on Android,
  `functions/src/notifications/`) can carry milestone messages later, but v1
  celebrations are purely in-session UI — no push needed.
- **Access & abuse posture** — `functions/src/shared/memberActor.ts`
  (member gating; saving drives is already member-only) and App Check on all
  callables.

## 3. Competitive & safety/legal framing

### 3.1 The competitive case (why now, why this shape)

Competitor X turned the recorded, shareable drive into 187K+ drivers in
under 18 months, largely via TikTok ads of its share cards and replays
leaning on racing-game comparisons. Three facts from the teardown
(`docs/competitor-analysis/competitor-x.md`) shape this proposal:

1. **The loop is the product.** Users do not rave about GPS accuracy in the
   abstract — they rave about the *post-drive moment*: the animated route
   drawing itself across a stylized map, the stat card, the streak ticking
   up. That moment is what our recording flow lacks: today a saved drive
   terminates in `savedDrives_saveSuccess` body text and a list row.
2. **Android is their weak flank.** Their Android reviews complain that
   speed detection is wildly wrong, friends can't be added, the theme
   switcher and Android Auto search are broken, and sharing is buggy. We are
   Android-first by product decision. Shipping this loop polished on Android
   contests the exact segment they serve worst — and Sweden is effectively
   unpenetrated for them (9 App Store ratings, no Nordic marketing).
3. **We should match the loop, not the app.** Their moat-less areas (garage
   is a stub, no events, no clubs, no chat, no partners) are our core. The
   right response is: close the experience gap (this proposal), match
   Convoys (`docs/proposals/convoy-mode.md`), answer their roads content
   with community road ratings (`docs/proposals/road-ratings-map.md`) — and
   keep the community stack they cannot cheaply replicate. Those three
   proposals together match or exceed their feature set.

### 3.2 No speed gamification — a deliberate, permanent divergence

Competitor X's cards carry top speed and g-force; its share routes are
speed-colored; it has "speed traps" (save your fastest moment at a point).
This proposal copies **none** of it, and not merely because of MVP scoping:

- **Legal exposure (Sweden).** A feature that celebrates top speed on public
  roads invites *vårdslöshet i trafik* framing (Trafikbrottslagen 1951:649)
  and hands prosecutors, insurers, and journalists a self-incriminating
  record with the club's branding on it. A speed-colored route shared to
  Instagram is evidence with a logo. Competitor X carries this risk with a
  16+ rating and a US legal posture; a Swedish 18+ community app with
  municipal and partner relationships cannot.
- **Insurance & reputation.** Members' insurers and the club's partners both
  read public share cards. "12,3 km · 24 min · Kustvägen" is charming;
  "topp 187 km/h" is a cancelled policy and a partner meeting.
- **It is already locked.** `docs/product-decisions.md`: "Ingen
  toppfarts-/speedranking i MVP" ("No top-speed / speed-ranking in the MVP");
  badges must not reward risky driving;
  Kronjakt may not encourage speeding. The backend enforces the stance
  structurally: `drive-calculations.ts` computes no top speed and
  `saveDrive.ts` stores none, and `road-ratings-map.md` §3.1 already declared
  segment times permanently out of scope. This proposal adopts the same
  register: **no top speed, no g-force, no speed-colored routes, no
  segments/timed stretches — permanently out of scope, not deferred.**
- **Average speed is the one nuance.** The backend already stores
  `averageSpeedMetersPerSecond` and the drive detail already displays it
  (harmless — an average over a drive with stops indicts nobody). This
  proposal keeps it on the *private* stat card by default but leaves it **off
  the exported share card** pending the human's call (§8 Q1).

Streaks get the same scrutiny: a *daily* driving streak manufactures pressure
to drive every day — an incentive that is unnecessary (we are not a commuting
app), faintly unsafe (drive to keep a number alive), and tone-deaf in a
Swedish winter. §4.3 therefore recommends **weekly** streaks ("körvecka") as
the default mechanic, with daily streaks an explicit product question.

## 4. Proposed design

### 4.1 Lifecycle

```
record drive (existing, unchanged)
      │ stop → explicit save (existing product rule)
      ▼
drives.save (existing callable, extended §4.3)
      │ response: stats + streak/milestone payload
      ├── client uploads route.bin + preview.png  ← NEW (contract already exists)
      ▼
POST-DRIVE STAT CARD (new UI, replaces the "saved" text)
      │  distance · duration · route thumbnail · drive count
      │  + celebration overlay when a streak/milestone fired
      ├──► "Visa reprisen" → animated route replay on the drive detail map
      ├──► "Dela" → on-device share card export (trimmed route outline)
      └──► done → saved-drives list (existing)

Mina vägar: map layer toggle → all my saved-drive polylines, private
```

Nothing about *recording* changes: still foreground-only, still explicit
save-or-discard, still member-gated.

### 4.2 Data model (Firestore)

- **`rides/{rideId}` (existing — unchanged).** Geometry stays in Storage;
  no polylines ever enter Firestore (cost + document-size discipline).
- **`driveStats/{uid}` (new, backend-only writes; owner-readable via rules):**
  ```
  driveCount: number
  totalDistanceMeters: number
  currentWeekStreak: number      // consecutive ISO weeks with ≥1 qualifying drive
  longestWeekStreak: number
  lastQualifyingDriveDate: string  // local date, Europe/Stockholm
  lastQualifyingWeek: string       // ISO week key, e.g. "2026-W28"
  milestonesAwarded: string[]      // e.g. ["dist_100km", "dist_1000km", "drives_10"]
  updatedAt
  ```
  One small aggregate doc per member — the client renders card counters and
  the "Milstolpar" list from a single read, and the backend has an
  authoritative place to decide "did this save cross a threshold?".
- **Milestone catalog (code constant, not Firestore):** cumulative distance
  (100 km, 500 km, 1 000 km "100 mil körda", 5 000 km) and drive counts
  (10, 50, 100). Positive, non-competitive Swedish wording; values in a pure
  `drive-experience-core.ts` for JVM-style unit testing parity with the rest
  of `functions/src`.

### 4.3 Backend changes (sketch)

Deliberately small — one extended callable, no new export group:

- **Extend `drives.save`** (same transaction pattern it already uses): after
  creating `rides/{rideId}`, update `driveStats/{uid}` transactionally
  (counter increments, streak advance, milestone diff) and include a
  celebration payload in the response:
  `{ driveCount, totalDistanceMeters, currentWeekStreak, newMilestones[] }`.
  Idempotent replays (`alreadySaved: true`) return the stored aggregates
  without re-incrementing — the same guard that already prevents duplicate
  rides prevents duplicate stats.
- **Qualifying-drive rule (anti-farm, §4.4):** a drive advances streaks /
  counts toward milestones only if server-computed `distanceMeters ≥ 2 000`
  and `durationSeconds ≥ 300` (constants, tunable), and at most **one**
  qualifying drive per local day advances the streak. Non-qualifying drives
  still save normally — they just don't gamify.
- **Kronpoäng on milestones:** for each newly crossed milestone, one
  `creditPoints()` call — `source: 'drive'` (new enum value),
  `idempotencyKey: 'milestone_{uid}_{milestoneKey}'`, small fixed amounts
  (suggest 25–100 KP; §8 Q5). Awards ride the existing fire-and-log posture
  (`tryAutomaticAward` pattern): a points failure never fails the save.
- **Streak semantics:** ISO-week based, Europe/Stockholm. A qualifying drive
  in week *W* when `lastQualifyingWeek` is *W−1* increments the streak; same
  week is a no-op; a gap resets to 1. No points for streaks in v1 — streaks
  are display + celebration only (farming a streak then earns nothing).
- **Rules:** `driveStats/{uid}` owner-read, backend-only write (mirrors
  `pointsLedger` posture). No new indexes.

### 4.4 Anti-abuse: why fake GPS doesn't pay

Threat: feed mock locations to farm Kronpoäng. Layered answer, mostly
already built:

1. **Existing server validation** (§2.1): implausible segments (>200 km/h)
   contribute zero distance; unordered timestamps are rejected; points are
   capped at 20 000. A teleporting track earns ~0 m.
2. **Qualifying thresholds + one-per-day streak advance + one-time
   milestones** (§4.3): the maximum farmable value is bounded and small —
   milestones pay once ever per key (idempotent ledger entries), and the
   only repeatable surface (streaks) pays nothing.
3. **Client mock-location signal:** Android's
   `Location.isFromMockProvider`/`isMock` flag is sent as a boolean hint on
   the save payload; the server stores it on the ride for audit and excludes
   flagged drives from gamification (not from saving — false positives on
   legit setups shouldn't eat a member's drive). A client hint is spoofable,
   which is why layers 1–2 are the real defense; this is cheap signal, not
   a gate.
4. **Small-community reality + admin tools:** at 20–30 known members,
   anomalies are visible, and the existing admin points tooling
   (`functions/src/points/adminPoints.ts` reversals) can claw back anything
   weird, audited.

### 4.5 Client UX (Android)

- **Post-drive stat card** (`RecordingState.Saved` branch of
  `RecordDriveScreen.kt` → a proper composable): route thumbnail (the
  freshly generated preview bitmap — no round-trip), distance / duration via
  `DriveFormatters`, drive title, "körning nr {driveCount}", date. Average
  speed shown per §3.2. Celebration overlay (confetti-light, haptic — the
  save button already uses haptics) when the response carries
  `newMilestones` or a streak advance. **No top speed anywhere, ever.**
- **Route persistence (the enabling chore):** after a successful save,
  serialize the recorded points to `route.bin` (simple length-prefixed
  lat/lng/timestamp binary, documented in `contracts/`), render the preview
  bitmap, upload both via `MediaUploader` to the paths the callable returned.
  Upload failure degrades gracefully (retry from detail screen); the ride
  doc exists regardless.
- **Animated route replay** (drive detail, replacing the placeholder card in
  `DrivesScreen.kt`): download + cache `route.bin`, downsample to ≤500
  points, then animate the polyline drawing itself over ~4–6 s with a slow
  camera follow — a `ValueAnimator` feeding incremental point lists to the
  existing `PolylineAnnotationManager` path (§2.2). This is the racing-game-style
  perceived-value feature at genuinely low cost, because the polyline
  and camera-fit machinery shipped with the map-first shell.
- **"Mina vägar" heatmap** (layer toggle on the map shell, next to the
  existing traffic toggle): render the member's own saved-drive polylines,
  low-alpha so overlaps read as intensity. **Private only; no sharing
  surface.** Phase-1-simple by design: raw polylines from cached route
  files (LRU disk cache; most recent ~100–200 drives), no road-graph
  matching, no server-side aggregation. If a member someday has 500 drives,
  cap and say so.
- **Share card export:** on-device Compose-render → bitmap (1080×1920 story
  + 1080×1080 square), out via the Android share sheet. Contents: **trimmed
  route outline** drawn as an abstract stroke on a branded background (no
  street-name basemap by default — see privacy §5.2), distance, duration,
  drive title/date, community wordmark + crown from design tokens
  (brand-ready: assets/tokens, no hardcoded "KCC"). Exporting is always a
  deliberate user action per card; nothing auto-posts.
- **All user-facing text Swedish**, i18n-structured (`savedDrives_*` string
  family grows; no new namespaces needed).

### 4.6 Explicit non-goals

- **Auto-start drive detection** — Competitor X's convenience feature, but it
  drags in background-location Play policy review, battery/thermal work
  (their #1 complaint area), and activity-recognition tuning. Phase-later
  spike at most (§8 Q6); this proposal assumes manual start forever.
- **Built-in navigation, CarPlay/Android Auto** — locked out of MVP in
  `docs/product-decisions.md`; unchanged.
- **Segments / timed stretches / any leaderboard of drives** — permanently
  out (§3.2).
- **GPX import/export** — genuinely cheap against `route.bin` and a nice
  enthusiast nod, but it is scope, so it is an open question (§8 Q4), not a
  commitment.
- **In-app drive feed / social layer** — sharing is outbound (share sheet)
  only; an in-app feed is a different proposal if ever.

## 5. Privacy, cost & performance

### 5.1 Privacy posture: nothing new is collected, nothing new is public

- Recording is unchanged: foreground, explicit save, member-gated. This
  proposal adds **zero** new location collection and **zero** new in-app
  visibility — `rides`, route files, `driveStats`, and the heatmap are all
  strictly owner-scoped. The only data leaving the device is what the
  backend contract already defines (route file + preview to owner-gated
  Storage paths).
- `driveStats/{uid}` is derived, owner-readable aggregate data; it joins the
  account-deletion cascade (`functions/src/account/`) in the same change
  that creates it.
- No coordinates in logs (Kronjakt rule, adopted verbatim).

### 5.2 Share-card endpoint protection (the one real exposure)

An exported card is the single place recorded location leaves the member's
own account — voluntarily, but people under-estimate route inference.
`docs/product-decisions.md` locks "Delning av saved drives ska inte inkludera
exakt rutt som standard" ("Sharing of saved drives must not include the exact
route by default"), so:

- The card's route outline applies **the endpoint-trim + fuzz design already
  specified in `docs/proposals/road-ratings-map.md` §5.1** — ≥500 m trimmed
  from each end, trimmed endpoints snapped to a coarse grid — with the same
  constants, rather than inventing a second scheme. Drives too short to trim
  export a stats-only card (no outline).
- Difference in enforcement point, stated honestly: road-ratings publishes
  server-derived artifacts, so it enforces server-side; a share card is a
  user-initiated local export of the member's own data, so client-side
  trimming is acceptable — but it is **default-on**, and v1 ships **no
  untrimmed option** (revisit only as a product question later).
- Default background is an abstract branded canvas, not map tiles: no street
  names, no neighborhood identifiable beyond the shape itself. A
  basemap-backed card (Mapbox `Snapshotter`) is a later option and would
  need Mapbox attribution on the exported image per their ToS — noted so it
  isn't discovered in review.
- No timestamps, no average/top speed on the exported card by default
  (§3.2, §8 Q1) — geometry + distance + duration + branding only.

### 5.3 Cost & performance

- **Storage:** `route.bin` at the 20 000-point cap ≈ 480 KB; a typical
  30–60 min drive is 40–120 KB; `preview.png` ≈ 50–150 KB. At 20–30 members
  recording a few drives a week this is a few hundred MB/year — trivially
  inside the SEK 500/month budget (ADR-001), especially with the client
  downsampling long drives before upload.
- **Firestore:** one aggregate doc read per stat render, one extra
  transactional update inside the existing save call, a handful of ledger
  entries per member per *year* (milestones are one-time). No scheduled
  jobs, no fan-out.
- **Client:** replay animates ≤500 points (downsampled) — comfortable for
  Mapbox annotations; heatmap renders capped, cached polylines off the main
  thread; share-card rendering is a one-shot offscreen compose. No new
  battery surface (nothing runs in background).
- **Backend CPU:** the `drives.save` extension adds one transaction on a
  tiny doc — stays well inside the existing 256 MiB / 30 s budget.

## 6. Effort estimate & phasing

Order-of-magnitude for one engineer familiar with the codebase; each phase
independently shippable behind the `driveExperience` flag (default off).
Phase 1 is deliberately the fat slice — it is the competitive response.

- **Phase 1 — Route persistence + stat card + replay (~1.5–2 weeks):**
  `route.bin`/`preview.png` upload (client serializer + `MediaUploader`
  calls + retry-from-detail), post-drive stat card composable replacing the
  saved-text state, drive-detail route map replacing the shipped
  placeholder, animated draw-on replay. Backend: none (contract exists).
  Also pays down an acknowledged product gap independent of any competitor.
- **Phase 2 — Streaks & milestones (~1 week):** `drive-experience-core.ts`
  (pure: qualifying rule, week math, milestone diff — heavily unit-tested),
  `drives.save` extension + `driveStats` doc + rules, `'drive'` points
  source + `creditPoints` awards, celebration overlay + "Milstolpar" section
  on the drives screen, mock-location hint.
- **Phase 3 — Share cards (~4–5 days):** card renderer (story + square),
  trim/fuzz reuse (shared constants with the road-ratings spec), share-sheet
  flow, brand-token styling. Unit-test the trim math hard — it is the
  security-sensitive code of this proposal.
- **Phase 4 — Mina vägar heatmap (~3–4 days):** route-file disk cache,
  layer toggle on the map shell, capped low-alpha polyline rendering.

**Total: roughly 3.5–4.5 weeks.** Kill-checkpoint after Phase 1: if the stat
card + replay don't visibly change how often members record drives, stop
before building the gamification.

## 7. Risks

| # | Risk | Severity | Notes |
|---|------|----------|-------|
| R1 | **Share card leaks home area** — outline inference despite trimming | **High** | Default-on ≥500 m trim + grid snap (shared spec with road-ratings §5.1), no basemap by default, stats-only card for short drives, no untrimmed option in v1; unit-test the trim math. |
| R2 | **Gamified-driving drift** — streaks/milestones read as "drive more, drive faster" | **Medium-High** | Weekly (not daily) streaks, no points for streaks, milestones cumulative + one-time, no leaderboards, no speed anywhere; copy in the "njut av vägen" register; §3.2 stance permanent. |
| R3 | **Points farming via fake GPS** | **Medium** | Existing plausibility validation + qualifying thresholds + one-time idempotent milestone awards + mock-provider flag + admin reversals (§4.4); bounded upside makes farming pointless. |
| R4 | **Badge-rule conflict** — distance milestones vs. badge-core's "no distance badges" | **Medium** | v1 keeps milestones out of the badge catalog (points + Milstolpar list only); explicit human decision required to touch `BADGE_KEYS` (§8 Q3). |
| R5 | **Route-upload debt** — Phase 1 ships uploads but old drives have no geometry | **Low-Medium** | Replay/heatmap/thumbnails apply to drives saved after Phase 1; detail screen states this honestly for legacy rows. No backfill possible — geometry was never persisted. |
| R6 | **Mapbox attribution/licensing on exported images** | **Low** | Avoided in v1 by abstract-canvas cards; if basemap cards come later, bake attribution into the bitmap. |
| R7 | **Scope creep toward auto-start / background tracking** | **Medium** | Explicit non-goal; would trigger Play background-location policy review + the battery/thermal problems that plague Competitor X. Separate spike only (§8 Q6). |
| R8 | **Perceived-value miss** — we build the loop and members shrug | **Low-Medium** | Phase 1 kill-checkpoint; Phase 1 is also intrinsically useful (route on detail screen) so the floor isn't zero. |

## 8. Open product questions (for the human)

1. **Average speed on the exported share card?** Stored and shown privately
   already; recommendation: keep it **off** the export (insurance/reputation
   optics, §3.2) unless there's a strong pull.
2. **Weekly vs. daily streaks:** proposal recommends weekly ("körvecka") as
   default. Accept, or offer daily as well behind the same flag?
3. **Distance milestones and the badge catalog:** `badge-core.ts` documents
   "no speed/distance/racing badges." Keep milestones badge-free (points +
   Milstolpar list, this proposal's v1), or consciously relax the rule for
   *cumulative non-competitive* distance and add badge keys?
4. **GPX export** (and import?) of own drives: cheap against `route.bin`,
   enthusiast-friendly, zero privacy delta for export (own data, explicit
   action). In or out?
5. **Kronpoäng amounts per milestone** (suggest 25–100 KP, one-time) — needs
   the usual backend-authoritative sign-off.
6. **Auto-start spike:** park a separate later spike on drive auto-detection
   (Activity Recognition + foreground-service-on-detect), or rule it out
   entirely? Recommendation: park it; do not attach it to this build.
7. **Weather on the stat card:** needs an external data source (locked
   decision: external sources only with explicit product-goal fit — e.g.
   SMHI open data). Recommend deferring; the card is strong without it.

## 9. Recommendation

**Park on the Future Ideas board; do not build without explicit go-ahead.**
When prioritized, build it Phase-1-first — and treat Phase 1 as the highest
competitive-leverage slice currently on the board:

- **It attacks the gap that actually matters.** Competitor X's growth runs on
  the post-drive moment; ours ends in a text string and a placeholder card.
  Phase 1 (route persistence + stat card + replay) closes that on their
  weakest platform, reuses the shipped Mapbox polyline stack, and requires
  zero backend work because the storage contract already exists.
- **The risky parts are fenced.** Speed mechanics are permanently out and
  structurally absent from the backend; streaks are weekly and points-free;
  milestone awards are idempotent, thresholded, and bounded; share cards
  inherit the road-ratings trim spec instead of a new privacy design. The
  genuinely sensitive code (trim math, qualifying rules) is small, pure, and
  unit-testable.
- **It compounds the siblings.** Route persistence and polyline replay are
  prerequisites road-ratings needs anyway; the share card is the outbound
  half of the viral loop convoy-mode's shared drives would feed. Approving
  this first makes both parked proposals cheaper.

**Concretely, if approved:** ship Phase 1 behind `driveExperience`, measure
whether recording frequency moves, then decide on Phases 2–4; resolve §8
Q1–Q3 before Phase 2 starts; require a focused review of the share-card trim
implementation before Phase 3 ships.

## Appendix A — files referenced

- `functions/src/drives/saveDrive.ts`, `functions/src/drives/drives-core.ts`,
  `functions/src/drives/drive-calculations.ts`,
  `functions/src/drives/deleteDrive.ts`
- `functions/src/points/ledger.ts`, `functions/src/points/points-core.ts`,
  `functions/src/points/adminPoints.ts`
- `functions/src/badges/badge-core.ts`, `functions/src/badges/awards.ts`
- `functions/src/shared/featureFlags-core.ts`,
  `functions/src/shared/featureFlags.ts`,
  `functions/src/shared/memberActor.ts`
- `functions/src/account/` (deletion cascade)
- `firebase/storage.rules` (`rideRoutes/{userId}/{rideId}/` rules)
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/drives/`
  (`DriveRecording.kt`, `DriveRecordingCoordinator.kt`,
  `RecordDriveScreen.kt`, `DrivesScreen.kt`, `SavedDrive.kt`,
  `FirebaseDrivesRepository.kt`)
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/shell/`
  (`MapboxMapSurface.kt`, `MapSurface.kt`, `MapHome.kt`)
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/map/`
  (`MapScreen.kt`, `MapMarkers.kt`)
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/media/`
  (`FirebaseMediaUploader.kt`, `ImageCompressor.kt`, `MediaUploader.kt`)
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/config/`
  (`FeatureFlags.kt`, `FeatureGate.kt`)
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/design/`
  (brand tokens)
- `docs/product-decisions.md`
- `docs/competitor-analysis/competitor-x.md` (sibling analysis, in
  parallel)
- `docs/proposals/road-ratings-map.md` (endpoint-trim/fuzz spec reused),
  `docs/proposals/convoy-mode.md` (sibling competitive-response proposal)
