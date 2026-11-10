package com.kungsbackacarcommunity.app.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.garage.Vehicle
import com.kungsbackacarcommunity.app.garage.VehicleDisplay
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl

/** Matches the server's main → first → no vehicle default. */
fun defaultStartDrivingVehicleId(vehicles: List<Vehicle>): String? =
    vehicles.firstOrNull { it.isMainCar }?.id ?: vehicles.firstOrNull()?.id

internal fun quickStartDrivingVehicles(vehicles: List<Vehicle>, rankedIds: List<String>): List<Vehicle> {
    val remaining = vehicles.associateBy { it.id }.toMutableMap()
    val ranked = rankedIds.mapNotNull { remaining.remove(it) }
    val main = vehicles.firstOrNull { it.isMainCar }?.let { remaining.remove(it.id) }
    return (ranked + listOfNotNull(main) + remaining.values).take(3)
}

@Composable
fun StartDrivingCarPicker(
    vehicles: List<Vehicle>,
    selectedVehicleId: String?,
    onSelectVehicle: (String) -> Unit,
    modifier: Modifier = Modifier,
    rankedVehicleIds: List<String> = emptyList(),
    showAll: Boolean = false,
    onShowAll: () -> Unit = {},
    rankingPending: Boolean = false,
) {
    val visible = when {
        showAll -> vehicles
        rankingPending -> emptyList()
        else -> quickStartDrivingVehicles(vehicles, rankedVehicleIds)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(KccSpacing.s1)) {
        if (!showAll) {
            Text(
                text = stringResource(R.string.shell_createChooserCarLabel),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (vehicles.isEmpty()) {
            Text(
                text = stringResource(R.string.shell_createChooserNoCars),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!showAll && visible.none { it.id == selectedVehicleId }) {
            vehicles.firstOrNull { it.id == selectedVehicleId }?.let { selected ->
                val headline = VehicleDisplay.headline(selected, stringResource(R.string.garage_catalogueOther))
                Text(
                    text = stringResource(R.string.shell_createChooserSelectedVehicle, headline),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Column(Modifier.selectableGroup()) {
            visible.forEach { vehicle ->
                CarPickerItem(vehicle, vehicle.id == selectedVehicleId) { onSelectVehicle(vehicle.id) }
            }
        }
        if (!showAll && vehicles.size > 3) {
            TextButton(onClick = onShowAll) { Text(stringResource(R.string.shell_createChooserAllVehicles)) }
        }
    }
}

@Composable
private fun CarPickerItem(vehicle: Vehicle, selected: Boolean, onClick: () -> Unit) {
    val label = VehicleDisplay.headline(vehicle, stringResource(R.string.garage_catalogueOther))
    val url = rememberStorageImageUrl(LocalContext.current, vehicle.imagePath)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(KccRadius.sm))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = KccSpacing.s2, vertical = KccSpacing.s1),
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp)
                .clip(RoundedCornerShape(KccRadius.sm))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.DirectionsCar, contentDescription = null, modifier = Modifier.size(20.dp))
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Box(Modifier.size(24.dp)) {
            if (selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}
