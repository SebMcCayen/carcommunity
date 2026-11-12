package com.kungsbackacarcommunity.app.notifications

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.convoy.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.kungsbackacarcommunity.app.live.LiveShareStart

private val invitationSaver = listSaver<ConvoyInvitationPresentation, Any>(
    save = { listOf(it.startedAtMillis, it.initialized, ArrayList(it.seenIds), ArrayList(it.pendingIds), it.currentId ?: "", it.deadlineMillis) },
    restore = {
        @Suppress("UNCHECKED_CAST")
        ConvoyInvitationPresentation(it[0] as Long, it[1] as Boolean,
            (it[2] as List<String>).toSet(), it[3] as List<String>,
            (it[4] as String).takeIf(String::isNotEmpty), it[5] as Long)
    },
)

/** Kept in the shell across tabs; only the inbox listener is foreground-scoped. */
@Composable
internal fun ConvoyInvitationHost(
    uid: String,
    repository: NotificationsRepository,
    convoyRepository: ConvoyRepository,
    coordinator: ConvoyCoordinator,
    inboxVisible: Boolean,
    sharing: Boolean,
    liveShareEnabled: Boolean,
    onJoined: (String) -> Unit,
    modifier: Modifier = Modifier,
    elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    var presentation by rememberSaveable(uid, stateSaver = invitationSaver) {
        mutableStateOf(ConvoyInvitationPresentation(System.currentTimeMillis()))
    }
    var items by remember(uid) { mutableStateOf<List<AppNotification>>(emptyList()) }
    var snapshotReady by remember(uid) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(elapsedRealtime()) }
    var busyId by remember(uid) { mutableStateOf<String?>(null) }
    var errorId by remember(uid) { mutableStateOf<String?>(null) }
    var actionFailed by remember(uid) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val available by rememberUpdatedState(foreground && !inboxVisible)
    LaunchedEffect(uid, repository, foreground) {
        if (foreground) repository.observeNotifications(uid).collect { snapshot ->
            if (snapshot is NotificationsState.Loaded) {
                snapshotReady = true
                items = snapshot.items
                now = elapsedRealtime()
                presentation = presentation.update(items, now, available)
            }
        }
    }
    LaunchedEffect(inboxVisible) {
        if (inboxVisible) presentation = presentation.update(items, elapsedRealtime(), false)
    }
    LaunchedEffect(presentation.currentId, presentation.deadlineMillis, foreground, snapshotReady) {
        if (foreground && snapshotReady) while (presentation.currentId != null) {
            now = elapsedRealtime()
            presentation = presentation.update(items, now, available)
            delay(200)
        }
    }
    val invitation = items.firstOrNull { it.id == presentation.currentId }
    // Retire stale/withdrawn/answered invitations immediately, including replies on another device.
    LaunchedEffect(invitation?.id, foreground) {
        val item = invitation ?: return@LaunchedEffect
        if (!foreground) return@LaunchedEffect
        try {
            convoyRepository.observeConvoy(item.relatedEntityId!!, uid).collect { convoy ->
                if (convoy == null || convoy.status == ConvoyStatus.Ended ||
                    convoy.viewer?.inviteStatus != ConvoyInviteStatus.Invited) {
                    presentation = presentation.dismiss(item.id).update(items, elapsedRealtime(), available)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The callable still validates; transient listener failure is not a decline. */ }
    }
    if (invitation != null && foreground && !inboxVisible && now < presentation.deadlineMillis) {
        val genericError = stringResource(R.string.convoy_errorGeneric)
        fun answer(join: Boolean) {
            if (busyId != null || elapsedRealtime() >= presentation.deadlineMillis) return
            val id = invitation.id
            busyId = id
            errorId = null
            scope.launch {
                var marked = false
                var success = false
                try {
                    // Refresh membership preconditions before using the existing accept path.
                    coordinator.load()
                    marked = join && liveShareEnabled && !sharing &&
                        (coordinator.status.value as? ConvoyListStatus.Loaded)?.convoy(invitation.relatedEntityId!!)?.status == ConvoyStatus.Active &&
                        LiveShareStart.request(System.currentTimeMillis(), observedSharing = false)
                    success = if (join) coordinator.accept(invitation.relatedEntityId!!) else coordinator.decline(invitation.relatedEntityId!!)
                    if (success) {
                        presentation = presentation.dismiss(id).update(items, elapsedRealtime(), available)
                        if (marked) { LiveShareStart.settled(System.currentTimeMillis()); marked = false }
                        if (join) onJoined(invitation.relatedEntityId!!)
                        try { repository.markRead(id) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                    } else { errorId = id; actionFailed = true }
                } finally {
                    if (marked) { if (success) LiveShareStart.settled(System.currentTimeMillis()) else LiveShareStart.failed() }
                    busyId = null
                }
            }
        }
        ConvoyInvitationCard(invitation,
            secondsRemaining = ((presentation.deadlineMillis - now + 999) / 1000).toInt().coerceIn(1, 30),
            busy = busyId != null,
            error = if (actionFailed && errorId == invitation.id) genericError else null,
            onJoin = { answer(true) }, onDecline = { answer(false) }, modifier = modifier)
    }
}
