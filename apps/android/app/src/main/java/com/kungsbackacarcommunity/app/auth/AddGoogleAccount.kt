package com.kungsbackacarcommunity.app.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Route from "you have no Google account" to actually adding one.
 *
 * When Credential Manager reports [androidx.credentials.exceptions.NoCredentialException]
 * the sign-in screen is otherwise a dead end — the user can re-tap the button
 * forever and nothing changes. [Settings.ACTION_ADD_ACCOUNT] turns that into one
 * tap, so the guidance text comes with a button when the system can honour it.
 *
 * The intent is NOT universally resolvable: it is a documented framework action,
 * but managed/kiosk profiles and some OEM ROMs strip or restrict the
 * add-account activity. Every entry point here therefore checks first
 * ([canAddGoogleAccount]) and still catches [ActivityNotFoundException] — the
 * check and the launch are separate moments and the resolution can change
 * between them. A device that cannot resolve it falls back to text-only guidance
 * (the text names Settings > Accounts, which is reachable by hand), never a
 * button that does nothing and never a crash.
 */

/**
 * The add-account intent, scoped to Google accounts so the user lands on the
 * Google flow rather than a generic account-type picker.
 *
 * `EXTRA_ACCOUNT_TYPES` is an authority-filter, not a guarantee: a device with no
 * Google account authority at all may ignore it. That is fine — the worst case is
 * the generic account picker, which still gets the user where they need to go.
 */
internal fun addGoogleAccountIntent(): Intent =
    Intent(Settings.ACTION_ADD_ACCOUNT)
        .putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf(GOOGLE_ACCOUNT_TYPE))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * Whether this device can actually open the add-account screen. Drives whether
 * the sign-in screen offers the button at all — showing one that silently does
 * nothing is worse than the text alone.
 */
fun canAddGoogleAccount(context: Context): Boolean =
    addGoogleAccountIntent().resolveActivity(context.packageManager) != null

/**
 * Opens the system add-account screen. Never throws: if the activity has
 * disappeared since [canAddGoogleAccount] said yes, the tap is a no-op and the
 * on-screen guidance still tells the user where to go by hand.
 */
fun openAddGoogleAccount(context: Context) {
    try {
        context.startActivity(addGoogleAccountIntent())
    } catch (notFound: ActivityNotFoundException) {
        // No add-account activity on this device/ROM — the guidance text stands
        // on its own ("Settings > Accounts"), so there is nothing further to do.
    }
}

/** The Android account-manager authority for Google accounts. */
private const val GOOGLE_ACCOUNT_TYPE = "com.google"
