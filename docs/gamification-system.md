# Gamification System — Kronjakt, Kronpoäng, Märken

**Status:** Design proposal, awaiting product approval.
**Scope:** The mathematical and logical system that ties **Crown Hunt (Kronjakt)**, **Points (Kronpoäng / KP)** and **Badges (Märken)** into one coherent, abuse-resistant loop.
**Audience:** Seb (product approval), backend implementers, design.

This document describes **both** what exists in the codebase today **and** the proposed extension. Nothing here is implemented purely from this document — it is the shared reference the implementation slices are written against.

**How to read it.** The default is **proposed**. §1 is the honest inventory of what exists; §8.1's Status column and the `STATUS:` banners on §4, §6 and §7 mark the built/unbuilt boundary where it is easy to get wrong. Everything else — the numbers in §10 and the still-unbuilt parts of §7 — is design, even where it is written in the present indicative for readability.

> **SINCE THIS DRAFT: §4, §5 and §6 have SHIPPED — but §4 only in part.**
>
> - **§5 (the earning rules, caps and streak) is BUILT.** `functions/src/points/points-economy-core.ts` is the canonical rule table, cap arithmetic and streak maths; `points/economy-award.ts` is the single award door; `points/economyTriggers.ts` holds the five triggers. All eight `Action` identifiers exist, both caps have real counter collections, and partial clipping is real (`creditPointsResolved` in `ledger.ts`). Where the numbers below differ from `points-economy-core.ts`, **the code is canonical.**
> - **§6 (event-attendance verification) is BUILT** as the `events.checkIn` callable + the `eventAttendance` collection. Its per-banner caveats are corrected in place below.
> - **§4's auto-spawn engine is BUILT** (PR #570: `functions/src/crownHunt/crown-spawn-core.ts`, `spawnScheduled.ts`, `spawnCells.ts`, `spawnActivity.ts`, `claimSpawn.ts`) — see the §1.1 table. It is behind the `crownHuntSpawn` flag, **contract default OFF**.
>   **Two parts of §4 did NOT ship as written and are still design:** the **Poisson replenishment** of §4.4 (the code tops a cell straight up to target — §4.4's banner) and the **placement policy mask** of §4.7 (replaced by an admin cell allow-list — §4.7's banner). Do not cite either as a live property.
> - **Still unbuilt:** the §7 badge ladders (no tier field, nothing writes `source: 'badge'`), cap exemption, and a `readGuard` on `debitPoints`.

**If you are implementing from this document, assume nothing described here exists until you have grepped for it.** The repo has a recurring failure mode where a doc asserts an invariant that no code enforces, and several of the callouts below exist specifically because an earlier draft of this document did exactly that.

---

## 0. Hard constraints

These are not negotiable and every section below is written to respect them. If a future proposal conflicts with one of these, the proposal is wrong.

### C1 — No speed gamification, ever

**Nothing in this system may reward, rank, or imply reward for speed.** No "fastest lap", no speed-based leaderboard, no acceleration badge, no "you beat your record" copy, no personal best, no comparison between drives.

**Amended 2026-07 by an explicit product decision:** a drive's maximum speed may now be *displayed* — `drives.save` stores `maxSpeedMetersPerSecond` on the ride document and the History card shows it. The clause above used to read "reward, rank, **display**, or imply reward" and to forbid a top-speed stat outright; "display" was dropped because showing a number is not the hazard, paying out for it is. What the amendment does not license: the figure sits at the same visual weight as distance and duration, carries no colour or styling that rewards a higher number, and is never compared against another drive or a previous best. If it ever starts to look like a trophy, this clause has been broken.

Two reasons, both load-bearing:

1. **Safety.** A points system that pays out for speed is a system that pays people to drive dangerously. There is no safe way to A/B test that.
2. **Brand differentiation.** Competitor X gamifies driving behaviour. Our stance — that a car community is about the cars, the meets and the people, not about how fast you went — is a deliberate market position, not an oversight. It is also the reason we can market to owners' clubs, insurers and families without a reputational asterisk.

This constraint is encoded in the codebase: `functions/src/drives/drive-calculations.ts` states in its header that maximum speed is computed and stored but never rewarded — it used to say "No top-speed calculation or storage", and the header records the reversal rather than dropping it — that it computes no driving-quality scores, and `functions/src/badges/badge-core.ts` states that no badge — in wording or artwork — rewards speed. (That rule was originally worded "no speed/distance/racing badges"; it was corrected when Vägfarare shipped, since Vägfarare *is* a distance ladder. The binding constraint is speed, per Q3.)

> **Note on the distance badges proposed in §7.** They are *lifetime milestone* badges, not per-kilometre payouts. The marginal KP for one more kilometre is zero once the (capped) daily distance award is taken. See §5.4 and §7.2 for why that keeps them inside the spirit of the rule, and open question **Q3** for Seb's call on whether they belong at all.

### C2 — Crowns are collected only while stationary

A crown may only be collected when the device is **not moving**: sustained speed ≤ **2.0 m/s** (7.2 km/h — walking pace) plus a short dwell. The user must have stopped safely before they can reach for the phone.

**The two crown paths now enforce this differently, and only one of them still has gaps.**

| | Hand-placed points (`submitClaim.ts`) | Auto-spawned crowns (`claimSpawn.ts`) |
|---|---|---|
| Speed ceiling | `MAX_CLAIM_SPEED_MPS = 1.4` (~5 km/h) | `MAX_COLLECT_SPEED_MPS = 2.0` (7.2 km/h) |
| Samples checked | **one** client-reported speed | **two** fixes, both reported speeds |
| Dwell | none | `MIN_DWELL_SECONDS = 4` .. `MAX_DWELL_SECONDS = 300` |
| Server-derived speed | **no** | **yes** — `movedMeters / dwellSeconds ≤ 2.0` |
| Omitting `speedMetersPerSecond` | **passes** | still caught by the derived check |

Both live in `crown-spawn-core.ts` / `crownhunt-core.ts` and route through `isSpeedSafe()` in `crown-hunt-geo.ts`; the spawn path bundles all four conditions into `evaluateStationaryCollection`, which also requires **both** fixes to be inside the (accuracy-buffered) collect radius. Failing it returns the plain result code `must_be_stationary` and deliberately feeds **nothing** into the risk score — the common case is an honest member rolling through a car park, not a cheat.

**Remaining gaps between C2 as written and C2 as enforced — these now apply to the hand-placed path only:**

1. **The gate is omittable on `submitClaim`.** `speedMetersPerSecond` is *optional* in the request schema, and `isSpeedSafe` opens with `if (speedMps === null || speedMps === undefined) return true;` — a **missing** speed is treated as safe, so a client that never sends the field is never speed-checked there. This is a deliberate, documented allowance for devices with no speed fix, but it means C2 is a constraint on *honest* clients only on that path. `claimSpawn` closes it with the server-derived speed, which needs no client cooperation beyond two coordinates it has already committed to — that is the pattern **Q16** should adopt for `submitClaim`.
2. **There is no "sustained" measurement on `submitClaim`.** It reads a *single* client-reported instantaneous sample. `claimSpawn`'s two-fix window is the nearest thing to "sustained" that ships, and it is a 4–300 s window rather than a continuous track.

Note the distinction: gap 1 is a *safety* gap (C2 is the constraint that stops phone-reaching while driving), whereas the 1.4-vs-2.0 question is mere tuning. Q16 is the one that matters, and it is now open for one path rather than two.

**Q1 has been answered by divergence rather than by decision.** 2.0 m/s shipped for spawned crowns and 1.4 m/s stayed on hand-placed points, so both values are live in production at once. Whether that is the intended end state or wants reconciling is still Seb's call — see **Q1**.

The user-facing copy already exists and is correct, but note it is **two separate keys** in `contracts/localization/{en,sv}.json`, not one sentence — do not grep for the concatenation:

| Key | Swedish | English |
|---|---|---|
| `crownHunt.movingTooFast` | *"Du rör dig för snabbt för att samla in."* | *"You are moving too fast to collect."* |
| `crownHunt.safetyStop` | *"Stanna säkert innan du samlar in belöningen."* | *"Stop safely before collecting the reward."* |

Both are already at EN/SV parity and already mirrored into `apps/android/.../strings.xml`. The rejection path returns `moving_too_fast`; pairing it with `safetyStop` as the guidance line is a client presentation choice, not a new string.

### C3 — Distance rewards must be capped

An uncapped per-kilometre reward is an instruction to drive in circles. Every distance-derived earn path in §5 is capped per day **and** per week, and once the weekly driving budget is spent the marginal reward for another kilometre is exactly zero. This is a feature, not a limitation — see §5.4.

### C4 — Points are server-authoritative

The client never computes, proposes, or writes a balance. Mutations go through `creditPoints`/`debitPoints` in `functions/src/points/ledger.ts`, inside a Firestore transaction: read `pointsLedger/{uid}.balance` → append `pointsLedger/{uid}/entries/{entryId}` → update the denormalized balance. Entries are **append-only**; corrections are compensating entries (`adjustment_credit`, `adjustment_debit`, `reversal`), never edits. Balances can never go negative. Suspended and deleted accounts earn nothing.

Two exceptions matter, because §5 leans on this as the single choke point where future caps get installed:

- **`ledger.ts` is not the only writer.** `points.adminReverse` opens its **own** transaction in `functions/src/points/adminPoints.ts` and writes the reversal entry and the balance directly, bypassing `creditPoints`/`debitPoints` entirely (only `adminAdjust` uses them). **A cap implemented inside `ledger.ts` would not see reversals.** Either route `adminReverse` through the shared primitives first, or accept and document that reversals are out of scope for caps — but do not assume one choke point exists today. See **Q15**.
- **"Never deletes" holds for every mutation path, but not for account erasure.** `functions/src/account/deletion-core.ts` lists `pointsLedger` in `PURGE_DOC_TREES`, so deleting an account destroys the whole ledger subtree. That is correct GDPR behaviour and is not a bug — it simply means "append-only" is a statement about *operations on a live account*, not an eternal audit guarantee.

---

## 1. What exists today

Reading the current code, the system is roughly half-built. This is the honest inventory.

### 1.1 Crown Hunt — built, but admin-placed

| Concern | Status | Where |
|---|---|---|
| Point CRUD (`createPoint`/`updatePoint`/`activatePoint`/`pausePoint`) | **Admin-only, built** | `functions/src/crownHunt/managePoints.ts` |
| Activation safety gate (`safeLocationConfirmed: true` + approval note) | **Built** | `managePoints.ts` |
| Claim submission, 15-step validation | **Built** | `functions/src/crownHunt/submitClaim.ts` |
| Haversine, geofence w/ accuracy buffer, freshness, speed, jump | **Built** | `functions/src/crownHunt/crown-hunt-geo.ts` |
| Risk scoring, threshold 60 → `risk_review` | **Built** | `functions/src/crownHunt/crown-hunt-risk.ts` |
| Idempotency + concurrent-award guards | **Built** | `crownhunt-core.ts`, `submitClaim.ts` |
| **Spawn algorithm** | **Built, backend only** | `functions/src/crownHunt/crown-spawn-core.ts`, `spawnScheduled.ts` |
| **Rarity tiers / TTL** | **Built** (`CROWN_RARITY_TABLE`: 10/25/100/500 KP, 6/12/24/48 h) | `crown-spawn-core.ts` |
| **Auto-spawn claim** (`crownHunt.claimSpawn`) | **Built, backend only** — stopped + dwelling required | `functions/src/crownHunt/claimSpawn.ts` |
| Admin cell allow-list (`setSpawnCellApproval`) | **Built, backend only** | `functions/src/crownHunt/spawnCells.ts` |
| Activity aggregate `A(cell)` | **Built, backend only** | `functions/src/crownHunt/spawnActivity.ts` |
| **Android client for any of the above** | **DOES NOT EXIST** | — |

**Crowns now come from two sources — but only one of them is reachable by a member.** Hand-placed `crownHuntPoints/{pointId}` documents an admin created and explicitly activated with a safety confirmation, *and* ephemeral `crownSpawns` placed automatically near recent member activity. Because no human reviews an auto-spawned coordinate, the safety approval moves up to the **area**: `crownHunt.setSpawnCellApproval` is an admin allow-list of grid cells the spawner may place in, and the whole automatic half sits behind the `crownHuntSpawn` feature flag, **contract default OFF**. Both crown paths credit the ledger with `source: 'crown_hunt'` and are folded into the daily cap after the fact (§5.3), with separate per-day claim counters (10 hand-placed, 20 spawned).

> **The auto-spawn half is inert today, in both directions.** The flag is OFF, so nothing spawns and no activity data is collected; and `grep -rnE "claimSpawn|crownSpawn" apps/android` returns **nothing**, so even with the flag on, no client could show or collect a spawned crown. Treat every auto-spawn row above as "server-side code that is deployed and tested but has never served a member". §4's banner lists what is left before that changes.

Current constants (`crownhunt-core.ts`):

| Constant | Value | Meaning |
|---|---|---|
| `MIN_GEOFENCE_RADIUS_METERS` | 20 | Admin-settable floor |
| `MAX_GEOFENCE_RADIUS_METERS` | 150 | Admin-settable ceiling |
| `MIN_REWARD_POINTS` / `MAX_REWARD_POINTS` | 1 / 1 000 | Per-point reward bounds |
| `MAX_CLAIM_SPEED_MPS` | 1.4 | Stationary gate |
| `MAX_POSITION_AGE_SECONDS` | 60 | Freshness |
| `MAX_DAILY_SUCCESSFUL_CLAIMS` | 10 | Per-user per-UTC-day |
| Repeat rules | `once` \| `daily` \| `weekly` | Per point |

The proposed 75 m collect radius and 10/25/100/500 KP rewards all sit comfortably inside the existing bounds — the spawner can use the existing point schema without widening any limit.

### 1.2 Points — built, and the foundation is sound

`functions/src/points/points-core.ts` + `ledger.ts` already give us exactly what a gamification economy needs:

- Transaction types: `earn`, `spend`, `adjustment_credit`, `adjustment_debit`, `reversal`.
- Sources: `badge`, `event`, `garage`, `admin_adjustment`, `system`, `crown_hunt` (plus a deprecated `future_crown_hunt` kept only for legacy rows). **The enum was always complete; the writers were the gap — and that gap is now closed.** §5 shipped the economy callers, so `event`, `garage` and `system` are written by the economy rule table (`points-economy-core.ts`), alongside the pre-existing `crown_hunt` (both crown paths) and `admin_adjustment`. §7's ladders supply the last one: `badge` is written by `badges/tierAwards.ts` for the tier milestones. Every source now has a writer, and no enum change was ever needed — exactly as predicted.
- Idempotency key **is** the entry document ID *when one is supplied*, so a replayed award is a transactional no-op that returns the original entry. The key is **optional** and falls back to an auto-ID, so this is a property of well-behaved callers, not an invariant of the ledger — see §5.5.
- An `AtomicReadGuard` hook that lets a caller run reads *inside* the award transaction and abort it — this is how the Kronjakt daily cap is enforced without a race, and it is the same mechanism every cap in §5 will use.

**The economy in §5 needed no new ledger *concepts*** — the transaction shape, the source enum and the atomic-guard mechanism all carried over unchanged, which is a good sign about the design of the existing code. It did need new primitives. Three were identified; one shipped with §5 and two remain open because nothing needs them yet:

| Gap | Status |
|---|---|
| **Partial clipping** | **CLOSED.** §5.3 and the §10 worked example require awarding *part* of an earn when it would breach a cap (e.g. 130 of 180 KP), and `AtomicReadGuard` returns `Promise<void>` — it can only throw to abort the whole mutation. `creditPointsResolved` in `ledger.ts` adds an `AmountResolver` that runs inside the transaction and returns the final amount, which the ledger validates is a positive integer **no larger than the requested one** — a resolver can only clip down, never inflate an award. |
| **Cap exemption** | **OPEN.** §5.3 exempts badge milestones from the 300/day global cap. `PointsMutationParams` has no such flag, so the cap logic has nothing to branch on. Not yet needed: the §7 ladders are unbuilt, so nothing writes `source: 'badge'`. |
| **`debitPoints` has no `readGuard`** | **OPEN.** `creditPoints` takes one; `debitPoints` takes only `extraWrites`. Any capped or guarded *spend* path needs the parameter added. Not yet needed: there is no spend path. |

Note that the first gap interacts with the **forfeit-not-bank** decision (Q9): capped-out KP is forfeited, and partial clipping is what makes "you earned 180, we credited 130" honest rather than a silent all-or-nothing rejection. The shipped award engine names the binding ceiling (`clippedBy`: `daily` or `weekly_driving`) in the ledger entry's description, so a member can always see *which* cap bit.

### 1.3 Badges — built, but flat and thin

`functions/src/badges/badge-core.ts` originally had exactly **five** badges, all single-tier. They are still present and unchanged, and are now joined by the 22 tiered rungs of §7:

| Key | Swedish name | Automatic? |
|---|---|---|
| `first_event` | Första träffen | yes |
| `five_events` | 5 träffar | yes |
| `helpful_member` | Hjälpsam medlem | no (admin) |
| `early_member` | Tidig medlem | yes |
| `garage_created` | Garageprofil skapad | yes |

At the time this document was first written there was no progression, no tiering, no crown-related badge and no relationship at all between badges and Kronpoäng; §7 has since shipped all four. Event attendance uses a deliberately conservative proxy — a `going` RSVP on an event that reaches `completed` — counted on a backend-only `badgeProgress/{uid}` document. That proxy is **not** presence verification; §6 adds real verification and keeps the proxy as the fallback.

### 1.4 Drives — distance and speed exist as facts, never as rewards

`functions/src/drives/drive-calculations.ts` computes total distance with Haversine and **excludes any segment implying > 55.6 m/s (200 km/h)** as a GPS teleport artifact. It computes an average speed, and (since the 2026-07 amendment to C1) a maximum speed under the same 55.6 m/s filter — stored and displayed as a plain fact, never scored, ranked or paid out for. It computes no quality score. This 55.6 m/s per-segment filter is a *distance-integrity* filter and is distinct from the 130 m/s *anti-teleport* bound in `isPlausibleJump` — the two serve different purposes and both are kept (§8).

---

## 2. The loop, in one picture

```
                 ┌─────────────────────────────┐
                 │  Presence (aggregate only)  │
                 │  A(cell), 7-day decayed     │
                 └──────────────┬──────────────┘
                                │ drives
                                ▼
    ┌────────────────────────────────────────────────┐
    │  CROWN SPAWNER  (§4)                           │
    │  admin-approved cells only · flag default OFF  │
    │  N_target = ceil(1.5 · ln(1+A)), cap 5         │
    │  top-up to target · 150 m min separation       │
    │  rarity 70/22/7/1 · TTL 6/12/24/48 h           │
    └──────────────────────┬─────────────────────────┘
                           │ crowns appear on map
                           ▼
    ┌────────────────────────────────────────────────┐
    │  GEO-EARN GUARD  (§8) — one pipeline, all earns│
    │  fresh · in-fence · stationary · plausible ·   │
    │  accurate · rate-limited · risk < 60           │
    └──────────────────────┬─────────────────────────┘
                           │ passes
                           ▼
    ┌────────────────────────────────────────────────┐
    │  POINTS LEDGER  (§5) — append-only, capped     │
    │  300 KP/day global · 400 KP/week driving       │
    │  every award idempotent on a derived key       │
    └──────────────────────┬─────────────────────────┘
                           │ counters cross thresholds
                           ▼
    ┌────────────────────────────────────────────────┐
    │  BADGES  (§7) — tiered progression             │
    │  Brons → Silver → Guld → Platina               │
    │  milestone KP bonus, credited via source=badge │
    └────────────────────────────────────────────────┘
```

**Three of the four boxes are now built; the badge box is not.** The ledger's *append-only, idempotent-on-a-derived-key* property, the earning rules, and both caps annotated on the diagram (300/day, 400/week) are real: `pointsDailyTotals` and `pointsWeeklyDriving` are the counter collections and `DAILY_POINTS_CAP` / `WEEKLY_DRIVING_POINTS_CAP` the constants, both in `points-economy-core.ts`, read and incremented inside the award transaction. Presence aggregation and spawning shipped with the auto-spawn engine (§4, §1.1) — though the spawner **tops a cell straight up to target** rather than drawing Poisson arrivals, so the diagram's replenishment box is labelled for what the code does, not for §4.4. **Badge tiers remain unbuilt**, so the bottom box and its `source=badge` arrow are still design. Both crown daily caps exist side by side and are unchanged: `MAX_DAILY_SUCCESSFUL_CLAIMS = 10` on hand-placed claims and `MAX_DAILY_SPAWN_CLAIMS = 20` on spawned ones, counted separately.

Three feedback loops, deliberately different in tempo:

- **Fast (minutes):** see a crown → stop → collect → KP. Variable reward, immediate.
- **Medium (days):** streak multiplier, daily caps resetting, map refreshing as TTLs expire.
- **Slow (months):** badge tiers. This is the one that survives the novelty wearing off.

---

## 3. Notation

| Symbol | Meaning |
|---|---|
| `A(c)` | Activity score of grid cell `c` — a decayed count of distinct recent visitors |
| `τ` | Activity decay time constant = **3 days** |
| `Δt_{u,c}` | Time since user `u` was last present in cell `c` |
| `K` | Spawn coefficient = **1.5** |
| `N_target(c)` | Desired number of live crowns in cell `c` |
| `N_current(c)` | Live (unclaimed, unexpired) crowns in cell `c` right now |
| `τ_spawn` | Replenishment time constant = **3 hours** — **§4.4 only, not implemented** |
| `λ(c)` | Poisson spawn rate for cell `c`, crowns per hour — **§4.4 only, not implemented** |
| `d_min` | Minimum separation between live crowns = **150 m** |
| `r_collect` | Collection radius = **75 m** |

`τ_spawn` and `λ` appear nowhere in the code. The shipped spawner places `N_target − N_current` crowns in a single pass; see the §4.4 banner.

---

## 4. Crown spawning

> **STATUS: BUILT in PR #570, behind the `crownHuntSpawn` flag (contract default OFF) — but not all of it, and not all as written.** Per-subsection status:
>
> | § | Status |
> |---|---|
> | 4.1 The grid | **Built as specified** — `CROWN_CELL_DEGREES = 0.01`, `crownCellKey()` |
> | 4.2 Activity score `A(c)` | **Built, with real differences** — see its banner (storage shape, hashing, and a new slow-sighting filter the design never mentioned) |
> | 4.3 Target crown count | **Built as specified** — `targetCrownCount()` |
> | 4.4 Poisson replenishment | **NOT BUILT.** The spawner tops straight up to target |
> | 4.5 Placement / dart throwing | **Built**, 24 attempts not 20, and with no safety mask in the rejection test |
> | 4.6 Rarity, value, lifetime | **Built as specified** — `CROWN_RARITY_TABLE` |
> | 4.7 Placement safety | **NOT BUILT as written.** The policy mask does not exist; an admin cell allow-list shipped instead (**Q4**) |
>
> The maths that shipped is in `functions/src/crownHunt/crown-spawn-core.ts` (pure, unit-tested in `crown-spawn-core.test.ts`); the I/O is in `spawnScheduled.ts`, `spawnCells.ts` and `spawnActivity.ts`. Where this section and the code disagree, **the code is canonical** — the numbers below have been checked against it and the differences are called out in place.
>
> **BACKEND ONLY — there is no client for this yet.** `grep -rnE "claimSpawn|crownSpawn" apps/android` returns **nothing**: the Android app cannot display a spawned crown and cannot call `crownHunt.claimSpawn`. Combined with the flag defaulting OFF, that means the engine is currently inert in both directions — nothing spawns, and nothing could be collected if it did. Everything in §8.1 about the spawn claim path describes server code that no client exercises today. The remaining work before this is a feature a member can see is: the Android map layer, the claim UI (which must send **two** position fixes — see C2), the `isMockLocation` flag, an admin screen for `setSpawnCellApproval`, a Firestore TTL policy on the activity rows (§4.2), and then the flag flip plus a first approved cell.

### 4.1 The grid

Space is bucketed into a **0.01° latitude × 0.01° longitude** grid. Cell ID is derived directly from truncated coordinates, so cell lookup is arithmetic — no spatial index needed.

At Swedish latitudes (~59°N) a cell is **1.11 km north–south × 0.57 km east–west**, area ≈ **0.64 km²**. Cells are therefore not square, and get narrower further north.

**This does not matter**, and it is worth being explicit about why: the grid exists only to *aggregate activity and target a crown count*. All actual geometry — separation, collection radius, geofencing — is computed in **metres** via Haversine. A slightly oblong aggregation bucket has no geometric consequence.

Shipped as `crownCellKey()`, which clamps both axes before flooring. `parseCrownCellKey()` additionally **rejects keys that are not a place on Earth** — the regex alone would accept `"50000_0"` (latitude 500), and an admin can name a cell key directly on `setSpawnCellApproval`, so the range check is load-bearing rather than cosmetic.

### 4.2 Activity score `A(c)`

> **STATUS: BUILT, but read the four differences below before citing this subsection.** The decay maths shipped exactly as written (`activityWeight`, `activityScore`); the *storage shape*, the *hashing*, and the *eligibility rule for what counts as a sighting at all* did not.
>
> 1. **Collection names.** Shipped as `crownCellActivity/{cellKey}` + `crownCellActivity/{cellKey}/recentUsers/{hash}`, not the `crownCells/…/presence/…` of §11.
> 2. **The digest is a plain SHA-256, not an HMAC under a rotating salt.** `crownActivityUserHash(cellKey, uid)` is a length-prefixed SHA-256 over the pair. It is genuinely cell-scoped, so the same member is a different identifier in every cell and the rows cannot be joined into a route — but **it is not keyed**, so anyone holding the UID list can recompute a digest and confirm a guess. The code says so in its own header. The defence is against *correlation*, not against an actor who already has the UIDs. This is a real weakening of what the privacy sketch below promised, and it is the thing **Q10** should now be answered against.
> 3. **`A` is NOT stored as an aggregate number on the cell document.** The cell document holds only `lastActivityAt` and `expireAt`. Each spawn pass **recomputes** `A` from up to `MAX_ACTIVITY_USERS_PER_CELL = 200` `recentUsers` documents.
> 4. **A slow-sighting filter exists that this design never specified**, and it is the most safety-relevant part of what shipped — see the callout under the weights table.
>
> Where the signal comes from is also narrower than "presence": the **only** writer is `live.updatePosition` (`spawnActivity.ts`), so a member contributes nothing unless they have an active, user-started live-sharing session. Writes are throttled to one per user per cell per `CROWN_ACTIVITY_MIN_INTERVAL_MS = 10 min`, with an immediate write when crossing into a new cell. The whole path is best-effort and swallows every error — a heat-map write must never be able to fail live location sharing.

```
A(c) = Σ  exp( −Δt_{u,c} / τ )        τ = 3 days
       u ∈ distinct users present in c during the last 7 days
```

Each distinct user contributes **at most once**, weighted by how recently they were there. Weights:

| Last seen | Weight `exp(−Δt/τ)` |
|---|---|
| now | 1.000 |
| 12 h ago | 0.846 |
| 1 day ago | 0.717 |
| 2 days ago | 0.513 |
| 3 days ago (= τ) | 0.368 |
| 5 days ago | 0.189 |
| 7 days ago (window edge) | 0.097 |

The 7-day window with a 3-day constant means the system effectively has a **three-day memory with a soft tail**. A place that was busy last weekend and dead since decays out on its own; no separate "expire stale hotspots" job is needed. Exponential decay is also *incrementally computable* — you never need to re-scan history, only re-weight.

**Distinctness matters.** Without it, one person parked in a cell for eight hours emitting pings would look like a crowd, and the system would carpet their driveway in crowns. Distinct-user counting means a cell needs *actual different people* to qualify.

**Only SLOW sightings count — this shipped, and this design never asked for it.** `isActivitySightingEligible` discards any sample whose reported speed exceeds `MAX_ACTIVITY_SPEED_MPS = 8` (28.8 km/h), and a sample with **no** speed is discarded too (the opposite of `isSpeedSafe`'s "absent means safe" — here an unknown speed is an unproven claim about a place, and the only cost of a false negative is a slightly lower score).

The reason is the worst failure mode this engine has. Raw presence would rank a **motorway** as the busiest place in the country, and a crown beside one is an invitation to stop on a hard shoulder. Requiring slow presence means a cell can only score from places people are actually *at* — car parks, meets, queues, on foot — and a cell nobody ever slows down in scores exactly zero however much traffic passes through it. The threshold sits above walking, cycling and car-park crawl but well below an urban 50 km/h limit (13.9 m/s), so ordinary through-traffic does not qualify either.

This runs **under** the admin allow-list (§4.7), not instead of it: it is what stops an approved area that happens to contain a through-road from spawning beside it.

#### Privacy: how `A` is computed without keeping traces

`A` needs exactly one fact per (cell, user): **when did this user last appear in this cell.** Nothing else. What shipped, against what was proposed:

| Proposed | Shipped |
|---|---|
| Backend-only collection keyed by cell, one small doc per (cell, hashed-uid) holding a single `lastSeenAt` — no coordinates, no sequence, no trajectory | **Yes.** `crownCellActivity/{cellKey}/recentUsers/{hash}`; the document body is `lastSeenAt` + `expireAt` and nothing else — no uid, no coordinate, no speed, no heading |
| Uid **HMAC'd under a rotating server-side salt** | **No.** Unkeyed, unsalted, length-prefixed **SHA-256** over `(cellKey, uid)` (`crownActivityUserHash`). Cell-scoped, so still unjoinable across cells; but recomputable by anyone holding the UID list |
| A **7-day TTL** deletes rows automatically | **Partial.** Both documents are *written* with a 7-day `expireAt`, and the sweeper `recursiveDelete`s any cell quiet for the whole 7-day window, taking its `recentUsers` sub-collection with it. But no Firestore **TTL policy** on `expireAt` exists yet (manual `gcloud` step, see below), so nothing deletes a stale row inside a cell that is still active |
| Backend-only in `firestore.rules`, no client read or write path | **Yes.** `allow read, write: if false` on both the cell and the sub-collection |
| `A` stored as an aggregate number on the cell doc; the only thing the spawner reads | **No.** Recomputed each pass from up to 200 `recentUsers` docs; the cell doc holds only `lastActivityAt` and `expireAt` |

> **The Firestore TTL policy on `expireAt` is a manual `gcloud`/console step and is not expressed in any committed file — not in `firebase/firestore.indexes.json`, not in `firebase.json`.** Writing an `expireAt` field is not the same thing as having a TTL policy, and only the former is in the repo (`spawnActivity.ts` sets it on both the cell doc and the `recentUsers` doc); whether the policy exists in the live project cannot be established from this repository at all. The one-time commands are `gcloud firestore fields ttls update expireAt --collection-group=crownCellActivity --enable-ttl` and the same for `--collection-group=recentUsers`, matching the pattern already documented for `messages`, `eventAttendance` and `pointsEconomyRateLimit`. Until they are run, the *only* thing deleting these rows is `crownHunt-sweepSpawns`, and it deletes exactly two things: `crownSpawns` documents whose `expiresAt` has passed, and whole `crownCellActivity` cells whose `lastActivityAt` is older than the 7-day window (**"quiet"** = no recorded sighting for `ACTIVITY_WINDOW_MS`), sub-collection included. It never queries `expireAt`, and it never expires an individual `recentUsers` row inside a cell that is still active. In a cell with continuous activity, a one-off visitor's row therefore persists past 7 days even though it stops contributing to `A` (`activityWeight` returns 0 outside the window). That is a retention gap, not a scoring bug, and it should be closed before the flag goes on.

**Honest caveat, unchanged and now more pointed:** a (cell, user, timestamp) row *is* location data at ~1 km resolution, even without a trace. It is the minimum required for distinctness, but with an unkeyed digest and the TTL gap above it should not be described internally as "anonymous" — pseudonymous is the accurate word. At larger scale the escalation path is a probabilistic distinct-count sketch (HyperLogLog), which removes per-user rows entirely at the cost of ~2% counting error — unnecessary overhead at 20–30 users, correct at 20 000. See **Q10**, which is now a question about code that exists rather than about a proposal.

### 4.3 Target crown count — and why logarithmic

> **STATUS: BUILT exactly as written** — `targetCrownCount()` in `crown-spawn-core.ts`, with `DENSITY_K = 1.5`, `MAX_CROWNS_PER_CELL = 5` and `MIN_ACTIVITY_FOR_SPAWN = 1`. A non-finite activity score also yields 0.

```
N_target(c) = min( 5, ceil( K · ln(1 + A(c)) ) )     K = 1.5
N_target(c) = 0   if A(c) < 1
```

| `A(c)` | `1.5·ln(1+A)` | `N_target` |
|---|---|---|
| 0.9 | — | **0** (below gate) |
| 1 | 1.04 | 2 |
| 2 | 1.65 | 2 |
| 3 | 2.08 | 3 |
| 5 | 2.69 | 3 |
| 10 | 3.60 | 4 |
| 20 | 4.57 | 5 |
| 27 | 4.96 | 5 |
| 50 | 5.90 | 5 (capped) |
| 100 | 6.92 | 5 (capped) |

**Why `ln` and not linear — this is the key fairness property.** Suppose a town centre has `A = 100` and a village has `A = 2`. Under a linear rule (`N = A/20`, say) the town gets 5 crowns and the village gets 0.1 → 0. Under a rule tuned so the village gets 1, the town gets 50 — an absurd carpet, and the map becomes a wall of icons in exactly one place.

The logarithm compresses that 50× activity ratio into a **2.5× crown ratio**. Busy areas get *more* crowns — that is correct, more people should find more to do — but not *proportionally* more. Concretely: **a member in a small town has a genuinely playable game.** That is the whole reason for the log, and it is the property to defend if anyone proposes "simplifying" the formula later.

The **cap of 5** binds from `A ≈ 27` upward. It exists so a city centre can never become a crown carpet regardless of how the activity metric behaves, and it makes the worst case bounded and testable.

**The `A < 1` gate is a safety rule, not a tuning rule.** Never spawn a crown where nobody already goes. A crown in an empty place is an instruction to drive somewhere unfamiliar, at an unknown hour, to a spot nobody has ever validated as safe to stop at. `A ≥ 1` means at minimum one person was there very recently, or several people were there in the last few days. The game **follows** the community; it must never **lure** it.

*Known wart:* because `ceil(1.5·ln 2) = 2`, `N_target` jumps 0 → 2 exactly at the gate. Reading: "if a place qualifies at all, it is worth a pair of crowns" — a single lonely crown is a weak reason to make a detour. Deliberate, but tunable (see **Q11**).

### 4.4 Replenishment — a Poisson process

> **STATUS: NOT BUILT. This subsection describes a design the code does not implement, and the difference changes a stated anti-farming property.**
>
> `runCrownSpawnPass` computes `deficit = N_target − N_current` and attempts **the whole deficit in that one pass** (`spawnScheduled.ts`). There is no `λ`, no `τ_spawn`, and no Poisson draw anywhere in the repo — `pickCrownRarity` is the only consumer of the RNG besides position sampling.
>
> Concretely, what actually happens:
>
> | | §4.4 as designed | `spawnScheduled.ts` as built |
> |---|---|---|
> | Refill of an emptied 5-crown cell | ~6.8 h on average | **one pass — at most 10 minutes** |
> | Tick interval | 15 min | **10 min** (`*/10 * * * *`; the 15-min job is the *sweeper*) |
> | Arrival process | `k ~ Poisson(λ·Δt)` | deterministic top-up to target |
> | Timing predictability | unlearnable by design | **a fixed 10-minute cron** |
>
> **The "Non-deterministic" property claimed in point 2 below does not hold.** A member can learn that crowns appear on the 10-minute boundary, and a cleared cell refills by the next one. What remains unpredictable is *rarity* and *position within the cell* (both RNG-driven), not *when*. Anything in §9 or §8.2 that leans on unpredictable spawn timing as an anti-farming measure is leaning on something that did not ship.
>
> Two things do still bound farming, and they are the honest defence today: **every crown expires** (§4.6), so no coordinate becomes a permanent fixture, and the per-user cap of `MAX_DAILY_SPAWN_CLAIMS = 20` spawned claims per UTC day is enforced inside the award transaction. Whether Poisson replenishment is still wanted on top of those is a live question — it is genuinely more work, and its main benefit (unlearnable timing) may not be worth it at 30 users.

Crowns are **not** topped up to target instantly. Instant refill makes the map static and farmable: collect, wait one tick, collect again in the same spot.

```
λ(c) = max( 0, N_target(c) − N_current(c) ) / τ_spawn        τ_spawn = 3 h
```

A scheduled sweep runs every **Δt = 15 min**; for each qualifying cell it draws `k ~ Poisson(λ · Δt)` and attempts `k` placements.

| Deficit | `λ` (crowns/h) | Mean wait for next crown |
|---|---|---|
| 1 | 0.33 | 3 h |
| 3 | 1.00 | 1 h |
| 5 | 1.67 | 36 min |

Refilling a fully emptied 5-crown cell takes `τ_spawn · H₅ = 3 · 2.283 ≈ **6.8 h**` on average (`H₅` = 5th harmonic number, because the rate falls as the deficit shrinks).

Two properties this buys us:

1. **Self-correcting.** The rate is proportional to the deficit, so cells naturally converge to target without any explicit controller, and an over-full cell (target dropped because activity decayed) simply stops spawning and drains via TTL.
2. **Non-deterministic.** A player cannot learn "crowns appear at :00 and :30". The unpredictability is part of the reward schedule (§9) *and* an anti-farming measure.

### 4.5 Placement — Poisson-disc / dart throwing

> **STATUS: BUILT** as `sampleCrownPosition` + `isFarEnoughFromAll` + `neighbourCrownCells`, with two differences: the attempt budget is **`MAX_SPAWN_SAMPLE_ATTEMPTS = 24`**, not 20, and **the second rejection test below does not exist** because the §4.7 safety mask was never built. A candidate is rejected on separation alone.
>
> One check shipped that is not described below: the sampled point is **re-keyed and compared against the cell it was drawn for**, and discarded on mismatch, so floating-point drift at a cell edge cannot emit a crown that belongs to the neighbouring cell and corrupt that cell's density accounting. Newly placed crowns are also appended to the `occupied` list *within* the same pass, so one run cannot create its own clump.

Within a cell, a candidate position is drawn uniformly, then **rejected** if:

- it is within `d_min = 150 m` of any *live* crown — checked across the **3×3 cell neighbourhood**, since the smallest cell dimension (572 m) exceeds 150 m, so a 3×3 window provably covers every crown that could conflict; or
- ~~it fails the placement-safety mask (§4.7)~~ — **not implemented**; see §4.7.

Up to 24 candidates are tried per placement; if all fail, the placement is abandoned until the next tick. It will almost never fail: at hexagonal packing a 0.64 km² cell holds

```
A_cell / (d_min² · √3/2) = 640 000 / (22 500 · 0.866) ≈ 33 crowns
```

before 150 m separation becomes impossible. We place at most **5**, roughly 15% of saturation, so rejection sampling converges in a handful of draws.

**Why separation at all?** Without it, uniform sampling clumps — that is what uniform sampling *does*. Three crowns 20 m apart is one stop for three rewards, which turns a "get out and explore" mechanic into a "park once and tap thrice" mechanic, and looks broken on the map. 150 m guarantees each crown is a genuinely separate stop, while staying well inside comfortable walking distance from a single parking spot for two adjacent ones.

### 4.6 Rarity, value and lifetime

> **STATUS: BUILT exactly as written** — `CROWN_RARITY_TABLE` in `crown-spawn-core.ts` holds all four tiers at these weights, KP values and TTLs, and `COLLECT_RADIUS_METERS = 75`. `pickCrownRarity` walks the tiers in table order against a uniform roll, falling back to `common` on a non-finite or out-of-range draw so a bad RNG value can never fail a placement.
>
> One guard shipped that is worth knowing when reading a crown document: `resolveCollectRadiusMeters` ignores a stored radius that is not a finite positive number `≤ MAX_STORED_COLLECT_RADIUS_METERS = 250` and falls back to 75 m. An oversized radius is the one corruption that would fail *open* — a wider geofence pays out to someone who was never there — so "wrong" can only ever mean a **smaller** gate.

| Rarity | Weight | KP | TTL | Collect radius |
|---|---|---|---|---|
| Vanlig (common) | 0.70 | 10 | 6 h | 75 m |
| Ovanlig (uncommon) | 0.22 | 25 | 12 h | 75 m |
| Sällsynt (rare) | 0.07 | 100 | 24 h | 75 m |
| Legendarisk (legendary) | 0.01 | 500 | 48 h | 75 m |

Weights sum to 1.00. **Expected value per crown:**

```
E[KP] = 0.70·10 + 0.22·25 + 0.07·100 + 0.01·500
      = 7.0 + 5.5 + 7.0 + 5.0
      = 24.5 KP
```

Each rarity tier contributes 5–7 KP to the expectation — the distribution is *balanced*, meaning no single tier dominates the economy. If legendaries were removed the expected value only drops 20%, so the economy does not depend on the jackpot; the jackpot exists for the feel (§9), not the maths.

**Rarity and TTL move together, deliberately.** A rare crown that expires in 6 hours is a rare crown nobody ever sees. A 48 h legendary is findable — someone will realistically pass it, and it can be worth planning a Saturday around. Meanwhile the 6 h common TTL is what keeps the map **churning**: the majority of crowns turn over four times a day, so the map looks different every time you open it, and no fixed location is farmable — the thing that made hand-placed points feel stale.

**Collect radius 75 m** sits mid-range in the existing 20–150 m admin bounds. Rationale: 75 m plus the accuracy buffer in `isWithinGeofence` (`radius + 0.5 · accuracy`) means a device reporting a typical urban 20 m accuracy has an effective 85 m radius — enough to collect from the parking spot you actually found rather than requiring you to walk to a precise pin, but far too small to collect from a passing road at any useful distance.

#### Sanity check: is this the right amount of game?

At **30 active members** and, say, 25 qualifying cells averaging `N_target = 3`:

- Live crowns at steady state: **~75**
- Mean TTL: `0.70·6 + 0.22·12 + 0.07·24 + 0.01·48 = **9.0 h**`
- Spawn rate: `75 / 9.0 ≈ **8.3 crowns/h ≈ 200/day**`
- Of which: ~14 rare/day, **~2 legendary/day** nationally
- Total KP *supply*: `200 · 24.5 ≈ **4 900 KP/day**` across 30 users

Nobody collects anything close to all of them — realistic collection is 15–25%. **Supply comfortably exceeds demand**, which is what we want: the map always has something on it, and the binding constraint on earning is the **per-user caps** (§5), not scarcity. Scarcity-limited economies produce hoarding, sniping and resentment; cap-limited economies produce "I've done my bit for today", which is the healthier stopping cue.

And because `A` drives `N_target`, **the whole thing scales itself.** More members → higher `A` → more crowns, logarithmically. No manual retuning per city.

### 4.7 Placement safety

> **STATUS: the policy mask below was NOT BUILT. Q4 was answered the other way — an admin-approved cell allow-list shipped instead.** Nothing in the repo consults a road class, a land mask, a property boundary or a school/hospital campus, and there is no `crownExclusionZones` collection. Do not cite the bullet list below as a live defence; it is the design that was rejected in favour of a human gate.

**What actually shipped: three independent gates, each sufficient on its own to produce zero spawns.**

| # | Gate | Where |
|---|---|---|
| 1 | The **`crownHuntSpawn` feature flag**, contract default **OFF** | `readFeatureFlag` at the top of `runCrownSpawnPass`, `recordCrownActivity` and `claimSpawn` |
| 2 | The **admin cell allow-list** — the candidate set *is* `crownSpawnCells where approved == true` | `spawnCells.ts`, `spawnScheduled.ts` |
| 3 | The **activity floor** (`A < 1` → target 0) and the **slow-sighting filter** (§4.2) narrowing placement *within* an approved area | `crown-spawn-core.ts`, `spawnActivity.ts` |

Gate 2 is the one that replaces `safeLocationConfirmed`, and it is deliberately structured so that approval is **the starting point of the query, not a filter applied afterwards** — an unapproved cell is invisible to the spawner however much activity it accumulates. `setSpawnCellApproval` mirrors `activatePoint`'s gate one level up: an explicit `safeAreaConfirmed: true` **literal** (so it cannot be satisfied by a default or a truthy accident) plus a note of ≥3 characters that lands in `adminAuditEvents`, so every area ever opened has a named admin and a reason attached. Auto-spawned crowns record `safeLocationConfirmed: false` explicitly — rather than omitting the field — plus `approvedCellBy`, so an incident review can tell at a glance which crowns had a person behind them and which had an algorithm inside a human-approved *area*.

Three properties of the allow-list worth knowing, because each closes a failure the naive version has:

- **Approval is re-checked at write time, inside a transaction.** A pass reads the allow-list once and may then run for minutes. Without the re-check, a revocation landing mid-pass would commit fresh crowns into an area an admin had just declared unsafe — and nothing downstream would remove them, because the sweeper only takes *expired* crowns, so they would stand for their full TTL (up to 48 h for a legendary). Both orderings are now safe.
- **Revoking is cheaper than approving, and takes effect immediately.** Revocation needs no confirmation literal (turning an area off must never be harder than turning it on) and it **deletes the cell's live crowns** in pages rather than waiting out their TTL — revocation is the lever an admin reaches for after a near-miss or a complaint.
- **Re-approval reseeds the round-robin cursor to the epoch sentinel**, so a re-approved area is repopulated on the next pass rather than waiting out a full cycle.

The `A ≥ 1` gate does much of the rest implicitly: we only place where people already stop, and collection requires being stationary within 75 m. But implicit safety is not audited safety — which is exactly why gate 2 exists rather than gate 3 alone.

**Still open even so:** the allow-list approves a **~1.1 × 0.57 km cell**, not a coordinate. An admin confirming a cell is confirming that *the area is broadly sensible to place in*, and the algorithm may still put a crown on a through-road inside it. The slow-sighting filter is what narrows that, and it is a proxy (nobody slows down there → it never scores) rather than a road-class check. An exclusion list at sub-cell resolution — the fourth bullet of the original mask — remains the obvious next increment if that proxy proves too coarse in practice.

<details>
<summary>The original policy-mask design, retained for reference (not implemented)</summary>

Auto-spawning removes the human from `activatePoint`'s `safeLocationConfirmed` gate. That gate exists for a reason, so it is replaced — not dropped — by a policy mask. A candidate is rejected if it falls:

- on a motorway or other limited-access carriageway, or on a slip road;
- in water, or outside the mapped land mask;
- on marked private property, or inside a school/hospital campus;
- inside an admin-maintained **exclusion list** (cells or radii an admin has permanently banned — accident blackspots, sites where neighbours complained, etc.).

</details>

### 4.8 Safe-stop placement — OpenStreetMap POIs

> **STATUS: BUILT for the MARKED-AREA pass.** The single-cell `runCrownSpawnPass` is unchanged (still random-in-cell); only the area pass (`runCrownAreaSpawnPass`, `crownSpawnAreas`) is POI-anchored.

Gate 3 (§4.7) narrows placement *within* an approved area, but only by a proxy: a random in-shape point can still land on a through-road that simply never scored activity. The marked-area pass now closes that at the source — a crown is placed **at a real safe stop**, not a random point inside the shape.

**The safe stops come from OpenStreetMap**, via the free [Overpass API](https://overpass-api.de/api/interpreter) (no key). For each active, safe-confirmed area we query its bounding box for three amenity classes — `amenity=parking`, `amenity=fuel`, `amenity=charging_station` (plus `man_made=charge_point` for chargers tagged that way), nodes and way centroids (`out center;`) — normalise each to `{ lat, lon, category }`, keep only the ones **inside the drawn shape** (not merely its bbox, reusing the §4-area point-in-shape helpers), and cache them at `crownSpawnAreaPois/{areaId}/pois/{poiId}` with `poiId = SHA-256('crownpoi', [areaId, lat, lon])`. The area document carries a `poiCount` and `poisRefreshedAt`, and `crownHunt.listSpawnAreas` surfaces `poiCount` so the admin UI can show "N safe spots found in this area".

| Piece | Where |
|---|---|
| Overpass query + parse/normalise + in-shape filter + POI-anchored sampler (pure) | `osm-poi-core.ts` (+ `osm-poi-core.test.ts`) |
| Overpass HTTP fetch, cache reconcile, ingest trigger, weekly refresh | `poiIngestion.ts` |
| POI-anchored placement in the area pass | `runCrownAreaSpawnPass` (`spawnScheduled.ts`) |

**Ingestion is gentle and resilient.** One Overpass query per area per refresh, a bounded timeout, and the endpoint is a configurable env var (`OVERPASS_ENDPOINT`, default the public interpreter — **no secret, no key**). It runs when an area is created active / activated / re-drawn (`onSpawnAreaWrittenIngestPois`, guarded against re-firing on its own `poiCount` write) and on a **weekly** scheduled refresh (`refreshAreaPois` — POIs rarely move). If Overpass fails or times out, the last cache is kept and `poisRefreshedAt` is left stale; ingestion failure **never** breaks the spawn pass, which only reads the cache.

**Placement (`samplePoiPlacement`)** walks the in-cell POIs in a seeded-random order and places the crown at the first one that clears the 150 m separation from every live crown, optionally jittered ≤ ~5 m (the jittered point is re-checked against the shape and cell, falling back to the exact POI point otherwise). Every other guard is preserved unchanged: in-shape, 150 m separation across **both** spawn sources, per-cell `targetCrownCount`, rarity/TTL, `collectMode`, and `areaId` tagging for draining. The 150 m rule also means two crowns can never stack on one POI, so placement spreads across distinct stops for free.

**SAFETY-FIRST fallback:** an area with **no cached POIs spawns nothing** (logged; counted as `areasWithoutPois`) rather than reverting to random placement — the entire point is that a crown only ever appears at a real place a member can stop.

**Attribution.** OSM data is ODbL, so **"© OpenStreetMap contributors"** must be shown wherever this data is surfaced — the admin area map's "N safe spots" and any app surface that names a crown's source. The credit lives in `contracts/localization` (`crownHunt.safeSpotAttribution`) and as `OSM_ATTRIBUTION` in `packages/shared`, mirroring the Trafikverket "Källa: Trafikverket" precedent (owed wherever the data is on screen, and only there).

---

## 5. Points economy (Kronpoäng, KP)

### 5.1 Earn table

**BUILT.** This table is implemented as `ECONOMY_RULES` in `functions/src/points/points-economy-core.ts`, and the KP / Limit / source columns below match it exactly. **That file is canonical** — if the two ever diverge, the code is right and this table is stale.

`crown_collect` is the one row that is *not* a rule key. Crowns are awarded by the Kronjakt domain in its own transaction and are folded into the daily cap afterwards by `points-onLedgerEntryCreated`, so a crown is never *clipped* by a cap but does consume the day's budget (§5.3). Every other row goes through the single award door, `awardEconomyPoints`. The **Badge tier milestone** row is still **proposed** — nothing writes `source: 'badge'`.

The **`Ledger source` column** needed no enum change, as predicted: every value in it was already a member of `POINTS_TRANSACTION_SOURCES` in `functions/src/points/points-core.ts` (and of the narrower `ACTIVE_POINTS_TRANSACTION_SOURCES`, which excludes the deprecated `future_crown_hunt`). That split was the point of §1.2 — new callers, existing primitives.

| Action | KP | Limit | Ledger source |
|---|---|---|---|
| `daily_open` | 5 × streak multiplier | 1/day | `system` |
| `live_session_1km` | 10 | **2/day** | `system` |
| `drive_5km` | 15 | **2/day** | `system` |
| `crown_collect` | 10 / 25 / 100 / 500 by rarity | 10 claims/day (existing cap) | `crown_hunt` |
| `event_attend_verified` | 50 | 1 per event | `event` |
| `event_host_success` (≥3 verified attendees) | 75 | 1 per event | `event` |
| `garage_first_car` | 25 | once, lifetime | `garage` |
| `incident_report_confirmed` | 15 | **3/day** | `system` |
| Badge tier milestone | 25 / 75 / 200 / 500 | once per tier, lifetime | `badge` |

### 5.2 Streak multiplier

```
m(s) = min( 1.7 ,  1 + min(s, 7) / 10 )        s = consecutive days opened
daily_open KP = round( 5 · m(s) )              round-half-up, integer KP
```

| Streak `s` | `m(s)` | KP |
|---|---|---|
| 1 | 1.1 | 6 |
| 2 | 1.2 | 6 |
| 3 | 1.3 | 7 |
| 4 | 1.4 | 7 |
| 5 | 1.5 | 8 |
| 6 | 1.6 | 8 |
| 7 | 1.7 | 9 |
| 8+ | 1.7 (cap) | 9 |

The `min(s,7)` and the 1.7 ceiling reach the cap at exactly the same point — the explicit `min(1.7, …)` is belt-and-braces so a future change to one term cannot silently uncap the other.

**The multiplier is deliberately feeble.** Maximum streak value is 9 KP/day; breaking a 200-day streak costs you **3 KP tomorrow**. This is intentional and is the ethical line on streaks (§9): a streak should be a small pleasant acknowledgement, never a hostage. Any future proposal to make streaks worth 10× should be rejected on these grounds.

### 5.3 Caps

| Cap | Value | Window | Window start | Status |
|---|---|---|---|---|
| Global earn cap | **300 KP** | Europe/Stockholm civil day | local midnight | **built** (`DAILY_POINTS_CAP`) |
| **Driving-derived** cap `D` | **400 KP** | Europe/Stockholm week (Mon–Sun) | local Monday midnight | **built** (`WEEKLY_DRIVING_POINTS_CAP`) |
| Crown claims | 10 | fixed UTC calendar day | `00:00:00Z` daily | **built** (pre-existing) |

> **CORRECTION — the economy windows are Europe/Stockholm, not UTC.** An earlier draft of this table specified UTC days and ISO-UTC weeks. The shipped engine deliberately does not do that, and the reasoning is in `stockholmDayKey`'s KDoc: Sweden is UTC+1/+2, so a UTC day never lines up with a Swedish one and the mismatch breaks **both** directions. Two opens at 01:30 and 02:30 local on the same Swedish summer day straddle UTC midnight, so UTC would pay two daily-opens and inflate the streak; two opens at 23:30 and 00:30 local are two consecutive Swedish days inside one UTC day, so UTC would pay once and silently break a streak the member actually kept. DST is resolved through the IANA zone database via `Intl.DateTimeFormat.formatToParts`, never by hard-coded offset arithmetic, so the 23- and 25-hour days come out right. The crown daily-claim cap predates the economy and still uses UTC days; that is a known, deliberate inconsistency, not a target.

**These are fixed calendar windows, not sliding ones.** A cap resets at the boundary; it is not a trailing 24 h / 7 d lookback. This is deliberate — a sliding window needs a per-earn timestamp scan, whereas a fixed window needs one counter document whose ID *is* the window, which is what makes the cap check cheap enough to run **inside** the award transaction (§5.5, and stage 16 of the §8.1 pipeline).

Implementers must not invent new boundary maths. For the **economy**, the helpers of record are in `functions/src/points/points-economy-core.ts` —

- `stockholmDayKey(instant)` → `YYYY-MM-DD`, the Europe/Stockholm civil day. Counter ID via `dailyTotalDocId`.
- `stockholmWeekKey(instant)` → the Monday-anchored local week, keyed by its **start date**, which is what lets the weekly cap avoid ISO week-number arithmetic entirely. Counter ID via `weeklyDrivingDocId`.
- `ruleLimitWindowKey(rule, dayKey, override)` → the per-rule limit-counter window (`local_day`, `event` or `forever`), used for `pointsRuleCounters`.

The older UTC helpers in `functions/src/crownHunt/crownhunt-core.ts` (`startOfUtcDay`, `startOfUtcWeek`, `utcDayKey`, `awardGuardWindowKey`) remain the definition of record **for Kronjakt's own crown-claim cap only**, and must not be used for economy windows.

So the weekly `D` counter should be keyed on `startOfUtcWeek`'s date, exactly as `awardGuardWindowKey('weekly', now)` already returns.

**Known consequence:** these boundaries are UTC, but the members are in Sweden (UTC+1/+2). A "day" therefore rolls over at 01:00 or 02:00 local, and the driving week resets late on Sunday evening local time. That is acceptable for a cap (it is a ceiling, not a scoreboard) but it is **not** acceptable for the daily-open streak, which a member will judge against their own calendar. Whether streaks and caps should share a boundary, or streaks move to `Europe/Stockholm`, is **Q14**.

**Bucket `D` (driving-derived)** = `live_session_1km` + `drive_5km` + `crown_collect`.

Everything else — daily open, events, garage, confirmed reports, badge milestones — sits **outside** `D` and is capped only by the 300/day global.

**Cap exemption:** badge tier milestones are **exempt from the 300/day global cap**. They are once-per-lifetime by construction, so they are already rate-limited, and clipping a member's 500 KP Platina award because they also had a good crown day would be an obviously wrong user experience.

**Calibration check — the 300/day cap is not arbitrary.** A maximal Kronjakt day is 10 crowns at expected value 24.5 KP = **245 KP**, plus 9 daily-open, plus one distance award ≈ **270 KP**. So a maximum honest day lands *just under* the cap: the cap is invisible to normal play, and only bites on a lucky day (one legendary alone pushes past it) or a day with an event on top. That is the correct place to put a ceiling — it does not punish the diligent, it truncates the extreme.

### 5.4 Why the weekly driving cap exists, and what it does

`D ≤ 400 KP/week` is the direct implementation of constraint **C3**.

Its effect: at an expected 24.5 KP/crown, the weekly budget covers roughly **13–16 crowns plus a handful of distance awards** — about **two heavy hunting days**. After that, **the marginal reward for another kilometre driven is exactly zero for the rest of the week.**

That is the whole point. State it plainly, including in-app:

> *There is no week in which driving more earns you more, past a modest amount. If you are driving to farm points, the system has already stopped paying you.*

Non-driving paths — attending meets, hosting, streaks, garage, confirmed incident reports, badge milestones — are **not** in bucket `D`. A member who never hunts a single crown can still progress steadily. That asymmetry is deliberate: the paths we most want to encourage (turning up, hosting, contributing) are the ones without a ceiling.

**Behaviour at the cap:** capped-out awards are **forfeited, not banked**. Banking would re-create the very incentive the cap removes (drive now, collect Monday). The client must show this honestly and calmly — *"Veckans körbudget är slut"* with the reset time — never as a silent failure and never with urgency framing. See **Q9**.

*Open question:* whether crowns belong in bucket `D` at all is **Q2**. Argument for: crowns are the strongest driving incentive in the system, so excluding them guts the cap. Argument against: crowns require *stopping*, and 400/week bites after two days, which some members will find tight. The doc assumes crowns **are** in `D`.

### 5.5 Idempotency

Every **new** award in §5.1 must carry a deterministic key derived only from server-trusted values. The key **is** the ledger entry document ID, so replay is a transactional no-op (`functions/src/points/ledger.ts`).

**Do not read that as a description of the current code — the one award that exists today does not satisfy it.** Two corrections:

- **The crown-claim ledger key is client-derived.** `claimLedgerIdempotencyKey(scopedKey)` wraps `scopeClaimIdempotencyKey(uid, idempotencyKey)` = `SHA-256(uid + ':' + clientKey)`, and `idempotencyKey` comes straight off the wire. Scoping by uid makes it unforgeable *across* users, but within one user the client picks the key. So it is the wrong exemplar for "server-trusted only" — the row below is marked accordingly.
  **This is not a live vulnerability**, and the reason is worth stating precisely so nobody "fixes" the wrong layer: double-award is prevented by the **server-derived guard documents** (`crownHuntAwardGuards`, `crownHuntDailyClaims`), created inside the award transaction from server-trusted values only. The ledger key provides *replay collapse*; the guard documents provide *award uniqueness*. The doc previously conflated the two. A client that varies its idempotency key gets a new ledger entry attempt and is then stopped by the guard, not by the key.
- **The ledger key is optional, so replay-safety is not universal.** `idempotencyKey` is optional in `PointsMutationParams`, and `ledger.ts` falls back to `ledgerRef.collection('entries').doc()` — an auto-ID. `adminAdjust` supplies no key, so **admin adjustments are not replay-safe today.** Any new caller must pass one.

Key shapes, by earn type (all rows **proposed** unless marked):

| Earn | Derived from |
|---|---|
| `daily_open` | uid + UTC day |
| `live_session_1km` / `drive_5km` | uid + drive/session ID + km bucket index |
| `crown_collect` | scoped claim key → `claimLedgerIdempotencyKey()` (**built**, but **client-derived** — see above; uniqueness comes from the guard docs, not this key) |
| `event_attend_verified` | uid + event ID |
| `event_host_success` | host uid + event ID |
| `garage_first_car` | uid (lifetime singleton) |
| `incident_report_confirmed` | report ID |
| Badge milestone | uid + badge key + tier |

Where a cap must hold under concurrency, it is enforced **inside the award transaction** via the `AtomicReadGuard` hook, against a counter document whose ID is derived from server-trusted values only. This is exactly the pattern `submitClaim.ts` already uses for `crownHuntAwardGuards` and `crownHuntDailyClaims`, and it is the reason those caps are not beatable by firing N concurrent requests with N different client idempotency keys. **Every new cap in §5.3 must use the same pattern.** A cap checked before the transaction is not a cap.

---

## 6. Event attendance verification

> **STATUS: BUILT.** §6 shipped as the `events.checkIn` callable (`functions/src/events/checkIn.ts`) writing the `eventAttendance` collection, with dwell + geofence evaluation and the risk pipeline in `points-economy-core.ts`. Both `event_attend_verified` and `event_host_success` are live rule keys, awarded by the `points-onAttendanceVerified` trigger. The RSVP proxy of §1.3 still exists alongside it.
>
> **Two design points below shipped differently from this draft — the code is canonical:**
> - **The award is not made by the callable.** `events.checkIn` only records evidence and flips `verified` false → true; `points-onAttendanceVerified` credits the points on that edge. This keeps the callable's failure modes away from the ledger.
> - **The raw samples are retention-bound.** §6.3 described them as scoped and owner-readable, which they are, but "few and scoped" is not "not retained". Each `eventAttendance` record carries an `expireAt` and a **90-day Firestore TTL** (`ATTENDANCE_EVIDENCE_RETENTION_MS`) — long enough to settle a dispute about the award, and no longer. Deleting the record **cannot re-open the award**: the ledger entry (whose document ID *is* the idempotency key), the per-event rule counter, and the `eventAttendanceCounts/{eventId}/counted/{uid}` host-tally guard all outlive it and none carries a TTL.

> *"How do you know someone is actually at the meeting — GPS?"* — Yes, with a dwell requirement, and with a deliberate bias toward being generous.

### 6.1 The rule

A member earns `event_attend_verified` for an event when **all** of the following hold:

| Condition | Value |
|---|---|
| Distance from event coordinates | ≤ **150 m** (accuracy-buffered, same `isWithinGeofence` helper) |
| Time window | `[start − 30 min, end + 30 min]` |
| Cumulative dwell inside the fence | ≥ **10 minutes** |
| Position samples inside the fence | ≥ **2**, separated by ≥ **10 minutes** |
| Attendance records | exactly **1** per (event, user) — deterministic document ID |
| Risk pipeline | the same §8 guard as a crown claim |

`event_host_success` (75 KP) fires for the host when the event accumulates **≥ 3 verified attendees**.

### 6.2 Why cumulative dwell rather than continuous

A stricter rule — *"you must be continuously inside the fence for 10 unbroken minutes"* — is easier to implement and easier to reason about. It is also wrong, for a reason that has nothing to do with cheating:

**GPS drops.** Underground car parks. Multi-storey garages. Industrial estates with tall sheds. Phones aggressively sleeping the location service to save battery. A member who genuinely attends a three-hour meet, but whose phone loses fix twice, fails a continuous rule and gets nothing — and they *cannot tell why*, because we will not expose the thresholds.

Cumulative dwell over a 10-minute-separated sample pair is the **humane** choice: it tolerates gaps, and it still requires real elapsed time on site. It is measurably weaker against a determined attacker — someone who drives past the venue twice, an hour apart, could in principle accumulate two in-fence samples. We accept that, for three reasons: the payoff is 50 KP (two crowns), the ±30-minute window bounds the drive-by opportunity, and **punishing honest members for their phone's battery optimiser is a worse failure than occasionally paying a drive-by 50 KP.**

The two-sample / ≥10-minutes-apart requirement is what stops the *cheapest* attack — a single ping while stopped at the traffic light outside.

### 6.3 Privacy

Position samples used for attendance are evaluated **in flight**. Only the derived result is stored: a boolean, the accumulated dwell seconds, and the sample count. **No trace, no coordinates, no timeline is retained.** The attendance record answers "did they attend" and nothing else.

### 6.4 Fallback when an event has no coordinates

`events-core.ts` permits events with `latitude`/`longitude` null (online meets, or a host who has not pinned the location). For those events, GPS verification is impossible and the system **will fall back to the existing conservative proxy**: a `going` RSVP on an event that reaches `completed`.

Precise location of that proxy, since it is split across three files and `badge-core.ts` is *not* where the counting happens: `badge-core.ts` holds only the thresholds (`FIRST_EVENT_THRESHOLD` / `FIVE_EVENTS_THRESHOLD`) and `qualifiedEventBadges()`; the actual counting is `recordEventAttendance()` in **`functions/src/badges/awards.ts`**, which increments `badgeProgress/{uid}.completedEventsAttended`, driven from `functions/src/events/eventLifecycle.ts`.

**As shipped, the fall-back is implicit rather than a branch.** `events.checkIn` loads the event's coordinates (teaser first, then `details/private`) and returns `event_not_checkinable` when there are none — as it also does for a draft or cancelled event. Nothing is awarded and nothing is recorded, so the RSVP proxy remains the only attendance signal for an unlocated event, exactly as intended. There is no code path that *reads* "this event is unlocated, therefore use the proxy"; the two mechanisms simply do not overlap.

The proxy path awards **badge progress but not `event_attend_verified` KP.** Otherwise an unlocated event becomes a free 50 KP for anyone who taps "going" — a much cheaper attack than any GPS spoof described in §8. Hosts should be nudged to pin a location, with the payoff made explicit: *pin the location and your attendees earn points.*

---

## 7. Badges (Märken)

> **STATUS: BUILT.** The ladders below are implemented in `functions/src/badges/` — `BadgeDefinition` carries `ladder` / `tier` / `metric` / `threshold`, and `badges/tierAwards.ts` credits the KP milestone through the ledger with `source: 'badge'` on a deterministic `badge_award_{key}` idempotency key. Awarding is trigger-driven from server-verified counters on the backend-only `badgeProgress/{uid}` document; there is deliberately **no callable**, so a tier cannot be requested by a client. This section is now a description of the system, not a proposal — keep it and the catalog in step.

### 7.1 Structure

Tiered badges use the ladder **Brons → Silver → Guld → Platina**, with a KP milestone bonus of **25 / 75 / 200 / 500**, credited via ledger source `badge` and exempt from the daily cap (§5.3).

Four rungs is the default, not an invariant. A ladder stops short where a fourth rung would be **unreachable** or **undesirable**, and exactly one does:

| Ladder | Rungs | Why it stops early |
|---|---|---|
| **Trogen** | 3 | A 365-day Platina rung is the loss-aversion hook §9 rules out (**Q6**). |

Every other ladder has all four — including **Samlare**, whose Platina rung became reachable when `MAX_VEHICLES_PER_USER` was raised 5 → 10 (2026-08).

All user-facing names are **Swedish**, matching the existing catalog convention. English names below are for internal reference and design briefs only; they never appear in the app.

### 7.2 The ladders

#### Kronjägare · *Crown Hunter*

| Tier | Requirement |
|---|---|
| Brons | 10 crowns collected |
| Silver | 50 |
| Guld | 250 |
| Platina | 1 000 |

*How to earn:* Collect crowns anywhere, any rarity. Counts lifetime.

*Icon design:* A stylised five-point crown resting inside an open compass rose — the crown is the reward, the compass is the search. Rendered as a flat silhouette so it reads at 24 dp. Tier is carried by the crown's metal and its jewel count: **Brons** a plain crown, one small jewel; **Silver** three jewels; **Guld** five jewels with the centre stone raised; **Platina** the gold form encircled by a thin laurel ring. The compass rose and crown geometry are **identical across all four** — only metal and jewel count change — so the four sit together as an obvious family in the badge grid.

#### Vägfarare · *Wayfarer*

| Tier | Requirement |
|---|---|
| Brons | 100 km lifetime |
| Silver | 500 km |
| Guld | 2 000 km |
| Platina | 10 000 km |

*How to earn:* Accumulated logged driving distance, lifetime.

*Icon design:* A ribbon of road narrowing to a horizon line, with a **milestone marker post** standing at the roadside — the classic European kilometre stone, not a race flag. Tier is the marker's metal plus the number of chevrons carved into it (1/2/3/4). **Explicitly forbidden in this icon:** speedometers, tachometers, needles, chequered flags, motion blur, speed lines. The visual language is *distance travelled and places seen*, not *velocity*. Get this wrong in the art and it contradicts C1 more loudly than any formula could.

*On C3:* these are lifetime milestones, not per-kilometre payouts. The KP rate for driving will be set entirely by the capped `drive_5km`/`live_session_1km` awards in §5.1, so once the weekly budget is spent, further kilometres advance the badge counter but pay **zero KP**. (The cap and the awards did ship together, as required: `drive_5km` is awarded by the `points-onDriveSaved` trigger on `rides/{rideId}`, `live_session_1km` by `points/liveDistance.ts` called inline from `live.updatePosition`, and both are `driving: true` so both are charged against `WEEKLY_DRIVING_POINTS_CAP`. Note the distance for `live_session_1km` is **server-measured** from the session's own accumulated total — no client-supplied distance, and no speed is read, stored or rewarded.) The badge is a record of where you have been, not a meter that keeps ticking. Whether even that is too much encouragement is **Q3**.

#### Träffräv · *Meet Fox*

| Tier | Requirement |
|---|---|
| Brons | 1 verified event |
| Silver | 5 |
| Guld | 25 |
| Platina | 100 |

*How to earn:* Attend community meets. Verified attendance (§6) preferred; the RSVP+completed proxy counts toward the badge where the event has no coordinates.

*Icon design:* A fox curled around a map pin, tail wrapped over the pin's base — a little cheeky, which suits meet culture, and instantly distinct in silhouette from every other badge in the set. Tier is metal plus the number of white tail-tips (1/2/3/4). The Brons and Silver rungs deliberately mirror the thresholds of the existing `first_event` (1) and `five_events` (5) badges.

#### Trogen · *Faithful*

| Tier | Requirement |
|---|---|
| Brons | 7-day streak |
| Silver | 30-day streak |
| Guld | 100-day streak |

*How to earn:* Open the app on consecutive days. **Three tiers only** — see **Q6**.

*Icon design:* A calm flame at the centre of a circular date-ring, the ring's segments filling clockwise with tier. Not an aggressive fire — a hearth flame, warm rather than urgent. Tier is the flame's metal and how much of the ring is filled (a third / two-thirds / complete). **Copy rule:** the badge and any related notification must never use loss framing ("don't lose your streak!"). It is an acknowledgement, not a threat.

#### Konvojledare · *Convoy Leader*

| Tier | Requirement |
|---|---|
| Brons | 1 convoy led |
| Silver | 5 |
| Guld | 25 |
| Platina | 100 |

*How to earn:* Be the initiating member of a convoy that completes with at least one other participant.

*On the Brons rung:* leading your **first** convoy is a milestone worth marking, in the same way `first_event` and `garage_created` mark a member's first meet and first car — and it gives the ladder the fourth rung §7.1 asks for without inventing a new upper threshold. The 5 / 25 / 100 above it are unchanged.

*On "completes with at least one other participant":* all three parts are enforced, and the last two are what make the ladder unfarmable. A convoy is **born active** with `startedAt` already set (`convoy-core.ts::buildConvoyDocument`), so crediting on start would let a member mint a rung per tap by creating and abandoning solo convoys. Credit therefore fires on the transition into `endedAt`, and only when a member other than the owner has `inviteStatus: 'accepted'` — an invite that was never answered, or declined, does not count.

*Icon design:* Three car silhouettes in staggered convoy formation, viewed from a low three-quarter angle, the lead car carrying a small pennant on its aerial. Tier is metal plus the pennant's detail. The **staggered** formation is the point — it reads as *travelling together*, not *racing*, which two side-by-side cars would.

#### Samlare · *Collector*

| Tier | Requirement |
|---|---|
| Brons | 1 car in garage |
| Silver | 3 cars |
| Guld | 6 cars |
| Platina | 10 cars |

*How to earn:* Complete vehicle profiles in Mitt garage.

*Four rungs, tuned to reality:* `MAX_VEHICLES_PER_USER` is **10** (`functions/src/garage/garage-core.ts`, enforced inside a transaction in `functions/src/garage/manageVehicle.ts`), raised from 5 in 2026-08. Platina now sits at the cap, so the full ladder is reachable. Because most members own 1–2 cars, Brons stays at the first car (inclusive entry rung) while Guld (6) and Platina (10) are deliberately aspirational.

*Icon design:* A roller garage door raised to two-thirds, with the noses of cars visible in the darkness behind it. Tier is the number of visible noses (1/2/3 — abstracted, not literal counts). Warm interior light spilling from under the door; this is the "home" badge of the set and should feel like it.

#### Säsongsmästare · *Season Champion*

| Tier | Requirement |
|---|---|
| Brons | 1 season won |
| Silver | 3 seasons won |
| Guld | 5 seasons won |
| Platina | 10 seasons won |

*How to earn:* Finish **first** in a Kronjakt season (a calendar month). The counter is a LIFETIME tally of first-place finishes maintained by the season-rollover finalizer (`functions/src/crownHunt/seasonRollover.ts`), which is the authoritative server writer — never a client number. It only ever grows, so the badge is permanent even after a season resets.

*This is the SCALING championship ladder, and it is distinct from the per-season podium badges.* The podium badges (`sasong_guld` / `sasong_silver` / `sasong_brons`) mark a single season's top three and are awarded by rank; Säsongsmästare instead counts how many times a member has WON, so a repeat champion visibly climbs Brons → Platina. The exact number of championships is also surfaced verbatim (`seasonsWon`) in the Kronjakt read contract (`packages/shared/src/crown-hunt.ts`), so the profile and the leaderboard can show "N-time champion" at a glance while the badge shows the nearest tier.

*Icon design:* A five-point crown resting inside an open laurel wreath — the Kronjägare crown silhouette cradled by two curved laurel branches meeting at the base. The wreath is what distinguishes a championship from a mere collection count. Tier is the ring treatment as usual; no speed imagery, ever.

### 7.3 The five existing badges

`first_event`, `five_events`, `helpful_member`, `early_member`, `garage_created` are **retained unchanged.** Badge award documents use the badge key as their document ID (`users/{uid}/badges/{badgeKey}`) and already exist on real accounts; removing a key orphans a member's award history for no gain.

`helpful_member` (admin-awarded) and `early_member` (a permanent historical marker) have no ladder equivalent and stand alone. `first_event`/`five_events`/`garage_created` overlap conceptually with the Brons/Silver rungs of Träffräv and Samlare. Recommendation: **grandfather them** — keep the awards, keep them visible on profiles, and lead the UI with the ladders. **Q5** asks Seb whether the legacy three should be visually demoted to a "tidiga märken" section rather than shown alongside the ladders.

### 7.4 Icon design system

So the set reads as one family rather than six unrelated drawings:

| Rule | Value |
|---|---|
| Master size | 96 × 96 dp, 1:1, exported at 1×/2×/3× |
| Safe padding | 8% on all sides |
| Legibility floor | **must** be identifiable as a silhouette at 24 dp |
| Palette | Flat two-colour base + one metal accent per tier |
| Tier metals | Brons `#A5682A` · Silver `#B4B7BC` · Guld `#D4A017` · Platina `#DDE3EA` + subtle cool sheen |
| Tier ring | A thin outer ring in the tier metal; same geometry on every badge |
| Motion | None. No speed lines, no blur, no motion arcs anywhere in the set. |
| Locked state | Same silhouette at 30% opacity, greyscale, with the next threshold shown as text below — **the next rung must always be visible** (§9) |

Icon identifiers follow the existing `badge_*` convention in `badge-core.ts` (e.g. `badge_kronjagare_guld`).

---

## 8. Anti-abuse: one pipeline for every geo-earn

Crown claims, event attendance, and any future location-based earn go through **the same guard**. Divergent copies of a security check are how one of them silently rots, so the shared modules are imported, not duplicated: `events/checkIn.ts` and `crownHunt/claimSpawn.ts` both call `isPositionFresh`, `isPlausibleJump` and `isValidCoordinate` from `crownHunt/crown-hunt-geo.ts` and `evaluateClaimRisk` from `crownHunt/crown-hunt-risk.ts` — the same functions, at the same thresholds, that `submitClaim.ts` uses. Anything new that accepts a coordinate must do the same.

### 8.1 Stages

**Read the Status column before implementing against this table.** It is a target-state pipeline, not an inventory of what runs today. **Three stages (13, 14a, 14b) are scored by a rule that no input ever populates**, so each contributes exactly zero risk to every claim ever made; treating any of them as a live defence would badly overstate what the system actually stops (§8.2). 14a is the subtlest of the three, because its rule *is* new and *is* wired server-side — only the client half is missing.

**There are now three claim paths through this pipeline, not one**, and they do not carry identical checks:

| Path | Callable | Claim record / risk record | Daily cap |
|---|---|---|---|
| Hand-placed crowns | `crownHunt.submitClaim` | `crownHuntClaims` / `crownHuntClaimRisk` | `MAX_DAILY_SUCCESSFUL_CLAIMS = 10` |
| **Auto-spawned crowns** | `crownHunt.claimSpawn` | `crownSpawnClaims` / **`crownSpawnClaimRisk`** | **`MAX_DAILY_SPAWN_CLAIMS = 20`** |
| Event attendance | `events.checkIn` | `eventAttendance` | n/a (per-event) |

Where a stage below differs by path, the footnote says so. The two crown paths share every geo and risk primitive by import — `evaluateClaimRisk` at the same 60-point threshold, the same `isPositionFresh` / `isPlausibleJump` / `isWithinGeofence` — and diverge only in the stationary gate (note ²), the mock-location signal (note ⁴) and the cap (note ⁵).

| # | Stage | Rule | Failure | Status |
|---|---|---|---|---|
| 1 | Auth + App Check | Signed in, App Check enforced | reject | **built** |
| 2 | Account state | Suspended/deleted earn nothing | `not_eligible` | **built** ¹ |
| 3 | Schema | Strict parse; non-finite/negative speed **rejected** | `invalid-argument` | **built** |
| 4 | Idempotency replay | Scoped key already seen → replay stored result | replay | **built** |
| 5 | Freshness | Position age ≤ **60 s** | `position_too_old`, +35 risk | **built** |
| 6 | Server-side distance | Haversine, server-computed | client distance never read | **built** |
| 7 | Geofence | `d ≤ radius + 0.5 · accuracy` | `outside_geofence` / `outside_radius` | **built** ⁶ |
| 8 | Stationary gate | Speed ≤ **2.0 m/s** sustained (crowns) | `moving_too_fast` / `must_be_stationary` | **partial** ² |
| 9 | Plausible jump | Implied speed vs last trusted position ≤ **130 m/s** | +40 risk | **built** |
| 10 | Accuracy | ≤ **50 m** | +10 risk | **built** |
| 11 | Attempt rate | ≥ 4 attempts/min | +25 risk | **built** |
| 12 | Success velocity | ≥ 5 awards / 5 min | +15 risk | **built** |
| 13 | Fence-edge probing | ≥ 3 edge attempts/hour | +20 risk | **PLANNED** ³ |
| 14a | Mock location (self-reported) | `isMockLocation === true` | **+60 risk** (= threshold) | **built, spawn path only** ⁴ |
| 14b | Device attestation | Play Integrity / App Attest | +40 risk | **PLANNED** ⁴ |
| 15 | **Risk threshold** | Score ≥ **60** → `risk_review`, **zero KP** | recorded, not awarded | **built** |
| 16 | Caps | Enforced **inside** the award transaction | `cap_reached` (clipped to headroom) / `limit_reached` | **built** ⁵ |
| 17 | Ledger | Append-only, idempotency key = entry ID | replay-safe | **built** |

¹ Suspended and deleted accounts do resolve to `not_eligible`, but *entitlement* is currently bypassed in `shared/memberGating.ts` — every non-suspended member passes.

² **The two crown paths now differ, and only one of them still has the hole.** Hand-placed claims (`submitClaim.ts`) run at **`MAX_CLAIM_SPEED_MPS = 1.4`** against a **single reported speed sample**, and a claim that omits `speedMetersPerSecond` entirely still passes — see C2 gap 1 and **Q16**. Auto-spawn claims (`claimSpawn.ts`) close both holes: **`MAX_COLLECT_SPEED_MPS = 2.0`** applied to **two** fixes at least `MIN_DWELL_SECONDS` apart, *and* a **server-derived** speed computed from those two positions and the elapsed time — which needs no client cooperation, so omitting the field no longer buys anything. That server-derived check is the pattern Q16 should adopt for `submitClaim` too.

³ **Scored but never triggered, on *both* crown paths.** `crown-hunt-risk.ts` has the `geofenceEdgeAttempts >= 3 → +20` rule, but `submitClaim.ts` passes `geofenceEdgeAttempts: 0` as a literal (`// legacy TODO: geofence-edge counting`) and `claimSpawn.ts` passes the same literal. Nothing counts edge attempts, so this stage contributes zero risk for every claim ever made. Implementing it means adding the counter, not the rule.

⁴ **Split, because one half now ships and the other still does not.**

**14a — mock location: the SERVER rule is built; nothing populates it yet.** `claimSpawn` accepts `isMockLocation` (Android's `Location.isMock`) and passes it to `evaluateClaimRisk` as `mockLocationReported`. The rule scores `MOCK_LOCATION_SCORE = RISK_REVIEW_THRESHOLD` — i.e. **exactly 60** — so a reported mock location **on its own** would send the claim to `risk_review` with zero KP. It is deliberately **one-way**: only `true` scores, and `false` is treated identically to absent, because a spoofing client would simply not set it.

**But grep before believing it fires.** `isMockLocation` has **no Android caller** — `grep -rn "isMock" apps/android` returns nothing, and the client has no auto-spawn support at all (see the §4 banner). So the field is always absent today and this stage contributes zero risk to every claim, exactly like stage 13 and stage 14b. It is a rule waiting for a client, not a live defence, and it must not be counted as one until the Android claim path sends the flag.

Two further limits for when it *is* wired. It is **client-supplied**, so it will catch the careless (a mock-location app left running, a debug build) and nobody who is actually trying. And **`submitClaim` does not pass it at all** — `mockLocationReported` is optional on `RiskSignals` precisely so the older path scores exactly as before, which means hand-placed points have no mock-location defence even in principle. Wiring it there is a small change and an obvious follow-up.

**14b — device attestation: still PLANNED, unchanged.** The `platformIntegrityPassed === false → +40` rule exists on both paths, but the value comes straight from the *client request body* (`platformIntegrityPassed: z.boolean().nullable().optional()` in both schemas) and **no Android code sends it** — the field has zero non-test callers in the repo. So today it is always `null`, and even once the client does send it, an attacker simply omits it or sends `true`. **This is not a device-integrity check until a real attestation token is verified server-side against Play Integrity / App Attest.** Until then it must not be counted as a defence.

⁵ Built for **three** separate crown caps plus the KP economy caps, all read-and-incremented inside the award transaction:

- `MAX_DAILY_SUCCESSFUL_CLAIMS = 10`/UTC day on hand-placed claims (`crownHuntDailyClaims`), plus the per-point repeat rule via `crownHuntAwardGuards`;
- **`MAX_DAILY_SPAWN_CLAIMS = 20`/UTC day on spawned claims (`crownSpawnDailyClaims`)** — a **separate** counter on purpose, because the two are different economies (curated 1–1 000 KP points vs a 10/25/100/500 spawn table) and sharing one would mean a busy hunting afternoon silently locked a member out of an admin's event point;
- the §5.3 KP economy caps: `pointsDailyTotals` (300/day global) and `pointsWeeklyDriving` (400/week driving-derived), plus `pointsRuleCounters` for the per-rule limits, all via `creditPointsResolved`.

The spawn path additionally enforces its **once-globally** rule in the same transaction: the read guard re-reads the crown document and the write phase flips it to `status: 'claimed'` with `expiresAt` set to the claim instant, so concurrent taps serialise on the crown and exactly one wins. The pre-transaction status read is a fast path, never the authority. Setting `expiresAt` to *now* rather than leaving the rarity TTL is what makes the client read rule (`status == 'live' && expiresAt > request.time`) hide a taken crown immediately instead of leaving it on the map until the sweeper arrives.

⁶ The spawn path checks the geofence **twice in one evaluation** — both the current and the previous fix must be inside the accuracy-buffered radius — because radius and dwell are decided together in `evaluateStationaryCollection` so the two cannot drift apart. Its failure code is `outside_radius`, not `outside_geofence`.

Risk **score and reasons are written to backend-only collections** — `crownHuntClaimRisk` for hand-placed claims and **`crownSpawnClaimRisk`** for spawned ones — and never returned to a client. Firestore rules cannot redact fields per-read, so separation is by collection, not by field (`allow read, write: if false` on both). Note the asymmetry in *when* a risk record is written: on both paths a `risk_review` rejection always writes one, and an **awarded** claim writes one only when `riskScore > 0`, so a clean claim leaves no risk document at all. Thresholds are never exposed either — a client that can see the threshold can tune against it.

Note the deliberate hardening already in `crown-hunt-geo.ts`: the legacy port treated an invalid speed as *safe*, which made the stationary gate bypassable with `speed = -1`. The current code treats non-finite and negative speeds as **unsafe**. Preserve that.

Two separate implausibility bounds exist and both are correct:

- **130 m/s** in `isPlausibleJump` — an anti-teleport bound for a *single claim*, deliberately generous so it only catches physically impossible movement.
- **55.6 m/s** in `totalDistanceMetres` — a per-segment *distance-integrity* filter, excluding GPS glitches from a drive total.

Different jobs. Do not unify them.

### 8.2 Threat model, honestly

**What we do stop:**

- Replay and double-submission — the scoped idempotency key **is** the document ID.
- Concurrent double-award — deterministic guard documents whose IDs derive from server-trusted values, created inside the award transaction. This specific attack (N concurrent requests with N distinct client keys) *works* against a pre-transaction check and is the reason for the `AtomicReadGuard`.
- Client-computed anything — distance, balance, eligibility are all server-derived.
- Collecting from outside the fence, or while moving.
- Stale/replayed position payloads (60 s freshness).
- Obvious automation (attempt-rate and success-velocity signals).
- Coarse teleportation.

**What we can make expensive but cannot prevent:**

- A rooted or jailbroken device running a system-level mock-location provider that feeds a *plausible, smooth, correctly-timed* track. Every signal above is satisfiable by a good simulator — including the spawn path's two-fix dwell check, which a simulator satisfies by simply holding a position for four seconds. Play Integrity and App Attest **would** raise the cost to "a device you are willing to burn" — a real deterrent at our scale, though never a wall. Neither is integrated today (stage 14b, note ⁴).

  A mock-location **rule** now exists on the spawn path and would score straight to review (stage 14a) — but no client sends the flag, so it fires for nobody today. The honest statement is therefore unchanged from the previous draft: **there is currently nothing raising the cost of a mock-location provider**, on either crown path. What shipped is the seat, not the occupant.
- Hardware GPS simulators. Nothing app-side touches these.
- Account sharing / one person collecting on a friend's phone.

**What we deliberately do not try to stop:**

- A real person actually driving to real crowns. That is the product working.

**The honest conclusion, and the reason not to over-invest in detection:**

> Because the caps in §5.3 are low, **a perfect GPS spoofer's maximum weekly extraction is identical to a diligent honest member's**: 400 KP of driving-derived earnings and 300/day overall. Spoofing buys *convenience*, not *advantage*. Combined with the fact that KP have **no cash value, no purchase path and no pay-to-win effect**, the rational incentive to spoof is close to zero.

Caps are the real anti-abuse mechanism. The risk pipeline exists to catch the careless and to give admins evidence, not to win an arms race we would lose. Building an ML anomaly detector before there is anything worth stealing would be effort spent in the wrong place.

**This calculus flips completely if KP ever redeem for anything of value** — merchandise, discounts, event tickets, subscription time. At that moment the caps stop being an economic defence and the entire threat model needs re-doing. See **Q7**. Please do not add a redemption store without revisiting this section.

**Detection tail:** a nightly aggregate job flags accounts whose collection geometry or cadence is statistically implausible (e.g. crowns collected at a rate or spatial spread no vehicle pattern explains) for **admin review — never automatic suspension**, matching the existing rule in `crown-hunt-risk.ts`.

---

## 9. Engaging without being exploitative

### What makes this engaging

| Mechanic | Why it works |
|---|---|
| **Variable-ratio reward** (rarity) | The single strongest engagement driver known. Unpredictable payoff size sustains interest where a fixed payoff does not. Here it is bounded by a 10-claim daily cap, so the loop has a built-in end. |
| ~~**Poisson spawning**~~ | ~~You cannot learn the schedule, so the map is worth checking.~~ **Not built** — the spawner runs on a fixed 10-minute cron and tops straight up to target (§4.4), so the schedule *is* learnable. What stays unpredictable is which rarity appears and where in the cell, plus the churn from 6–48 h TTLs. Weaker than intended, and worth knowing before citing this row as a live engagement mechanic. |
| **Streaks** | Low-cost daily re-entry ritual — capped at ×1.7, so it is a nudge, not a hook. |
| **Tiered progression** | Long-horizon goals that survive the novelty. The one mechanic still working in month six. |
| **Near-miss visibility** | Crowns are shown on the map with rarity colour and remaining TTL **before** collection. Seeing a legendary you did not reach is a real, honest near-miss, and it is motivating. |

### Where the line is, and why we hold it

| Red line | Rationale |
|---|---|
| **No pay-to-win.** KP cannot be bought, ever. | The moment points are purchasable, every badge becomes a receipt instead of an achievement. |
| **No fabricated scarcity or fake near-misses.** No "someone just took this crown!" unless it is true. | Manufacturing regret is manipulation. Real near-misses only. |
| **No energy, lives, or timers you can pay to skip.** | Pure friction-for-monetisation. |
| **No notification that could plausibly cause someone to start driving.** No "legendary crown expires in 20 minutes!" push. TTL countdowns are **in-app only**. | This is the most important line in the document. A push notification with a countdown and a map pin is, functionally, an instruction to drive somewhere in a hurry. We will not build one. |
| **No leaderboard ranked by distance, speed, or driving volume.** | Ranking by driving is ranking by how much you drove. See C1 and C3. |
| **No streak loss-aversion copy.** Never "you're about to lose 47 days!" | Streaks are worth ≤9 KP/day precisely so they can never justify this framing. |
| **Quiet hours** on all gamification notifications; every one individually mutable. | Nobody should be woken by a game. |
| **The app must be fully usable by someone who never opens the map.** | Gamification is an addition to the community product, not a tax on it. If a member's experience degrades because they do not play, the design has failed. |

**On the streak, one more time:** it is capped at ×1.7 (max 9 KP/day) *on purpose*. If a member breaks a 300-day streak, they lose three points tomorrow. The streak should be a small warm acknowledgement that you showed up. It should never be a reason to open an app you did not want to open.

---

## 10. Worked example — one plausible week

**Anna**, an average member. Enters the week on a 4-day streak. Attends one Saturday meet, does a bit of casual hunting near home and on the way to work, reports one road hazard.

| Day | Daily open | Crowns | Distance | Other | Day total |
|---|---|---|---|---|---|
| Mon | streak 5 → **8** | 2 common → **20** | 2× live 1 km → **20** | — | **48** |
| Tue | streak 6 → **8** | — | — | hazard report confirmed **15** | **23** |
| Wed | streak 7 → **9** | 1 common + 1 uncommon → **35** | — | — | **44** |
| Thu | streak 8 (capped) → **9** | — | — | — | **9** |
| Fri | *missed — streak resets* | — | — | — | **0** |
| Sat | streak 1 → **6** | 2 common + **1 rare** → **120** | 2× drive 5 km **30** + 1× live 1 km **10** | verified meet **50**, Kronjägare Brons **25** | **241** |
| Sun | streak 2 → **6** | 1 uncommon → **25** | 1× drive 5 km → **15** | — | **46** |
| | **46** | **200** | **75** | **90** | **411 KP** |

**Cap check — daily (300 KP):** Anna's biggest day is Saturday at 241 KP. Under the cap, and she never sees it. Note the Brons badge bonus (25) is cap-exempt anyway. **The cap is invisible to normal play** — exactly as intended.

**Cap check — weekly driving `D` (400 KP):** `D = 200 (crowns) + 75 (distance) = 275 KP`. Well under. **An average member never meets the driving cap.**

### Contrast: a heavy hunter meeting the cap

**Björn** hunts hard from Monday.

| Day | Crowns | Distance | `D` this day | Running `D` | Awarded |
|---|---|---|---|---|---|
| Mon | 7 common + 2 uncommon + 1 rare = **220** | 2 live + 2 drive = **50** | 270 | 270 | 270 ✓ |
| Tue | 8 common + 2 uncommon = **130** | 2 live + 2 drive = **50** | 180 | 450 → **clipped to 400** | **130** (50 forfeited) |
| Wed–Sun | any amount | any amount | — | 400 (full) | **0 driving KP** |

Monday's day total is `8 + 220 + 50 = 278` — under the 300 daily cap, though a legendary instead of the rare would have pushed him to 678 and been clipped to 300.

From Tuesday afternoon onward, **Björn earns nothing further from driving that week.** Non-driving progress still available to him across Wed–Sun:

| Source | Rate | 5 days |
|---|---|---|
| Saturday meet | 50, once | 50 |
| Hosting | 75, once | 75 |
| Streak | up to 9/day | 45 |
| Confirmed reports | 15 KP × **3/day** (§5.1) = 45/day | 225 |
| | | **≈ 395 KP** |

That is close to a full second week's driving budget, earned without driving a metre — which is the asymmetry §5.4 is built to create.

> **Implementation note:** Tuesday's row (award 130 of 180, forfeit 50) is a **partial clip**, which the current `ledger.ts` primitives cannot express — see the gap table in §1.2. Either partial clipping lands first, or Tuesday's award is rejected outright and the worked example above is wrong about what a member would actually see.

The message the system sends is precisely the intended one: **turn up, host, contribute — the driving is capped and always was.**

---

## 11. Data model additions (indicative sketch)

Names are indicative; the implementing slices own the final shapes and the contract updates. All new collections are **backend-only** in `firestore.rules` unless stated.

> **STATUS: mostly BUILT, and the shipped names differ from this sketch.** The table below has been rewritten to the collections that actually exist in `firebase/firestore.rules`; the "Sketched as" column records what this document originally proposed, because several of the old names are still cited elsewhere in the repo's older notes.

| Collection | Purpose | Access | Sketched as |
|---|---|---|---|
| `crownSpawns/{spawnId}` | **Auto-spawned crowns.** A *separate* collection, not an extension of `crownHuntPoints` — mixing them would have made every admin safety guarantee optional | members read `status == 'live' && expiresAt > now`; no client writes | `crownHuntPoints` + `rarity`/`expiresAt`/`cellId`/`spawnedBy` |
| `crownSpawnCells/{cellKey}` | **Admin allow-list** of areas the spawner may place in (§4.7) | admin read; no client writes | — (this is Q4's answer) |
| `crownCellActivity/{cellKey}` | `lastActivityAt`, `expireAt`. **`A` is not stored here** — it is recomputed per pass | backend only | `crownCells/{cellId}` w/ stored `A` |
| `crownCellActivity/{cellKey}/recentUsers/{hash}` | Single `lastSeenAt` + `expireAt` per distinct user; cell-scoped SHA-256 ID, no uid, no coordinate | backend only | `…/presence/{hmacUid}` |
| `crownSpawnClaims/{scopedKey}` | Every spawn claim attempt; ID = namespaced SHA-256 of (uid, client key) | owner reads own | — |
| `crownSpawnClaimRisk/{scopedKey}` | Risk score + reasons for spawn claims | backend only | — |
| `crownSpawnDailyClaims/{derivedId}` | Per-user/per-UTC-day spawn-claim counter (cap 20) | backend only | — |
| `pointsDailyTotals/{derivedId}` | Global 300/day counter, transactionally enforced | backend only | `pointsDailyCounters` |
| `pointsWeeklyDriving/{derivedId}` | Bucket `D` 400/week counter | backend only | `pointsWeeklyDrivingCounters` |
| `eventAttendance/{eventId}_{uid}` | Verified boolean, dwell seconds, sample count — **no trace** | owner reads own | as sketched |
| `badgeProgress/{uid}` | **Existing.** Extend with per-ladder lifetime counters | backend only | **still unbuilt** |
| ~~`crownExclusionZones/{id}`~~ | Admin-maintained no-spawn areas | — | **never built** (§4.7) |

**Two scheduled jobs shipped, not one**, and neither matches the sentence this section used to carry:

- `crownHunt-spawnCrowns`, **every 10 min** — recomputes `A` per approved cell, derives `N_target`, and tops the cell up to it. It does **not** expire crowns and does **not** draw Poisson arrivals (§4.4).
- `crownHunt-sweepSpawns`, **every 15 min** — deletes crowns past `expiresAt` (oldest-first, paged, ≤1 000/run) and `recursiveDelete`s activity cells quiet for the whole 7-day window. Deliberately **not** feature-flag gated, so turning the engine off still lets placed crowns age off the map.

Both pin `maxInstances: 1` and `concurrency: 1`: the spawner reads a cell's live neighbourhood and then writes into it, so two concurrent passes would each place crowns the other could not see and violate the 150 m separation rule without either run doing anything wrong.

The nightly job that recomputes badge ladder counters and runs the §8.2 anomaly pass is **still unbuilt**.

---

## 12. Open questions for Seb

These need a product call before implementation locks in. Ordered by how much rework the answer causes.

**Five of these were overtaken by PR #570.** Q4 and Q11 are settled by what shipped; Q1 and Q16 are half-settled (answered on the spawn path, still open on the hand-placed one); Q10's *subject* now exists in code and differs from what was proposed, so the sign-off it asks for is now more urgent, not less. Resolved rows are marked **RESOLVED** with what actually shipped, and are kept rather than deleted so the reasoning stays auditable.

| # | Question | Why it matters | Doc's assumption |
|---|---|---|---|
| **Q1** | Stationary gate: keep **1.4 m/s** (current code) or relax to **2.0 m/s**? | 1.4 will reject some genuine standstills on GPS jitter; 2.0 is a brisk walk. Pure safety-vs-frustration trade. | **PARTLY RESOLVED — both shipped, on different paths.** `claimSpawn` uses **2.0 m/s + a real 4–300 s dwell + a server-derived speed**; `submitClaim` still uses **1.4 m/s** on a single sample. That was not a decision, it was a consequence of building the new path independently. Still open: whether to reconcile them (and if so, in which direction). See C2. |
| **Q2** | Does `crown_collect` belong in the driving-derived weekly bucket `D`? | Excluding it makes the 400/week cap nearly toothless; including it makes the cap bite after ~2 heavy days. | **Included** |
| **Q3** | Should the **Vägfarare** distance ladder exist at all? | It is the only place the system acknowledges kilometres as an achievement. Defensible (milestones, zero marginal KP) but it is the closest thing here to a distance incentive. | **Yes**, with strict icon rules |
| **Q4** | May auto-spawned crowns bypass the admin `safeLocationConfirmed` gate, or must launch use an **admin-approved cell allow-list**? | The only item with a physical-safety failure mode. Allow-list is slower but auditable. | ~~Policy mask, no per-crown admin~~ → **RESOLVED: the allow-list, not the policy mask.** `crownHunt.setSpawnCellApproval` writes `crownSpawnCells/{cellKey}` (explicit `safeAreaConfirmed: true` literal + a ≥3-char note, audited to `adminAuditEvents`), and the spawner's candidate set **is** `approved == true` — an unapproved cell is invisible to it. Re-checked transactionally at write time; revocation deletes the cell's live crowns immediately. Layered under it: the `crownHuntSpawn` flag (**default OFF**), the `A ≥ 1` floor, and the 8 m/s slow-sighting filter. **The policy mask (motorway/water/private-property/exclusion list) was NOT built** — see §4.7 for what remains open at sub-cell resolution. |
| **Q5** | Legacy `first_event` / `five_events` / `garage_created` — show alongside the ladders, or demote to a "tidiga märken" section? | Cosmetic, but affects whether profiles look cluttered or duplicated. | Grandfather, demote in UI |
| **Q6** | ~~**Trogen** has 3 tiers, everything else has 4. Accept the asymmetry, or add a Platina at a 365-day streak?~~ **DECIDED: 3 tiers, no Platina** — implemented; `trogen_platina` does not exist as a badge key. | A 365-day streak badge is exactly the kind of thing §9 warns about. | 3 tiers, no Platina |
| **Q7** | Will KP **ever** redeem for anything of value? | Flips the entire §8.2 threat model. Needs to be known *now*, not discovered later. | **No redemption** |
| **Q8** | Legendary at p = 0.01 / 500 KP → roughly **2 per day nationally** at 30 users. Right scarcity? | Too rare = nobody believes they exist; too common = the jackpot stops being one. | 0.01 |
| **Q9** | Capped-out KP: **forfeited** (proposed) or banked to next period? | Banking re-creates the incentive the cap removes. | Forfeited, shown transparently |
| **Q10** | Approve the per-(cell, user) `lastSeenAt` presence rows (7-day TTL, HMAC'd uid) for computing `A`? | It is location data, however minimal. Needs a privacy sign-off, not just an engineering one. | **STILL OPEN, and now urgent — the code exists and it is weaker than the question assumes.** Built as `crownCellActivity/{cellKey}/recentUsers/{hash}`, backend-only, written only from `live.updatePosition` (so it requires an active user-started sharing session) and only for sub-8 m/s samples. **Two deltas from what this question asked you to approve:** the digest is an **unkeyed, unsalted SHA-256** of (cellKey, uid), not an HMAC under a rotating salt — cell-scoped, so unjoinable across cells, but recomputable by anyone holding the UID list; and the **7-day TTL policy does not exist yet** (`expireAt` is written, but no Firestore TTL policy is configured, and the sweeper only reaps whole quiet *cells*). Mitigating: the `crownHuntSpawn` flag is OFF, so **nothing is being collected today**. The sign-off should happen before it is switched on, against §4.2's shipped-vs-proposed table rather than against the original proposal. |
| **Q11** | `N_target` jumps 0 → 2 at the `A ≥ 1` gate. Accept, or floor the first rung at 1? | Minor tuning; affects how sparse rural areas feel. | ~~Accept (2)~~ → **RESOLVED as assumed: accepted.** `targetCrownCount` applies `ceil(1.5·ln(1+A))` with no first-rung override, so `A = 1` yields `ceil(1.0397) = 2`. Shipped, but never explicitly decided — it is the default falling through. Cheap to revisit: it is one `Math.max`/clamp in a pure, unit-tested function with no data migration. |
| **Q12** | Approve badge milestone bonuses **25 / 75 / 200 / 500** and their exemption from the daily cap? | Platina alone is 500 KP — larger than any single non-legendary earn. | Approve |
| **Q17** | Should `first_event` / `five_events` be retired now that **Träffräv** Brons/Silver measure the same 1 and 5 attendances? | They are duplicates on a profile, but the keys are frozen and members already hold them (§7.3, Q5). Kept for now; both are awarded, and `garage_created` carries the same overlap with `samlare_brons`. | Grandfather all three |
| **Q13** | Launch **nationwide** or opt-in regions? | Nationwide with 30 users spreads `A` thin and many cells will never qualify. | Nationwide, monitor `A` |
| **Q14** | Keep **all** windows on UTC boundaries (matching existing crown-cap code), or move the **daily-open streak** to `Europe/Stockholm`? | A UTC day rolls over at 01:00/02:00 local. Invisible for a cap, but a member will judge a *streak* against their own calendar and will feel robbed. | Caps stay UTC, streak moves to local |
| **Q15** | Route `points.adminReverse` through `creditPoints`/`debitPoints` before building any cap, or exempt reversals from caps by design? | `adminReverse` writes its own transaction today, so `ledger.ts` is **not** a single choke point (C4). A cap built there silently misses reversals. | Route it through first |
| **Q16** | When a claim arrives with **no** `speedMetersPerSecond`, reject, or derive speed server-side from the last trusted position? | Today a missing speed is treated as safe, so the C2 stationary gate — a *safety* constraint, not a fairness one — is bypassable by omitting one optional field. The data for a server-derived fallback already exists (`isPlausibleJump` uses it). | **PARTLY RESOLVED — the assumption shipped, on the spawn path only.** `claimSpawn` requires a `previousFix` and derives speed from the two coordinates and the elapsed time (`movedMeters / dwellSeconds ≤ 2.0`), so omitting the reported field buys nothing there. It derives from the client's **own second fix** rather than from the RTDB trusted position, which is simpler and needs no cooperation beyond coordinates the client has already committed to. **`submitClaim` is unchanged and still bypassable.** The remaining question is narrow: port the same two-fix pattern to `submitClaim` (a breaking request-schema change for the Android client), or reject a missing speed there outright? |

---

## 13. Summary of canonical constants

Everything an implementer needs, in one table. Crown-side values have been checked against `crown-spawn-core.ts` / `crownhunt-core.ts`; where the two crown paths differ, both rows appear.

| Constant | Value | In code |
|---|---|---|
| Grid cell | 0.01° × 0.01° | `CROWN_CELL_DEGREES` |
| Activity decay `τ` | 3 days (7-day window) | `ACTIVITY_TAU_DAYS` / `ACTIVITY_WINDOW_DAYS` |
| Activity sighting speed ceiling | **8 m/s** (absent speed does **not** count) | `MAX_ACTIVITY_SPEED_MPS` |
| Activity write throttle | 10 min per user per cell | `CROWN_ACTIVITY_MIN_INTERVAL_MS` |
| Spawn coefficient `K` | 1.5 | `DENSITY_K` |
| `N_target` | `min(5, ceil(1.5·ln(1+A)))`, 0 if `A < 1` | `targetCrownCount()` |
| ~~Replenish `τ_spawn`~~ | ~~3 h~~ — **not implemented**; the spawner tops straight up to target (§4.4) | — |
| **Spawn pass interval** | **10 min** | `crownHunt-spawnCrowns` |
| Sweep interval | 15 min | `crownHunt-sweepSpawns` |
| Per-run bounds | 50 cells, 100 spawns, 200 activity docs/cell, 1 000 sweep deletions | `spawnScheduled.ts` |
| Min separation `d_min` | 150 m (3×3 neighbourhood) | `MIN_CROWN_SEPARATION_METERS` |
| Dart-throwing attempts | **24** | `MAX_SPAWN_SAMPLE_ATTEMPTS` |
| Collect radius `r_collect` | 75 m (stored radius trusted only if `0 < r ≤ 250`) | `COLLECT_RADIUS_METERS` |
| Rarity weights | 0.70 / 0.22 / 0.07 / 0.01 | `CROWN_RARITY_TABLE` |
| Rarity KP | 10 / 25 / 100 / 500 | `CROWN_RARITY_TABLE` |
| Rarity TTL | 6 h / 12 h / 24 h / 48 h | `CROWN_RARITY_TABLE` |
| Expected KP per crown | 24.5 | (derived) |
| Stationary gate — hand-placed | ≤ **1.4 m/s**, single sample, omittable | `MAX_CLAIM_SPEED_MPS` |
| Stationary gate — spawned | ≤ **2.0 m/s** on both fixes **and** on the server-derived speed | `MAX_COLLECT_SPEED_MPS` |
| Dwell window — spawned | **4 s .. 300 s** between the two fixes | `MIN_DWELL_SECONDS` / `MAX_DWELL_SECONDS` |
| Position freshness | 60 s (current fix only; the previous fix is bounded by the dwell window) | `MAX_POSITION_AGE_SECONDS` |
| Accuracy risk threshold | 50 m | `POOR_ACCURACY_THRESHOLD_METERS` |
| Risk review threshold | 60 | `RISK_REVIEW_THRESHOLD` |
| Mock-location penalty | **60** (= threshold, so it alone triggers review); spawn path only | `MOCK_LOCATION_SCORE` |
| Crown claims/day — hand-placed | 10 (fixed UTC day, from 00:00Z) | `MAX_DAILY_SUCCESSFUL_CLAIMS` |
| Crown claims/day — spawned | **20** (fixed UTC day, separate counter) | `MAX_DAILY_SPAWN_CLAIMS` |
| Global earn cap | 300 KP (Europe/Stockholm civil day, from local midnight) | `DAILY_POINTS_CAP` |
| Driving-derived cap `D` | 400 KP (Europe/Stockholm week, from local Monday midnight) | `WEEKLY_DRIVING_POINTS_CAP` |
| Streak multiplier | `min(1.7, 1 + min(s,7)/10)` | `points-economy-core.ts` |
| Event fence | 150 m | `events/checkIn.ts` |
| Event dwell | ≥ 10 min cumulative, ≥ 2 in-fence samples spanning ≥ 10 min; any single gap credits at most 30 min | `events/checkIn.ts` |
| Event window | `[start − 30 min, end + 30 min]` | `events/checkIn.ts` |
| Badge tier bonuses | 25 / 75 / 200 / 500 KP (cap-exempt) | **unbuilt** |
