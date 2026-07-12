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

// Firebase configuration: google-services.json is intentionally NOT committed
// (see .gitignore and apps/android/README.md). The plugin is applied only when
// the file is present so that CI validation builds work without secrets.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
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
        versionCode = 8
        versionName = "0.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // Mapbox Maps
    implementation(libs.mapbox.maps)

    // Google Play Services — fused location provider (Phase 12 slice 6)
    implementation(libs.play.services.location)

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

    // Instrumented / Compose UI tests
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
