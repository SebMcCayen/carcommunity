package com.kungsbackacarcommunity.app.garage

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.firebase.awaitOrThrow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * [GarageRepository] backed by an owner Firestore query on `vehicles` plus the
 * garage-addVehicle / updateVehicle / setMainVehicle / deleteVehicle callables
 * (europe-west1), Phase 12 slice 13. Guarded ([createIfAvailable]).
 */
class FirebaseGarageRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : GarageRepository {

    override fun observeGarage(uid: String): Flow<GarageState> = callbackFlow {
        val registration =
            firestore
                .collection(VEHICLES)
                .whereEqualTo("userId", uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(GarageState.Error)
                        return@addSnapshotListener
                    }
                    val vehicles = snapshot?.documents?.mapNotNull { it.toVehicle() } ?: emptyList()
                    trySend(GarageState.Loaded(vehicles.sortedWith(compareBy({ it.make.lowercase() }, { it.model.lowercase() }))))
                }
        awaitClose { registration.remove() }
    }

    override suspend fun addVehicle(input: VehicleInput): String {
        val result = call(ADD_VEHICLE, input.toData())
        // garage-addVehicle responds { vehicleId }. A response without a usable
        // id is a broken contract, not a soft failure: the caller would have no
        // path to key the vehicle's photo under, so fail loudly rather than
        // report a success the photo step cannot act on.
        val vehicleId = (result?.get("vehicleId") as? String)?.takeIf { it.isNotBlank() }
        return vehicleId
            ?: throw IllegalStateException("$ADD_VEHICLE returned no vehicleId")
    }

    override suspend fun updateVehicle(vehicleId: String, input: VehicleInput) {
        call(UPDATE_VEHICLE, input.toData() + ("vehicleId" to vehicleId))
    }

    override suspend fun updateVehicleImagePath(vehicleId: String, imagePath: String) {
        // Partial update: only vehicleId + imagePath. The backend's
        // buildVehicleUpdate accepts an imagePath-only change.
        call(UPDATE_VEHICLE, mapOf("vehicleId" to vehicleId, "imagePath" to imagePath))
    }

    override suspend fun setMainVehicle(vehicleId: String, isMain: Boolean) {
        call(SET_MAIN_VEHICLE, mapOf("vehicleId" to vehicleId, "isMain" to isMain))
    }

    override suspend fun deleteVehicle(vehicleId: String) {
        call(DELETE_VEHICLE, mapOf<String, Any?>("vehicleId" to vehicleId))
    }

    /**
     * Invokes [name] and returns its response map, or null when the callable
     * responded with absent or non-map data. Callers that need a field
     * (addVehicle's `vehicleId`) validate it themselves; the rest ignore the
     * result.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun call(name: String, data: Map<String, Any?>): Map<String, Any?>? {
        val result =
            functions.getHttpsCallable(name).call(data)
                .awaitOrThrow { "$name failed without a cause" }
        return result.getData() as? Map<String, Any?>
    }

    companion object {
        private const val VEHICLES = "vehicles"
        private const val REGION = "europe-west1"
        private const val ADD_VEHICLE = "garage-addVehicle"
        private const val UPDATE_VEHICLE = "garage-updateVehicle"
        private const val SET_MAIN_VEHICLE = "garage-setMainVehicle"
        private const val DELETE_VEHICLE = "garage-deleteVehicle"

        fun createIfAvailable(context: Context): GarageRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseGarageRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(REGION),
            )
        }
    }
}

private fun VehicleInput.toData(): Map<String, Any?> =
    mapOf(
        "make" to make,
        "model" to model,
        "modelYear" to modelYear,
        "powertrain" to powertrain.wire,
        // Always sent (possibly null) so an edit can CLEAR the description —
        // the backend accepts explicit null for engineDescription.
        "engineDescription" to engineDescription,
        // "modifications" is stored in the existing free-text `description`
        // field (garage-core); always sent (possibly null) so an edit can clear.
        "description" to modifications,
    )

private fun DocumentSnapshot.toVehicle(): Vehicle? {
    if (!exists()) return null
    val make = getString("make") ?: return null
    val model = getString("model") ?: return null
    val powertrain = VehiclePowertrain.fromWire(getString("powertrain")) ?: return null
    val modelYear = (get("modelYear") as? Number)?.toInt() ?: return null
    return Vehicle(
        id = id,
        make = make,
        model = model,
        modelYear = modelYear,
        powertrain = powertrain,
        engineDescription = getString("engineDescription"),
        modifications = getString("description"),
        imagePath = getString("imagePath"),
        isMainCar = getBoolean("isMainCar") ?: false,
    )
}
