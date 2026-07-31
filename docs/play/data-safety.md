# Google Play — Data Safety form mapping

> **DRAFT — grounded in the app's actual data handling** (Firestore/RTDB/Storage rules,
> Cloud Functions, Android manifest + SDKs). Verify each row before submitting in
> **Play Console → App content → Data safety**. Where a human decision is needed it is
> flagged inline. Conservative choices were preferred.

## How to read this

For every data type Google asks four things:
1. **Collected?** — sent off the device to your servers/processors.
2. **Shared?** — sent to a third party (Google treats a processor that only acts on your
   behalf as **not** "shared"; "shared" means a separate party using it for its own purposes,
   or transfer for that party's use).
3. **Processing** — is it **required** or **optional** (user can use the app without it)?
4. **Purposes** — from Google's fixed purpose list.

Two account-wide answers (set once in the form):
- **Is all collected data encrypted in transit?** → **Yes.** All traffic uses HTTPS/TLS
  (Firebase SDKs, Cloud Functions, Mapbox).
- **Do you provide a way for users to request data deletion?** → **Yes.** The app has an
  in-app account-deletion flow (`functions/src/account/deleteAccount.ts`) that soft-deletes
  the account immediately. Deleted accounts are retained for up to 30 days, then permanently
  hard-purged by a daily cleanup job (`functions/src/account/scheduled.ts` →
  `account-purgeDeleted`), so full deletion typically completes within ~30–31 days of the
  request. Provide the in-app path and, if required, a web deletion-request URL.

> Note on "Shared": we treat **Google/Firebase and Mapbox as processors** (they operate the
> app on our behalf), so most rows are **Collected = Yes, Shared = No**. The clear exception
> is that using the map discloses map-view/technical data to **Mapbox**, and payments go to
> **Google Play Billing** — flagged below. Confirm this processor characterization with your
> legal reviewer; if any provider is deemed to use data for its own purposes, switch that
> row's "Shared" to Yes.

---

## Location

### Precise location — **Collected: Yes**
- **Shared:** No (stored in our Firebase Realtime Database). Map rendering exposes the map
  view to Mapbox (see Mapbox note) — this is technical, but be conservative and disclose.
- **Optional/Required:** **Optional** — only collected when the user starts live-location
  sharing / a group drive, records a drive, or claims a Kronjakt point. The
  `ACCESS_BACKGROUND_LOCATION` permission is **not** requested, so there is **no passive or
  always-on collection**. During a user-initiated live-location session the app streams
  location via a **foreground service (type: location) with an ongoing notification**, which
  **may continue while the app is backgrounded** until the user stops sharing or the session
  ends — this is foreground-service location, not background-location-permission collection.
- **Purposes:** App functionality (live location sharing, group drives, drive recording,
  location-based game feature).
- **Encrypted in transit:** Yes.
- **User can request deletion:** Yes. Also auto-expires (positions stale ~60s; a sweep runs
  every ~5 min and removes position markers once they are older than 15 min, so ~15–20 min).
- **Source:** `functions/src/live/live-core.ts`, `packages/shared/src/live-location.ts`,
  `firebase/database.rules.json`; Android `location/LocationSharingService.kt`
  (`foregroundServiceType="location"`), `play-services-location`.

### Approximate location — **Collected: Yes**
- Approximate start/end **area labels** for saved drives; approximate event area.
- **Shared:** No. **Optional.** **Purpose:** App functionality. Encrypted in transit: Yes.
  Deletion: Yes.
- **Source:** `packages/shared/src/saved-drives.ts`, `events-core.ts`.

---

## Personal info

### Name — **Collected: Yes**
- Display name (and, for partner applicants, contact name). **Shared:** No.
  **Required** (display name needed for a profile). **Purpose:** App functionality; account
  management. Encrypted: Yes. Deletion: Yes.

### Email address — **Collected: Yes**
- From the sign-in provider. **Shared:** No. **Required** (authentication). **Purpose:**
  Account management; app functionality. Encrypted: Yes. Deletion: Yes.
- **Source:** `functions/src/auth/provisioning.ts`, `userPrivate/{uid}`.

### Phone number — **Collected: Yes (optional)**
- Only if the user or a partner applicant provides it. **Shared:** No. **Optional.**
  **Purpose:** Account management. Encrypted: Yes. Deletion: Yes.

### User IDs — **Collected: Yes**
- Firebase Auth UID. **Shared:** No. **Required.** **Purpose:** App functionality; account
  management. Encrypted: Yes. Deletion: Yes.

### Other info (bio) — **Collected: Yes (optional)**
- Free-text profile bio. **Shared:** No. **Optional.** **Purpose:** App functionality.
  Encrypted: Yes. Deletion: Yes.
- **Vehicle registration number.** User-entered plate on a garage vehicle, normalised
  server-side (trim the ends, collapse repeated whitespace, upper-case) and never verified.
  **Shared:** No (not sent to third parties), but **readable by every signed-in user by
  design** — it is stored on the `vehicles` document, whose read rule is
  `allow read: if isAuthenticated()`. Note this is wider than "members": it is gated on
  neither an active membership nor a suspension check. **Optional** (blank clears it).
  **Purpose:** App functionality. Encrypted: Yes.
  Deletion: Yes — cleared by the user at any time, and removed with the vehicle/account.
- **Source:** `functions/src/garage/garage-core.ts` (`normaliseRegistrationPlate`),
  `firebase/firestore.rules` (`match /vehicles/{vehicleId}`).

> Address / race / political / religious / sexual-orientation data: **not collected.**

---

## Financial info

### Purchase history — **Collected: Yes**
- Subscription status, entitlement, validity dates, and a **hash** of the purchase token.
  Raw purchase token is never stored. **Shared:** the payment transaction goes to **Google
  Play Billing** (Google processes the payment). **Optional** (only if user subscribes).
  **Purpose:** App functionality (manage subscription). Encrypted: Yes. Deletion: subscription
  records retained as billing records; token hash removable.
- **Source:** `functions/src/subscription/verify.ts`, `subscription-core.ts`; Android
  `subscription/PlayBillingRepository.kt` (`billing-ktx`).
- **Payment/card details:** **Not collected** — handled entirely by Google Play; we never
  receive card or payment-instrument data.

---

## Messages

### Other in-app messages — **Collected: Yes**
- Event **chat message text** and content **reports**. **Shared:** No (visible to other
  event members by design; reports visible only to moderators). **Optional** (only if the
  user posts). **Purpose:** App functionality. Encrypted: Yes. Deletion: account deletion
  purges the user's account; note chat text authored under a display name may persist in
  community history in the current version (flagged in privacy policy §9).
- **Source:** `packages/shared/src/event-chat.ts`, `events-core.ts`.

---

## Photos and videos

### Photos — **Collected: Yes**
- **Profile avatar** and **vehicle photos** the user uploads (Cloud Storage). **Shared:** No.
  **Optional.** **Purpose:** App functionality (personalize profile, show vehicles).
  Encrypted: Yes. Deletion: Yes — `profileImages/{uid}/` and `vehicleImages/{uid}/` are
  purged on account deletion.
- **Source:** `firebase/storage.rules`; Android uses `coil-compose` to display.
- **Videos:** not collected. **Camera permission:** not declared (photos come from the
  picker/library, not in-app capture) — verify against final image-upload UI.

---

## App activity

### In-app actions / other user-generated content — **Collected: Yes**
- RSVP status, points/Kronpoäng ledger, badges, saved offers, drive summaries, Crown Hunt
  claim outcomes. **Shared:** No. **Optional** (feature-dependent). **Purpose:** App
  functionality; gamification is core functionality. Encrypted: Yes. Deletion: Yes for
  user-owned records; hash-scoped claim/points-integrity records retained.
- **Source:** `packages/shared/src/points.ts`, `crown-hunt.ts`, `partner-offers.ts`,
  `saved-drives.ts`.

---

## App info and performance

### Crash logs / Diagnostics — **Collected: Yes**
- Sanitized diagnostic reports: app version, OS version, platform, safe error message/code.
  Server-side stripped of tokens, credentials, exact coordinates, route history, personal
  messages; email excluded by default. **Shared:** No. **Optional.** **Purpose:** App
  functionality; analytics/diagnostics. Encrypted: Yes. Deletion: short-lived.
- **Source:** `functions/src/diagnostics/*`, `packages/shared/src/diagnostics.ts`.
- **Firebase Crashlytics:** **now IS in the Android dependency set** (`firebase-crashlytics`,
  via the Firebase BoM — see `docs/crashlytics.md`). Collection is ON for release builds and
  OFF for debug. It uploads full stack traces plus a fixed, app-generated set of custom keys
  and breadcrumbs (build/version, feature flags, screen name, whether live sharing is on);
  `setUserId` is never called, and no uid, email, display name, coordinates, message content
  or registration number is attached by us. The SDK itself additionally collects a Crashlytics
  Installation UUID, the Firebase installation ID and device state, retained by Google for
  90 days.
  **This supersedes the earlier "not detected — do not declare" note.** The declaration
  wording is a human decision — see "Human decisions before submitting" item 2 — and touches
  the Crash logs / Diagnostics rows above and the Device-or-other-IDs row below.

---

## Device or other IDs

### Device or other IDs — **Collected: Yes**
- A **hashed push-notification (FCM) token** (SHA-256 hash, never the raw token) plus device
  platform / app version, and Play Integrity / App Check attestation signals. **Shared:** No
  (FCM transport is Google as processor). **Required** for push if the user enables
  notifications; attestation required for anti-abuse. **Purpose:** App functionality
  (notifications); fraud prevention / security. Encrypted: Yes. Deletion: Yes — token hashes
  purged on account deletion.
- **Source:** `functions/src/notifications/pushTokens.ts`; Android
  `push/KccMessagingService.kt`, Firebase App Check Play Integrity.

---

## Data types NOT collected (declare as not collected)

- Health & fitness, Contacts, Calendar, SMS/call logs, Web browsing history, Installed apps,
  Audio (voice/sound recordings), Music files, other files/docs, Payment card info, Sexual
  orientation, Political/religious beliefs, Race/ethnicity, **passive/always-on background
  location** (no `ACCESS_BACKGROUND_LOCATION`; the location we do collect is the
  user-initiated foreground-service session described above).

---

## Summary table

| Data type | Collected | Shared | Optional/Required | Purpose(s) | In-transit enc. | Deletable |
|---|---|---|---|---|---|---|
| Precise location | Yes | No* | Optional | App functionality | Yes | Yes (auto-expires) |
| Approximate location | Yes | No | Optional | App functionality | Yes | Yes |
| Name | Yes | No | Required | Functionality, account mgmt | Yes | Yes |
| Email | Yes | No | Required | Account mgmt, functionality | Yes | Yes |
| Phone | Yes | No | Optional | Account mgmt | Yes | Yes |
| User ID (UID) | Yes | No | Required | Functionality, account mgmt | Yes | Yes |
| Bio (other) | Yes | No | Optional | App functionality | Yes | Yes |
| Registration number (other) | Yes | No† | Optional | App functionality | Yes | Yes |
| Purchase history | Yes | Yes (Play Billing) | Optional | App functionality | Yes | Partial (billing records) |
| In-app messages | Yes | No | Optional | App functionality | Yes | Yes (see note) |
| Photos | Yes | No | Optional | App functionality | Yes | Yes |
| App activity / UGC | Yes | No | Optional | Functionality | Yes | Yes |
| Diagnostics / crash | Yes | No | Optional | Functionality, analytics | Yes | Yes |
| Device/other IDs (push hash, attestation) | Yes | No | Req. for push | Functionality, security | Yes | Yes |

\* Precise location is stored in our own backend (not "shared"), but map rendering exposes
map-view/technical data to Mapbox — see the processor note at the top and confirm with legal.

† The registration number is not "shared" in Play's sense (no third party receives it), but it
is deliberately readable by every other signed-in user — not just members — and the user opts
in by filling the field.

---

### Human decisions before submitting
1. **Processor vs. sharing** characterization for Google/Firebase and **Mapbox** (drives
   the "Shared" column). Default here: processors → not shared, except Play Billing payment.
2. **Crashlytics is now shipped** (Android, release builds only — `docs/crashlytics.md`).
   Confirm that the Crash logs / Diagnostics and Device-or-other-IDs rows, and the
   Google-as-processor characterization in item 1, are worded to cover it; and decide whether
   any further analytics SDK (e.g. GA4) will be added before release.
3. Confirm no in-app camera capture (Photos row assumes library upload; camera permission is
   not declared).
4. Confirm the retention/deletion nuance for chat text authored under a display name.
