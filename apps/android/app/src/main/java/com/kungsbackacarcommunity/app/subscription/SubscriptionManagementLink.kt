package com.kungsbackacarcommunity.app.subscription

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Opens Google Play's management page for one of this app's subscriptions. */
object SubscriptionManagementLink {
    private const val PLAY_STORE_PACKAGE = "com.android.vending"

    /**
     * Official product-specific URL when the product is known, otherwise the
     * generic subscriptions center. The fallback keeps cancellation reachable
     * while a stale/offline entitlement is being reconciled.
     */
    fun webUri(applicationId: String, productId: String?): String =
        if (productId.isNullOrBlank()) {
            "https://play.google.com/store/account/subscriptions"
        } else {
            "https://play.google.com/store/account/subscriptions" +
                "?sku=${encode(productId)}&package=${encode(applicationId)}"
        }

    /**
     * Prefers the Play Store app, then falls back to any browser. If neither can
     * handle the official HTTPS management URL, [onUnavailable] is invoked.
     */
    fun open(
        context: Context,
        applicationId: String,
        productId: String?,
        onUnavailable: () -> Unit,
    ) {
        val uri = Uri.parse(webUri(applicationId, productId))
        val newTask = context !is Activity

        val playIntent =
            Intent(Intent.ACTION_VIEW, uri)
                .setPackage(PLAY_STORE_PACKAGE)
                .apply { if (newTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try {
            context.startActivity(playIntent)
            return
        } catch (_: ActivityNotFoundException) {
            // Play Store is unavailable; the same HTTPS page can open in a browser.
        }

        val browserIntent =
            Intent(Intent.ACTION_VIEW, uri)
                .apply { if (newTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try {
            context.startActivity(browserIntent)
        } catch (_: ActivityNotFoundException) {
            onUnavailable()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
