package com.kungsbackacarcommunity.app.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlin.math.roundToInt

/** Test tag on the dropped-pin overlay's marker. */
const val DROPPED_PIN_TAG = "dropped_pin"

/**
 * A single animated pin that pops up on the map at [target], the visual anchor
 * for the long-press place menu: it tells the user exactly WHICH point the menu
 * (and its "navigate / copy / save" actions) refers to.
 *
 * Renders nothing when [target] is null (menu dismissed / action taken) or on a
 * surface with no camera (the stub — CI and the token-less build). A [MapProjection]
 * Compose overlay rather than a Mapbox annotation, for the same reasons the
 * live-marker overlays are ([NearbyLiveOverlay]): it stays out of the
 * map-surface file and reaches the map only through the projection seam, and a
 * Compose drop/bounce is trivial here. The pin's TIP sits on the projected point;
 * on appearance it falls in from above with a bouncy spring and fades in.
 */
@Composable
fun DroppedPinOverlay(
    mapSurface: MapProjection,
    target: LatLng?,
    modifier: Modifier = Modifier,
) {
    val camera by mapSurface.cameraSnapshot.collectAsState()
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = it },
    ) {
        val point = target ?: return@Box
        val snapshot = camera ?: return@Box
        if (viewportSize.width <= 0 || viewportSize.height <= 0) return@Box

        // Keyed on the settled camera + target: the projection reaches into the
        // live map, so it recomputes when the camera settles somewhere new (a pan
        // keeps the pin glued to its coordinate) or the target changes.
        val screen =
            remember(snapshot, point, viewportSize) {
                mapSurface.screenPositionFor(point.latitude, point.longitude)
            } ?: return@Box

        // The drop animation is keyed on the TARGET only (not the camera), so
        // panning the map does not re-trigger it — only a brand-new long-press does.
        val drop = remember(point) { Animatable(1f) }
        LaunchedEffect(point) {
            drop.snapTo(1f)
            drop.animateTo(
                targetValue = 0f,
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
            )
        }

        val density = LocalDensity.current
        val pinSizePx = with(density) { PIN_SIZE.toPx() }
        val liftPx = with(density) { (drop.value * DROP_HEIGHT.toPx()) }

        // The pin glyph's tip is at the BOTTOM-centre of its box, so anchor the
        // box so that bottom-centre lands on the projected point; the lift raises
        // it above the rest point during the drop-in.
        Icon(
            imageVector = Icons.Filled.Place,
            contentDescription = stringResource(R.string.shell_selectedLocationPin),
            tint = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .offset {
                        IntOffset(
                            x = (screen.x - pinSizePx / 2f).roundToInt(),
                            y = (screen.y - pinSizePx - liftPx).roundToInt(),
                        )
                    }
                    .size(PIN_SIZE)
                    .graphicsLayer { alpha = 1f - drop.value }
                    .testTag(DROPPED_PIN_TAG),
        )
    }
}

// The pin glyph footprint.
private val PIN_SIZE = 44.dp

// How far above its rest point the pin starts before dropping in.
private val DROP_HEIGHT = 40.dp
