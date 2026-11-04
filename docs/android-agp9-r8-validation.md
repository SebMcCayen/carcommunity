# Android AGP 9 / optimized resource shrinking

Baseline: `195a41b` (2026-11-04). This is a build-tool migration only, with no
application version, SDK level, runtime dependency, signing policy or service
configuration change.

## Official compatibility decisions

- [AGP 9.1.1 compatibility](https://developer.android.com/build/releases/agp-9-1-0-release-notes):
  stable patch on the 9.1 line; minimum Gradle **9.3.1**, Build Tools **36.0.0**,
  JDK **17**. Platform 36 remains supported. This avoids a broader upgrade to
  newer AGP minor lines.
- [Kotlin 2.4 compatibility](https://kotlinlang.org/docs/gradle-configure-project.html):
  The table lists Gradle 7.6.3–9.5.0 and AGP through 9.1.0 for Kotlin 2.4.0.
  We use the stable 9.1.1 patch on that AGP line and validate it locally. Kotlin and the
  [Compose compiler plugin](https://developer.android.com/develop/ui/compose/compiler)
  stay at **2.4.0**; the Compose BoM and runtime libraries do not change.
- [Kotlin / D8 / R8 requirements](https://developer.android.com/build/kotlin-support):
  Kotlin 2.4 requires R8 **9.1.29** or later. Reading `r8-version.properties` from
  the resolved official `com.android.tools.build:builder:9.1.1` JAR confirms
  **9.1.31**. The old AGP 8.13.2 builder embeds **8.13.19**. Thus simply enabling
  the 8.13 optimized-resource flag would leave the existing Kotlin 2.4 / R8
  mismatch; the 9.1 upgrade fixes both concerns without overriding R8 separately.
- [Built-in Kotlin migration](https://developer.android.com/build/migrate-to-built-in-kotlin):
  remove `org.jetbrains.kotlin.android` at both levels and from the catalog.
  Explicitly supply catalog KGP 2.4.0 on the root buildscript classpath as
  [documented for a newer KGP](https://developer.android.com/build/releases/agp-9-0-0-release-notes#runtime-dependency-on-kotlin-gradle-plugin),
  rather than silently using AGP's bundled 2.2.10. Keep `kotlin.compilerOptions`
  targeting JVM 17. Register shared-test and nav/noNav Kotlin directories with
  `android.sourceSets.*.kotlin.directories`.
- [AGP 9 behavior changes](https://developer.android.com/build/releases/agp-9-0-0-release-notes#behavior-changes):
  explicitly enable `buildFeatures.resValues` for both Mapbox token resources.
  Set `android.onlyEnableUnitTestForTheTestedBuildType=false` to retain **both**
  debug and release JVM tests under `test`; accepting the new default would drop
  release test coverage. This is a test-coverage setting, not a Kotlin/DSL
  compatibility opt-out. Built-in Kotlin, the new DSL and strict R8 remain on.
- [Optimized resource shrinking](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization#optimize-resource-shrinking):
  AGP 8.12/8.13 needs `android.r8.optimizedResourceShrinking=true`; AGP 9+ applies
  it automatically with `isShrinkResources=true`. Keep both release minification
  and resource shrinking enabled, plus `proguard-android-optimize.txt` and existing
  vendor/application rules. Debug stays unminified.
- Gradle distribution SHA-256 is pinned to the
  [official checksum](https://services.gradle.org/distributions/gradle-9.3.1-bin.zip.sha256).

Google Services 4.5.0 and Crashlytics Gradle plugin 3.0.7 stay pinned; Firebase's
[plugin release notes](https://firebase.google.com/support/release-notes/android)
are separate from the runtime BoM. The existing guarded plugin application,
Crashlytics mapping upload defaults, AAB mapping/native-symbol settings and
Mapbox 11.26.0 / Navigation 3.26.0 `-ndk27` dependency pair are preserved.

## Validation

All local Gradle work is serial (`--no-daemon --max-workers=1`) on JDK 17 with
no local emulator. Generated Android strings/design tokens and a CRLF-aware
whitespace check (`git -c core.whitespace=cr-at-eol diff --check`) pass; the generated
Windows wrapper retains Gradle's CRLF line endings.

Completed locally:

- AGP 9.1.1 / Gradle 9.3.1 `help` configuration.
- `compileDebugKotlin`, Java compilation, debug resource linking and the compiled
  noNav entry point with built-in Kotlin 2.4.0.
- A temporary configuration probe asserts both JVM-test tasks exist, shared-test
  and noNav source roots are registered, resValues and release shrinking stay
  enabled, Compose is applied, and `org.jetbrains.kotlin.android` is absent.
  It resolves KGP **2.4.0** and R8 **9.1.31** from the build's actual classpath.
- The same probe applies Google Services **4.5.0** and Crashlytics **3.0.7** using
  disposable `demo-kcc-agp9` local JSON. `processReleaseGoogleServices` and
  `injectCrashlyticsMappingFileIdRelease` pass, and the mapping-upload task is
  registered. No upload task runs; the fixture is removed afterwards.
- Gradle `wrapper` refreshes the JAR and both launch scripts. The generated JAR's
  SHA-256 matches the [official wrapper checksum](https://services.gradle.org/distributions/gradle-9.3.1-wrapper.jar.sha256).

- The complete debug JVM-test source set compiles. Focused
  `testDebugUnitTest --tests ...CrashTelemetryTest --tests
  ...ChangelogReleaseCoverageTest --tests ...OnboardingScreenTest` passes:
  **21 tests, 0 failures/errors/skips** (16 telemetry, 3 changelog, 2
  Robolectric/Compose onboarding). This runs through the refreshed wrapper and
  covers Android resources and Compose as well as pure JVM behavior.

The local Gradle slot was released after this run; no Gradle or Kotlin compiler
process remained. No local emulator was started.

The initial broad `assembleDebug testDebugUnitTest compileDebugAndroidTestKotlin
lintDebug assembleRelease` invocation was deliberately stopped during DEX
processing after successful source compilation, to honor the requested bounded
local checks and free the shared 16 GB machine for the parallel emulator work.
It is **not** a completed debug assembly, full test suite, lint, R8 or device run.
No local Mapbox downloads credential was available. Existing PR CI retains
`test` (debug + release), lintDebug, debug packaging, unsigned release R8,
16 KB alignment and authenticated real-navigation compilation; the validation
workflow needs no compatibility change. Record CI status separately from the
local results.

A compiled/minified package is not device or production certification. A future
approved release must still test the minified, Firebase-configured, real-navigation
build on devices and upload through the usual release process to verify Play's
warning clears. No release workflow is dispatched for this migration.
