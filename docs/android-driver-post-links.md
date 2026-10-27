# Android Driver post actions

## Verified external contract (2026-10-27)

Read-only inspection of `SebMcCayen/driver` main at
`2d64d3e1d059965cee8bd49014b86fb3824fac5e` found:

- `android/app/build.gradle.kts` declares application ID
  `com.kungsbackacarcommunity.driver`.
- `android/app/src/main/AndroidManifest.xml` exports `MainActivity` with only
  `MAIN` / `LAUNCHER`. No external `VIEW` / `BROWSABLE` post URI is registered.
- `MainActivity.kt` is an uncustomized `FlutterActivity`;
  `lib/app/driver_app.dart` uses `MaterialApp(home: AuthGate(...))` with no
  external post route. Notification handling in `app_shell.dart` is internal
  Firebase Messaging handling, not an exported post-link contract.
- README, tracked client sources and documentation contain no published Driver
  download URL. The GitHub repository is private, has no homepage, and its
  releases API returns no releases. The Android workflows upload expiring
  Actions artifacts, not public downloads.
- Checking Google Play for the verified application ID returned HTTP 404:
  `https://play.google.com/store/apps/details?id=com.kungsbackacarcommunity.driver&hl=sv&gl=SE`.
  This URL is evidence of the check, **not a working download fallback**.

## Compatible behavior in this PR

Every readable feed post and native post detail has an **Open the Driver app**
action. The adjacent localized copy explains that Driver cannot yet open a
specific post by link. The action sends a package-restricted launcher intent;
it sends no URI, post ID/text, account UID, token, extras, or permission grants.
It does not bypass either app's sign-in or backend authorization.

Missing, disabled, or inaccessible Driver activities show a localized inline
error and allow retry. There is no package-query permission or new backend
read/write. Native card-to-detail navigation remains unchanged.

## Blocked acceptance criteria

Opening the **exact external post** and redirecting missing installations to a
**real download page** remain blocked on Driver's external contract. Neither
behavior is claimed by this PR. Do not fabricate a scheme, notification extra,
Play listing, or public download from private CI artifacts.

Follow-up requires an owner-approved Driver post URI handler that reauthorizes
the requested thread and a published download destination. Once available,
validate IDs and encode each post ID as one URI component, restrict launches
to the verified Driver package, and handle missing app, store/browser fallback,
and no-handler failures. Device validation must cover cold/warm starts,
signed-out/onboarding, deleted/restricted posts, missing app, and missing browser.
This PR does not modify Driver, iOS, Firebase, Play, versions, or release builds.

## Regression coverage

`DriverAppLauncherTest` checks the exact package, launcher semantics, absence of
transferred data/grants, missing handler, and security rejection.
`DriverPostActionTest` checks both feed cards, native exact-post navigation,
detail rendering, production Swedish string resolution, failed launch, and retry.
English copy is also exercised through the production string adapter.

Android strings are generated from the canonical Swedish/English contracts.
The iOS codegen CI check also consumes those contracts and will require the
parent integration to regenerate its catalog separately; iOS files are outside
this task's explicitly authorized scope. Root npm lint/typecheck/test/build were
attempted but cannot run in this clean worktree without the absent
`eslint`/`tsc`/`vitest`/`vite` workspace dependencies; no dependencies were changed.

Local validation: Android string regeneration is deterministic and
`git diff --check` passes. The targeted Driver `testDebugUnitTest` invocation
used the required toolchain, two workers, and 3 GB Kotlin daemon setting, but
was cancelled during `compileDebugKotlin` after diagnosing multiple concurrent
compilations in the shared daemon with its old-generation heap at 99.99%.
No Android test result is claimed. The parent must coordinate a rerun of the
seven new tests and existing Driver suite before integration.
