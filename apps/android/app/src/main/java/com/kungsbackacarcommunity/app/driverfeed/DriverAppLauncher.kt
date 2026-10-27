package com.kungsbackacarcommunity.app.driverfeed

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kungsbackacarcommunity.app.BuildConfig
import java.net.URI

internal const val DRIVER_ANDROID_PACKAGE = "com.kungsbackacarcommunity.driver"
private val linkedPostIdPattern = Regex("^[A-Za-z0-9_-]{8,128}$")

/** Contract v1: the bridge's id is the original driverThreads document ID. */
internal fun driverPostUri(postId: String): Uri? =
    postId.takeIf(linkedPostIdPattern::matches)?.let {
        Uri.Builder().scheme("kcc-driver").authority("post").appendPath(it).build()
    }

/** Trusted public website configuration only; never derive it from post/server content. */
internal fun driverFallbackUri(value: String): Uri? = runCatching {
    val uri = URI(value)
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
        uri.rawFragment != null) null else Uri.parse(uri.toASCIIString())
}.getOrNull()

/** Only the verified Driver package receives the ID; the website carries no post data. */
internal fun openDriverPost(
    context: Context, postId: String, fallbackUrl: String = BuildConfig.DRIVER_DOWNLOAD_URL,
): Boolean {
    val uri = driverPostUri(postId) ?: return false
    val postIntent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage(DRIVER_ANDROID_PACKAGE)
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    if (tryStartDriverIntent(context, postIntent)) return true
    // The default is the owner-approved community website, not a Driver download listing.
    val website = driverFallbackUri(fallbackUrl) ?: return false
    return tryStartDriverIntent(context, Intent(Intent.ACTION_VIEW, website).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

private fun tryStartDriverIntent(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

@Composable
internal fun driverPostLauncher(): (String) -> Boolean {
    val context = LocalContext.current
    return remember(context) { { postId -> openDriverPost(context, postId) } }
}
