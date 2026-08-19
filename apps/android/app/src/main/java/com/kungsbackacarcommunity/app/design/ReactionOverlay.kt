package com.kungsbackacarcommunity.app.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Test tag on the reaction overlay's animated badge. */
const val REACTION_OVERLAY_TAG = "reaction_overlay"

/** The four phases of one reaction pop, in order. */
enum class ReactionOverlayPhase { Hidden, Entering, Holding, Exiting }

/**
 * PURE timing for the mid-screen reaction pop — show -> hold -> hide — with NO
 * Compose or Android types, so the state machine (phase boundaries, total
 * visible duration, fade) is unit-testable off the composable
 * ([ReactionOverlayTimingTest]).
 *
 * The visible life of one reaction is [ENTER_MS] popping in, [HOLD_MS] held at
 * rest, then [EXIT_MS] fading out — deliberately SHORT (~1.7s total) so it never
 * blocks the map for long. [ReactionOverlay] drives the actual pop with a bouncy
 * spring for the scale, but every duration and the fade come from here so the
 * two cannot drift.
 */
object ReactionOverlayTiming {
    const val ENTER_MS: Long = 240
    const val HOLD_MS: Long = 1_100
    const val EXIT_MS: Long = 380
    const val TOTAL_MS: Long = ENTER_MS + HOLD_MS + EXIT_MS

    /** The scale the badge starts from before it pops in to 1.0 (a small pop). */
    const val ENTER_START_SCALE: Float = 0.35f

    /** The small entry tilt (degrees) the badge settles from, for a playful pop. */
    const val ENTER_START_SPIN: Float = -14f

    /** The phase at [elapsedMs] into the pop. Negative or past-total is [Hidden]. */
    fun phaseAt(elapsedMs: Long): ReactionOverlayPhase =
        when {
            elapsedMs < 0L -> ReactionOverlayPhase.Hidden
            elapsedMs < ENTER_MS -> ReactionOverlayPhase.Entering
            elapsedMs < ENTER_MS + HOLD_MS -> ReactionOverlayPhase.Holding
            elapsedMs < TOTAL_MS -> ReactionOverlayPhase.Exiting
            else -> ReactionOverlayPhase.Hidden
        }

    /** True once the pop has fully finished (the host may clear the event). */
    fun isFinished(elapsedMs: Long): Boolean = elapsedMs >= TOTAL_MS

    /**
     * The badge's alpha at [elapsedMs]: 0 -> 1 across the enter, a solid 1 through
     * the hold, then 1 -> 0 across the exit, and 0 outside the pop. Clamped to
     * [0, 1] so a caller that over-runs the clock never produces a bad alpha.
     */
    fun alphaAt(elapsedMs: Long): Float =
        when (phaseAt(elapsedMs)) {
            ReactionOverlayPhase.Hidden -> 0f
            ReactionOverlayPhase.Entering -> (elapsedMs.toFloat() / ENTER_MS).coerceIn(0f, 1f)
            ReactionOverlayPhase.Holding -> 1f
            ReactionOverlayPhase.Exiting -> {
                val intoExit = elapsedMs - (ENTER_MS + HOLD_MS)
                (1f - intoExit.toFloat() / EXIT_MS).coerceIn(0f, 1f)
            }
        }
}

/**
 * One thing to pop in the middle of the screen. Deliberately GENERIC (an icon +
 * optional caption + tint), NOT tied to convoys, so the same overlay is reused by
 * the convoy reactions AND the later crown-spawn / police-proximity / wave
 * features — each just builds its own event.
 *
 * @param id changes for every distinct pop; a repeat of the SAME id does not
 *   re-trigger the animation, and a new id restarts it (so two reactions in a row
 *   both play). Use the source event's own id (e.g. the reaction doc id).
 */
data class ReactionOverlayEvent(
    val id: String,
    val icon: ImageVector,
    val caption: String? = null,
    /** null = [MaterialTheme] primary. */
    val tint: Color? = null,
    /**
     * What TalkBack announces when this pops (a live-region announcement, since the
     * pop is transient and non-focusable). Null announces nothing.
     */
    val contentDescription: String? = null,
    /**
     * When true the announcement INTERRUPTS whatever TalkBack is saying
     * (LiveRegionMode.Assertive) rather than queueing politely — for a
     * safety-relevant pop like the police alert. Defaults false (polite).
     */
    val assertive: Boolean = false,
)

/**
 * A NON-BLOCKING mid-screen pop: when [event] becomes non-null it scales in with a
 * bouncy spring and a small settle-spin, holds briefly, fades out, then calls
 * [onFinished] so the host can clear it. Renders nothing when [event] is null.
 *
 * Draws over the map WITHOUT capturing touches or blocking it: no node here takes
 * pointer input (the badge is a non-clickable Surface), so the map stays fully
 * interactive underneath. It is INTENTIONALLY ACCESSIBLE rather than hidden: the
 * badge carries a LIVE-REGION announcement built from [ReactionOverlayEvent.contentDescription]
 * (assertive for a safety-relevant pop like the police alert), so a TalkBack user
 * HEARS the reaction ("Police reported" / "<name> says hi") even though the pop is
 * transient and never grabs focus. The badge sits in the CENTRE by default; the
 * host aligns the whole overlay.
 *
 * Timing comes from [ReactionOverlayTiming]; only the scale/spin easing is a
 * spring here, for the car/game-inspired pop.
 */
@Composable
fun ReactionOverlay(
    event: ReactionOverlayEvent?,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {},
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val current = event ?: return@Box

        val scale = remember(current.id) { Animatable(ReactionOverlayTiming.ENTER_START_SCALE) }
        val alpha = remember(current.id) { Animatable(0f) }
        val spin = remember(current.id) { Animatable(ReactionOverlayTiming.ENTER_START_SPIN) }
        val finished by rememberUpdatedState(onFinished)

        // The LaunchedEffect block is a CoroutineScope, so the bouncy scale/spin
        // springs run as CHILD jobs (fire-and-forget, structured) while the parent
        // drives the timed enter-fade -> hold -> exit-fade sequence off
        // ReactionOverlayTiming. A new id restarts the whole thing; the same id
        // does not re-trigger.
        LaunchedEffect(current.id) {
            scale.snapTo(ReactionOverlayTiming.ENTER_START_SCALE)
            spin.snapTo(ReactionOverlayTiming.ENTER_START_SPIN)
            alpha.snapTo(0f)
            launch {
                scale.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                )
            }
            launch {
                spin.animateTo(
                    targetValue = 0f,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                )
            }
            alpha.animateTo(1f, animationSpec = tween(ReactionOverlayTiming.ENTER_MS.toInt()))
            delay(ReactionOverlayTiming.HOLD_MS)
            alpha.animateTo(0f, animationSpec = tween(ReactionOverlayTiming.EXIT_MS.toInt()))
            finished()
        }

        val tint = current.tint ?: MaterialTheme.colorScheme.primary
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
            modifier =
                Modifier
                    .graphicsLayer {
                        this.alpha = alpha.value
                        scaleX = scale.value
                        scaleY = scale.value
                        rotationZ = spin.value
                    }
                    // ONE merged, non-interactive semantics node that ANNOUNCES the
                    // reaction via a live region (assertive interrupts, for the police
                    // alert). The icon + caption below are decorative to a11y — the
                    // announcement here is the single spoken description, so nothing is
                    // read twice and no dead contentDescription is left on the icon.
                    // A semantics modifier takes no pointer input, so the map underneath
                    // stays interactive.
                    .semantics(mergeDescendants = true) {
                        liveRegion =
                            if (current.assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
                        current.contentDescription?.let { contentDescription = it }
                    }
                    .testTag(REACTION_OVERLAY_TAG),
        ) {
            Surface(
                shape = CircleShape,
                color = tint,
                shadowElevation = 6.dp,
                modifier = Modifier.size(96.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = current.icon,
                        // Decorative: the merged live-region node above carries the
                        // spoken description, so the icon must not also announce.
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(52.dp),
                    )
                }
            }
            current.caption?.let { caption ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 3.dp,
                ) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier.padding(horizontal = KccSpacing.s3, vertical = KccSpacing.s1),
                    )
                }
            }
        }
    }
}
