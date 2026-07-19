package com.kungsbackacarcommunity.app.garage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.shell.AeroPage

/**
 * Garage list (Phase 12 slice 13). Stateless apart from the delete-confirm
 * dialog. Any signed-in user may add/manage their own cars (no longer
 * member-gated); adding is limited only by the max-5 cap (backend-enforced).
 *
 * This is what the Garage TAB shows: the user's cars and the "Add vehicle"
 * button are visible on landing, with no hub screen in between. Back is handled
 * centrally by the shell, so this renders no Back affordance.
 */
@Composable
fun GarageScreen(
    state: GarageState,
    onAdd: () -> Unit,
    onEdit: (Vehicle) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    // Sets (true) or clears (false) a car as the user's main car; max 1 enforced
    // by the backend. No-op wiring in previews/tests when the coordinator is absent.
    onSetMain: (vehicleId: String, isMain: Boolean) -> Unit = { _, _ -> },
    // Re-invokes the garage load; when null the error state shows no retry.
    onRetry: (() -> Unit)? = null,
) {
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    AeroPage(title = stringResource(R.string.garage_screenTitle), modifier = modifier) {
            when (state) {
                GarageState.Loading ->
                    Text(
                        text = stringResource(R.string.garage_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                GarageState.Error -> {
                    Text(
                        text = stringResource(R.string.garage_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (onRetry != null) {
                        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                            Text(text = stringResource(R.string.garage_retryButton))
                        }
                    }
                }

                is GarageState.Loaded -> {
                    if (state.vehicles.isEmpty()) {
                        Text(
                            text = stringResource(R.string.garage_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.vehicles.forEach { vehicle ->
                            VehicleCard(
                                vehicle = vehicle,
                                onEdit = { onEdit(vehicle) },
                                onDelete = { pendingDelete = vehicle.id },
                                onSetMain = { isMain -> onSetMain(vehicle.id, isMain) },
                            )
                        }
                    }
                    // Max 5 vehicles per user (backend-enforced).
                    if (state.vehicles.size < 5) {
                        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                            Text(text = stringResource(R.string.garage_addVehicle))
                        }
                    }
                }
            }
    }

    val deleteId = pendingDelete
    if (deleteId != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(text = stringResource(R.string.garage_deleteVehicle)) },
            text = { Text(text = stringResource(R.string.garage_deleteConfirm)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(deleteId)
                }) {
                    Text(text = stringResource(R.string.garage_deleteConfirmButton))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(text = stringResource(R.string.garage_cancelButton))
                }
            },
        )
    }
}

@Composable
private fun VehicleCard(
    vehicle: Vehicle,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetMain: (isMain: Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
        ) {
            VehiclePhoto(vehicle.imagePath)
            Text(
                text = "${vehicle.make} ${vehicle.model} (${vehicle.modelYear})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(vehicle.powertrain.labelRes()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (vehicle.isMainCar) {
                Text(
                    text = stringResource(R.string.garage_mainCarBadge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            vehicle.engineDescription?.takeIf { it.isNotBlank() }?.let { engine ->
                Text(
                    text = engine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            vehicle.modifications?.takeIf { it.isNotBlank() }?.let { mods ->
                Text(
                    text = mods,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Main-car toggle: filled when this car is the main car (tapping
            // clears it), outlined otherwise (tapping makes it the main car).
            if (vehicle.isMainCar) {
                Button(
                    onClick = { onSetMain(false) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.garage_unsetMainCar))
                }
            } else {
                OutlinedButton(
                    onClick = { onSetMain(true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
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
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.garage_deleteVehicle))
                }
            }
        }
    }
}

/**
 * The car's photo, resolved from its Storage path at render time. Mirrors the
 * member-profile card's photo (same 16:9 crop), so a car looks the same in its
 * owner's garage as it does on their public profile.
 *
 * Renders nothing when the car has no photo, or while/if the URL cannot be
 * resolved (config-less build) — the card simply starts at its title, exactly
 * as it did before photos existed.
 */
@Composable
private fun VehiclePhoto(imagePath: String?) {
    val context = LocalContext.current
    val url = rememberStorageImageUrl(context, imagePath)
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = stringResource(R.string.garage_photoAlt),
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(KccRadius.sm)),
        )
    }
}
