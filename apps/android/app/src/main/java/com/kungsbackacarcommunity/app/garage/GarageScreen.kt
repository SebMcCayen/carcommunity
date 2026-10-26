package com.kungsbackacarcommunity.app.garage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import com.kungsbackacarcommunity.app.design.KccButton as Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.shell.AeroPage

/**
 * Garage list (Phase 12 slice 13). Stateless apart from the delete-confirm
 * dialog. Any signed-in user may add/manage their own cars (no longer
 * member-gated); adding follows the current Community/Plus/Supporter allowance
 * (backend-enforced).
 *
 * This is what the Garage TAB shows: the user's cars and the "Add vehicle"
 * button are visible on landing, with no hub screen in between. Back is handled
 * centrally by the shell, so this renders no Back affordance.
 */
@Composable
fun GarageScreen(
    state: GarageState,
    vehicleLimit: Int,
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

    AeroPage(
        title = stringResource(R.string.garage_screenTitle),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
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
                    Text(
                        text =
                            stringResource(
                                R.string.garage_vehicleAllowance,
                                state.vehicles.size,
                                vehicleLimit,
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The backend independently resolves the current tier and
                    // enforces this allowance inside the add transaction.
                    if (state.vehicles.size < vehicleLimit) {
                        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text(text = stringResource(R.string.garage_addVehicle))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.garage_vehicleLimitReached, vehicleLimit),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
 * One of the owner's own cars in the garage list: the shared [VehicleCard] with
 * My Garage's photo-led look plus the manage actions that only
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
    var menuExpanded by remember(vehicle.id) { mutableStateOf(false) }
    val vehicleName = VehicleDisplay.headline(vehicle, stringResource(R.string.garage_catalogueOther))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    VehicleCard(
        vehicle = vehicle,
        photoStyle = VehiclePhotoStyle.FullWidth(garagePhotoAspectRatio(maxWidth.value, LocalDensity.current.fontScale), KccRadius.md),
        photoModifier = Modifier.testTag(garagePhotoTag(vehicle.id)),
        mainCarLabelRes = R.string.garage_mainCarBadge,
        photoContentDescriptionRes = R.string.garage_photoAlt,
        contentSpacing = KccSpacing.s2,
        onOpen = onOpen,
        overlayActions = {
            // Sibling of the detail tap target: opening/using this menu cannot open the car.
            Box {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.savedPlaces_moreActions, vehicleName),
                        )
                    }
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(if (vehicle.isMainCar) R.string.garage_unsetMainCar else R.string.garage_setMainCar))
                        },
                        onClick = {
                            menuExpanded = false
                            onSetMain(!vehicle.isMainCar)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.garage_editVehicle)) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.garage_deleteVehicle), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
    }
}
