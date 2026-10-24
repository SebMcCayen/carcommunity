# iOS release readiness and iPhone verification

This is the current iOS release checklist under [ADR-002](adr/002-ios-app-rescope.md).
Implementation and CI success do not establish that Apple services work on a
provisioned iPhone. Record actual results before a TestFlight rollout. The legacy
migration checklist is historical; the Android device checklist remains separate.

## Before Apple enrollment is complete

Run from the repository root:

```bash
python3 apps/ios/scripts/release-preflight.py
python3 -m unittest discover -s apps/ios/scripts -p 'test_*.py'
xcodebuild -project apps/ios/KCC.xcodeproj -scheme KCC \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO test
```

The preflight verifies source versioning, localized changelog entries, privacy
manifest declarations, background modes, and Apple entitlements. It requires only
Python 3 and never changes files. The simulator suite runs unit tests plus the
`KCCUITests` shell smoke tests in Swedish and English, nested Settings navigation,
and labeled/reachable Settings controls with large text. Build without
`KCC/GoogleService-Info.plist` and without a Mapbox token for these smoke tests;
they use the actual config-less app, without an authentication bypass or production
writes. A configured build fails the initial shell assertion intentionally.

These smoke checks do not cover authenticated screens, purchases, push delivery,
real map rendering, GPS, or a full VoiceOver audit. Those need the checks below.
Android already has native Compose UI tests; this change adds the corresponding
iOS UI test infrastructure without changing shared product behavior or contracts.

## After enrollment: configuration and first archive

- [ ] In Xcode, sign in to the enrolled Apple Account and select the paid team.
      Set `DEVELOPMENT_TEAM` locally; do not commit certificates or private keys.
- [ ] Register bundle ID `com.kungsbackacarcommunity.app`; enable Sign in with
      Apple, Push Notifications, and App Attest as required by the target. Refresh
      provisioning after capability changes. The app declares all three
      entitlements; portal configuration must agree with the signed binary.
- [ ] Configure the iOS app in the production Firebase project. Enable the Apple
      authentication provider and register App Attest/DeviceCheck in App Check.
      Follow [the App Check rollout plan](app-check.md) for enforcement.
- [ ] Put its `GoogleService-Info.plist` at `apps/ios/KCC/GoogleService-Info.plist`
      (gitignored). Keep APNs `.p8` keys and signing material outside the repo.
- [ ] Upload the APNs key to Firebase Cloud Messaging with the correct team/key
      IDs. Debug uses development APNs; Release uses production.
- [ ] Configure the public Mapbox runtime token using `MAPBOX_ACCESS_TOKEN`.
      Never use a secret Mapbox downloads token (`sk.`) as the runtime token.
- [ ] Create the App Store Connect app record. If enabling paid plans, configure
      `plus_monthly` and `supporter_monthly`, Sandbox testers, and the backend
      provider switch per [deployment.md](deployment.md#store-subscription-verification-disabled-by-default).
      TestFlight uses StoreKit's sandbox; keep public purchases disabled until
      purchase/restore and the required subscription lifecycle checks are verified.

Set `DEVELOPMENT_TEAM` and `MAPBOX_ACCESS_TOKEN` in your local shell/environment,
then run the read-only local input checks:

```bash
python3 apps/ios/scripts/release-preflight.py --release
```

The release mode checks presence and shape, including Firebase bundle ID, without
printing config values. It cannot validate an online token, enrollment status,
portal capabilities, certificate/profile validity, deployed backend settings, or
App Store Connect products. It does not inject environment variables into Xcode:
supply the same team and token as Xcode build settings when archiving.

- [ ] Archive the Release scheme for a generic iOS device with the production
      Firebase configuration and runtime token included.
- [ ] Validate the signed archive in Xcode Organizer. Inspect the embedded
      provisioning profile/entitlements for Apple sign-in, production APNs and
      production App Attest. Confirm the privacy manifest is in the app bundle.
- [ ] Confirm the build uploaded its dSYM and perform the
      [one-time Crashlytics check](crashlytics.md#one-time-ios-verification).
- [ ] Upload to App Store Connect, wait for processing, and install through
      TestFlight on a real iPhone. Store the build/version in the results below.

## Physical-iPhone acceptance checks

Use dedicated test accounts and avoid real personal content. Exercise the
shipping Release build. Never operate drive/chat controls while driving; use a
passenger or stationary setup for interactions. Test actual movement safely.

| Area | Verify on iPhone |
| --- | --- |
| Authentication | Apple sign-in completes, cancellation returns to the login screen, sign-out removes the authenticated UI, and relaunch restores the right account. Apple and Android Google accounts are separate (ADR-002). |
| Onboarding/access | New accounts must accept required consent. Test Community/Plus/Supporter gates and restricted-account handling; sign-out and privacy stop remain available. |
| Maps/location | Real tiles load; location denial and later grant work; recenter, layer toggles, search, and saved places work. Merely opening the map must not start live sharing. |
| Background sessions | Explicitly start recording/live sharing, lock the phone and background the app, then resume. Verify position/session behavior and that Stop, Hide, expiry, sign-out, and restriction release background location demand. |
| Drive saving | Stop offers save/discard; save produces plausible statistics and a visible route; discard leaves no saved drive. Exercise interrupted upload/relaunch recovery, sharing, and owner deletion. |
| Social/events | Test RSVP, permitted check-in, event/group-drive screens, convoy invitation/start/end, community/event chat, friend requests and DMs using two accounts. Test reporting and blocking. |
| Garage/profile | Create/edit/delete a vehicle, choose a main car, upload profile/vehicle images, and verify orientation and removal of GPS metadata. Test permission denial/cancel flows. |
| Notifications | Grant/deny notification permission. Verify foreground/background delivery, cold-start tap navigation, preference opt-out, sign-out token cleanup, and that another account cannot receive the previous account's content. |
| StoreKit | In sandbox, purchase each allowed plan, restore, cancel the purchase sheet, interrupt/retry backend verification, and confirm backend-approved tier changes. Verify cancellation, expiry, refund/revocation behavior before public paid rollout. |
| App Check/backend | Verify production-mode App Attest/DeviceCheck tokens are accepted and deployed callables/rules/indexes support the build; a green config-less CI run does not prove this. |
| Privacy/account | Update privacy preferences; Hide me now removes live visibility; delete a disposable account and confirm session/data cleanup. Test Supporter crown visibility and blocked users. |
| Stability/accessibility | Confirm symbolicated Crashlytics reports; test airplane mode, network recovery, light/dark mode, Swedish/English, largest Dynamic Type, and VoiceOver on critical flows. |

## Submission and evidence

- [ ] Enter age rating, screenshots, release text, support/privacy URLs, review
      contact and instructions, and App Store privacy answers consistent with
      Firebase SDKs, crash reporting, location, user content, and purchases.
- [ ] Verify the actual hosted Terms and Privacy pages from the app.
- [ ] Resolve blocking archive validation warnings and TestFlight feedback.
- [ ] Record evidence below; leave untested items unchecked.

| Build / commit | Device / iOS | Tester / date | Passed areas | Failures / linked issues |
| --- | --- | --- | --- | --- |
| Not yet verified | — | — | — | Apple setup and physical-device validation pending |

Turn-by-turn navigation is intentionally deferred on iOS. A signed TestFlight
build, completed device checks, and store configuration are release gates, not
additional MVP feature implementations. GitHub's Validate iOS workflow runs
config-less simulator tests and an unsigned Release device build; it does not
sign or upload builds. A TestFlight delivery workflow can be added separately
when signing credentials and distribution preferences are available.
