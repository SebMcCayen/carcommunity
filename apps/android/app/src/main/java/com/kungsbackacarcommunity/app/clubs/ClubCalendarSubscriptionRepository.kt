package com.kungsbackacarcommunity.app.clubs

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class CalendarSubscription(val active: Boolean, val version: Long, val url: String? = null)

internal interface ClubCalendarSubscriptionRepository {
    suspend fun manage(clubId: String, action: String, expectedVersion: Long? = null): CalendarSubscription
}

/** A server reply must not turn the copy action into an arbitrary external URL. */
internal fun parseCalendarSubscription(raw: Any?, project: String): CalendarSubscription {
    val data = raw.clubMap()
    val active = data["active"] as? Boolean ?: throw ClubException("unavailable")
    val number = (data["version"] as? Number)?.toDouble() ?: throw ClubException("unavailable")
    if (!number.isFinite() || number < 0 || number > 9_007_199_254_740_991.0 || number != number.toLong().toDouble()) {
        throw ClubException("unavailable")
    }
    if (data["url"] != null && data["url"] !is String) throw ClubException("unavailable")
    val url = data["url"] as? String
    if (url != null) {
        val uri = runCatching { URI(url) }.getOrNull() ?: throw ClubException("unavailable")
        if (!active || !Regex("[a-z][a-z0-9-]{4,61}[a-z0-9]").matches(project) ||
            uri.scheme != "https" || uri.host != "europe-west1-$project.cloudfunctions.net" ||
            uri.port != -1 || uri.rawUserInfo != null || uri.rawFragment != null ||
            uri.rawPath != "/clubCalendar-feed/calendar.ics" ||
            !Regex("token=[A-Za-z0-9_-]{43}").matches(uri.rawQuery.orEmpty())) throw ClubException("unavailable")
    }
    return CalendarSubscription(active, number.toLong(), url)
}

internal class FirebaseCalendarSubscriptionRepository private constructor(
    private val uid: String, private val project: String,
    private val auth: FirebaseAuth, private val functions: FirebaseFunctions,
) : ClubCalendarSubscriptionRepository {
    override suspend fun manage(clubId: String, action: String, expectedVersion: Long?): CalendarSubscription {
        if (auth.currentUser?.uid != uid) throw ClubException("unauthenticated")
        val fields = mutableMapOf<String, Any>("clubId" to clubId, "action" to action)
        expectedVersion?.let { fields["expectedVersion"] = it }
        val result = suspendCancellableCoroutine<Any?> { continuation ->
            functions.getHttpsCallable("clubCalendar-manage").call(fields).addOnCompleteListener { task ->
                if (continuation.isActive) {
                    if (task.isSuccessful) continuation.resume(task.result.data)
                    else {
                        val error = task.exception
                        continuation.resumeWithException(
                            if ((error as? FirebaseFunctionsException)?.code == FirebaseFunctionsException.Code.ABORTED)
                                ClubException("calendar-changed")
                            else error ?: ClubException("unavailable"),
                        )
                    }
                }
            }
        }
        if (auth.currentUser?.uid != uid) throw ClubException("unauthenticated")
        val subscription = parseCalendarSubscription(result, project)
        if (action == "issue" && subscription.url == null) throw ClubException("unavailable")
        return subscription
    }

    companion object {
        fun createIfAvailable(): ClubCalendarSubscriptionRepository? = runCatching {
            val app = FirebaseApp.getInstance()
            val auth = FirebaseAuth.getInstance(app)
            val uid = auth.currentUser?.uid ?: return null
            val project = app.options.projectId ?: return null
            FirebaseCalendarSubscriptionRepository(uid, project, auth, FirebaseFunctions.getInstance(app, "europe-west1"))
        }.getOrNull()
    }
}
