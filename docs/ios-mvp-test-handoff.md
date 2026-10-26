# iOS MVP: automated checks and Apple-account handoff

This complements [release readiness](ios-release-readiness.md). Passing automated
checks does not mean that the app is approved for publication or that device-only
services have been tested.

## No-cost work before enrollment

- `Validate iOS` runs on a standard GitHub-hosted runner. This repository is
  public; standard hosted execution is free for public repositories. Do not switch
  to larger/paid runners or enable paid storage without owner approval.
- iOS test artifact uploads are **off by default**. `IOS_UPLOAD_TEST_ARTIFACTS=true`
  explicitly opts into storage after the owner has checked/approved its cost.
  Logs and test pass/fail status remain available without artifact uploads.
- The native Auth acceptance test uses only the loopback Auth emulator, project
  `demo-kcc-ios-ci`, a fabricated Apple token and synthetic users. Its test-only
  App Check provider prevents external attestation requests and does not test
  production App Check enforcement. It is not compiled into the shipping target.
- Do not dispatch the Mapbox preview, use production Firebase test writes, buy a
  subscription, create cloud resources, or upload to Apple as part of these tests
  without the relevant owner approval. Mapbox rendering is not exercised by CI.

## Automated coverage and its limits

| Check                                         | Evidence                                                                                                                           | Still needs manual/device testing                                     |
| --------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| Simulator packaging                           | `simulator-package.py` checks every Intel/ARM slice, iOS Simulator platform, embedded app identity, config mode and code signature | Signed physical-device archive and provisioning profile               |
| Keychain                                      | `DiagnosticsTests` writes, reads and deletes one unique synthetic item using actual Security APIs                                  | Relaunch/account-switch behavior in the shipping build                |
| Apple emulator exchange                       | `FirebaseEmulatorIntegrationTests` uses the real Firebase iOS SDK to exchange, refresh, reload and sign out                        | Real Apple authorization, cancellation and account linking policy     |
| Location permission loss and stop/hide        | Existing `LocationPermissionCoordinatorTests` and `LiveLocationCoordinatorTests`                                                   | Real GPS, lock-screen/background behavior, battery use                |
| Interrupted upload and retry                  | Existing `DriveRecordingCoordinatorTests` and journal tests                                                                        | Actual network interruption and process termination on iPhone         |
| Chat retry/account scoping and Partners gates | Existing `ChatCoordinatorTests` and `PartnersCoordinatorTests`                                                                     | Two-user end-to-end UI, live production rules and moderation behavior |
| Source release metadata                       | `release-preflight.py` and regression tests; demo credentials are rejected in release mode                                         | Actual Apple/Firebase/Mapbox provisioning and service availability    |

The normal config-less suite skips the opt-in Auth integration test. A separate
acceptance invocation enables it and checks the xcresult: exactly one test must
have passed, with zero skipped or failed tests. A skipped test is not acceptance.
No authentication bypass or automatic login is added to the app.

## Reproduce the native Auth acceptance test

The existing `Validate iOS` workflow performs these steps automatically. Locally,
use a dedicated build directory and an available simulator UUID. No Firebase
plist or Mapbox token is needed. Keep production emulator/config variables out
of this shell. Node 24 and Firebase CLI 15.34.0 are used by CI.

```bash
xcodebuild -project apps/ios/KCC.xcodeproj -scheme KCC \
  -destination 'platform=iOS Simulator,id=YOUR_SIMULATOR_UUID' \
  -derivedDataPath /tmp/kcc-native-auth-tests \
  ENTITLEMENTS_REQUIRED=YES \
  -only-testing:KCCTests/FirebaseEmulatorIntegrationTests build-for-testing
python3 apps/ios/scripts/simulator-package.py \
  /tmp/kcc-native-auth-tests/Build/Products/Debug-iphonesimulator/KCC.app
python3 apps/ios/scripts/auth-emulator-tests.py config /tmp/kcc-auth-emulator.json
npm exec --yes --package=firebase-tools@15.34.0 -- firebase emulators:exec \
  --project demo-kcc-ios-ci --config /tmp/kcc-auth-emulator.json \
  --only auth --non-interactive \
  "python3 apps/ios/scripts/auth-emulator-tests.py run --products /tmp/kcc-native-auth-tests/Build/Products --destination YOUR_SIMULATOR_UUID"
```

Do not set `CODE_SIGNING_ALLOWED=NO` on a simulator build used for authentication.
Xcode must embed simulator entitlements for Keychain access. Ad hoc simulator
signing does not need an enrolled Apple team. After modifying/repackaging an app,
preserve its embedded simulator identity, re-sign it, verify it, and terminate any
old running process before installing/launching the replacement. A separately
packaged local demo must use a `.local` bundle ID, matching Firebase bundle ID,
project `demo-kcc-ios` and the dummy key; validate it with `--firebase-mode demo`.

## Prepare TestFlight without signing or uploading

```bash
python3 apps/ios/scripts/prepare-testflight.py --output /tmp/kcc-testflight-handoff
```

Alternatively run **Prepare iOS TestFlight Handoff** manually on `main`. It runs
only for a public repository on a standard Ubuntu runner. It uses no secrets,
does not sign/archive/upload, and writes a text job summary without uploading
artifacts. It generates the current version/build handoff and an explicitly
incomplete `ExportOptions.example.plist`. Existing output is never overwritten.

Once enrollment is complete, the owner must supply/approve team and distribution
configuration. The first signed archive/upload remains an explicit next step,
not something the preparation workflow performs automatically.

## App Store metadata draft — owner review required

- App name: confirm **Kungsbacka Car Community** and availability in App Store Connect.
- Swedish description draft: “En mötesplats för bilintresserade. Utforska kartan,
  hitta evenemang och partners, visa ditt garage och håll kontakten med communityn.”
- Do not advertise turn-by-turn navigation or unfinished/unverified functionality.
- Supply final screenshots from the actual signed iPhone build, support/privacy
  URLs, review contact and review-access instructions. The local fabricated Apple
  account is not a usable App Review account.
- Confirm age-rating answers from actual user-generated content and moderation.
- Review privacy answers for account identifiers/contact data, location/sharing,
  photos and messages, purchase history, crash reports and diagnostics, including
  third-party SDK behavior. This is an inventory to verify, not completed privacy
  declarations or legal approval.
- Decide whether Plus/Supporter sales are enabled at MVP launch. Do not invent
  prices or promise availability before StoreKit/backend verification passes.

Record real results in `ios-release-readiness.md`; leave untested rows pending.
