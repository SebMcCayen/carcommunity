package com.kungsbackacarcommunity.app.garage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.shell.AeroPage

/**
 * Maximum vehicles a member may keep in their garage. Mirrors
 * MAX_VEHICLES_PER_USER in functions/src/garage/garage-core.ts, which is the
 * source of truth and enforces the cap inside the addVehicle transaction; this
 * client copy only decides whether to show the "Add vehicle" button.
 */
private const val MAX_VEHICLES_PER_USER = 10

/**
 * Garage list (Phase 12 slice 13). Stateless apart from the delete-confirm
 * dialog. Any signed-in user may add/manage their own cars (no longer
 * member-gated); adding is limited only by the max-vehicle cap (backend-enforced).
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
    // Opens the full car-detail page for a tapped car. Defaults to a no-op so
    // existing preview/test call sites (and any screen that has no detail route)
    // compile unchanged.
    onOpen: (Vehicle) -> Unit = {},
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
                            OwnedVehicleCard(
                                vehicle = vehicle,
                                onOpen = { onOpen(vehicle) },
                                onEdit = { onEdit(vehicle) },
                                onDelete = { pendingDelete = vehicle.id },
                                onSetMain = { isMain -> onSetMain(vehicle.id, isMain) },
                            )
                        }
                    }
                    // Mirrors MAX_VEHICLES_PER_USER in functions/src/garage/garage-core.ts
                    // (backend enforces the cap inside the addVehicle transaction).
                    if (state.vehicles.size < MAX_VEHICLES_PER_USER) {
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

/**
 * Diameter of the circular car photo in the garage list.
 *
 * Deliberately a fixed size rather than `fillMaxWidth()`: a full-width circle is
 * as tall as the card is wide (~300dp+ on a phone), so a single car filled the
 * screen and the list could not be skimmed. There is no size token for this —
 * [KccSpacing] tops out at 48dp and is a spacing scale, not a component scale —
 * so a literal dp here matches how the rest of the app sizes images (e.g. the
 * 96dp profile avatar).
 */
private val VehiclePhotoDiameter = 180.dp

/**
 * My Garage renders the car photo as a fixed-diameter CIRCLE, centre-cropped
 * into the circle from whatever ratio the source was. Declared after
 * [VehiclePhotoDiameter] because top-level properties initialise in file order.
 */
private val GarageVehiclePhotoStyle = VehiclePhotoStyle.Circle(VehiclePhotoDiameter)

/**
 * One of the owner's own cars in the garage list: the shared [VehicleCard] with
 * My Garage's look (circular photo, 4dp rows) plus the manage actions that only
 * the owner gets. The plate is deliberately not shown here — it belongs to the
 * detail page ([VehicleDetailScreen]), not the skimmable list.
 */
@Composable
private fun OwnedVehicleCard(
    vehicle: Vehicle,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetMain: (isMain: Boolean) -> Unit,
) {
    VehicleCard(
        vehicle = vehicle,
        photoStyle = GarageVehiclePhotoStyle,
        mainCarLabelRes = R.string.garage_mainCarBadge,
        photoContentDescriptionRes = R.string.garage_photoAlt,
        contentSpacing = KccSpacing.s1,
        onOpen = onOpen,
    ) {
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
