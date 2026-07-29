# Privacy Policy — Kungsbacka Car Community

> **DRAFT — requires legal review before publishing.** This document was drafted
> from the application's actual data-handling code (Firestore/RTDB/Storage rules,
> Cloud Functions, and the Android app). It has **not** been reviewed by a lawyer or
> data-protection specialist. Do not publish it as-is. All `[PLACEHOLDER]` fields
> require a human decision before this policy is valid.
>
> **Hosting requirement:** Google Play and the app both require this policy to live at a
> **public, stable HTTPS URL**. The recommended home is the project's existing Firebase
> Hosting site (`kungsbacka-car-community`) as a `/privacy` page — e.g.
> `https://[HOSTING-DOMAIN]/privacy`. The chosen URL must be entered in **Play Console →
> App content → Privacy policy** and surfaced inside the app (settings/onboarding).
> Since current hosting serves the admin SPA (`apps/admin/dist`) with a catch-all rewrite
> to `index.html`, publishing a real `/privacy` route requires either adding a static page
> to the hosting target or a dedicated hosting rewrite. **This is a hosting task, not part
> of this doc-only deliverable.**

**Last updated:** [DATE] · **Version:** DRAFT 0.1

---

## 1. Who we are (Data Controller)

The data controller for the Kungsbacka Car Community app ("the App") is:

- **Controller:** `[CONTROLLER LEGAL NAME]`
- **Organization number:** `[ORG NUMBER]`
- **Registered address:** `[ADDRESS]`
- **Contact email (privacy):** `[PRIVACY CONTACT EMAIL]`
- **Data Protection Officer / privacy contact:** `[DPO NAME OR "not appointed"]`

If you have questions about this policy or how we handle your data, contact us at the
email above.

> Human decision: confirm the legal entity operating the community and whether a formal
> DPO is required. Under GDPR a DPO is mandatory only in specific cases; a named contact
> is sufficient otherwise.

---

## 2. Scope

This policy explains what personal data the App collects, why, on what legal basis, who we
share it with, how long we keep it, and the rights you have. It applies to the Android app
and its Firebase backend. The App is aimed at car enthusiasts in the Kungsbacka area
(Sweden) and is subject to the EU General Data Protection Regulation (GDPR) and Swedish
data-protection law, supervised by the Swedish Authority for Privacy Protection
(**Integritetsskyddsmyndigheten, IMY**).

---

## 3. Age and children

The App includes social features (community chat, live location sharing, group drives)
that are **not suitable for young children**.

- **Minimum age to use the App:** `[AGE FLOOR]` (recommended 15 or 16). See
  `app-content-checklist.md` for the rationale; the presence of user-to-user chat and
  precise real-time location sharing argues for a non-child audience.
- During onboarding we record a **driving-licence confirmation** timestamp
  (`licenceConfirmedAt`) together with acceptance of the Terms and this Privacy Policy.
  Onboarding **no longer collects a self-declared age confirmation**.
- Accounts created before that change kept the older `ageConfirmedAt` field. It holds a
  timestamp **only for members who actually confirmed the old 18+ wording**; on accounts
  that were created but never finished the old onboarding it is empty, and on a few it is
  missing altogether. Where a timestamp exists we keep it, unchanged, as the record of
  what that member confirmed at the time: we never reinterpret it, never treat it as a
  driving-licence confirmation, and never write a new one.
- We do not knowingly collect data from children below our stated minimum age. If you
  believe a child has provided us personal data, contact us and we will delete it.

> Human decision: set the minimum age. Note that under Swedish implementation of GDPR
> Art. 8, the age of digital consent is **13**; however the product-risk profile
> (location + open chat) supports a higher self-imposed floor.
>
> Human decision — **open**: the in-app self-declared age gate was replaced by a
> driving-licence confirmation. There is now **no age question anywhere in onboarding**.
> A Swedish category-B licence implies 18+, so the licence confirmation is an *indirect*
> age signal at best, and it does not cover members who join without driving. Decide
> whether to (a) rely on that indirect signal, (b) re-add an explicit age checkbox
> alongside the licence one, or (c) state the floor in the Terms only — and make the
> answer match `app-content-checklist.md` Section 4 and `docs/product-decisions.md`
> ("Appen är 18+ i MVP").

---

## 4. What data we collect

We only collect what the App's features require. The table below reflects what the code
actually stores.

### 4.1 Account and profile
- **Email address** — from your sign-in provider (Google Sign-In / Firebase Auth) on first
  login. Stored in a private, owner-only record.
- **Optional phone number** — only if you provide it.
- **Display name, profile photo (avatar), short bio** — your public profile.
- **Role / membership status** (member, active, suspended flags).
- **Consent timestamps** — driving-licence confirmation, Terms acceptance, Privacy Policy
  acceptance (plus a legacy age-confirmation timestamp, but only on those older accounts
  that actually gave one before the licence confirmation replaced it — see Section 3).

### 4.2 Vehicles (Garage)
- Vehicle **make, model, year, powertrain, engine description, free-text description**, and
  **photos** you upload (up to 5 vehicles).
- **Registration number (optional).** You may add your vehicle's registration plate. This
  field is **public by design** — it is stored on the shared vehicle record, which **any
  signed-in user of the App can read**. That is broader than "members": the read permission
  is not limited to paying members, and it is not withdrawn from suspended accounts. While a
  value is set, treat it as visible to the whole signed-in community — and note that anyone
  who saw it may have copied or screenshotted it, which clearing the field cannot undo.
  It is entirely optional: leave it blank, or clear it later, and no registration number is
  stored for that vehicle. We normalise what you type (trim the ends, collapse repeated
  spaces, upper-case) but never verify it, and we do not use it to query any vehicle register.
- We deliberately **do not** ask for or record VIN, insurance details, or a vehicle's
  location as structured data in our database.
- **Photo metadata (EXIF):** photos (vehicle and profile) are uploaded as the original image
  file you choose. Depending on your camera and device settings, that file may contain
  embedded **EXIF metadata — including GPS coordinates** of where the photo was taken. The App
  does **not** strip this metadata before upload, and does **not** read, index, or otherwise
  use it; it is simply stored inside the image file. If you prefer, you can remove location
  data from a photo on your device before uploading it.

### 4.3 Precise location (only when you turn it on)
- **Live location sharing / group drives:** when you start a session, the App streams your
  **precise GPS position** (latitude, longitude, accuracy, heading, speed) to other active
  members while the session is active. A visible ongoing notification is shown the whole
  time. Positions are stored transiently and expire automatically (see Retention).
- **Drive recording ("saved drives"):** if you record a drive, we store a **route trace**
  and summary statistics (distance, duration, average speed) and an approximate start/end
  area label. Exact coordinates are not stored in our database as an address; the route
  trace is stored as a file you own.
- **Kronjakt (Crown Hunt):** when you claim a location-based point, your device position is
  used **momentarily** to verify you are within range. We store the resulting distance and
  claim outcome, not your raw coordinates.
- Location sharing is **always user-initiated**. The App does **not** request the Android
  background-location permission (`ACCESS_BACKGROUND_LOCATION`), so there is **no passive or
  always-on location tracking**. Location is collected **only during a live-location session
  you start**, streamed by a foreground service that shows an ongoing notification the whole
  time. Because it is a foreground service, that session **may continue to send your location
  while the App is in the background**, until **you stop sharing** (or the session ends).

### 4.4 Community content
- **Event chat messages** (text you post in event chats), your **RSVP** status to events,
  and any **reports** you submit about content (reporter identity + reason, visible only to
  moderators/admins).

### 4.5 Points, badges and activity
- **Kronpoäng (points) ledger** and **badges** earned through participation. No monetary
  value and no location is stored in the points ledger.

### 4.6 Notifications
- A **hashed identifier of your push-notification token** — a one-way SHA-256 hash used to
  **register your device** for notifications. The hash is the device record's identifier, so
  when your token rotates (the notification service issues a new token) registering it creates
  a **new** device record rather than updating the old one; the App can explicitly **unregister**
  a device (for example when you sign out or turn notifications off) to remove its record. We
  store **only this hash, never the raw device token**, plus platform and app version. The raw
  token that would be needed to actually deliver a push is not persisted at rest.
- Your **notification preferences** and an in-app notification inbox (titles/short text).

### 4.7 Subscriptions / purchases
- If you subscribe, we store your **subscription status, entitlement, and validity dates**,
  plus a **hash** of the purchase token. The raw Google Play purchase token is hashed and
  **never stored, logged, or returned**. Payment itself is processed by Google Play; we do
  not receive or store your card or payment-instrument details.

### 4.8 Partners
- If you apply to become a partner, we collect the **business contact details** you submit
  (name, email, phone, company, organization number, website, message).
- If you bookmark an offer, we store the offer id and timestamp.

### 4.9 Diagnostics and moderation
- **Diagnostic reports** may include app version, OS version, platform, and a sanitized
  error message. These are **stripped server-side** of tokens, credentials, exact
  coordinates, route history, and personal messages; email is excluded by default.
- **Moderation records** (reports, actions, audit logs) are retained for safety and
  accountability.

### 4.10 Technical identifiers
- Standard technical data required to operate a networked app: **authentication user ID
  (UID)**, IP address (processed transiently by our infrastructure providers), device
  platform, and app/build version.

---

## 5. Why we use your data and our legal basis (GDPR Art. 6)

| Purpose | Data used | Legal basis |
|---|---|---|
| Create and operate your account; authenticate you | Account/profile, UID, email | Contract (Art. 6(1)(b)) |
| Show your profile, garage and community content to other members | Profile, vehicles, chat, RSVP | Contract (Art. 6(1)(b)) |
| Live location sharing / group drives | Precise location | **Consent** (Art. 6(1)(a)) — you start each session; withdraw by stopping |
| Drive recording | Route trace, drive stats | Consent (Art. 6(1)(a)) — you choose to record |
| Kronjakt point validation | Momentary device position | Contract / legitimate interest to run the feature you engaged (Art. 6(1)(b)/(f)) |
| Send push notifications you enabled | Hashed push token, preferences | Consent (Art. 6(1)(a)) |
| Points, badges, gamification | Activity, points ledger | Contract (Art. 6(1)(b)) |
| Process subscriptions | Subscription status, token hash | Contract (Art. 6(1)(b)) |
| Safety, moderation, abuse prevention | Reports, moderation/audit logs | Legitimate interest (Art. 6(1)(f)) |
| App stability and security | Diagnostics, technical identifiers | Legitimate interest (Art. 6(1)(f)) |
| Partner applications | Business contact details | Consent / pre-contract (Art. 6(1)(a)/(b)) |

> Human decision: confirm the legal-basis choices with your legal reviewer, especially the
> consent vs. legitimate-interest split for location and diagnostics.

---

## 6. Who we share data with (processors / sub-processors)

We do not sell your personal data. We share it only with service providers ("processors")
that operate the App on our behalf, and only as needed:

- **Google / Firebase (Google Ireland Ltd / Google LLC)** — our core backend: Firebase
  Authentication, Cloud Firestore, Realtime Database, Cloud Storage, Cloud Functions, and
  Firebase Cloud Messaging (push). Processes essentially all of the data categories above.
- **Google Play Services** — Google Sign-In (authentication), Play Integrity / Firebase App
  Check (anti-abuse device attestation), Firebase Cloud Messaging transport (push
  notifications), and Google Play Billing (subscription payments). Google Play Billing
  processes your payment; we receive only subscription status and a token hash.
- **Mapbox (Mapbox, Inc.)** — renders map tiles and map interactions in the App. To serve
  maps, Mapbox receives technical request data such as your **IP address** and the **map
  view / coordinates being displayed**, per Mapbox's own privacy terms. Your live-location
  positions are stored in our Firebase backend, not sent to Mapbox as user data, but map
  interaction inherently discloses what area you are viewing to Mapbox.

Each provider processes data under its own terms and applicable data-processing agreements.

> Human decision: confirm the exact Google/Mapbox contracting entities and that Data
> Processing Agreements (DPAs) are in place; link each provider's privacy terms.

---

## 7. International transfers

Our backend is configured to run in the EU (Firebase resources in the `europe-west1`
region). However, some providers above (notably Google and Mapbox, both US-headquartered)
may process limited data outside the EU/EEA. Where that happens, transfers are protected by
appropriate safeguards such as the EU **Standard Contractual Clauses** and/or the EU–US
Data Privacy Framework, as offered by each provider.

> Human decision: verify current transfer mechanisms with each provider and cite them.

---

## 8. How long we keep data (retention)

- **Account, profile, vehicles, points, notifications:** kept while your account is active.
- **Live location positions:** transient — stale after ~60 seconds, sessions expire at their
  chosen duration (1/2/4 h), and a scheduled sweep (runs every ~5 minutes) removes remaining
  position nodes once they are older than 15 minutes, so they are gone within ~15–20 minutes.
  "Hide me now" removes your latest position immediately.
- **Drive routes / saved drives:** kept until you delete them or your account.
- **Push tokens:** stored as hashes and refreshed; removed on account deletion.
- **Subscription records:** kept for the life of the entitlement and as required for billing
  records.
- **Diagnostics:** short-lived, sanitized.
- **Moderation / audit records:** retained for safety and legal accountability even after
  account deletion (legitimate interest).
- **Account deletion:** see Section 9 — soft-delete is immediate; a deletion request is
  retained for up to 30 days, then permanently purged by a daily cleanup job — typically
  within **~30–31 days** of the request.

---

## 9. Your rights

Under GDPR you have the right to: **access** your data, **rectify** inaccurate data,
**erase** your data ("right to be forgotten"), **restrict** or **object** to processing,
**data portability**, and to **withdraw consent** at any time (e.g. by stopping location
sharing or disabling notifications).

- **Access / correction:** most profile, vehicle and preference data can be viewed and edited
  directly in the App. For other requests, contact `[PRIVACY CONTACT EMAIL]`.
- **Erasure / account deletion:** you can request deletion **in the App** (account
  deletion). This immediately disables your account and schedules a full purge. Your
  deletion request is retained for up to 30 days, then a daily cleanup job permanently
  deletes your profile, private data, vehicles, saved drives, push tokens, notifications,
  points ledger, and your uploaded images (profile and vehicle photos, route files) —
  typically within **~30–31 days** of your request.
  - **What we retain after deletion, and why:** limited **moderation reports, moderation
    actions and admin audit logs** (safety and legal accountability), **anonymized partner
    analytics** (stored only as non-reversible hashes with a short TTL), and
    **hash-scoped Crown Hunt claim records** (points integrity). These do not identify you
    by name. Event chat messages and RSVPs authored under your display name may persist in
    community history in the current version — flag for your legal reviewer.
- **Complaints:** you may lodge a complaint with the Swedish supervisory authority,
  **Integritetsskyddsmyndigheten (IMY)** — https://www.imy.se — or your local EU data
  protection authority.

---

## 10. Cookies and similar identifiers

The Android app itself does not use browser cookies. It uses standard mobile identifiers to
function: your authentication UID, a hashed push-notification token, and device-attestation
signals via Play Integrity / App Check. Any associated websites (e.g. the hosting site that
serves this policy or the admin console) may use strictly necessary cookies.

---

## 11. Security

We protect your data with: transport encryption (**HTTPS/TLS** for all network traffic),
server-side Security Rules that govern data access and enforce authentication, ownership,
field validation, and suspension checks on the everyday user data written directly by the
client, Cloud Functions with server-side authorization and auditing for specific sensitive or
privileged operations (e.g. moderation and admin actions, role and subscription changes, and
the points ledger) rather than for all writes, **hashing** of sensitive tokens (push tokens,
purchase tokens) so raw values are never stored, device attestation (Play Integrity / App
Check) to deter abuse, and data minimization by design (e.g. no VIN or insurance data;
approximate-area labels instead of exact addresses where possible — the optional registration
number in §4.2 is the one vehicle identifier we store, it is readable by any signed-in user,
and it is stored only because you chose to publish it). No system is perfectly secure, but we
take reasonable measures appropriate to the data.

---

## 12. Changes to this policy

We may update this policy as the App evolves. Material changes will be communicated in the
App and/or by updating the "Last updated" date at the top. Continued use after an update
constitutes acknowledgment of the revised policy where permitted by law.

---

## 13. Contact

- **Privacy contact:** `[PRIVACY CONTACT EMAIL]`
- **Controller:** `[CONTROLLER LEGAL NAME]`, `[ADDRESS]`
- **Supervisory authority:** Integritetsskyddsmyndigheten (IMY), https://www.imy.se

---

### Appendix — placeholders to resolve before publishing
- `[CONTROLLER LEGAL NAME]`, `[ORG NUMBER]`, `[ADDRESS]`
- `[PRIVACY CONTACT EMAIL]`, `[DPO NAME OR "not appointed"]`
- `[AGE FLOOR]` (recommend 15–16; minimum legal 13 in Sweden)
- `[HOSTING-DOMAIN]` / final public policy URL
- `[DATE]` last-updated date
- Legal review of legal bases, retention of chat/RSVP after deletion, and transfer mechanisms.
