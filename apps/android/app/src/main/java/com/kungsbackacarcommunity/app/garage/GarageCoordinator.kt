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

    /**
     * Adds when [editingVehicleId] is null, otherwise updates it.
     *
     * @return the saved vehicle's id — the NEW id minted by garage-addVehicle on
     *   an add, or [editingVehicleId] on an update — or null when the save did
     *   not happen (failed, or a re-entrant call while another save is in
     *   flight). The add-photo flow needs the new id to key
     *   `vehicleImages/{uid}/{vehicleId}/`, which does not exist until the
     *   vehicle does.
     */
    suspend fun save(input: VehicleInput, editingVehicleId: String?): String? {
        if (state.value == VehicleSaveStatus.Saving) return null
        state.value = VehicleSaveStatus.Saving
        return try {
            val vehicleId =
                if (editingVehicleId == null) {
                    repository.addVehicle(input)
                } else {
                    repository.updateVehicle(editingVehicleId, input)
                    editingVehicleId
                }
            state.value = VehicleSaveStatus.Saved
            vehicleId
        } catch (cancellation: CancellationException) {
            state.value = VehicleSaveStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = VehicleSaveStatus.Failed
            null
        }
    }

    suspend fun delete(vehicleId: String) {
        repository.deleteVehicle(vehicleId)
    }

    /**
     * Records an already-uploaded gallery photo (garage-addVehiclePhoto).
     * Fire-and-forget like [delete]/[setMain] — the list observer reflects the
     * new photoPaths once the callable resolves.
     */
    suspend fun addPhoto(vehicleId: String, photoPath: String) {
        repository.addVehiclePhoto(vehicleId, photoPath)
    }

    /** Removes a gallery photo (garage-removeVehiclePhoto). Fire-and-forget. */
    suspend fun removePhoto(vehicleId: String, photoPath: String) {
        repository.removeVehiclePhoto(vehicleId, photoPath)
    }

    /**
     * Sets [orderedPaths] as the full photo order / cover
     * (garage-reorderVehiclePhotos). [orderedPaths] must be a permutation of the
     * vehicle's current photos; the callable rejects anything else.
     */
    suspend fun reorderPhotos(vehicleId: String, orderedPaths: List<String>) {
        repository.reorderVehiclePhotos(vehicleId, orderedPaths)
    }

    /**
     * Sets or clears [vehicleId] as the user's main car. Fire-and-forget like
     * [delete] — the list observer reflects the flip (and the max-1 clear of any
     * previous main car) once the callable resolves.
     */
    suspend fun setMain(vehicleId: String, isMain: Boolean) {
        repository.setMainVehicle(vehicleId, isMain)
    }

    fun reset() {
        state.value = VehicleSaveStatus.Idle
    }
}
