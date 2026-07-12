package com.kungsbackacarcommunity.app.garage

import kotlinx.coroutines.flow.Flow

/** UI-facing state of the garage list. */
sealed interface GarageState {
    data object Loading : GarageState

    data object Error : GarageState

    data class Loaded(val vehicles: List<Vehicle>) : GarageState
}

/**
 * Garage operations (Phase 12 slice 13). Firebase-free interface so the
 * route/screens are unit- and UI-testable with fakes. The list is an owner
 * Firestore read; all writes go through the member-gated garage.* callables.
 */
interface GarageRepository {
    fun observeGarage(uid: String): Flow<GarageState>

    suspend fun addVehicle(input: VehicleInput)

    suspend fun updateVehicle(vehicleId: String, input: VehicleInput)

    /**
     * Records the uploaded photo path on the vehicle via garage-updateVehicle
     * (imagePath field). [imagePath] must lie under the caller's own
     * vehicleImages/{uid}/{vehicleId}/ prefix; the callable re-validates it.
     */
    suspend fun updateVehicleImagePath(vehicleId: String, imagePath: String)

    /**
     * Marks [vehicleId] as the user's main car (or clears it). At most one main
     * car per user is enforced by the garage-setMainVehicle callable — setting
     * one clears any other in the same transaction.
     */
    suspend fun setMainVehicle(vehicleId: String, isMain: Boolean)

    suspend fun deleteVehicle(vehicleId: String)
}
