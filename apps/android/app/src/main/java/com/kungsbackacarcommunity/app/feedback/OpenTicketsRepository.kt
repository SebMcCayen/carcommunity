package com.kungsbackacarcommunity.app.feedback

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Reads the member-readable `openTickets` mirror and drives the
 * `feedback-interactWithIssue` callable. Split into an interface so the
 * coordinator is testable with a fake (pure JVM) and a Firebase-backed
 * implementation ([FirebaseOpenTicketsRepository]) that owns all Firestore /
 * Functions I/O.
 */
interface OpenTicketsRepository {
    /**
     * Live stream of open tickets, newest issue first. Emits on every mirror
     * change; a listener error before the first snapshot surfaces as
     * [OpenTicketsListState.Error], while an error AFTER a good load keeps the
     * last value (Firestore reconnects and re-delivers).
     */
    fun observe(): Flow<OpenTicketsListState>

    /**
     * Registers a +1 (fixed template comment) or posts a member comment on the
     * public issue. The result is collapsed from the callable's HttpsError code;
     * see [TicketInteractOutcome].
     */
    suspend fun interact(
        issueNumber: Int,
        type: TicketInteractionType,
        text: String?,
        clientId: String,
    ): TicketInteractOutcome
}

/**
 * [OpenTicketsRepository] backed by Firestore (`openTickets`) + the
 * europe-west1 `feedback-interactWithIssue` callable. Guarded construction
 * ([createIfAvailable] returns null when Firebase is not configured — CI /
 * validation builds — mirroring the rest of the wiring).
 */
class FirebaseOpenTicketsRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : OpenTicketsRepository {

    override fun observe(): Flow<OpenTicketsListState> = callbackFlow {
        // Once a good snapshot has arrived, a later transient listener error must
        // NOT clobber the shown list back to an error screen (Firestore reconnects
        // and re-delivers). Error is surfaced ONLY before the first Loaded.
        var hasLoaded = false
        // Backend-write, member-read. Order by issue number DESC so the newest
        // reported problem is on top. A missing composite index is not a risk:
        // this is a single-field order on the doc's own `number`.
        val registration =
            firestore
                .collection(OPEN_TICKETS)
                .orderBy("number", Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        if (!hasLoaded) trySend(OpenTicketsListState.Error)
                        return@addSnapshotListener
                    }
                    val tickets =
                        snapshot?.documents.orEmpty().mapNotNull { doc ->
                            val number = (doc.getLong("number") ?: doc.id.toLongOrNull())?.toInt()
                                ?: return@mapNotNull null
                            val htmlUrl = doc.getString("htmlUrl") ?: return@mapNotNull null
                            OpenTicket(
                                number = number,
                                title = doc.getString("title").orEmpty(),
                                summary = doc.getString("summary").orEmpty(),
                                htmlUrl = htmlUrl,
                                plusOneCount = (doc.getLong("plusOneCount") ?: 0L).toInt(),
                                commentCount = (doc.getLong("commentCount") ?: 0L).toInt(),
                            )
                        }
                    hasLoaded = true
                    trySend(OpenTicketsListState.Loaded(tickets))
                }
        awaitClose { registration.remove() }
    }

    override suspend fun interact(
        issueNumber: Int,
        type: TicketInteractionType,
        text: String?,
        clientId: String,
    ): TicketInteractOutcome {
        val data =
            buildMap<String, Any> {
                put("issueNumber", issueNumber)
                put("type", if (type == TicketInteractionType.PLUS_ONE) "plus_one" else "comment")
                put("clientId", clientId)
                if (type == TicketInteractionType.COMMENT && text != null) put("text", text)
            }
        return suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(CALLABLE)
                .call(data)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(TicketInteractOutcome.POSTED)
                        return@addOnCompleteListener
                    }
                    val code = (task.exception as? FirebaseFunctionsException)?.code
                    val outcome =
                        when (code) {
                            // Duplicate / issue-closed / feature-off all arrive as
                            // failed-precondition and all mean "stop offering this".
                            FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
                                TicketInteractOutcome.ALREADY_DONE

                            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                                TicketInteractOutcome.RATE_LIMITED

                            else -> TicketInteractOutcome.FAILED
                        }
                    continuation.resume(outcome)
                }
        }
    }

    companion object {
        private const val OPEN_TICKETS = "openTickets"
        private const val CALLABLE = "feedback-interactWithIssue"
        private const val REGION = "europe-west1"

        fun createIfAvailable(context: Context): OpenTicketsRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseOpenTicketsRepository(
                firestore = FirebaseFirestore.getInstance(),
                functions = FirebaseFunctions.getInstance(REGION),
            )
        }
    }
}
