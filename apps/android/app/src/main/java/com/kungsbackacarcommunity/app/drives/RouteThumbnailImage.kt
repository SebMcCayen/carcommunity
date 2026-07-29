package com.kungsbackacarcommunity.app.drives

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.LocalKccDarkTheme

/** Thumbnail box on a History card — small enough that a row stays a row. */
private val THUMBNAIL_WIDTH = 72.dp
private val THUMBNAIL_HEIGHT = 56.dp

/** Inset so the route never touches (or gets clipped by) the rounded corners. */
private val THUMBNAIL_PADDING = 6.dp

private val ROUTE_STROKE_WIDTH = 2.dp

/** The placeholder glyph, kept well inside the box so it reads as an icon. */
private val PLACEHOLDER_ICON_SIZE = 20.dp

/**
 * The app's route blue — the same `0xFF1A73E8` the History replay map and the
 * shell's route overlay draw with, so a drive's shape is the same colour
 * wherever it appears.
 *
 * The dark-theme variant is Google's paired light blue rather than the same
 * value: on the dark card surface `0xFF1A73E8` sits at ~2.3:1, under the 3:1
 * that non-text graphics need to be made out at all, while `0xFF8AB4F8` is
 * ~5:1. Same hue, still unmistakably "the route colour", actually visible.
 */
private val ROUTE_COLOR_LIGHT = Color(0xFF1A73E8)
private val ROUTE_COLOR_DARK = Color(0xFF8AB4F8)

/**
 * A drive's route drawn from its stored `routeThumbnail` polyline, or a tidy
 * placeholder when there is nothing to draw.
 *
 * Costs one decode + one projection per distinct polyline, both inside
 * `remember`: nothing here fetches, nothing instantiates a map, and the draw
 * scope only strokes an already-built `Path`. That matters because this is a
 * row in a scrolling list.
 *
 * The placeholder is the PERMANENT path for every drive saved before thumbnails
 * existed (there is no backfill), and the path for stationary or one-fix
 * recordings, so it is a first-class state rather than a loading state: a
 * muted, rounded box with a route glyph, never an empty hole in the card.
 */
@Composable
fun RouteThumbnailImage(
    encodedPolyline: String?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val widthPx = with(density) { THUMBNAIL_WIDTH.toPx() }
    val heightPx = with(density) { THUMBNAIL_HEIGHT.toPx() }
    val paddingPx = with(density) { THUMBNAIL_PADDING.toPx() }
    val strokePx = with(density) { ROUTE_STROKE_WIDTH.toPx() }

    // Keyed on the polyline (and the box, which changes only with display
    // density/font scale): scrolling re-uses the built path instead of
    // re-decoding ~64 points and re-fitting them on every recomposition.
    val path: Path? =
        remember(encodedPolyline, widthPx, heightPx, paddingPx) {
            RouteThumbnail.pathFor(encodedPolyline, widthPx, heightPx, paddingPx)?.let { points ->
                Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (i in 1 until points.size) {
                        lineTo(points[i].x, points[i].y)
                    }
                }
            }
        }

    val routeColor = if (LocalKccDarkTheme.current) ROUTE_COLOR_DARK else ROUTE_COLOR_LIGHT
    val label =
        stringResource(
            if (path == null) {
                R.string.savedDrives_routeThumbnailUnavailable
            } else {
                R.string.savedDrives_routeThumbnailLabel
            },
        )

    Box(
        modifier =
            modifier
                .width(THUMBNAIL_WIDTH)
                .height(THUMBNAIL_HEIGHT)
                .clip(RoundedCornerShape(KccRadius.sm))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (path == null) {
            Icon(
                imageVector = Icons.Filled.Timeline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(PLACEHOLDER_ICON_SIZE),
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawPath(
                    path = path,
                    color = routeColor,
                    // Round cap/join so a hairpin turn does not spike, and a
                    // two-point route still reads as a line rather than a
                    // sliver.
                    style =
                        Stroke(
                            width = strokePx,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                )
            }
        }
    }
}
