package com.kungsbackacarcommunity.app.auth

import com.google.firebase.auth.FirebaseAuth
import com.kungsbackacarcommunity.app.BuildConfig

/**
 * DEBUG source-set implementation of the dev sign-in against the local Firebase
 * Auth emulator.
 *
 * The seeded test-user credentials live ONLY in this debug source set (see the
 * matching no-op stub in src/release), so they are NOT compiled into release
 * APK/AAB artifacts even with minification disabled. MainActivity (in src/main)
 * calls [create] uniformly; in a release build it links against the release
 * stub, which returns null and contains no credentials.
 *
 * The returned action is non-null only when a debug build was assembled with
 * -PuseFirebaseEmulator=true AND Firebase is configured, matching the emulator
 * wiring installed by KccApplication under the same guard.
 */
object DevEmulatorSignIn {
    // Local-emulator test user (seeded by scripts/local-android/seed-sven.js).
    private const val EMAIL = "sven.svensson@example.com"
    private const val PASSWORD = "Test1234!"

    fun create(firebaseAvailable: Boolean): (() -> Unit)? =
        if (BuildConfig.USE_FIREBASE_EMULATOR && firebaseAvailable) {
            {
                FirebaseAuth.getInstance()
                    .signInWithEmailAndPassword(EMAIL, PASSWORD)
                    .addOnFailureListener { e ->
                        // Debug-only; surface emulator sign-in failures in logcat
                        // so a misconfigured/absent emulator is easy to diagnose.
                        android.util.Log.w("KccDevSignIn", "Emulator dev sign-in failed", e)
                    }
            }
        } else {
            null
        }
}
