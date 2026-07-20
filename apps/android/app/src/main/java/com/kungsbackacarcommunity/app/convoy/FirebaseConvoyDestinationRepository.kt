package com.kungsbackacarcommunity.app.convoy

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions

/**
 * [ConvoyDestinationRepository] backed by the convoy destination callables —
 * **which are not deployed yet**.
 *
 * ## Read this before wiring it up
 * `convoy-setDestination` and `convoy-clearDestination` DO NOT EXIST in the
 * deployed backend today (see the contract spelled out in
 * [ConvoyDestination.kt]'s file KDoc). This class is the real implementation,
 * written against that contract and ready, but nothing constructs it while
 * [ConvoyDestinations.availability] is
 * [ConvoyDestinationAvailability.BackendMissing] — the app is handed
 * [UnavailableConvoyDestinationRepository] instead, and the bar's controls render
 * disabled with an honest explanation.
 *
 * Calling it before the callables ship would produce a `NOT_FOUND` from the
 * Functions SDK, which [ConvoyErrorMapper.mapSetDestination] would translate into
 * "convoy not found" — a plainly wrong message for "this feature does not exist".
 * That is exactly the failure mode the availability flag prevents.
 *
 * ## When the backend lands
 * Deploy the two callables, flip [ConvoyDestinations.availability] to
 * [ConvoyDestinationAvailability.Wired], and swap
 * [UnavailableConvoyDestinationRepository] for [createIfAvailable] at the one
 * construction site in `AuthenticatedApp`. Nothing else in the client changes.
 *
 * Guarded ([createIfAvailable]) like its sibling, so a config-less build gets a
 * null repository rather than a crash.
 */
class FirebaseConvoyDestinationRepository private constructor(
    private val functions: FirebaseFunctions,
) : ConvoyDestinationRepository {

    override suspend fun setDestination(
        convoyId: String,
        latitude: Double,
        longitude: Double,
        label: String?,
    ): ConvoyDestinationResult {
        // Client-side bounds check purely so an obviously bad coordinate never
        // becomes a round-trip; the server re-validates and remains the gate.
        if (!ConvoyDestinations.isValidCoordinate(latitude, longitude)) {
            return ConvoyDestinationResult.Failed(ConvoyActionError.Invalid)
        }
        val normalizedLabel = ConvoyDestinations.normalizeLabel(label)
        return functions.callConvoyFunction(
            SET_DESTINATION,
            buildMap {
                put("convoyId", convoyId)
                put("latitude", latitude)
                put("longitude", longitude)
                if (normalizedLabel != null) put("label", normalizedLabel)
            },
        ).fold(
            onSuccess = { data ->
                when (val parsed = ConvoyResponseParser.parseMutation(data)) {
                    is ConvoyMutationResult.Updated ->
                        ConvoyDestinationResult.Updated(parsed.convoy)
                    is ConvoyMutationResult.Failed ->
                        ConvoyDestinationResult.Failed(parsed.error)
                }
            },
            onFailure = {
                ConvoyDestinationResult.Failed(
                    ConvoyErrorMapper.mapSetDestination(it.toErrorCode()),
                )
            },
        )
    }

    override suspend fun clearDestination(convoyId: String): ConvoyDestinationResult =
        functions.callConvoyFunction(CLEAR_DESTINATION, mapOf("convoyId" to convoyId)).fold(
            onSuccess = { data ->
                when (val parsed = ConvoyResponseParser.parseMutation(data)) {
                    is ConvoyMutationResult.Updated ->
                        ConvoyDestinationResult.Updated(parsed.convoy)
                    is ConvoyMutationResult.Failed ->
                        ConvoyDestinationResult.Failed(parsed.error)
                }
            },
            onFailure = {
                ConvoyDestinationResult.Failed(
                    ConvoyErrorMapper.mapClearDestination(it.toErrorCode()),
                )
            },
        )

    companion object {
        private const val REGION = "europe-west1"
        private const val SET_DESTINATION = "convoy-setDestination"
        private const val CLEAR_DESTINATION = "convoy-clearDestination"

        fun createIfAvailable(context: Context): ConvoyDestinationRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseConvoyDestinationRepository(FirebaseFunctions.getInstance(REGION))
        }
    }
}
