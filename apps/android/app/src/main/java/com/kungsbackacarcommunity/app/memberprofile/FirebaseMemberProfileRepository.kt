package com.kungsbackacarcommunity.app.memberprofile

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.QuerySnapshot
import com.kungsbackacarcommunity.app.badges.Badge
import com.kungsbackacarcommunity.app.badges.Badges
import com.kungsbackacarcommunity.app.firebase.await
import com.kungsbackacarcommunity.app.garage.Vehicle
import com.kungsbackacarcommunity.app.garage.VehiclePowertrain
import com.kungsbackacarcommunity.app.navigation.runCatchingCancellable
import com.kungsbackacarcommunity.app.profile.SocialHandles

/**
 * [MemberProfileRepository] backed by one-shot Firestore reads of another
 * member's publicly-readable docs, Guarded ([createIfAvailable]).
 *
 * Reads (all `get()`, no listeners):
 *  - users/{targetUid} — public profile (rules: any authenticated user).
 *  - vehicles where userId == targetUid — the member's garage (rules: any
 *    authenticated user).
 *  - users/{targetUid}/badges — the member's badge wall (rules: any
 *    authenticated user; badges are public so achievements can be shown off).
 *    Still read best-effort: a PERMISSION_DENIED maps to
 *    [MemberBadges.Unavailable] and any other failure to [MemberBadges.Unknown],
 *    so an older deployed ruleset or a transient error degrades to a note
 *    instead of failing the whole profile.
 *  - pointsLedger/{targetUid} — the member's PUBLIC Kronpoäng balance (rules:
 *    the balance document is readable by any authenticated user; the `entries`
 *    subcollection behind it stays owner-only, so it is not read). Best-effort:
 *    any failure degrades to a null balance ("0 p"), never failing the profile.
 *
 * NOT read: `badgeProgress/{targetUid}`, nor `pointsLedger/{targetUid}/entries`. The counters a badge was earned against
 * (streak, lifetime distance, meets attended, crowns) are denied to every
 * client, and the member-profile screen shows trophies only — no progress.
 */
class FirebaseMemberProfileRepository private constructor(
    private val firestore: FirebaseFirestore,
) : MemberProfileRepository {

    override suspend fun loadMemberProfile(targetUid: String): MemberProfileResult {
        if (targetUid.isBlank()) return MemberProfileResult.NotFound

        val profileSnapshot =
            runCatchingCancellable { firestore.collection(USERS).document(targetUid).get().await() }
                .getOrElse { return MemberProfileResult.Error }
        val profile = profileSnapshot.toMemberProfile() ?: return MemberProfileResult.NotFound

        val vehicles =
            runCatchingCancellable {
                firestore
                    .collection(VEHICLES)
                    .whereEqualTo("userId", targetUid)
                    .get()
                    .await()
                    .toVehicles()
            }
                // A garage read failure degrades to an empty list rather than
                // failing the whole profile — name/avatar/bio still render.
                .getOrDefault(emptyList())

        // Badges are publicly readable, so this is expected to SUCCEED. The two
        // failure shapes are still told apart: a PERMISSION_DENIED (an older
        // deployed ruleset, or visibility narrowed again later) is definitive
        // and collapses to the "not available" note, while any OTHER failure
        // (offline, timeout, misconfig) is transient and maps to Unknown. The
        // rest of the profile still renders in both cases.
        val badges: MemberBadges =
            runCatchingCancellable {
                firestore
                    .collection(USERS)
                    .document(targetUid)
                    .collection(BADGES)
                    .get()
                    .await()
                    .toBadges()
            }
                .fold(
                    onSuccess = { MemberBadges.Available(it) },
                    onFailure = { error ->
                        if ((error as? FirebaseFirestoreException)?.code ==
                            FirebaseFirestoreException.Code.PERMISSION_DENIED
                        ) {
                            MemberBadges.Unavailable
                        } else {
                            MemberBadges.Unknown
                        }
                    },
                )

        // Public Kronpoäng balance from pointsLedger/{targetUid}.balance (the
        // DOCUMENT is authenticated-readable; the entries subcollection behind it
        // stays owner-only, so it is deliberately NOT read here). Best-effort:
        // any failure — an older ruleset that has not been deployed yet, offline,
        // or simply no wallet — degrades to null, which the profile renders as
        // "0 p" rather than failing the whole page.
        val pointsBalance: Long? =
            runCatchingCancellable {
                firestore
                    .collection(POINTS_LEDGER)
                    .document(targetUid)
                    .get()
                    .await()
                    .let { snapshot -> (snapshot.get("balance") as? Number)?.toLong() }
            }
                .getOrNull()

        return MemberProfileResult.Loaded(profile, vehicles, badges, pointsBalance)
    }

    companion object {
        private const val USERS = "users"
        private const val VEHICLES = "vehicles"
        private const val BADGES = "badges"
        private const val POINTS_LEDGER = "pointsLedger"

        fun createIfAvailable(context: Context): MemberProfileRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseMemberProfileRepository(FirebaseFirestore.getInstance())
        }
    }
}

private fun DocumentSnapshot.toMemberProfile(): MemberProfile? {
    if (!exists()) return null
    return MemberProfile(
        uid = id,
        displayName = getString("displayName"),
        bio = getString("bio"),
        avatarPath = getString("avatarPath"),
        // Stored verbatim; SocialLinks re-validates every handle at RENDER time
        // (SocialLinks.links), so a document written before the social rules
        // were deployed still cannot produce a link this app did not build.
        social =
            SocialHandles(
                facebook = getString("facebook"),
                instagram = getString("instagram"),
                youtube = getString("youtube"),
            ),
        // Public join date — the only figure the member "Stats" section shows.
        createdAtMillis = getTimestamp("createdAt")?.toDate()?.time,
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
        // Carry the catalogue ids here TOO. Another member's car is rendered by
        // the same VehicleCard, which resolves its headline through
        // VehicleDisplay — dropping the ids would show the stored English
        // placeholder for an "Other / not listed" car instead of the viewer's own
        // localized label. (The plate had exactly this bug once; same shape.)
        makeId = getString("makeId"),
        modelId = getString("modelId"),
        modelYear = modelYear,
        powertrain = powertrain,
        engineDescription = getString("engineDescription"),
        modifications = getString("description"),
        // Deliberately public (see Vehicle.registrationPlate): vehicles/{id} is
        // readable by any signed-in user, so this viewer could read the plate
        // regardless — dropping it here only hid it from the UI.
        registrationPlate = getString("registrationPlate"),
        imagePath = getString("imagePath"),
        photoPaths = (get("photoPaths") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
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
