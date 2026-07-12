import org.gradle.authentication.http.BasicAuthentication

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Mapbox release maven repository.
        // The Maps SDK v11+ artifacts are public (no credentials needed). The
        // Navigation SDK v3 artifacts (com.mapbox.navigationcore:*) in the SAME
        // repo require Basic auth with a secret downloads token. We attach those
        // credentials only when a MAPBOX_DOWNLOADS_TOKEN is provided (Gradle
        // property or env) — so a token-less build (CI, local dev) still resolves
        // every public artifact, and the gated navigation dependency + its source
        // set are simply not compiled (see app/build.gradle.kts). The username is
        // the fixed literal "mapbox" per Mapbox's downloads-token contract.
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            // Resolve the downloads token from the SAME sources, in the same
            // order, that app/build.gradle.kts uses to drive `navSdkEnabled`
            // (Gradle property → env → gitignored app/mapbox.properties). If the
            // two disagree — e.g. the token is set ONLY in app/mapbox.properties —
            // navSdkEnabled would add the Navigation SDK dependency while these
            // repository credentials stayed empty, and resolution would fail. One
            // resolution order keeps the dependency and its credentials in lockstep.
            val mapboxPropsFile = File(rootDir, "app/mapbox.properties")
            val mapboxProps = java.util.Properties().apply {
                if (mapboxPropsFile.isFile && mapboxPropsFile.canRead()) {
                    try {
                        mapboxPropsFile.inputStream().use { load(it) }
                    } catch (_: Exception) {
                        // Fall through to no credentials: a token-less build still
                        // resolves the public artifacts and compiles the noNav stub.
                    }
                }
            }
            val mapboxDownloadsToken =
                (providers.gradleProperty("MAPBOX_DOWNLOADS_TOKEN").orNull
                    ?: System.getenv("MAPBOX_DOWNLOADS_TOKEN")
                    ?: mapboxProps.getProperty("MAPBOX_DOWNLOADS_TOKEN"))
                    ?.trim()
                    .orEmpty()
            if (mapboxDownloadsToken.isNotEmpty()) {
                credentials {
                    username = "mapbox"
                    password = mapboxDownloadsToken
                }
                authentication {
                    create<BasicAuthentication>("basic")
                }
            }
        }
    }
}

rootProject.name = "kungsbacka-car-community"
include(":app")
