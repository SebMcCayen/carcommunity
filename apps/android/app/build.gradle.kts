import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: keystore.properties and the keystore file are gitignored and
// absent in CI, so release builds there stay unsigned (mirrors the
// google-services.json apply-if-present pattern below). Locally, if
// keystore.properties points at a readable keystore, the release build is
// signed with the upload key for Google Play.
val keystorePropsFile = file("keystore.properties")
val keystoreProps = Properties().apply {
    // Loading keystore.properties must never be fatal: an unreadable file,
    // broken symlink, or malformed contents should fall through to unsigned
    // rather than break Gradle configuration. Pre-check isFile && canRead(),
    // and still catch read/parse errors in case the file changes underneath.
    if (keystorePropsFile.isFile && keystorePropsFile.canRead()) {
        try {
            keystorePropsFile.inputStream().use { load(it) }
        } catch (e: Exception) {
            logger.warn(
                "Release signing disabled: keystore.properties could not be read " +
                    "(${e.message}). The release build will be unsigned.",
            )
        }
    }
}
// Read every required property up front, trimmed. A blank storeFile must not
// resolve to the project dir and slip through, and a missing password/alias
// must not produce null values later, so the release signing path is taken
// only when all four are non-blank AND the keystore file actually exists.
val keystoreStoreFile = keystoreProps.getProperty("storeFile")?.trim()
val keystoreStorePassword = keystoreProps.getProperty("storePassword")?.trim()
val keystoreKeyAlias = keystoreProps.getProperty("keyAlias")?.trim()
val keystoreKeyPassword = keystoreProps.getProperty("keyPassword")?.trim()
val hasReleaseSigning = !keystoreStoreFile.isNullOrBlank() &&
    file(keystoreStoreFile).run { isFile && canRead() } &&
    !keystoreStorePassword.isNullOrBlank() &&
    !keystoreKeyAlias.isNullOrBlank() &&
    !keystoreKeyPassword.isNullOrBlank()

// If keystore.properties exists but is unusable, fall through to unsigned
// (never fatal) and note why, so the missing signature isn't a silent surprise.
// Distinguish "the file itself couldn't be read" (a directory or permission
// issue) from "the file loaded but the config is incomplete" so the warning
// points at the real problem instead of blaming missing keys in both cases.
if (keystorePropsFile.exists() && !(keystorePropsFile.isFile && keystorePropsFile.canRead())) {
    logger.warn(
        "Release signing disabled: keystore.properties exists but is not a readable " +
            "file (a directory or permission issue?). The release build will be unsigned.",
    )
} else if (keystorePropsFile.exists() && !hasReleaseSigning) {
    logger.warn(
        "Release signing disabled: keystore.properties is present but incomplete " +
            "(need a readable storeFile plus storePassword, keyAlias, keyPassword). " +
            "The release build will be unsigned.",
    )
}

// Mapbox runtime access token (a public `pk.` token). Injected without ever
// committing it (this repo is public): read from the MAPBOX_ACCESS_TOKEN Gradle
// property — CI sets it from the production secret via the
// ORG_GRADLE_PROJECT_MAPBOX_ACCESS_TOKEN env var — else from a gitignored
// mapbox.properties for local signed builds, else empty. Empty is valid: the
// MapView still renders (blank style) so config-less / CI validation builds
// stay green (MapRoute.kt only sets MapboxOptions.accessToken when non-blank).
val mapboxPropsFile = file("mapbox.properties")
val mapboxProps = Properties().apply {
    if (mapboxPropsFile.isFile && mapboxPropsFile.canRead()) {
        try {
            mapboxPropsFile.inputStream().use { load(it) }
        } catch (e: Exception) {
            logger.warn(
                "Could not read mapbox.properties (${e.message}); the Mapbox token " +
                    "will be empty and the map will render blank.",
            )
        }
    }
}
val mapboxAccessToken: String = (
    providers.gradleProperty("MAPBOX_ACCESS_TOKEN").orNull
        ?: mapboxProps.getProperty("MAPBOX_ACCESS_TOKEN")
        ?: ""
    ).trim()

// Build-time Mapbox downloads token (a secret `sk.` token with DOWNLOADS:READ
// scope). This is DISTINCT from the runtime `mapboxAccessToken` above: it only
// authenticates the Gradle resolution of the Navigation SDK v3 artifacts
// (com.mapbox.navigationcore:*), which — unlike the public Maps SDK — sit behind
// Basic auth (see settings.gradle.kts). Resolved from the MAPBOX_DOWNLOADS_TOKEN
// Gradle property / env / gitignored mapbox.properties, else empty.
//
// When empty (CI and token-less local/dev builds), the Navigation SDK is NOT a
// dependency and the real turn-by-turn source set (src/nav) is NOT on the
// compile path; the no-SDK stub (src/noNav) is compiled instead so the whole
// build resolves and stays green without the secret. A provisioned build with
// the token compiles the real Nav SDK implementation. This mirrors the app's
// established "seam + stub, config-less CI compiles the stub" pattern (MapSurface).
val mapboxDownloadsToken: String = (
    providers.gradleProperty("MAPBOX_DOWNLOADS_TOKEN").orNull
        ?: System.getenv("MAPBOX_DOWNLOADS_TOKEN")
        ?: mapboxProps.getProperty("MAPBOX_DOWNLOADS_TOKEN")
        ?: ""
    ).trim()
val navSdkEnabled: Boolean = mapboxDownloadsToken.isNotEmpty()

// App Check DEBUG token (debug builds only). The Firebase debug provider
// otherwise generates a random secret that is wiped on uninstall/reinstall, so
// every debug rebuild yields an unregistered token and every App-Check-gated
// callable starts failing until it's re-registered by hand. Supplying a fixed
// UUID here (seeded into the SDK's debug store by AppCheckDebugSecret) makes a
// single Firebase-console registration survive rebuilds.
//
// Read from the gitignored apps/android/local.properties (`appcheck.debugToken`)
// so a token value is NEVER committed to this public repo. Empty is the default
// and is valid: CI and fresh clones build fine and simply keep today's
// SDK-generated behaviour. Release builds ignore this entirely.
//
// rootProject.file, NOT file(): the latter resolves against apps/android/app/,
// and it is apps/android/local.properties (the Gradle root, alongside sdk.dir)
// that .gitignore covers.
val localPropsFile = rootProject.file("local.properties")
val localProps = Properties().apply {
    if (localPropsFile.isFile && localPropsFile.canRead()) {
        try {
            localPropsFile.inputStream().use { load(it) }
        } catch (e: Exception) {
            logger.warn(
                "Could not read local.properties (${e.message}); the App Check debug " +
                    "token will be empty and debug builds will use an SDK-generated one.",
            )
        }
    }
}
val appCheckDebugToken: String = (localProps.getProperty("appcheck.debugToken") ?: "").trim()

// buildConfigField splices its value into generated Java verbatim, so a stray
// quote/backslash/newline would emit code that doesn't compile. Escape rather
// than reject: the runtime seeder treats anything unusable as "not configured".
fun javaStringLiteral(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .let { "\"$it\"" }

// Firebase configuration: google-services.json is intentionally NOT committed
// (see .gitignore and apps/android/README.md). The plugin is applied only when
// the file is present so that CI validation builds work without secrets.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    // Firebase Crashlytics BUILD plugin. Applied AFTER google-services and under
    // the same guard, deliberately:
    //
    //  - It resolves the Firebase App ID from the resources the google-services
    //    plugin generates from google-services.json, so applying it without that
    //    file (CI validation builds, fresh clones) has nothing to point at.
    //  - Its only job is BUILD-time symbol work: uploading the R8/ProGuard
    //    mapping file so release stack traces de-obfuscate, and stamping a build
    //    id into the APK so Crashlytics can match a crash to that mapping.
    //
    // The RUNTIME `firebase-crashlytics` dependency below is NOT guarded — it is
    // on every variant so KccApplication compiles everywhere; without Firebase
    // configuration it simply never initializes (same shape as appcheck-debug).
    //
    // FORWARD-LOOKING: `isMinifyEnabled = false` on release today, so there is no
    // mapping file to upload and this plugin is INERT beyond stamping the build
    // id. It is wired now so that flipping minification on (a separate, riskier
    // change) does not silently produce unreadable obfuscated stack traces. The
    // plugin's defaults are relied on deliberately — mapping upload on, native
    // symbol upload off (native symbols already ship via the release
    // `ndk { debugSymbolLevel = "FULL" }` block, which Play symbolicates).
    apply(plugin = libs.plugins.firebase.crashlytics.get().pluginId)
}

android {
    namespace = "com.kungsbackacarcommunity.app"
    // compileSdk 36 is required by core-ktx 1.18 / activity-compose 1.13 (their
    // AAR metadata declares minCompileSdk=36). We deliberately cap at that line:
    // core 1.19 / lifecycle 2.11 would require compileSdk 37 + AGP 9.x, a larger
    // toolchain jump out of scope here. targetSdk intentionally stays 35
    // (raising it is a separate, tested change).
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kungsbackacarcommunity.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 26
        versionName = "0.8.15"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Whether the real Mapbox Navigation SDK (turn-by-turn) is on the compile
        // path. Mirrors the src/nav vs src/noNav source-set swap driven by
        // navSdkEnabled: true only when a downloads token was present at build
        // time. The UI reads this to gate the turn-by-turn "Start" CTA so a
        // token-less build (which compiles the noNav stub) never advertises a
        // feature that can only reach the "navigation unavailable" stub.
        buildConfigField("boolean", "NAV_SDK_ENABLED", navSdkEnabled.toString())

        // The pinned Mapbox Maps SDK version, sourced from the version catalog so
        // it can never drift from the artifact actually on the classpath. Attached
        // to feature-health reports (see diagnostics/FeatureHealth.kt) so a silent
        // map-rendering regression names the SDK version it appeared on — the
        // v0.8.1 blank-map bug arrived with an SDK/config change, and the version
        // is the first thing anyone triaging such an issue asks for.
        buildConfigField(
            "String",
            "MAPBOX_MAPS_SDK_VERSION",
            "\"${libs.versions.mapbox.get()}\"",
        )

        // Empty by default and deliberately NOT overridden in the release block:
        // only the debug build type (below) gets the real value, so a release
        // APK/AAB can never carry a debug token even on a machine that has one
        // configured. KccApplication reads it behind BuildConfig.DEBUG.
        buildConfigField("String", "APP_CHECK_DEBUG_TOKEN", "\"\"")

        // Crashlytics data collection, as a MANIFEST default (the debug build
        // type below flips it off). This is the value that applies from process
        // start — before Application#onCreate has had a chance to run — so a
        // crash during startup on a developer's debug build is not reported.
        //
        // KccApplication ALSO sets this explicitly at runtime, from the same
        // unit-tested decision (CrashTelemetryPolicy.collectionEnabled). The two
        // always agree; the manifest covers "before onCreate", the runtime call
        // is the readable, testable statement of intent.
        //
        // A placeholder (not a literal) because the value differs per build type
        // and manifest placeholders are substituted before aapt2 compiles the
        // manifest, so `true`/`false` is still typed as a boolean — which is what
        // Firebase's meta-data reader requires. A `@bool/...` resource reference
        // would NOT work: the metadata Bundle would hold a resource id, and
        // getBoolean() on it silently reads false.
        manifestPlaceholders["crashlyticsCollectionEnabled"] = "true"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // hasReleaseSigning guarantees keystoreStoreFile is non-blank, but
                // the compiler can't smart-cast a captured val — assert non-null.
                storeFile = file(keystoreStoreFile!!)
                storePassword = keystoreStorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Resolved above: empty unless MAPBOX_ACCESS_TOKEN / mapbox.properties
            // provides a token. Empty keeps config-less/CI builds green.
            resValue("string", "mapbox_access_token", mapboxAccessToken)

            // Resolved above: empty unless local.properties sets
            // appcheck.debugToken. Empty keeps config-less/CI builds green and
            // preserves the SDK-generated-token behaviour.
            buildConfigField(
                "String",
                "APP_CHECK_DEBUG_TOKEN",
                javaStringLiteral(appCheckDebugToken),
            )

            // Crashlytics OFF for debug builds. A developer's own crashes —
            // deliberate ones included — would otherwise land in the same
            // dashboard as members' crashes and drag the crash-free-users metric
            // down, which is the one number that has to stay trustworthy.
            // Mirrored at runtime by CrashTelemetryPolicy.collectionEnabled.
            // To exercise the integration locally, flipping THIS to "true" is not
            // enough on its own: KccApplication calls the runtime setter with
            // BuildConfig.DEBUG afterwards, and the runtime override takes
            // precedence over the manifest. Flip this AND pass
            // isDebugBuild = false to FirebaseCrashTelemetry.install, for one
            // build; commit neither. See docs/crashlytics.md.
            manifestPlaceholders["crashlyticsCollectionEnabled"] = "false"
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Bundle native debug symbols (Mapbox / Play Services .so files) into
            // the AAB so Play symbolicates native crashes & ANRs. Clears the Play
            // Console "native code without debug symbols" upload warning.
            ndk {
                debugSymbolLevel = "FULL"
            }
            resValue("string", "mapbox_access_token", mapboxAccessToken)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Phase 15: App Check init reads BuildConfig.DEBUG to pick the provider.
        buildConfig = true
    }

    // Turn-by-turn navigation: exactly ONE of two mutually-exclusive source dirs
    // is added to `main`, both defining the same TurnByTurnNavScreen entry point
    // (same package + signature) so the rest of the app calls it uniformly:
    // - src/nav   — the real Mapbox Navigation SDK v3 implementation, compiled
    //               ONLY when a downloads token is present (navSdkEnabled).
    // - src/noNav — a no-SDK stub compiled otherwise (CI / token-less builds), so
    //               nothing references com.mapbox.navigationcore.* and the build
    //               resolves without the secret. Never both, so no duplicate class.
    sourceSets {
        getByName("main") {
            java.srcDir(if (navSdkEnabled) "src/nav/java" else "src/noNav/java")
        }
    }
}

// Replaces the removed android.kotlinOptions DSL (error since Kotlin 2.2+).
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // Material icons (map-first shell: search, mic, broadcast, layers, my-location,
    // music, route, tab glyphs). Vectors are stripped by R8 in release.
    implementation(libs.androidx.material.icons.extended)

    // Firebase (versions via BoM)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.database)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.appcheck.playintegrity)
    // Available to all variants so KccApplication compiles for release; the
    // debug provider is only INSTALLED when BuildConfig.DEBUG is true.
    implementation(libs.firebase.appcheck.debug)
    // Crashlytics. ADDITIVE to diagnostics/CrashReporter.kt — it is what carries
    // the full stack trace (and breadcrumbs, custom keys, crash-free-users) that
    // the home-grown, privacy-reviewed diagnostics report deliberately omits.
    // Unguarded like the rest: collection is switched off for debug builds, and
    // a build without google-services.json never initializes Firebase at all.
    implementation(libs.firebase.crashlytics)

    // Mapbox Maps
    implementation(libs.mapbox.maps)

    // Mapbox Navigation SDK v3 (turn-by-turn) — added only when a build-time
    // downloads token is configured (see navSdkEnabled). Token-less builds
    // compile the src/noNav stub and pull none of these, so resolution/CI stay
    // green without the secret. The `navigation` artifact brings the core trip
    // session + routing; `ui-maps` the navigation camera + route line/arrow;
    // `tripdata` the maneuver API; `ui-components` the maneuver banner view.
    if (navSdkEnabled) {
        implementation(libs.mapbox.nav.navigation)
        implementation(libs.mapbox.nav.ui.maps)
        implementation(libs.mapbox.nav.tripdata)
        implementation(libs.mapbox.nav.ui.components)
        // MapboxManeuverView (ui-components) extends ConstraintLayout, but
        // Mapbox declares constraintlayout as an `implementation` dep, so it is
        // NOT on our compile classpath transitively. Without it Kotlin cannot
        // resolve the supertype — and therefore cannot see the view as a
        // `View` either, breaking the AndroidView factory in src/nav.
        implementation(libs.androidx.constraintlayout)
    }

    // Google Play Services — fused location provider (Phase 12 slice 6)
    implementation(libs.play.services.location)

    // Google Play In-App Updates. The update prompt asks Play itself whether a
    // newer build is live on the track this install came from, so there is no
    // server-held version number for anyone to keep in sync (see
    // update/AppUpdateSource.kt). A non-Play install (adb/debug/sideloaded) has
    // no Play install context and the library reports an error, which
    // PlayAppUpdateSource degrades to "nothing to offer" — so debug and CI
    // builds simply never prompt.
    implementation(libs.play.app.update)

    // Credential Manager — Google Sign-In (docs/auth-mobile-requirements.md)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)

    // Google Play Billing — subscriptions (Phase 12 slice 24)
    implementation(libs.billing)

    // Coil — Compose image loading (avatar + vehicle photos)
    implementation(libs.coil.compose)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json so Changelog.parse (whatsnew) is testable on the JVM: the
    // mockable android.jar's org.json entries are stubs that throw. Listed
    // before the mockable jar on the unit-test classpath, so it wins there;
    // devices/instrumented tests still use the platform org.json.
    testImplementation(libs.org.json)

    // Instrumented / Compose UI tests
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
