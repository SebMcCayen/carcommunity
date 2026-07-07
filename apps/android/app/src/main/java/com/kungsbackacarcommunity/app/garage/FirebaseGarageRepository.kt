package com.kungsbackacarcommunity.app.garage

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [GarageRepository] backed by an owner Firestore query on `vehicles` plus the
 * garage-addVehicle / updateVehicle / deleteVehicle callables (europe-west1),
 * Phase 12 slice 13. Guarded ([createIfAvailable]).
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

    override suspend fun addVehicle(input: VehicleInput) {
        call(ADD_VEHICLE, input.toData())
    }

    override suspend fun updateVehicle(vehicleId: String, input: VehicleInput) {
        call(UPDATE_VEHICLE, input.toData() + ("vehicleId" to vehicleId))
    }

    override suspend fun updateVehicleImagePath(vehicleId: String, imagePath: String) {
        // Partial update: only vehicleId + imagePath. The backend's
        // buildVehicleUpdate accepts an imagePath-only change.
        call(UPDATE_VEHICLE, mapOf("vehicleId" to vehicleId, "imagePath" to imagePath))
    }

    override suspend fun deleteVehicle(vehicleId: String) {
        call(DELETE_VEHICLE, mapOf<String, Any?>("vehicleId" to vehicleId))
    }

    private suspend fun call(name: String, data: Map<String, Any?>): Unit =
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(name)
                .call(data)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("$name failed without a cause"),
                        )
                    }
                }
        }

    companion object {
        private const val VEHICLES = "vehicles"
        private const val REGION = "europe-west1"
        private const val ADD_VEHICLE = "garage-addVehicle"
        private const val UPDATE_VEHICLE = "garage-updateVehicle"
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
        imagePath = getString("imagePath"),
    )
}
