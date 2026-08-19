package com.kungsbackacarcommunity.app.chatchannels

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.kungsbackacarcommunity.app.blocking.BlockVisibility
import com.kungsbackacarcommunity.app.navigation.runCatchingCancellable
import com.kungsbackacarcommunity.app.profile.LiveProfileRepository
import com.kungsbackacarcommunity.app.profile.LiveProfiles
import kotlin.coroutines.resume
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** The europe-west1 region every chat-channels callable is deployed to. */
internal const val CHANNEL_FUNCTIONS_REGION = "europe-west1"

/**
 * Invokes a chat-channels callable and returns its `Map` payload, shared by both
 * the community and convoy Firebase repositories. HttpsError codes (never
 * messages) surface via a failed [Result] so the caller maps them through
 * [ChannelErrorMapper].
 */
internal suspend fun FirebaseFunctions.callChannel(
    name: String,
    payload: Map<String, Any?>,
): Result<Map<String, Any?>?> =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        getHttpsCallable(name)
            .call(payload)
            .addOnCompleteListener { task ->
                if (!continuation.isActive) return@addOnCompleteListener
                if (task.isSuccessful) {
                    @Suppress("UNCHECKED_CAST")
                    val data = task.result?.getData() as? Map<String, Any?>
                    continuation.resume(Result.success(data))
                } else {
                    continuation.resume(
                        Result.failure(
                            task.exception
                                ?: IllegalStateException("$name failed without a cause"),
                        ),
                    )
                }
            }
    }

/** Translates a raw callable failure into the pure, testable error code. */
internal fun Throwable.toChannelErrorCode(): ChannelErrorCode {
    val functionsError = this as? FirebaseFunctionsException ?: return ChannelErrorCode.Other
    return when (functionsError.code) {
        FirebaseFunctionsException.Code.UNAUTHENTICATED -> ChannelErrorCode.Unauthenticated
        FirebaseFunctionsException.Code.PERMISSION_DENIED -> ChannelErrorCode.PermissionDenied
        FirebaseFunctionsException.Code.INVALID_ARGUMENT -> ChannelErrorCode.InvalidArgument
        FirebaseFunctionsException.Code.FAILED_PRECONDITION -> ChannelErrorCode.FailedPrecondition
        FirebaseFunctionsException.Code.NOT_FOUND -> ChannelErrorCode.NotFound
        else -> ChannelErrorCode.Other
    }
}

/** Reads a stored channel message doc into the pure model (Timestamp → millis + ISO). */
internal fun DocumentSnapshot.toChannelMessage(): ChannelMessage? {
    if (!exists()) return null
    // senderUid is required (author identity + own/other + unread logic); a
    // missing OR blank value is malformed, so drop the doc.
    val senderUid = getString("senderUid")?.takeIf { it.isNotBlank() } ?: return null
    val millis = getTimestamp("createdAt")?.toDate()?.time
    return ChannelMessage(
        id = id,
        senderUid = senderUid,
        text = getString("text") ?: "",
        senderDisplayName = getString("senderDisplayName"),
        senderAvatarPath = getString("senderAvatarPath"),
        createdAtMillis = millis,
        createdAtIso = millis?.let { java.time.Instant.ofEpochMilli(it).toString() },
        // Backend-written and always present on new messages; absent only on
        // pre-mentions history, which parses as the empty set.
        mentionedUids = ChannelResponseParser.parseMentionedUids(get("mentionedUids")),
        // Present when the sender posted optimistically (it equals this doc id).
        // Reconciliation matches on the doc id, so this is carried only for parity.
        clientId = getString("clientId")?.takeIf { it.isNotBlank() },
        // Present only on a reply whose parent the server snapshotted; the shared
        // parser tolerates a missing/malformed map (ordinary message → null).
        replyTo = ChannelResponseParser.parseReplyTo(get("replyTo")),
    )
}

/**
 * Overlays each sender's CURRENT `users/{uid}` profile onto the copy stamped on
 * the message at post time ([ChannelThread.hydrate] carries the decision and the
 * fallback rules).
 *
 * Shared by BOTH channel repositories, alongside the other helpers in this file,
 * for the same reason [BlockVisibility.filterHiddenAuthors] is shared rather than
 * written twice: the community and convoy live windows are the same shape, and a
 * change to the overlay policy must not be able to land on one and miss the
 * other.
 *
 * De-duplicated by sender BEFORE the read, so a window of hundreds of messages
 * costs a read per distinct SENDER, never one per message. Apply it AFTER the
 * block filter so a hidden sender is never paid for with a profile read.
 *
 * ENFORCED, not assumed: [LiveProfileRepository.observeProfiles] documents itself
 * as never failing, but this operator sits in the middle of the LIVE message
 * stream. A throw from the overlay would terminate that flow for good — the chat
 * would freeze on its last frame and stop receiving messages entirely, with no
 * error state to show for it. Containing it here means the same failure merely
 * leaves the stored copies on screen, exactly as
 * [com.kungsbackacarcommunity.app.convoy.ConvoyCoordinator] contains its own
 * overlay. `catch` rethrows the flow's own cancellation cause, so leaving the
 * screen still tears the listener down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<ChannelMessagesState>.hydrateSenders(
    liveProfiles: LiveProfileRepository,
): Flow<ChannelMessagesState> = flatMapLatest { state ->
    if (state !is ChannelMessagesState.Loaded) return@flatMapLatest flowOf(state)
    val uids = LiveProfiles.uidsOf(state.messages) { it.senderUid }
    liveProfiles.observeProfiles(uids)
        .map { live -> ChannelMessagesState.Loaded(ChannelThread.hydrate(state.messages, live)) }
        .catch { emit(state) }
}

/**
 * [hydrateSenders] for an older page fetched through `*-list`.
 *
 * An older page needs the overlay just as much as the live window — without it,
 * scrolling back would show a member's old avatar above their new one. Unlike the
 * live window this genuinely costs reads: an older page is BY DEFINITION messages
 * outside the live window, so its senders are the ones the live hydration has not
 * already cached. That is bounded by the distinct senders in one page, and
 * pagination already shows a spinner, so it is paid inline rather than by
 * publishing the page twice.
 *
 * Contained like the live-window overload: this runs inside the callable's
 * `Result.fold`, whose value becomes a [ChannelOlderResult.Loaded], and
 * [ChannelChatCoordinator.loadOlder] turns any throw into a retryable
 * [ChannelPageStatus.Error]. Unguarded, a failed cosmetic overlay would therefore
 * DISCARD a page that had already been fetched successfully and make the member
 * retry it. Returning the un-hydrated page keeps the messages and their stored
 * copies, which is precisely the pre-hydration behaviour.
 */
internal suspend fun ChannelMessagesPage.hydrateSenders(
    liveProfiles: LiveProfileRepository,
): ChannelMessagesPage =
    runCatchingCancellable {
        val live = liveProfiles.loadProfiles(LiveProfiles.uidsOf(messages) { it.senderUid })
        copy(messages = ChannelThread.hydrate(messages, live))
    }
        .getOrDefault(this)
