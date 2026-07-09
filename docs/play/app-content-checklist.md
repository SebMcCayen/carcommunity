# Google Play — App content declarations checklist

> **DRAFT answers grounded in the app.** Covers the **App content** section of Play Console
> that must be completed before an Internal testing rollout can graduate (and several items
> are required even for Internal testing). Verify each answer; `[PLACEHOLDER]` and
> "Human decision" flags need a person to confirm. App: **Kungsbacka Car Community**,
> applicationId `com.kungsbackacarcommunity.app`, versionName 0.1.0.

---

## 1. Privacy policy — **required**

- **Status:** URL pending hosting. Enter a public HTTPS URL that serves
  `docs/play/privacy-policy.md` (recommended: `https://[HOSTING-DOMAIN]/privacy` on the
  existing Firebase Hosting site).
- **Blocker:** the policy is a **DRAFT pending legal review**, and hosting a `/privacy` page
  requires a small hosting change (current hosting serves the admin SPA with a catch-all
  rewrite). Both must be resolved before this field can be filled truthfully.
- **Action:** Play Console → App content → Privacy policy → paste URL.

---

## 2. Data safety — **required**

- Fill from `docs/play/data-safety.md`.
- Key global answers: **encrypted in transit = Yes**; **users can request deletion = Yes**
  (in-app account deletion + 30-day purge). Provide the in-app deletion path and, if Google
  asks, a web deletion-request URL.

---

## 3. Content rating (IARC questionnaire) — **required**

Complete the IARC questionnaire. Suggested answers grounded in the app:

- **Category:** Social / Reference / Utility (a community app), **not** a game — despite the
  "Kronjakt/Crown Hunt" gamification, the app is a community/social utility. Choose the
  questionnaire category that best matches (likely "Social Networking / Communication" or
  "Reference, News, or Educational"). **Human decision.**
- **Violence / scary content:** No.
- **Sexual content / nudity:** No.
- **Profanity:** None authored by the app. Note **user-generated content** exists (chat) —
  answer the UGC questions accordingly (see below).
- **Controlled substances / gambling:** No. (Points/badges are non-monetary and not
  gambling.)
- **User interaction / user-generated content:** **Yes** — users can communicate (event
  chat) and share content. This typically requires acknowledging UGC and moderation.
- **Shares user location with other users:** **Yes** — live location sharing / group drives
  share precise location with other members. Declare this honestly; it can affect the rating
  and audience.
- **Digital purchases:** **Yes** — subscription via Google Play Billing.
- **Personal information sharing:** users can share profile info and location with other
  users.

> The location-sharing + open-chat answers will likely push the rating toward a teen/mature
> band and reinforce a non-child target audience (Section 4).

---

## 4. Target audience and children — **required**

- **Recommended target age group:** **`[AGE FLOOR]` and up — recommend 16+ (minimum 15)**,
  and **do not** include under-13 age bands.
- **Rationale:** the app offers **precise real-time location sharing** between users and
  **open user-to-user chat**, both of which are inappropriate for children and would pull
  the app into Google's **"Families / Designed for Families"** obligations and stricter
  data rules if child audiences were selected. The onboarding already records an
  `ageConfirmedAt` consent. Selecting a 16+ (or 15+) audience keeps the app out of the
  child-directed program, which matches the feature set.
- **"Do you want your app in the Designed for Families program?"** → **No.**
- **Appeal to children:** answer **No** — the branding and features target car enthusiasts,
  not children.

> Human decision: set the final age floor. Under Swedish GDPR the digital-consent age is 13,
> but the product risk profile supports 15–16+. This must match the age gate you enforce in
> onboarding and the content-rating answers.

---

## 5. Ads — **required**

- **Does your app contain ads?** → **Yes** (conservative and the likely-correct answer).
- **Rationale:** the app shows in-app **digital billboards** — explicitly **sponsored
  third-party partner placements**, labelled "Sponsrad placering", with impression tracking
  and call-to-action buttons (`packages/shared/src/digital-billboards.ts`,
  `billboards/BillboardsScreen.kt`) — plus **partner offers/promotions** (`companies`,
  `offers`). Google Play's "contains ads" declaration covers **display ads, native ads, and
  banner ads**, not only ads delivered through a third-party ad SDK; the delivery mechanism
  does not matter. The only house-ads carve-out is for cross-promoting **your own other
  apps** — these billboards promote **third-party partner businesses**, so the carve-out does
  **not** apply. There is no AdMob or third-party ad SDK, but sponsored third-party
  promotional placements shown to users are still ads. The honest, conservative answer is
  therefore **Yes**.
- **Downstream implication:** declaring ads adds the **"Contains ads"** store-listing label
  and feeds the **content-rating / target-audience** handling (in-app ads interact with the
  16+ audience choice and Play's ad-content policy). Keep this consistent with Sections 3–4.
- **Ad-policy compliance:** because ads are declared, ensure the billboards/offers meet Play's
  ad requirements (clearly labelled, dismissible where applicable, not interfering with app
  use, age-appropriate content).
- **Human decision:** confirm whether billboards/partner offers are sold as paid inventory
  (reinforces "Yes") and that ad content complies with the ad-policy points above.

---

## 6. News / COVID-19 / Government apps — **required**

- **News app?** → **No.**
- **COVID-19 contact tracing / status app?** → **No.**
- **Government app?** → **No.**

---

## 7. Financial features — **required (if applicable)**

- **Does the app provide financial features?** The relevant declaration is **in-app
  subscriptions via Google Play Billing** (`billing-ktx`, `subscription/PlayBillingRepository.kt`).
  This is a standard Play digital-goods subscription, **not** a banking/lending/crypto
  financial-services product — answer the financial-**services** questions as **No**, and
  ensure the subscription is set up as a Play in-app product.
- No lending, no crypto, no personal loans, no debt management.

---

## 8. Permissions justification — **required for sensitive permissions**

Declared permissions (`apps/android/app/src/main/AndroidManifest.xml`):

| Permission | Requested? | Justification |
|---|---|---|
| `INTERNET` | Yes | Core networking (Firebase, Mapbox). |
| `ACCESS_FINE_LOCATION` | Yes (runtime) | Precise location for user-initiated live-location sharing, group drives, drive recording, and Kronjakt point validation. |
| `ACCESS_COARSE_LOCATION` | Yes | Companion to fine location. |
| `FOREGROUND_SERVICE` | Yes | Runs the live-location sharing service while active. |
| `FOREGROUND_SERVICE_LOCATION` | Yes | Android 14+ typed foreground service for location streaming. |
| `POST_NOTIFICATIONS` | Yes | Show push notifications the user enabled. |

- **Background location:** **`ACCESS_BACKGROUND_LOCATION` NOT requested.** It is absent from the
  manifest, Kotlin source, and merged release manifest, so the app has **no passive/always-on
  background location** and **no Play background-location declaration / prominent-disclosure
  review is required** — do **not** accidentally opt into it. Keep the distinction clear:
  location is collected **only during a user-initiated live-location session**, streamed by a
  **foreground service** (`location/LocationSharingService.kt`,
  `foregroundServiceType="location"`) with a visible ongoing notification, which **may
  continue while the app is backgrounded until the user stops sharing**. That is
  foreground-service location — *not* the background-location permission, and *not* the same
  as "only while the app is on screen."
- **Foreground-service (location) type** must be declared/justified in Play Console's
  foreground-service form: purpose = user-initiated live location sharing with other
  community members, with an ongoing notification. Prepare a short demo/description.
- **Camera:** not declared (photos via library/picker). Verify against final upload UI.

---

## 9. Play Integrity / App Check note

- The app uses **Firebase App Check with the Play Integrity provider** (release builds; a
  debug provider is used only in debug builds). This is device/app attestation for
  anti-abuse, not additional user-data collection.
- **Action items outside this doc:** enable the **Play Integrity API** for the app in Google
  Cloud / Play Console, register the App Check Play Integrity provider in the Firebase
  console, and ensure the **release signing SHA-256** is registered (tracked separately as
  the pending Play release SHA task). Without this, attested calls fail in production.

---

## 10. Other pre-rollout items (not strictly "App content" but required for release)

- **App access:** if sign-in is required to use the app, provide **test credentials / a
  demo account** in Play Console → App content → App access, so reviewers can log in.
  (Auth is Google Sign-In; provide a working test path.) **Human decision / setup.**
- **Government / official status:** N/A.
- **Store listing:** privacy policy URL also appears here.

---

## Human decisions summary
1. **Privacy policy URL** — resolve hosting + legal review, then paste.
2. **Age floor** (recommend 16+, min 15) — must match onboarding gate + content rating.
3. **Ads = Yes** — in-app sponsored digital billboards / partner promotions are ads under
   Play's display/native-ad definition (adds the "Contains ads" label). Confirm paid-inventory
   status and ad-policy compliance.
4. **Content-rating category** and honest UGC + location-sharing answers.
5. **App access test account** for reviewers (sign-in required).
6. **Play Integrity / App Check** enablement + release SHA registration (tracked separately).
