# Proposal: "Sieges" — a location-based map minigame

> **Status: PROPOSAL / DESIGN EXPLORATION — NOT APPROVED, NOT IMPLEMENTED.**
> This is a doc-only feasibility study for an early-stage backlog idea. No
> application code, Cloud Functions, security rules, or `firebase.json` changes
> accompany it. Nothing here is a committed spec. The goal is to lay out
> **options, trade-offs, and a phased MVP-of-the-feature** so the product owner
> can decide whether to spike, build later, or defer.

- **Author:** Claude (Opus 4.8), on request
- **Date:** 2026-07-09
- **Feature flag (proposed):** `sieges` (default `false` until launched)
- **Depends heavily on product direction** — see "Recommendation".

---

## 1. Summary

"Sieges" is a gamified, competitive layer over real-world locations: players
or teams contest and hold **territories** ("nodes") on a map. It is the
competitive, multiplayer cousin of the existing single-player **Kronjakt**
(Crown Hunt) point-collection game.

The good news for feasibility: the carcommunity backend already contains almost
every primitive Sieges needs. Kronjakt is a working, production-grade,
location-based claim game with server-authoritative geofencing, GPS anti-cheat
risk scoring, admin-managed points, and a full audit trail. The **Kronpoäng
(KP) ledger** already handles atomic, idempotent, replay-safe reward payouts.
The **live-location** domain already streams positions through RTDB with
staleness enforcement. The **badges** catalog already models achievements.

The hard part is **not** the plumbing — it is **product and safety design**.
Territory control is inherently competitive and repeat-visit-driven, which
pushes against two things the codebase is deliberately built to avoid:
encouraging unsafe driving, and creating "hotspots" that concentrate members at
contested physical locations. Whether Sieges is desirable at all is a product
call, not an engineering one.

**Recommendation (up front):** **Defer, with an optional small design spike.**
Build the recommended variant *after* MVP and *only* if the community actively
wants competitive play. See §9.

---

## 2. Existing infrastructure we would reuse

### 2.1 Kronjakt (Crown Hunt) — the closest analogue

Kronjakt lives at `functions/src/crownHunt/` and is the single most relevant
prior art. It is a **single-player, admin-curated, geofenced point-collection
game**: admins place reward points on the map; members physically travel to a
point, stop safely, and "claim" it for Kronpoäng.

**Data model** (Firestore):

| Collection | Purpose | Readability |
| --- | --- | --- |
| `crownHuntPoints/{pointId}` | Admin-managed reward points (title, lat/lng, geofence radius 20–150 m, reward 1–1000 KP, repeat rule `once`/`daily`/`weekly`, status `draft`/`active`/`paused`/`ended`, availability window). | Members read active points directly. |
| `crownHuntClaims/{claimId}` | Every claim **attempt** is recorded. Doc ID = SHA-256 of `(userId, idempotencyKey)`, so a duplicate submission is a replay, not a double-award. | Owner-readable history **without** risk data. |
| `crownHuntClaimRisk/{claimId}` | Risk score + reason labels, in a **separate backend-only collection** because Firestore rules cannot redact per-field, and thresholds must never reach clients. | Backend/admin only. |

**Claim flow** (`crownHunt/submitClaim.ts`, an `onCall` with App Check
enforced). Eligibility failures return *result codes with Swedish messages*,
not errors — only malformed input / unauthenticated calls throw. Steps, in
order:

1. Feature flag check (`crownHunt`).
2. Account status + entitlement (`canAccessMemberFeatures`).
3. Idempotency replay guard (returns the stored result for a reused key).
4. Load point; must be `active` and inside its availability window.
5. Server-side coordinate validation.
6. **Position freshness** — reject positions older than 60 s.
7. **Server-computed distance** (Haversine) + **geofence** check, buffered by
   reported GPS accuracy (client-supplied distance is never trusted).
8. **Speed gate** — claiming is only allowed at ≤ 1.4 m/s (~5 km/h, walking
   pace): *you must be safely stopped*.
9. Repeat-rule window check (once / daily / weekly per point).
10. Daily successful-claim cap (`MAX_DAILY_SUCCESSFUL_CLAIMS = 10`).
11. **Risk evaluation** (see below).
12. **Award**: `creditPoints(...)` ledger credit + claim record + risk record,
    all committed atomically inside one transaction via the ledger's
    `AtomicExtraWrites` hook, replay-safe through a derived idempotency key.

**Geo utilities** (`crownHunt/crown-hunt-geo.ts`) — small, pure, testable, no
DB deps: WGS-84 coordinate validation, `haversineDistanceMeters`,
`isPositionFresh`, `isSpeedSafe` (negative/non-finite speeds are treated as
*unsafe*, closing a spoofing bypass), `isWithinGeofence` (accuracy-buffered,
buffer capped at 0.5× so poor accuracy can't be used to claim from far away),
and `isPlausibleJump` (flags teleportation faster than ~130 m/s but tolerates
normal fast driving).

**Anti-cheat / risk** (`crownHunt/crown-hunt-risk.ts`) — `evaluateClaimRisk`
returns a normalized 0–100 score plus safe category labels. Signals and
weights: stale position (+35), impossible jump (+40), duplicate idempotency key
(+50), poor GPS accuracy (+10), excessive attempts/min (+25), high claim
velocity (+15), repeated geofence-edge probing (+20), platform-integrity
failure (+40, placeholder). Score ≥ 60 → `risk_review`: **no points awarded**,
the attempt is flagged for admins. Jump detection reads the member's latest
trusted position from RTDB (`liveLocation/{uid}/latest`). Thresholds are
constants, never secrets, never sent to clients. Documented TODOs:
Play Integrity / App Attest, admin review workflow, ML anomaly detection.

**Admin surface** (`crownHunt/managePoints.ts`) — `createPoint` /
`updatePoint` / `activatePoint` / `pausePoint`, all requiring an active admin
(`requireAdminActor`). Points are created as drafts; **activation is an
explicit safety gate** requiring `safeLocationConfirmed: true` and an approval
note that lands in `adminAuditEvents`. Ended points can't be reactivated. Every
operation writes an audit record.

### 2.2 Kronpoäng (KP) points ledger

`functions/src/points/`. `pointsLedger/{uid}` holds a denormalized `balance`;
`pointsLedger/{uid}/entries/{entryId}` is an append-only ledger. The backend is
the sole authority — clients never compute balances. Every mutation is one
Firestore transaction (read balance → append entry → update balance), so
concurrent credits/debits serialize (the Firestore equivalent of a PostgreSQL
advisory lock). Entries are immutable; corrections use compensating
`adjustment`/`reversal` entries. Balances never go negative; suspended/deleted
users transact nothing. **Idempotency key = entry doc ID**, so replayed awards
are transactional no-ops. `creditPoints` / `debitPoints` are internal writers
(never generic client endpoints); domains call them directly, and the
`AtomicExtraWrites` hook lets a caller commit its own records in the same
transaction. Transaction `source` is an enum — **Sieges would add a `siege`
source** (currently: `badge`, `event`, `garage`, `admin_adjustment`, `system`,
`crown_hunt`).

### 2.3 Badges

`functions/src/badges/`. Awards live at `users/{uid}/badges/{badgeKey}` with
the catalog definition denormalized on. Doc ID = badge key → awards are
naturally idempotent. **A critical design rule, quoted verbatim from
`badge-core.ts`:** *"Wording stays positive and non-competitive; no
speed/distance/racing badges; nothing may encourage unsafe driving."* Any
Sieges badges must respect this — see §5.

### 2.4 Live location (RTDB)

`functions/src/live/`. `liveLocation/{uid}/session` (backend-owned) and
`liveLocation/{uid}/latest` (the marker read by entitled members). Positions
flow through `live.updatePosition` with a 60 s staleness threshold; a
5-minute scheduled sweep (`live.cleanupExpired`, an `onSchedule`) expires
sessions and removes silent-stale markers. This is the pattern for any
**real-time contested-node state** and for **scheduled scoring/decay** jobs.

### 2.5 Group driving

`functions/src/groupDrive/` — `events/{eventId}/groupDriveParticipants/{uid}`,
join/leave/status callables. Relevant only as prior art for **roster / team
membership** modeling if Sieges needs teams tied to events.

---

## 3. Framing: extend Kronjakt, or build a sibling system?

This is the central architectural decision.

### Option A — Extend Kronjakt in place

Add territory/ownership fields and a contest flow onto `crownHuntPoints` /
`crownHuntClaims`.

- **Pros:** Maximum code reuse; one map surface; one admin curation tool; geo +
  risk utilities used as-is with zero duplication.
- **Cons:** Kronjakt's model is fundamentally **single-player and stateless
  between claims** (a claim is a fire-and-forget award). Territory control is
  **stateful and adversarial** (who holds it *now*, contest windows, decay).
  Bolting ownership onto a claim-attempt ledger muddies a clean, well-tested,
  safety-audited domain and risks regressions in a live feature. The Swedish
  result-code vocabulary, repeat rules, and daily caps don't map cleanly onto
  "capture the node".

### Option B — Sibling domain that *reuses the libraries* (recommended)

New `functions/src/sieges/` domain with its own collections, but importing the
**pure** modules from Kronjakt (`crown-hunt-geo.ts`, `crown-hunt-risk.ts`) and
calling the shared ledger (`creditPoints`).

- **Pros:** Clean separation; Kronjakt stays untouched and stable; Sieges gets
  its own data model, its own feature flag, its own admin surface, and can be
  paused/removed wholesale. Reuses the *hard, safety-critical* code (geo math,
  risk scoring, atomic ledger) without inheriting the *ill-fitting* claim
  semantics.
- **Cons:** Some duplication of scaffolding (callable option blocks, admin
  actor guards, audit-event wiring). A second map-game surface to explain to
  users.
- **Mitigation:** Before building, extract the pure geo/risk helpers into
  `functions/src/shared/geo/` (or `packages/shared`) so both domains import one
  copy. This is a small, low-risk refactor and the right long-term home.

**Recommendation: Option B.** Reuse the safety-critical *libraries*; do not
overload the Kronjakt *domain model*.

---

## 4. Game-design options

Three concrete variants, from lowest to highest complexity. All tie into KP and
(optionally) badges, and all inherit Kronjakt's "must be safely stopped to
interact" gate.

### Variant 1 — Timed territory capture ("King of the Node")

A small set of admin-placed **nodes** (reuse geofenced points). During a
scheduled **event window** (e.g. a Saturday meet, 14:00–18:00), a node is
"held" by whoever most recently performed a valid capture there. Holding a node
when the window closes awards KP to the current holder (and their team).

- **Core loop:** travel to node → stop safely → capture → node shows your
  name/team → someone else captures it later → whoever holds at window close
  scores.
- **Scoring:** flat KP per node held at close; optional bonus for cumulative
  hold-time (tracked server-side).
- **Teams/factions:** optional; simplest version is individuals. Teams can be
  ad-hoc (pick a color on join) or tied to an event roster (§2.5).
- **Complexity:** LOW–MEDIUM. Node state is "current holder + since"; scoring is
  a scheduled job at window close. Very close to Kronjakt + a holder field + a
  scheduled sweep (the live-cleanup pattern).
- **Risk:** Bounded event windows naturally limit unsafe repeat driving and
  concentrate play into supervised meets.

### Variant 2 — Persistent control with decay ("Hold the Line")

Nodes are held continuously. Ownership **decays** over time unless refreshed by
a re-capture, so holding territory requires ongoing presence. A scheduled job
periodically awards small KP trickles to current holders and applies decay.

- **Core loop:** capture → periodic trickle income while held → ownership
  weakens over time → must revisit to refresh, or lose it to a challenger.
- **Scoring:** continuous small KP income; leaderboards of territory held.
- **Teams/factions:** strongly implied — persistent maps are more fun with
  factions competing for map dominance.
- **Complexity:** HIGH. Real-time contested state, decay math, continuous
  scoring, and a persistent leaderboard. Highest cost-control risk (frequent
  scheduled jobs, more writes).
- **Risk:** **Actively rewards repeat physical visits** — the strongest pull
  toward unsafe/nuisance driving and location hotspots. This directly tensions
  the badges rule and the product's safety posture. Least aligned with the
  codebase's values as written.

### Variant 3 — Team-vs-team siege events ("The Siege")

A scheduled, bounded PvP event: two (or more) teams contest a *ring* of nodes
around a central objective. Capturing outer nodes unlocks the objective;
holding the objective at the end wins the siege for the team. One-off,
tournament-style.

- **Core loop:** team assembles → capture outer ring → push to central
  objective → hold to win → team KP split + a one-time event badge.
- **Scoring:** team-based; winning team splits a KP pot; MVP-style per-player
  contribution optional.
- **Teams/factions:** mandatory and central; needs a roster/lobby (reuse the
  group-drive participant pattern).
- **Complexity:** MEDIUM–HIGH. Team model + lobby + objective state machine +
  event lifecycle. But bounded in time like Variant 1.
- **Risk:** Medium. Bounded + supervised, but the "push to objective" loop can
  still encourage rushing. Needs careful geofencing to safe venues.

### Recommendation: **Variant 1 (Timed territory capture)** for a first version

It delivers the core "contest a place" fantasy with the **least new machinery**
and the **best safety profile**: bounded event windows, no continuous decay
grind, individual play first (teams as a fast-follow), and a scoring model that
is a single scheduled job. It is the smallest thing that is recognizably
"Sieges", and it maps almost directly onto proven Kronjakt + live-location +
ledger patterns. Variants 2 and 3 are natural follow-ons *if* Variant 1 proves
the community wants competitive play.

---

## 5. Technical design for the recommended variant (Variant 1)

Sibling domain `functions/src/sieges/` (Option B). Feature flag `sieges`
(default `false`). All callables `onCall`, region `europe-west1`, App Check
enforced, mirroring Kronjakt's option block.

### 5.1 Data model (Firestore + RTDB)

```
siegeEvents/{eventId}                     (Firestore — admin-managed)
  title, description
  status: draft | scheduled | active | scoring | ended
  windowStart, windowEnd                  (Timestamps)
  nodeIds: [pointId, ...]                  (denormalized for client convenience)
  rewardPerNodeHeld: int (1..1000 KP)
  createdByUserId, approvedByUserId, approvedAt (safety gate — see below)
  createdAt, updatedAt

siegeNodes/{nodeId}                        (Firestore — admin-managed, geofenced)
  eventId
  title
  latitude, longitude
  geofenceRadiusMeters (20..150)           (reuse Kronjakt bounds)
  status: draft | active | paused
  safeLocationConfirmed: true              (activation gate)

siegeCaptures/{captureId}                  (Firestore — every ATTEMPT, append-only)
  doc ID = SHA-256(userId, idempotencyKey) (Kronjakt idempotency pattern)
  eventId, nodeId, userId, teamId?
  result: captured | outside_geofence | moving_too_fast
          | position_too_old | event_inactive | risk_review
          | not_eligible | feature_disabled | already_holding
  claimedAt, distanceMeters, positionRecordedAt, reportedSpeedMetersPerSecond

siegeCaptureRisk/{captureId}               (Firestore — BACKEND ONLY)
  userId, eventId, nodeId, riskScore, riskReasons   (never client-readable)

siegeScores/{eventId}/participants/{uid}   (Firestore — written by scoring job)
  teamId?, nodesHeldAtClose, totalHoldSeconds, kpAwarded

/siegeState/{eventId}/{nodeId}             (RTDB — live contested state)
  holderUid, holderDisplayName, teamId?, heldSince   (backend-owned; NO client writes)
```

Rationale:
- **Live "who holds it now" belongs in RTDB**, not Firestore. It changes
  frequently during a window and every participant subscribes to it — exactly
  the live-location trade-off. Firestore holds the durable audit/score records;
  RTDB holds the volatile current state. This mirrors the existing split
  (roster in Firestore, live markers in RTDB).
- **Every capture attempt is recorded** (audit + anti-cheat + idempotency),
  identical to `crownHuntClaims`, with risk in a separate backend-only
  collection (rules can't redact per-field).
- Node bounds and the activation safety gate are copied from Kronjakt verbatim.

### 5.2 Backend surface

**Member callables**
- `sieges.captureNode` — the heart of the game. Reuses the Kronjakt validation
  pipeline **step for step**: feature flag → account/entitlement → idempotency
  replay → load event (must be `active`, `now` within `[windowStart,
  windowEnd]`) and node (must be `active`) → coordinate validation → position
  freshness → server-side Haversine distance + accuracy-buffered geofence →
  **speed gate (must be safely stopped)** → risk evaluation → on success,
  update `/siegeState/{eventId}/{nodeId}` (holder = uid, `heldSince = now`) and
  append the `captured` capture record. **No KP is awarded at capture time** —
  scoring is deferred to window close (this removes the incentive to spam-claim
  a single node for points and dramatically reduces the unsafe-repeat-visit
  pull). Reuses `isWithinGeofence`, `isPositionFresh`, `isSpeedSafe`,
  `isPlausibleJump`, `evaluateClaimRisk` unchanged.
- `sieges.listActiveEvents` — read active/scheduled events + their nodes.
  (Members can also read `siegeNodes` / `siegeEvents` directly via rules, like
  they read active Kronjakt points.)

**Admin callables** (all `requireAdminActor`, all audited)
- `sieges.createEvent` / `updateEvent` / `scheduleEvent` / `endEvent`
- `sieges.createNode` / `updateNode` / `activateNode` (safety gate:
  `safeLocationConfirmed: true` + approval note) / `pauseNode`
- `sieges.reviewCapture` — resolve a `risk_review` capture (mirrors the
  documented Kronjakt admin-review TODO; a shared review workflow is ideal).

**Triggers / scheduled jobs**
- `sieges.scoreWindowClose` — an `onSchedule` (the live-cleanup pattern) that,
  for events whose `windowEnd` has passed and `status === 'active'`, reads
  final `/siegeState`, computes `nodesHeldAtClose` per participant/team, writes
  `siegeScores/...`, awards KP via `creditPoints({ source: 'siege',
  idempotencyKey: 'siege-close_<eventId>_<uid>', ... })` (idempotent, so a
  re-run double-awards nothing), flips the event to `ended`, and clears the
  RTDB state node. Alternatively an event-driven `onValueWritten` if precise
  real-time scoring is ever needed (Variant 2), but a scheduled sweep is
  cheaper and matches existing patterns.

### 5.3 Real-time: RTDB vs Firestore

| Concern | Store | Why |
| --- | --- | --- |
| Current node holder (volatile, high fan-out subscribe) | **RTDB** `/siegeState` | Same reasoning as live-location markers: frequent updates, every participant subscribes. |
| Capture attempts, risk, scores (durable, audited, queried) | **Firestore** | Append-only audit; transactional KP payout via the ledger; admin queries. |
| KP payouts | **Firestore ledger** | Atomic, idempotent, replay-safe — reuse as-is. |

### 5.4 Anti-cheat / GPS-spoofing

Reuse Kronjakt's approach **wholesale** — this is the single biggest reason the
feature is feasible at all:
- Server-computed distance; client-supplied distance never trusted.
- Position freshness (≤ 60 s) and the "safely stopped" speed gate.
- Accuracy-buffered geofence (buffer capped so poor accuracy can't cheat range).
- `isPlausibleJump` teleportation detection against the RTDB last trusted
  position.
- `evaluateClaimRisk` scoring; score ≥ 60 → `risk_review`, no capture recorded
  as valid, flagged to admins. Risk reasons/thresholds never leave the backend.
- **Competitive play raises the spoofing incentive** vs. single-player Kronjakt,
  so this variant is where the documented **Play Integrity / App Attest** TODO
  should finally be implemented (populate `platformIntegrityPassed`, +40 risk
  when it fails). Consider a per-event, per-user capture-rate cap and a
  cooldown between captures of the *same* node to blunt automated flipping.

---

## 6. Safety & fair play

The product's north star (`docs/product-decisions.md`) is a *safe, inclusive*
community, and the badges module explicitly forbids anything that encourages
unsafe driving. Sieges is the backlog idea most in tension with this, so safety
is a **gating requirement, not a feature**:

- **Must be safely stopped to interact** — inherited speed gate (≤ 1.4 m/s).
  No interaction while moving, ever.
- **Geofence only to safe, public, sanctioned locations.** Admin activation
  requires `safeLocationConfirmed: true` + an audited approval note (Kronjakt
  gate). Nodes should be car meets, public car parks, sanctioned venues — never
  private property, residential streets, or anything that invites trespassing
  or nuisance gathering. This is an **admin curation discipline**, enforced by
  the activation gate and audit trail.
- **Bounded event windows** (Variant 1) rather than 24/7 persistent control —
  concentrates play into supervised meets and removes the "grind" that drives
  repeat late-night driving.
- **Score at window close, not per capture** — removes the incentive to
  repeatedly re-capture a node for points.
- **No speed/distance/racing rewards or badges.** KP for *holding a place*,
  never for *how fast you got there*. Badge wording stays positive and
  non-competitive per the existing rule.
- **Abuse handling:** captures run through the existing risk pipeline;
  `risk_review` captures are quarantined for admin review; suspended/deleted
  users transact no points (ledger enforces). Reuse the existing blocking /
  moderation seams. Admins can pause a node or end an event instantly.
- **Privacy:** reuse live-location's "hide me now" — being visible as a holder
  should respect the same visibility controls; a member must be able to
  participate without broadcasting a persistent live track.

---

## 7. Effort estimate & phasing

Rough order-of-magnitude, assuming Option B and Variant 1. Excludes native
Android UI depth and admin-web screens beyond basic CRUD.

| Phase | Scope | Effort |
| --- | --- | --- |
| **0. Spike** | Extract pure geo/risk helpers to `shared/`; prototype `captureNode` + RTDB node-state + a scheduled close job on the emulator. Prove the loop end-to-end with fake positions. | ~2–4 days |
| **1. Backend MVP** | `sieges/` domain: data model, member `captureNode` + `listActiveEvents`, admin CRUD + activation gate, `scoreWindowClose` scheduled job, `siege` ledger source, security rules, contracts, tests. | ~1.5–2.5 weeks |
| **2. Android** | Map surface (reuse live-location map), node markers + holder overlay, capture button gated on "stopped", event window UI, KP result. | ~1.5–2.5 weeks |
| **3. Admin web** | Event + node CRUD, activation with safety confirmation, capture risk-review queue, live leaderboard. | ~1–1.5 weeks |
| **4. Hardening** | Play Integrity / App Attest, rate/cooldown caps, cost-control review of scheduled-job cadence, playtest + safety review. | ~1 week |

**Total: ~5–8 weeks** for a polished Variant 1 across backend + Android +
admin, plus the spike. Teams (fast-follow) and Variants 2/3 are separate
efforts.

---

## 8. Risks

- **Product/values risk (highest):** competitive territory control can pull
  against the app's safety-first, inclusive, non-competitive posture. If the
  community skews casual/social, Sieges could feel off-brand or attract the
  wrong behavior. *This is the deciding risk and it is not an engineering one.*
- **Safety/liability:** any location game that rewards being somewhere invites
  unsafe driving, trespassing, or nuisance gatherings. Mitigated but not
  eliminated by the gates above; requires ongoing admin curation.
- **Anti-cheat arms race:** competition raises spoofing incentive; the current
  risk model is solid but Play Integrity/App Attest are still TODOs.
- **Cost control:** scheduled jobs, RTDB fan-out, and capture write volume must
  stay within the SEK 500/month envelope (ADR-001). Variant 1's bounded windows
  and single close-job keep this modest; Variant 2 does not.
- **Scope creep:** teams, factions, leaderboards, and seasons are all tempting;
  Variant 1 must ship thin.
- **Player concentration:** contested nodes deliberately draw people to one
  place at one time — capacity/parking/neighbour-impact must be considered per
  venue.

---

## 9. Open product questions

1. **Do we even want competitive play?** Is a territory-control game consistent
   with the KCC brand and the "safe, inclusive, non-competitive" values, or does
   it invite exactly the behavior we design against? (This gates everything.)
2. **Individuals or teams first?** Teams are more fun but add a roster/faction
   model. Variant 1 works either way — which is the v1?
3. **What does a node *cost the neighbourhood*?** Who owns venue selection and
   the "is this a safe, sanctioned place to draw a crowd" judgment, and is there
   appetite to maintain that curation over time?

---

## 10. Recommendation

**Defer — with an optional, cheap design spike.**

- The **engineering feasibility is high**: Kronjakt already proves the hard
  parts (geofencing, GPS anti-cheat, atomic idempotent payouts, admin curation
  with safety gates), and Sieges (Variant 1, Option B) is largely a
  recombination of existing, battle-tested primitives plus a scheduled scoring
  job.
- The **product and safety desirability is genuinely uncertain** and matters
  more than the feasibility. Competitive territory control sits in tension with
  the codebase's explicit anti-unsafe-driving, non-competitive values.
- Therefore: **do not build now.** This is post-MVP at the earliest, and only
  if the community demonstrably wants competitive play. If there is appetite to
  explore, fund a **~2–4 day spike** (Phase 0) to de-risk the technical loop and
  produce a playtestable prototype behind the `sieges` flag — cheap insurance
  before committing to the full ~5–8 week build. Otherwise, **park it on the
  ideas board.**

---

*Appendix — key source references:*
`functions/src/crownHunt/{crownhunt-core,crown-hunt-geo,crown-hunt-risk,submitClaim,managePoints}.ts`,
`functions/src/points/{ledger,points-core}.ts`,
`functions/src/badges/badge-core.ts`,
`functions/src/live/{live-core,scheduled}.ts`,
`functions/src/groupDrive/groupdrive-core.ts`,
`docs/product-decisions.md`, `docs/adr/001-firebase-platform.md`,
`contracts/features/feature-flags.json`.
