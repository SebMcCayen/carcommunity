# iOS subscription testing

The app uses StoreKit 2 and the existing `subscription-verify` callable. Apple
Server Notifications V2 enter through `subscription-handleAppleNotification`
(HTTPS POST, europe-west1). Both paths verify Apple signatures and account
binding, acquire the same per-account lease, and use `applyEntitlement` for
subscription records, user projections and Auth claims. Provider configuration
is still off by default. This change does not activate billing or deploy anything.

## Test now, without payment agreements or App Store products

Open `apps/ios/KCC.xcodeproj`. Run `StoreKitSubscriptionIntegrationTests` and
`SubscriptionCoordinatorTests` on an iOS simulator using the normal KCC scheme.
The integration tests create a real `SKTestSession` from the test-bundle resource
`KCCTests/Resources/Subscriptions.storekit`, disable payment dialogs and exercise
product loading, purchase/account binding, restore, unfinished transactions,
renewal, refund and pending purchase/approval. They never call Firebase.

```sh
xcodebuild -project apps/ios/KCC.xcodeproj -scheme KCC \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -only-testing:KCCTests/StoreKitSubscriptionIntegrationTests \
  -only-testing:KCCTests/SubscriptionCoordinatorTests test
```

For interactive StoreKit debugging, choose the **KCCStoreKit** scheme. Its Run
action selects the local catalogue. Test prices (39 / 119 SEK) are fixtures,
not the configured App Store prices. Both products are monthly, in one group;
Supporter is level 1 and Plus level 2. Test resources belong only to KCCTests,
not the shipping app. Normal KCC and KCCReleaseSmoke schemes do not enable this
configuration.

**Local Xcode transactions are signed by Xcode, not the App Store.** The real
backend deliberately rejects them. Use the automated tests to test the purchase
flow before payment setup; use Apple's sandbox to validate the full app →
Firebase entitlement chain. There is no client-side membership bypass and no
production acceptance of local test certificates.

## Finish App Store setup, then test the complete chain

1. Ensure Apple Developer membership and Paid Apps Agreement are active; complete
   the required banking and tax information.
   The Xcode target explicitly links StoreKit. Confirm that the matching explicit
   bundle ID has this capability enabled in Certificates, Identifiers & Profiles
   and that the signing profile uses that ID (not a wildcard).
2. In the app with bundle ID `com.kungsbackacarcommunity.app`, create
   `plus_monthly` and `supporter_monthly` as auto-renewable **one-month**
   subscriptions in **one group**. Configure levels, names, Swedish/English
   metadata, prices, territory availability and review information. The iOS
   catalogue intentionally rejects a partial catalogue, separate groups or
   a different duration. Sandbox metadata changes may take up to one hour.
   Leave Family Sharing off: this flow requires a purchase bound to the owning
   CarCommunity account and does not accept family-shared transactions.
3. Deploy the reviewed backend and rules with `apple.enabled` still false.
   Copy the HTTPS URL reported for `subscription-handleAppleNotification` into
   the app's **App Store Server Notifications → Sandbox Server URL**, choosing
   **Version 2**. Do not use a callable URL or add Firebase authentication to
   Apple's request. The handler authenticates the outer and nested JWS with
   Apple's server library, configured bundle/app ID, environment and certificate
   revocation checks. Signed-JWS verification needs no App Store private key.
4. For the controlled test, set the Firestore document
   `config/subscriptionProviders`'s Apple map to
   `{ "enabled": true, "environment": "sandbox" }`, preserving its Google map.
   Production additionally requires the real numeric `appAppleId`; never copy
   a placeholder app ID. TestFlight also uses sandbox.
5. Install the KCC build from TestFlight (or a development-signed build with
   StoreKit Configuration set to None). Use a Sandbox Apple Account for
   renewal-rate controls, purchase-history reset and billing retry/grace tests.
   For TestFlight sandbox controls, follow Apple's Media & Purchases sign-out
   and Developer-settings sign-in instructions linked below.
6. Buy each plan with a dedicated CarCommunity test account. Check
   `subscriptions/{uid}`, `users/{uid}` projections and refreshed Auth claims.
   Request a test notification using Apple's App Store Server API after adding
   its API credentials, and confirm successful delivery; `TEST` acknowledges
   without changing entitlement. API test-notification requests are optional
   for the app purchase and require separate server API credentials.

The endpoint returns 200 for verified applied/test/irrelevant events, 400 for
invalid signatures or account/payload mismatches, and 503 for a disabled provider,
transient verification/storage failure or a purchase whose ownership the device
has not registered yet. Apple can redeliver after a 503. Invalid HTTP methods,
content types and oversized payloads are rejected before verification. No signed
payload, raw transaction ID or renewal JWS is logged or persisted.

## Acceptance before public activation

Verify each scenario in sandbox, including the resulting server record and claims:

- First Plus and Supporter purchase; cancel the payment sheet; pending purchase
  and later approval; network/backend failure followed by unfinished-transaction retry.
- Restore after reinstall; sign out and switch CarCommunity account. A purchase
  bound to one Firebase UID must never grant another UID access.
- Automatic renewal while the app is closed; reopening membership; opening and
  dismissing Apple's in-app subscription management sheet.
- Plus → Supporter upgrade and scheduled Supporter → Plus downgrade.
- Cancel renewal: retain access through the paid deadline. Expiry and refund:
  remove access. Billing retry: remove access unless the verified grace period
  is still effective; recover on renewal.
- Repeated and out-of-order deliveries, including an old Plus refund after a
  Supporter upgrade. A device transaction from an old period must not overwrite
  the newer server state. A device JWS for the same period must not erase the
  renewal/refund/grace state delivered by Apple's server.
  If V2 reports the chain still active/in grace but the enclosed transaction is
  revoked, the handler preserves canonical state: the refund can concern an
  older period. A current transaction/renewal must supply the current dates and
  tier; the handler does not infer these from the refunded period.
- Disabled/deleted account and a manual/other-provider replacement: notifications
  must not recreate the account or overwrite the replacement entitlement.

`subscriptionAppleEvents/{chainHash}` records the latest purchase date, signed
date and source, committed atomically with subscription records. Duplicate
delivery reapplies the current canonical record to repair any failed Auth claim
write. The marker is backend-only and purged with the owning account. The existing
expiry and representation sweeps remain recovery paths, not a replacement for
testing Apple delivery. This implementation does not fetch missed notification
history automatically; investigate failed deliveries and replay them before launch.

Keep public billing disabled until sandbox acceptance is complete. For real App
Store purchases, configure the Production Server URL, production environment and
real app ID separately from the sandbox test run. The provider configuration
selects one environment at a time; do not switch a live production backend to
sandbox while customers are using it.

## Scope and native parity

Affected screen: S80 Membership. Presentation keeps the existing localized
Swedish/English labels, native touch sizes and status messages. iOS now presents
Apple's native management sheet and reconciles on dismissal/foreground return;
restore also works when product loading fails. Android already uses Google Play
Billing/RTDN and its native subscription-management link; Apple's JWS webhook,
StoreKit fixture and management sheet are permanent store-specific mechanisms
under ADR-002. Shared entitlement rules and the callable request/response contract
are unchanged. No new localization or Android implementation is required.

Automated StoreKit and emulator results establish the scenarios they exercise;
they do not establish a configured-device App Store purchase, Apple notification
delivery, or complete S80 visual/accessibility acceptance.

## Apple references

- [Testing throughout development](https://developer.apple.com/documentation/storekit/testing-at-all-stages-of-development-with-xcode-and-the-sandbox)
- [Testing with sandbox](https://developer.apple.com/documentation/storekit/testing-in-app-purchases-with-sandbox)
- [Troubleshooting sandbox availability and capabilities](https://developer.apple.com/documentation/technotes/tn3186-troubleshooting-in-app-purchases-availability-in-the-sandbox)
- [StoreKit Test sessions](https://developer.apple.com/documentation/storekittest/sktestsession)
- [App Store Server Notifications V2 data](https://developer.apple.com/documentation/appstoreservernotifications/data)
- [Apple's explanation of chain status and older-period refunds](https://developer.apple.com/videos/play/wwdc2023/10141/)
