package com.kungsbackacarcommunity.app.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import kotlin.math.roundToInt
import kotlinx.coroutines.launch


/**
 * Fraction of the SAFE area (window minus the status-bar inset) that a shell
 * panel's card occupies, anchored to the bottom.
 *
 * Deliberately below 1: the remaining `1 - fraction` is a genuinely uncovered,
 * tappable strip of live map above the card — the "outside" that makes the panel
 * read as an overlay rather than a page, and the target for the outside-tap
 * dismiss. Mirrors the chat hub's card (see `ChatHubPopup`); `fillMaxHeight()`
 * WITHOUT a fraction fills the parent's max-height constraint and leaves no real
 * strip at all.
 */
private const val PANEL_CARD_HEIGHT_FRACTION = 0.92f

/**
 * Alpha of a panel card's surface colour. Translucent enough that the live map
 * reads through it, opaque enough that body text stays legible over moving
 * roads. Matches the chat hub's card.
 */
private const val PANEL_CARD_ALPHA = 0.92f

/** Width/height of the drag handle pill at the top of a panel. */
private val PanelHandleWidth = 32.dp
private val PanelHandleHeight = 4.dp

/** Height of the touch target around the handle — comfortably grabbable. */
private val PanelHandleTouchHeight = 28.dp

/**
 * Pure (Android-free, Compose-free) drag arithmetic for [TranslucentShellPanel],
 * so the gesture's actual DECISIONS are JVM-unit-testable instead of only
 * reachable through an instrumentation drag.
 *
 * The panel can only move DOWN, so its offset is always `>= 0` and "dismiss"
 * always means "let it keep going down".
 */
internal object PanelDrag {
    /**
     * Fraction of the card's own height a downward drag must cover before
     * RELEASING dismisses it. Proportional rather than a fixed dp so the gesture
     * feels the same on a tall phone and a short landscape window.
     */
    const val DISMISS_DISTANCE_FRACTION = 0.35f

    /**
     * Downward velocity (px/s) at which a release dismisses regardless of how
     * far the drag actually travelled — the "quick flick down" case. A drag that
     * covered almost nothing but was thrown downwards is unambiguously a dismiss.
     */
    const val FLING_DISMISS_VELOCITY_PX_PER_SECOND = 800f

    /** Distance a release must have covered to dismiss, for a card [panelHeightPx] tall. */
    fun dismissThresholdPx(panelHeightPx: Int): Float =
        panelHeightPx * DISMISS_DISTANCE_FRACTION

    /**
     * Whether releasing at [offsetPx] with [velocityPxPerSecond] should dismiss
     * the panel (true) or spring it back to rest (false).
     *
     * Velocity is considered as well as distance, in both directions:
     * - thrown DOWN fast enough → dismiss even from a short drag;
     * - thrown UP fast enough → spring back even from past the threshold (the
     *   user changed their mind mid-gesture and is putting it back);
     * - otherwise distance decides.
     *
     * A non-positive [dismissThresholdPx] means the card has not been measured
     * yet; nothing can dismiss until it has, so a pre-measure gesture can never
     * close the panel by accident.
     */
    fun shouldDismiss(
        offsetPx: Float,
        velocityPxPerSecond: Float,
        dismissThresholdPx: Float,
    ): Boolean {
        if (dismissThresholdPx <= 0f) return false
        if (offsetPx <= 0f) return false
        if (velocityPxPerSecond >= FLING_DISMISS_VELOCITY_PX_PER_SECOND) return true
        if (velocityPxPerSecond <= -FLING_DISMISS_VELOCITY_PX_PER_SECOND) return false
        return offsetPx >= dismissThresholdPx
    }

    /**
     * How much of a scroll the PANEL takes BEFORE the content sees it.
     *
     * Only the "put it back" direction: while the panel is pulled down
     * ([offsetPx] > 0) an upward drag ([availableY] < 0) returns it to rest
     * first, and only the leftover reaches the list. Never more than the
     * outstanding offset, so the panel stops exactly at rest and the same
     * gesture continues into the list from there.
     *
     * Returns 0 in every other case — crucially, a DOWNWARD drag is never taken
     * pre-emptively, so scrolling a list back up towards its top always works.
     */
    fun preScrollConsumption(availableY: Float, offsetPx: Float): Float =
        if (availableY < 0f && offsetPx > 0f) maxOf(availableY, -offsetPx) else 0f

    /**
     * How much of a scroll the PANEL takes AFTER the content had its turn.
     *
     * This is the whole list-vs-panel resolution: a downward drag is offered to
     * the scrollable content first, so mid-list it simply scrolls and nothing
     * is left over here ([availableY] is 0 and the panel does not move). Once
     * the list is at its top it can consume no more, the full delta arrives here
     * unconsumed, and the panel starts to move. The user never has to think
     * about which one they are dragging.
     */
    fun postScrollConsumption(availableY: Float): Float =
        if (availableY > 0f) availableY else 0f
}

/**
 * Whether the current composition is inside a [TranslucentShellPanel].
 *
 * Read by [AeroPage] / [AeroLazyPage], which otherwise paint an OPAQUE page
 * background and apply their own status-bar inset — both wrong inside a panel,
 * whose card already paints a translucent surface and already sits below the
 * status bar. A composition local rather than parameters because the panel wraps
 * whole ROUTES (`DrivesRoute`, `GarageRoute`, `HubScreen`) that build their Aero
 * page several layers down; threading a flag through every one of them would put
 * the same decision in a dozen places for the drift to start from.
 *
 * `static` because it changes only with the structure of the tree (a page is
 * either in a panel or it is not, for its whole lifetime), never per frame.
 */
val LocalInTranslucentPanel = staticCompositionLocalOf { false }

/**
 * A shell page rendered as a TRANSLUCENT panel over the live map, dismissed by
 * pulling its drag handle downwards.
 *
 * The shared implementation behind the History, Social and Garage tabs. All
 * three used to be opaque full-screen pages that hid the map completely; they
 * are now the same overlay idiom as the chat hub — a bottom-anchored translucent
 * card with a genuinely uncovered strip of live map above it.
 *
 * ONE component rather than three bespoke ones on purpose: the geometry, the
 * gesture, the accessibility affordances and the map-cover contract are all
 * decisions that must be identical on all three pages, and three copies is how
 * they stop being identical.
 *
 * **Not a `Popup`, `Dialog` or `ModalBottomSheet`.** Each of those renders in
 * its OWN window, and a child window receives no window-inset dispatch: the chat
 * hub measured `WindowInsets.navigationBars` as 0 inside a `Popup` while the
 * host activity window reported the real 63px at the same instant, which broke
 * every inset-derived padding in it (see `ChatHubPopup`'s KDoc). This is a plain
 * `Box` in the activity's own window, which `enableEdgeToEdge()` already puts in
 * charge of its insets.
 *
 * Dismissal is deliberately reachable FOUR ways, because a drag alone is not
 * usable for everyone:
 * - pulling the handle (or the content, once its list is at the top) downwards;
 * - tapping the uncovered strip of map above the card;
 * - the system Back button (the shell's own `BackHandler` returns to the Map
 *   tab from any other tab — this composable does not duplicate it);
 * - the accessibility `dismiss` action on the card, which TalkBack surfaces
 *   without any gesture at all.
 *
 * **Hosting contract.** This fills its parent, and the PARENT defines the
 * panel's bounds — it does not assume the whole window. What it requires is
 * only that the parent lives in the activity's own window (see the `Popup`
 * note above), and that the parent does NOT apply a top inset: the card takes
 * the status-bar inset itself, so the height fraction is measured against the
 * safe area rather than the raw window.
 *
 * The bottom is the parent's job. In the shell the host is the body container,
 * which is already inset by `navigationBarsPadding()` and the bottom bar's
 * height — so the card is bottom-anchored ABOVE the bottom bar and the bar
 * stays visible and tappable while a panel is open, which is what makes
 * switching tabs out of a panel work.
 *
 * @param onDismiss invoked once when the panel is dismissed by any route this
 *   composable owns (drag or outside tap). Back is handled by the shell.
 * @param testTag applied to the CARD, so tests can measure its bounds and prove
 *   an uncovered strip exists above it.
 * @param content the page body, composed with [LocalInTranslucentPanel] set so
 *   any [AeroPage] inside it renders transparent and skips its own status-bar
 *   inset.
 */
@Composable
fun TranslucentShellPanel(
    onDismiss: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Current vertical displacement of the card, in px, 0 = at rest. Only ever
    // >= 0: the card can be pulled DOWN and springs back up, never above rest.
    val offset = remember { Animatable(0f) }
    var cardHeightPx by remember { mutableIntStateOf(0) }
    // rememberUpdatedState so the gesture callbacks below — which are captured
    // once by draggable/nestedScroll — always call the CURRENT lambda rather
    // than the one that happened to be in scope when the gesture started.
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    // Shared by the handle drag and the nested-scroll drag so the two paths can
    // never settle differently: whichever moved the card, releasing runs this.
    val settle: (Float) -> Unit = { velocity ->
        if (PanelDrag.shouldDismiss(
                offsetPx = offset.value,
                velocityPxPerSecond = velocity,
                dismissThresholdPx = PanelDrag.dismissThresholdPx(cardHeightPx),
            )
        ) {
            // No bespoke exit animation: the shell already crossfades between
            // tabs, and dismissing IS a tab change (back to Map), so the
            // crossfade is the panel's exit. A second animation here would run
            // against it.
            currentOnDismiss()
        } else {
            scope.launch { offset.animateTo(0f) }
        }
    }

    val nestedScrollConnection =
        remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val taken = PanelDrag.preScrollConsumption(available.y, offset.value)
                    if (taken == 0f) return Offset.Zero
                    scope.launch { offset.snapTo(offset.value + taken) }
                    return Offset(0f, taken)
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    val taken = PanelDrag.postScrollConsumption(available.y)
                    if (taken == 0f) return Offset.Zero
                    scope.launch { offset.snapTo(offset.value + taken) }
                    return Offset(0f, taken)
                }

                // A fling that starts while the card is off its rest position
                // belongs to the CARD, not to the list: settle it and swallow
                // the velocity so the list does not also fling underneath.
                override suspend fun onPreFling(available: Velocity): Velocity =
                    if (offset.value > 0f) {
                        settle(available.y)
                        available
                    } else {
                        Velocity.Zero
                    }
            }
        }

    Box(modifier = modifier.fillMaxSize()) {
        // The uncovered strip's dismiss layer. Fills the window and is composed
        // BEFORE the card, so only the part of it the card does not cover — the
        // visible strip of live map — actually receives a tap. A raw
        // pointerInput plus clearAndSetSemantics keeps this invisible layer out
        // of the accessibility tree (mirrors the chat hub and the map-home
        // popups); the card's own `dismiss` action is what TalkBack uses.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { currentOnDismiss() } }
                    .clearAndSetSemantics {},
        )
        // Status-bar inset FIRST, so the height fraction below is measured
        // against the SAFE area rather than the raw window. A fraction of the
        // raw window is not guaranteed to clear system UI: on a short window
        // (landscape, split-screen, a tall cutout) the remaining fraction can be
        // smaller than the status bar and the page's title would render under
        // it. Insetting first makes the card's top
        // `statusBar + (1 - fraction) * safeHeight` — provably below system UI
        // at any window size or orientation.
        Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            val dismissLabel = stringResource(R.string.shell_panelDismiss)
            Surface(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(PANEL_CARD_HEIGHT_FRACTION)
                        .onSizeChanged { cardHeightPx = it.height }
                        // Applied AFTER the size modifiers so dragging moves the
                        // card without changing how tall it is.
                        .offset { IntOffset(x = 0, y = offset.value.roundToInt()) }
                        .nestedScroll(nestedScrollConnection)
                        .testTag(testTag)
                        // The non-gesture dismissal accessibility services use.
                        // Announced on the card itself, so it is reachable
                        // wherever focus happens to be inside the page.
                        .semantics {
                            dismiss(label = dismissLabel) {
                                currentOnDismiss()
                                true
                            }
                        },
                shape = RoundedCornerShape(topStart = KccRadius.lg, topEnd = KccRadius.lg),
                color = MaterialTheme.colorScheme.surface.copy(alpha = PANEL_CARD_ALPHA),
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    PanelDragHandle(
                        onDrag = { delta ->
                            // Clamped at 0: the card is at its resting TOP
                            // already, so an upward drag on the handle must not
                            // lift it off the bottom edge and expose a gap.
                            scope.launch {
                                offset.snapTo(maxOf(0f, offset.value + delta))
                            }
                        },
                        onDragStopped = settle,
                    )
                    CompositionLocalProvider(LocalInTranslucentPanel provides true) {
                        content()
                    }
                }
            }
        }
    }
}

/**
 * The grab affordance at the top of a panel: the standard sheet drag-handle pill
 * on a touch target tall enough to actually hit.
 *
 * Dragged directly (rather than through the nested-scroll path) because it sits
 * OUTSIDE the page's scroll container — a handle that only worked when the list
 * beneath it happened to be at the top would be the opposite of an affordance.
 * Both paths call the same `onDragStopped`, so they settle identically.
 *
 * Labelled for accessibility rather than `contentDescription = null`: it is the
 * only visible sign that the page can be pulled away, so a screen reader must be
 * able to say so. The actual non-gesture dismissal is the card's `dismiss`
 * action (plus system Back and the outside tap).
 */
@Composable
private fun PanelDragHandle(
    onDrag: (Float) -> Unit,
    onDragStopped: (Float) -> Unit,
) {
    val handleDescription = stringResource(R.string.shell_panelDragHandle)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(PanelHandleTouchHeight)
                .draggable(
                    state = rememberDraggableState(onDelta = onDrag),
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity -> onDragStopped(velocity) },
                )
                .semantics { contentDescription = handleDescription }
                .testTag(PANEL_DRAG_HANDLE_TEST_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = PanelHandleWidth, height = PanelHandleHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}

/** Test tag on a panel's drag handle. */
const val PANEL_DRAG_HANDLE_TEST_TAG = "panelDragHandle"

/** Test tag on the History tab's panel card. */
const val HISTORY_PANEL_TEST_TAG = "historyPanel"

/** Test tag on the Social tab's panel card. */
const val SOCIAL_PANEL_TEST_TAG = "socialPanel"

/** Test tag on the Garage tab's panel card. */
const val GARAGE_PANEL_TEST_TAG = "garagePanel"
