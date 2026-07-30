# Firebase Crashlytics (Android)

Crash reporting for the Android app. This is **additive** — it sits alongside the
existing home-grown diagnostics pipeline, it does not replace it.

## The two pipelines, and why both exist

| | `diagnostics-submitReport` (existing) | Crashlytics (this) |
|---|---|---|
| Installed by | `diagnostics/CrashReporter.kt` | the Firebase SDK, at `FirebaseApp` init |
| Stack trace | **never** (`DiagnosticsReport.kt`) | full, symbolicated |
| Message | sanitized, digits/UUIDs/paths/emails masked, ≤ 2000 chars | the exception's own message |
| Where it lands | `diagnosticsReports`, admin diagnostics view | Firebase Console → Crashlytics |
| Grouping / trend | none | issue grouping, occurrence counts, crash-free users |
| Breadcrumbs / custom keys | none | yes |
| Non-fatals | no | yes, listed separately |

The diagnostics report answers *what* threw and feeds the admin view under a
privacy-reviewed backend contract. Crashlytics answers *where* it threw and *how
the user got there*. Neither is redundant.

### How they coexist on one crash

Crashlytics installs its own `Thread.UncaughtExceptionHandler` when
`FirebaseApp` initializes. `KccApplication#onCreate` touches Crashlytics
**first** (`FirebaseCrashTelemetry.install`), then calls
`CrashReporter.install(...)`, which chains itself in **front** of it and
delegates onward. One uncaught exception therefore runs:

```
CrashReporter          -> PII-safe diagnostics report, enqueued on the dying thread
  Crashlytics handler  -> full stack trace + breadcrumbs + custom keys
    platform handler   -> the process dies exactly as before
```

`CrashReporter`'s `finally` guarantees the chain continues even if the
diagnostics report throws, so it can never be the reason Crashlytics misses a
crash. `CrashReporterChainTest` pins this.

## Data collection

- **Release: ON.** **Debug: OFF.** Set two ways that always agree:
  - statically, via the `firebase_crashlytics_collection_enabled` manifest
    meta-data (substituted per build type from `app/build.gradle.kts`), which
    covers a crash that happens *before* `Application#onCreate` runs;
  - explicitly at runtime, from `CrashTelemetryPolicy.collectionEnabled`.
- Rationale: a developer's crashes — deliberate test crashes included — would
  otherwise land in the same dashboard as members' and drag down crash-free
  users, the one number that has to stay trustworthy.
- To exercise the integration locally, flip
  `manifestPlaceholders["crashlyticsCollectionEnabled"]` to `"true"` in the
  `debug` block for a single build. **Do not commit it.**

## Consent

There is deliberately **no consent gate** on crash telemetry.

The app has two consent surfaces and neither governs crash diagnostics:

- **Onboarding consents** (`onboarding/OnboardingScreen.kt` →
  `auth.completeOnboarding`) — driving licence, terms, privacy policy. Account
  terms, not a telemetry choice.
- **Anonymised partner statistics opt-in** (`privacy/PartnerStats.kt`,
  `userPrivate/{uid}.anonymousPartnerStatsOptIn`, default OFF) — governs sharing
  aggregate *usage* stats with partners. A different purpose, a different
  recipient, and it is not a diagnostics switch.

The existing crash pipeline (`diagnostics-submitReport`) is likewise ungated, so
gating only the new half would be inconsistent as well as invented. If a
telemetry opt-out is wanted later, the seam is ready for it: honour it in
`CrashTelemetryPolicy.collectionEnabled` and call
`FirebaseCrashlytics.setCrashlyticsCollectionEnabled(false)`, which also stops
uploading anything already queued.

## What is attached to a crash

**Custom keys** (`CrashKeys`) — all app-generated, never user data:

| Key | Value |
|---|---|
| `build_type` | `debug` / `release` |
| `version_name` / `version_code` | from `BuildConfig` |
| `nav_sdk_enabled` | whether the real Mapbox Navigation SDK is compiled in, or the `src/noNav` stub |
| `mapbox_sdk_version` | the pinned Maps SDK |
| `shell_tab` / `shell_route` | the `ShellTab` / `ShellRoute` enum **name** |
| `live_sharing` | whether the live-location foreground service is running |
| `last_non_fatal` | the `CrashFeatures` path of the most recent non-fatal |

**Breadcrumbs** (`CrashEvents`) — deliberately few, because the buffer is bounded
(~64 entries) and a chatty crumb evicts the useful ones:

- `app.start` — process start
- `nav: tab=<Tab> route=<Route>` — every tab switch / sub-route open / Back /
  push deep-link, from the single hook in `AuthenticatedApp`
- `live.sharingStart` / `live.sharingStop`
- `nonFatal: <feature>` — written by `recordNonFatal` itself

### The PII rule

No uid, email, display name, coordinates, message or chat content, search text,
or vehicle data — in keys, breadcrumbs, or feature names. `ShellRoute.Chat` says
"a DM thread was open" and that is all it is allowed to say. If a value must be
derived from user data, run it through `CrashTelemetryText.userDerived`, which
applies the same masking the diagnostics pipeline applies (emails, UUIDs, unix
paths and digit runs). Prefer a constant.

## Non-fatals

Errors the app catches and handles never reach a crash handler. These swallowed
paths now record a non-fatal (`CrashFeatures`):

| Feature | Site | Why |
|---|---|---|
| `live.nearbyRefresh` | `NearbyLiveController.refresh` | the map keeps stale sharers; nothing else notices |
| `live.sessionListener` | `LocationSharingService.observeSession` | reads to the member exactly like "sharing ended" |
| `navigation.origin` | `NavigationController.select` | reads exactly like a denied location permission |
| `dm.send` | `DmThreadCoordinator.send` | unmodelled throw; member sees only a generic retry |
| `channel.send` | `ChannelChatCoordinator.send` | as above |

Deliberately **not** recorded: `CrashReporter`'s own catch (it must never mask
the original crash), `ClientErrorReporter`'s catch (reporting must not itself
fail), `ActivityNotFoundException` on external intents (no app installed is a
normal outcome), `SecurityException` from location starts (permission not
granted is a normal outcome), `createIfAvailable` fallbacks (config-less builds
are expected), mapped/modelled failures such as `DmSendResult.Failed` (the app
working correctly), and the ~50 defensive `runCatching` calls in
`MapboxMapSurface` (per-call recording would flood the console; map render
failures are already covered by `FeatureHealth` → `ClientErrorReporter`).

## Mapping-file upload

The Crashlytics Gradle plugin is applied in `app/build.gradle.kts` under the same
`google-services.json`-present guard as the google-services plugin (it resolves
the Firebase App ID from what that plugin generates), so CI validation builds are
unaffected.

**It is inert today.** `isMinifyEnabled = false` on release, so there is no
mapping file to upload and release stack traces are already un-obfuscated. It is
wired now so that turning minification on later — a separate change with its own
risk — does not silently start producing unreadable traces. Native symbols are
unrelated and already ship via the release `ndk { debugSymbolLevel = "FULL" }`
block, which Play symbolicates.

## One-time setup (Firebase Console)

1. **Firebase Console → Crashlytics**, select the Android app
   (`com.kungsbackacarcommunity.app`), and click **Enable Crashlytics**.
2. **Ship a build with the SDK and force one crash.** The dashboard stays on the
   "waiting for your first crash" screen until a report arrives; there is nothing
   to configure while it is empty. Debug builds do **not** count — collection is
   off there.
3. **Nothing else.** No API key, no CI secret, no service account. Mapping upload
   is authenticated by the Firebase App ID that `google-services.json` already
   carries, and `build-android-release.yml` already materializes that file from
   the `ANDROID_GOOGLE_SERVICES_JSON` production environment secret.

## Reading a crash

- **Crash-free users** — the share of users (not sessions) who saw no *fatal*
  crash in the selected window. Non-fatals do not count against it. It is a
  trend, not a target: a single crash in a small user base moves it a lot.
- **Sort by "Event count"** for what happens most often; **by "Users affected"**
  for what hurts most people. They differ — one member in a crash loop tops event
  count while affecting one person.
- **Open an issue → a single session** to see: the full stack trace with the
  throwing frame highlighted; the **Keys** tab (`shell_route`, `live_sharing`,
  `nav_sdk_enabled`, versions); and the **Logs** tab, the breadcrumb trail
  leading to the crash — usually the fastest route to a reproduction.
- **Non-fatals** are on their own tab (Crashes / Non-fatals / ANRs). Same
  grouping and stack traces, no effect on crash-free users. A non-fatal spiking
  just before a fatal spike is usually the same root cause.
- The **admin diagnostics view** still shows the sanitized `diagnosticsReports`
  entries. Cross-reference by exception class + app version when a member reports
  a crash that Crashlytics has not surfaced yet.
