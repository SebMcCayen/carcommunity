package com.kungsbackacarcommunity.app.badges

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.firebase.awaitOrThrow

/**
 * Fetches the signed-in member's OWN server-verified ladder counters, so the
 * own-profile badge wall can draw a progress bar on every ladder (issue #799).
 *
 * The authoritative counters live on the backend-only `badgeProgress/{uid}`
 * document, which `firebase/firestore.rules` denies to EVERY client — the owner
 * included. Rather than relax that rule, the counters are handed to the OWN
 * client by the owner-only `badges-getMyProgress` callable, which returns a
 * read-only projection of just this member's numbers (the uid is taken from the
 * auth context server-side, never from the request, so it can only ever be the
 * caller's own). Another member's wall gets no counters at all — see
 * [PublicBadgeWall] — so progress bars stay an own-profile affordance.
 *
 * Firebase-free interface for testability; the raw payload→[BadgeCounters]
 * projection is [BadgeProgressResponseParser], unit-tested off-device.
 */
interface BadgeProgressRepository {
    /**
     * The caller's own counters, or null when they cannot be fetched (any
     * callable failure — offline, App Check, an unexpected payload). A null
     * simply leaves the ladders bar-less; it is never surfaced as an error,
     * because the wall is fully usable (trophies + goals) without the bars.
     */
    suspend fun fetchMyProgress(): BadgeCounters?
}

/**
 * [BadgeProgressRepository] backed by the owner-only `badges-getMyProgress`
 * callable (europe-west1). Guarded ([createIfAvailable]) so a config-less build
 * gets a null repository, which the profile route turns into absent counters and
 * a bar-less-but-complete wall.
 */
class FirebaseBadgeProgressRepository private constructor(
    private val functions: FirebaseFunctions,
) : BadgeProgressRepository {

    override suspend fun fetchMyProgress(): BadgeCounters? =
        try {
            val data = callForData(GET_MY_PROGRESS)
            BadgeProgressResponseParser.parse(data)
        } catch (error: Exception) {
            // Any failure (FirebaseFunctionsException, App Check, empty payload)
            // degrades to "no counters", which the wall renders as goals without
            // bars. CancellationException must propagate so a cancelled profile
            // route really cancels the in-flight call rather than swallowing it.
            if (error is kotlinx.coroutines.CancellationException) throw error
            null
        }

    private suspend fun callForData(name: String): Map<String, Any?> {
        // Same Task->suspend bridge every FirebaseFunctions repo uses (see
        // firebase/TaskAwait.kt): awaitOrThrow observes a cancelled Task as a
        // failure rather than hanging, and rethrows the callable's own exception
        // (FirebaseFunctionsException) so the caller's code/App-Check handling
        // still applies.
        val result =
            functions
                .getHttpsCallable(name)
                .call(emptyMap<String, Any?>())
                .awaitOrThrow { "$name failed without a cause" }
        @Suppress("UNCHECKED_CAST")
        val data = result?.getData() as? Map<String, Any?>
        // A 2xx carrying no Map payload is a protocol fault, not an answer;
        // surfacing it (rather than degrading to empty) keeps a broken contract
        // from silently reading as "no counters".
        return data ?: throw IllegalStateException("$name returned an unexpected or empty payload")
    }

    companion object {
        private const val REGION = "europe-west1"
        private const val GET_MY_PROGRESS = "badges-getMyProgress"

        fun createIfAvailable(context: Context): BadgeProgressRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseBadgeProgressRepository(FirebaseFunctions.getInstance(REGION))
        }
    }
}

/**
 * Projects the `badges-getMyProgress` payload into [BadgeCounters]. Pure and
 * defensive: a counter that is missing, non-numeric, non-finite or negative is
 * read as null (no bar) rather than a fabricated one — even though the callable
 * already sanitises server-side, the client never trusts the wire shape blindly.
 */
object BadgeProgressResponseParser {
    fun parse(data: Map<String, Any?>): BadgeCounters =
        BadgeCounters(
            crownsCollected = data.counter("crownsCollected"),
            lifetimeDistanceMeters = data.counter("lifetimeDistanceMeters"),
            verifiedEventsAttended = data.counter("verifiedEventsAttended"),
            bestDayStreak = data.counter("bestDayStreak"),
            convoysLed = data.counter("convoysLed"),
            vehiclesInGarage = data.counter("vehiclesInGarage"),
            seasonsWon = data.counter("seasonsWon"),
        )

    /**
     * One counter from the payload as a non-negative Long, or null. Firebase
     * decodes JSON numbers as [Int]/[Long]/[Double]; a Double is trusted only
     * when finite and is floored to match the server's integer counter.
     */
    private fun Map<String, Any?>.counter(key: String): Long? {
        val value = this[key] as? Number ?: return null
        val asLong =
            when (value) {
                is Double -> if (value.isFinite()) kotlin.math.floor(value).toLong() else return null
                is Float -> if (value.isFinite()) kotlin.math.floor(value.toDouble()).toLong() else return null
                else -> value.toLong()
            }
        return asLong.takeIf { it >= 0 }
    }
}
