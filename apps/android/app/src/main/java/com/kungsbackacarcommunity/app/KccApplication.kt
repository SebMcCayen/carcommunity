package com.kungsbackacarcommunity.app

import android.app.Application
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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

        // Debug-only local-emulator wiring. This block is present in the release
        // binary (release has isMinifyEnabled=false) but never executes there: it
        // is runtime-gated on BuildConfig.DEBUG (false in release) AND
        // BuildConfig.USE_FIREBASE_EMULATOR (hardcoded false in the release
        // buildType). In a normal debug build it is also off unless the build was
        // assembled with -PuseFirebaseEmulator=true. Must run before any
        // Auth/Firestore use, so it lives here in Application.onCreate ahead of
        // MainActivity's wiring. The Firebase Auth (9099) and Firestore (8080)
        // emulators listen on the host loopback (firebase.json); the reachable host
        // differs by device type — see emulatorHost().
        if (BuildConfig.DEBUG && BuildConfig.USE_FIREBASE_EMULATOR) {
            val host = emulatorHost()
            FirebaseAuth.getInstance(firebaseApp).useEmulator(host, 9099)
            FirebaseFirestore.getInstance(firebaseApp).useEmulator(host, 8080)
        }

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

    /**
     * Host that reaches the local Firebase emulators from THIS device:
     * - Android emulator: `10.0.2.2`, the emulator's alias for the host loopback.
     * - Physical device: `127.0.0.1`, reachable once the emulator ports are
     *   forwarded to the device with `adb reverse tcp:9099 tcp:9099` (and 8080).
     *
     * Emulator detection combines several Build signals so it's robust across
     * emulator images (goldfish/ranchu hardware, generic/sdk fingerprints and
     * products, the "Emulator"/"Android SDK built for" models, Genymotion).
     * Debug/USE_FIREBASE_EMULATOR-only path, so this only ever runs in that mode.
     */
    private fun emulatorHost(): String {
        val isEmulator = Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.FINGERPRINT.contains("generic") ||
            Build.HARDWARE == "goldfish" ||
            Build.HARDWARE == "ranchu" ||
            Build.PRODUCT.contains("sdk") ||
            Build.PRODUCT.contains("emulator") ||
            Build.PRODUCT.contains("simulator") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.MANUFACTURER.contains("Genymotion")
        return if (isEmulator) "10.0.2.2" else "127.0.0.1"
    }
}
