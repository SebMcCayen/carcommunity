import java.util.Properties

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
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
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
    file(keystoreStoreFile).isFile &&
    !keystoreStorePassword.isNullOrBlank() &&
    !keystoreKeyAlias.isNullOrBlank() &&
    !keystoreKeyPassword.isNullOrBlank()

// If keystore.properties exists but is incomplete, fall through to unsigned
// (never fatal) and note why, so the missing signature isn't a silent surprise.
if (keystorePropsFile.exists() && !hasReleaseSigning) {
    logger.warn(
        "Release signing disabled: keystore.properties is present but incomplete " +
            "(need a readable storeFile plus storePassword, keyAlias, keyPassword). " +
            "The release build will be unsigned.",
    )
}

// Firebase configuration: google-services.json is intentionally NOT committed
// (see .gitignore and apps/android/README.md). The plugin is applied only when
// the file is present so that CI validation builds work without secrets.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

android {
    namespace = "com.kungsbackacarcommunity.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kungsbackacarcommunity.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreStoreFile)
                storePassword = keystoreStorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Mapbox runtime access token: a secret NOT committed and NOT
            // present in CI, so it DEFAULTS to empty. The Map slice sets
            // MapboxOptions.accessToken only when non-blank; an empty value
            // still renders the MapView (empty style) so the config-less
            // build and launch stay green. The real token is provisioned at
            // cutover (override this resValue or inject from a secret).
            resValue("string", "mapbox_access_token", "")
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
            resValue("string", "mapbox_access_token", "")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Phase 15: App Check init reads BuildConfig.DEBUG to pick the provider.
        buildConfig = true
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
