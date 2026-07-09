# Proposal: Relevant Nearby Notifications

> **Status: PROPOSAL — not approved, not implemented.**
> This is a feasibility and design document for a **non-MVP backlog idea**. It
> contains **no application code** and changes no runtime behaviour. Nothing
> here is scheduled for a release. It exists to let us make a build / spike /
> defer decision with the real codebase in front of us.

- **Author:** Claude (delegated design spike)
- **Date:** 2026-07-09
- **Scope:** Backend (Cloud Functions), Android client, notification pipeline
- **Idea source:** future-ideas board (GitHub Project #2)

---

## 1. Summary

Notify a member about relevant things happening **physically near them** —
nearby published events, nearby active group drives / other members, and nearby
partner offers or Kronjakt (Crown Hunt) point opportunities — driven by
location, strictly opt-in, and engineered to not be spammy.

The good news: almost every primitive this needs **already exists** in the
backend. We already store user location (RTDB live location), we already have a
single backend-only notification writer with a per-category preferences model,
we already have battle-tested, privacy-conscious geo helpers (Haversine,
geofence-with-accuracy, freshness/plausibility checks) built for Kronjakt, and
the notification schema **already reserves the exact categories this feature
needs** (`nearby_event`, `partner_offer`) behind a documented "requires product
and security review" gate.

The hard part is not the plumbing. It is (a) **background location** — the
permission, privacy, and battery cost of knowing where a member is when the app
is closed — and (b) **density**: "nearby" only produces value when enough
members are in the same place at the same time, and the current target scale is
**20–30 active users** across the Kungsbacka area.

**Recommendation (see §9): DEFER the background/member-proximity ambition; SPIKE
a narrow, foreground-only "nearby now" slice** (nearby published events and
active partner offers, computed only when the user opens the map/app with
location already granted). This delivers most of the perceived value with none
of the background-location liability, and it is the honest fit for current
scale.

---

## 2. Existing infrastructure to reuse

The design **builds on** these modules and must not duplicate them.

### 2.1 Notification sending — `functions/src/notifications/`

- **Single backend writer.** `writeInAppNotification(recipientUid, input,
  notificationId?)` in `deliver.ts` is the *only* path into the in-app inbox
  (`notifications/{uid}/items/{notificationId}`). Clients can never author
  notifications. It already enforces recipient eligibility (deleted → nothing;
  suspended → only essential account notices) and honours per-category
  opt-outs.
- **Idempotent delivery.** Passing a deterministic `notificationId` makes
  delivery create-if-absent inside a transaction — a replayed producer never
  duplicates. **This is the dedup primitive we build cooldown on top of.**
- **Preferences model.** `userPrivate/{uid}.notificationPreferences` is an
  owner-writable map of `{ [category]: { inApp?: boolean, push?: boolean } }`.
  `decideInAppDelivery()` reads it. Essential categories cannot be disabled;
  everything else can.
- **Categories already reserved.** `notifications-core.ts` defines the active
  category allowlist and explicitly documents *future* categories that are
  deliberately **not** yet accepted:

  > "The legacy contract also defines FUTURE categories (`partner_offer`,
  > `event_chat`, `nearby_event`) that must not be activated without product
  > and security review — they are deliberately NOT accepted here."

  **This proposal is, in effect, the product-and-security review that unlocks
  `nearby_event` and `partner_offer`.** Adding them is a one-line allowlist
  change plus new action types.
- **Push tokens.** `pushTokens.ts` registers devices at
  `userPrivate/{uid}/pushTokens/{tokenId}`, storing **only** the SHA-256 hash
  of the FCM token (the hash is the doc ID → idempotent), never the raw token,
  with `platform`, `appVersion`, `buildNumber`.
- **⚠️ FCM push delivery is not implemented yet.** `sendPushNotification` is
  documented as shipping "with the Firebase console / FCM project setup at the
  end of the MVP" (`index.ts`, `pushTokens.ts`). Today the inbox is
  **in-app only**. A nearby feature whose whole point is to reach a user who is
  *not currently looking at the app* is therefore **blocked on FCM push
  landing first** if we want true push; the in-app-only version is much weaker
  (the user only sees it next time they open the app).

### 2.2 Storing user location — `functions/src/live/` (RTDB)

- `liveLocation/{uid}/session` — backend-owned session (`status`
  active|stopped|expired, `duration` 1h|2h|4h, `startedAt`, `expiresAt`).
  **No client writes.**
- `liveLocation/{uid}/latest` — lean marker node: `latitude`, `longitude`,
  `accuracyMeters`, `headingDegrees`, `speedMetersPerSecond`, `recordedAt`,
  `sessionId`, `expiresAt`, `displayName`. Written only via
  `live.updatePosition`, which enforces a 60-second staleness threshold.
- **Read access is already gated** to `activeMember` (custom claim) and
  non-suspended users.
- **Sessions are short and explicit.** Durations cap at 4h; "Hide me now" kills
  the session and removes `latest` immediately, for *any* signed-in user. A
  5-minute scheduled sweep (`live/scheduled.ts`) expires overdue sessions and
  drops `latest` markers older than 15 minutes.
- **Key implication:** live location today is a **deliberately foreground,
  time-boxed, user-initiated share** — not a continuous background feed. A
  member is only "on the map" when they chose to be, for at most a few hours.
  Member-proximity notifications can reuse this feed *for free*, but they
  inherit its limitation: you can only be told about a *nearby member* if that
  member is currently sharing.

### 2.3 Geo distance / geofencing — `functions/src/crownHunt/crown-hunt-geo.ts`

Small, pure, tested, no DB dependencies. Ported "verbatim, must preserve all
validation logic". Directly reusable:

- `haversineDistanceMeters(lat1, lon1, lat2, lon2)` — great-circle distance.
- `isValidCoordinate(lat, lon)` — WGS-84 validation.
- `isWithinGeofence(distanceMeters, radiusMeters, accuracyMeters)` — geofence
  test that **conservatively accounts for reported GPS accuracy**.
- `isPositionFresh(recordedAt, now, maxAgeSeconds)` — staleness guard.
- `isPlausibleJump(...)` / `isSpeedSafe(...)` — anti-teleport / speed sanity.

`crown-hunt-risk.ts` adds privacy-conscious risk scoring (accuracy, velocity,
etc.) with the design rule "no permanent raw location history is created here".

> **Note on placement:** these helpers currently live under `crownHunt/`. A
> nearby feature should **not** import across domains ad hoc. The clean move is
> to promote the pure geo helpers to `functions/src/shared/geo.ts` (or a
> `packages/shared` geo module) and have both Kronjakt and nearby consume them.
> This is a refactor, not new logic.

### 2.4 Candidate sources — what "relevant" things have coordinates

| Domain | Collection | Geo fields | Status filter for candidacy |
|---|---|---|---|
| Events | `events/{eventId}` | `latitude`, `longitude` (exact loc is member-only) | `status == 'published'`, future `startsAt` |
| Group drives | `groupDrives` roster + `liveLocation/{uid}/latest` markers | via live markers | active roster, `on_the_way`/`arrived` |
| Members | `liveLocation/{uid}/latest` | `latitude`, `longitude` | active session, fresh marker |
| Partner offers | `companies/{id}` + offers | `latitude`, `longitude`, `address` | company `active` + offer `active` |
| Kronjakt | `crownHuntPoints/{pointId}` | `latitude`, `longitude`, `geofenceRadiusMeters` (20–150) | `status == 'active'`, within `repeatRule` window |

No geohash/GeoFire library exists anywhere in the repo today (confirmed by
search). All geo work is brute-force Haversine over small sets — which is fine
at current scale (see §5).

### 2.5 Scheduled-function pattern

`onSchedule` in `europe-west1`, `Europe/Stockholm`, scale-to-zero, with a
separately-exported pure runner for emulator tests (see `live/scheduled.ts`,
`notifications/scheduled.ts`). Any scan-based approach here would follow the
same shape and the same cost rules (§5).

---

## 3. Proposed design

### 3.1 Two very different notification classes

It is a design mistake to treat all "nearby" alerts the same. They split cleanly:

| Class | Example | Needs *my* live location? | Needs *background* location? | Time sensitivity |
|---|---|---|---|---|
| **A. Nearby static/semi-static** | published event nearby; partner offer nearby; active Kronjakt point nearby | Only my *current* position when computed | No — can be foreground-only | Low–medium (hours/days) |
| **B. Nearby live/ephemeral** | a member / group drive is near me *right now* | Yes | Yes, to be useful when app is closed | High (minutes) |

Class A is where the value/effort ratio lives. Class B is where the privacy and
battery cost lives. The recommendation (§9) is to ship A and gate B behind a
later, density-justified decision.

### 3.2 How "nearby" is computed

**Rejected: continuous server-side geofencing / geohash index.** At 20–30 users
the machinery (geohash cells, index maintenance on every position write, fan-out
subscriptions) costs more in complexity and reads than it saves. Revisit only if
DAU crosses ~1–2k.

**Chosen: on-demand + lightweight event-driven, brute-force Haversine.**

- **Class A (foreground "nearby now"):** when the user opens the app / map with
  location permission already granted, the client sends its current coordinate
  to a callable (`nearby.getNearby`, mirroring `live.updatePosition`'s coord
  schema). The function loads the small candidate sets (published future events,
  active offers, active Kronjakt points — all already bounded, all served with
  `limit()`), filters by `haversineDistanceMeters <= radius`, applies per-user
  dedup/cooldown, and writes at most a handful of notifications via
  `writeInAppNotification`. **No background location. No stored trail.**
- **Class B (member/drive proximity), if ever built:** piggyback on the
  *existing* live-location write path. When `live.updatePosition` writes a
  `latest` node, an event-driven trigger (or the existing 5-minute sweep,
  extended) compares the writer against *other currently-sharing* members who
  have opted into member-proximity, within radius, and mutually unblocked.
  Because it only ever considers members **already actively sharing**, it adds
  **no new background-location requirement for the recipient beyond what live
  location already asks** — a crucial privacy property. The cost is it only
  fires when both parties are live-sharing, which at current density is rare.

### 3.3 Trigger → candidate → relevance/dedupe → send (end to end)

```
              CLASS A (foreground, on-demand)                 CLASS B (live, event-driven)
              ------------------------------                  ----------------------------
 Trigger      user opens map w/ location granted              live.updatePosition writes latest
                 → nearby.getNearby(coord)                       → onValueWritten trigger
                          |                                            |
 Candidate    load bounded candidate sets                     load other ACTIVE sharers
 filter       (events published+future, offers active,        opted into member-proximity,
              Kronjakt active) — each limit()-capped          within radius, mutually unblocked
                          |                                            |
 Geo filter   haversineDistanceMeters <= perTypeRadius        haversineDistanceMeters <= radius
              (reuse isValidCoordinate / freshness)           (reuse freshness; ignore stale markers)
                          |                                            |
 Per-user      check preferences (category enabled?),         same
 relevance     dedupe key + cooldown window,
 & dedupe      cap N-per-scan
                          |                                            |
 Send         writeInAppNotification(uid, input,              writeInAppNotification(...)
              deterministicNotificationId)  ── idempotent ──> notifications/{uid}/items
                          |
 Push         (only if FCM sendPushNotification has shipped)  same
```

### 3.4 Dedup / cooldown — the anti-fatigue core

Fatigue is the #1 failure mode of proximity notifications. Layers:

1. **Deterministic notification IDs → idempotency.** Build the ID as a hash of
   `{ recipientUid, category, relatedEntityId, coarseTimeBucket }`. The existing
   writer's create-if-absent transaction then makes re-firing within the same
   bucket a **no-op** (`skippedReason: 'duplicate'`). This is the primary dedup
   and it reuses code that already ships.
2. **Cooldown ledger.** A per-user doc
   `userPrivate/{uid}/nearbyState/{entityKey}` (or a small map) records
   `lastNotifiedAt` per entity. A candidate is suppressed if notified within its
   cooldown (proposed: **event 24h, partner offer 72h, Kronjakt point 24h,
   member-proximity 60 min**). Cheap: one small read per scan, TTL-swept.
3. **Global per-user rate cap.** Max N nearby notifications per rolling window
   (proposed: **3 / 6h, 6 / day**). Hard ceiling regardless of how many
   candidates match.
4. **Quiet hours.** Suppress non-urgent nearby pushes 22:00–07:00
   Europe/Stockholm (reuse the timezone already standard on scheduled jobs).
5. **Geofence hysteresis.** For Class B, require the member to have *entered*
   the radius (was outside on the previous marker) rather than merely *being*
   inside — prevents repeated pings from someone parked nearby.

### 3.5 Opt-in / preferences model (extend, don't reinvent)

Reuse `userPrivate/{uid}.notificationPreferences` exactly as-is:

- Add categories `nearby_event` and `partner_offer` to the active allowlist in
  `notifications-core.ts` (they are already reserved). Add a
  `nearby_member` category for Class B if/when built.
- These are **non-essential** → fully opt-out-able per the existing
  `decideInAppDelivery` path, with independent `inApp` / `push` toggles.
- **Master switch = default OFF.** A top-level opt-in
  (`notificationPreferences.nearby.enabled`, default `false`) gates the whole
  feature. Nearby is off until the member explicitly turns it on — this is the
  single most important product default for both trust and cost.
- Per-type radius and category toggles exposed in settings (e.g. "Nearby events
  within 10 km", "Partner offers within 5 km").
- Class B additionally requires the member to be **currently live-sharing** (it
  is literally computed from their own `latest` node), so it inherits live
  location's existing explicit, time-boxed consent — no new standing consent.

### 3.6 Sending

Everything terminates at `writeInAppNotification` with the appropriate
`actionType` (new `open_map` / `open_offer` action types alongside the existing
`open_event`). If/when `sendPushNotification` ships, the same writer call fans
out to push for users whose `notificationPreferences[category].push !== false`.
No new sending path is introduced.

---

## 4. Privacy (first-class)

Continuous/background location is the most sensitive data this app could touch.
Design commitments:

- **Opt-in, default off, revocable.** No nearby processing for any user who has
  not flipped the master switch. Revoking is immediate (mirrors "Hide me now").
- **No new background-location requirement for Class A.** Class A uses only the
  coordinate the user's device already produced while the app is in the
  foreground. It never requests `ACCESS_BACKGROUND_LOCATION`.
- **Class B reuses existing consent only.** Because Class B is computed from a
  member's *own* live-location `latest` node, a recipient is only ever
  considered while they are already, explicitly, live-sharing. The feature must
  **not** silently extend or auto-start a live session to "keep them findable".
- **Android background-location reality (if Class B is ever pushed to
  always-on).** Google Play's Location Permissions policy treats
  `ACCESS_BACKGROUND_LOCATION` as a high-risk permission requiring: a
  documented core use case, a separate in-context runtime prompt (cannot be
  bundled with foreground grant on Android 11+), a prominent disclosure, and a
  **policy declaration + possibly a review video** at store submission. This is
  a material store-review risk and a recurring compliance burden. **This
  proposal explicitly recommends *not* going always-on** and keeping location
  strictly foreground/session-bound (§9). If always-on is ever demanded, it is
  its own project with legal review.
- **Precision minimisation.** Follow the pattern already in the data model,
  which stores billboard locations as "approximate — rounded, not exact GPS".
  Nearby matching should compute on-server against the precise candidate
  coordinate but **snap the *recipient's* position to a coarse grid** (e.g.
  ~100–500 m) before any storage, and store no more precision than the cooldown
  logic needs.
- **No location trail / retention.** Reuse the Kronjakt design rule: "no
  permanent raw location history is created here." The cooldown ledger stores
  entity keys + timestamps, **not coordinates**. Anything transient is
  TTL-swept like `liveLocation` and the notification inbox already are.
- **Partners never get location data.** Product decision (`product-decisions.md`)
  is explicit: companies never receive personal data, live location, routes,
  driving history, or individual tracking. "Nearby partner offer" means *we*
  tell the member a partner is nearby; the partner learns **nothing** about who
  is near them or that a notification fired.
- **Blocking.** Class B must respect blocking. Note the live-location module
  documents that a blocks collection does **not exist yet** — so member
  proximity is **blocked on the blocking domain landing** and must fail safe
  (no notification) until it does.
- **GDPR.** Location is personal data. Nearby state must be covered by the
  existing account-deletion/export flows; the master switch is the lawful-basis
  (consent) anchor.

---

## 5. Cost & performance

Target scale is explicitly **20–30 active users**, hard budget **SEK 500/month**
(`firebase-cost-controls.md`), all functions scale-to-zero (`minInstances: 0`),
`maxInstances` capped, `limit()` on every query.

- **Class A is essentially free at scale.** One callable invocation per app-open
  (only when location already granted), reading a few small bounded candidate
  sets. At 30 users this is a rounding error against the existing live-location
  and notification traffic. Cache the candidate sets within an invocation per
  the existing cost rule.
- **Reject scheduled full scans for Class A.** A cron that scans all users ×
  all candidates every N minutes burns reads whether or not anyone is nearby —
  exactly the anti-pattern the cost doc warns about. On-demand is strictly
  cheaper here.
- **Class B is event-driven off an existing write.** It only runs when a member
  is *already* paying the cost of live-sharing (`live.updatePosition` already
  fires). Extra cost = reading the small set of other active sharers per update.
  At current density, active sharers overlapping in space is **rare**, so the
  amortised cost is near zero — but so is the **value**. This is the crux of the
  defer recommendation.
- **FCM volume.** Bounded hard by the §3.4 rate caps (≤6/user/day). Negligible
  cost; FCM itself is free. Real cost is *attention*, not money.
- **Battery.** Class A adds **zero** background battery cost (foreground only).
  Class B always-on would be the real battery risk (continuous GPS + wake-ups) —
  another reason to keep location session-bound.
- **Scaling assumption / trip-wire.** Brute-force Haversine and no geohash index
  are correct **only** at low candidate counts. Add a geohash/GeoFire index and
  reconsider scheduled fan-out **only** if candidate sets (active points/offers/
  events) exceed ~a few hundred *or* concurrent live-sharers routinely exceed
  ~100. Document this trip-wire so we don't over-build now or under-build later.

---

## 6. Effort estimate & phasing

Estimates are for one experienced engineer, backend + Android, excluding
product/legal review time.

| Phase | Scope | Effort |
|---|---|---|
| **0. Prereqs** | Promote geo helpers to `shared/geo.ts`; confirm FCM `sendPushNotification` landed (else nearby is in-app-only) | 0.5–1 day (blocked on FCM for push) |
| **1. Class A backend** | `nearby.getNearby` callable, candidate loaders, dedup/cooldown ledger, activate `nearby_event` + `partner_offer` categories, preferences master switch | 4–6 days |
| **2. Class A Android** | Settings UI (master switch, per-type radius/toggles), foreground coord capture on map open, inbox rendering + `open_map`/`open_offer` deep links | 4–6 days |
| **3. Hardening** | Quiet hours, rate caps, hysteresis, emulator tests for runners, TTL sweep for cooldown state | 3–4 days |
| **4. Class B (member/drive proximity) — GATED** | Requires: FCM push live, **blocking domain live**, density justification. Event-driven trigger off `updatePosition`, mutual-block checks, hysteresis | 6–10 days + review |
| **5. Always-on background location — NOT RECOMMENDED** | Play policy declaration, prominent disclosure, review video, legal sign-off | Separate project |

**MVP-relevant slice (Phases 0–3):** ~2–3 weeks, mostly Class A.

---

## 7. Risks

- **Density kills value.** At 20–30 users, member-proximity (Class B) will
  almost never fire. Shipping it is effort spent on an empty room. *(High /
  likely.)*
- **Notification fatigue → uninstall.** Over-notifying is worse than not
  notifying. Mitigated by default-off, cooldowns, rate caps, quiet hours — but
  requires real tuning with real users. *(High.)*
- **Background-location store rejection / policy churn.** Only if Class B goes
  always-on. Avoided by the foreground-only recommendation. *(High if pursued.)*
- **FCM not yet shipped.** Without push, "nearby" only surfaces when the user
  already opened the app — undercutting the feature's premise. *(Blocking for
  the push variant.)*
- **Blocking domain absent.** Class B cannot ship safely until blocks exist.
  *(Blocking for Class B.)*
- **Cross-domain coupling.** Nearby reads events, partners, Kronjakt, and live
  location. Without promoting shared geo helpers, this invites duplication and
  drift. *(Medium — mitigated by Phase 0.)*
- **Privacy perception.** Even a well-designed feature can feel creepy
  ("the app knows where I am"). Messaging and defaults matter as much as code.
  *(Medium.)*

---

## 8. Open product questions

1. **Is member proximity (Class B) actually wanted at this scale**, or is the
   real desire nearby *events/offers* (Class A)? The value/liability split
   hinges entirely on this.
2. **In-app-only acceptable for v1**, or is push a hard requirement (→ blocked
   on FCM)?
3. **What radii feel right per type** (events 10 km? offers 5 km? Kronjakt 2 km?)
   and what are the acceptable notifications-per-day ceilings?
4. **Do we ever want always-on background location?** If "no" (recommended),
   we can commit to foreground/session-bound location as a hard product
   constraint and simplify the whole privacy story.
5. **Should Kronjakt "point nearby" nudges be part of this feature or owned by
   the Kronjakt domain?** (It already has geo + risk logic; a nearby nudge may
   belong there, not here.)

---

## 9. Recommendation

**DEFER the ambitious version; SPIKE the narrow one.**

- **Do not** build continuous/background member-proximity now. At 20–30 users it
  has near-zero hit rate, and it carries the entire background-location privacy,
  battery, and Play-policy burden. It is a solution waiting for density we don't
  have.
- **Do** consider a small **Class A spike**: foreground-only "nearby now" for
  **published events and active partner offers** (and optionally active Kronjakt
  points), computed on app/map open, opt-in and default-off, reusing
  `writeInAppNotification`, the existing preferences model, and the existing geo
  helpers. ~2–3 weeks, low risk, mostly wiring, and it activates the already
  reserved `nearby_event` / `partner_offer` categories exactly as the code
  anticipated.
- **Sequence it correctly:** it is only fully valuable **after FCM
  `sendPushNotification` ships** (end-of-MVP task). Until then the in-app-only
  version is a weak preview. Class B additionally waits on the **blocking
  domain**.
- **Net:** treat this as *two* features. Green-light the cheap, safe half as a
  post-MVP spike gated on FCM; keep the expensive, sensitive half parked on the
  ideas board until real usage density and a product decision justify it.

---

## Appendix A — files referenced

- `functions/src/notifications/notifications-core.ts` — categories (incl.
  reserved `nearby_event` / `partner_offer`), preferences, delivery decision,
  retention.
- `functions/src/notifications/deliver.ts` — `writeInAppNotification` (sole
  writer, idempotent).
- `functions/src/notifications/pushTokens.ts` — device registration (hash-only);
  FCM delivery pending.
- `functions/src/notifications/scheduled.ts` — retention sweep pattern.
- `functions/src/live/live-core.ts` — RTDB live-location model, coord schema,
  freshness, session lifecycle.
- `functions/src/live/scheduled.ts` — 5-minute TTL sweep pattern.
- `functions/src/crownHunt/crown-hunt-geo.ts` — reusable pure geo helpers.
- `functions/src/crownHunt/crown-hunt-risk.ts` — privacy-conscious risk scoring
  (no location trail).
- `functions/src/crownHunt/crownhunt-core.ts` — `crownHuntPoints` geo/geofence
  fields.
- `functions/src/events/events-core.ts` — event coordinates + status.
- `functions/src/groupDrive/groupdrive-core.ts` — drive roster; markers via live
  location.
- `functions/src/partners/partners-core.ts` — company/offer coordinates +
  status.
- `docs/firebase-cost-controls.md` — 20–30 user scale, SEK 500 budget,
  scale-to-zero rules.
- `docs/product-decisions.md` — companies never receive location/tracking; live
  location is a core, user-initiated feature.
