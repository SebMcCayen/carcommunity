# Proposal: "Bilspaning" — car-spotting mini-game

> **Status:** Design proposal / feasibility study — **NOT approved for build.**
> Parked on the Future Ideas board pending explicit go-ahead. This document
> does not change any application code, Cloud Functions, security rules, or
> configuration. It exists to support a go / no-go decision.
>
> **Author:** Claude (delegated feasibility spike)
> **Date:** 2026-07-10
> **Recommendation (short version):** **Build post-MVP — this is the
> best-fitting gamification idea in the backlog.** Phase 1 needs no AI and no
> new external data source; the one hard gating requirement is GDPR-safe photo
> handling (mandatory license-plate blurring before upload). Fund a small
> plate-blur spike first. See [§9](#9-recommendation).

---

## 1. Summary

"Bilspaning" is *Pokémon Go for cars*: a member spots an interesting car in
the wild, photographs it, tags make/model, and posts the **spot**. The spot
gets a **rarity score** (a Fiat Multipla is worth more than a Volvo V70),
other members **verify or dispute** the identification (itself a fun mechanic
and a small points source), and confirmed spots award **Kronpoäng**, feed a
spotting leaderboard, and unlock a new badge family. Admins can run seasonal
**spotting challenges** ("spana en fransk klassiker i juli") using the same
curation patterns as Kronjakt rounds.

Why this idea, and why now-ish:

- **Proven niche.** Car-spotting apps (Spyde, CarSpotter, RIDESPOTR, Carva)
  are a fast-growing category, but there is **no Swedish or local-community
  player**. A community-scoped spotting game — where the person who confirms
  your rare-spot ID is someone you'll meet at Saturday's träff — is exactly
  the social loop a local club app can do better than a global app.
- **Strongest appeal to younger members**, complementing the event/meet
  features that skew toward established owners. You don't need to *own* an
  interesting car to play — you only need to *find* one. That widens the
  funnel below the garage-owner demographic (still 18+ per product decisions).
- **It composes almost entirely out of existing, battle-tested primitives**:
  the media-upload pipeline, the backend-authoritative Kronpoäng ledger, the
  badge catalog, the Mapbox map surface, the crown-hunt admin-curation
  patterns, the chat-report moderation queue, blocking, and feature flags.
  The genuinely new machinery is small: a spot document model, a community
  verification flow, a rarity computation, and — the critical piece — a
  **plate-blurring step in the photo pipeline** (GDPR, see [§3](#3-legal-etiquette--safety)).

Unlike Kronjakt and the parked Sieges idea, Bilspaning is **not
location-race-shaped**: nothing rewards being somewhere fast or first, so it
sits naturally inside the product's safety posture rather than in tension
with it. The safety work is inherited (the crown-hunt "must be safely
stopped" speed gate reused as *no spotting while driving*), and the new risk
surface is **photo privacy and moderation**, not driving behavior.

**Phase 1 deliberately uses no AI**: manual make/model tagging plus community
verification. Phase 2 can add an optional on-device or cloud model-ID assist
behind a feature flag once the loop is proven (cost analysis in
[§5](#5-privacy-cost--performance)).

---

## 2. Existing infrastructure we can reuse

### 2.1 Media upload pipeline (Android + Storage rules)

`apps/android/app/src/main/java/com/kungsbackacarcommunity/app/media/` is a
complete, shipped image pipeline (Phase 12 media-uploads slice):

- `FirebaseMediaUploader.kt` — Cloud Storage `putBytes` upload with
  content-type metadata so Security Rules' `request.resource.contentType`
  check passes; construction guarded so config-less CI builds degrade
  gracefully.
- `MediaUpload.kt` — pure, JVM-testable helpers that **mirror the Storage
  rules client-side** (allowed types `image/(jpeg|png|webp|gif)`, byte caps:
  5 MB profile images, 10 MB vehicle images) so bad picks fail fast with a
  clear Swedish message instead of a generic permission-denied.
- `ImagePicker.kt` / `ImageUploadCoordinator.kt` — the pick → validate →
  upload UX flow already used by profile and garage images.

Bilspaning adds one new rules-scoped path
(`spotPhotos/{userId}/{spotId}/{imageId}`, 10 MB cap, image types only) and
**one new pipeline stage: on-device plate blurring before upload** (§4.4).
Everything else is reuse.

### 2.2 Kronpoäng ledger — the payout backbone

`functions/src/points/ledger.ts` + `points-core.ts`. The backend is the sole
authority; every mutation is one Firestore transaction (read balance → append
immutable entry → update denormalized balance), idempotency key = entry doc
ID so replayed awards are transactional no-ops, and suspended/deleted users
transact nothing. `creditPoints` is an internal writer that domains call
directly, with an `AtomicExtraWrites` hook so a caller commits its own
records in the same transaction — exactly how a verified spot can flip to
`confirmed` and pay out atomically. Transaction `source` is an enum in
`points-core.ts` (`POINTS_TRANSACTION_SOURCES`); Bilspaning adds a
**`car_spotting` source**, mirroring how `crown_hunt` was added.

### 2.3 Kronjakt (crown hunt) — curation, safety, and anti-abuse patterns

`functions/src/crownHunt/`:

- `crown-hunt-geo.ts` — pure geo utilities with no DB deps:
  `isPositionFresh` (≤ 60 s), `isSpeedSafe` (≤ 1.4 m/s — *you must be safely
  stopped*; negative/non-finite speeds treated as unsafe), coordinate
  validation, Haversine. Bilspaning reuses `isSpeedSafe` verbatim as the
  **"no spotting while driving" gate** on spot submission.
- `submitClaim.ts` — the callable template: feature-flag check →
  account/entitlement (`canAccessMemberFeatures`, `functions/src/shared/access.ts`)
  → idempotency replay guard (doc ID = SHA-256 of `(userId, idempotencyKey)`)
  → server-side validation → Swedish result codes instead of thrown errors →
  atomic award. `submitSpot` follows this step for step.
- `managePoints.ts` — the admin-curation pattern: draft → explicit,
  audited activation (`requireAdminActor` from
  `functions/src/admin/actorContext.ts`, `adminAuditEvents` records). This is
  the template for **admin-run spotting challenges** (§4.6), including the
  activation-as-safety-gate discipline.
- Daily success caps (`MAX_DAILY_SUCCESSFUL_CLAIMS`) — the template for
  per-day spot and verification caps that blunt farming.

### 2.4 Badges

`functions/src/badges/badge-core.ts` — catalog as single source of truth,
awards at `users/{uid}/badges/{badgeKey}` with the definition denormalized
and doc ID = badge key (naturally idempotent), backend-only
`badgeProgress/{uid}` counters incremented by guarded transitions. The
design rule, quoted verbatim: *"Wording stays positive and non-competitive;
no speed/distance/racing badges; nothing may encourage unsafe driving."* A
Bilspaning badge family (§4.7) fits this comfortably — spotting rewards
*noticing*, not driving.

### 2.5 Map surface

`apps/android/app/src/main/java/com/kungsbackacarcommunity/app/map/` —
Mapbox `MapView` in Compose (`MapScreen.kt`) with `CircleAnnotationManager`
markers (`MapMarkers.kt`), already rendering live-location and event pins.
Spot pins (rarity-tinted, at **fuzzed** coordinates — §5.1) are one more
annotation source on the same screen or a filtered layer of it.

### 2.6 Moderation, reporting, and blocking

- `functions/src/events/reportChatMessage.ts` + `moderateReports.ts` +
  `removeChatMessage.ts` — a complete report → admin queue → resolve/remove
  loop with backend-only report storage (rules `read,write: false`),
  `requireAdminActor`, bounded collection-group scans, and audit records.
  Bilspaning clones this shape for **spot reports** (wrong ID is *not* a
  report — that's a dispute; reports are for privacy/inappropriate-content
  problems).
- `functions/src/blocking/` (`manageBlocks.ts`, `onBlockWrite.ts`) — blocked
  users' spots and verifications are filtered per the existing pattern.
- `functions/src/admin/suspendUser.ts` / `warnUser.ts` — escalation paths
  already exist; repeated plate/privacy violations plug into them.

### 2.7 Feature flags, admin web, garage precedent

- `functions/src/shared/featureFlags-core.ts` — one flat `config/featureFlags`
  document, contract-synced defaults, audited `admin.setFeatureFlag` writes.
  Bilspaning adds `carSpotting` (default **false**) and later
  `carSpottingAiAssist` (default **false**).
- `apps/admin/src/features/` — `crown-hunt/`, `points/`, `badges/`,
  `feature-flags/`, `audit-log/` are the module templates a `spotting/`
  admin vertical copies (challenge CRUD, moderation queue, rarity overrides).
- `functions/src/garage/garage-core.ts` sets the decisive privacy precedent,
  quoted from its header: the vehicles document is authenticated-readable,
  *"so NO registration numbers, VIN, insurance data, or vehicle location can
  ever be stored — the strict schemas make such fields unrepresentable."*
  Spot documents adopt the identical rule: **plates are unrepresentable in
  the schema and must not be legible in the photo** (§3).

### 2.8 Adjacent parked proposals (cross-references, not duplicates)

- `docs/proposals/vehicle-reg-lookup.md` — analyzes GDPR posture of
  plate-linked *data lookup*. Bilspaning deliberately avoids that entire
  problem: plates are never captured, stored, or looked up. If reg-lookup is
  ever built, its make/model normalization could improve spot tagging, but
  there is no dependency either way.
- `docs/proposals/sieges-map-minigame.md` — the *competitive location* game;
  its safety analysis explains why Bilspaning is intentionally
  **not** race-to-a-place shaped.
- `docs/proposals/nearby-notifications.md` — a future "rare spot near you"
  notification would ride on that design; explicitly out of scope here.

---

## 3. Legal, etiquette & safety

This section is the gating content. Engineering-wise Bilspaning is mostly
recombination; **the reason to say no would live here**, so the mitigations
below are requirements, not options.

### 3.1 Photography law in Sweden — what is allowed

Photographing cars in public places is legal in Sweden; there is no general
prohibition on photographing other people's property from publicly
accessible locations. The relevant limits are:

- **Kränkande fotografering** (BrB 4:6a) concerns covert photography of
  *people* in private spaces — not applicable to photographing a parked car
  in public, but it frames the etiquette rules below.
- **Private land / hemfridszon**: photographing *from* someone's driveway or
  garden is trespass territory and, community-wise, exactly the behavior
  that gets car spotters a bad name. **House rule: public places only — no
  photos on private driveways, through garage windows, or over fences.**
  Enforced by guidelines + reporting + moderation, not geometry (there is no
  reliable "is this a driveway" API).

### 3.2 License plates are personal data — the hard requirement

A Swedish registration number identifies the vehicle's owner (via
Transportstyrelsen's register), so under GDPR a legible plate in a photo
**published to other members is personal data** processed without the
owner's consent. IMY guidance treats registration numbers as personal data.
This is the single hard legal requirement of the feature:

- **Plates must not be legible in published spot photos.** The upload flow
  includes a mandatory **on-device plate-blur step** (§4.4): free ML Kit
  text/object detection finds plate-shaped regions, the client blurs them,
  and offers a manual blur brush for anything missed (foreign plates, angled
  shots, reflections). The member must confirm "inga läsbara
  registreringsskyltar" before upload — an attestation stored on the spot
  document.
- **The blurred image is the only image that ever leaves the device.** The
  original is never uploaded, so no plate ever reaches Storage — the same
  "make it unrepresentable" posture as `garage-core.ts` takes for reg
  numbers in `vehicles`.
- **Defense in depth**: reporting (§2.6) with a dedicated *"skylt syns"*
  reason auto-hides the photo pending review (privacy reports fail safe);
  repeat offenders escalate through the existing warn/suspend path.
- **No identifiable people** in spot photos — same mechanism (blur or
  reframe), same report reason. This also keeps the feature clear of
  kränkande-fotografering and image-publishing gray zones entirely.

### 3.3 Owner respect & community etiquette

Legal ≠ welcome. A community app lives on goodwill:

- Spots show the car, never the owner, never the home. **Location is fuzzed
  on display** (§5.1) so a spot can't be used to case where a valuable car
  is parked — an anti-theft consideration as much as a privacy one.
- An owner who finds their own car spotted and wants it removed gets it
  removed, no questions (report reason *"min bil — ta bort"*). Fighting an
  owner over a photo of their property is a community-killing hill to die on.
- Published guidelines (Swedish, in-app, shown before first spot): public
  places only, no plates, no people, no driveways, be the spotter you'd want
  to meet.

### 3.4 No spotting while driving

Reuse the crown-hunt safety stance wholesale: `submitSpot` runs
`isSpeedSafe` (≤ 1.4 m/s) against the submitted position — **you must be
safely stopped to post a spot**. Passengers photographing from a moving car
still fail the gate; that is accepted product cost (the driver's phone can't
tell driver from passenger, and the conservative rule is the only defensible
one — consistent with `docs/product-decisions.md` driving-mode rules).
Composing the post later from the photo roll is fine; the *submission*
position/speed is what's gated, and nothing in scoring rewards speed or
being first-to-a-place.

### 3.5 Market context

Spyde, CarSpotter, RIDESPOTR and Carva prove the loop (photograph → identify
→ rarity → collection → leaderboard) retains a young car-enthusiast audience.
None has Swedish localization, local-community scoping, or an existing
points/badges/events economy to plug into. The differentiator is not
features — it's that the verifier of your spot is someone from your club.

---

## 4. Proposed design

Feature flag `carSpotting` (default `false`). New sibling domain
`functions/src/spotting/` (the Sieges proposal's Option B reasoning applies
identically: reuse crown-hunt's pure libraries, don't overload its domain
model). All callables `onCall`, region `europe-west1`, App Check enforced,
Swedish result codes, mirroring the crown-hunt option block.

### 4.1 Spot lifecycle

```
draft (client-only) → submitted → community verification window
    → confirmed  (≥ N confirmations, payout via ledger)
    → disputed   (dispute majority → spotter can re-tag once → back to submitted)
    → hidden     (privacy report or admin action; photo access revoked)
    → removed    (admin/owner-request takedown; tombstone kept for audit)
```

KP is awarded **only at `confirmed`**, never at submission — the same
"score at the gate, not per action" logic that keeps crown-hunt spam-safe.

### 4.2 Data model (Firestore + Storage)

```
spots/{spotId}                              (Firestore)
  spotterUserId
  makeModelKey        e.g. "fiat|multipla"   (normalized lowercase key)
  makeDisplay, modelDisplay                  (member-entered, length-capped
                                              like VEHICLE_MAKE_MODEL_MAX_LENGTH)
  modelYearApprox?    int (bounded like garage-core year rules)
  photoPath           spotPhotos/{uid}/{spotId}/{imageId}
  plateBlurConfirmed  true                   (required attestation)
  displayLat, displayLng                     (FUZZED — the only coords members read)
  rarityTierAtConfirm 1..5                   (denormalized at payout for history)
  status              submitted | confirmed | disputed | hidden | removed
  confirmCount, disputeCount                 (denormalized by backend)
  challengeId?                               (if submitted into a challenge)
  createdAt, confirmedAt?

spots/{spotId}/verifications/{uid}          (doc ID = uid → one vote per member)
  verdict: confirm | dispute
  suggestedMakeModelKey?                     (on dispute: "it's actually a …")
  createdAt

spotLocationsPrivate/{spotId}               (BACKEND-ONLY — exact coords)
  lat, lng, accuracy, recordedAt             (rules read,write: false;
                                              admin/moderation + fuzzing source)

modelSpotCounts/{makeModelKey}              (backend-maintained aggregate)
  totalConfirmed, last90dConfirmed           (rarity input, §4.5)
  adminTierOverride?  1..5

spotChallenges/{challengeId}                (admin-managed, crown-hunt round pattern)
  title, description, criteria (makeModelKeys | freetext theme)
  status: draft | active | ended             (draft → audited activation)
  windowStart, windowEnd, bonusKp
  createdByUserId, approvedByUserId

spotReports/{reportId}                      (backend-only, moderateReports clone)
  spotId, reporterUserId, reason:
    plate_visible | person_visible | private_property | wrong_content
    | owner_removal | other
  status: open | under_review | resolved | dismissed
```

Storage: `spotPhotos/{userId}/{spotId}/{imageId}` — image types only, 10 MB
cap, owner-write, member-read (mirrored client-side in `MediaUpload.kt` like
the existing paths). `hidden`/`removed` spots get photo read access revoked.

Exact coordinates follow the crown-hunt risk-collection precedent
(`crownHuntClaimRisk`): Firestore rules can't redact per-field, so anything
members must never read lives in a **separate backend-only collection**.

### 4.3 Backend callables (sketch)

**Member** (all: flag → `canAccessMemberFeatures` → idempotency → validate):

- `spotting.submitSpot` — input: photoPath, make/model, position (freshness
  ≤ 60 s + `isSpeedSafe` gate), `plateBlurConfirmed` must be true. Writes
  `spots` + `spotLocationsPrivate`, computes fuzzed display coords, applies a
  **daily submission cap** (e.g. 5). Result codes, not errors.
- `spotting.verifySpot` — one verdict per member per spot (doc ID = uid);
  spotter can't verify own spot; **daily verification cap** (e.g. 20). At
  ≥ N confirms (N = 2 proposed) with confirms > disputes, the same
  transaction flips status and pays out via `creditPoints({ source:
  'car_spotting', idempotencyKey: 'spot-confirm_<spotId>' , ...})` plus a
  small verifier reward (`'spot-verify_<spotId>_<uid>'`) — atomic via the
  ledger's `AtomicExtraWrites` hook, replay-safe by construction.
- `spotting.reportSpot` — clone of `reportChatMessage`; `plate_visible` /
  `person_visible` / `owner_removal` immediately set `hidden` (fail-safe).
- `spotting.listSpots` / direct rules-read of non-hidden spots (fuzzed
  fields only), like members read active crown-hunt points today.

**Admin** (all `requireAdminActor`, all audited):

- `spotting.createChallenge` / `updateChallenge` / `activateChallenge`
  (explicit audited activation, crown-hunt `managePoints` pattern) /
  `endChallenge`.
- `spotting.moderateSpots` — list/resolve report queue (moderateReports
  clone), remove spot, restore hidden spot.
- `spotting.setRarityOverride` — pin a tier on `modelSpotCounts/{key}`.

### 4.4 Client UX (Android)

1. **Spana**-button (camera or photo roll) → pick photo.
2. **Plate-blur step** (the new pipeline stage): on-device ML Kit detects
   plate-shaped text regions → auto-blur → member reviews, manual blur brush
   for misses → confirms the attestation checkbox. Only then does the
   existing `ImageUploadCoordinator`/`FirebaseMediaUploader` flow upload the
   **blurred** bytes.
3. Tag make/model (free text with local suggestions from previously used
   keys; Phase 2 may add AI suggestions), optional year.
4. Submit → Swedish result feedback (reused pattern) → spot appears on the
   map (fuzzed pin) and in a **Bilspaning feed**.
5. Verification: members browsing the feed see *"Stämmer det? Bekräfta /
   Ifrågasätt"* on unconfirmed spots; disputing prompts a suggested correct
   model. Confirmations pay both parties on flip-to-confirmed.
6. Collection view ("Min spaningsbok"): grid of your confirmed spots by
   rarity tier — the Pokémon-dex moment that drives retention.

### 4.5 Rarity scoring — hybrid, algorithmic-first

- **Algorithmic base**: tier from community-wide confirmed counts in
  `modelSpotCounts` (e.g. tier 5 ≤ 1 confirmed spot ever, tier 4 ≤ 5, tier 3
  ≤ 20, tier 2 ≤ 100, tier 1 common). Self-calibrating to the local area —
  what's rare *in Kungsbacka* is what matters — and requires zero admin
  effort or curated car database.
- **Admin override**: `adminTierOverride` pins obvious cases the cold-start
  algorithm gets wrong (everything is "rare" when counts are all zero) and
  handles gaming (mis-tagging toward rare keys — though the verification
  flow already fights that).
- KP by tier at confirm time, e.g. 5/10/20/40/75 KP (tuning is a product
  knob), denormalized as `rarityTierAtConfirm` so later rarity drift never
  retro-changes history. Verifier reward small and flat (e.g. 2 KP) and
  capped daily so verification can't be farmed.

### 4.6 Seasonal spotting challenges

Direct reuse of the crown-hunt admin round shape: draft → audited activation
→ bounded window → ended. A challenge defines a theme (specific
`makeModelKeys` or a described theme admins judge), a window, and a KP bonus
applied on top of the rarity payout for qualifying confirmed spots
(idempotency key `'spot-challenge_<challengeId>_<spotId>'`). Admin web gets
CRUD + a results view in the new `apps/admin/src/features/spotting/` module.

### 4.7 Badges (new family — all wording positive, non-competitive)

`first_spot` (Första spaningen), `ten_spots` (Tio spaningar),
`rare_find` (Sällsynt fynd — first tier-4+ confirm), `sharp_eye`
(Skarp blick — 25 accurate verifications), `challenge_finisher`. All
automatic, awarded through the existing idempotent `users/{uid}/badges/`
mechanism; leaderboard is a bounded query over confirmed-spot KP (or a
backend-maintained aggregate if query cost demands it).

### 4.8 Phase 2 (optional, flag `carSpottingAiAssist`, default off)

AI model-ID assist at tag time: photo → suggested make/model, member
confirms. Two implementation options, cost-compared in §5.2. Explicitly
**not** Phase 1 — community verification *is* the identification mechanic
first, and it's more social.

---

## 5. Privacy, cost & performance

### 5.1 Privacy design

- **Location fuzzing on display**: members only ever read `displayLat/Lng` —
  exact coords snapped to a ~250 m grid plus deterministic per-spot jitter
  (deterministic so repeated reads don't let anyone average out the noise).
  Exact position lives in backend-only `spotLocationsPrivate`, used for
  moderation and (optionally) coarse duplicate detection. Protects the *car
  owner* (anti-theft/casing) more than the spotter.
- **Plates & people**: unrepresentable/illegible by construction (§3.2);
  report-driven fail-safe hiding; attestation recorded.
- **Spotter privacy**: spots show display name like other community content;
  posting a spot reveals only the fuzzed location and that the member was
  there at some point — no live track, consistent with the live-location
  opt-in posture. A member's spots are removed on account deletion via the
  existing deletion flow (photo objects included).
- **No new external data flows** in Phase 1: no plate lookups, no cloud AI,
  nothing leaves Firebase. (Contrast with `vehicle-reg-lookup.md`, where the
  external source *is* the problem.)
- **GDPR basis**: with plates and people made illegible, published photos of
  cars in public places don't process third-party personal data; the
  member's own data (uid, photos, coarse location) is covered by the
  existing consent/privacy framework (`apps/android/.../privacy/`,
  public-web policy pages get a Bilspaning section).

### 5.2 Cost & performance

- **Phase 1 marginal cost ≈ storage + reads.** One blurred JPEG (~1–3 MB)
  per spot; Firestore writes per spot ≈ 3–5 docs; verification is 1 write +
  1 transactional aggregate update. At an optimistic 500 spots/month this is
  single-digit SEK against the ADR-001 SEK 500/month envelope. No scheduled
  jobs required in Phase 1 (rarity aggregates update transactionally on
  confirm; a nightly recount job is an optional integrity sweep later).
- **Plate blur (Phase 1) is free**: ML Kit on-device text recognition /
  object detection runs locally, no per-call cost, works offline, and the
  unblurred image never leaves the device — the cheapest option is also the
  most private one.
- **Phase 2 AI model-ID options**:
  - *Cloud Vision API*: ~USD 1.50 per 1 000 images after the free 1 000/month
    tier — trivial at community scale (500 spots/month ≈ free tier), but
    generic labels ("car", "sports car") are **not** make/model
    identification; a specialized model or a hosted classifier would be
    needed, which is where real cost/effort lives.
  - *On-device (ML Kit custom model / TFLite)*: zero marginal cost and no
    image egress, but requires sourcing/training a make-model classifier —
    a meaningful ML effort that is exactly why Phase 1 ships without it.
  - Decision deferred until Phase 1 data shows whether mis-tagging is
    actually a problem the community verification loop doesn't solve.
- **Performance**: feed/map reads are bounded queries over `spots` with the
  same pagination discipline as the moderation queue's bounded scans;
  photos load through the existing `StorageImageUrl.kt` path.

---

## 6. Effort estimate & phasing

Order-of-magnitude, one experienced developer, matching sibling proposals'
calibration. Every phase lands behind `carSpotting = false`.

| Phase | Scope | Effort |
| --- | --- | --- |
| **0. Spike** | On-device plate detection + blur on real Swedish plates (angles, dirt, dusk, EU band); manual-brush fallback UX; go/no-go on detection quality. **This is the gating spike.** | ~3–5 days |
| **1. Backend MVP** | `spotting/` domain: spots + verifications + private locations model, `submitSpot`/`verifySpot`/`reportSpot`, rarity aggregate, `car_spotting` ledger source, moderation callables, security + Storage rules, contracts, emulator tests. | ~2 weeks |
| **2. Android** | Blur pipeline stage, submit flow, feed + verification UX, map pins (fuzzed), collection view, Swedish strings, guidelines interstitial. | ~2–3 weeks |
| **3. Admin web** | `features/spotting/`: moderation queue, challenge CRUD + audited activation, rarity overrides. | ~1–1.5 weeks |
| **4. Challenges + badges + leaderboard** | Challenge bonus payouts, badge family, leaderboard, polish, closed-group pilot. | ~1 week |

**Total: ~6–8 weeks** to a piloted v1. Phase 2 AI assist is a separate,
later effort (~1–2 weeks if Cloud-Vision-class; substantially more if a
custom classifier).

---

## 7. Risks

- **Plate-blur quality (highest, and it gates launch).** If on-device
  detection misses too many plates, the manual brush becomes the primary
  tool and the legal safety of the feature rests on member diligence +
  reporting. The Phase 0 spike exists to measure exactly this; a poor result
  means adding a server-side second-pass check (Vision API text detection on
  upload, ~free at this scale) before any spot goes live.
- **Moderation load.** This is the app's first member-published photo
  surface (chat is deliberately text-only in MVP) — a real, ongoing admin
  commitment. Mitigated by fail-safe auto-hide on privacy reports, hard
  daily caps, member-only (18+, subscribed) submission, and the existing
  warn/suspend escalation. If admins can't commit, the feature shouldn't
  ship.
- **Verification collusion / farming.** Two friends confirm each other's
  mis-tagged "rare" spots. Mitigations: verifier rewards small + capped,
  N ≥ 2 non-spotter confirms, dispute path, admin override on rarity,
  everything auditable. Accept residual risk at community scale.
- **Cold-start rarity distortion.** Early on everything is "never spotted"
  → tier 5. Mitigations: launch with admin tier overrides pre-seeded for
  common models, or cap tier-4/5 payouts during a calibration month.
- **Etiquette incidents** (driveway photos, owner complaints) damaging club
  reputation — the guidelines + instant owner-removal rule + moderation are
  the answer, but one bad viral incident is a real reputational tail risk.
- **Scope creep**: AI ID, spot trading, national leaderboards, rare-spot
  push alerts (see `nearby-notifications.md`) are all tempting; v1 must
  ship the thin loop: spot → blur → tag → verify → KP.

---

## 8. Open product questions (for the human)

1. **Is member-published photography in scope for the product at all?** MVP
   deliberately excludes images in chat; Bilspaning crosses that line
   deliberately, with moderation cost attached. Comfortable?
2. **Who moderates?** Reports need same-day-ish handling for privacy
   reasons. Is there admin capacity, or should launch wait for more admins?
3. **Free vs subscribed:** proposal assumes member-only submission *and*
   verification (consistent with `canAccessMemberFeatures` gating). Should
   free users see the feed (read-only teaser, like events)?
4. **Payout tuning:** are 5–75 KP per confirmed spot the right magnitudes
   relative to Kronjakt claims, so neither game devalues the other?
5. **Name check:** "Bilspaning" is a working title — brand-ready naming per
   product decisions (no hardcoded KCC), and shouldn't collide with future
   national branding.
6. **Should spotted-car owners get an opt-out registry** ("aldrig min
   bil"), or is report-driven removal enough for v1? (Registry requires
   identifying cars without plates — hard; recommend report-driven for v1.)

---

## 9. Recommendation

**Build — post-MVP, spike-first.** Of the parked gamification ideas this is
the strongest fit:

- **Engineering feasibility is high.** Phase 1 is a recombination of shipped
  primitives (media pipeline, ledger, badges, map, moderation, flags) plus
  one genuinely new component — on-device plate blurring — which the ~3–5
  day Phase 0 spike de-risks cheaply before any commitment.
- **Product fit is strong and safety-aligned.** Unlike competitive
  location-control ideas, nothing here rewards driving behavior; the
  crown-hunt speed gate is inherited, and the mechanic (noticing cool cars
  and talking about them) *is* the community's founding activity, packaged
  for younger members.
- **The honest cost is organizational, not technical**: a member-photo
  surface means a standing moderation commitment and a hard GDPR rule
  (no legible plates, ever) that must be enforced from day one.

Sequence: park on the Future Ideas board now → after MVP launch, run the
Phase 0 plate-blur spike → go/no-go with real detection-quality data and an
explicit admin-moderation commitment → build Phases 1–4 behind
`carSpotting = false` → pilot with a closed group before enabling the flag.

---

## Appendix A — files referenced

- `functions/src/points/ledger.ts`, `functions/src/points/points-core.ts`
- `functions/src/crownHunt/submitClaim.ts`, `crown-hunt-geo.ts`,
  `crown-hunt-risk.ts`, `managePoints.ts`
- `functions/src/badges/badge-core.ts`
- `functions/src/events/reportChatMessage.ts`, `moderateReports.ts`,
  `removeChatMessage.ts`
- `functions/src/blocking/manageBlocks.ts`, `onBlockWrite.ts`
- `functions/src/admin/actorContext.ts`, `suspendUser.ts`, `warnUser.ts`
- `functions/src/shared/featureFlags-core.ts`, `access.ts`
- `functions/src/garage/garage-core.ts`
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/media/`
  (`FirebaseMediaUploader.kt`, `MediaUpload.kt`, `ImagePicker.kt`,
  `ImageUploadCoordinator.kt`, `StorageImageUrl.kt`)
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/map/`
  (`MapScreen.kt`, `MapMarkers.kt`)
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/crownhunt/`
- `apps/admin/src/features/crown-hunt/`, `points/`, `badges/`,
  `feature-flags/`, `audit-log/`
- `docs/product-decisions.md`
- `docs/proposals/vehicle-reg-lookup.md`, `sieges-map-minigame.md`,
  `nearby-notifications.md`
