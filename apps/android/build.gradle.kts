// Top-level build file. Plugin versions come from gradle/libs.versions.toml.
// AGP supplies built-in Kotlin. Keep the existing compiler and Compose plugin
// aligned at the catalog version instead of falling back to AGP's bundled KGP.
// https://developer.android.com/build/releases/agp-9-0-0-release-notes#runtime-dependency-on-kotlin-gradle-plugin
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.services) apply false
    // Applied by :app only when google-services.json is present (it needs the
    // Firebase App ID the google-services plugin generates) — see
    // app/build.gradle.kts. Declared here so the version lives in the catalog.
    alias(libs.plugins.firebase.crashlytics) apply false
}
