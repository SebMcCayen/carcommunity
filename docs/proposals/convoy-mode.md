# Proposal: "Konvoj" — Convoy Mode for Group Drives

> **Status:** Design proposal / feasibility study — **NOT approved for build.** Parked on the Future Ideas board pending explicit go-ahead.
> **Author:** Claude (delegated feasibility spike)
> **Date:** 2026-07-10
> **Recommendation (short version):** Strong product fit and unusually high infrastructure reuse — but **defer** until two hard prerequisites land: the unfixed live-location IDOR in the RTDB rules, and the FCM push send path. Then build event-attached convoys first, behind a `convoy` feature flag. Park push-to-talk voice entirely.

---

## 1. Summary

Upgrade the existing group-drive + live-location features into a true **convoy
experience**: a drive leader starts a convoy (standalone or attached to an
event), participants join via a short code / QR / their event RSVP, everyone
sees each other's live positions on the shared map during the drive, the
leader can broadcast **stop/regroup waypoints** mid-drive, and participants who
fall behind see a glanceable **"distance behind leader"** indicator. When the
convoy ends, location sharing stops automatically and each participant who
opted in gets the route saved as a regular saved drive.

Niche apps prove the demand (Convoy Tracker, KONVOY, Velox and similar), but
none of them sit inside a community platform where the convoy's *social
context* — the event, the RSVP list, the event chat, the member garage —
already exists. For carcommunity this is largely a **composition of
infrastructure that already ships**: live RTDB location sharing, the
group-drive roster, drive recording + saved drives, the Mapbox map, and
per-event chat.

Two things temper the enthusiasm, and both are load-bearing:

1. **Security prerequisite.** A recent security audit found an unfixed
   **live-location IDOR in the RTDB rules** (`firebase/database.rules.json`:
   any active member can read `liveLocation/{uid}/latest` for *any* uid).
   Convoy mode multiplies the number of people sharing location concurrently,
   so it **must not** reuse or widen that surface. This proposal designs
   convoy visibility on **separate, per-convoy RTDB paths with
   membership-scoped rules**, and treats the IDOR fix as a blocking
   prerequisite (§3.1, §4.3).
2. **Safety.** A convoy feature used *while driving* sits directly on the
   product's "Driving mode" principle ("funktionalitet får inte uppmuntra
   interaktion som ökar risk under körning") and on Swedish traffic law
   (*vårdslöshet i trafik*). The design must be glanceable, must never show
   other drivers' speeds, and must never gamify "keeping up" (§3.2).

---

## 2. Existing infrastructure we can reuse

All backend lives in `functions/src` (region `europe-west1`, App Check
enforced on callables, Node 22 / TypeScript). Target scale is small (tens of
active users), which favours simple full-scan approaches over geo-indexes —
the same stance the live-location TTL sweep already documents.

This section is deliberately honest about what is genuinely reusable versus
what is new. Verdict up front: the **plumbing** (GPS → callable → RTDB →
map marker; roster state machine; route recording → saved drive) is roughly
80 % there. The **convoy entity itself** (lifecycle, join codes, leader role,
waypoints, scoped visibility rules, "distance behind leader") is new code —
but new code built almost entirely from existing idioms.

### 2.1 Live location (RTDB) — `functions/src/live/`

The direct ancestor of convoy position sharing.

- **Data shape** (`live-core.ts`): `liveLocation/{uid}/session` (backend-owned
  session: status `active|stopped|expired`, duration, `expiresAt`,
  `stopReason`) and `liveLocation/{uid}/latest` (lean marker: lat/lon,
  accuracy, heading, speed, `recordedAt`, denormalized `displayName`). **All
  RTDB writes are backend-only** — rules deny every client write.
- **Callables** (`session.ts`): `live.startSession`, `live.updatePosition`
  (requires an active unexpired session; enforces the 60-second
  `POSITION_STALENESS_SECONDS` freshness guard via `guardPositionFreshness`),
  `live.stopSession`, and `live.hideMeNow` (privacy stop that works even while
  suspended).
- **TTL sweep** (`scheduled.ts`): `live-cleanupExpired` runs every 5 minutes,
  expires overdue sessions and removes `latest` markers older than 15 minutes
  (`isLatestStale`). Deliberate full scan — documented as the right trade at
  MVP scale.
- **Blocking mirror**: because RTDB rules cannot read Firestore, the blocking
  domain mirrors `userBlocks` into RTDB (`liveLocationBlocks/`) and the
  `latest` read rule denies either party of a block. **This mirror pattern is
  exactly how convoy membership will be exposed to RTDB rules** (§4.3).

**Reuse:** the session/marker split, backend-only writes, freshness guards,
the `hideMeNow` privacy idiom, the sweep cadence, and the Firestore→RTDB
mirror pattern are all directly reusable. What is *not* reusable is the
**path layout and its read rule** — see §3.1.

### 2.2 Group drive roster — `functions/src/groupDrive/`

- `events/{eventId}/groupDriveParticipants/{uid}` — document ID = uid, all
  writes via callables (`participants.ts`): `join` (published event + RSVP
  going/maybe + not ended, idempotent, rejoin resets `joinedAt`),
  `updateStatus` (`joined|on_the_way|arrived`, never `left`), and an
  idempotent `leave` that deliberately does **not** stop the live-location
  session.
- Pure guards in `groupdrive-core.ts` (`guardJoinableEvent`,
  `buildParticipantDocument`) with the zod-parse + `GuardResult` idiom used
  across every domain.
- Firestore rules (`firebase/firestore.rules`) already gate roster reads to
  admins, the owner, or active members of a published event.

**Reuse:** the convoy member roster is this collection's state machine with a
`leader` role added. The join-precondition guard decomposition, idempotent
join/rejoin/leave transactions, and denormalized `displayName` transfer
one-for-one. An **event-attached convoy** can even reuse `guardJoinableEvent`
verbatim for its RSVP-based join path.

### 2.3 Drive recording + saved drives — `functions/src/drives/`, `apps/android/.../drives/`

- `drives.save` (`saveDrive.ts`): member-only callable that computes
  distance/duration/average speed server-side (`drive-calculations.ts`),
  creates `rides/{rideId}`, and is **idempotent per `sourceSessionId`**
  (deterministic doc ID `${uid}_${sourceSessionId}` claimed in a
  transaction). Route GPS goes to Cloud Storage
  (`rideRoutes/{uid}/{rideId}/route.bin`), never Firestore. No top-speed
  field is ever stored (locked product decision).
- Android side: `DriveRecordingCoordinator.kt` (start → record → **explicit
  save/discard prompt** → save), `DriveLocationController.kt` (fused-location
  fixes), `FirebaseDrivesRepository.kt`.

**Reuse:** end-of-convoy route saving needs **zero new backend**. Each
participant's client records its own track during the convoy (the recording
coordinator already exists) and, if the participant opted in, submits it via
the existing `drives.save` with `sourceSessionId = convoy-<convoyId>` —
idempotent per participant for free. The explicit-save product rule ("saved
drives sparas endast efter uttrycklig användarhandling") is honoured by
keeping the existing save/discard prompt; convoy merely pre-fills the title.

### 2.4 Map + Android location plumbing — `apps/android/.../map/`, `.../location/`, `.../live/`

- `MapScreen.kt` / `MapMarkers.kt`: Mapbox `MapView` that already renders the
  caller's own marker in the primary colour and *other members' markers*
  (documented as "e.g. a group-drive roster") in the secondary colour.
- `FirebaseLiveLocationRepository.kt`: callable invocation + RTDB value
  listeners bridged to coroutines; `LiveLocationCoordinator.kt`: the
  unit-testable command-state idiom every feature copies.
- `location/BackgroundLocation` cadence constants and the existing
  `LocationSharingService` foreground service (the documented follow-up home
  for screen-off recording).

**Reuse:** the map already draws multi-member markers; convoy adds marker
labelling, a leader highlight, waypoint pins, and the distance-behind-leader
chip. The repository/coordinator pattern is copied, not invented.

### 2.5 Event chat, notifications, geo helpers, cross-cutting

- **Event chat** (`functions/src/events/postChatMessage.ts`): validated,
  rate-limited (~5 msgs/30 s), backend-only writes. An event-attached convoy
  gets a text channel for free (the event's chat). Leader **waypoint
  broadcasts are not chat messages** — they are structured documents (§4.2) —
  but the rate-limit and moderation idioms transfer.
- **Notifications** (`functions/src/notifications/`): the eligibility-aware
  `writeInAppNotification` writer (idempotent per deterministic ID, honours
  suspension/deletion/preferences) and hashed push-token registry
  (`pushTokens.ts`). **⚠️ Gap:** actual FCM delivery (`sendPushNotification`)
  is *still not implemented* — both `pushTokens.ts` and
  `notifications-core.ts` defer it to the end-of-MVP Firebase console setup.
  A "regroup" ping that must reach a *backgrounded* phone depends on it
  (§4.5). The notification **category allowlist** is closed; a
  `convoy_regroup` category requires the documented product + security
  review.
- **Geo helpers** (`functions/src/crownHunt/crown-hunt-geo.ts`):
  `haversineDistanceMeters`, `isPositionFresh`, `isWithinGeofence`,
  `isPlausibleJump` — pure, tested, reusable for any server-side distance or
  anti-spoof check.
- **Cross-cutting:** `shared/memberActor.ts` (`requireMemberActor` /
  `requireActiveActor`), `shared/featureFlags.ts` +
  `contracts/features/feature-flags.json` (a new `convoy` flag, default
  **false**), the blocking domain, and the admin group-drive module
  (`apps/admin/src/features/group-drive/index.ts`) whose "aggregate counts
  only, no live map in admin, no individual positions" stance convoy admin
  views must copy.

### 2.6 What is genuinely NEW (no existing analog)

- The **convoy entity** and its lifecycle (forming → active → ended), join
  codes / QR joining, and the **leader role** (first role-differentiated
  member feature in the app — everything else is member vs. admin).
- **Per-convoy RTDB paths + membership-scoped read rules** and the
  `convoyMembers` mirror (pattern exists; the rules themselves are new and
  security-critical).
- **Waypoint broadcast** documents + their client rendering.
- **Distance-behind-leader** computation and glanceable in-drive UI.
- **Regroup push ping** (gated on the unbuilt FCM send path).
- End-of-convoy **auto-stop fan-out** (server ends convoy → all markers
  purged) — a one-to-many variant of `hideMeNow`.

---

## 3. Security & safety framing (the gates for this feature)

### 3.1 The live-location IDOR is a blocking prerequisite

The current RTDB rule (`firebase/database.rules.json`) makes
`liveLocation/{$uid}/latest` readable by **any** active, non-suspended,
non-blocked member for **any** `$uid`. The 2026-07 security audit flagged
this as a live-location IDOR (HIGH), and it is **not yet fixed**.

Consequences for this proposal:

- **Convoy mode must not touch `liveLocation/`.** Convoy positions live under
  a *new* top-level path with reads scoped to convoy membership (§4.3). A
  convoy member's position is visible to their convoy — never to the general
  member population — unless they *separately* run a normal live-location
  session.
- **The IDOR fix comes first.** Shipping a feature that says "your position
  is only visible to your convoy" while the *general* rules still allow
  member-wide reads would make the scoping claim misleading. The fix is a
  small, independent rules/product change and should land regardless of this
  proposal.
- **No convoy data may leak through the old path.** `convoy.updatePosition`
  writes only the convoy marker; it must not implicitly refresh
  `liveLocation/{uid}/latest`.

### 3.2 Driver distraction & Swedish traffic law (design-critical)

The product already commits to "Driving mode ska främja säkert användande"
and "funktionalitet får inte uppmuntra interaktion som ökar risk under
körning" (`docs/product-decisions.md`). Convoy mode is used *while driving*,
so this section is first-class design input, not compliance garnish. Relevant
Swedish framing: *vårdslöshet i trafik* (Trafikbrottslagen) and the mobile
device rule (handheld use while driving is prohibited; mounted, glanceable
use is the only acceptable mode).

Design consequences (all treated as **requirements**, not options):

- **Glanceable, zero-interaction driving UI.** During the active phase the
  screen is a map + at most two large-type facts (next waypoint, distance
  behind leader). No buttons that invite mid-drive tapping except a single
  oversized "Lämna konvoj / Dölj mig" action. All setup (join, opt-ins,
  route-save choice) happens **before** departure.
- **Audio cues over visual ones.** Waypoint broadcasts and regroup pings are
  announced by a chime + short TTS phrase ("Ny samlingspunkt: OKQ8 Onsala")
  so nobody needs to look at the phone. Visual detail is for passengers and
  stopped cars.
- **Passenger mode.** A "jag är passagerare" toggle unlocks richer UI (member
  list, chat shortcut). Default assumption is *driver*, i.e. minimal UI. We
  cannot verify the claim — same trust stance as every navigation app — but
  the default must be the safe one.
- **Never show other members' speeds.** The RTDB marker may carry
  `speedMetersPerSecond` for staleness/plausibility checks, but the convoy UI
  **never renders another member's speed** — no per-car speed, no "convoy
  pace". This extends the locked "ingen toppfart/speed-ranking" decision.
- **Never encourage catching up.** "Distance behind leader" is presented as
  *information for the leader and regrouping* — phrased "Du är 1,2 km bakom",
  never "Kör ikapp!", with no countdowns, no colour-coded urgency, no points,
  no badges tied to gap size or tightness. The *product's* answer to a gap is
  the **leader regroups** (broadcasts a stop), not the straggler speeds up.
  Copy review in Swedish is part of the definition of done.
- **No gamification of the drive itself.** No Kronpoäng for joining, staying
  tight, or finishing — aligned with "inga mekaniker som premierar riskfylld
  körning".
- **Auto-expiry.** Convoy location sharing ends automatically when the convoy
  ends, when the convoy's `expiresAt` passes (leader forgot to end it), or
  when a member goes silent past the marker TTL — nobody stays visible by
  accident (§4.5).

### 3.3 Abuse & membership integrity

- **Join codes leak.** A screenshot of a QR code reaches Instagram in
  minutes. Mitigations: codes are single-convoy and die with it; the leader
  can **rotate the code** and **remove a member** (removal purges their
  marker and revokes their read access via the membership mirror); joining
  requires an active member account (`requireMemberActor`) so a leaked code
  admits members, not the public.
- **Rate limits:** cap convoys created per user per day, members per convoy
  (product question, §8), and waypoint broadcasts per leader (reuse the chat
  rate-limit idiom).
- **Blocking:** if either party of a block is in a convoy, the other cannot
  join it (checked in the join callable against `userBlocks`); the existing
  RTDB block mirror pattern extends to convoy reads if needed.
- **Kill switch:** `convoy` feature flag, default false.

---

## 4. Proposed design

### 4.1 Lifecycle

```
create (leader: standalone OR attached to an event)
      │         join code / QR / event RSVP path
      ▼
   FORMING ──── members join, set opt-ins (route save), pick passenger mode
      │  leader taps "Starta konvoj"
      ▼
    ACTIVE ──── positions flow (convoy-scoped RTDB markers)
      │         leader broadcasts waypoints (stop/regroup/finish)
      │         leader sends regroup ping (push, gated on FCM)
      │         members leave / are removed (marker purged immediately)
      │
      ├─ leader taps "Avsluta"          ─┐
      ├─ expiresAt passes (auto)         ├──►  ENDED
      └─ leader vanishes > grace period ─┘      │
                                                ▼
                              markers purged; each opted-in member's client
                              opens the EXISTING save/discard drive prompt
                              (drives.save, sourceSessionId = convoy id)
```

- **Create:** an active member calls `convoy.create` (flag-gated). Standalone
  convoys get a short join code; event-attached convoys additionally allow
  any member with RSVP going/maybe to join without the code (reusing
  `guardJoinableEvent`). The creator is the **leader**.
- **Join:** `convoy.join` with `{ code }` or `{ eventId }`. Idempotent;
  rejoin follows the group-drive rejoin semantics. Join is only possible in
  `forming` or `active` states.
- **Active:** members' clients push positions via `convoy.updatePosition`
  (same 60-second freshness contract as live location). Clients listen to the
  convoy's RTDB subtree and render all markers + waypoints. The
  distance-behind-leader chip is computed **client-side** with the local
  haversine (each client already reads the leader's marker; no server hot
  path needed).
- **End:** `convoy.end` (leader), or the scheduled sweep on `expiresAt`, or a
  leader-silent grace fallback. Ending sets terminal status, **deletes the
  entire RTDB convoy subtree at once** (one-to-many `hideMeNow`), and writes
  an in-app notification to members whose route-save opt-in is set, prompting
  the standard save/discard flow.

### 4.2 Data model

**Firestore** (durable; backend-only writes, like every domain):

- `convoys/{convoyId}`: `leaderUid`, `leaderDisplayName` (denormalized),
  `eventId` (nullable), `title`, `status`
  (`forming|active|ended|cancelled|expired`), `createdAt`, `startedAt`,
  `endedAt`, `expiresAt` (hard cap, e.g. start + 6 h), `memberCount`,
  `waypointCount`. **The join code is NOT on this document** — it lives in
  `convoys/{convoyId}/private/join` (`codeHash`, `rotatedAt`), never
  client-readable; joining goes through the callable, which looks up an
  internal `convoyJoinCodes/{codeHash} → convoyId` index. Members read the
  code to *display/share* it only via a leader-only callable response.
- `convoys/{convoyId}/members/{uid}` (doc ID = uid, group-drive roster
  pattern): `displayName`, `role` (`leader|participant`), `status`
  (`joined|left|removed`), `passengerMode` (bool, self-declared),
  `routeSaveOptIn` (bool, default per §8), `joinedAt`, `leftAt`, `updatedAt`.
- `convoys/{convoyId}/waypoints/{waypointId}`: `type`
  (`stop|regroup|finish`), `latitude`, `longitude`, `note` (bounded plain
  text), `createdBy` (leader uid), `createdAt`, `active` (bool — a new
  regroup waypoint deactivates the previous one).

Firestore rules: convoy + members + waypoints readable by admins and by
members of that convoy (`exists(.../members/$(request.auth.uid))` with status
!= removed); zero client writes.

**RTDB** (ephemeral positions; backend-only writes):

- `convoyLocation/{convoyId}/{uid}` — lean marker, same field shape as
  `buildLatestNode` (lat/lon, accuracy, heading, speed-for-staleness,
  `recordedAt`, `displayName`).
- `convoyMembers/{convoyId}/{uid}: true` — membership **mirror** written by
  the join/leave/remove callables, exactly like the `liveLocationBlocks`
  mirror, because RTDB rules cannot read Firestore.

### 4.3 RTDB rules sketch (the security heart of the feature)

```json
"convoyLocation": {
  "$convoyId": {
    ".read": "auth != null
              && auth.token.activeMember == true
              && auth.token.suspended != true
              && root.child('convoyMembers').child($convoyId)
                     .child(auth.uid).exists()",
    ".write": false
  }
},
"convoyMembers": { ".read": false, ".write": false }
```

Properties, stated explicitly because the audit context demands it:

- **No cross-convoy reads:** the read grant is scoped to `$convoyId` and
  keyed on the caller's own uid appearing in that convoy's mirror. There is
  no path where member A of convoy X can read convoy Y, and no path where a
  non-member can read anything — the top-level default deny stays.
- **No client writes anywhere** — positions flow only through
  `convoy.updatePosition`, which verifies active-convoy membership and the
  freshness guard server-side.
- **Removal is immediate:** `convoy.leave` / leader removal deletes the
  mirror entry *and* the member's marker in the same multi-path update; the
  read grant dies with the mirror entry.
- **Prerequisite restated:** this scoping is only honest once the general
  `liveLocation/{uid}/latest` member-wide read (the audit IDOR) is fixed.
  Convoy mode must not ship before that fix.

### 4.4 Backend callables & jobs (sketch — names illustrative)

All follow the house pattern: zod parse in a pure `convoy-core.ts`, guards
returning `GuardResult`, `requireMemberActor`, transactions for roster
mutations, `enforceAppCheck`, region `europe-west1`.

- `convoy.create` (member, `convoy` flag): creates convoy + leader member doc
  + join-code index + RTDB mirror entry; rate-limited per user/day.
- `convoy.join` (member): by `{ code }` (hash lookup) or `{ eventId }`
  (reuses `guardJoinableEvent`); block-pair check; idempotent rejoin;
  writes roster doc + mirror.
- `convoy.leave` (`requireActiveActor` — leaving must work if entitlement
  lapsed mid-drive, group-drive parity): roster → `left`, purge marker +
  mirror entry. Idempotent.
- `convoy.removeMember` / `convoy.rotateCode` (leader only): removal purges
  marker + mirror; rotation rewrites the code index.
- `convoy.start` / `convoy.end` (leader): state transitions; `end` deletes
  the whole `convoyLocation/{convoyId}` and `convoyMembers/{convoyId}`
  subtrees and fans out the route-save prompt notification via
  `writeInAppNotification` (idempotent per `convoyId_uid`).
- `convoy.updatePosition` (member of an active convoy): freshness guard
  (`guardPositionFreshness` reused), writes the marker.
- `convoy.broadcastWaypoint` (leader, rate-limited): creates the waypoint
  doc, deactivates the previous active one.
- `convoy.regroupPing` (leader, rate-limited): in-app notification now;
  high-priority FCM **once the send path exists** — until then this callable
  is honest-but-weak (foregrounded clients see it via their waypoint/notification
  listeners; a chime fires from the active listener, which in practice covers
  cars actively in the convoy with the app mounted and on).
- **Scheduled** `convoy-cleanupExpired` (5-min cadence, `live/scheduled.ts`
  shape): expires convoys past `expiresAt`, removes silent markers past the
  15-minute TTL, ends convoys whose leader marker has been silent past a
  grace period, and purges any orphaned RTDB subtrees.

### 4.5 Client UX (Android; Swedish UI per product decisions)

- **Pre-drive (interactive allowed):** create/join sheet (code entry, QR
  scan, or "Gå med via eventet"), roster with leader crown, route-save opt-in
  toggle, passenger-mode toggle, safety notice (mount the phone; the app is
  not a navigator; regroup — don't chase).
- **Driving (glanceable only):** full-bleed map, convoy markers with initials
  (leader visually distinct), active waypoint pin, one status chip ("1,2 km
  bakom ledaren" — informational tone only), one oversized leave/hide button.
  Audio: chime + short TTS on new waypoint and regroup ping. No chat, no
  member list, no speeds of others.
- **Leader extras:** "Ny samlingspunkt" (drops a pin at own position or a
  long-press point — designed for use **while stopped**; the UI nudges this
  by requiring a confirm step), "Samla ihop" (regroup ping), end convoy.
- **Post-drive:** the existing save/discard prompt from
  `DriveRecordingCoordinator` with a pre-filled title ("Konvoj: {title}");
  discard stores nothing.
- **Admin web:** read-only convoy list with aggregate counts, mirroring the
  group-drive admin module's stance — **no live map, no individual
  positions**. Kill switch via the flags UI.

### 4.6 Push-to-talk voice — scoped and parked

In-convoy PTT (leader announcements, "puncture, pulling over") is the obvious
"phase 5". Recommendation: **park it on the ideas board as its own item**.

- **WebRTC (real PTT):** needs a signalling channel plus, realistically, a
  TURN relay for carrier-NAT mobile links (~50 % of pairs won't connect
  P2P). Managed options (LiveKit, Twilio) or self-hosted coturn both add
  standing cost and an entirely new operational surface — hard to justify
  against the documented ~SEK 500/month Firebase budget and a
  tens-of-users community.
- **FCM voice notes (async):** record → upload to Storage → notify convoy.
  Cheap and buildable on existing pieces, but it is *not* PTT (latency in
  seconds), it collides with the "no voice/video chat in MVP" spirit of the
  locked chat decision, and recording while driving is its own distraction
  problem.
- Both variants also raise the distraction stakes (§3.2). The regroup ping +
  TTS waypoint announcements deliver most of the practical value ("everyone,
  pull in at the next stop") with none of the cost. Revisit only if real
  usage shows convoys actively suffering without it.

---

## 5. Privacy, cost & performance

**Privacy** (extends the locked live-location decisions):

- **Opt-in, manual, time-boxed, scoped:** convoy sharing starts only by
  joining a convoy and starting the drive phase, is visible only to that
  convoy's members, and hard-expires at `expiresAt`. "Dölj mig nu" semantics
  are preserved: leaving the convoy (always one tap, works while suspended
  via `requireActiveActor` parity with `hideMeNow`) purges the member's
  marker immediately.
- **Auto-purge:** ending/expiring a convoy deletes the whole
  `convoyLocation/{convoyId}` subtree at once; the sweep catches stragglers.
  **No position history is ever stored server-side** — the only route
  artifact is the participant's *own* client recording, saved *only* through
  the explicit opt-in + existing save prompt, into their own `rides/{rideId}`
  (route file in their own Storage prefix). No shared/merged convoy route in
  MVP.
- **Waypoints outlive positions but are low-sensitivity** (leader-chosen
  public stops, not member positions); still, purge or archive the waypoint
  subcollection with the convoy record per a retention window (open question
  §8).
- **Free users:** the locked decision "aktiv subscription krävs för att se
  andras live positioner" makes convoy participation effectively
  member-only; the callables enforce `requireMemberActor` (leaving excepted).
  No coordinates in logs (Kronjakt rule).
- **Admins** see aggregates only, audited — no live member positions
  (group-drive admin parity).

**Cost & performance** (ADR-001 budget frame):

- Position updates: the dominant load. At a generous 15 cars × 1 update /
  5 s × 3 h convoy ≈ 32 k callable invocations + tiny RTDB writes — pennies,
  and convoys are occasional (weekend events), not continuous. Marker fan-out
  to 15 listeners of a lean node is well inside RTDB free-tier bandwidth at
  this scale.
- Client battery/data: same profile as an existing live-location session +
  drive recording running together, which the app already supports; the
  convoy screen adds one RTDB listener.
- The 5-minute sweep merges into the existing scheduled-job pattern (or the
  existing sweep is extended) — no new standing cost.
- The only real cost cliff is the parked WebRTC option (§4.6) — which is a
  reason it is parked.

---

## 6. Effort estimate & phasing

Rough order-of-magnitude for one engineer familiar with the codebase.

- **Phase 0 — prerequisites (blocking, mostly not convoy work):**
  (a) **fix the live-location IDOR** in `firebase/database.rules.json`
  (independent security work, already pending from the audit);
  (b) the **FCM `sendPushNotification` path** — required only for the
  regroup ping's backgrounded-device case; already scheduled as an
  end-of-MVP task. (c) product/security review for the `convoy_regroup`
  notification category.
- **Phase 1 — backend core (~1–1.5 weeks):** `convoy-core.ts` (pure guards +
  builders + tests), create/join/leave/remove/rotate/start/end/updatePosition
  callables, join-code index, RTDB mirror + rules, scheduled sweep, `convoy`
  flag. Heavy reuse of live/groupDrive idioms.
- **Phase 2 — Android convoy vertical (~1.5–2 weeks):** repository +
  coordinator (house pattern), create/join UX (code + QR + event path),
  convoy map layer on `MapScreen` (markers, leader highlight, waypoint pins,
  distance chip), glanceable driving screen + audio cues (chime/TTS),
  passenger toggle, Swedish copy with the §3.2 review.
- **Phase 3 — leader tools + regroup (~3–5 days, push part gated on FCM):**
  waypoint broadcast + deactivation, regroup ping (in-app now, high-priority
  push when the send path lands), rotate/remove UX.
- **Phase 4 — route auto-save + polish (~2–4 days):** end-of-convoy
  notification fan-out, pre-filled save prompt integration
  (`sourceSessionId = convoy-<id>`), empty/edge states.
- **Phase 5 — admin + hardening (~2–3 days):** read-only admin module
  (aggregate counts, group-drive parity), rate limits, emulator rules tests
  for the §4.3 matrix (member/non-member/removed/other-convoy).

**Total: roughly 4–5 weeks** after Phase 0. A deliberately smaller MVP-light
cut — event-attached convoys only, no standalone codes, no QR, regroup as
in-app-only — fits in **~2.5–3 weeks** and still delivers the core
experience.

---

## 7. Risks

| # | Risk | Severity | Notes |
|---|------|----------|-------|
| R1 | **Driver distraction / traffic-safety liability** — a mid-drive feature invites glances and "keep up" behaviour (*vårdslöshet i trafik*) | **Critical** | §3.2 requirements: glanceable UI, audio-first, passenger mode, no others' speeds, regroup-not-catch-up framing, Swedish copy review. Gate on the existing driving-mode principle. |
| R2 | **Widening the unfixed live-location IDOR** — new location surface built before the audited rules hole is closed | **High** | Hard prerequisite: fix the IDOR first; per-convoy paths + membership-scoped rules (§4.3); emulator rules tests in the definition of done. |
| R3 | **Regroup ping useless without FCM push** — the send path is still unbuilt | **Medium** | Phase-gated; in-app + TTS covers foregrounded (mounted) devices, which is the realistic in-drive state; full fix ships with the end-of-MVP FCM task. |
| R4 | **Join-code leakage** admits unwanted members | **Medium** | Member-only join, code rotation, leader removal with instant marker+mirror purge, per-convoy scope, block-pair checks. |
| R5 | **Battery/data drain** ends drives early or silently drops markers | **Medium** | Reuses the tuned fused-location cadence; the 15-min TTL keeps ghosts off the map; foreground-service follow-up already documented in `DriveLocationController.kt`. |
| R6 | **Scope creep toward navigation** ("show the route to the waypoint") | **Medium** | Locked decision: no in-app turn-by-turn; deep-link the active waypoint to Google Maps, nothing more. |
| R7 | **Low utilisation at current community size** — convoys need several simultaneous cars | **Low-Med** | Event-attached-first strategy rides existing meets where cars already cluster; standalone codes can wait. |
| R8 | **Leader single-point-of-failure** (leader's phone dies mid-drive) | **Low** | Leader-silent grace fallback ends the convoy; optional leader-handoff is a later nicety, not MVP. |

---

## 8. Open product questions (for the human)

1. **Standalone convoys vs. event-only:** the locked decision says group
   driving is "MVP-light och kopplad till events". Standalone join-code
   convoys extend beyond that. Ship event-attached-only first (recommended,
   and cheaper), or is the standalone code/QR path worth including at once?
2. **Membership gating:** convoys are effectively member-only because seeing
   others' positions requires a subscription. Comfortable making that
   explicit ("Konvoj är en medlemsfunktion"), including the *leader* needing
   membership?
3. **Route-save opt-in default:** default ON at join (more saved drives,
   more value) or OFF (privacy-forward)? The explicit save prompt exists
   either way.
4. **Max convoy size** (proposal: 20) and **max duration / `expiresAt` cap**
   (proposal: 6 h)?
5. **Retention:** keep ended convoy records + waypoints (nice history: "our
   Ljungskile run"), or purge after N days? Positions are never retained
   regardless.
6. **Regroup ping urgency:** is in-app + TTS acceptable for v1 (FCM push
   later), or is push a launch requirement — which chains the whole feature
   behind the end-of-MVP FCM task?
7. **Passenger mode:** acceptable as a self-declared toggle (industry norm),
   or should driver-mode restrictions apply unconditionally?
8. **Kronpoäng:** confirm the §3.2 stance that convoy participation earns
   **no** points/badges (recommended), to keep gamification away from
   driving behaviour.

---

## 9. Recommendation

**Build it — but not yet, and in the narrow order below.** This is the
rare backlog idea where the codebase is genuinely most of the way there: the
position pipeline, roster state machine, map markers, drive recording,
event linkage, flags, and blocking all exist and were built in reusable
house patterns. The new work is one well-scoped vertical (convoy entity +
scoped rules + glanceable UI), not a new platform. It also deepens the
community's core loop (events → driving together) rather than bolting on a
side feature — a better strategic fit than most parked ideas.

Concretely:

1. **Fix the live-location IDOR first** (`firebase/database.rules.json`).
   This is already-pending audit work and is a hard prerequisite — convoy
   mode must launch onto rules whose scoping story is true.
2. **Keep this parked** until that fix lands and the §8 questions (notably
   event-only vs. standalone, and the regroup-push question) are answered.
3. When approved, build the **MVP-light cut**: event-attached convoys only,
   behind a default-off `convoy` flag, with the §3.2 safety requirements
   treated as acceptance criteria and emulator rules tests for the §4.3
   access matrix. ~2.5–3 weeks.
4. **Add** standalone codes/QR and the FCM-backed regroup ping as fast
   follows once the push send path ships.
5. **Park push-to-talk voice separately** (§4.6) — do not let it ride along;
   its cost and distraction profile are a different conversation.

---

## Appendix A — files referenced

Backend (`functions/src/`):

- `live/live-core.ts`, `live/session.ts`, `live/scheduled.ts` — session/marker model, callables, TTL sweep
- `groupDrive/groupdrive-core.ts`, `groupDrive/participants.ts` — roster state machine, join guards
- `drives/drives-core.ts`, `drives/saveDrive.ts`, `drives/drive-calculations.ts` — saved drives, `sourceSessionId` idempotency
- `events/postChatMessage.ts`, `events/chat-core.ts` — event chat, rate-limit idiom
- `crownHunt/crown-hunt-geo.ts` — haversine/freshness/geofence helpers
- `notifications/notifications-core.ts`, `notifications/deliver.ts`, `notifications/pushTokens.ts`, `notifications/adminSend.ts` — in-app writer, category allowlist, FCM gap
- `shared/memberActor.ts`, `shared/featureFlags.ts` — actor guards, flags

Rules & contracts:

- `firebase/database.rules.json` — current RTDB rules (audit IDOR lives here; §3.1, §4.3)
- `firebase/firestore.rules` — `groupDriveParticipants` read rule (roster precedent)
- `contracts/features/feature-flags.json` — flag registry (new `convoy` key)

Android (`apps/android/app/src/main/java/com/kungsbackacarcommunity/app/`):

- `live/FirebaseLiveLocationRepository.kt`, `live/LiveLocationCoordinator.kt` — callable + RTDB listener patterns
- `groupdrive/GroupDrive.kt`, `groupdrive/GroupDriveCoordinator.kt` — roster mirror of the backend contract
- `drives/DriveRecordingCoordinator.kt`, `drives/DriveLocationController.kt` — recording lifecycle, explicit-save rule, GPS source
- `map/MapScreen.kt`, `map/MapMarkers.kt` — Mapbox multi-member markers

Admin web:

- `apps/admin/src/features/group-drive/index.ts` — aggregate-only admin stance to copy

Docs:

- `docs/product-decisions.md` — locked decisions cited throughout (driving mode, live location, saved drives, no speed ranking, navigation, subscriptions)
- `docs/proposals/roadside-sos-alert.md` — adjacent parked idea (shares the FCM-gap dependency and location-privacy idioms)
- `docs/proposals/nearby-notifications.md` — adjacent parked idea (notification-category review precedent)
