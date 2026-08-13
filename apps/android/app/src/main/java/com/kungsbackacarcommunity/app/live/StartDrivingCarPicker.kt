package com.kungsbackacarcommunity.app.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.garage.Vehicle
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl

/**
 * Which garage car the "Start driving" popup preselects: the main car, or — when
 * none is flagged — the first car, or null when the user owns no cars. Mirrors
 * the server's fallback (pickSessionVehicleData) so the highlighted car and the
 * car the session actually denormalizes are the same. Pure so it is JVM-testable
 * without Compose.
 */
fun defaultStartDrivingVehicleId(vehicles: List<Vehicle>): String? =
    vehicles.firstOrNull { it.isMainCar }?.id ?: vehicles.firstOrNull()?.id

/** Diameter of one round car photo in the picker; also the item's full width. */
private val CarPickerDiameter: Dp = 64.dp

/**
 * A horizontal row of round garage-car photos shown in the "Start driving"
 * popup. Tapping one selects the car the live session (Single OR Convoy) will be
 * driven in; the selection is denormalized onto the session so viewers see that
 * car on the map. The currently-selected car wears a primary-coloured ring.
 *
 * Renders a short "no cars" hint instead of the row when the garage is empty —
 * the session still starts (with the generic marker), the picker just has
 * nothing to offer. Photos reuse the same circular Storage-backed rendering as
 * the garage cards.
 */
@Composable
fun StartDrivingCarPicker(
    vehicles: List<Vehicle>,
    selectedVehicleId: String?,
    onSelectVehicle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
        Text(
            text = stringResource(R.string.shell_createChooserCarLabel),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (vehicles.isEmpty()) {
            Text(
                text = stringResource(R.string.shell_createChooserNoCars),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val scrollState = rememberScrollState()
            // Bring the preselected car into view when the picker opens (and again
            // whenever the selection changes). The row scrolls horizontally, so a
            // preselected MAIN car that sorts after the cars that fit on screen
            // would otherwise sit off to the right — the popup would open showing
            // only unselected cars, making it look as though nothing (or the wrong
            // car) is preselected even though the ring is correctly on the main car.
            // Items are fixed-width (CarPickerDiameter) with a uniform gap, so the
            // target offset is exact; scrollState clamps a past-the-end target.
            // Index 0 (and "no match", -1) map to the start, so selecting the first
            // car after the row was scrolled right brings it back into view too.
            val selectedIndex = vehicles.indexOfFirst { it.id == selectedVehicleId }
            val itemStridePx =
                with(LocalDensity.current) { (CarPickerDiameter + KccSpacing.s3).toPx() }
            LaunchedEffect(selectedIndex, vehicles.size) {
                val targetIndex = selectedIndex.coerceAtLeast(0)
                scrollState.scrollTo((targetIndex * itemStridePx).toInt())
            }
            Row(
                modifier = Modifier.horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
            ) {
                vehicles.forEach { vehicle ->
                    CarPickerItem(
                        vehicle = vehicle,
                        selected = vehicle.id == selectedVehicleId,
                        onClick = { onSelectVehicle(vehicle.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CarPickerItem(
    vehicle: Vehicle,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val diameter = CarPickerDiameter
    val ringColor =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val ringWidth = if (selected) 3.dp else 1.dp
    // The plate is never surfaced here — the make/model line is enough to tell the
    // cars apart and keeps the picker consistent with the no-plate live marker.
    val label = "${vehicle.make} ${vehicle.model}".trim()
    val url = rememberStorageImageUrl(LocalContext.current, vehicle.imagePath)
    Box(
        modifier =
            Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(ringWidth, ringColor, CircleShape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().clip(CircleShape),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.DirectionsCar,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(KccSpacing.s3),
            )
        }
    }
}
