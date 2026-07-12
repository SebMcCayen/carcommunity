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
            val mapboxDownloadsToken =
                (System.getenv("MAPBOX_DOWNLOADS_TOKEN")
                    ?: providers.gradleProperty("MAPBOX_DOWNLOADS_TOKEN").orNull)
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
