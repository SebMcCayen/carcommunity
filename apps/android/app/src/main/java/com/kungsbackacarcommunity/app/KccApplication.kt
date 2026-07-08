package com.kungsbackacarcommunity.app

import android.app.Application
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.kungsbackacarcommunity.app.diagnostics.CrashReporter
import com.kungsbackacarcommunity.app.diagnostics.FirebaseDiagnosticsReporter
import com.kungsbackacarcommunity.app.push.PushChannels

/**
 * Application entry point (Phase 15b): registers Firebase App Check as
 * early as possible so every SDK call carries an App Check token.
 *
 * - Release builds attest with Play Integrity.
 * - Debug builds use the debug provider (token printed to logcat on
 *   first run; register it in the Firebase console / emulator).
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
 */
class KccApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Notification channels are pure Android (no Firebase) — created
        // unconditionally so they exist before the first FCM delivery
        // (Phase 12 slice 21, push portion).
        PushChannels.ensureCreated(this)

        // FirebaseApp.initializeApp returns null when configuration is
        // absent — mirror FirebaseAuthRepository.createIfAvailable.
        val firebaseApp = FirebaseApp.initializeApp(this) ?: return

        val appCheck = FirebaseAppCheck.getInstance(firebaseApp)
        if (BuildConfig.DEBUG) {
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
