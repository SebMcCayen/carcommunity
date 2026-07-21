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

    /**
     * Creates a vehicle and returns its NEW id, as minted by garage-addVehicle
     * (which already responds with `{ vehicleId }`). The id is required by the
     * add-photo flow: `vehicleImages/{uid}/{vehicleId}/` cannot be keyed until
     * the vehicle exists, so the photo is uploaded only after this resolves.
     */
    suspend fun addVehicle(input: VehicleInput): String

    suspend fun updateVehicle(vehicleId: String, input: VehicleInput)

    /**
     * Records the uploaded COVER photo path on the vehicle via garage-updateVehicle
     * (imagePath field). [imagePath] must lie under the caller's own
     * vehicleImages/{uid}/{vehicleId}/ prefix; the callable re-validates it and
     * reconciles photoPaths so the cover stays photoPaths[0].
     */
    suspend fun updateVehicleImagePath(vehicleId: String, imagePath: String)

    /**
     * Appends an uploaded [photoPath] to the vehicle's gallery via
     * garage-addVehiclePhoto (max 10 photos; own-prefix validated). The first
     * photo also becomes the cover.
     */
    suspend fun addVehiclePhoto(vehicleId: String, photoPath: String)

    /**
     * Removes [photoPath] from the vehicle's gallery via garage-removeVehiclePhoto
     * (the callable also deletes the Storage object); removing the cover promotes
     * the next remaining photo.
     */
    suspend fun removeVehiclePhoto(vehicleId: String, photoPath: String)

    /**
     * Sets the full photo display order via garage-reorderVehiclePhotos.
     * [orderedPaths] must be a permutation of the vehicle's current photos;
     * orderedPaths[0] becomes the cover.
     */
    suspend fun reorderVehiclePhotos(vehicleId: String, orderedPaths: List<String>)

    /**
     * Marks [vehicleId] as the user's main car (or clears it). At most one main
     * car per user is enforced by the garage-setMainVehicle callable — setting
     * one clears any other in the same transaction.
     */
    suspend fun setMainVehicle(vehicleId: String, isMain: Boolean)

    suspend fun deleteVehicle(vehicleId: String)
}
