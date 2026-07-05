package com.kungsbackacarcommunity.app.garage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing status of a vehicle add/update. */
sealed interface VehicleSaveStatus {
    data object Idle : VehicleSaveStatus

    data object Saving : VehicleSaveStatus

    data object Saved : VehicleSaveStatus

    data object Failed : VehicleSaveStatus
}

/**
 * Orchestrates garage add/update/delete (Phase 12 slice 13). Pure Kotlin so it
 * is unit-testable with a fake repository. Save tracks a status so the form can
 * close on success; delete is fire-and-forget (the list observer reflects it).
 */
class GarageCoordinator(
    private val repository: GarageRepository,
) {
    private val state = MutableStateFlow<VehicleSaveStatus>(VehicleSaveStatus.Idle)
    val saveStatus: StateFlow<VehicleSaveStatus> = state.asStateFlow()

    /** Adds when [editingVehicleId] is null, otherwise updates it. */
    suspend fun save(input: VehicleInput, editingVehicleId: String?) {
        if (state.value == VehicleSaveStatus.Saving) return
        state.value = VehicleSaveStatus.Saving
        try {
            if (editingVehicleId == null) {
                repository.addVehicle(input)
            } else {
                repository.updateVehicle(editingVehicleId, input)
            }
            state.value = VehicleSaveStatus.Saved
        } catch (cancellation: CancellationException) {
            state.value = VehicleSaveStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = VehicleSaveStatus.Failed
        }
    }

    suspend fun delete(vehicleId: String) {
        repository.deleteVehicle(vehicleId)
    }

    fun reset() {
        state.value = VehicleSaveStatus.Idle
    }
}
