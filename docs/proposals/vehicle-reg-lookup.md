# Proposal: Automatic Vehicle Information (registration-number lookup)

> **Status:** Design proposal / feasibility study — **NOT approved for build.**
> Parked on the "Future Ideas" board. This document does not change any
> application code. It exists to support a go / no-go decision.
>
> **Author:** Claude (Opus 4.8), delegated feasibility spike
> **Date:** 2026-07-09
> **Recommendation (short version):** **Spike-first, then most likely defer.**
> The client UX is trivial; the blocker is legal access to Swedish vehicle
> data. See [§9](#9-recommendation).

## 1. Summary

Let a member type their Swedish registration plate (e.g. `ABC123` /
`ABC12D`) in the garage "add vehicle" flow and have make, model, model year,
fuel/powertrain and similar **technical** fields auto-populate, so they
confirm rather than type.

The feature is small on the surface (one plate field → prefill → confirm →
save through the existing `garage.addVehicle` callable). The real work — and
the real risk — is entirely in **where the data comes from** and **the GDPR
posture of looking up plate-linked data at all**. Those two sections
([§3](#3-external-data-sources-the-crux) and [§4](#4-legal--gdpr)) are the
heart of this proposal.

## 2. Codebase fit — what already exists

The garage/vehicles slice is already built and shipped (backend Phase 9e/9f,
native Android Phase 12 slice 13). A lookup feature must plug into it, not
reinvent it.

### 2.1 Data model (`vehicles/{vehicleId}`)

Source of truth: `docs/firebase-data-model.md`,
`contracts/schemas/garage.schema.json`, `functions/src/garage/garage-core.ts`.

| Field | Type | Notes |
| --- | --- | --- |
| `userId` | string | Owner Firebase UID |
| `make` | string (1..80) | e.g. `Volvo` |
| `model` | string (1..80) | |
| `modelYear` | int (1886 .. currentYear+2) | |
| `powertrain` | enum | `petrol \| diesel \| hybrid \| plug_in_hybrid \| electric \| other` |
| `engineDescription` | string? (≤120) | free text |
| `description` | string? (≤500) | free text |
| `color` | string? (≤80) | |
| `imagePath` | string? | `vehicleImages/{uid}/{vehicleId}/{imageId}` |
| `createdAt` / `updatedAt` | Timestamp | server-set |

**The single most important constraint in the whole codebase for this
feature** (`docs/firebase-data-model.md` line ~144, and repeated in the
schema description and both clients):

> The `vehicles` document is **readable by any authenticated user**.
> Registration numbers, VIN, insurance data and vehicle location are
> therefore deliberately **unrepresentable** in the schema and must never be
> added. If a plate must be stored, it belongs in the owner-only
> `userPrivate/{uid}` document — never on the shared `vehicles` record.

The strict Zod schemas (`.strict()` in `garage-core.ts`) actively reject any
extra field, so a plate cannot leak onto `vehicles` even by accident. Any
design here must preserve that invariant.

### 2.2 Where a lookup plugs in

- **Backend callables** live in `functions/src/garage/manageVehicle.ts`
  (`addVehicle` / `updateVehicle` / `deleteVehicle`), all member-only via
  `requireMemberActor`, `europe-west1`, with App Check enforced
  (`enforceAppCheck`) outside the emulator. Pure validation/builders are in
  `garage-core.ts`. A lookup would
  be a **new sibling callable** (e.g. `garage.lookupPlate`) that returns
  *prefill suggestions only* and writes nothing to `vehicles` — the user
  still saves through the existing `addVehicle`.
- **Android UI** (`apps/android/.../garage/VehicleFormScreen.kt`,
  `Vehicle.kt`, `GarageCoordinator.kt`): a Compose form with make / model /
  year / powertrain / engine fields plus `VehicleValidation` mirroring the
  backend bounds. A plate field + "Fetch details" button would sit above the
  existing fields and populate the same `VehicleForm` state.
- **React-Native app** (`apps/mobile/.../VehicleFormScreen.tsx`) is the
  legacy/parallel client; per current MVP scope (Android + admin web +
  backend) new work targets native Android. The RN screen even documents
  "Does not request registration number, VIN, insurance, or location" — that
  comment would need revisiting if this ships.
- **Contracts:** a new `lookupPlateRequest` / `lookupPlateResponse` pair in
  `contracts/schemas/garage.schema.json`, plus a `contracts/functions/functions.json` entry.

### 2.3 Gaps in the current backend (new patterns this would introduce)

Flagged because they materially affect the estimate:

- **No outbound-HTTP or third-party-API pattern exists.** A grep across
  `functions/src` finds **no `fetch(` to external hosts and no
  `defineSecret`**. Callables today use a range of Firebase services —
  Firestore and Storage, plus Auth and the Realtime Database (see
  `functions/src/firebase.ts` and, e.g., `functions/src/live/session.ts`) —
  but there is **no third-party outbound HTTP/API integration pattern** yet.
  This feature would be the **first** to call a paid external API and the
  first to need a stored API credential (Firebase Secrets / Secret Manager).
- **No general rate-limiter.** Only `events/postChatMessage.ts` implements
  per-user throttling, and it does so with an ad-hoc Firestore counter. A
  cost-sensitive external lookup needs real per-user/day quotas — a small
  reusable helper would have to be written (see [§5](#5-proposed-design)).
- Egress: Cloud Functions calling an external API needs outbound network
  (default allowed) but the provider may require **IP allow-listing**, which
  in turn means a static egress IP (VPC connector + Cloud NAT) — extra infra.

## 3. External data sources (the crux)

Swedish per-plate vehicle data ultimately originates from **Transportstyrelsen's
vägtrafikregistret** (Road Traffic Register). Everyone below is either that
agency directly or a licensed reseller of it. Access is genuinely hard for a
hobby/community app — this is the deciding factor.

> **Confidence note:** exact prices, rate limits and current terms below could
> not be fully verified without a signed commercial account (Biluppgifter's API
> docs return HTTP 403 to anonymous fetch; Car.info/D&B are quote-based). Every
> "cost" figure is an order-of-magnitude estimate and **must be confirmed with
> the vendor before any decision.** Treat this section as a research starting
> point, not contract terms.

### 3.1 Transportstyrelsen — direct access ("direktåtkomst")

- **What:** Direct/API access to vägtrafikregistret, incl. vehicle *and*
  owner data.
- **Access model:** Regulated by **lag (2019:369)** and **förordning
  (2019:382)** on vehicle-traffic data. You **apply** and must be *medgiven*
  (granted). Eligibility is scoped to actors who "in their activity handle
  vehicles or need vehicle/owner information" — the named examples are
  holders of professional-traffic (yrkestrafik) / taxi permits, insurers,
  the vehicle trade, municipalities, authorities. **A car-enthusiast
  community app is a poor fit** and would likely struggle to demonstrate a
  qualifying purpose.
- **Auth / cost:** contract-based; not a self-serve developer key.
- **Verdict:** highest-quality, cheapest-per-record source **if** you
  qualify — but qualification is the problem. **Not realistic for MVP-scale
  community app** without a strong purpose argument. Uncertain; would need a
  direct conversation with Transportstyrelsen's helpdesk för direktanslutna.

### 3.2 Transportstyrelsen — open data portal (`tsopendata` / PSI)

- Public, PDM-licensed **open datasets** (no usage restrictions) via the
  Azure API portal and dataportal.se.
- **But:** these are **statistical / bulk** datasets, **not a per-plate
  real-time lookup**, and deliberately exclude owner personal data.
- **Verdict:** does **not** satisfy "type a plate, get this car's specs."
  Not usable for the core UX. (Could conceivably back a make/model *reference*
  database, but that is a different, larger feature.)

### 3.3 Biluppgifter.se — commercial API (most promising)

- **What:** Licensed reseller; real-time API over the official register.
  Products: grundinformation, teknisk fordonsdata (from the registreringsbevis),
  värdering, ägaruppgifter, bildelar.
- **Access model:** Paid, commercial account; **test API keys** advertised
  for pre-integration. **Owner-data endpoints are separately gated** — you
  only get ägaruppgifter if explicitly granted, which is exactly the split we
  want (we can subscribe to *technical* data only).
- **Auth:** API key (details behind a 403'd doc portal — confirm on signup).
- **Cost:** pay-per-use / per-lookup; **figure unconfirmed** — must request a
  quote. Model appears to be per-request credits.
- **Rate limits / ToS:** unconfirmed; standard commercial ToS expected
  (no scraping, attribution, purpose limits).
- **Verdict:** **most viable commercial option.** Real-time per-plate,
  self-serve-ish onboarding, and the technical-only data subset keeps us out
  of the owner-data/GDPR danger zone. Recommended primary candidate for a
  spike.

### 3.4 Car.info — B2B API

- Customized-per-customer B2B API (SE/NO/DK), aimed at dealers, finance,
  insurance, car-parts businesses. Quote-based; no public price.
- **Verdict:** viable but enterprise-oriented; likely heavier onboarding than
  Biluppgifter for our scale. Keep as **fallback / second quote**.

### 3.5 Dun & Bradstreet "Vehicle Data Finder"

- Live Transportstyrelsen data (vehicles, owners, licences) by plate — but
  **explicitly requires Transportstyrelsen permission**, i.e. inherits the
  §3.1 eligibility problem plus enterprise pricing.
- **Verdict:** not a fit for our scale.

### 3.6 Unofficial scrapers (e.g. `fordonsuppgifter-api-wrapper` on GitHub, "vininfo"-style sites)

- Node scraper that screen-scrapes Transportstyrelsen's public
  "Fordonsuppgifter" page by plate. No key, no cost.
- **Verdict:** **Do not use.** Almost certainly violates Transportstyrelsen's
  terms, is legally exposed under the vehicle-data law, breaks whenever the
  page changes, has no SLA, and shifts all GDPR liability onto us with none of
  the safeguards a licensed reseller provides. Mentioned only to explicitly
  rule it out.

### 3.7 Source comparison

| Source | Access model | Auth | Rough cost | Owner data? | Fit |
| --- | --- | --- | --- | --- | --- |
| Transportstyrelsen direktåtkomst | Apply + be granted (regulated) | Contract | Low per-record | Yes (gated) | Poor — eligibility |
| Transportstyrelsen open data | Public / PDM | None | Free | No | No per-plate lookup |
| **Biluppgifter API** | Paid commercial, test keys | API key | Per-lookup (TBC) | Separately gated | **Best candidate** |
| Car.info B2B | Quote / contract | API key | Enterprise (TBC) | Configurable | Fallback |
| D&B Vehicle Data Finder | Needs Tpst. permission | Contract | Enterprise | Yes | No — eligibility |
| Unofficial scraper | None | None | Free | Whatever page shows | **Rejected** |

## 4. Legal / GDPR

First-class section: Swedish plate-linked data is personal data and this is
where the feature can go wrong.

### 4.1 Why a plate lookup is a data-protection question at all

A registration plate is an identifier that maps to a **named registered
keeper** — a natural person in most private-car cases. Under GDPR, *processing*
includes retrieving, and even the pure act of resolving `ABC123 → this car,
owned by this person` is processing of personal data. Two distinct data
classes come out of a lookup:

- **Technical vehicle specifications** — make, model, year, fuel type, engine
  size, weight, inspection dates. About the *object*. Low sensitivity, though
  still linkable to a person via the plate.
- **Owner / keeper identity** — name, address, ownership history. Clearly
  personal data, sensitive from a privacy/stalking standpoint.

### 4.2 What's safe to surface vs. what is not

- **Safe (and the only thing we should fetch):** the technical specs, used
  purely to prefill fields the user was about to type anyway. Subscribe to a
  provider tier that returns **technical data only** (Biluppgifter separates
  this from ägaruppgifter — see §3.3).
- **Do not surface, do not fetch, do not store:** owner name/address/history.
  There is no product reason for a community garage to show who owns a car,
  and doing so would turn the app into a mini surveillance tool.

### 4.3 "Your own vehicle" vs. arbitrary-plate lookup — the core risk

The dangerous version is *arbitrary-plate* lookup: anyone types any plate they
see in a car park and learns about that car (and, with the wrong provider tier,
its owner). That is a doxxing/stalking vector and a reputational and legal
liability, even if only technical fields are shown, because it normalises
plate-surveillance and the plate itself is the sensitive link.

The safer version is *own-vehicle* prefill: the user is entering **their own**
car and merely saving keystrokes. **But we cannot cryptographically prove
ownership** without official integration — there is no consumer-facing "prove
you own this plate" primitive available to us. Practical mitigations:

- Frame and gate the feature strictly as **"add *your* vehicle"** — never a
  public search box.
- **Member-only** (already the garage baseline) + **App Check** + **strict
  per-user daily quota** (e.g. a handful of lookups/day — a real owner adds a
  car rarely; a scraper needs volume). This makes bulk/arbitrary use
  impractical without hard-proving ownership.
- Show **technical fields only**; the user still confirms before save.
- Log lookups minimally (see §4.5) to detect abuse, without building a
  plate-surveillance database ourselves.

### 4.4 Lawful basis, consent, transparency

- Likely basis: **legitimate interest** for the narrow "prefill the car I am
  adding" purpose, or **consent** captured at first use. Product/legal must
  pick one and document it.
- Update the **privacy policy** and the in-app consent copy to disclose that a
  plate is sent to a named third-party processor to retrieve technical data.
- A **Data Processing Agreement (DPA)** with the chosen provider is mandatory,
  plus adding them to the app's processor register / ROPA.
- Data minimisation: request the smallest data tier; **discard** any owner
  field the provider returns even if we did not ask for it.

### 4.5 Storage & retention (ties back to §2.1)

- **Never** store the plate (or any lookup response) on the authenticated-
  readable `vehicles` document — schemas forbid it by design.
- If we must remember the plate at all (e.g. to avoid re-charging for repeat
  lookups, or for the user's own convenience), it goes **only** in
  `userPrivate/{uid}` (owner-only read/write), exactly as the data-model doc
  prescribes — and even that should be an explicit product decision, not a
  default. The cheapest privacy posture is to **not persist the plate at
  all**: use it transiently in the callable, return prefill, forget it.
- Cache technical results **keyed by plate hash, not stored plaintext**, with
  a short TTL, purely to control cost (§5) — and confirm the provider ToS
  permits caching.

## 5. Proposed design

Assuming a licensed technical-data provider (Biluppgifter primary, Car.info
fallback) and a **spike confirming access + price first**.

### 5.1 Backend — new callable `garage.lookupPlate`

- Sibling of `manageVehicle.ts` in `functions/src/garage/`, `europe-west1`,
  `memory: 256MiB`, `enforceAppCheck` on (matching existing garage callables).
- **Auth:** `requireMemberActor` — member-only, same as the rest of garage.
- **Input:** `{ plate: string }` — validated/normalised in a pure
  `garage-core` helper (uppercase, strip spaces, assert the Swedish plate
  format). Use a conservative **shape** check with Latin letters only —
  e.g. `^[A-Z]{3}[0-9]{2}[0-9A-Z]$` — to reject malformed plates before
  spending a paid call. **Note:** the exact allowed letters and series
  (e.g. which letters are excluded, personalised plates) must be confirmed
  against Transportstyrelsen / the chosen provider's documentation before
  hard-coding the pattern; the regex above is a placeholder shape, not an
  authoritative rule.
- **Quota (cost + abuse control):** a small reusable rate-limit helper
  (generalising the `postChatMessage` pattern) enforcing e.g. **N lookups per
  user per day** and a global daily ceiling as a cost circuit-breaker.
  Exceeded → `resource-exhausted`.
- **Cache:** check a `plateLookupCache` (backend-only collection, doc id =
  hash(plate), short TTL) before calling the provider; write through on miss.
  Reduces cost and provider load. ToS-permitting.
- **Provider call:** the first outbound external `fetch` in the codebase; the
  API key comes from **Firebase Secrets / Secret Manager** (new pattern),
  never `.env` committed. Timeout + graceful degradation. May need a static
  egress IP (VPC connector + Cloud NAT) if the provider IP-allow-lists.
- **Output:** `{ found: boolean, suggestion?: { make, model, modelYear,
  powertrain, engineDescription? } }` — mapped/normalised to our schema
  vocabulary (esp. provider fuel types → our `powertrain` enum). **Owner
  fields are dropped server-side and never enter the response.**
- Writes **nothing** to `vehicles`; persists nothing user-visible. Save still
  goes through the untouched `garage.addVehicle`.

### 5.2 Client UX (Android, Compose)

1. In `VehicleFormScreen`, add an optional **plate field + "Fetch details"**
   button above the existing fields, clearly labelled "Add *your* vehicle".
2. On tap → call `garage.lookupPlate`, show inline spinner.
3. **Success:** prefill make / model / year / powertrain into the existing
   `VehicleForm` state; show a "we filled these in — check and edit" hint. The
   user **confirms/edits then saves** as today. Nothing is auto-committed.
4. **Not found / empty:** friendly "couldn't find that plate — enter details
   manually," fields stay editable. No error styling for the normal miss.
5. **Error states:** network/provider down → "lookup unavailable, add
   manually"; quota exceeded → "you've reached today's lookup limit"; invalid
   plate → inline validation. The feature is always **optional** — manual
   entry remains the primary path and full fallback.
6. Copy in both `sv` and `en` string resources; consent/disclosure string on
   first use.

### 5.3 Abuse & cost controls (summary)

Member-only · App Check · per-user daily quota · global daily ceiling
(circuit breaker) · plate-hash cache with TTL · technical-tier-only
subscription · minimal audit logging to spot anomalies · framed as own-vehicle
add, never a public search.

## 6. Effort estimate & phasing

| Phase | Scope | Rough effort |
| --- | --- | --- |
| **0. Access + legal spike (gate)** | Get a Biluppgifter (and/or Car.info) quote + test key; confirm price, rate limits, caching ToS, technical-only tier; DPA feasibility; legal sign-off on lawful basis. **No code.** | ~0.5–1.5 wks mostly waiting on vendor/legal |
| 1. Backend callable | `garage.lookupPlate` + `garage-core` plate validation/normalisation + fuel→powertrain mapping + contracts + tests | ~2–3 days |
| 2. Infra plumbing | Secret Manager wiring, quota helper, cache collection, optional static egress IP | ~2–4 days (new patterns) |
| 3. Android UX | Plate field, fetch/prefill/confirm flow, all states, i18n, consent copy | ~2–3 days |
| 4. Privacy/compliance | Privacy-policy + ROPA update, in-app disclosure, abuse monitoring | ~1–2 days |

**Total build (Phases 1–4):** ~1.5–2.5 engineer-weeks **once Phase 0
succeeds.** Phase 0 is the real gate and is mostly out of engineering's hands.

## 7. Risks

- **Access risk (highest):** no affordable, ToS-clean per-plate technical API
  may be available to an app of this profile. If Phase 0 fails, the feature is
  dead. Everything else is moot until access is confirmed.
- **Recurring cost:** per-lookup pricing is an ongoing OPEX with no direct
  revenue; needs a budget owner and the global ceiling as protection.
- **Privacy/reputation:** mishandled, becomes a plate-surveillance tool —
  reputational and regulatory (IMY) exposure. Mitigated by technical-only data
  + own-vehicle framing + quotas.
- **New backend surface:** first external API + first secret + possible VPC
  egress. More moving parts than any existing callable; more to operate.
- **Provider lock-in / SLA:** third-party outage degrades the feature; format
  changes need mapping maintenance. Mitigated by keeping it optional and
  manual-first.
- **Ownership unprovable:** we cannot truly verify the plate is the user's,
  so "own-vehicle only" is a framing + rate-limit posture, not a guarantee.

## 8. Open product questions (human must answer before go/no-go)

1. **Is anyone willing to run Phase 0** — chase a Biluppgifter/Car.info quote
   and get legal sign-off on lawful basis + DPA? Without an owner, this cannot
   proceed.
2. **Is recurring per-lookup cost acceptable** for a nice-to-have prefill, and
   who owns that budget/ceiling? (This is a convenience feature, not a
   must-have — see recommendation.)
3. **Do we ever persist the plate** (userPrivate only) for repeat-lookup
   convenience, or stay strictly transient (preferred for privacy)?

## 9. Recommendation

**Spike-first (Phase 0 only), then most likely defer.**

- The engineering is small and clean, and fits the existing garage
  architecture well — a new read-only `garage.lookupPlate` callable + a plate
  field, with the save path untouched.
- **But the value is low** (it saves a member ~4 fields, once, when adding a
  car they add rarely) while the **cost and risk are real and recurring**:
  paid per-lookup OPEX, a GDPR/reputational surface, and the first
  external-API + secret + possible egress-IP plumbing in the backend.
- The gating question is not "can we build it" but **"can we get affordable,
  ToS-clean access to technical-only per-plate data?"** Answer that with a
  cheap, code-free **Phase 0 spike** (vendor quote + legal read).
  - If Phase 0 yields a clean technical-only tier at trivial cost **and**
    someone owns the budget/legal work → build it behind a feature flag,
    technical data only, own-vehicle framing, hard quotas.
  - Otherwise → **defer / drop.** It is a convenience that does not justify
    standing up recurring cost and a privacy-sensitive integration for the
    community MVP. Manual entry (already shipped) is perfectly adequate.

Given current MVP scope (Android + admin web + backend, auth still deferred),
the honest call today is **defer**: do not build until MVP is live and Phase 0
has independently proven cheap, clean data access.

## Appendix — sources consulted

- Transportstyrelsen — Ansök om att söka uppgifter i vägtrafikregistret / villkor för medgivandet: <https://www.transportstyrelsen.se/sv/vagtrafik/Yrkestrafik/Ansok-om-att-soka-uppgifter-i-vagtrafikregistret/>
- Transportstyrelsen — öppna data / PSI: <https://www.transportstyrelsen.se/psidata> and API portal <https://tsopendata.portal.azure-api.net/>
- Biluppgifter — API & produkter: <https://biluppgifter.se/produkter/> ; API reference (403 to anonymous fetch): <https://apidocs.biluppgifter.se/>
- Car.info — B2B API: <https://www.car.info/en-se/b2b/api>
- Dun & Bradstreet — Vehicle Data Finder (Nordics): <https://www.dnb.com/en-gb/developers/vehicle-data-finder.html>
- Unofficial scraper (ruled out): <https://github.com/philipgyllhamn/fordonsuppgifter-api-wrapper>

_Access models, pricing and terms above are indicative and were not confirmed
against signed vendor contracts; verify in Phase 0._
