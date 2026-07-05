package com.kungsbackacarcommunity.app.garage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.time.Year
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Garage integration route (Phase 12 slice 13): owns the list ↔ form selection
 * and wires the repository flow + coordinator into the stateless screens.
 */
@Composable
fun GarageRoute(
    repository: GarageRepository,
    coordinator: GarageCoordinator?,
    uid: String,
    isActiveMember: Boolean,
    onBack: () -> Unit,
    currentYear: Int = Year.now().value,
) {
    val scope = rememberCoroutineScope()
    var showForm by rememberSaveable { mutableStateOf(false) }
    var editingVehicleId by rememberSaveable { mutableStateOf<String?>(null) }

    val garageState by
        remember(repository, uid) { repository.observeGarage(uid) }
            .collectAsState(initial = GarageState.Loading)
    val saveStatus by
        (coordinator?.saveStatus ?: flowOf(VehicleSaveStatus.Idle))
            .collectAsState(initial = VehicleSaveStatus.Idle)

    if (showForm) {
        val editingId = editingVehicleId
        val vehicle =
            editingId?.let { id -> (garageState as? GarageState.Loaded)?.vehicles?.firstOrNull { it.id == id } }
        val initial =
            vehicle?.let {
                VehicleForm(
                    make = it.make,
                    model = it.model,
                    modelYear = it.modelYear.toString(),
                    powertrain = it.powertrain,
                    engineDescription = it.engineDescription ?: "",
                )
            } ?: VehicleForm()

        key(editingId) {
            VehicleFormScreen(
                initial = initial,
                isEdit = editingId != null,
                saveStatus = saveStatus,
                currentYear = currentYear,
                onSave = { input ->
                    coordinator?.let { c -> scope.launch { c.save(input, editingId) } }
                },
                onCancel = {
                    showForm = false
                    editingVehicleId = null
                    coordinator?.reset()
                },
            )
        }
    } else {
        GarageScreen(
            state = garageState,
            isActiveMember = isActiveMember,
            onAdd = {
                editingVehicleId = null
                coordinator?.reset()
                showForm = true
            },
            onEdit = { vehicle ->
                editingVehicleId = vehicle.id
                coordinator?.reset()
                showForm = true
            },
            onDelete = { id ->
                coordinator?.let { c -> scope.launch { runCatching { c.delete(id) } } }
            },
            onBack = onBack,
        )
    }
}
