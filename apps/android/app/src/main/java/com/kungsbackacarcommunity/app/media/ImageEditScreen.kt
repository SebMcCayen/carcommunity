package com.kungsbackacarcommunity.app.media

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.shell.AeroPage
import com.kungsbackacarcommunity.app.shell.LocalAeroBackAvailable
import kotlin.math.max
import kotlin.math.roundToInt

/** Test tag for the gesture surface (the editor viewport). */
const val ImageEditViewportTag: String = "imageEditViewport"

/** Test tag for the confirm ("Done") button. */
const val ImageEditConfirmTag: String = "imageEditConfirm"

/**
 * The crop-frame shape the shared editor draws, and how it may be reshaped:
 *  - [CIRCLE]: a fixed SQUARE frame masked as a circle (the avatar default — the
 *    profile renders avatars round, so a square cut fills that circle edge to
 *    edge). Not resizable; the aspect is locked to 1:1.
 *  - [FREEFORM]: a rectangular frame whose edges the user can drag (via the four
 *    corner handles) to reshape it — the car/other-photo default.
 */
enum class ImageEditFrameShape {
    CIRCLE,
    FREEFORM,
}

/**
 * The ONE shared gesture-driven crop/rotate editor every image upload routes
 * through (avatar, vehicle photos, and any future image surface). See
 * [ImageEditGeometry] for the maths; this file is the Compose shell over it.
 *
 * A single continuous gesture drives everything at once — drag to PAN, pinch to
 * ZOOM, and twist two fingers to ROTATE by any free angle — via
 * [detectTransformGestures] with `panZoomLock = false`. There are deliberately NO
 * rotate buttons: the image moves under a fixed, axis-aligned crop frame exactly
 * like the Photos / Instagram crop editor.
 *
 * The crux that prevents "weird sizing": as the image is twisted, the minimum
 * zoom needed to keep the frame fully covered by the rotated image rises
 * ([ImageEditGeometry.coverScaleForFrame]); the scale is clamped to at least that
 * every frame, so a rotated image can NEVER leave an empty triangular corner
 * inside the crop frame, and the pan is clamped so the frame stays covered.
 *
 * On confirm the display transform is inverted into an `(angle, crop)` pair — the
 * arbitrary rotation in degrees plus the axis-aligned [NormalizedCropRect] in the
 * rotated source — and handed to [onConfirm]. The caller passes BOTH to
 * [ImageCompressor.compressForPublicUpload], which rotates the oriented source by
 * `angle`, cuts the rect out, downscales and (crucially) strips EXIF/GPS. This
 * screen NEVER produces image bytes — only a window + an angle — so cropping and
 * rotation cannot become a route around sanitisation (same contract as the old
 * [com.kungsbackacarcommunity.app.garage.VehiclePhotoCropScreen] it replaces).
 *
 * @param bitmap the EXIF-oriented preview decode from
 *   [ImageCompressor.decodeForCrop]. Display only; the uploaded pixels are
 *   re-derived from the original pick.
 * @param frameShape [ImageEditFrameShape.CIRCLE] for avatars (square + circle
 *   mask), [ImageEditFrameShape.FREEFORM] for car/other photos.
 * @param initialAspect the frame's starting width/height. Forced to 1f for
 *   [ImageEditFrameShape.CIRCLE].
 * @param onConfirm receives the free rotation (degrees) and the crop window in
 *   normalized ROTATED-source coordinates.
 * @param onCancel backs out; nothing has been written or uploaded.
 */
@Composable
fun ImageEditScreen(
    bitmap: Bitmap,
    frameShape: ImageEditFrameShape,
    initialAspect: Float,
    onConfirm: (rotationDegrees: Float, crop: NormalizedCropRect) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val hint = stringResource(R.string.imageEditor_hint)
    val frameDescription = stringResource(R.string.imageEditor_frameDescription)

    // Image (preview) half-extents in preview pixels. The scale below is measured
    // in screen px per preview px, so scale * these = the image's on-screen
    // half-extents, the unit ImageEditGeometry works in.
    val imgHalfWp = bitmap.width / 2f
    val imgHalfHp = bitmap.height / 2f

    // Gesture state. Plain float/state (NOT Saveable): the preview bitmap cannot
    // survive process death, so a restored transform for a photo that is gone
    // would be meaningless. Keyed on [bitmap] so a new pick starts fresh.
    var angle by remember(bitmap) { mutableFloatStateOf(0f) }
    var scale by remember(bitmap) { mutableFloatStateOf(0f) }
    var panX by remember(bitmap) { mutableFloatStateOf(0f) }
    var panY by remember(bitmap) { mutableFloatStateOf(0f) }
    var boxSize by remember(bitmap) { mutableStateOf(Size.Zero) }
    // The crop frame rectangle in viewport pixels. Null until the viewport is
    // measured; recomputed to the default framing on measure (and on reset).
    var frameRect by remember(bitmap) { mutableStateOf<Rect?>(null) }
    var initialized by remember(bitmap) { mutableStateOf(false) }

    val aspect = if (frameShape == ImageEditFrameShape.CIRCLE) 1f else initialAspect

    // Re-clamps the scale (to the rotation-aware cover floor) and the pan (so the
    // frame stays fully inside the rotated image) against the CURRENT frame and
    // angle. Called after every gesture and after any frame-edge drag.
    val clampToFrame: () -> Unit = clamp@{
        val frame = frameRect ?: return@clamp
        if (frame.width <= 0f || frame.height <= 0f || imgHalfWp <= 0f || imgHalfHp <= 0f) {
            return@clamp
        }
        val fHalfW = frame.width / 2f
        val fHalfH = frame.height / 2f
        val minScale =
            ImageEditGeometry.coverScaleForFrame(angle, fHalfW, fHalfH, imgHalfWp, imgHalfHp)
        val maxScale =
            max(
                minScale,
                ImageEditGeometry.coverScaleForFrame(0f, fHalfW, fHalfH, imgHalfWp, imgHalfHp) *
                    ImageEditGeometry.MAX_ZOOM,
            )
        scale = scale.coerceIn(minScale, maxScale)

        // Frame centre relative to the image centre (= frame centre relative to
        // viewport centre, minus the pan that moved the image).
        val frameCx = frame.center.x - boxSize.width / 2f
        val frameCy = frame.center.y - boxSize.height / 2f
        val (cdx, cdy) =
            ImageEditGeometry.clampImageOffset(
                angleDeg = angle,
                dX = frameCx - panX,
                dY = frameCy - panY,
                frameHalfW = fHalfW,
                frameHalfH = fHalfH,
                imageScaledHalfW = scale * imgHalfWp,
                imageScaledHalfH = scale * imgHalfHp,
            )
        panX = frameCx - cdx
        panY = frameCy - cdy
    }

    // Opt OUT of the pinned in-app Back arrow: the editor renders under RouteHost
    // (avatar / garage crop), where LocalAeroBackAvailable is true, but it already
    // owns a bottom Cancel that backs out of the EDIT (not the whole route). A top
    // arrow would be a confusing duplicate, so re-provide false around this page.
    CompositionLocalProvider(LocalAeroBackAvailable provides false) {
        AeroPage(
            title = stringResource(R.string.imageEditor_title),
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(KccRadius.md))
                    .background(Color.Black)
                    .testTag(ImageEditViewportTag)
                    .semantics { contentDescription = hint }
                    .onSizeChanged { size ->
                        boxSize = Size(size.width.toFloat(), size.height.toFloat())
                        if (frameRect == null) {
                            frameRect = defaultFrame(boxSize, frameShape, aspect)
                        }
                        if (!initialized) {
                            val frame = frameRect
                            if (frame != null) {
                                scale =
                                    ImageEditGeometry.coverScaleForFrame(
                                        angleDeg = 0f,
                                        frameHalfW = frame.width / 2f,
                                        frameHalfH = frame.height / 2f,
                                        imageHalfW = imgHalfWp,
                                        imageHalfH = imgHalfHp,
                                    )
                                panX = frame.center.x - boxSize.width / 2f
                                panY = frame.center.y - boxSize.height / 2f
                                initialized = true
                            }
                        } else {
                            clampToFrame()
                        }
                    }
                    .pointerInput(bitmap) {
                        // `detectTransformGestures` reports `rotation` in DEGREES
                        // (its KDoc: "the rotation angle in degrees"; it derives
                        // from PointerEvent.calculateRotation, also degrees) — the
                        // SAME unit graphicsLayer.rotationZ takes and the unit the
                        // ImageEditGeometry / ImageCompressor `angle`/`rotationDegrees`
                        // pipeline expects. Accumulate it directly; do NOT convert
                        // to/from radians (that would break rotation entirely).
                        detectTransformGestures(panZoomLock = false) { _, pan, gestureZoom, rotation ->
                            angle += rotation
                            scale *= gestureZoom
                            panX += pan.x
                            panY += pan.y
                            clampToFrame()
                        }
                    },
        ) {
            if (scale > 0f) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .size(
                                width = with(density) { bitmap.width.toDp() },
                                height = with(density) { bitmap.height.toDp() },
                            )
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                rotationZ = angle,
                                translationX = panX,
                                translationY = panY,
                            ),
                )
            }

            // Dim everything outside the crop frame, and outline it. The circle
            // variant clears an oval so the avatar's round mask is literal.
            val frame = frameRect
            if (frame != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val path =
                        Path().apply {
                            if (frameShape == ImageEditFrameShape.CIRCLE) {
                                addOval(frame)
                            } else {
                                addRect(frame)
                            }
                        }
                    clipPath(path, clipOp = ClipOp.Difference) {
                        drawRect(color = Color.Black.copy(alpha = 0.6f))
                    }
                    drawPath(
                        path = path,
                        color = Color.White,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }

                // Free-form frame: four draggable corner handles. Dragging a corner
                // reshapes the crop rectangle; the transform is re-clamped so the
                // (possibly new-aspect) frame stays covered with no empty corner.
                if (frameShape == ImageEditFrameShape.FREEFORM && boxSize != Size.Zero) {
                    // dp → px for this density: the touch box is centred on its
                    // corner (offset by half its size), the dot is drawn at the
                    // dp radius, and a corner drag can shrink the frame no smaller
                    // than the dp minimum. All scale correctly on any density.
                    val touchHalfPx = with(density) { HandleTouchSize.toPx() } / 2f
                    val handleRadiusPx = with(density) { HandleVisualRadius.toPx() }
                    val minFramePx = with(density) { MinFrameSize.toPx() }
                    listOf(
                        FrameCorner.TOP_LEFT,
                        FrameCorner.TOP_RIGHT,
                        FrameCorner.BOTTOM_LEFT,
                        FrameCorner.BOTTOM_RIGHT,
                    ).forEach { corner ->
                        val handle = corner.position(frame)
                        Box(
                            modifier =
                                Modifier
                                    .offset {
                                        IntOffset(
                                            (handle.x - touchHalfPx).roundToInt(),
                                            (handle.y - touchHalfPx).roundToInt(),
                                        )
                                    }
                                    .size(HandleTouchSize)
                                    .semantics { contentDescription = frameDescription }
                                    .pointerInput(corner, bitmap) {
                                        detectDragGestures { change, drag ->
                                            change.consume()
                                            val current = frameRect ?: return@detectDragGestures
                                            frameRect =
                                                corner.resize(current, drag, boxSize, minFramePx)
                                            clampToFrame()
                                        }
                                    },
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawCircle(
                                    color = Color.White,
                                    radius = handleRadiusPx,
                                    center = Offset(size.width / 2f, size.height / 2f),
                                )
                            }
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                val frame = frameRect
                val crop =
                    if (frame == null || scale <= 0f) {
                        NormalizedCropRect.FULL
                    } else {
                        val frameCx = frame.center.x - boxSize.width / 2f
                        val frameCy = frame.center.y - boxSize.height / 2f
                        ImageEditGeometry.resolveCrop(
                            angleDeg = angle,
                            dX = frameCx - panX,
                            dY = frameCy - panY,
                            frameHalfW = frame.width / 2f,
                            frameHalfH = frame.height / 2f,
                            imageScaledHalfW = scale * imgHalfWp,
                            imageScaledHalfH = scale * imgHalfHp,
                        )
                    }
                onConfirm(angle, crop)
            },
            modifier = Modifier.fillMaxWidth().testTag(ImageEditConfirmTag),
        ) {
            Text(text = stringResource(R.string.imageEditor_confirm))
        }
        TextButton(
            onClick = {
                angle = 0f
                if (boxSize == Size.Zero) {
                    // Reset tapped BEFORE the viewport is measured. Do NOT install a
                    // zero-size default frame here: onSizeChanged only re-initializes
                    // the frame when frameRect == null, so a zero-size frame would
                    // wedge the editor at scale == 0 with a non-functional frame until
                    // the user reset again. Instead clear back to the fresh-mount state
                    // (null frame, un-initialized, zero transform) so the pending
                    // onSizeChanged installs the default frame AND the cover scale once
                    // the viewport is measured.
                    frameRect = null
                    initialized = false
                    scale = 0f
                    panX = 0f
                    panY = 0f
                } else {
                    val frame = defaultFrame(boxSize, frameShape, aspect)
                    frameRect = frame
                    scale =
                        ImageEditGeometry.coverScaleForFrame(
                            angleDeg = 0f,
                            frameHalfW = frame.width / 2f,
                            frameHalfH = frame.height / 2f,
                            imageHalfW = imgHalfWp,
                            imageHalfH = imgHalfHp,
                        )
                    panX = frame.center.x - boxSize.width / 2f
                    panY = frame.center.y - boxSize.height / 2f
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.imageEditor_reset))
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.imageEditor_cancel))
        }
    }
    }
}

/**
 * Corner-handle touch target. Density-INDEPENDENT (dp): a px value would shrink
 * the physical touch area on high-density screens. 48dp meets the Material
 * minimum touch-target size so the handle stays comfortably grabbable everywhere.
 */
private val HandleTouchSize = 48.dp

/** Drawn radius of the white handle dot, in dp (converted to px at draw time). */
private val HandleVisualRadius = 8.dp

/** Minimum crop-frame side, in dp, so a corner drag cannot collapse the frame. */
private val MinFrameSize = 96.dp

/**
 * The default crop frame for a freshly measured viewport: a centred rectangle of
 * [aspect] (1:1 for [ImageEditFrameShape.CIRCLE]) filling ~82% of the viewport.
 */
private fun defaultFrame(box: Size, shape: ImageEditFrameShape, aspect: Float): Rect {
    if (box.width <= 0f || box.height <= 0f) return Rect(Offset.Zero, Size.Zero)
    val ratio = if (shape == ImageEditFrameShape.CIRCLE || aspect <= 0f || !aspect.isFinite()) 1f else aspect
    val maxW = box.width * 0.82f
    val maxH = box.height * 0.82f
    // Fit the aspect box inside the allowance.
    var w = maxW
    var h = w / ratio
    if (h > maxH) {
        h = maxH
        w = h * ratio
    }
    val left = (box.width - w) / 2f
    val top = (box.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/** The four draggable corners of a free-form frame. */
private enum class FrameCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    ;

    /** This corner's viewport position for the given [frame]. */
    fun position(frame: Rect): Offset =
        when (this) {
            TOP_LEFT -> Offset(frame.left, frame.top)
            TOP_RIGHT -> Offset(frame.right, frame.top)
            BOTTOM_LEFT -> Offset(frame.left, frame.bottom)
            BOTTOM_RIGHT -> Offset(frame.right, frame.bottom)
        }

    /**
     * Moves this corner by [drag], keeping the frame inside [box] and no smaller
     * than [minFramePx] (the density-converted [MinFrameSize]) on either side. The
     * opposite corner stays put, so the frame's aspect is free — exactly the
     * "draggable edges" behaviour.
     */
    fun resize(frame: Rect, drag: Offset, box: Size, minFramePx: Float): Rect {
        var left = frame.left
        var top = frame.top
        var right = frame.right
        var bottom = frame.bottom
        when (this) {
            TOP_LEFT -> {
                left = (frame.left + drag.x).coerceIn(0f, frame.right - minFramePx)
                top = (frame.top + drag.y).coerceIn(0f, frame.bottom - minFramePx)
            }
            TOP_RIGHT -> {
                right = (frame.right + drag.x).coerceIn(frame.left + minFramePx, box.width)
                top = (frame.top + drag.y).coerceIn(0f, frame.bottom - minFramePx)
            }
            BOTTOM_LEFT -> {
                left = (frame.left + drag.x).coerceIn(0f, frame.right - minFramePx)
                bottom = (frame.bottom + drag.y).coerceIn(frame.top + minFramePx, box.height)
            }
            BOTTOM_RIGHT -> {
                right = (frame.right + drag.x).coerceIn(frame.left + minFramePx, box.width)
                bottom = (frame.bottom + drag.y).coerceIn(frame.top + minFramePx, box.height)
            }
        }
        return Rect(left, top, right, bottom)
    }
}

