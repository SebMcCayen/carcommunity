package com.kungsbackacarcommunity.app.auth

/**
 * RELEASE source-set stub for [DevEmulatorSignIn]. Contains no dev credentials
 * and no sign-in path: the dev emulator sign-in exists only in debug builds
 * (see the real implementation in src/debug). [create] always returns null, so
 * release builds never render the dev button and no test credentials are
 * compiled into release artifacts.
 */
object DevEmulatorSignIn {
    fun create(firebaseAvailable: Boolean): (() -> Unit)? = null
}
