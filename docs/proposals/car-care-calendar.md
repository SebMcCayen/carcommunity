# Proposal: "Bilkalendern" — Swedish car-care calendar per garage vehicle

> **Status:** Design proposal / feasibility study — **NOT approved for build.** Parked on the Future Ideas board pending explicit go-ahead.
> **Author:** Claude (delegated feasibility spike)
> **Date:** 2026-07-10
> **Recommendation (short version):** **Build Phase 1 (manual dates + server-computed reminders) after MVP launch.** It is a small, cheap, privacy-clean feature that reuses the garage + notification stack almost entirely, and it is the single strongest "daily practical value" candidate on the board. Auto-fetch (Phase 2) stays gated on the parked reg-number-lookup proposal.

## 1. Summary

Every Swedish car owner juggles the same three dates: the **besiktning**
(periodic vehicle inspection) deadline, the **vinterdäck/sommardäck** swap
windows, and loose service intervals. Transportstyrelsen explicitly does
**not** send a reminder ("kallelse") when an inspection is due — tracking it
is the owner's own responsibility — so Swedes today improvise with the Opus
app, besiktningstid-style lookup sites, and phone-calendar entries.

"Bilkalendern" adds a per-vehicle care layer on top of the existing garage:

1. **Besiktning countdown** — the member enters the last approved inspection
   date (and, for new cars, the first-registration date); the backend computes
   the legal deadline window and sends escalating reminders.
2. **Däckbyte assistant** — season reminders derived from the legal winter- and
   studded-tire dates, plus optional per-car tire-set tracking (which set is
   mounted, tread-depth log, storage-location note).
3. **"Innan besiktning" checklist** — a community prep checklist (static
   content in Phase 1) that de-fuses the besiktning anxiety that fills
   garaget.org threads.

No competitor community app has this. It works for **all powertrains and all
vehicle ages** (including the 30+/50+ year classics this community loves,
which have their own inspection rules — see §3), and it gives the app a reason
to be opened *between* meets. All reminders are computed server-side by one
daily scheduled function, so they arrive even if the app is never opened.

This is deliberately **not** a full digital service book. Phase 1 is dates in,
reminders out.

## 2. Existing infrastructure we can reuse

The feature is ~80% assembled from parts that already exist.

### 2.1 Garage vehicle model (the anchor)

- **Backend:** `functions/src/garage/garage-core.ts` (strict Zod schemas,
  `MAX_VEHICLES_PER_USER = 5`, powertrain vocabulary) and
  `functions/src/garage/manageVehicle.ts` (`addVehicle` / `updateVehicle` /
  `deleteVehicle`, all member-only via `requireMemberActor` from
  `functions/src/shared/memberActor.ts`, `europe-west1`, App Check enforced).
  Care data hangs off the existing `vehicles/{vehicleId}` identity — no new
  vehicle concept.
- **The critical invariant:** `vehicles/{vehicleId}` is **readable by any
  authenticated user** and its `.strict()` schemas deliberately make plates,
  VINs, insurance and location *unrepresentable*
  (`garage-core.ts` header comment; `docs/firebase-data-model.md` ~line 144).
  This proposal does **not** touch the `vehicles` schema at all — care data
  lives in an owner-only location (§4.2), preserving the invariant.
- **Android:** the complete garage slice in
  `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/garage/` —
  `Vehicle.kt` (domain + validation), `GarageScreen.kt` / `VehicleFormScreen.kt`
  (Compose UI), `GarageCoordinator.kt`, `FirebaseGarageRepository.kt`,
  `GarageStrings.kt` (sv/en i18n pattern). A "Bilkalender" card per vehicle
  slots into the existing vehicle detail/edit surface.

### 2.2 Notification inbox, preferences and push

- **Single backend inbox writer:**
  `functions/src/notifications/deliver.ts` → `writeInAppNotification()` —
  already handles deleted/suspended recipients, per-category opt-outs, and
  (crucially for a reminder engine) **idempotent delivery via deterministic
  `notificationId`s**, so a re-run of the daily job can never double-remind.
- **Categories:** `functions/src/notifications/notifications-core.ts` defines
  `NOTIFICATION_CATEGORIES` and warns that new categories require product +
  security review. This feature needs **one** new non-essential category
  (proposed: `care_reminder`), mirrored in the Android settings list
  (`apps/android/.../notifications/NotificationSettings.kt`,
  `NotificationCategories.ACTIVE`) so members can opt out per the existing
  `userPrivate/{uid}.notificationPreferences` mechanism.
- **Scheduled-function pattern:** `functions/src/notifications/scheduled.ts`
  (`notifications-cleanupExpired`) is the exact template — `onSchedule`,
  `region: europe-west1`, `timeZone: Europe/Stockholm`, and an exported
  `run…()` runner so emulator tests can drive it deterministically.
- **Push reality check:** `functions/src/notifications/pushTokens.ts` registers
  tokens (hash-only) but its header states that actual FCM delivery
  (`sendPushNotification`) is **deliberately deferred to end-of-MVP** Firebase
  setup. Phase 1 reminders therefore land in the **in-app inbox**
  (`apps/android/.../notifications/NotificationsScreen.kt`); they become true
  push automatically once the end-of-MVP FCM delivery work ships, with no
  changes to this feature.

### 2.3 Feature flags

`functions/src/shared/featureFlags-core.ts` (`FEATURE_FLAG_DEFAULTS`, flat
`config/featureFlags` doc, audited `admin.setFeatureFlag`, client read access
via rules) + the admin toggle UI in `apps/admin/src/features/feature-flags/`.
One new key — `carCareCalendar`, default `false` — is a one-line addition on
each side and gives us the risk gate product-decisions.md requires.

### 2.4 Events module (the community hook)

`functions/src/events/manageEvent.ts` + `events-core.ts` already support
admin-created events with RSVP. The **"däckbytarhelg"** idea (a community
weekend where members help each other swap wheels, borrow torque wrenches and
jacks) needs **zero new backend**: it is a normal event; the calendar simply
shows a seasonal suggestion card that deep-links into the existing events list
around swap season. MVP-light by construction.

### 2.5 Parked adjacent proposal: reg-number lookup

`docs/proposals/vehicle-reg-lookup.md` (parked) already mapped the terrain for
fetching per-plate **technical data — which includes inspection dates** — from
licensed resellers (Biluppgifter et al.). Two-way relationship:

- **This proposal does not depend on it.** Phase 1 is manual entry.
- **This proposal strengthens it.** The lookup proposal's own recommendation
  said its value was low ("saves ~4 fields, once"). A besiktning calendar is
  the killer use-case that changes that math: auto-fetching the last-approved
  inspection date makes reminders zero-effort and always correct, and gives a
  recurring (annual) reason for the lookup rather than a one-time prefill.
  If Bilkalendern proves engagement, it is the strongest argument yet to run
  that proposal's "Phase 0" vendor/legal spike.
- All of that proposal's constraints carry over unchanged: plates never on
  `vehicles`, owner data never fetched, plate at most in `userPrivate/{uid}`.

## 3. Swedish rules — besiktning and däckbyte (the domain facts)

The feature encodes law, so the law must be encoded correctly — and, more
importantly, **updatable without an app release** (§4.5), because parts of it
are changing in 2026.

### 3.1 Besiktning (kontrollbesiktning) intervals

Sweden abolished the old final-digit ("sista siffran") inspection scheme on
20 May 2018. Current rules for personbil/lätt lastbil ≤ 3 500 kg, per
Transportstyrelsen:

- **First inspection:** no later than **36 months after the month** the
  vehicle was first taken into traffic.
- **Second inspection:** no later than **24 months after the month** of the
  first inspection.
- **Thereafter:** no later than **14 months after the month** of the previous
  approved inspection.
- Granularity is the **calendar month**, and inspecting **early is allowed but
  restarts the clock** from the new inspection month.
- Transportstyrelsen sends **no kallelse** — the owner alone must track this.
  Missing the deadline triggers automatic körförbud. This gap *is* the product.

Enthusiast-relevant special cases (this community skews classic-heavy):

- Vehicles **30–49 years old**: inspection at most **24 months** after the
  previous one.
- Vehicles **50+ years old**: currently **exempt** ("besiktningsbefriade")
  given a valid approved inspection — **but Transportstyrelsen has announced a
  rework** (exact decision date and in-force timeline unverified — **must be
  confirmed against current Transportstyrelsen guidance before implementation**)
  that would re-introduce biennial inspection for cars manufactured 1960+ and
  change the exemption model. **Conclusion: interval logic must live in server config,
  not in code or clients** (§4.5).

Sources: [Transportstyrelsen — besiktningsregler personbil/lätt lastbil ≤3500 kg](https://www.transportstyrelsen.se/sv/vagtrafik/fordon/aga-kopa-eller-salja-fordon/fordonsbesiktning/besiktningsregler/personbil-och-lastbil-som-inte-overstiger-3500-kg-i-totalvikt/),
[Transportstyrelsen — besiktningsregler (overview)](https://www.transportstyrelsen.se/sv/vagtrafik/fordon/aga-kopa-eller-salja-fordon/fordonsbesiktning/besiktningsregler/),
[Transportstyrelsen — veteranfordon (50+ år)](https://www.transportstyrelsen.se/sv/vagtrafik/fordon/aga-kopa-eller-salja-fordon/fordonsbesiktning/besiktningsregler/veteranfordon/),
[Opus Bilprovning — när ska bilen besiktas](https://opus.se/tjanster/kontrollbesiktning/personbil/),
[Bilprovningen — besiktningsperioder](https://www.bilprovningen.se/ovrigt/besiktningsperioder),
[auto motor & sport — återinförd veteranbesiktning 2026](https://www.automotorsport.se/nyheter/transportstyrelsen-aterinfor-besiktning-av-veteranbilar-2026/).

### 3.2 Däckbyte legal dates

Per Transportstyrelsen:

- **Winter tires (or equivalent) are required 1 December – 31 March** when
  winter road conditions (vinterväglag) prevail; minimum tread depth **3 mm**
  in that period.
- **Studded tires (dubbdäck) are permitted 1 October – 15 April**, and outside
  that window only if winter conditions exist or are expected
  (Transportstyrelsen's own 2026 messaging: "dubbdäcken ska vara av senast den
  16 april — om inte vintern är kvar").
- Since **1 December 2024**, friction winter tires must carry the
  **alptopp/snöflinga (3PMSF)** symbol to count as winter tires.
- Studded and non-studded tires may not be mixed on the same car.

Reminder design implication: the dates are fixed but the *obligation* is
conditional on winter conditions, so copy must say "senast" / "från och med"
framing, never "you are now illegal". Dates also belong in server config.

Sources: [Transportstyrelsen — vinterdäck](https://www.transportstyrelsen.se/vinterdack),
[Transportstyrelsen — nya krav på dubbfria däck (3PMSF)](https://www.transportstyrelsen.se/sv/om-oss/pressrum/nyhetsarkiv/2024/nya-krav-pa-dubbfria-dack/),
[Transportstyrelsen — dubbdäck av senast 16 april (2026)](https://www.transportstyrelsen.se/sv/om-oss/pressrum/nyhetsarkiv/2026/dubbdacken-ska-vara-av-senast-den-16-april--om-inte-vintern-ar-kvar/).

### 3.3 Positioning / market context

Opus/Bilprovningen apps remind only about inspections **booked with them**;
besiktningstid-style sites are one-shot lookups; none of it is per-garage,
community-flavored, or connected to events. For a member with a daily driver
plus a classic, one place that says "Volvon ska besiktas senast augusti,
Escorten är befriad, dubbdäcken ska av om en vecka" is genuinely new — and it
is utility, not gamification, so it fits the safety-first product posture
(no time pressure, no driving interaction; reminders are read at home).

## 4. Proposed design

### 4.1 Lifecycle

1. Member opens a vehicle in the garage → new "Bilkalender" section.
2. Enters: last approved inspection month (or "first inspection not yet done"
   + first-in-traffic month), and optionally tire info (current set mounted,
   sets owned, storage note, tread measurements).
3. Client calls a new callable; backend validates, computes
   `nextInspectionDeadline` from the config rules table, and stores the care
   doc (owner-only).
4. A daily scheduled job finds vehicles whose deadlines cross reminder
   thresholds and writes idempotent inbox notifications; the same job emits
   season-wide tire reminders on config-driven dates.
5. Member gets inspected → updates the date → deadline recomputes, reminder
   cycle resets.

### 4.2 Data model

**No change to `vehicles/{vehicleId}`** (preserves the authenticated-readable
+ strict-schema invariant). New owner-only subcollection:

`userPrivate/{uid}/vehicleCare/{vehicleId}`:

| Field | Type | Notes |
| --- | --- | --- |
| `vehicleId` | string | mirrors doc id; FK to `vehicles` |
| `lastInspectionMonth` | string `YYYY-MM` \| null | month granularity per §3.1 |
| `firstInTrafficMonth` | string `YYYY-MM` \| null | for the 36-month first-inspection case and age-based rules |
| `inspectionCount` | int \| null | 0/1/2+ — selects 36/24/14-month rule |
| `inspectionExempt` | boolean | user-declared 50+ exemption (UI suggests it from `modelYear`) |
| `nextInspectionDeadline` | string `YYYY-MM` \| null | **server-computed only** |
| `currentTireSet` | enum `summer \| winter_friction \| winter_studded \| unknown` | |
| `tireSets[]` | array (≤4) | `{ label, kind, storageNote? (≤120), treadLog: [{date, mm}] (≤20) }` |
| `remindersEnabled` | boolean | per-vehicle master switch |
| `createdAt` / `updatedAt` | Timestamp | server-set |

Plus one backend-only config doc `config/carCareRules` (sibling of
`config/partnerInsights`, backend-only per the featureFlags-core comment):
interval table (36/24/14, age thresholds, exemption policy) and tire dates
(`winterRequiredFrom/To`, `studdedAllowedFrom/To`, reminder lead days). When
Transportstyrelsen's 2026 veteran decision lands, an admin edits one document.

Security rules: `userPrivate/{uid}/vehicleCare/{vehicleId}` owner-read only
(same posture as `userPrivate/{uid}/pushTokens`); writes go through the
callable so `nextInspectionDeadline` can never be client-forged. Nothing here
is subscription-entitlement-bearing, so backend-authoritative-entitlements
rules are not implicated; the member gate is the same `requireMemberActor` as
the rest of garage.

### 4.3 Backend sketch

New domain `functions/src/carCare/` mirroring the garage layout:

- `carCare-core.ts` — pure module: input schemas (`.strict()`), the deadline
  algorithm `computeNextInspectionDeadline(careDoc, rulesConfig, now)`
  (month arithmetic incl. 36/24/14 selection, age-based 24-month override,
  exemption), and reminder-stage selection
  `dueReminderStages(deadline, now)` → e.g. `T-60d`, `T-30d`, `T-14d`,
  `deadline month`, `overdue`. Fully unit-testable, no Admin SDK — same
  pattern as `garage-core.ts` / `events-core.ts`.
- `manageCare.ts` — callable `carCare.setVehicleCare` (member-only,
  `requireMemberActor`, App Check, `europe-west1`, flag-gated on
  `carCareCalendar`): validates ownership of `vehicleId` against
  `vehicles` (ownership failure → not-found, matching the garage-core
  anti-enumeration stance), recomputes the deadline, upserts the care doc.
- `scheduled.ts` — `carCare-remindersDaily` (`onSchedule`, 06:00
  Europe/Stockholm, exported `runCareReminders(now)` runner for emulator
  tests, per `notifications/scheduled.ts`). Collection-group query on
  `vehicleCare` where `remindersEnabled == true` and `nextInspectionDeadline`
  within the reminder horizon (one composite index); for each due stage,
  `writeInAppNotification(uid, {...category: 'care_reminder'...},
  notificationId: \`care_besiktning_${vehicleId}_${deadline}_${stage}\`)` —
  deterministic ID ⇒ idempotent, re-runs are safe. Tire reminders: on
  config-driven trigger dates, same call with
  `care_tires_${season}_${year}_${vehicleId}`, filtered by `currentTireSet`
  (e.g. studded-off reminder only to cars with `winter_studded` mounted).
- One new notification category `care_reminder` in
  `notifications-core.ts` + Android `NotificationSettings.kt` (opt-out-able,
  **not** essential) — flagged here explicitly because the core file requires
  product/security review for category additions.
- Contracts: `contracts/schemas/carCare.schema.json` +
  `contracts/functions/functions.json` entry; feature-flag key in
  `contracts/features/feature-flags.json`.

### 4.4 Client UX (Android, Compose, Swedish-first)

- Vehicle detail/edit gains a **Bilkalender** card: besiktning status line
  ("Besiktas senast **aug 2026** · 34 dagar kvar", or "Besiktningsbefriad
  (50+ år)" with an "rules may change" footnote), tire status ("Sommardäck på
  · dubbdäck tillåtna fr.o.m. 1 okt"), and edit affordances. Strings via the
  `GarageStrings.kt` sv/en pattern.
- Garage list shows a small deadline chip on each vehicle when < 60 days.
- **"Innan besiktning" checklist**: static bundled content in Phase 1
  (lights, wipers, tires, brakes, rust/leak glance, warning lamps, plate
  lamps — the classic anmärkning list), one screen, no backend. Phase 2 can
  move it to an admin-editable Firestore doc if the community wants to curate
  it.
- **Däckbytarhelg hook**: around swap season the calendar card shows
  "Däckbytarhelg med klubben?" linking to the events list; organizing one is
  a normal admin-created event with RSVP (§2.4). Opt-in, zero new moving
  parts.
- Reminder settings ride the existing notification-preferences screen (new
  `care_reminder` row) plus the per-vehicle `remindersEnabled` switch.

### 4.5 Correctness posture

The app must present itself as a **reminder aid, not an authority**: copy
states that the legally binding date is Transportstyrelsen's, computed from
what the user entered, with a "kontrollera på transportstyrelsen.se" link.
Rules live in `config/carCareRules` so the 2026 veteran-rule change (and any
future interval change) is a config edit, not a release. Deadline computation
happens **only** server-side so every client shows the same answer.

## 5. Privacy, cost & performance

- **Privacy: near-zero new surface.** The only new data is what the user
  types: inspection month, tire notes. Stored owner-only under
  `userPrivate/{uid}/vehicleCare`; never on the authenticated-readable
  `vehicles` doc; no plates, no VINs, no external calls, no third parties.
  Deleted with the account via the existing `userPrivate` deletion path
  (verify the account-deletion sweep includes the new subcollection —
  one-line addition if not). An inspection *month* is arguably inferable
  public info anyway; we still treat it as private by default.
- **Cost: effectively free.** One scheduled function/day (256 MiB, seconds of
  runtime), one composite index, a handful of document writes per reminder.
  At community scale (hundreds of vehicles) this is deep inside the Firebase
  free tier. No paid APIs in Phase 1.
- **Performance:** the daily job is offline batch work; nothing touches the
  hot paths (live location, events, auth) that product-decisions.md
  prioritizes. Client reads are one small owner-only doc per vehicle.

## 6. Effort estimate & phasing

| Phase | Scope | Rough effort |
| --- | --- | --- |
| **1a. Backend core** | `carCare-core.ts` (deadline algorithm + stages + tests — the month arithmetic and rule-table selection is the only genuinely fiddly part), `setVehicleCare` callable, rules config doc + loader, security rules, contracts, feature flag | ~3–4 days |
| **1b. Reminder job** | `carCare-remindersDaily` + `care_reminder` category (backend + Android settings row) + emulator tests of idempotency | ~2 days |
| **1c. Android UI** | Bilkalender card + edit sheet, garage-list chip, checklist screen (static), i18n sv/en, disclaimers | ~3–4 days |
| **1d. Polish** | Däckbytarhelg suggestion card → events deep-link; docs (`firebase-data-model.md` addition) | ~1 day |
| **2. Tire-set tracking** | `tireSets[]` UI (sets, tread log, storage note) — data model already reserves it | ~2–3 days, ship only if Phase 1 sticks |
| **3. Auto-fetch** | Reg-number lookup of inspection dates — **gated entirely on `vehicle-reg-lookup.md` Phase 0** (vendor + legal). Not costed here. | see that proposal |

**Total Phase 1: ~1.5–2 engineer-weeks**, flag-gated (`carCareCalendar`,
default off), no admin-web work beyond the existing flag toggle. Push
escalation arrives for free with the end-of-MVP FCM delivery task.

## 7. Risks

- **Legal-rule drift** (the 2026 veteran-inspection rework is already
  announced): mitigated by the server-side `config/carCareRules` table and
  "reminder aid, not authority" copy. Someone must own watching
  Transportstyrelsen announcements (~2×/year check).
- **Garbage-in deadlines:** a user who mistypes the inspection month gets a
  wrong reminder and could, in theory, miss a real deadline and blame the
  app. Mitigations: month picker (no free text), plausibility checks
  (not in the future, not before `firstInTrafficMonth`), the disclaimer, and
  the Phase-3 auto-fetch as the real fix.
- **Notification fatigue:** escalating besiktning stages + two tire seasons
  could feel naggy. Mitigations: conservative defaults (3 besiktning stages,
  1 reminder per tire season), per-vehicle switch, category opt-out, and the
  existing inbox retention sweep (`notifications/scheduled.ts`) keeping the
  inbox clean.
- **Push dependency:** until end-of-MVP FCM delivery ships, reminders are
  in-app-inbox only, which blunts the "works without opening the app" pitch.
  Honest sequencing: ship Phase 1 with inbox, market the push angle only once
  FCM lands.
- **Scope creep toward a service book** (fuel logs, cost tracking, full
  maintenance history): explicitly out of scope; the data model deliberately
  reserves only tire fields.
- **New notification category** touches a reviewed invariant in
  `notifications-core.ts`; kept to exactly one category to minimize review
  surface.

## 8. Open product questions (for the human)

1. **Member-only or free-tier?** Garage is member-only today
   (`requireMemberActor`), so Bilkalendern inherits that. But as a
   subscription *retention/justification* feature ("the app that remembers
   your besiktning") it may be exactly what the paid tier needs — or,
   inversely, a free teaser. Product call; backend is one actor-guard either
   way.
2. **Reminder defaults:** proposed besiktning stages T-60/T-30/T-7 days +
   deadline-month + overdue; tire reminders ~2 weeks before 1 Dec and ~1 week
   before 15 Apr. Tune?
3. **Is the 50+ exemption user-declared enough**, or should the UI compute it
   from `modelYear` and just confirm? (Rules changing in 2026 argues for
   user-declared + config.)
4. **Does Phase 1 include tire-set tracking** (Phase 2 above) or ship
   date-reminders only first? Recommendation: reminders only first.
5. **Checklist curation:** static app content forever, or admin-editable so
   the community can maintain it? (Static for Phase 1 regardless.)
6. **Does this green-light the reg-number-lookup Phase 0 spike** once Phase 1
   shows engagement, per §2.5?

## 9. Recommendation

**Build Phase 1 after MVP launch; park Phases 2–3 behind evidence.**

- The cost/benefit is unusually good: ~1.5–2 weeks reusing the garage model,
  the single-writer notification inbox with idempotent delivery, the
  scheduled-function pattern, feature flags, and the events module — with no
  paid APIs, no new privacy surface beyond user-entered dates, and near-zero
  runtime cost.
- It attacks a real, verified gap: Transportstyrelsen sends no inspection
  reminder, the deadline math (36/24/14 months, age-based special cases) is
  genuinely confusing, and no community competitor offers it. It is the
  "practical daily value between meets" feature, for every powertrain and
  every vehicle age.
- The two structural risks — law changes and wrong user input — are designed
  out (server-side config rules table; reminder-aid framing) rather than
  hoped away.
- Do **not** couple it to the reg-number lookup: ship manual-first, and let
  this feature's engagement be the evidence that finally justifies (or
  buries) that parked proposal's vendor spike.

## Appendix A — files referenced

- `functions/src/garage/garage-core.ts`, `functions/src/garage/manageVehicle.ts`
- `functions/src/shared/memberActor.ts`, `functions/src/shared/featureFlags-core.ts`
- `functions/src/notifications/deliver.ts`, `functions/src/notifications/notifications-core.ts`, `functions/src/notifications/scheduled.ts`, `functions/src/notifications/pushTokens.ts`
- `functions/src/events/manageEvent.ts`, `functions/src/events/events-core.ts`, `functions/src/events/eventLifecycle.ts`
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/garage/` (`Vehicle.kt`, `GarageScreen.kt`, `VehicleFormScreen.kt`, `GarageCoordinator.kt`, `FirebaseGarageRepository.kt`, `GarageStrings.kt`)
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/notifications/` (`NotificationSettings.kt`, `NotificationsScreen.kt`)
- `apps/admin/src/features/feature-flags/`
- `docs/product-decisions.md`, `docs/firebase-data-model.md`
- `docs/proposals/vehicle-reg-lookup.md` (parked; cross-referenced in §2.5, §6 Phase 3)

_External sources (Transportstyrelsen et al.) are cited inline in §3; rules
verified 2026-07-10 and must be re-verified against `transportstyrelsen.se`
before any build starts, especially the pending 2026 veteran-inspection
decision._
