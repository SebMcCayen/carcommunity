# Proposal: Roadside SOS Alert

> **Status: PROPOSAL — not approved, not implemented.** This document is a
> feasibility study and design sketch for a backlog idea. It contains **no
> application code**. Nothing here should be built until product sign-off,
> and — given the safety/liability surface — an explicit legal review (see
> [Safety, liability & legal](#3-safety-liability--legal)).

- **Author:** Claude (Opus 4.8), on request
- **Date:** 2026-07-09
- **Scope:** Kungsbacka Car Community (Android + admin web + Firebase Cloud Functions)
- **Type:** Non-MVP backlog idea (future-ideas board)
- **Recommendation (short):** **Defer.** Optionally run a small, tightly-scoped
  design spike *after* the FCM push send path exists — and only once a
  liability position is confirmed. Details in [§7](#7-recommendation).

---

## 1. Summary

Let a stranded or at-risk member raise a **community roadside help signal**
that broadcasts their approximate location to nearby members (and/or their
group), so someone close by can lend a hand — a jump start, a spare tyre, a
tow, a lift, or just company while they wait. Responders can acknowledge, mark
themselves *en route*, and the requester (or the system) can resolve or cancel
the alert.

This is a **community assistance** feature, deliberately **not** an emergency
service. The single most important design constraint is that it must never
read as, or be relied upon as, a substitute for **112** (the Swedish emergency
number). Everything below is shaped by that constraint.

The good news: the codebase already has most of the *mechanical* building
blocks (live location over RTDB, an in-app notification writer with
eligibility rules, Kronjakt distance/geofence helpers, a group-drive
participant-roster pattern, blocking, feature flags). The gaps that matter are
**not** technical plumbing — they are (a) the FCM push **send** path does not
exist yet, and (b) the safety/liability model.

---

## 2. Existing infrastructure we can reuse

All backend lives in `functions/src`, region `europe-west1`, App Check
enforced on callables, Node/TypeScript, Firebase Admin SDK. Target scale is
small (20–30 active users, ≤ SEK 500/month per ADR-001), which strongly
favours simple full-scan approaches over geo-indexes.

### 2.1 Live location (RTDB) — `functions/src/live/`

The closest existing analog to "where is a member right now".

- **Data shape** (`live-core.ts`): `liveLocation/{uid}/session` (backend-owned
  session: id, status `active|stopped|expired`, duration `1h|2h|4h`,
  `startedAt`, `expiresAt`, `stoppedAt`, `stopReason`) and
  `liveLocation/{uid}/latest` (lean marker: lat/lon, accuracy, heading, speed,
  `recordedAt`, `sessionId`, `expiresAt`, denormalized `displayName`). **All
  RTDB writes are backend-only** — rules deny client writes; positions flow
  through callables.
- **Callables** (`session.ts`): `live.startSession`, `live.updatePosition`
  (requires an active, unexpired session; enforces a **60-second** position
  staleness threshold), `live.stopSession`, and `live.hideMeNow` (a privacy
  action that works **even while suspended** — removing your own position must
  always be possible).
- **TTL sweep** (`scheduled.ts`): `live-cleanupExpired` runs every 5 minutes,
  flips overdue sessions to `expired`, and removes `latest` markers whose
  `recordedAt` is older than 15 minutes (client went silent). It does a
  **deliberate full scan** of `liveLocation/` — "at MVP scale it is cheaper
  and simpler than maintaining index nodes."

**Reuse:** the session/marker split, backend-only writes, the "hide me now"
privacy pattern, and the scheduled sweep are all directly reusable idioms for
an SOS lifecycle. An SOS is essentially a live-location session with a very
different *purpose*, a broadcast on creation, and a responder roster.

> Note: live location has a documented but **not-yet-implemented** blocking
> seam ("no blocks collection exists yet" comment predates the blocking
> domain, which now exists — see §2.5). SOS must wire blocking in from day one.

### 2.2 In-app notifications + FCM — `functions/src/notifications/`

- **In-app inbox** (`notifications-core.ts`, `deliver.ts`):
  `notifications/{uid}/items/{notificationId}`, owner-read, **backend-only
  writes** via the single `writeInAppNotification(recipientUid, input,
  notificationId?)` helper. It enforces eligibility centrally: **deleted**
  recipients get nothing; **suspended** recipients get only *essential*
  categories; per-category in-app opt-outs in
  `userPrivate/{uid}.notificationPreferences` are honoured (essential
  categories cannot be disabled). Passing a deterministic `notificationId`
  makes delivery idempotent (create-if-absent transaction).
- **Categories are a closed allowlist** (`NOTIFICATION_CATEGORIES`). The file
  explicitly notes that *future* categories (e.g. `nearby_event`) "must not be
  activated without product and security review." **An SOS category would be a
  new entry requiring exactly that review.**
- **Fan-out** (`adminSend.ts`): `notifications.adminSend` resolves an audience
  (`specific_user`, `admins`, `members`, `event_participants`, …), fans out in
  bounded chunks of 25, is idempotent per `idempotencyKey` (deterministic
  batch id claimed with `create()`), writes an `adminAuditEvents` record before
  fan-out, and rejects audiences larger than `MAX_SYNC_AUDIENCE_SIZE`
  (synchronous only; a background queue is the documented follow-up). This is
  a strong template for a targeted SOS fan-out.
- **Push tokens** (`pushTokens.ts`): `notifications.registerPushToken` /
  `unregisterPushToken`. Only the **SHA-256 hash** of the FCM token is stored
  (`userPrivate/{uid}/pushTokens/{tokenId}`, hash = doc id); the raw token is
  never persisted, logged, or returned.

> **⚠️ Critical gap:** actual FCM **delivery** (`sendPushNotification`) is
> **not implemented**. Both `pushTokens.ts` and `notifications-core.ts` state
> it "ships with the Firebase console/FCM setup at the end of the MVP." Today
> the notification path is **in-app inbox only** — which requires the app to
> be foregrounded/polling to be seen promptly. **An SOS feature is worthless
> without reliable high-priority push to a possibly-backgrounded device.**
> This is the single largest technical dependency (see §6).

### 2.3 Kronjakt geo helpers — `functions/src/crownHunt/crown-hunt-geo.ts`

Pure, dependency-free, already-tested functions we can reuse verbatim for
distance and radius logic:

- `haversineDistanceMeters(lat1, lon1, lat2, lon2)` — server-side great-circle
  distance (client-supplied distance is never trusted).
- `isValidCoordinate` / `isValidLatitude` / `isValidLongitude` — WGS-84 checks.
- `isPositionFresh(recordedAt, nowMs, maxAgeSeconds)` — freshness guard.
- `isWithinGeofence(distance, radius, accuracy)` — radius check that
  conservatively accounts for reported GPS accuracy.
- `isSpeedSafe`, `isPlausibleJump` — anti-spoof / teleport guards.

**Reuse:** "find nearby members" for a radius broadcast = for each candidate
with a fresh position, compute `haversineDistanceMeters` to the SOS origin and
keep those within the radius. At 20–30 users a full scan of live positions is
entirely adequate (mirrors the live-cleanup scan and the Kronjakt "no route
history / no geo-index" stance). No GeoFire / geohash indexing needed at MVP
scale.

### 2.4 Events / group drive — `functions/src/events/`, `functions/src/groupDrive/`

- **Group drive** (`groupdrive-core.ts`, `participants.ts`):
  `events/{eventId}/groupDriveParticipants/{uid}` with statuses
  `joined | on_the_way | arrived | left`, backend-enforced join preconditions,
  idempotent join/rejoin, and a separate idempotent `leave`. This participant
  state machine is an **excellent template for the SOS responder roster**
  (`acknowledged | en_route | arrived | stood_down`).
- **Events** (`events-core.ts`) and **groups/RSVP** give us the "targeted to my
  group" audience option, and the moderation pipeline (`moderateReports.ts`,
  `reportChatMessage.ts`) is the template for **false-alarm / abuse reporting**.

### 2.5 Cross-cutting

- **Access/actor** (`shared/memberActor.ts`, `shared/access.ts`):
  `requireActiveActor` (signed-in, non-suspended, non-deleted),
  `requireMemberActor` (+ `activeMember`). Backend state is the source of
  truth; client claims are never trusted for the decision.
- **Blocking** (`blocking/`): `userBlocks/{blockerUid}/blocked/{blockedUid}`,
  directional, backend-only. SOS fan-out must exclude blocked pairs **in both
  directions**.
- **Feature flags** (`shared/featureFlags.ts`,
  `contracts/features/feature-flags.json`): `readFeatureFlag(key)`, admin-set.
  SOS would add an `sos` flag, default **false** (kill switch mandatory for a
  safety-adjacent feature).

---

## 3. Safety, liability & legal

**This section is the gate for the whole feature.** Treat it as first-class,
not an appendix. The mechanics in §4 are easy; this is the hard part.

### 3.1 Core stance: assistance, not emergency dispatch

The feature must be scoped, worded, and designed as **"ask nearby members for
a hand,"** never as an emergency/rescue service.

- **112 always comes first.** Any life-safety, injury, fire, or crime
  situation → call **112**. This must be the *first* thing in the raise-SOS
  UI, unmissable, and repeated in the responder view.
- **No dispatch, no guarantees, no ETA promises.** The system must never imply
  that help is coming, that anyone is obligated to respond, or how long it
  will take. Copy is "we've asked nearby members" — never "help is on the way."
- **Rename in-product away from "SOS/emergency."** "SOS" and the 🆘 glyph carry
  an emergency connotation that is exactly what we must avoid. Prefer neutral
  community framing: **"Roadside help"** / *"Väghjälp"* (Swedish) / "Request a
  hand." (Keep "SOS" only as the internal codename.)

### 3.2 Disclaimers & consent (must-haves)

- A one-time **acknowledgement** on first use (stored, versioned): *this is not
  an emergency service; for emergencies call 112; responders are volunteer
  community members, not professionals; use at your own risk.*
- The disclaimer is repeated (condensed) on **every** raise action and in the
  responder view.
- Responder-side framing: helping is **entirely voluntary**, at the
  responder's own discretion and risk; **do not** put yourself in danger; if
  the scene looks unsafe, call 112.

### 3.3 Duty of care & liability (needs legal review)

The central liability question: **by facilitating a "help" signal, does the
club/operator assume a duty of care it cannot meet?** Realistic failure modes:

- A member relies on the app instead of calling 112, and help never comes.
- A responder is injured (or injures someone) while helping.
- No one responds; the requester feels abandoned.
- A bad actor uses a "roadside help" alert to **lure** a member to a location
  (this is the most serious misuse vector — see §3.5).

Mitigations to bring to legal review:

- Explicit **Terms of Use** for the feature and a per-use disclaimer with
  recorded consent (§3.2).
- Position as a **passive matchmaking/notification** tool — the operator
  connects members but neither vets, dispatches, nor supervises help. Analogy:
  a community bulletin board, not a service provider.
- Consider whether the club's **insurance / association bylaws** (this is a
  Swedish *ideell förening*-style community) cover member-to-member assistance
  activity, and whether a disclaimer meaningfully limits liability under
  Swedish law (*skadeståndslagen*). **This is a lawyer question, not an
  engineering one** — do not ship without an answer.

### 3.4 Minors

- Members may be under 18. A minor stranded roadside is a heightened safety and
  legal concern, and directing an unknown adult to a minor's location is a
  serious risk. Options to weigh with legal/product:
  - Gate the feature to 18+ (requires reliable age data we may not have).
  - For minors, bias the audience toward **group/known contacts only** rather
    than radius-broadcast to strangers.
  - Stronger 112/guardian prompting for minors.
- No decision here — flagged as an **open question** requiring legal input.

### 3.5 Misuse controls (abuse, false alarms, luring)

- **Luring / safety of the requester:** the biggest risk. Mitigations:
  broadcast **coarsened** location initially (see §5), reveal precise location
  only to a responder the requester **explicitly accepts**; show responder
  identity (displayName, and ideally tenure/helpful-member badge) to the
  requester before acceptance; log all responder identities server-side for
  accountability.
- **Rate limiting:** hard cap on active SOS per user (1 at a time) and on SOS
  per rolling window (e.g. ≤ N/day). Reuse the idempotency/claim patterns and
  add a cooldown like Kronjakt's.
- **False-alarm handling:** easy one-tap "false alarm / resolved" and
  auto-expiry (see §4). Repeated false alarms feed a strike/flag counter.
- **Reporting & moderation:** reuse the `moderateReports` / report pipeline so
  responders can report a bogus or abusive alert; admins can review and
  **suspend** a member's SOS access (feature-level ban) without full account
  suspension.
- **Membership gate:** require `requireMemberActor` (active, paying, non-
  suspended member) to raise — reduces drive-by abuse and ties actions to a
  known identity.
- **Kill switch:** the `sos` feature flag (default off) lets admins disable the
  feature instantly if abused.

### 3.6 Explicit non-goals (keep scope to community assistance)

- No integration with 112 / SOS Alarm / police / ambulance / any official
  dispatch.
- No "panic button" / personal-safety / duress positioning (that is a
  regulated safety-device space with far higher legal stakes).
- No guaranteed response, SLA, or "someone is coming" language.
- No automated escalation to authorities.

---

## 4. Proposed design

### 4.1 Lifecycle

```
                 raise (requester)
                       │
                       ▼
   ┌───────────────► ACTIVE ──────────────────────┐
   │                   │  broadcast to audience    │
   │                   │  (coarse location)        │
   │      responder ack│                           │ resolve / cancel
   │                   ▼                           │ (requester)
   │              RESPONDING  ── responder ────────┤
   │            (≥1 en_route)   en_route/arrived   │  auto-expire
   │                   │                           │ (scheduled sweep)
   └───────────────────┴──────────────► RESOLVED / CANCELLED / EXPIRED
```

- **Raise:** member taps "Request roadside help," picks a short reason
  (from a fixed enum — battery, tyre, fuel, tow/stuck, other) and optional free
  text; app captures current position (validated server-side). Backend creates
  the SOS, sets status `active`, records origin, and triggers the broadcast.
  **112 reminder shown first.**
- **Broadcast:** backend resolves the audience (§4.4), excludes blocked pairs,
  and delivers a **high-priority push + in-app notification** to each. Delivery
  reliability is paramount (§4.6).
- **Responder acknowledges / en route:** responders opt in via a responder
  roster (`acknowledged → en_route → arrived`), mirroring group-drive statuses.
  Acknowledgement is the trigger to reveal precise location to *that* responder.
- **Resolve / cancel:** requester can resolve ("got help / sorted") or cancel
  ("false alarm") at any time — one tap, always available. Resolving stops all
  location sharing immediately (like `hideMeNow`).
- **Auto-expire:** a scheduled sweep (reusing the live-cleanup cadence) expires
  any SOS older than a max lifetime (e.g. 60 min) or whose requester has gone
  silent, and clears its location. Prevents "zombie" alerts.

### 4.2 Data model

Firestore for the durable record + roster; RTDB for the live requester
position during an active SOS (reusing the live-location machinery).

- `sosAlerts/{sosId}` (backend-only writes):
  `requesterUid`, `displayName` (denormalized), `status`
  (`active|responding|resolved|cancelled|expired`), `reason` (enum),
  `note` (bounded plain text), `audienceType` (`radius|group|responders`),
  `radiusMeters` (if radius), `groupId` (if group), `coarseLocation`
  {lat, lon} (reduced precision — see §5), `createdAt`, `updatedAt`,
  `expiresAt`, `resolvedAt`, `resolvedReason`, `responderCount`.
- `sosAlerts/{sosId}/responders/{uid}` (backend-only writes): mirrors
  group-drive participants — `displayName`, `status`
  (`acknowledged|en_route|arrived|stood_down`), `acknowledgedAt`, `updatedAt`.
  Precise requester location is **not** stored here; it is exposed via RTDB
  only to accepted responders.
- **RTDB** `sosLocation/{sosId}/latest` — precise live position of the
  **requester** while active, backend-only writes, TTL-swept. Read access
  limited to accepted responders (enforced via a backend read-callable or a
  narrowly-scoped rule keyed off responder membership; precise rule design is
  an open item). Reusing the existing `liveLocation` session for the requester
  is an option but the *audience* differs, so a dedicated node is cleaner.
- `userPrivate/{uid}` — add per-user SOS settings: `sosResponderOptIn`
  (default? — open question), `sosDisclaimerAckedVersion`.
- New notification category `sos_alert` (essential-like: see §4.6) added to the
  closed allowlist **through the required product+security review**.

### 4.3 Backend callables & triggers (sketch — names illustrative)

- `sos.raise` (member, `sos` flag on): validates coordinate & rate limits,
  creates `sosAlerts/{sosId}`, kicks off broadcast. Idempotent per client-
  supplied key (Kronjakt/adminSend pattern).
- `sos.updatePosition` (requester): refresh precise position, freshness-guarded
  (reuse `guardPositionFreshness` / `isPositionFresh`).
- `sos.respond` (member): acknowledge → unlock precise location to caller;
  `sos.updateResponderStatus` (`en_route|arrived`); `sos.standDown`.
- `sos.resolve` / `sos.cancel` (requester; cancel = false-alarm): stop sharing,
  set terminal status, clear RTDB node.
- `sos.reportAlert` (responder): abuse/false-alarm report → moderation pipeline.
- `sos.adminStandDown` (admin): kill an alert + optional feature-ban; audited
  via `adminAuditEvents` (adminSend pattern).
- **Scheduled** `sos-cleanupExpired`: expire stale alerts, clear locations
  (reuse `live/scheduled.ts` shape and 5-min cadence).
- **Delivery:** fan-out via the `writeInAppNotification` helper **plus** the
  (to-be-built) FCM `sendPushNotification` path, chunked like `adminSend`.

### 4.4 Who receives it (audience)

Three modes, chosen by the requester (with a safe default and minor-aware
constraints from §3.4):

1. **Group / known contacts** — members of the requester's group(s). Safest
   (known people); recommended **default**, especially for minors.
2. **Radius** — active members with a fresh live position within N km of the
   origin (Haversine full-scan over live positions). Reaches strangers →
   higher reach but higher luring risk; coarse location only until acceptance.
3. **Opt-in responders** — members who explicitly enabled "I'm willing to help
   with roadside requests." Reduces spam to the uninterested; combine with
   radius.

In all modes: exclude blocked pairs (both directions), suspended/deleted
recipients (via `writeInAppNotification` eligibility), and the requester
themselves.

### 4.5 Client UX

- **Raise (requester):** a deliberate, **two-step** action (not a single hair-
  trigger tap, to cut false alarms) with the **112-first** banner, reason
  picker, audience choice (defaulted), and the per-use disclaimer. After
  raising: a clear status screen showing responder count/status and a large,
  always-available **Resolve / Cancel** button.
- **Responder:** notification → screen showing requester displayName, reason,
  coarse distance/area, and the **voluntary-help + safety** disclaimer.
  "I can help" reveals precise location and adds them to the roster; status
  chips `en route` / `arrived`; "Report" and "Stand down" available.
- **Emphasis:** delivery reliability (§4.6) and an unmissable, always-reachable
  resolve/cancel are the two UX properties that matter most.

### 4.6 Reliability of delivery

- **High-priority FCM** (`android` priority `high`, appropriate channel) so a
  backgrounded/dozing device wakes. **Depends on the unbuilt send path (§2.2).**
- **Belt-and-braces:** push + in-app inbox item, both idempotent per `sosId`.
- **Category handling:** an SOS notification should behave like an *essential*
  category (bypass generic opt-out) **but** must still respect blocking and a
  dedicated `sosResponderOptIn` — i.e. it ignores the "mute event reminders"
  style prefs, not the "I don't want roadside requests" pref. Needs security
  review per the notifications allowlist rule.
- **Requester confirmation:** show the requester how many recipients the alert
  reached (honest reach signal, never a promise of response).
- **No silent failures:** if zero recipients (e.g. no one nearby), tell the
  requester immediately and re-surface the **112** prompt.

---

## 5. Privacy

- **Precise location only while active, only to accepted responders.** During
  an active SOS, precise position lives in RTDB (`sosLocation/{sosId}`) and is
  readable **only** by responders the requester has accepted — not the whole
  audience. The initial broadcast carries a **coarsened** location (e.g.
  snapped to ~250–500 m, or an area/road name) so the wide audience sees
  "someone needs help near X," not an exact pin. This directly mitigates the
  luring risk (§3.5).
- **Immediate stop on resolve/cancel:** terminal status clears the RTDB
  location node at once (the `hideMeNow` pattern) — no lingering pin.
- **Retention after resolve:** keep the *record* (`sosAlerts/{sosId}` +
  responder roster, for accountability/moderation/abuse investigation) but
  **purge precise coordinates** promptly; retain at most coarse origin. Align
  the record-retention window with the notifications scheme (unread 30d / read
  7d) or a dedicated short window — an open question for the privacy review.
  No coordinates in logs (Kronjakt rule: "no coordinates are logged").
- **Who can see what:** wide audience → coarse area + reason + displayName;
  accepted responders → precise live location; requester → responder
  identities (for informed acceptance); admins → full record for moderation
  (audited). Everything backend-gated; no client writes to SOS collections.
- **GDPR:** location + a "distress" context is arguably sensitive-adjacent
  personal data. Data-minimise (coarse by default, precise only when
  necessary), define retention explicitly, and cover it in the privacy policy.
  Flag for the same review as §3.

---

## 6. Effort estimate & phasing

Rough order-of-magnitude for one engineer familiar with the codebase.
**Prerequisite (not counted below): the FCM `sendPushNotification` send path
must exist first** — this is scheduled for end-of-MVP and is a hard blocker.

- **Phase 0 — Legal & product gate (blocking, not eng):** confirm liability
  stance, minors policy, insurance/bylaws, disclaimer wording, feature naming.
  *No code until this clears.*
- **Phase 1 — Design spike (~2–3 days):** finalise data model, RTDB read-rule
  design for accepted-responder-only precise location, notification-category
  security review, coarsening approach. Deliverable: an ADR + rules sketch.
- **Phase 2 — Backend core (~1–1.5 weeks):** `sosAlerts` model, `sos.raise`,
  responder roster callables, resolve/cancel, scheduled expiry, blocking +
  eligibility, rate limits, audit. Reuses geo helpers, live-session idioms,
  group-drive roster pattern, adminSend fan-out.
- **Phase 3 — Delivery (~2–4 days, gated on FCM):** high-priority push wiring,
  coarse-vs-precise location exposure, reach reporting.
- **Phase 4 — Android UX (~1–1.5 weeks):** raise flow (two-step + disclaimers +
  112-first), status screen, responder view, settings/opt-in.
- **Phase 5 — Admin & moderation (~2–4 days):** admin stand-down, feature-ban,
  reporting review in admin web.

**Total: roughly 3–4 weeks of eng** *after* the FCM prerequisite and the legal
gate — but the calendar cost is dominated by the non-engineering gate.

---

## 7. Risks

| # | Risk | Severity | Notes |
|---|------|----------|-------|
| R1 | **Liability** — perceived as emergency service; someone relies on it instead of 112 and is harmed | **Critical** | Gating risk. Requires legal sign-off, strong disclaimers, "assistance not dispatch" framing. |
| R2 | **Luring / physical safety** — bad actor uses an alert to draw a member (esp. a minor) to a location | **Critical** | Coarse broadcast + precise-only-on-acceptance + identity visibility + membership gate + moderation. |
| R3 | **No push send path yet** — feature is useless without reliable high-priority push | **High** | Hard technical prerequisite; not yet built. |
| R4 | **No one responds** at 20–30-user scale, low density → alerts go unanswered | **High** | Product value risk. Manage expectations; 112-first framing; group mode as fallback. |
| R5 | **False alarms / abuse** eroding trust and desensitising responders | **Medium** | Two-step raise, rate limits, cooldown, reporting, feature-ban, kill switch. |
| R6 | **Minors** — heightened legal/safety exposure | **High** | Needs explicit policy; possibly group-only for minors or 18+ gate. |
| R7 | **Privacy** — precise-location leak or over-retention | **Medium** | Coarsen by default, backend-gated reads, purge coords on resolve, no coords in logs. |
| R8 | **Cost/complexity** vs. a 20–30-user MVP on a SEK 500/mo budget | **Medium** | Feature is safety-adjacent complexity for a small community; weigh against payoff. |

---

## 8. Open product questions (for the human)

1. **Liability appetite (the big one):** is the club/operator willing to run a
   member-to-member "help" signal at all, given Swedish liability and the
   club's insurance/bylaws? A lawyer must weigh in **before** any build. What
   is the acceptable framing — pure "community bulletin board," or something
   more active?
2. **Minors:** what is the policy for under-18 members — 18+ gate, group-only
   audience, or something else? (Depends on whether we even hold reliable age
   data.)
3. **Default audience & responder opt-in:** should the safe default be
   **group-only** (known people) with radius as an explicit opt-in, and should
   "willing to respond to roadside requests" be **opt-in** (privacy-forward) or
   opt-out (reach-forward)?
4. (Secondary) Is this worth building at 20–30 users, where response density is
   low — or is it better parked until the community is larger?

---

## 9. Recommendation

**Defer** — do not build now. Rationale, weighting the liability angle
explicitly:

- The **technical** feasibility is good: distance/geofence helpers, the
  live-location session/marker/sweep idioms, the group-drive roster pattern,
  the blocking domain, the eligibility-aware notification writer, and feature
  flags give us most of the mechanics. This is **not** where the difficulty
  lies.
- The **blocking issues are not code**: (a) the FCM push **send** path does not
  yet exist and is a hard prerequisite; (b) far more importantly, this is a
  **safety-adjacent feature with real liability and physical-safety
  (luring/minors) exposure** that must not be adopted without an explicit legal
  position and a firm "assistance, not emergency dispatch — call 112 first"
  product stance.
- At current scale (20–30 users), response density is low enough that the
  feature may under-deliver on its core promise, which *itself* increases the
  liability risk (someone relies on it and no one comes).

**Concretely:**

1. **Keep on the ideas board.** Do not schedule build.
2. If there is genuine product pull, the **only** first step is a **Phase 0
   legal/product gate** (§6) — confirm the liability stance, minors policy, and
   feature framing. Everything else is downstream of that answer.
3. Revisit the technical spike **only after** (a) the legal gate clears and (b)
   the FCM send path is delivered. Even then, start with the safest possible
   scope: **group-only audience, coarse location, precise-on-acceptance, hard
   112-first framing, kill switch on by default.**

If forced to pick one word: **defer**, with a **legal gate** as the sole
sanctioned next action.
