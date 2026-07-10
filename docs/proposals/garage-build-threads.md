# Proposal: "Byggloggar" — build threads & mod lists on the garage

> **Status:** Design proposal / feasibility study — **NOT approved for build.**
> Parked on the Future Ideas board pending explicit go-ahead. This document
> does not change any application code.
>
> **Author:** Claude (delegated feasibility spike)
> **Date:** 2026-07-10
> **Recommendation (short version):** **Build phase 1 (entries + photos +
> follow + feed), flag-gated, after MVP go-live.** This is the single
> cheapest way to give the app living member content between events, and
> ~90 % of the required infrastructure (garage, media upload, moderation
> pipeline, blocking, notifications, badges) already exists and is reused
> as-is. Defer reactions and structured mod lists to later phases; do
> **not** attach automatic Kronpoäng to posting.

## 1. Summary

Today the garage is a static profile artifact: a member adds up to five
vehicles (`MAX_VEHICLES_PER_USER = 5`, `functions/src/garage/garage-core.ts`)
with make/model/year/powertrain, one photo, and a 500-char description —
then nothing ever happens to it again. This proposal turns each vehicle into
**living content**:

1. **Bygglogg (build log):** an optional chronological thread of entries on
   a vehicle — photo(s) + text with a lightweight type tag
   (`mod | service | trip | milestone | other`): *"nya coilovers monterade"*,
   *"besiktning godkänd"*, *"vinterförvaring"*, *"OTA-uppdatering + ny
   laddbox"*. Works equally for a '72 Amazon and a Model 3 — EV owners log
   software and charging upgrades.
2. **Follow a car:** any member can follow a vehicle; new entries surface in
   a lightweight **"Verkstan"** feed and (opt-out) push notification.
3. **Mod list (later phase):** a structured list of installed parts and a
   wishlist (category + part name + note) attached to the vehicle.

Build threads are the beloved core of every surviving enthusiast platform —
Wheelwell, CarThrottle's Garage, Swedish garaget.org — and the one classic
community feature this app is missing. Crucially, it is **user-generated,
evergreen content tied to real cars owned by real local members**, which
makes it the lowest-risk content investment available (see
[§3](#3-market-context--the-content-flywheel-argument)).

Scope discipline: phase 1 is *entries + photos + follow + feed + the full
moderation pipeline*. Reactions are phase 2; mod lists phase 3. This matches
the locked "Funktionalitet hålls MVP-light" posture for Vehicles/Garage in
`docs/product-decisions.md`.

## 2. Existing infrastructure we can reuse

This feature is unusually reuse-heavy — no new external services, no new
GCP infrastructure, no new secrets. Everything below already ships.

### 2.1 Garage domain (the anchor)

- `functions/src/garage/garage-core.ts` — pure Zod validation + document
  builders; the strict-schema pattern (`.strict()`, bounded strings, safe
  Firestore IDs via the `vehicleIdSchema` regex) is copied directly for
  build-entry inputs. The hard invariant documented here — **`vehicles` is
  authenticated-readable, so registration numbers / VIN / location are
  unrepresentable** — extends to the new subcollections (see §5.1).
- `functions/src/garage/manageVehicle.ts` — the callable pattern to clone:
  `europe-west1`, `256MiB`, `enforceAppCheck` outside the emulator,
  `requireMemberActor`, transactional per-user caps, ownership failures as
  `not-found` (no existence probing), storage-prefix validation
  (`isValidVehicleImagePath`), storage-first deletion for clean retries.
- `firebase/firestore.rules` (~line 310) — `vehicles/{vehicleId}`: read for
  any authenticated user, **no client writes**. Build-entry subcollections
  adopt the same posture.
- `functions/src/index.ts` — the `garage` grouped export; new callables slot
  in as `garage-addBuildEntry` etc. with zero deploy-topology novelty.
- Android: `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/garage/`
  (`GarageRepository.kt`, `FirebaseGarageRepository.kt`, `GarageScreen.kt`,
  `VehicleFormScreen.kt`, `GarageCoordinator.kt`, `GarageStrings.kt`) — the
  Firebase-free repository interface + coordinator + Compose screen pattern
  every Phase 12 slice follows. The build-log detail screen is a new sibling
  route inside this package.

### 2.2 Media pipeline (photos come for free)

- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/media/` —
  `MediaUploader.kt` / `FirebaseMediaUploader.kt` (putBytes + content-type
  metadata so storage rules validate), `ImagePicker.kt`,
  `ImageUploadCoordinator.kt`. Already used for vehicle photos; entry photos
  are the same flow with a new storage prefix.
- `firebase/storage.rules` (~line 64) — the
  `vehicleImages/{userId}/{vehicleId}/{imageId}` rule (owner + active member
  writes, ≤ 10 MB, `isImage()`, authenticated read) is duplicated for a
  `buildEntryImages/{userId}/{vehicleId}/{entryId}/{imageId}` prefix, keeping
  the extra path segment so entry deletion can remove exactly one entry's
  photos (same rationale the vehicleId segment already documents).

### 2.3 Report → moderation pipeline (event chat, reused wholesale)

The entire UGC-safety loop exists for event chat and ports 1:1:

- `functions/src/events/reportChatMessage.ts` — member files a report;
  deterministic doc ID dedupes per (message, reporter, reason); reports are
  backend-only (never client-readable); repeat reports never reset review
  state. Clone as `garage.reportBuildEntry`.
- `functions/src/events/moderateReports.ts` — admin queue
  (`listChatReports` / `resolveChatReport`): bounded collection-group scan,
  status transitions stamped with `reviewedByUserId`/`reviewedAt`, every
  action written to `adminAuditEvents` via `buildAdminAuditEvent`
  (`functions/src/admin/claims-core.ts`). Clone for build-entry reports.
- `functions/src/events/removeChatMessage.ts` — admin soft-remove that
  auto-resolves open reports; same for `garage.removeBuildEntry`.
- `apps/admin/src/features/event-chat/index.ts` — the reports-driven admin
  moderation module (deliberately no "browse all content" surface; admins
  act on reports, which carry the IDs they need). A
  `apps/admin/src/features/build-logs/` module is a near-copy. This is the
  minimal admin surface and it is already the house pattern.

### 2.4 Blocking

- `functions/src/blocking/blocking-core.ts` + `manageBlocks.ts` —
  `userBlocks/{blockerUid}/blocked/{blockedUid}`, owner-readable,
  backend-written, directional. Event chat filters blocked authors **on
  read, not on post** (documented in
  `functions/src/events/postChatMessage.ts`); the feed and vehicle build
  logs apply the same read-time filtering in the Android client
  (`apps/android/.../blocking/BlockingRepository.kt` already exposes the
  list).

### 2.5 Notifications (in-app + push)

- `functions/src/notifications/deliver.ts` — `writeInAppNotification`, the
  single backend inbox writer: recipient eligibility (deleted/suspended),
  per-category opt-outs from `userPrivate/{uid}.notificationPreferences`,
  idempotent delivery via deterministic IDs. A new **non-essential**
  category (e.g. `followed_build_update`) is added to
  `NOTIFICATION_CATEGORIES` in `notifications-core.ts`. Note the code
  comment there: new categories **must not be activated without product and
  security review** — this proposal is that review's input.
- `functions/src/notifications/pushTokens.ts` + FCM push and the Android
  inbox/prefs UI (`apps/android/.../notifications/`,
  `NotificationSettingsScreen.kt`) — the prefs UI does **not** auto-surface a
  new category. `NotificationSettingsScreen` renders one toggle row per entry
  in the hardcoded `NotificationCategories.ACTIVE` list
  (`NotificationSettings.kt`) and labels each via the hardcoded
  `categoryLabelRes` `when`. Surfacing a `followed_build_update` toggle
  therefore needs three manual client edits: append the category to
  `NotificationCategories.ACTIVE`, add its `categoryLabelRes` case, and add a
  `notifications_category*` string resource — otherwise it either never
  renders (absent from `ACTIVE`) or falls back to the generic "System" label
  (the `else` branch).

### 2.6 Badges & Kronpoäng (for the *featured*, not the *automatic*, path)

- `functions/src/badges/badge-core.ts` — `BADGE_KEYS` (currently
  `first_event`, `five_events`, `helpful_member`, `early_member`,
  `garage_created`) + `BADGE_CATALOG`; `functions/src/badges/awards.ts` —
  idempotent `awardBadge`, admin-manual or automatic. A **"Månadens bygge"
  (Build of the Month)** admin-manual badge is one new key + catalog entry.
- `functions/src/points/ledger.ts` — transactional, idempotent
  `creditPoints` with `idempotencyKey`, already exposed to admins via
  `points-adminAdjust`. **Deliberately not wired to posting** (see §4.6).
- Announcements (`apps/admin/src/features/announcements/`) can broadcast the
  monthly winner.

### 2.7 Cross-cutting

- `functions/src/shared/memberActor.ts` — `requireMemberActor` /
  `requireActiveActor` for all new callables.
- `functions/src/shared/featureFlags-core.ts` + `featureFlags.ts` — one new
  flag key (`buildLogs`, default **false**) gates the whole feature, per the
  locked "feature flags måste finnas för riskfyllda features" decision.
- Rate limiting — `postChatMessage.ts`'s collection-group count pattern
  (~5 msgs / 30 s) is reused for entry creation (stricter: entries are
  rarer than chat messages).
- Contracts — `contracts/schemas/garage.schema.json` (or a new
  `build-log.schema.json`) + `contracts/functions/functions.json` entries,
  exactly as every domain does.

### 2.8 Adjacent parked proposals (cross-references, no overlap)

- `docs/proposals/vehicle-reg-lookup.md` — same garage anchor; its "never
  store plate/VIN on authenticated-readable vehicle data" analysis applies
  verbatim to build-entry text and photos (§5.1).
- `docs/proposals/nearby-notifications.md` — if that ships, its
  notification-category plumbing and this proposal's `followed_build_update`
  category follow the same review path.

## 3. Market context — the content-flywheel argument

Why this feature, before any other content feature:

- **It is table stakes in the genre.** Every long-lived enthusiast platform
  converges on the build thread: Wheelwell (mod lists + build logs as the
  core object), CarThrottle's Garage section, and — most relevant for this
  audience — **garaget.org**, the Swedish institution where a car's page
  *is* its build history. Swedish car people already know exactly what a
  "bygglogg" is; there is zero concept-education cost.
- **The DriveTribe lesson.** DriveTribe had enormous reach and shut down
  anyway (Jan 2022). What users mourned was not the celebrity content but
  the *tribes* — member-run sub-communities and owner-posted build/ownership
  content. The expensive editorial layer and ad-revenue model killed the
  economics; the user-generated layer was the part with durable value.
  KCC's model (member subscription, no ad dependency) keeps exactly the
  part that worked and skips the part that didn't.
- **Lowest-risk content investment available.** Compare the options for
  giving the app a pulse between events: editorial content (recurring cost,
  DriveTribe trap), general-purpose feeds/DMs (explicitly excluded from MVP:
  "Ingen privat DM, bild- eller videochat i MVP"), or third-party content
  (licensing). Build logs are **user-generated, evergreen, and anchored to
  real cars owned by verified 18+ local members** — the content moderates
  itself toward authenticity because every thread is attached to a vehicle
  the community can see at the next träff.
- **It serves every member segment.** Wrenchers log mods; daily-drivers log
  service and besiktning; EV owners log software versions, charging setups
  and efficiency experiments. Nothing about the design privileges combustion
  — important for an inclusive community and consistent with the
  `powertrain` enum already covering `electric`.
- **It compounds the existing loop.** Events bring people together; build
  logs give them something to talk about between events; "follow that
  Escort I saw at the träff" creates a reason to reopen the app that is not
  event-driven. Content → follows → notifications → return visits →
  event attendance.

The honest counterpoint: a small community can produce an **empty feed**
(cold-start risk, §7). Mitigations are cheap — seed with admin/founder
builds, feature one build per month via announcement + badge — but the risk
is real and argues for the flag-gated, phase-1-small approach.

## 4. Proposed design

### 4.1 Lifecycle

1. Owner opens their vehicle in the garage → "Bygglogg" section → adds an
   entry (text, optional type tag, 0–5 photos). Photos upload through the
   existing media pipeline to the new storage prefix; the entry is created
   via callable (never direct writes).
2. Any authenticated user viewing a vehicle sees its build log (same
   visibility as the vehicle document itself). Members can **follow** the
   vehicle.
3. New entry → Firestore trigger fans out in-app notifications (and push,
   per prefs) to followers → followers open the entry from the inbox or
   the "Verkstan" feed surface.
4. Anyone eligible can report an entry → backend-only report queue → admin
   resolves/dismisses or soft-removes the entry (audited). Blocked authors
   are filtered from the reader's feed/build logs at read time.
5. Owner can edit/delete own entries; vehicle deletion cascades (storage
   prefix first, then documents — the `deleteVehicle` pattern).

### 4.2 Firestore data model

All new collections: **backend-only writes** (callables), read rules shown.

```
vehicles/{vehicleId}/buildEntries/{entryId}     read: isAuthenticated()
  vehicleId        string   (denormalized — enables collection-group feed query)
  authorUserId     string   (== vehicle.userId at creation)
  authorDisplayName string  (denormalized, chat-message pattern)
  entryType        'mod' | 'service' | 'trip' | 'milestone' | 'other'
  title            string 1..80
  text             string 0..2000
  photoPaths       string[] 0..5   (each under buildEntryImages/{uid}/{vehicleId}/{entryId}/)
  removed          boolean         (admin soft-remove; client renders tombstone)
  createdAt / updatedAt   Timestamp (server)

vehicles/{vehicleId}/entryReports/{reportId}    read/write: false (backend-only)
  — chat-core report shape: reporterUserId, reason, details, status,
    reviewedByUserId, reviewedAt; deterministic ID = hash(entryId, reporter, reason)

vehicleFollows/{followId}                       (followId = `${uid}_${vehicleId}`)
  read: owner only (resource.data.followerUserId == request.auth.uid)
  followerUserId   string
  vehicleId        string
  vehicleOwnerUid  string   (denormalized for fan-out + "unfollow on block")
  createdAt        Timestamp

vehicles/{vehicleId}.buildMeta                  (fields on the existing doc)
  entryCount       int      (transactional counter, cheap list badges)
  followerCount    int      (transactional counter)
  lastEntryAt      Timestamp | null

# Phase 3
vehicles/{vehicleId}/mods/{modId}               read: isAuthenticated()
  category   'engine'|'drivetrain'|'suspension'|'brakes'|'wheels_tyres'|
             'exterior'|'interior'|'electronics'|'software'|'charging'|'other'
  name       string 1..80
  note       string 0..300
  status     'installed' | 'wishlist'
  sortOrder  int
```

Design notes:

- **Feed without fan-out-on-write.** A top-level `vehicleFollows` collection
  (not a subcollection) lets one indexed query answer both directions:
  "who follows vehicle X" (trigger fan-out) and "which vehicles does U
  follow" (feed). The feed itself is a collection-group query on
  `buildEntries` with `where('vehicleId', 'in', chunk)` ordered by
  `createdAt desc` — Firestore's `in` operator caps at 30 values, so cap
  follows per user at **30** in phase 1 (generous for a local community;
  revisit with fan-out-on-write to a `feedItems` inbox only if the cap ever
  binds). MVP-light and zero write amplification.
- **Follower cap** per vehicle is unnecessary at community scale, but the
  notification trigger processes followers in bounded batches.
- **Entry cap** per vehicle (e.g. 500) enforced transactionally like the
  vehicle cap, purely as an abuse bound.
- Counters update inside the same transaction as entry/follow writes —
  the `ledger.ts` transactional pattern.

### 4.3 Backend callables (sketch)

All: `europe-west1`, `256MiB`, `enforceAppCheck` outside emulator, pure
validation in a new `functions/src/garage/buildLog-core.ts` (strict Zod,
`garage-core` conventions), gated on `readFeatureFlag('buildLogs')`.

| Callable | Actor | Behavior |
| --- | --- | --- |
| `garage.addBuildEntry` | member (owner) | Validate; verify vehicle ownership (not-found on foreign); rate limit (collection-group count, e.g. ≤ 10 entries/hour); validate each photoPath under own `buildEntryImages/{uid}/{vehicleId}/{entryId}/` prefix; transactional create + entryCount++/lastEntryAt. |
| `garage.updateBuildEntry` | member (owner) | Partial update, `buildVehicleUpdate` pattern; photos re-validated. |
| `garage.deleteBuildEntry` | member (owner) | Storage prefix first, then doc (deleteVehicle pattern); entryCount--. |
| `garage.followVehicle` / `unfollowVehicle` | member | Idempotent (deterministic followId); reject own vehicle; reject when follower has blocked owner or vice versa; cap 30 follows; followerCount±1 transactionally. |
| `garage.reportBuildEntry` | member | Clone of `reportChatMessage`: dedupe, no self-report, backend-only queue, response never reveals prior reports. |
| `garage.listBuildReports` / `resolveBuildReport` | admin | Clone of `moderateReports.ts` incl. `adminAuditEvents`. |
| `garage.removeBuildEntry` | admin | Soft-remove (`removed: true`, text/photos withheld client-side), auto-resolves open reports, audited — `removeChatMessage` pattern. |
| *(Phase 2)* `garage.reactToBuildEntry` | member | Emoji from a fixed small set; `buildEntries/{id}/reactions/{uid}` one-doc-per-user (idempotent, switchable, removable); denormalized per-emoji counts updated in the same transaction — **backend-authoritative counts**, never client increments. |

Trigger: `onBuildEntryCreated` (Firestore `onDocumentCreated` on
`vehicles/{vehicleId}/buildEntries/{entryId}`) → query followers → for each,
`writeInAppNotification` (category `followed_build_update`, deterministic
notificationId = `${entryId}_${followerUid}` for idempotent retries) → push
via existing delivery. Suspended/deleted/opted-out recipients are already
handled inside `deliver.ts`.

### 4.4 Client UX (Android, Compose)

1. **Vehicle detail / garage screen:** "Bygglogg" section with entry list
   (photo thumbnails, type-tag chip, relative time), "Ny loggpost" FAB for
   the owner. Entry composer = text field + type selector + photo picker
   (existing `ImagePicker`/`ImageUploadCoordinator`).
2. **Other members' vehicles:** today the Android garage only lists *own*
   vehicles (`GarageRepository.observeGarage(uid)`). Phase 1 adds a
   read-only **vehicle card** reachable from feed/notification items
   (vehicle docs are already authenticated-readable, so this is a plain
   Firestore read + new route). Follow/unfollow button lives here.
3. **"Verkstan" feed:** simplest placement is a section on the existing
   home surface (`apps/android/.../home/HomeContent.kt` — currently
   placeholder cards) listing the newest entries from followed vehicles,
   with an empty-state that deep-links to… nothing to follow yet? Show
   recently active builds community-wide (still just the collection-group
   query, minus the `in` filter) — this doubles as discovery and kills the
   personal-feed cold start. A dedicated tab can come later if usage earns
   it.
4. **Report + block:** overflow menu on every entry (not own): "Rapportera"
   (reason picker, mirrors chat) and "Blockera medlem" (existing blocking
   flow). Blocked authors filtered from feed/build logs at read time.
5. All copy in Swedish via `GarageStrings.kt` conventions (i18n-ready keys).

### 4.5 Admin web

`apps/admin/src/features/build-logs/` mirroring `event-chat/`: reports
queue → resolve/dismiss → remove entry. No "browse all entries" surface
initially (the house privacy-preserving pattern) — though note build entries
are authenticated-readable anyway, so a later read-only browser is purely a
convenience, not a privacy question. Feature-flag toggle already exists in
`apps/admin/src/features/feature-flags/`.

### 4.6 Kronpoäng & badges — engagement without spam incentive

**Recommendation: no automatic points for posting.** A per-entry credit is
a direct spam pump (post junk → farm Kronpoäng) and would force us to build
anti-fraud for a feature whose whole value is authenticity. Backend is
source of truth for points (locked decision), which makes the *safe*
variants easy later, in this order of preference:

1. **Phase 1: nothing.** Social visibility is the reward.
2. **Phase 2+: "Månadens bygge"** — admin-manual badge (new `BADGE_KEYS`
   entry + catalog item, awarded via existing admin badge flow) + an
   announcement. Human-curated → ungameable, and it *creates* content
   (members post toward it).
3. **Only if ever needed:** reaction-threshold points (credit when an entry
   organically reaches N distinct reactors, idempotencyKey = entryId) —
   harder to game than per-post, but still gameable by friend rings; requires
   real review before enabling. Not in any planned phase.

## 5. Privacy, safety & cost

### 5.1 Privacy

- **Visibility inheritance:** entries are exactly as visible as the vehicle
  they hang off (any authenticated user). The garage's hard invariant —
  no registration numbers, VIN, insurance, or location in schema —
  **cannot be schema-enforced for free text and photos.** Mitigations:
  composer hint text ("skriv inte regnummer eller adress"), the report
  pipeline, and admin soft-remove. Same residual exposure as event chat and
  the existing 500-char vehicle description; not a new class of risk, but a
  larger surface.
- **Photos:** vehicle photos already carry this risk (plates visible);
  entry photos multiply the volume. Two concrete points for build time:
  (a) re-encode via the existing picker pipeline and confirm **EXIF/GPS
  metadata is stripped** before upload (verify what `ImagePicker` currently
  produces — flagged as an open engineering check, and worth fixing for
  existing vehicle photos too if it isn't); (b) privacy-policy copy already
  covering vehicle photos likely needs a sentence for build-log photos.
- **Follows are private:** `vehicleFollows` docs are readable only by the
  follower; owners see only the aggregate `followerCount`. No follower
  lists in phase 1 (stalking-adjacent surface, zero product need).
- **No location, no live data:** entries are static content; the `trip`
  type is a text/photo story, never a route. Saved-drive sharing rules
  (no exact route by default) are untouched.
- **Account deletion:** the existing deletion flow must cascade build
  entries + photos + follows (add to the account-deletion checklist;
  entries are keyed by authorUserId, follows by followerUserId — both
  indexable).
- **18+, UGC:** report + block + moderation are Google Play UGC-policy
  table stakes; all three are reused, which keeps Play compliance intact.

### 5.2 Cost & performance

Firestore-native, community-scale — negligible:

- Writes: one doc per entry (+ counter in-transaction), one per
  follow, N notification docs per entry (N = followers, tens not
  thousands). No fan-out-on-write feed.
- Reads: feed = ≤ 1 collection-group query per chunk of 30 followed
  vehicles per open; build log = one paginated subcollection read.
  Standard listener discipline in the repository (the Phase 12 pattern).
- Storage: ≤ 5 × 10 MB per entry in theory; in practice compressed
  uploads via the existing pipeline. One new composite index
  (`buildEntries`: `vehicleId` ASC/`createdAt` DESC collection-group) plus
  the rate-limit index (`authorUserId`/`createdAt`).
- Functions: one new trigger + ~8 callables in existing groups; no new
  regions, secrets, or egress. Consistent with `docs/firebase-cost-controls.md`
  posture (bounded scans, caps everywhere).

## 6. Effort estimate & phasing

| Phase | Scope | Rough effort |
| --- | --- | --- |
| **1a. Backend: entries + follow + reports** | `buildLog-core.ts` (validation/builders + tests), 7 callables (add/update/delete entry, follow/unfollow, report, admin list/resolve/remove), notification category + trigger, rules + storage rules + indexes, contracts, `buildLogs` flag | ~5–7 days |
| **1b. Android** | Build-log section on own vehicle, composer w/ photos, read-only vehicle card + follow button, Verkstan home section (community-recent + followed), report/block menu, i18n strings, repo fakes + tests | ~6–8 days |
| **1c. Admin web** | `build-logs` reports queue (clone event-chat module) | ~2–3 days |
| **1d. Hardening** | EXIF check, account-deletion cascade, emulator e2e, privacy copy | ~2 days |
| **Phase 1 total** | flag-gated, default off | **~3–4 engineer-weeks** |
| 2. Reactions + polish | `reactToBuildEntry` + reaction UI + per-emoji counts; "Månadens bygge" badge + announcement flow | ~1–1.5 weeks |
| 3. Mod lists | `mods` subcollection + CRUD callables + structured UI (installed/wishlist) | ~1–1.5 weeks |

Phases 2 and 3 are independently shippable and skippable; phase 1 stands
alone as a complete feature.

## 7. Risks

- **Cold start / empty feed (highest product risk):** a quiet feed reads as
  a dead app. Mitigated by: community-recent entries in Verkstan (not just
  followed), founder/admin seeding before flag-on, Månadens bygge as a
  posting prompt, and the flag staying off until a handful of real logs
  exist. If the community never posts, the feature quietly stays a
  per-vehicle log — still useful, no feed embarrassment.
- **Moderation load on a small admin team:** more UGC = more reports. The
  pipeline is reports-driven (no proactive review), rate limits bound spam
  volume, and the flag is the kill switch. Realistic load at community
  scale: low.
- **PII in free text/photos:** plates, faces, home garages. Not novel
  (vehicle photos + chat exist) but a bigger surface; §5.1 mitigations, and
  the EXIF check is a hard pre-launch gate.
- **Scope creep into a social network:** comments, DMs, share sheets,
  algorithmic feeds all beckon. Locked decisions already exclude DMs and
  image chat from MVP; this proposal deliberately has **no comments** —
  reactions (phase 2) are the ceiling of interaction. Comments would need
  their own proposal with its own moderation math.
- **Points gaming:** avoided by design (§4.6 — no automatic points).
- **Follow-cap surprise:** the 30-follow `in`-query cap is fine for a local
  community but is a real architectural fork (fan-out-on-write) if the app
  goes multi-community/national. Documented so future-us isn't surprised.
- **Notification fatigue:** per-category opt-out ships day one
  (non-essential category), and following is explicit opt-in per vehicle.

## 8. Open product questions (for the human)

1. **Feed placement:** home-screen section ("Verkstan") in phase 1, or a
   dedicated bottom-nav tab from the start? (Proposal assumes home section;
   a tab raises the visibility stakes on the cold-start risk.)
2. **Visibility for free users:** vehicles are authenticated-readable
   today, so the cheap default is build logs readable by all signed-in
   users, while posting/following stays member-only (garage writes already
   are). Alternatively gate *reading* others' logs behind subscription
   (matching event-detail gating) as a membership carrot — which weakens
   the content flywheel. Which posture?
3. **Naming:** "Bygglogg" for the per-car log and "Verkstan" for the feed —
   or does the brand kit have opinions? (Internal identifiers stay generic:
   `buildLog`, brand-ready per product decisions.)
4. **Månadens bygge:** wanted in phase 2, and who curates it monthly?
5. **Photo count per entry:** proposal says 5 — enough, or keep it at 1–3
   to bound moderation surface?
6. **Push default for `followed_build_update`:** on-by-default (opt-out) as
   proposed, or opt-in? (Follows are already explicit opt-in, so opt-out
   push is defensible.)

## 9. Recommendation

**Build phase 1 after MVP go-live, flag-gated, default off.**

- The strategic case is unusually strong: it is the canonical enthusiast
  feature (garaget.org made it a Swedish habit two decades ago), it fills
  the app's between-events content gap with user-generated, evergreen,
  locally-anchored material, and it takes the durable half of the
  DriveTribe lesson without the cost structure that killed DriveTribe.
- The engineering case is even stronger: this is mostly **assembly of
  shipped parts** — garage callable patterns, the media pipeline, the
  entire chat report/moderation/audit loop, blocking, the notification
  writer, badges, feature flags. No new infrastructure, secrets, external
  APIs, or GCP surface. ~3–4 engineer-weeks for a complete phase 1 is the
  best content-per-effort ratio of any idea currently parked.
- The discipline that keeps it MVP-light: entries + photos + follow + feed
  **only**; reactions phase 2; mod lists phase 3; **no comments, no
  automatic Kronpoäng**; 30-follow cap; flag off until seeded.
- Timing: not before MVP go-live (auth setup, Play closed testing, and the
  two open security-audit HIGHs all outrank it), and answer §8 Q1–Q2 first
  since they shape the phase-1 UI.

## Appendix A — files referenced

- `functions/src/garage/garage-core.ts`, `functions/src/garage/manageVehicle.ts`
- `functions/src/events/postChatMessage.ts`, `reportChatMessage.ts`,
  `moderateReports.ts`, `removeChatMessage.ts`, `chat-core.ts`
- `functions/src/blocking/blocking-core.ts`
- `functions/src/notifications/deliver.ts`, `notifications-core.ts`, `pushTokens.ts`
- `functions/src/badges/badge-core.ts`, `functions/src/badges/awards.ts`
- `functions/src/points/ledger.ts`
- `functions/src/shared/memberActor.ts`, `featureFlags.ts`, `featureFlags-core.ts`
- `functions/src/index.ts`
- `firebase/firestore.rules`, `firebase/storage.rules`
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/garage/`
  (`GarageRepository.kt`, `FirebaseGarageRepository.kt`, `GarageScreen.kt`,
  `VehicleFormScreen.kt`, `GarageCoordinator.kt`, `GarageStrings.kt`, `Vehicle.kt`)
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/media/`
  (`MediaUploader.kt`, `FirebaseMediaUploader.kt`, `ImagePicker.kt`,
  `ImageUploadCoordinator.kt`)
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/blocking/`,
  `.../notifications/`, `.../home/HomeContent.kt`
- `apps/admin/src/features/event-chat/index.ts`,
  `apps/admin/src/features/feature-flags/`, `.../announcements/`
- `contracts/schemas/garage.schema.json`, `contracts/functions/functions.json`
- `docs/product-decisions.md`, `docs/firebase-data-model.md`,
  `docs/firebase-cost-controls.md`
- Adjacent parked proposals: `docs/proposals/vehicle-reg-lookup.md`,
  `docs/proposals/nearby-notifications.md`
