package com.kungsbackacarcommunity.app.memberprofile

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.kungsbackacarcommunity.app.badges.Badge
import com.kungsbackacarcommunity.app.badges.Badges
import com.kungsbackacarcommunity.app.garage.Vehicle
import com.kungsbackacarcommunity.app.garage.VehiclePowertrain
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [MemberProfileRepository] backed by one-shot Firestore reads of another
 * member's publicly-readable docs, Guarded ([createIfAvailable]).
 *
 * Reads (all `get()`, no listeners):
 *  - users/{targetUid} — public profile (rules: any authenticated user).
 *  - vehicles where userId == targetUid — the member's garage (rules: any
 *    authenticated user).
 *  - users/{targetUid}/badges — best-effort. Under the current rules this
 *    subcollection is OWNER-ONLY read, so the query is denied for any other
 *    viewer; that denial is caught and mapped to [MemberBadges.Unavailable]
 *    (the rest of the profile still renders). Exposing another member's badges
 *    would require a backend rule/callable change (out of the Android lane).
 */
class FirebaseMemberProfileRepository private constructor(
    private val firestore: FirebaseFirestore,
) : MemberProfileRepository {

    override suspend fun loadMemberProfile(targetUid: String): MemberProfileResult {
        if (targetUid.isBlank()) return MemberProfileResult.NotFound

        val profileSnapshot =
            runCatching { firestore.collection(USERS).document(targetUid).get().awaitResult() }
                .getOrElse { return MemberProfileResult.Error }
        val profile = profileSnapshot.toMemberProfile() ?: return MemberProfileResult.NotFound

        val vehicles =
            runCatching {
                firestore
                    .collection(VEHICLES)
                    .whereEqualTo("userId", targetUid)
                    .get()
                    .awaitResult()
                    .toVehicles()
            }
                // A garage read failure degrades to an empty list rather than
                // failing the whole profile — name/avatar/bio still render.
                .getOrDefault(emptyList())

        // Badges are owner-only readable today: a denied read is EXPECTED, so we
        // treat any failure as "not available" rather than an error state.
        val badges: MemberBadges =
            runCatching {
                firestore
                    .collection(USERS)
                    .document(targetUid)
                    .collection(BADGES)
                    .get()
                    .awaitResult()
                    .toBadges()
            }
                .map { MemberBadges.Available(it) }
                .getOrDefault(MemberBadges.Unavailable)

        return MemberProfileResult.Loaded(profile, vehicles, badges)
    }

    companion object {
        private const val USERS = "users"
        private const val VEHICLES = "vehicles"
        private const val BADGES = "badges"

        fun createIfAvailable(context: Context): MemberProfileRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseMemberProfileRepository(FirebaseFirestore.getInstance())
        }
    }
}

/** Minimal Task -> suspend bridge (no kotlinx-coroutines-play-services dep). */
private suspend fun <T> Task<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }.addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

private fun DocumentSnapshot.toMemberProfile(): MemberProfile? {
    if (!exists()) return null
    return MemberProfile(
        uid = id,
        displayName = getString("displayName"),
        bio = getString("bio"),
        avatarPath = getString("avatarPath"),
    )
}

private fun QuerySnapshot.toVehicles(): List<Vehicle> =
    documents
        .mapNotNull { it.toVehicle() }
        .sortedWith(compareBy({ it.make.lowercase() }, { it.model.lowercase() }))

/** Mirrors FirebaseGarageRepository's owner mapping (kept local to decouple modules). */
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

private fun QuerySnapshot.toBadges(): List<Badge> =
    Badges.sortedForList(documents.mapNotNull { it.toBadge() })

private fun DocumentSnapshot.toBadge(): Badge? {
    if (!exists()) return null
    return Badge(
        key = getString("badgeKey") ?: id,
        fallbackName = getString("name"),
        awardedAtMillis = getTimestamp("awardedAt")?.toDate()?.time,
    )
}
