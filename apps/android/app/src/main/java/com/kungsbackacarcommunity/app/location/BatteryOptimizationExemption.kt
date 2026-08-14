package com.kungsbackacarcommunity.app.location

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.getSystemService
import com.kungsbackacarcommunity.app.R

/**
 * One-time request to exempt the app from battery optimization (#849).
 *
 * ## Why
 * A live drive records via a location-typed foreground service that must keep
 * running with the app backgrounded / the screen off. On devices with aggressive
 * battery optimization — notably Samsung — or under Doze, the OS still kills the
 * backgrounded process, losing the in-flight drive. An ignore-battery-
 * optimizations exemption keeps the tracking session alive. Google Play permits
 * this for apps whose core function is location tracking (see the manifest
 * justification on REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).
 *
 * ## How
 * The ask is a user-consented system dialog, shown ONCE (the first time a drive
 * starts). Declining leaves a fully working app — the on-disk recording journal
 * ([com.kungsbackacarcommunity.app.drives.DriveRecordingJournal]) still resumes a
 * killed drive; the exemption merely reduces how often a kill happens at all.
 *
 * The decision ([shouldPrompt]) is pure so it is unit-testable without an Android
 * runtime; the exemption check and the "already asked" flag are the impure
 * boundaries.
 */
object BatteryOptimizationGate {

    private const val PREFS = "kcc_battery_optimization"
    private const val KEY_ASKED = "ignore_battery_optimizations_asked"

    /**
     * Whether to show the exemption prompt now. Pure so the policy is testable.
     *
     * Asks only when a drive is actually recording, the app is NOT already
     * exempt, and it has never been asked before — so it never nags and never
     * appears when it would do nothing.
     */
    fun shouldPrompt(
        isRecordingDrive: Boolean,
        isIgnoringBatteryOptimizations: Boolean,
        alreadyAsked: Boolean,
    ): Boolean =
        isRecordingDrive &&
            !isIgnoringBatteryOptimizations &&
            !alreadyAsked

    /** Whether the OS currently exempts this app from battery optimization. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService<PowerManager>() ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasAsked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ASKED, true)
            .apply()
    }

    /**
     * Opens the system "ignore battery optimizations" request for this app. Uses
     * the direct-request action (gated by the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * permission), falling back to the general battery-optimization settings list
     * if no activity handles it. Returns false only if neither could be launched.
     */
    // BatteryLife lint flags the direct-request action; it is INTENDED here — this
    // app's core function is location-tracking a drive that must survive being
    // backgrounded, a use Google Play permits (see the manifest justification).
    @SuppressLint("BatteryLife")
    fun requestExemption(context: Context): Boolean {
        val direct =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
        if (launch(context, direct)) return true
        return launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun launch(context: Context, intent: Intent): Boolean =
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            // Some OEMs / device-policy configs block the battery-optimization
            // action outright, throwing SecurityException from startActivity. Treat
            // that as "not launchable" rather than crashing on the user's Allow tap.
            false
        }
}

/**
 * Shows the battery-optimization exemption dialog ONCE, the first time a drive is
 * recording ([isRecordingDrive] true) on a device that is not already exempt.
 * Renders nothing otherwise. Whatever the user answers, the ask is recorded so it
 * never reappears — declining is a supported, fully working state.
 */
@Composable
fun DriveBatteryOptimizationPrompt(isRecordingDrive: Boolean) {
    val context = LocalContext.current

    // Snapshot the decision once: re-reading it after the dialog resolves (or the
    // exemption is granted) must not resurface it.
    val shouldPrompt =
        remember(isRecordingDrive) {
            BatteryOptimizationGate.shouldPrompt(
                isRecordingDrive = isRecordingDrive,
                isIgnoringBatteryOptimizations =
                    BatteryOptimizationGate.isIgnoringBatteryOptimizations(context),
                alreadyAsked = BatteryOptimizationGate.hasAsked(context),
            )
        }

    var visible by remember(shouldPrompt) { mutableStateOf(shouldPrompt) }
    if (!visible) return

    AlertDialog(
        onDismissRequest = {
            BatteryOptimizationGate.markAsked(context)
            visible = false
        },
        title = { Text(stringResource(R.string.batteryOptimization_title)) },
        text = { Text(stringResource(R.string.batteryOptimization_body)) },
        confirmButton = {
            TextButton(
                onClick = {
                    BatteryOptimizationGate.markAsked(context)
                    BatteryOptimizationGate.requestExemption(context)
                    visible = false
                },
            ) {
                Text(stringResource(R.string.batteryOptimization_allow))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    BatteryOptimizationGate.markAsked(context)
                    visible = false
                },
            ) {
                Text(stringResource(R.string.batteryOptimization_notNow))
            }
        },
    )
}
