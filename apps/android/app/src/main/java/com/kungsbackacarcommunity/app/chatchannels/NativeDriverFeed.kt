package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.driverfeed.DriverFeedRoute
import com.kungsbackacarcommunity.app.driverfeed.DriverFeedStrings

/** The shell supplies identity and localization; the adapter owns all data/access. */
@Composable
internal fun NativeDriverFeed(uid: String) {
    DriverFeedRoute(uid = uid, strings = driverFeedStrings())
}

/** Also usable by local screenshot fixtures; resolving copy never starts a repository. */
@Composable
fun driverFeedStrings(): DriverFeedStrings {
    val localizedResources = LocalResources.current
    val resources = mapOf(
        "title" to R.string.driver_feed_title,
        "source" to R.string.driver_feed_source,
        "loading" to R.string.driver_feed_loading,
        "empty" to R.string.driver_feed_empty,
        "error" to R.string.driver_feed_error,
        "retry" to R.string.driver_feed_retry,
        "refresh" to R.string.driver_feed_refresh,
        "more" to R.string.driver_feed_more,
        "back" to R.string.driver_feed_back,
        "comments" to R.string.driver_feed_comments,
        "noComments" to R.string.driver_feed_noComments,
        "unavailable" to R.string.driver_feed_unavailable,
        "onboarding" to R.string.driver_feed_onboarding,
        "canonicalOnboarding" to R.string.driver_feed_canonicalOnboarding,
        "restricted" to R.string.driver_feed_restricted,
        "image" to R.string.driver_feed_image,
        "likes" to R.string.driver_feed_likes,
        "openApp" to R.string.driver_feed_openApp,
        "appLinkHint" to R.string.driver_feed_appLinkHint,
        "appOpenError" to R.string.driver_feed_appOpenError,
    )
    return DriverFeedStrings { key ->
            localizedResources.getString(resources.getValue(key.removePrefix("driver.feed.")))
    }
}
