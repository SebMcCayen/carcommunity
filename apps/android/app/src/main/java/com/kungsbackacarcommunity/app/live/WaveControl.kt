package com.kungsbackacarcommunity.app.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.CircleControl
import com.kungsbackacarcommunity.app.shell.MAP_HOME_WAVE_TAG

/**
 * The right-side WAVE control. A car/game-styled round icon that ENTERS with a
 * smooth slide-from-the-right + scale/fade pop when [visible] flips true (a nearby
 * live user appears while you are sharing) and animates OUT when it flips false.
 * Built on the shared [CircleControl] so it matches the rest of the stack.
 *
 * The icon greys and ignores taps while inside the client cooldown mirror
 * ([cooldownUntilMs]); a self-driving ~200ms ticker re-enables it exactly when the
 * window elapses. The SERVER is the real anti-spam gate — this is only the
 * optimistic UX so the icon dims the instant you wave.
 */
@Composable
fun WaveCircleControl(
    visible: Boolean,
    cooldownUntilMs: Long,
    onWave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            fadeIn(animationSpec = tween(WAVE_CONTROL_ENTER_MS)) +
                scaleIn(
                    initialScale = 0.6f,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                ) +
                slideInHorizontally(
                    animationSpec = tween(WAVE_CONTROL_ENTER_MS),
                    // Slide in from just off the right edge, where the stack lives.
                    initialOffsetX = { full -> full / 2 },
                ),
        exit =
            fadeOut(animationSpec = tween(WAVE_CONTROL_EXIT_MS)) +
                scaleOut(
                    targetScale = 0.6f,
                    animationSpec = tween(WAVE_CONTROL_EXIT_MS),
                ) +
                slideOutHorizontally(
                    animationSpec = tween(WAVE_CONTROL_EXIT_MS),
                    targetOffsetX = { full -> full / 2 },
                ),
    ) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        // Tick only while a cooldown is pending, so the icon re-enables itself the
        // moment the window elapses without a permanent recomposition loop.
        LaunchedEffect(cooldownUntilMs) {
            while (System.currentTimeMillis() < cooldownUntilMs) {
                delay(WAVE_TICK_MS)
                now = System.currentTimeMillis()
            }
            now = System.currentTimeMillis()
        }
        val enabled = WavePresence.isSendEnabled(now, cooldownUntilMs)
        CircleControl(
            icon = Icons.Filled.WavingHand,
            contentDescription = stringResource(R.string.shell_waveButton),
            onClick = { if (enabled) onWave() },
            // Car/game-styled: a warm primary-tinted disc when armed, dimmed to the
            // neutral surface while on cooldown so the state reads at a glance.
            containerColor =
                if (enabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            contentColor =
                if (enabled) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = modifier.testTag(MAP_HOME_WAVE_TAG),
        )
    }
}

private const val WAVE_CONTROL_ENTER_MS = 260
private const val WAVE_CONTROL_EXIT_MS = 200
private const val WAVE_TICK_MS = 200L
