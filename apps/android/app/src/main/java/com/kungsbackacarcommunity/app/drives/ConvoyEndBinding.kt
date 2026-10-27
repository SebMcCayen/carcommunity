package com.kungsbackacarcommunity.app.drives

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.kungsbackacarcommunity.app.convoy.ConvoyRepository
import com.kungsbackacarcommunity.app.convoy.ConvoyStatus
import com.kungsbackacarcommunity.app.convoy.ConvoySummary
import com.kungsbackacarcommunity.app.live.LiveCommandResult
import com.kungsbackacarcommunity.app.live.LiveLocation
import com.kungsbackacarcommunity.app.live.LiveLocationCoordinator
import com.kungsbackacarcommunity.app.live.LiveSessionInfo
import com.kungsbackacarcommunity.app.live.LiveSessionLoad
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull

/** Rules use observed document/session values, never the optimistic convoy list. */
internal object ConvoyEndObservation {
    fun eligible(session: LiveSessionInfo?, userEnded: Boolean, now: Long): Boolean =
        session?.convoyAutoStarted == true && !userEnded &&
            (session.expiresAtMillis == null || session.expiresAtMillis > now)

    fun ended(session: LiveSessionInfo?, convoy: ConvoySummary?, userEnded: Boolean, now: Long): Boolean =
        session != null && convoy != null && eligible(session, userEnded, now) &&
            session.convoyId != null && convoy.convoyId == session.convoyId && convoy.status == ConvoyStatus.Ended

    fun manualReplacement(oldId: String, session: LiveSessionInfo?, now: Long): Boolean =
        session != null && session.sessionId != oldId && !session.convoyAutoStarted &&
            session.convoyId == null && LiveLocation.isSharing(session, now)
}

class ConvoyEndBinding internal constructor(
    val promptSessionId: String?,
    val holdRecording: Boolean,
    val suppressOldSessionStart: Boolean,
    val choose: (ConvoyEndChoice) -> Unit,
)

/**
 * One uid/session owns the choice, deadline and command. Saveable command markers
 * prevent reissuing a replacement start after rotation; observations alone resolve
 * it. A failed transfer retries the choice if the old session is still live,
 * otherwise it stops+saves. Neither outcome can leave a recorder without a session.
 */
@Composable
fun rememberConvoyEndBinding(
    uid: String,
    repository: ConvoyRepository?,
    sessionLoad: LiveSessionLoad,
    sharing: Boolean,
    userEndedSession: Boolean,
    coordinator: LiveLocationCoordinator?,
    canStartSingle: Boolean,
    now: () -> Long,
    onStopRecording: () -> Unit,
    onCommandFailed: () -> Unit,
    timeoutMillis: Long = 20_000L,
): ConvoyEndBinding = key(uid) {
    val session = sessionLoad.sessionOrNull
    val convoyId = session?.takeIf { it.convoyAutoStarted }?.convoyId
    // Key the entire producer: a changed user/convoy cannot borrow a previous
    // document for even a single frame. null includes missing/denied reads.
    val observation by key(repository, convoyId) {
        produceState<Pair<Boolean, ConvoySummary?>>(false to null, repository, convoyId) {
            if (repository != null && convoyId != null) {
                repository.observeConvoy(convoyId, uid).collect { value = true to it }
            } else {
                value = true to null
            }
        }
    }
    val convoy = observation.second
    var prompt by rememberSaveable { mutableStateOf<String?>(null) }
    var decided by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var operation by rememberSaveable { mutableStateOf<String?>(null) }
    var issued by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    var deadline by rememberSaveable { mutableStateOf(0L) }
    var timedOut by rememberSaveable { mutableStateOf(false) }
    var endWaitSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var endWaitDeadline by rememberSaveable { mutableStateOf(0L) }
    val currentLoad by rememberUpdatedState(sessionLoad)
    val currentNow by rememberUpdatedState(now)
    val stopRecording by rememberUpdatedState(onStopRecording)
    val commandFailed by rememberUpdatedState(onCommandFailed)
    val currentUserEnded by rememberUpdatedState(userEndedSession)

    // The shell's derived sharing producer can lag a new session by one frame.
    // End/transfer decisions must use this observed session's actual status/expiry.
    val observedSharing = LiveLocation.isSharing(session, now())
    val confirmedEnd = ConvoyEndObservation.ended(session, convoy, userEndedSession, now())
    val eligible = ConvoyEndObservation.eligible(session, userEndedSession, now())
    val oldId = pending ?: prompt
    val replaced = sessionLoad.observed && session != null && oldId != null && session.sessionId != oldId
    val cancelled = sessionLoad.observed && session != null && !eligible
    val visiblePrompt = prompt?.takeUnless { replaced || cancelled }
    val awaitingEndDocument = eligible && !observedSharing && convoyId != null &&
        session?.sessionId != decided && !confirmedEnd && prompt == null && pending == null &&
        (!observation.first || (convoy != null && convoy.convoyId == convoyId && convoy.status != ConvoyStatus.Ended))
    val currentAwaitingEndDocument by rememberUpdatedState(awaitingEndDocument)

    LaunchedEffect(awaitingEndDocument, session?.sessionId, sessionLoad.observed) {
        // Loading after recreation preserves the saved deadline; it cannot restart
        // the grace period or consume an old account/session's stopped observation.
        if (!sessionLoad.observed) return@LaunchedEffect
        if (!awaitingEndDocument) {
            endWaitSessionId = null
            endWaitDeadline = 0L
            return@LaunchedEffect
        }
        val id = session?.sessionId ?: return@LaunchedEffect
        if (endWaitSessionId != id) {
            endWaitSessionId = id
            endWaitDeadline = currentNow() + timeoutMillis
        }
        delay((endWaitDeadline - currentNow()).coerceAtLeast(0L))
        if (currentAwaitingEndDocument) {
            // No ended document arrived in the bounded reconciliation window.
            // Release the ordinary stop/save path and consume this session so a
            // much later Firestore emission cannot offer Continue after it saved.
            decided = id
        }
    }

    LaunchedEffect(confirmedEnd, session?.sessionId, decided, pending, userEndedSession,
        observedSharing, eligible, sessionLoad.observed, sharing) {
        // Consume deliberate stops before the shell clears its self-stop marker.
        // Otherwise the same stopped session could raise a convoy choice later.
        if (session?.convoyAutoStarted == true && sessionLoad.observed && !observedSharing && !eligible) {
            decided = session.sessionId
        }
        if (confirmedEnd && session != null && session.sessionId != decided && pending == null) {
            prompt = session.sessionId
        }
        if (pending == null && (replaced || cancelled)) prompt = null
    }

    LaunchedEffect(pending, deadline) {
        if (pending != null) {
            delay((deadline - currentNow()).coerceAtLeast(0L))
            timedOut = true
        }
    }
    LaunchedEffect(pending, operation, coordinator, sessionLoad.observed) {
        val id = pending ?: return@LaunchedEffect
        if (issued) return@LaunchedEffect
        // Loading after recreation is never permission to issue an unscoped stop.
        if (!currentLoad.observed) return@LaunchedEffect
        issued = true
        val own = currentLoad.sessionOrNull
        if ((own != null && own.sessionId != id) || currentUserEnded) return@LaunchedEffect
        val result = withTimeoutOrNull((deadline - currentNow()).coerceAtLeast(1L)) {
            when {
                coordinator == null -> LiveCommandResult.Failed
                operation == ConvoyEndChoice.ContinueAsSingle.name && canStartSingle ->
                    // Unlike startSingleSession's double-tap guard, replacement is
                    // intentional even while the OLD session still reports ACTIVE.
                    coordinator.start(LiveLocation.DEFAULT_SESSION_DURATION)
                operation == ConvoyEndChoice.EndSession.name && LiveLocation.isSharing(own, currentNow()) ->
                    coordinator.stop()
                operation == ConvoyEndChoice.EndSession.name -> LiveCommandResult.Success
                else -> LiveCommandResult.Failed
            }
        }
        if (result != LiveCommandResult.Success) failed = true
    }
    LaunchedEffect(pending, operation, sessionLoad, sharing, failed, timedOut, userEndedSession) {
        val id = pending ?: return@LaunchedEffect
        if (!sessionLoad.observed) return@LaunchedEffect
        val different = session != null && session.sessionId != id
        val manual = ConvoyEndObservation.manualReplacement(id, session, now())
        val endConfirmed = operation == ConvoyEndChoice.EndSession.name && !observedSharing
        when {
            // A newer unrelated session owns the recorder now. Never stop it.
            operation == ConvoyEndChoice.ContinueAsSingle.name && manual -> {
                decided = id
                pending = null
            }
            different && observedSharing -> {
                decided = id
                pending = null
            }
            // Explicit privacy stops and hard expiry keep the ordinary lifecycle's
            // neutral save reason, including when they interrupt a transfer.
            userEndedSession || (session != null && session.expiresAtMillis?.let { it <= now() } == true) -> {
                pending = null
                prompt = null
            }
            endConfirmed || (failed || timedOut) && !observedSharing -> {
                decided = id
                pending = null
                stopRecording()
                if (failed || timedOut) commandFailed()
            }
            failed || timedOut -> {
                pending = null
                prompt = id
                commandFailed()
            }
        }
    }

    ConvoyEndBinding(
        promptSessionId = visiblePrompt,
        holdRecording = (confirmedEnd && session?.sessionId != decided) || prompt != null || pending != null ||
            // Reconcile independent listeners even after an ACTIVE document has
            // already emitted. Neither ordering may stop the recorder before choice.
            awaitingEndDocument ||
            (eligible && session?.sessionId != decided && convoyId != null && !observation.first) ||
            // Bridge a stale derived false on the first ACTIVE manual-session echo.
            (sessionLoad.observed && observedSharing && !sharing),
        suppressOldSessionStart = session != null && session.sessionId == decided,
        choose = { choice ->
            val id = visiblePrompt
            if (id != null && pending == null) {
                prompt = null
                pending = id
                operation = choice.name
                issued = false
                failed = false
                timedOut = false
                deadline = now() + timeoutMillis
            }
        },
    )
}
