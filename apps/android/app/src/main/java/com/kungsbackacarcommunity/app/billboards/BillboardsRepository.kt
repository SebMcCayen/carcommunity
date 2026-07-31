package com.kungsbackacarcommunity.app.billboards

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/** UI-facing state of the billboards list. */
sealed interface BillboardsState {
    data object Loading : BillboardsState

    data object Error : BillboardsState

    data class Loaded(val billboards: List<Billboard>) : BillboardsState
}

/** Billboards read + interaction (Phase 12 slice 20). Firebase-free for tests. */
interface BillboardsRepository {
    fun observeActiveBillboards(): Flow<BillboardsState>

    suspend fun recordInteraction(billboardId: String, type: BillboardInteractionType)
}

/**
 * [BillboardsRepository] backed by a Firestore listener on map-visible
 * billboards plus the billboards-recordInteraction callable (europe-west1).
 * Guarded.
 *
 * ## Why a listener, and not a viewport query
 *
 * The incidents layer re-queries `incidents-listNearby` as the camera settles,
 * gated by [com.kungsbackacarcommunity.app.incidents.CameraRequeryDecision], and
 * that is right for incidents: there are potentially thousands of them, they
 * are only interesting near you, and they change by the minute.
 *
 * Billboards are the opposite of all three. Every one of them is a human
 * decision an admin pushed through a six-point safety gate, so the live
 * population is dozens; they change when an admin acts or a scheduled window
 * turns over, not continuously; and the whole set fits comfortably inside the
 * [Billboards.ACTIVE_BILLBOARDS_QUERY_LIMIT] cap. Coupling them to the camera
 * would therefore buy nothing and cost a query per settled pan — on a surface
 * that pans constantly, with no billing alert on this project.
 *
 * So: ONE bounded snapshot listener, attached while the map is composed and
 * never re-issued for a camera move. Firestore charges the full document count
 * once when the listener attaches and then only for documents that actually
 * change, so a 30-minute session costs the cap in reads (150 worst case, in
 * practice the real population) plus one read per admin action or window
 * turnover — versus the tens-to-hundreds a camera-driven query would run up.
 * The sweep is deliberately change-only for the same reason: a no-op write
 * would push a delta to every listening device.
 *
 * ## The filter is the server's, not this class's
 *
 * The query constrains `status == 'active'` AND `mapVisible == true` — the
 * server-owned pair described in `functions/src/billboards/billboards-core.ts`,
 * and exactly what the read rule requires. So this is NOT a client-side
 * visibility filter that could be relaxed to reveal more: widening the query
 * gets permission-denied rather than more billboards, because a `list` rule is
 * evaluated against the query's own constraints.
 *
 * Needs the `status ASC, mapVisible ASC, createdAt ASC` composite index (added
 * alongside the pre-existing `status ASC, createdAt ASC` one) — a missing
 * composite index ERRORS rather than returning an empty list, so a missed index
 * deploy surfaces as [BillboardsState.Error], not a silently empty map.
 */
class FirebaseBillboardsRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : BillboardsRepository {

    override fun observeActiveBillboards(): Flow<BillboardsState> = callbackFlow {
        val registration =
            firestore
                .collection(BILLBOARDS)
                // BOTH constraints, and both are load-bearing. They mirror the
                // read rule exactly, and they HAVE to: Firestore evaluates a
                // `list` rule against the QUERY's constraints, not against the
                // documents it returns, so a field the rule reads and the query
                // does not filter on is undefined at evaluation time and the
                // whole query fails with permission-denied. Dropping either
                // `where` below does not widen the result set — it takes the
                // billboard layer down completely.
                .whereEqualTo(STATUS, STATUS_ACTIVE)
                .whereEqualTo(MAP_VISIBLE, true)
                .orderBy(CREATED_AT, Query.Direction.ASCENDING)
                .limit(Billboards.ACTIVE_BILLBOARDS_QUERY_LIMIT)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(BillboardsState.Error)
                        return@addSnapshotListener
                    }
                    trySend(
                        BillboardsState.Loaded(
                            snapshot?.documents?.mapNotNull { it.toBillboard() } ?: emptyList(),
                        ),
                    )
                }
        awaitClose { registration.remove() }
    }

    override suspend fun recordInteraction(billboardId: String, type: BillboardInteractionType) {
        val data = mapOf("billboardId" to billboardId, "interactionType" to type.wire)
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(RECORD_INTERACTION)
                .call(data)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("$RECORD_INTERACTION failed without a cause"),
                        )
                    }
                }
        }
    }

    companion object {
        private const val BILLBOARDS = "billboards"
        private const val CREATED_AT = "createdAt"
        private const val MAP_VISIBLE = "mapVisible"
        private const val STATUS = "status"
        private const val STATUS_ACTIVE = "active"
        private const val REGION = "europe-west1"
        private const val RECORD_INTERACTION = "billboards-recordInteraction"

        fun createIfAvailable(context: Context): BillboardsRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseBillboardsRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(REGION),
            )
        }
    }
}

/**
 * One `billboards/{id}` document → a [Billboard], or null when it is not
 * something the map can draw.
 *
 * A missing headline or a missing coordinate drops the document rather than
 * yielding a marker with no label or nowhere to stand. Both are contract
 * violations (the create callable requires all three), so this is defence
 * against a hand-edited or half-migrated document, not an expected path.
 */
private fun DocumentSnapshot.toBillboard(): Billboard? {
    if (!exists()) return null
    val headline = getString("headline") ?: return null
    val latitude = getDouble("latitude") ?: return null
    val longitude = getDouble("longitude") ?: return null
    return Billboard(
        id = id,
        headline = headline,
        message = getString("message"),
        companyId = getString("partnerCompanyId"),
        latitude = latitude,
        longitude = longitude,
        callToActionType = BillboardCtaType.fromWire(getString("callToActionType")),
        callToActionValue = getString("callToActionValue"),
        availableFromMillis = getTimestamp("availableFrom")?.toDate()?.time,
        availableUntilMillis = getTimestamp("availableUntil")?.toDate()?.time,
    )
}
