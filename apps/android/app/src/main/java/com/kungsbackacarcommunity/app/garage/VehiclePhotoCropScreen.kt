package com.kungsbackacarcommunity.app.garage

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import com.kungsbackacarcommunity.app.media.ImageCrop
import com.kungsbackacarcommunity.app.media.NormalizedCropRect
import com.kungsbackacarcommunity.app.shell.AeroPage
import kotlin.math.roundToInt

/** Test tag for the pan/zoom surface — the crop box itself. */
const val VehiclePhotoCropBoxTag: String = "vehiclePhotoCropBox"

/**
 * Crop/resize step between picking a vehicle photo and uploading it.
 *
 * The user drags and pinches the photo beneath a FIXED 16:9 window
 * ([ImageCrop.VEHICLE_ASPECT_RATIO]) — the exact ratio the garage card and the
 * public member-profile card render vehicle photos at, so what is inside the
 * window is what everyone will see. Free-form cropping was rejected for that
 * reason: an odd ratio would only be re-cropped (or letterboxed) at render time,
 * making the user's framing a lie.
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
    // Gesture state, in the crop box's pixel space. Float state (not Saveable):
    // the preview bitmap itself cannot survive process death, so restoring a
    // zoom for a photo that is gone would be meaningless.
    var zoom by remember(bitmap) { mutableFloatStateOf(ImageCrop.MIN_ZOOM) }
    var offsetX by remember(bitmap) { mutableFloatStateOf(0f) }
    var offsetY by remember(bitmap) { mutableFloatStateOf(0f) }
    var boxSize by remember(bitmap) { mutableStateOf(Size.Zero) }

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

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(ImageCrop.VEHICLE_ASPECT_RATIO)
                    .clip(RoundedCornerShape(KccRadius.md))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .testTag(VehiclePhotoCropBoxTag)
                    // The gesture surface is one opaque control to a screen
                    // reader; the hint above describes it, so mirror that.
                    .semantics { contentDescription = cropHint }
                    .onSizeChanged { size ->
                        boxSize = Size(size.width.toFloat(), size.height.toFloat())
                        // Re-clamp against the new box so a rotation or window
                        // resize cannot strand the image off-centre with a gap.
                        val scale = effectiveScale(bitmap, boxSize, zoom)
                        offsetX = ImageCrop.clampOffset(bitmap.width * scale, boxSize.width, offsetX)
                        offsetY = ImageCrop.clampOffset(bitmap.height * scale, boxSize.height, offsetY)
                    }
                    .pointerInput(bitmap) {
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
                    // scaled size, so any further content scaling would fight it.
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
