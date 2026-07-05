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

    suspend fun deleteVehicle(vehicleId: String)
}
