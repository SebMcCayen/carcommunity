package com.kungsbackacarcommunity.app

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.kungsbackacarcommunity.app.appcheck.AppCheckDebugSecret
import com.kungsbackacarcommunity.app.diagnostics.CrashReporter
import com.kungsbackacarcommunity.app.diagnostics.FirebaseCrashTelemetry
import com.kungsbackacarcommunity.app.diagnostics.FirebaseDiagnosticsReporter
import com.kungsbackacarcommunity.app.map.MapboxNativeWarmup
import com.kungsbackacarcommunity.app.media.KccImageLoader
import com.kungsbackacarcommunity.app.push.PushChannels

/**
 * Application entry point (Phase 15b): registers Firebase App Check as
 * early as possible so every SDK call carries an App Check token.
 *
 * - Release builds attest with Play Integrity.
 * - Debug builds use the debug provider, seeded with a STABLE secret from
 *   `BuildConfig.APP_CHECK_DEBUG_TOKEN` (set via `appcheck.debugToken` in the
 *   gitignored `apps/android/local.properties`). Without it the SDK generates
 *   a random secret that is wiped on every uninstall/reinstall, so each debug
 *   rebuild produces an unregistered token and every App-Check-gated callable
 *   — "report an issue" included — fails with `UNAUTHENTICATED` until the new
 *   token is registered by hand. With it, one console registration survives
 *   every rebuild. When the value is blank (CI, fresh clones) nothing is
 *   seeded and the SDK falls back to its generated token. See
 *   docs/app-check.md for the one-time setup.
 *
 * Guarded like the rest of the Firebase wiring: when google-services.json
 * is absent (CI/local validation builds), FirebaseApp never initializes
 * and App Check registration is skipped — the app still renders.
 * Production ENFORCEMENT is a console-side switch flipped at cutover per
 * docs/app-check.md; client registration is safe to ship ahead of it.
 *
 * Phase 12 slice 22: once Firebase is available, install the diagnostics
 * crash reporter so uncaught exceptions submit a PII-safe report via the
 * public `diagnostics-submitReport` callable before the default handler runs.
 *
 * Alongside it — not instead of it — Firebase Crashlytics is installed, which
 * carries the full stack trace, breadcrumbs and custom keys the diagnostics
 * report deliberately omits. Both handlers run on one crash; see the ordering
 * note in [onCreate] and the contrast in
 * [com.kungsbackacarcommunity.app.diagnostics.CrashTelemetry].
 *
 * Also the install point for the app's single Coil [ImageLoader]
 * ([KccImageLoader]): implementing [ImageLoaderFactory] is how Coil's singleton
 * picks up a configured loader, and it must be the Application that does it so
 * the very first `AsyncImage` — wherever it renders — already has it.
 */
class KccApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader = KccImageLoader.create(this)

    override fun onCreate() {
        super.onCreate()

        // Notification channels are pure Android (no Firebase) — created
        // unconditionally so they exist before the first FCM delivery
        // (Phase 12 slice 21, push portion).
        PushChannels.ensureCreated(this)

        // Preload the Mapbox native libraries OFF the main thread (ANR #1000).
        // The manifest removes the SDK's two androidx.startup initializers, which
        // otherwise load `mapbox-common` + `mapbox-maps` on the MAIN thread during
        // process creation and ANR on slow devices. This does the same load on a
        // background thread so the first MapView pays no cold-load cost. Idempotent
        // and best-effort; see MapboxNativeWarmup.
        MapboxNativeWarmup.warmUp(this)

        // The only persistent status notification is now the live-location
        // foreground service's own ongoing notification (LocationSharingService),
        // which is present iff a live session is running. There is deliberately
        // no separate "app is active" notice while no session is live.

        // FirebaseApp.initializeApp returns null when configuration is
        // absent — mirror FirebaseAuthRepository.createIfAvailable.
        val firebaseApp = FirebaseApp.initializeApp(this) ?: return

        val appCheck = FirebaseAppCheck.getInstance(firebaseApp)
        if (BuildConfig.DEBUG) {
            // MUST precede installAppCheckProviderFactory: the debug provider
            // reads the store the first time it resolves a secret, and only
            // generates its own when the store is empty.
            AppCheckDebugSecret.seedIfConfigured(
                context = this,
                firebaseApp = firebaseApp,
                isDebugBuild = BuildConfig.DEBUG,
                rawToken = BuildConfig.APP_CHECK_DEBUG_TOKEN,
            )
            appCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance(),
            )
        } else {
            appCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance(),
            )
        }

        // Crashlytics. ORDER IS LOAD-BEARING and must stay above
        // CrashReporter.install below.
        //
        // The Crashlytics SDK installs its OWN Thread.UncaughtExceptionHandler,
        // capturing whatever was default at that moment and delegating to it
        // after it has recorded the crash. Touching it here (install() calls
        // FirebaseCrashlytics.getInstance()) puts that handler in place first;
        // CrashReporter.install() then chains itself IN FRONT of it and
        // delegates onward. So one uncaught exception runs:
        //
        //   CrashReporter (PII-safe diagnostics report, no stack trace)
        //     -> Crashlytics handler (full stack trace + breadcrumbs + keys)
        //       -> the platform's original handler (the process dies as usual)
        //
        // Both paths fire, neither masks the other, and the crash still surfaces
        // unchanged. Installing in the opposite order would also chain, but the
        // diagnostics report — the one that must be ENQUEUED on the dying thread
        // — would run after Crashlytics' several-second persist step.
        //
        // install() also applies the debug/release collection decision and
        // attaches the static custom keys, so the first crash of the process
        // already carries build + feature-flag context.
        FirebaseCrashTelemetry.install(this)

        FirebaseDiagnosticsReporter.createIfAvailable(this)?.let { reporter ->
            CrashReporter.install(
                reporter = reporter,
                appVersion = BuildConfig.VERSION_NAME,
                buildNumber = BuildConfig.VERSION_CODE.toString(),
                osVersion = Build.VERSION.RELEASE,
            )
        }
    }
}
