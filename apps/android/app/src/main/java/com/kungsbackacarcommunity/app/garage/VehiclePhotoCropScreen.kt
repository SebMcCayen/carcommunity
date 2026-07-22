package com.kungsbackacarcommunity.app.garage

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.media.CropAspect
import com.kungsbackacarcommunity.app.media.ImageCrop
import com.kungsbackacarcommunity.app.media.NormalizedCropRect
import com.kungsbackacarcommunity.app.shell.AeroPage
import kotlin.math.roundToInt

/** Test tag for the pan/zoom surface — the crop box itself. */
const val VehiclePhotoCropBoxTag: String = "vehiclePhotoCropBox"

/** Test tag for the aspect-ratio option chip of the given [aspect]. */
fun vehiclePhotoCropRatioTag(aspect: CropAspect): String =
    "vehiclePhotoCropRatio_${aspect.name}"

/**
 * Crop/resize step between picking a vehicle photo and uploading it.
 *
 * The user first chooses an output SHAPE ([CropAspect] — Original, Square, 4:3
 * or 16:9; default Square, which pairs with the round garage display) and then
 * drags/pinches the photo beneath a crop window drawn at that ratio. The crop is
 * always a faithful, un-stretched cut: the image is laid out at a single uniform
 * scale ([ImageCrop.coverScale] x zoom), so what is inside the window is exactly
 * those source pixels at their true proportions — never squashed or stretched to
 * fit. Switching shape re-frames the window; it never distorts the photo.
 *
 * Resizing is not a separate control. The photo is always downscaled to
 * [com.kungsbackacarcommunity.app.media.ImageCompressor.VEHICLE_MAX_DIMENSION]
 * and JPEG re-encoded during sanitisation, which is what keeps it under the
 * 10 MB Storage rule; zooming in crops tighter and therefore also shrinks the
 * uploaded pixel area.
 *
 * **This screen produces a [NormalizedCropRect] and nothing else.** It never
 * encodes, writes or uploads an image — [onConfirm] cannot hand over bytes
 * because its parameter type has none. The rect is passed to
 * [com.kungsbackacarcommunity.app.media.ImageCompressor.compressForPublicUpload],
 * which does the actual cutting alongside its EXIF/GPS stripping, so cropping
 * cannot become a route around sanitisation. Do not "optimise" this by having
 * the screen return the cropped bitmap — that is the bypass.
 *
 * @param bitmap the EXIF-oriented preview decode from
 *   [com.kungsbackacarcommunity.app.media.ImageCompressor.decodeForCrop]. Display
 *   only; the uploaded pixels are re-derived from the original pick.
 * @param onConfirm receives the chosen window in normalized source coordinates.
 * @param onCancel backs out. Nothing has been uploaded or written at this point,
 *   so there is nothing to undo — the caller simply drops the pending pick.
 */
@Composable
fun VehiclePhotoCropScreen(
    bitmap: Bitmap,
    onConfirm: (NormalizedCropRect) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The chosen output shape. The crop box is drawn at this ratio and the
    // gesture state resets when it changes (see the remember keys below), so
    // switching shape re-frames a fresh, un-panned window rather than carrying a
    // stale offset from the previous ratio.
    var aspect by remember(bitmap) { mutableStateOf(CropAspect.DEFAULT) }
    val boxAspectRatio = aspect.ratio(bitmap.width.toFloat(), bitmap.height.toFloat())

    // Gesture state, in the crop box's pixel space. Float state (not Saveable):
    // the preview bitmap itself cannot survive process death, so restoring a
    // zoom for a photo that is gone would be meaningless. Keyed on [aspect] too
    // so a shape change starts from the fully zoomed-out window.
    var zoom by remember(bitmap, aspect) { mutableFloatStateOf(ImageCrop.MIN_ZOOM) }
    var offsetX by remember(bitmap, aspect) { mutableFloatStateOf(0f) }
    var offsetY by remember(bitmap, aspect) { mutableFloatStateOf(0f) }
    var boxSize by remember(bitmap) { mutableStateOf(Size.Zero) }
    // False until the fully-zoomed-out image has been centred for the current
    // shape. Reset with the gesture state on a shape change so each ratio starts
    // framed on the CENTRE of the photo (not the top-left corner); once the user
    // has pinched/panned we only clamp, never re-centre out from under them.
    var centered by remember(bitmap, aspect) { mutableStateOf(false) }

    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val cropHint = stringResource(R.string.garage_photoCropHint)

    AeroPage(
        title = stringResource(R.string.garage_photoCropTitle),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
    ) {
        Text(
            text = cropHint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Shape selector: horizontally scrollable so it never clips on a narrow
        // screen. Choosing an option re-frames the crop window to that ratio.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            CropAspect.entries.forEach { option ->
                FilterChip(
                    selected = option == aspect,
                    onClick = { aspect = option },
                    label = { Text(text = stringResource(option.labelRes())) },
                    modifier = Modifier.testTag(vehiclePhotoCropRatioTag(option)),
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(boxAspectRatio)
                    .clip(RoundedCornerShape(KccRadius.md))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .testTag(VehiclePhotoCropBoxTag)
                    // The gesture surface is one opaque control to a screen
                    // reader; the hint above describes it, so mirror that.
                    .semantics { contentDescription = cropHint }
                    .onSizeChanged { size ->
                        boxSize = Size(size.width.toFloat(), size.height.toFloat())
                        val scale = effectiveScale(bitmap, boxSize, zoom)
                        val scaledW = bitmap.width * scale
                        val scaledH = bitmap.height * scale
                        if (!centered && zoom == ImageCrop.MIN_ZOOM) {
                            // First measure for this shape at full zoom-out: seed
                            // the framing on the CENTRE of the photo so the default
                            // confirmed window isn't a top-left corner-crop.
                            offsetX = ImageCrop.centeredOffset(scaledW, boxSize.width)
                            offsetY = ImageCrop.centeredOffset(scaledH, boxSize.height)
                            centered = true
                        } else {
                            // Re-clamp against the new box so a rotation or window
                            // resize cannot strand the image off-centre with a gap.
                            offsetX = ImageCrop.clampOffset(scaledW, boxSize.width, offsetX)
                            offsetY = ImageCrop.clampOffset(scaledH, boxSize.height, offsetY)
                        }
                    }
                    .pointerInput(bitmap, aspect) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            val previousScale = effectiveScale(bitmap, boxSize, zoom)
                            val newZoom =
                                (zoom * gestureZoom).coerceIn(ImageCrop.MIN_ZOOM, ImageCrop.MAX_ZOOM)
                            val newScale = effectiveScale(bitmap, boxSize, newZoom)
                            // Zoom about the box's CENTRE: keep whatever is in the
                            // middle of the window in the middle of the window,
                            // otherwise pinching drifts the subject out of frame.
                            val centreX = boxSize.width / 2f
                            val centreY = boxSize.height / 2f
                            val ratio = if (previousScale > 0f) newScale / previousScale else 1f
                            val zoomedX = centreX - (centreX - offsetX) * ratio
                            val zoomedY = centreY - (centreY - offsetY) * ratio

                            zoom = newZoom
                            offsetX =
                                ImageCrop.clampOffset(
                                    bitmap.width * newScale,
                                    boxSize.width,
                                    zoomedX + pan.x,
                                )
                            offsetY =
                                ImageCrop.clampOffset(
                                    bitmap.height * newScale,
                                    boxSize.height,
                                    zoomedY + pan.y,
                                )
                        }
                    },
        ) {
            val scale = effectiveScale(bitmap, boxSize, zoom)
            val density = LocalDensity.current
            // Only draw once the box has been measured; at scale 0 the image
            // would have no size anyway.
            if (scale > 0f) {
                Image(
                    bitmap = imageBitmap,
                    // Decorative: the hint on the gesture surface already names it,
                    // and a second announcement for the same pixels only adds noise.
                    contentDescription = null,
                    // FillBounds, not Crop/Fit: the size below IS the intended
                    // scaled size (width and height share ONE scale, so the aspect
                    // ratio is preserved), so any further content scaling would
                    // fight it.
                    contentScale = ContentScale.FillBounds,
                    modifier =
                        Modifier
                            .offset {
                                IntOffset(offsetX.roundToInt(), offsetY.roundToInt())
                            }
                            .size(
                                width = with(density) { (bitmap.width * scale).toDp() },
                                height = with(density) { (bitmap.height * scale).toDp() },
                            ),
                )
            }
        }

        Button(
            onClick = {
                onConfirm(
                    ImageCrop.visibleRect(
                        imageWidth = bitmap.width.toFloat(),
                        imageHeight = bitmap.height.toFloat(),
                        boxWidth = boxSize.width,
                        boxHeight = boxSize.height,
                        zoom = zoom,
                        offsetX = offsetX,
                        offsetY = offsetY,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.garage_photoCropConfirm))
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.garage_cancelButton))
        }
    }
}

/** The localized chip label for a crop shape option. */
@androidx.annotation.StringRes
private fun CropAspect.labelRes(): Int =
    when (this) {
        CropAspect.ORIGINAL -> R.string.garage_photoCropRatioOriginal
        CropAspect.SQUARE -> R.string.garage_photoCropRatioSquare
        CropAspect.RATIO_4_3 -> R.string.garage_photoCropRatio43
        CropAspect.RATIO_16_9 -> R.string.garage_photoCropRatio169
    }

/**
 * The scale at which [bitmap] is drawn: the cover scale for [boxSize] times the
 * user's [zoom]. 0f before the box has been measured, which the callers treat as
 * "no transform yet".
 */
private fun effectiveScale(bitmap: Bitmap, boxSize: Size, zoom: Float): Float =
    ImageCrop.coverScale(
        bitmap.width.toFloat(),
        bitmap.height.toFloat(),
        boxSize.width,
        boxSize.height,
    ) * zoom.coerceIn(ImageCrop.MIN_ZOOM, ImageCrop.MAX_ZOOM)
