package com.kungsbackacarcommunity.app.partners

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [PartnersRepository] backed by Firestore listeners (active companies/offers,
 * member offer detail, saved bookmarks) and the partners-showOfferCode callable
 * (europe-west1), Phase 12 slice 17. Guarded ([createIfAvailable]).
 *
 * The active-companies and active-offers listeners are bounded to
 * [Partners.ACTIVE_COMPANIES_QUERY_LIMIT] / [Partners.ACTIVE_OFFERS_QUERY_LIMIT]
 * (createdAt descending) — see those constants' KDoc for the new composite
 * indexes this requires.
 */
class FirebasePartnersRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : PartnersRepository {

    override fun observeActiveCompanies(): Flow<CompaniesState> = callbackFlow {
        val registration =
            firestore
                .collection(COMPANIES)
                .whereEqualTo("status", "active")
                .orderBy(CREATED_AT, Query.Direction.DESCENDING)
                .limit(Partners.ACTIVE_COMPANIES_QUERY_LIMIT)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(CompaniesState.Error)
                        return@addSnapshotListener
                    }
                    val companies = snapshot?.documents?.mapNotNull { it.toCompany() } ?: emptyList()
                    // Locale.ROOT: stable, device-locale-independent ordering.
                    trySend(CompaniesState.Loaded(companies.sortedBy { it.name.lowercase(Locale.ROOT) }))
                }
        awaitClose { registration.remove() }
    }

    override fun observeActiveOffers(): Flow<List<PartnerOffer>> = callbackFlow {
        val registration =
            firestore
                .collection(OFFERS)
                .whereEqualTo("status", "active")
                .orderBy(CREATED_AT, Query.Direction.DESCENDING)
                .limit(Partners.ACTIVE_OFFERS_QUERY_LIMIT)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        // Keep the last known offers on a transient listener error
                        // rather than flickering to an empty ("no offers") list.
                        return@addSnapshotListener
                    }
                    trySend(snapshot?.documents?.mapNotNull { it.toOffer() } ?: emptyList())
                }
        awaitClose { registration.remove() }
    }

    override fun observeOfferDetail(offerId: String): Flow<OfferMemberDetail?> = callbackFlow {
        val registration =
            firestore
                .collection(OFFERS)
                .document(offerId)
                .collection(DETAILS)
                .document(MEMBER)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(null)
                        return@addSnapshotListener
                    }
                    trySend(snapshot?.toOfferDetail())
                }
        awaitClose { registration.remove() }
    }

    override fun observeSavedOfferIds(uid: String): Flow<Set<String>> = callbackFlow {
        val registration =
            firestore
                .collection(USERS)
                .document(uid)
                .collection(SAVED_OFFERS)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(emptySet())
                        return@addSnapshotListener
                    }
                    trySend(snapshot?.documents?.map { it.id }?.toSet() ?: emptySet())
                }
        awaitClose { registration.remove() }
    }

    override suspend fun showOfferCode(offerId: String): String? =
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(SHOW_OFFER_CODE)
                .call(mapOf("offerId" to offerId))
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        @Suppress("UNCHECKED_CAST")
                        val data = task.result?.getData() as? Map<String, Any?>
                        continuation.resume(data?.get("code") as? String)
                    } else {
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("showOfferCode failed without a cause"),
                        )
                    }
                }
        }

    override suspend fun setSaved(uid: String, offerId: String, saved: Boolean) {
        val docRef =
            firestore.collection(USERS).document(uid).collection(SAVED_OFFERS).document(offerId)
        suspendCancellableCoroutine { continuation ->
            val task =
                if (saved) {
                    docRef.set(mapOf("offerId" to offerId, "savedAt" to FieldValue.serverTimestamp()))
                } else {
                    docRef.delete()
                }
            task.addOnCompleteListener { completed ->
                if (!continuation.isActive) return@addOnCompleteListener
                if (completed.isSuccessful) {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(
                        completed.exception ?: IllegalStateException("savedOffers write failed without a cause"),
                    )
                }
            }
        }
    }

    companion object {
        private const val COMPANIES = "companies"
        private const val OFFERS = "offers"
        private const val CREATED_AT = "createdAt"
        private const val DETAILS = "details"
        private const val MEMBER = "member"
        private const val USERS = "users"
        private const val SAVED_OFFERS = "savedOffers"
        private const val REGION = "europe-west1"
        private const val SHOW_OFFER_CODE = "partners-showOfferCode"

        fun createIfAvailable(context: Context): PartnersRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebasePartnersRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(REGION),
            )
        }
    }
}

private fun DocumentSnapshot.toCompany(): PartnerCompany? {
    if (!exists()) return null
    val name = getString("name") ?: return null
    return PartnerCompany(
        id = id,
        name = name,
        category = PartnerCategory.fromWire(getString("category")),
        description = getString("description"),
        website = getString("website"),
        phone = getString("phone"),
        latitude = getDouble("latitude"),
        longitude = getDouble("longitude"),
    )
}

private fun DocumentSnapshot.toOffer(): PartnerOffer? {
    if (!exists()) return null
    val companyId = getString("companyId") ?: return null
    val title = getString("title") ?: return null
    return PartnerOffer(
        id = id,
        companyId = companyId,
        title = title,
        teaserText = getString("teaserText") ?: "",
        offerType = PartnerOfferType.fromWire(getString("offerType")),
    )
}

private fun DocumentSnapshot.toOfferDetail(): OfferMemberDetail? {
    if (!exists()) return null
    return OfferMemberDetail(
        description = getString("description"),
        redemptionInstructions = getString("redemptionInstructions"),
        terms = getString("terms"),
    )
}
