# Proposal: "Analys" profile section (personal driving analytics)

> **Status: PROPOSAL — not approved, not implemented.** This document is a
> design sketch for a backlog idea. It contains **no application code**.
> Nothing here should be built until Seb gives an explicit in-session
> go-ahead.

- **Author:** Claude (Opus 4.8), on request
- **Date:** 2026-08-21
- **Scope:** Kungsbacka Car Community (Android + admin web + Firebase Cloud Functions)
- **Type:** Non-MVP backlog idea (Future Ideas board, GitHub Project #2)
- **Recommendation (short):** **Park.** When greenlit, build **Phase 1** only
  (data-ready sections, no new backend). Later phases are gated on aggregation
  work and on the Drivelogg route-geometry persistence (#366).

---

## 1. Summary

An **"Analys"** tab on the user's profile page: personal analytics computed
from data the app already collects about the member, their driving, and their
cars. The guiding principle is **"your own data, shown only to you"** — which
keeps the privacy surface low, with two deliberate exceptions handled
explicitly (community comparison and co-driver names, see [§4](#4-key-decisions-resolved)).

No speed analytics of any kind, ever (product-wide no-speed stance).

## 2. Sections, grouped by data-readiness

**Data-ready today (no new collection):**
1. **Körstatistik** — totals + trends: mil, hours, drive count, longest drive,
   average length, month-over-month arrows.
2. **Körmönster (persona)** — weekday/hour histograms → light, non-judgmental
   personas ("Du är en fredagskvällskörare — 40% av dina mil efter 20:00");
   season splits.
3. **Konvoj vs solo** — convoy share of drives, top körkompisar (most frequent
   co-drivers, convoy-only), average convoy size.
4. **Kronjakt-analys** — points over time, per-source breakdown, crowns
   collected, progress to next rank, "nearest badge" nudges.
5. **Eventengagemang** — events attended (RSVP), created, published; busiest
   month.

**Needs light new aggregation:**
6. **Garage-analys** — per-car usage split ("Volvon tog 80% av milen"); fleet
   fun facts (avg fleet age, brand loyalty, community rarity). *Caveat: drives
   before the Start-driving car-picker carry no car link — this analysis only
   covers data from that feature's launch onward.*
7. **Utforskning** — unique kommuner/areas visited, new-vs-familiar route
   share. **Blocked** on route-geometry persistence (the parked Drivelogg 2.0
   proposal, #366). Without it, only coarse start/end-area analysis.
8. **Du vs klubben** — the member's monthly mil vs an **anonymized** community
   average. Needs one scheduled aggregation function.

**Seasonal payoff:**
9. **Körårskrönikan** — a yearly "KCC Wrapped" (December): a swipeable,
   Spotify-Wrapped-style recap (mil, hours, top körkompis, most-driven car,
   crowns, rank climb), exportable as shareable images. Reuses the Phase-1/2
   stats and composes with the share-card concept in the Drivelogg proposal.
   A monthly mini-digest could reuse the same pipeline.

## 3. Phasing

- **Phase 1 — data-ready, no new backend:** Körstatistik, Körmönster,
  Kronjakt-analys, Konvoj vs solo (Eventengagemang can ride along — it's
  small). This is the shippable core; computed on-demand from the member's own
  history.
- **Phase 2 — light aggregation:** Garage-analys + Du vs klubben (one
  scheduled community-average job).
- **Phase 3 — dependent:** Utforskning — gated on Drivelogg route-geometry
  persistence (#366); do not promise it before that lands.
- **Payoff phase:** Körårskrönikan (December). Reuses Phase-1/2 stats, so it
  comes after them. Overlaps the Drivelogg share-card work — could be split
  into its own proposal if it grows.

## 4. Key decisions (resolved)

1. **"Du vs klubben" privacy** — it is the one section that leaves the
   member's own data. It must be an **anonymized community average** with a
   **minimum-N threshold** (do not render if too few members contribute, to
   avoid de-anonymization). It **respects the existing leaderboard opt-out**
   (`leaderboardOptOut`, PR #940): an opted-out member is excluded from the
   aggregate and does not see the comparison.
2. **Cold-start honesty** — garage-per-car only works from the car-picker
   launch; utforskning needs route geometry not stored historically. Early
   analytics will be **partial**, so sections use graceful "börjar samla data
   / sedan [datum]" framing rather than blank or misleading output.
3. **Compute strategy** — keep it simple: Phase 1 computes **on-demand** from
   the member's own (bounded) drive history; Phase 2 adds **one scheduled
   aggregate job** for community averages. Precomputed per-user aggregate docs
   are noted as a **future optimization** only if on-open compute proves heavy.
4. **Co-driver naming** — "top körkompisar" names a specific person in the
   member's own analytics. Allowed **only for convoy participation** (both
   parties opted into the convoy; visible only to the member). Nothing sourced
   from DMs or other social signals.
5. **Placement** — a new **"Analys" tab on the profile page**.

## 5. Locked exclusions (do not re-propose)

- **No speed analytics of any kind** — no top speed, fastest drives, or
  braking/acceleration scoring. Product-wide no-speed-gamification stance.
- **No creepy social metrics** — no "most DM'd person" etc. Convoy co-driver
  stats are fine (see §4.4).
- **No fuel/consumption analysis** — not collected; a separate parked idea.
- Never name competitor apps in the public repo ("Competitor X").

## 6. Dependencies & conventions

- **Dependencies:** Drivelogg route-geometry persistence (#366) for
  Utforskning and for the richest Körårskrönikan share cards; the
  Start-driving car-picker launch for Garage-analys coverage.
- **Conventions (when/if it proceeds):** branch + PR, never merge without
  Seb's explicit instruction; Swedish UI via `contracts/localization`;
  points/stats logic backend-authoritative; new UI behind a feature flag
  (mind the known one-shot flag-read OFF-default behaviour); Gradle serial /
  foreground; emulator tests need JDK 21.

## 7. Recommendation

**Park** on the Future Ideas board. When Seb greenlights, start with **Phase
1** (data-ready sections, no new backend) as the "Analys" tab behind a feature
flag; treat Phase 2/3 and Körårskrönikan as follow-ups gated on the
aggregation job and the Drivelogg route-persistence work respectively.
