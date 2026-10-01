package com.kungsbackacarcommunity.app.partners

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
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

    override fun observeCompany(companyId: String): Flow<CompanyState> = callbackFlow {
        trySend(CompanyState.Loading)
        val registration =
            firestore
                .collection(COMPANIES)
                .whereEqualTo(FieldPath.documentId(), companyId)
                .whereEqualTo("status", "active")
                .limit(1)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(CompanyState.Error)
                        return@addSnapshotListener
                    }
                    val company = snapshot?.documents?.firstOrNull()?.toCompany()
                    trySend(company?.let(CompanyState::Loaded) ?: CompanyState.Missing)
                }
        awaitClose { registration.remove() }
    }

    override fun observeActiveOffers(): Flow<OffersState> = callbackFlow {
        var hasLoadedSnapshot = false
        val registration =
            firestore
                .collection(OFFERS)
                .whereEqualTo("status", "active")
                .orderBy(CREATED_AT, Query.Direction.DESCENDING)
                .limit(Partners.ACTIVE_OFFERS_QUERY_LIMIT)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        if (!hasLoadedSnapshot) trySend(OffersState.Error)
                        return@addSnapshotListener
                    }
                    hasLoadedSnapshot = true
                    val documents = snapshot?.documents.orEmpty()
                    trySend(
                        OffersState.Loaded(
                            offers = documents.mapNotNull { it.toOffer() },
                            // A full page may have more active offers behind it.
                            isExhaustive = documents.size.toLong() < Partners.ACTIVE_OFFERS_QUERY_LIMIT,
                        ),
                    )
                }
        awaitClose { registration.remove() }
    }

    override fun observeOffers(offerIds: Set<String>): Flow<OffersState> = callbackFlow {
        trySend(OffersState.Loading)
        if (offerIds.isEmpty()) {
            trySend(OffersState.Loaded(emptyList()))
            close()
            return@callbackFlow
        }
        val chunks = offerIds.toList().chunked(FIRESTORE_IN_LIMIT)
        val lock = Any()
        val snapshots = mutableMapOf<Int, List<PartnerOffer>>()
        var failedBeforeLoad = false
        val registrations =
            chunks.mapIndexed { index, ids ->
                firestore
                    .collection(OFFERS)
                    .whereEqualTo("status", "active")
                    .whereIn(FieldPath.documentId(), ids)
                    .addSnapshotListener { snapshot, error ->
                        synchronized(lock) {
                            if (error != null) {
                                if (snapshots.size < chunks.size && !failedBeforeLoad) {
                                    failedBeforeLoad = true
                                    trySend(OffersState.Error)
                                }
                                return@synchronized
                            }
                            snapshots[index] =
                                snapshot?.documents?.mapNotNull { document ->
                                    document.takeIf { it.getString("status") == "active" }?.toOffer()
                                } ?: emptyList()
                            if (snapshots.size == chunks.size) {
                                failedBeforeLoad = false
                                trySend(OffersState.Loaded(snapshots.toSortedMap().values.flatten()))
                            }
                        }
                    }
            }
        awaitClose { registrations.forEach { it.remove() } }
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

    override fun observeSavedOfferIds(uid: String): Flow<SavedOfferIdsState> = callbackFlow {
        var hasLoadedSnapshot = false
        val registration =
            firestore
                .collection(USERS)
                .document(uid)
                .collection(SAVED_OFFERS)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        if (!hasLoadedSnapshot) trySend(SavedOfferIdsState.Error)
                        return@addSnapshotListener
                    }
                    hasLoadedSnapshot = true
                    trySend(
                        SavedOfferIdsState.Loaded(
                            snapshot?.documents?.map { it.id }?.toSet() ?: emptySet(),
                        ),
                    )
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
        private const val FIRESTORE_IN_LIMIT = 30

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
        address = getString("address"),
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
        partnerCompanyName = getString("partnerCompanyName"),
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
