package com.kungsbackacarcommunity.app.garage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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

/**
 * Garage list (Phase 12 slice 13). Stateless apart from the delete-confirm
 * dialog. Adding is gated on active membership + the max-5 limit.
 */
@Composable
fun GarageScreen(
    state: GarageState,
    isActiveMember: Boolean,
    onAdd: () -> Unit,
    onEdit: (Vehicle) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // Re-invokes the garage load; when null the error state shows no retry.
    onRetry: (() -> Unit)? = null,
) {
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.garage_screenTitle),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            if (!isActiveMember) {
                Text(
                    text = stringResource(R.string.garage_memberRequiredBody),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (state) {
                GarageState.Loading ->
                    Text(
                        text = stringResource(R.string.garage_screenTitle),
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
                                isActiveMember = isActiveMember,
                                onEdit = { onEdit(vehicle) },
                                onDelete = { pendingDelete = vehicle.id },
                            )
                        }
                    }
                    // Max 5 vehicles per user (backend-enforced).
                    if (isActiveMember && state.vehicles.size < 5) {
                        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                            Text(text = stringResource(R.string.garage_addVehicle))
                        }
                    }
                }
            }

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.profile_back))
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
    isActiveMember: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
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
            vehicle.engineDescription?.takeIf { it.isNotBlank() }?.let { engine ->
                Text(
                    text = engine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isActiveMember) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
}
