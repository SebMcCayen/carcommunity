// Top-level build file. Plugin versions come from gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.services) apply false
    // Applied by :app only when google-services.json is present (it needs the
    // Firebase App ID the google-services plugin generates) — see
    // app/build.gradle.kts. Declared here so the version lives in the catalog.
    alias(libs.plugins.firebase.crashlytics) apply false
}
