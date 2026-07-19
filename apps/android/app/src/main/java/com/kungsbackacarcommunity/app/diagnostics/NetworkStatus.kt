package com.kungsbackacarcommunity.app.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * "Does this device actually have working internet right now?"
 *
 * Exists solely to keep [FeatureHealthGate] honest: a user in a tunnel, on a
 * plane, or out of data cannot load map tiles, and reporting that as a defect
 * would file a public GitHub issue for the network behaving normally. A noisy
 * auto-reporter gets muted, and a muted reporter catches nothing — so this is
 * load-bearing, not a nicety.
 *
 * A `fun interface` so the decision logic can be unit-tested both ways without a
 * device or a `ConnectivityManager`.
 */
fun interface NetworkStatus {
    /** True only when the device has a validated internet connection. */
    fun isOnline(): Boolean
}

/**
 * [NetworkStatus] backed by `ConnectivityManager`.
 *
 * Requires `NET_CAPABILITY_VALIDATED` as well as `NET_CAPABILITY_INTERNET`:
 * "attached to a cell tower" is not "has working data". Validated is what drops
 * in a tunnel, on a captive-portal wifi, or on a SIM that has run out of
 * allowance — exactly the cases that must not file issues.
 *
 * **Fails closed.** Any error, a missing service, or a null active network all
 * return false ("assume offline"), because the cost of a wrong `false` is one
 * missed report while the cost of a wrong `true` is a public issue filed against
 * a user's flaky connection.
 */
class ConnectivityNetworkStatus(context: Context) : NetworkStatus {
    private val appContext = context.applicationContext

    override fun isOnline(): Boolean =
        runCatching {
            val manager = appContext.getSystemService(ConnectivityManager::class.java)
            val capabilities =
                manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)
}
