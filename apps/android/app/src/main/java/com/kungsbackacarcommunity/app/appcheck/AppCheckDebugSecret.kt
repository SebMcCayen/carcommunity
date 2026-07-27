package com.kungsbackacarcommunity.app.appcheck

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.firebase.FirebaseApp

/**
 * Makes the App Check DEBUG secret stable across reinstalls.
 *
 * ## Why this exists
 *
 * Every callable enforces App Check (`enforceAppCheck` across `functions/src`,
 * guarded by `functions/src/__tests__/appcheck-guard.test.ts`). On debug builds
 * the client attests with `DebugAppCheckProviderFactory`, whose secret the SDK
 * generates with `UUID.randomUUID()` on first run and persists in
 * SharedPreferences. That store lives in the app's data dir, so an
 * uninstall/reinstall — i.e. every `installDebug` from a clean device state —
 * throws it away and the SDK mints a *new*, unregistered secret. The backend
 * then rejects every gated callable with `UNAUTHENTICATED` until the new token
 * is registered in the Firebase console by hand. That is the "report an issue
 * fails after every rebuild" bug.
 *
 * Seeding the SDK's own store with a fixed secret *before* the provider factory
 * is installed makes the SDK adopt that secret instead of generating one, so a
 * single console registration survives every rebuild.
 *
 * There is deliberately no server-side change: `enforceAppCheck` cannot target
 * debug clients (the backend can't tell them apart), and relaxing it would
 * disable the guard test's protection for release traffic too.
 *
 * ## Store layout
 *
 * Verified against `firebase-appcheck-debug` 19.3.0 (Firebase BoM 34.16.0),
 * class `com.google.firebase.appcheck.debug.internal.StorageHelper`:
 * - prefs file: `String.format("com.google.firebase.appcheck.debug.store.%s", persistenceKey)`
 *   where `persistenceKey` is [FirebaseApp.getPersistenceKey] — note the
 *   per-app suffix; the bare `...debug.store` name (no suffix) is NOT the file
 *   the SDK reads.
 * - key: `com.google.firebase.appcheck.debug.DEBUG_SECRET`
 *
 * These are SDK internals rather than public API, so every step here is
 * best-effort: a mismatch degrades to today's behaviour (SDK-generated token)
 * and never crashes or blocks startup.
 *
 * Note: the Android SDK has no `FIREBASE_APP_CHECK_DEBUG_TOKEN` environment
 * variable — that mechanism is web/JS only — so seeding the store is the way.
 */
object AppCheckDebugSecret {
    /** Prefix of the SDK's debug-store SharedPreferences file name. */
    const val PREFS_NAME_PREFIX: String = "com.google.firebase.appcheck.debug.store."

    /** Key the SDK reads the debug secret from. */
    const val DEBUG_SECRET_KEY: String = "com.google.firebase.appcheck.debug.DEBUG_SECRET"

    private const val TAG = "AppCheckDebugSecret"

    /**
     * Normalizes a configured token: trimmed, or `null` when absent/blank.
     *
     * Blank is the default (CI, fresh clones, and anyone who hasn't opted in),
     * and it means "leave the SDK alone".
     */
    fun normalizeToken(rawToken: String?): String? = rawToken?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Whether the debug store should be seeded at all.
     *
     * Release builds must never touch it — they attest with Play Integrity and
     * don't even have the debug provider installed.
     */
    fun shouldSeed(isDebugBuild: Boolean, rawToken: String?): Boolean =
        isDebugBuild && normalizeToken(rawToken) != null

    /** The SDK's prefs file name for a given [FirebaseApp.getPersistenceKey]. */
    fun prefsFileName(persistenceKey: String): String = PREFS_NAME_PREFIX + persistenceKey

    /**
     * Whether an actual write is needed. Re-writing an identical secret would
     * be harmless but pointless, and skipping it keeps repeat launches free of
     * disk churn.
     */
    fun shouldWrite(storedSecret: String?, desiredSecret: String): Boolean =
        storedSecret != desiredSecret

    /**
     * Seeds the SDK's debug store with [rawToken] when [shouldSeed] allows it.
     *
     * Must be called BEFORE `installAppCheckProviderFactory` — the provider
     * reads the store when it first resolves a secret.
     *
     * @return true iff a secret was written.
     */
    fun seedIfConfigured(
        context: Context,
        firebaseApp: FirebaseApp,
        isDebugBuild: Boolean,
        rawToken: String?,
    ): Boolean {
        if (!shouldSeed(isDebugBuild, rawToken)) return false
        val secret = normalizeToken(rawToken) ?: return false
        return try {
            val prefs = context.getSharedPreferences(
                prefsFileName(firebaseApp.persistenceKey),
                Context.MODE_PRIVATE,
            )
            if (!shouldWrite(prefs.getString(DEBUG_SECRET_KEY, null), secret)) {
                return false
            }
            // apply() is enough despite the SDK reading the store from its own
            // thread moments later: SharedPreferences instances are cached per
            // (process, file name), so the SDK's getSharedPreferences returns
            // this very instance and sees the value from the in-memory map
            // immediately — apply() only defers the disk flush.
            prefs.edit { putString(DEBUG_SECRET_KEY, secret) }
            true
        } catch (e: Exception) {
            // Never fatal: fall back to the SDK-generated token (today's
            // behaviour) rather than take down a debug launch. The token itself
            // is deliberately not logged.
            Log.w(TAG, "Could not seed the App Check debug secret; using the SDK-generated one", e)
            false
        }
    }
}
