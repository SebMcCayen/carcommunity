package com.kungsbackacarcommunity.app.crownhunt

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The short phone VIBRATION that fires when the signed-in member drives onto a
 * rival's Spikmatta (Crown Hunt trap-trigger, victim side). A single ~250 ms
 * buzz — deliberately brief and non-repeating so it reads as a game "hit"
 * without conflicting with the no-alert-while-driving stance (the paired
 * on-screen pop is likewise a ~1.7 s non-modal ReactionOverlay).
 *
 * Uses the platform [Vibrator]/[VibrationEffect] (available from the app's
 * minSdk 26) rather than Compose haptics, because the spec calls for a real
 * phone vibration, not a UI tick. Requires the VIBRATE permission (declared in
 * the manifest). Best-effort: a device with no vibrator, or any failure to
 * obtain the service, is a silent no-op — the on-screen pop still plays.
 */
object TrapDrainVibration {
    private const val BUZZ_MS = 250L

    fun buzz(context: Context) {
        val vibrator = resolveVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            vibrator.vibrate(
                VibrationEffect.createOneShot(BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        }
    }

    private fun resolveVibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
