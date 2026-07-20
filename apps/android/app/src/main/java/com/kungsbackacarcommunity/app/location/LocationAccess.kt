package com.kungsbackacarcommunity.app.location

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * Why the app cannot see the user's position, from the app's point of view.
 *
 * The app is map-first, so "no location" is not a degraded corner — it is the
 * home screen not working. Until now that failed SILENTLY: the map-home
 * permission request in AuthenticatedApp had a `granted` branch and a
 * `not yet asked` branch and no denial branch at all, so a user who tapped Deny
 * got a permanently puck-less map, a recenter button that did nothing, and no
 * explanation anywhere.
 *
 * The two failure modes are kept APART deliberately, because they are fixed on
 * two different system screens and neither screen can fix the other:
 *  - [PERMISSION_DENIED] — this app was refused the permission. Fixed on the
 *    app's own details page. Sending the user to the device location toggle
 *    instead would show them a switch that is already ON, with nothing to do.
 *  - [SERVICES_OFF] — the permission is granted but the device's location
 *    master switch is off, so NO app can position. Fixed in location settings.
 *    Sending the user to this app's permission page instead would show them a
 *    permission that is already granted, with nothing to do.
 * Getting sent to a screen where the thing you were told to change is already
 * in the state you were told to put it in is worse than a generic message, so
 * the distinction is worth the extra state.
 *
 * Order matters: permission is resolved FIRST. When the app has been refused
 * the permission it cannot use location no matter what the master switch says,
 * so that is the problem to report and the one the user can act on.
 */
enum class LocationAccess {
    GRANTED,
    PERMISSION_DENIED,
    SERVICES_OFF,
    ;

    /** True when the map cannot show the user's position. */
    val isBlocked: Boolean get() = this != GRANTED
}

/**
 * Pure classification of the two inputs. Kept free of Android types so the
 * matrix is unit-testable on the JVM — the runtime states themselves are
 * awkward to drive in an instrumented test, so the decision is tested here and
 * only the thin glue below is left to the device.
 */
fun locationAccessOf(permissionGranted: Boolean, locationServicesEnabled: Boolean): LocationAccess =
    when {
        !permissionGranted -> LocationAccess.PERMISSION_DENIED
        !locationServicesEnabled -> LocationAccess.SERVICES_OFF
        else -> LocationAccess.GRANTED
    }

/**
 * What to offer a user whose location permission is not granted.
 *
 * Android gives no direct "permanently denied" signal;
 * `shouldShowRequestPermissionRationale` is the only probe, and it is false in
 * BOTH the never-asked and the don't-ask-again states. It is disambiguated with
 * [alreadyAsked] — the caller's record that this app has actually raised the
 * dialog — since "false and we have asked" is precisely the permanent denial.
 *
 * This matters because a permanent denial CANNOT be re-prompted: launching the
 * permission request returns denied instantly without showing anything, which
 * to the user is a button that does nothing. Those users must be sent to
 * Settings, and only those users, since Settings is the longer road.
 */
enum class LocationPermissionRemedy {
    /** The system dialog can still be raised. */
    REQUEST_AGAIN,

    /** Don't-ask-again: only the app's settings page can grant it now. */
    OPEN_APP_SETTINGS,
}

fun locationPermissionRemedy(
    canShowRationale: Boolean,
    alreadyAsked: Boolean,
): LocationPermissionRemedy =
    when {
        // The system will still show the dialog — always the shorter road.
        canShowRationale -> LocationPermissionRemedy.REQUEST_AGAIN
        // Asked before, and the system no longer offers a rationale: the user
        // chose "don't ask again" and the dialog can never appear again.
        alreadyAsked -> LocationPermissionRemedy.OPEN_APP_SETTINGS
        // Never asked (rationale is false here too) — the dialog is available.
        else -> LocationPermissionRemedy.REQUEST_AGAIN
    }

/**
 * Whether the device's location master switch is on. False when the platform
 * service is unavailable, which is the safe reading: we cannot position, and
 * the prompt is an explanation rather than a destructive action.
 */
fun isLocationServicesEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return false
    return LocationManagerCompat.isLocationEnabled(manager)
}

/**
 * Opens this app's system details page (where its location permission lives).
 *
 * Returns false when no activity handled it, so the caller can say so instead
 * of leaving a button that visibly does nothing. Follows the codebase's
 * try/catch idiom (openAppNotificationSettings, openExternalUrl) rather than
 * `resolveActivity`, which is subject to package-visibility filtering on
 * API 30+ and can report "nothing resolves this" for an activity that in fact
 * launches fine. Never throws.
 */
fun openAppLocationSettings(context: Context): Boolean =
    startSettings(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null)),
    )

/** Opens the device location-services settings. Never throws; see above. */
fun openDeviceLocationSettings(context: Context): Boolean =
    startSettings(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

/** True when this app currently holds ACCESS_FINE_LOCATION. */
fun hasFineLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** Reads the CURRENT location access state from the platform. */
fun currentLocationAccess(context: Context): LocationAccess =
    locationAccessOf(
        permissionGranted = hasFineLocationPermission(context),
        locationServicesEnabled = isLocationServicesEnabled(context),
    )

/**
 * `shouldShowRequestPermissionRationale` for fine location, or false when the
 * Compose context has no Activity behind it (previews/tests).
 *
 * This is one of TWO inputs and does not by itself decide anything: false here
 * only sends the user to Settings once [locationPermissionRemedy] also sees
 * that we have in fact asked. A caller that has never asked still re-prompts,
 * which is the correct reading of a false — the flag is false before the first
 * ask too.
 */
fun shouldShowLocationRationale(context: Context): Boolean =
    context.findActivity()?.let {
        ActivityCompat.shouldShowRequestPermissionRationale(
            it,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    } ?: false

/** Unwraps the nearest [Activity] from a Compose context, or null. */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun startSettings(context: Context, intent: Intent): Boolean =
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (notFound: ActivityNotFoundException) {
        // Some stripped/OEM ROMs ship no such settings activity.
        false
    }
