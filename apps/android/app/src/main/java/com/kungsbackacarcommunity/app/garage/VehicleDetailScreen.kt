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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.media.ImageCrop
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
 * PHOTO COUNT: the gallery is built to page over N photos, but the data model
 * stores a single [Vehicle.imagePath] today, so [VehicleGallery.photoPaths]
 * yields at most one. The "add more photos" control is therefore rendered
 * DISABLED with an explanation (the #433 pattern) — the existing single-photo
 * pick stays on the Edit form and still runs through the crop/compress/EXIF
 * coordinator. See the PR body for the multi-photo backend contract.
 *
 * @param onAddPhoto picker hook for additional photos; null (today) disables
 *   the affordance and shows the "one photo per car for now" hint.
 */
@Composable
fun VehicleDetailScreen(
    vehicle: Vehicle,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetMain: (isMain: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onAddPhoto: (() -> Unit)? = null,
) {
    val photoPaths = remember(vehicle) { VehicleGallery.photoPaths(vehicle) }
    var pendingDelete by remember { mutableStateOf(false) }

    AeroPage(title = stringResource(R.string.garage_detailTitle), modifier = modifier) {
        VehicleGalleryPager(photoPaths)

        Text(
            text = "${vehicle.make} ${vehicle.model} (${vehicle.modelYear})",
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

        AddMorePhotosAffordance(onAddPhoto = onAddPhoto)

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

/**
 * The swipeable photo gallery: a full-width 16:9 pager over [photoPaths], with a
 * "current / total" counter and a tappable thumbnail strip whenever there is
 * more than one photo. Falls back to a single empty placeholder tile when the
 * car has no photo yet, so the page layout is stable either way.
 *
 * Built for N photos even though the model yields at most one today (see
 * [VehicleGallery]): the pager, counter and thumbnails all read straight off the
 * list, so multi-photo support becomes a data change with no rework here.
 */
@Composable
private fun VehicleGalleryPager(photoPaths: List<String>) {
    if (photoPaths.isEmpty()) {
        GalleryPlaceholder()
        return
    }

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { photoPaths.size })

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ImageCrop.VEHICLE_ASPECT_RATIO)
            .clip(RoundedCornerShape(KccRadius.md)),
    ) { page ->
        GalleryPhoto(path = photoPaths[page], modifier = Modifier.fillMaxSize())
    }

    if (VehicleGallery.hasMultiple(photoPaths.size)) {
        val current = VehicleGallery.clampIndex(pagerState.currentPage, photoPaths.size)
        Text(
            text = stringResource(R.string.garage_photoCounter, current + 1, photoPaths.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            photoPaths.forEachIndexed { index, path ->
                val selected = index == current
                GalleryPhoto(
                    path = path,
                    modifier = Modifier
                        .size(KccSpacing.s12)
                        .clip(RoundedCornerShape(KccRadius.sm))
                        .then(
                            if (selected) {
                                Modifier.border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(KccRadius.sm),
                                )
                            } else {
                                Modifier
                            },
                        )
                        .clickable { scope.launch { pagerState.animateScrollToPage(index) } },
                )
            }
        }
    }
}

/** One resolved gallery image (full page or thumbnail), cropped to fill. */
@Composable
private fun GalleryPhoto(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val url = rememberStorageImageUrl(context, path)
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.garage_photoAlt),
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
            .aspectRatio(ImageCrop.VEHICLE_ASPECT_RATIO)
            .clip(RoundedCornerShape(KccRadius.md))
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
 * "Add more photos" control. Enabled only once a picker hook is wired
 * ([onAddPhoto] non-null); until the backend can store more than one photo it
 * renders DISABLED with an explanatory hint (the #433 pattern) rather than being
 * hidden, so the capability is discoverable and its absence is explained.
 */
@Composable
private fun AddMorePhotosAffordance(onAddPhoto: (() -> Unit)?) {
    OutlinedButton(
        onClick = onAddPhoto ?: {},
        enabled = onAddPhoto != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = stringResource(R.string.garage_photoAddMore))
    }
    if (onAddPhoto == null) {
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
