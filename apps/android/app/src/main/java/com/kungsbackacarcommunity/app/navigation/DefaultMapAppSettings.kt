package com.kungsbackacarcommunity.app.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Opens the system screen where the member makes KCC a default handler for map
 * links.
 *
 * Honest framing: there is NO Android API to set a default app programmatically,
 * and no OS-level "default navigator" role at all — the choice is the user's,
 * made in system Settings, and only ever a per-scheme handler chosen through the
 * "Open with" chooser. So the button this backs opens the right settings screen
 * rather than pretending to flip a switch.
 *
 * On API 31+ it opens the app's own "Open by default" screen
 * ([Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS]), where supported-link
 * defaults live. That action does not exist before API 31 and is missing on some
 * OEM builds, so it falls back to the app's details screen
 * ([Settings.ACTION_APPLICATION_DETAILS_SETTINGS]) — from which "Open by
 * default" is one tap away — and finally to the top-level Settings app. Each
 * target is tried in order and the first that resolves wins, so a device without
 * a given screen degrades instead of throwing.
 */
fun openDefaultMapAppSettings(context: Context) {
    val packageUri = Uri.fromParts("package", context.packageName, null)
    val candidates =
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, packageUri))
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            add(Intent(Settings.ACTION_SETTINGS))
        }
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Screen absent on this device — try the next fallback.
        }
    }
}
