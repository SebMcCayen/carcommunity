# Kungsbacka Car Community — Android

Native Android app (Kotlin, Jetpack Compose). Created in migration plan Phase 5; this is the **MVP mobile client** (iOS is out of MVP scope — parked on the Future Ideas board).

## Requirements

- JDK 17+
- Android SDK (compileSdk 35) — install via Android Studio or `sdkmanager`
- Node.js 24+ (the repo baseline) — only for the localization generator script (`scripts/generate-strings.mjs`); the app build itself is Gradle-only and this workspace is intentionally not part of the JS monorepo workspaces

## Firebase setup (`google-services.json`)

`app/google-services.json` is **gitignored on purpose** — it contains project identifiers that should not live in the repo. To run against the real Firebase project:

1. Firebase console → Project settings → Your apps → Android app (`com.kungsbackacarcommunity.app`)
2. Download `google-services.json`
3. Place it at `apps/android/app/google-services.json`

The Google Services Gradle plugin is applied **only when the file exists**, so CI and fresh clones build fine without it.

## Localization

`res/values/strings.xml` (Swedish, default) and `res/values-en/strings.xml` are **generated** from the canonical contracts:

```bash
node apps/android/scripts/generate-strings.mjs   # from repo root
```

Never edit the generated files by hand — edit `contracts/localization/*.json` and regenerate. CI fails if the generated files are out of date.

## Design tokens (KCC Crown UI)

`app/src/main/java/.../design/Tokens.kt` is **generated** from `contracts/design-tokens/tokens.json`:

```bash
node apps/android/scripts/generate-tokens.mjs   # from repo root
```

`KccTheme` (hand-written, in `design/KccTheme.kt`) maps the semantic tokens onto Material3 — light, dark, and system-adaptive. `statusSuccess`/`statusWarning` have no Material3 slot and are exposed via `LocalKccStatusColors`. Use `MaterialTheme.colorScheme`/`typography` in composables — never hard-code colors, sizes, or radii.

## Commands

```bash
./gradlew test           # unit tests
./gradlew assembleDebug  # debug APK
./gradlew lint           # Android lint
./gradlew connectedAndroidTest  # Compose UI tests (needs emulator/device)
```

## Key dependencies

- Firebase (via BoM): auth, firestore, database, messaging, app check
- Mapbox Maps SDK v11 (public Maven repo; runtime requires a public access token — added with the map feature slice)
- Versions are managed in `gradle/libs.versions.toml` (Gradle Version Catalog)
