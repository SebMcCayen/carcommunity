package com.kungsbackacarcommunity.app.coachmark

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import kotlin.math.roundToInt

/**
 * Runs the one-time first-login coach-mark tour: a dimming overlay that
 * spotlights one map-home control at a time and shows a chat-bubble tooltip
 * pointing at it, with Skip / Next (Done on the last tip) and a "1/N" progress
 * label. Advances through [CoachMarkTour.ORDERED]; [onFinish] fires on Skip,
 * system Back, or completing the final tip — the caller persists "seen" there so
 * the tour never returns.
 *
 * The control bounds come from [anchors] (populated by [Modifier.coachMarkAnchor]
 * on the real controls), so the spotlight and bubble always track the live
 * layout. Stateless about "seen"; that lives in [CoachMarkStore].
 */
@Composable
fun CoachMarkTourHost(
    anchors: CoachMarkAnchorRegistry,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val steps = CoachMarkTour.ORDERED
    // Clamp a restored index in case the step list ever shrank between releases.
    val index = stepIndex.coerceIn(0, steps.lastIndex)
    val step = steps[index]

    CoachMarkOverlay(
        step = step,
        position = CoachMarkTour.position(step),
        count = CoachMarkStep.COUNT,
        isLast = CoachMarkTour.isLast(step),
        targetBounds = anchors.boundsOf(step),
        onNext = { if (CoachMarkTour.isLast(step)) onFinish() else stepIndex = index + 1 },
        onSkip = onFinish,
        modifier = modifier,
    )
}

@Composable
private fun CoachMarkOverlay(
    step: CoachMarkStep,
    position: Int,
    count: Int,
    isLast: Boolean,
    targetBounds: Rect?,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // System Back dismisses the whole tour — expected, and the a11y-friendly exit.
    BackHandler(onBack = onSkip)

    val density = LocalDensity.current
    val bubbleColor = MaterialTheme.colorScheme.surface
    val tourLabel = stringResource(R.string.coachMark_progress, position, count)
    // Spoken description for TalkBack: the bare "1/4" progress is meaningless on
    // its own, so announce the step's title and body too, e.g.
    // "Steg 1 av 4: Starta en körning. Tryck på plus …".
    val tourDescription =
        stringResource(
            R.string.coachMark_a11yTour,
            position,
            count,
            stringResource(step.titleRes()),
            stringResource(step.bodyRes()),
        )

    val spotlightPadPx = with(density) { KccSpacing.s2.toPx() }
    val spotlightRadiusPx = with(density) { KccRadius.md.toPx() }
    val gapPx = with(density) { KccSpacing.s3.toPx() }
    val marginPx = with(density) { KccSpacing.s4.toPx() }
    val tailHalfPx = with(density) { KccSpacing.s2.toPx() }
    val tailHeightPx = with(density) { KccSpacing.s2.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = tourDescription },
    ) {
        var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
        var bubbleHeight by remember { mutableIntStateOf(0) }

        // Bottom layer: a full-screen scrim that (a) captures this overlay's own
        // origin, so the root-space anchor bounds can be made overlay-local, and
        // (b) swallows any tap that MISSES the bubble — a tap on the dimmed
        // background neither advances the tour nor falls through to the app
        // behind this modal coach mark. Composed FIRST, so it is drawn and
        // hit-tested BELOW the bubble; the Skip / Next buttons sit on top and
        // therefore receive their taps unambiguously (the tap-swallow is on this
        // separate layer, never on the shared parent). This mirrors the dismiss-
        // layer pattern in shell/TranslucentPanel. clearAndSetSemantics keeps the
        // invisible layer out of the a11y tree (the bubble carries the actions).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
                .pointerInput(Unit) { detectTapGestures { } }
                .clearAndSetSemantics {},
        )

        val localTarget = targetBounds?.let {
            Rect(
                left = it.left - overlayOrigin.x,
                top = it.top - overlayOrigin.y,
                right = it.right - overlayOrigin.x,
                bottom = it.bottom - overlayOrigin.y,
            )
        }

        // Scrim with a rounded spotlight hole punched around the target. Needs an
        // offscreen layer so BlendMode.Clear actually cuts a hole in the scrim.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            drawRect(color = Color.Black.copy(alpha = 0.6f))
            localTarget?.let { t ->
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(t.left - spotlightPadPx, t.top - spotlightPadPx),
                    size = Size(t.width + spotlightPadPx * 2, t.height + spotlightPadPx * 2),
                    cornerRadius = CornerRadius(spotlightRadiusPx, spotlightRadiusPx),
                    blendMode = BlendMode.Clear,
                )
            }
        }

        // Nothing to point at yet: hold the scrim until the control reports bounds
        // (always within a frame for the always-present map-home controls).
        if (localTarget != null) {
            val containerW = constraints.maxWidth.toFloat()
            val containerH = constraints.maxHeight.toFloat()

            // Bubble width is fixed (so horizontal placement is stable from the
            // first frame): a comfortable card, shrunk to fit narrow screens.
            // coerceAtLeast(0.dp) guards a pathologically narrow layout where the
            // margins alone exceed the width — a negative width would crash layout.
            val bubbleWidthDp =
                minOf(320.dp, (maxWidth - KccSpacing.s4 * 2).coerceAtLeast(0.dp))
            val bubbleWidthPx = with(density) { bubbleWidthDp.toPx() }

            // Bubble goes BELOW a target in the top half, ABOVE one in the bottom
            // half — so bottom-bar controls get a bubble floating above them and the
            // top-right menu button gets one below it.
            val below = localTarget.center.y < containerH / 2f

            val rawX = localTarget.center.x - bubbleWidthPx / 2f
            val maxX = (containerW - marginPx - bubbleWidthPx).coerceAtLeast(marginPx)
            val bubbleX = rawX.coerceIn(marginPx, maxX)

            val bubbleY = if (below) {
                localTarget.bottom + gapPx + tailHeightPx
            } else {
                localTarget.top - gapPx - tailHeightPx - bubbleHeight.toFloat()
            }

            // Only the ABOVE placement needs the measured height; below is stable at
            // once. Hide until placement is settled to avoid a one-frame jump.
            val ready = below || bubbleHeight > 0

            // Tail centred on the target, clamped to stay on the card's flat edge.
            // Via the pure helper so the degenerate narrow-bubble case (which would
            // otherwise invert the coerceIn range and throw) is handled and tested.
            val tailCenterX = CoachMarkGeometry.tailCenterX(
                targetCenterX = localTarget.center.x,
                bubbleLeft = bubbleX,
                bubbleWidth = bubbleWidthPx,
                insetPerSide = spotlightRadiusPx + tailHalfPx,
            )
            val tailY = if (below) bubbleY - tailHeightPx else bubbleY + bubbleHeight.toFloat()

            Box(
                modifier = Modifier
                    .offset { IntOffset(bubbleX.roundToInt(), bubbleY.roundToInt()) }
                    .width(bubbleWidthDp)
                    .onSizeChanged { bubbleHeight = it.height }
                    .graphicsLayer { alpha = if (ready) 1f else 0f },
            ) {
                CoachMarkBubble(
                    step = step,
                    progressLabel = tourLabel,
                    isLast = isLast,
                    onNext = onNext,
                    onSkip = onSkip,
                )
            }

            if (ready) {
                Canvas(
                    modifier = Modifier
                        .offset { IntOffset((tailCenterX - tailHalfPx).roundToInt(), tailY.roundToInt()) }
                        .size(
                            width = with(density) { (tailHalfPx * 2).toDp() },
                            height = with(density) { tailHeightPx.toDp() },
                        ),
                ) {
                    val w = size.width
                    val h = size.height
                    val path = Path().apply {
                        if (below) {
                            // Apex points UP toward the target above the bubble.
                            moveTo(w / 2f, 0f)
                            lineTo(0f, h)
                            lineTo(w, h)
                        } else {
                            // Apex points DOWN toward the target below the bubble.
                            moveTo(0f, 0f)
                            lineTo(w, 0f)
                            lineTo(w / 2f, h)
                        }
                        close()
                    }
                    drawPath(path, color = bubbleColor)
                }
            }
        }
    }
}

/** The chat-bubble card: progress, title, one-sentence body, Skip + Next/Done. */
@Composable
private fun CoachMarkBubble(
    step: CoachMarkStep,
    progressLabel: String,
    isLast: Boolean,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(KccRadius.lg),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(KccSpacing.s5),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            Text(
                text = progressLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(step.titleRes()),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(step.bodyRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.coachMark_skip))
                }
                Spacer(modifier = Modifier.width(KccSpacing.s2))
                Button(onClick = onNext) {
                    Text(
                        stringResource(
                            if (isLast) R.string.coachMark_done else R.string.coachMark_next,
                        ),
                    )
                }
            }
        }
    }
}

private fun CoachMarkStep.titleRes(): Int =
    when (this) {
        CoachMarkStep.Drive -> R.string.coachMark_driveTitle
        CoachMarkStep.Social -> R.string.coachMark_socialTitle
        CoachMarkStep.Explore -> R.string.coachMark_exploreTitle
        CoachMarkStep.History -> R.string.coachMark_historyTitle
    }

private fun CoachMarkStep.bodyRes(): Int =
    when (this) {
        CoachMarkStep.Drive -> R.string.coachMark_driveBody
        CoachMarkStep.Social -> R.string.coachMark_socialBody
        CoachMarkStep.Explore -> R.string.coachMark_exploreBody
        CoachMarkStep.History -> R.string.coachMark_historyBody
    }
