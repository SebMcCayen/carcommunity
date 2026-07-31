package com.kungsbackacarcommunity.app.garage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.shell.AeroPage
import kotlinx.coroutines.launch

/**
 * Full car-detail page (car-detail-gallery slice): opened by tapping a car in
 * the garage list. Shows every field the vehicle model carries plus a swipeable
 * photo GALLERY, and hosts the same manage actions (edit / delete / set-main)
 * as the list card so the detail page is self-sufficient.
 *
 * Rendered inside [AeroPage] like every other sub-route (shared status-bar
 * inset, gutters and title treatment); system Back returns to the list, handled
 * centrally in [GarageRoute].
 *
 * PHOTO GALLERY: the gallery pages over [VehicleGallery.photoPaths] (cover
 * first). Adding a photo goes through the same pick -> crop -> compress ->
 * EXIF/GPS-strip pipeline as the Edit form ([onAddPhoto]); each photo can be
 * made the cover ([onSetCover]) or removed ([onRemovePhoto], confirmed). The
 * cover is mirrored into [Vehicle.imagePath] server-side.
 *
 * @param onAddPhoto picker hook for another photo; null hides the affordance
 *   (config-less builds with no uploader). Disabled at the photo cap.
 * @param onSetCover promotes the given photo path to cover; null hides the action.
 * @param onRemovePhoto removes the given photo path (after confirm); null hides it.
 * @param isUploadingPhoto true while an add-photo upload is in flight.
 */
@Composable
fun VehicleDetailScreen(
    vehicle: Vehicle,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetMain: (isMain: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onAddPhoto: (() -> Unit)? = null,
    onSetCover: ((path: String) -> Unit)? = null,
    onRemovePhoto: ((path: String) -> Unit)? = null,
    isUploadingPhoto: Boolean = false,
) {
    val photoPaths = remember(vehicle) { VehicleGallery.photoPaths(vehicle) }
    var pendingDelete by remember { mutableStateOf(false) }

    AeroPage(title = stringResource(R.string.garage_detailTitle), modifier = modifier) {
        VehicleGalleryPager(
            photoPaths = photoPaths,
            onSetCover = onSetCover,
            onRemovePhoto = onRemovePhoto,
        )

        Text(
            // See VehicleDisplay: catalogue name / localized "Other" / legacy text.
            text = VehicleDisplay.headline(vehicle, stringResource(R.string.garage_catalogueOther)),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (vehicle.isMainCar) {
            Text(
                text = stringResource(R.string.garage_mainCarBadge),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        vehicle.registrationPlate?.takeIf { it.isNotBlank() }?.let { plate ->
            InfoRow(label = stringResource(R.string.garage_registrationPlate), value = plate)
        }
        InfoRow(
            label = stringResource(R.string.garage_powertrain),
            value = stringResource(vehicle.powertrain.labelRes()),
        )
        vehicle.engineDescription?.takeIf { it.isNotBlank() }?.let { engine ->
            InfoRow(label = stringResource(R.string.garage_engineDescription), value = engine)
        }
        vehicle.modifications?.takeIf { it.isNotBlank() }?.let { mods ->
            InfoRow(label = stringResource(R.string.garage_modifications), value = mods)
        }

        AddMorePhotosAffordance(
            onAddPhoto = onAddPhoto,
            atCap = photoPaths.size >= VehicleValidation.MAX_VEHICLE_PHOTOS,
            isUploading = isUploadingPhoto,
        )

        // Manage actions, mirroring the list card so the detail page can stand on
        // its own. Main-car toggle first (its state is the most consequential),
        // then edit / delete.
        if (vehicle.isMainCar) {
            Button(onClick = { onSetMain(false) }, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.garage_unsetMainCar))
            }
        } else {
            OutlinedButton(onClick = { onSetMain(true) }, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.garage_setMainCar))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.garage_editVehicle))
            }
            OutlinedButton(onClick = { pendingDelete = true }, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.garage_deleteVehicle))
            }
        }
    }

    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text(text = stringResource(R.string.garage_deleteVehicle)) },
            text = { Text(text = stringResource(R.string.garage_deleteConfirm)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = false
                    onDelete()
                }) {
                    Text(text = stringResource(R.string.garage_deleteConfirmButton))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) {
                    Text(text = stringResource(R.string.garage_cancelButton))
                }
            },
        )
    }
}

/** Test tag on the scrollable thumbnail strip (used by the strip UI test). */
internal const val VEHICLE_GALLERY_STRIP_TAG = "vehicleGalleryStrip"

/** Test tag for the gallery thumbnail at [index] (used by the strip UI test). */
internal fun vehicleGalleryThumbnailTag(index: Int): String =
    "vehicleGalleryThumbnail_$index"

/** Test tags for the per-photo gallery actions. */
internal const val VEHICLE_GALLERY_SET_COVER_TAG = "vehicleGallerySetCover"
internal const val VEHICLE_GALLERY_REMOVE_TAG = "vehicleGalleryRemove"

/**
 * The swipeable photo gallery: a full-width ROUND (circle-clipped) pager over
 * [photoPaths], with a "current / total" counter and a tappable thumbnail strip
 * whenever there is more than one photo. Falls back to a single empty
 * placeholder tile when the car has no photo yet, so the page layout is stable
 * either way.
 *
 * Reads straight off [photoPaths] (cover first), so the pager, counter and
 * thumbnails all follow the data with no per-count rework.
 *
 * When [onSetCover] / [onRemovePhoto] are supplied, an action row for the
 * CURRENT photo (make cover / remove, with a confirm dialog) is shown below the
 * pager. `internal` (not `private`) so the multi-photo strip + actions can be
 * exercised directly in a UI test.
 */
@Composable
internal fun VehicleGalleryPager(
    photoPaths: List<String>,
    onSetCover: ((path: String) -> Unit)? = null,
    onRemovePhoto: ((path: String) -> Unit)? = null,
) {
    if (photoPaths.isEmpty()) {
        GalleryPlaceholder()
        return
    }

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { photoPaths.size })
    var pendingPhotoRemoval by remember { mutableStateOf<String?>(null) }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(CircleShape),
    ) { page ->
        GalleryPhoto(path = photoPaths[page], modifier = Modifier.fillMaxSize())
    }

    // Safe index for the visible photo — used by the counter AND the per-photo
    // actions below, so it must be computed for a single-photo gallery too.
    val current = VehicleGallery.clampIndex(pagerState.currentPage, photoPaths.size)
    val currentPath = photoPaths[current]
    val currentIsCover = VehicleGallery.isCover(photoPaths, currentPath)

    if (VehicleGallery.hasMultiple(photoPaths.size)) {
        Text(
            text = stringResource(R.string.garage_photoCounter, current + 1, photoPaths.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Horizontally scrollable strip: the gallery is built for N photos, so a
        // fixed Row would clip (and make unreachable) any thumbnail past the
        // screen width once the backend stores more than a screenful. LazyRow
        // only composes the visible tiles and lets every thumbnail be reached.
        val thumbnailListState = rememberLazyListState()
        // Keep the highlighted thumbnail on-screen as the pager moves — LazyRow
        // won't auto-scroll to the selected item, so on a long strip the border
        // highlight would otherwise drift off-screen.
        LaunchedEffect(current) {
            thumbnailListState.animateScrollToItem(current)
        }
        LazyRow(
            state = thumbnailListState,
            modifier = Modifier.fillMaxWidth().testTag(VEHICLE_GALLERY_STRIP_TAG),
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            itemsIndexed(photoPaths) { index, path ->
                val selected = index == current
                GalleryPhoto(
                    path = path,
                    // Each thumbnail is a navigation control, so it carries an
                    // action label ("Show photo N") rather than the generic photo
                    // alt, and role = Role.Button so it is announced as a button —
                    // matching AeroPageTitle and the other clickable sites.
                    contentDescription = stringResource(R.string.garage_photoThumbnail, index + 1),
                    modifier = Modifier
                        .testTag(vehicleGalleryThumbnailTag(index))
                        .size(KccSpacing.s12)
                        .clip(CircleShape)
                        .then(
                            if (selected) {
                                Modifier.border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .clickable(role = Role.Button) {
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                )
            }
        }
    }

    // Per-photo actions for the visible photo. Shown only when the caller wired
    // the callbacks (the detail page does; the strip UI test does not).
    if (onSetCover != null || onRemovePhoto != null) {
        if (currentIsCover) {
            Text(
                text = stringResource(R.string.garage_photoCoverBadge),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            if (onSetCover != null) {
                // Making the already-cover photo the cover is a no-op, so disable it.
                OutlinedButton(
                    onClick = { onSetCover(currentPath) },
                    enabled = !currentIsCover,
                    modifier = Modifier.weight(1f).testTag(VEHICLE_GALLERY_SET_COVER_TAG),
                ) {
                    Text(text = stringResource(R.string.garage_photoSetCover))
                }
            }
            if (onRemovePhoto != null) {
                OutlinedButton(
                    onClick = { pendingPhotoRemoval = currentPath },
                    modifier = Modifier.weight(1f).testTag(VEHICLE_GALLERY_REMOVE_TAG),
                ) {
                    Text(text = stringResource(R.string.garage_photoRemove))
                }
            }
        }
    }

    val removalTarget = pendingPhotoRemoval
    if (removalTarget != null && onRemovePhoto != null) {
        AlertDialog(
            onDismissRequest = { pendingPhotoRemoval = null },
            title = { Text(text = stringResource(R.string.garage_photoRemove)) },
            text = { Text(text = stringResource(R.string.garage_photoRemoveConfirm)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingPhotoRemoval = null
                    onRemovePhoto(removalTarget)
                }) {
                    Text(text = stringResource(R.string.garage_photoRemoveConfirmButton))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPhotoRemoval = null }) {
                    Text(text = stringResource(R.string.garage_cancelButton))
                }
            },
        )
    }
}

/** One resolved gallery image (full page or thumbnail), cropped to fill. */
@Composable
private fun GalleryPhoto(
    path: String,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.garage_photoAlt),
) {
    val context = LocalContext.current
    val url = rememberStorageImageUrl(context, path)
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Empty-state photo tile, shown when the car has no photo yet. */
@Composable
private fun GalleryPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.garage_photoNone),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "Add more photos" control. Hidden entirely when no picker hook is wired
 * ([onAddPhoto] null — e.g. a config-less build with no uploader). Enabled while
 * below the photo cap; at the cap it renders DISABLED with an explanation, and
 * while an upload is in flight it shows a progress label instead.
 */
@Composable
private fun AddMorePhotosAffordance(
    onAddPhoto: (() -> Unit)?,
    atCap: Boolean,
    isUploading: Boolean,
) {
    if (onAddPhoto == null) return
    OutlinedButton(
        onClick = onAddPhoto,
        enabled = !atCap && !isUploading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = stringResource(R.string.garage_photoAddMore))
    }
    when {
        isUploading ->
            Text(
                text = stringResource(R.string.garage_photoUploading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
        atCap ->
            Text(
                text = stringResource(R.string.garage_photoAddMoreUnavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
    }
}

/** A labelled read-only field: small caption label above the value. */
@Composable
private fun InfoRow(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
