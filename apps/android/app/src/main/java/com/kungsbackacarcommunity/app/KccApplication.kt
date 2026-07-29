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
import com.kungsbackacarcommunity.app.diagnostics.FirebaseDiagnosticsReporter
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
