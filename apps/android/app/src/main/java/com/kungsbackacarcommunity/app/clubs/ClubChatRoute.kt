package com.kungsbackacarcommunity.app.clubs

import com.kungsbackacarcommunity.app.chat.ChatSendCooldowns
import com.kungsbackacarcommunity.app.chat.rememberChatCooldownSeconds
import com.kungsbackacarcommunity.app.chat.ChatCooldownNotice
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import com.kungsbackacarcommunity.app.chatchannels.ChannelChatComposer
import com.kungsbackacarcommunity.app.chatchannels.ChannelMessageList
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.profile.FirebaseLiveProfileRepository
import com.kungsbackacarcommunity.app.profile.LiveProfileRepository
import com.kungsbackacarcommunity.app.profile.LiveProfiles
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kungsbackacarcommunity.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID
import java.security.MessageDigest
import com.kungsbackacarcommunity.app.chatchannels.ChannelMessage
import com.kungsbackacarcommunity.app.chatchannels.ChannelDeliveryState

@Composable
fun ClubChatRoute(
    uid: String,
    repository: ClubRepository? = null,
    liveProfiles: LiveProfileRepository? = null,
    sendCooldown: com.kungsbackacarcommunity.app.chat.ChatSendCooldown? = null,
) {
    val context = LocalContext.current
    val repo = remember(uid, repository) { repository ?: FirebaseClubRepository.createIfAvailable(context, uid) }
    val profiles = remember(uid, repo, liveProfiles) {
        liveProfiles ?: if (repo is FirebaseClubRepository) FirebaseLiveProfileRepository.sharedOrEmpty(context)
        else LiveProfileRepository.EMPTY
    }
    // Keep only submitted messages outside repo-identity/foreground keys. A new
    // Activity creates a new repository, but must not lose already-cleared text.
    // Use one registry slot, reset by uid inputs. Per-uid key groups would leave
    // an unconsumed restored payload for an old account that could reappear when
    // switching back later in the same Activity.
    val outboxSaver = remember(uid) { clubChatOutboxSaver(uid) }
    val outbox = rememberSaveable(uid, saver = outboxSaver) { ClubChatOutbox(uid) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var entryEpoch by remember(lifecycle) { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            // Increment even if no background frame is drawn: rapid stop/start must
            // recreate the private composition and require fresh server proof.
            if (event == Lifecycle.Event.ON_STOP) entryEpoch++
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (foreground) key(uid, repo, entryEpoch) { VerifiedClubChat(uid, repo, profiles, sendCooldown, outbox) }
}

@Composable
private fun VerifiedClubChat(uid: String, repo: ClubRepository?, profiles: LiveProfileRepository, sendCooldown: com.kungsbackacarcommunity.app.chat.ChatSendCooldown?, outbox: ClubChatOutbox) {
    val source = remember(repo, outbox) {
        var verified = false
        (repo?.chat() ?: flowOf(ClubChatState.Unavailable)).onEach { state ->
            if (state is ClubChatState.Ready) verified = true
            else if (verified) {
                // A previously verified foreground room lost access. Initial
                // loading after resume is different: keep its outbox hidden
                // until fresh proof selects the exact same membership again.
                outbox.clear()
                verified = false
            }
        }
    }
    val state by source.collectAsState(initial = ClubChatState.Unavailable)
    val ready = state as? ClubChatState.Ready
    if (ready == null || repo == null) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.clubs_noClubChat)) }
    } else {
        key(uid, repo, ready.membership, outbox.proofEpoch) {
            val roomOutbox = remember(outbox) { outbox.open(ready.membership) }
            ClubChatRoom(uid, repo, ready, profiles, sendCooldown, roomOutbox)
        }
    }
}

@Composable
private fun ClubChatRoom(uid: String, repository: ClubRepository, ready: ClubChatState.Ready, profiles: LiveProfileRepository, sendCooldown: com.kungsbackacarcommunity.app.chat.ChatSendCooldown?, roomOutbox: ClubRoomOutbox) {
    val membership = ready.membership
    val cooldown = remember(uid, sendCooldown) { sendCooldown ?: ChatSendCooldowns.forUser(uid) }
    val cooldownSeconds = rememberChatCooldownSeconds(cooldown)
    var pages by remember { mutableStateOf<List<ClubMessagePage>>(emptyList()) }
    var retainedLiveIds by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var pageCursor by remember { mutableStateOf<ClubMessageCursor?>(null) }
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var draftRevision by rememberSaveable { mutableLongStateOf(0L) }
    var pending by roomOutbox.pending
    var operationId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(false) }
    var paging by remember { mutableStateOf(false) }
    var pageHasOlder by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    // Only IDs survive a recomposition. A removed/blocked message closes its actions immediately.
    var actionsMessageId by remember { mutableStateOf<String?>(null) }
    var reportingId by remember { mutableStateOf<String?>(null) }
    var emojiOperation by remember { mutableStateOf<Pair<String, String>?>(null) }
    var reported by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun retainLiveWindow() {
        val observed = (retainedLiveIds.flatten() + pages.flatMap { it.ids }).toSet()
        val added = ready.messages.map { it.id }.distinct().filterNot { it in observed }
        // Fill the partial tail before adding batches: full batches keep their listeners,
        // and only the changed tail reconnects with an empty initial snapshot.
        // Keep only IDs: redactions/removals must come from live snapshots, never old DTOs.
        if (added.isNotEmpty()) retainedLiveIds = (retainedLiveIds.flatten() + added).chunked(25)
    }
    SideEffect { if (paging || pages.isNotEmpty()) retainLiveWindow() }
    val olderRows = mutableListOf<ClubMessage>()
    (retainedLiveIds + pages.map { it.ids }).filter { it.isNotEmpty() }.distinct().forEach { ids -> key(ids) {
        val source = remember(repository, ids) { repository.observeMessageRows(membership.clubId, ids) }
        val rows by source.collectAsState(initial = emptyList())
        olderRows.addAll(rows)
        Unit
    } }
    val rawMessages = (ready.messages + olderRows).distinctBy { it.id }
        .sortedWith(compareByDescending<ClubMessage> { it.createdAtMillis ?: Long.MAX_VALUE }.thenByDescending { it.id })
    val visibleMessages = rawMessages.filter { it.authorUid !in ready.hiddenUids }
    val senderUids = LiveProfiles.uidsOf(visibleMessages) { it.authorUid }
    val live = key(profiles, senderUids) {
        val source = remember(profiles, senderUids, visibleMessages) {
            // observeProfiles completes after its cached/refreshed batch. Renew on
            // message-window changes even for the same senders; the repository's
            // TTL still decides whether another profile read is needed.
            // A cosmetic profile failure must never terminate the live Club message stream.
            flow { emitAll(profiles.observeProfiles(senderUids)) }.catch { emit(emptyMap()) }
        }
        source.collectAsState(initial = emptyMap()).value
    }
    val deliveredIds = visibleMessages.filter { it.authorUid == uid }.mapTo(mutableSetOf()) { it.id }
    val latestDeliveredIds by rememberUpdatedState(deliveredIds)
    val localRows = pending.filterNot { it.messageId in deliveredIds }
    LaunchedEffect(deliveredIds) { pending = pending.filterNot { it.messageId in deliveredIds } }
    val messages = clubMessagesWithPending(clubChatPresentation(visibleMessages, ready.hiddenUids, live), pending, uid)
    fun sendText(text: String, clearDraft: Boolean, retryId: String? = null) {
        if (busy || text.isBlank() || cooldown.isBlocked()) return
        val id = retryId ?: if (clearDraft) operationId else {
            emojiOperation?.takeIf { it.first == text }?.second ?: UUID.randomUUID().toString().also {
                emojiOperation = text to it
            }
        }
        val snapshot = draft
        if (clearDraft) {
            draft = TextFieldValue("")
            draftRevision++
            operationId = UUID.randomUUID().toString()
        }
        val revision = draftRevision
        val messageId = clubPendingMessageId(uid, id)
        val attempt = pending.firstOrNull { it.operationId == id }?.copy(failed = false, accepted = false)
            ?: ClubPendingSend(id, messageId, text)
        pending = if (pending.any { it.operationId == id }) pending.map { if (it.operationId == id) attempt else it }
            else pending + attempt
        busy = true
        scope.launch {
            try {
                repository.send(membership.clubId, text.trim(), id)
                currentCoroutineContext().ensureActive()
                pending = pending.map { if (it.operationId == id) it.copy(accepted = true, failed = false) else it }
                if (!clearDraft) emojiOperation = null
                if (operationId == id) operationId = UUID.randomUUID().toString()
                error = null
            } catch (e: CancellationException) {
                pending = pending.map { if (it.operationId == id && messageId !in latestDeliveredIds) it.copy(failed = true) else it }
                throw e
            } catch (e: Exception) {
                // A server echo beats a late transport failure; never restore an accepted send.
                if (messageId in latestDeliveredIds) return@launch
                pending = pending.map { if (it.operationId == id) it.copy(failed = true) else it }
                val clubFailure = e as? ClubException
                // Only explicit rejection restores the original selection/composition. An
                // ambiguous network failure remains recoverable through the same-ID bubble.
                val rejected = clubFailure?.code in setOf("invalid-argument", "permission-denied", "failed-precondition", "chat-rate-limited", "resource-exhausted")
                if (clearDraft && rejected && draftRevision == revision && draft.text.isEmpty()) {
                    draft = snapshot
                    operationId = id
                }
                if (clubFailure?.code == "chat-rate-limited" && clubFailure.cooldown != null) {
                    cooldown.apply(clubFailure.cooldown); error = null
                } else error = e
            }
            finally { busy = false }
        }
    }
    val nextCursor = pageCursor ?: ready.oldestCursor
    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s2),
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
        if (error != null) Text(clubError(error!!), color = MaterialTheme.colorScheme.error)
        if (reported) Text(stringResource(R.string.clubs_reported), style = MaterialTheme.typography.bodySmall)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) Text(stringResource(R.string.clubs_emptyChat),
                Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Keep the pager reachable even when every loaded row was blocked/redacted.
            ChannelMessageList(
                messages = messages, currentUid = uid, mentionDisplayNames = emptyMap(),
                canLoadOlder = nextCursor != null && (if (pages.isEmpty()) ready.hasOlder else pageHasOlder),
                isLoadingOlder = paging,
                onLoadOlder = {
                    if (!paging && nextCursor != null) {
                        // Pin the boundary before the suspend request: arrivals during loading
                        // must not open a gap between the latest window and this older page.
                        retainLiveWindow()
                        paging = true; scope.launch {
                            try {
                                val page = repository.olderMessages(membership.clubId, nextCursor)
                                currentCoroutineContext().ensureActive()
                                pages = pages + page; pageCursor = page.nextCursor; pageHasOlder = page.hasMore; error = null
                            }
                            catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                            finally { paging = false }
                        }
                    }
                },
                onRetry = { message ->
                    localRows.firstOrNull { "pending:${it.messageId}" == message.id && it.failed }?.let {
                        sendText(it.text, clearDraft = operationId == it.operationId && draft.text.trim() == it.text.trim(), retryId = it.operationId)
                    }
                }, onViewProfile = null,
                retryEnabled = !busy && cooldownSeconds == 0,
                ownMessageActionsEnabled = membership.role.managesMembers,
                onShowLocationOnMap = null,
                onMessageLongPress = { message ->
                    if (!busy && visibleMessages.any { it.id == message.id && it.authorUid == message.senderUid }) {
                        actionsMessageId = message.id
                    }
                },
                modifier = Modifier.testTag("club-message-list"),
            )
        }
        ChatCooldownNotice(cooldownSeconds)
        ChannelChatComposer(
            draft = draft,
            onValueChange = { value ->
                if (value.text.codePointCount(0, value.text.length) <= 2000) {
                    if (value != draft) draftRevision++
                    if (value.text != draft.text) operationId = UUID.randomUUID().toString()
                    draft = value
                }
            },
            onSend = { sendText(draft.text, clearDraft = true) },
            onQuickEmoji = { sendText(it, clearDraft = false) },
            canSend = !busy && draft.text.isNotBlank() && cooldownSeconds == 0, enabled = true,
            quickEmojiEnabled = !busy && cooldownSeconds == 0,
            placeholder = stringResource(R.string.channel_inputPlaceholder),
            sendLabel = stringResource(R.string.channel_send),
            inputModifier = Modifier.testTag("club-chat-input"),
        )
    }
    visibleMessages.firstOrNull { it.id == actionsMessageId }?.let { message ->
        AlertDialog(
            onDismissRequest = { actionsMessageId = null },
            title = { Text(stringResource(R.string.clubs_messageActions)) },
            text = { Column {
                Text(messages.firstOrNull { it.id == message.id }?.senderDisplayName
                    ?: stringResource(R.string.clubs_member), style = MaterialTheme.typography.labelMedium)
                Text(message.text)
            } },
            confirmButton = { Column {
                if (message.authorUid != uid) {
                    TextButton(enabled = !busy, onClick = { actionsMessageId = null; reportingId = message.id }) {
                        Text(stringResource(R.string.clubs_report))
                    }
                    TextButton(enabled = !busy, onClick = {
                        actionsMessageId = null; busy = true; scope.launch {
                            try { repository.blockAuthor(message.authorUid); error = null }
                            catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                            finally { busy = false }
                        }
                    }) { Text(stringResource(R.string.clubs_block)) }
                }
                if (membership.role.managesMembers) TextButton(enabled = !busy, onClick = {
                    actionsMessageId = null; busy = true; scope.launch {
                        try { repository.manage("removeMessage", mapOf("clubId" to membership.clubId, "messageId" to message.id), UUID.randomUUID().toString()); error = null }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.clubs_hideMessage)) }
            } },
        )
    }
    visibleMessages.firstOrNull { it.id == reportingId }?.let { message ->
        com.kungsbackacarcommunity.app.moderation.ReportReasonDialog(
            onDismiss = { reportingId = null },
            onSelect = { reason ->
                reportingId = null; busy = true
                scope.launch {
                    try {
                        repository.manage("reportMessage", mapOf("clubId" to membership.clubId, "messageId" to message.id,
                            "reason" to clubReportReason(reason)), UUID.randomUUID().toString())
                        reported = true; error = null
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                    finally { busy = false }
                }
            },
        )
    }
}

internal fun clubReportReason(reason: com.kungsbackacarcommunity.app.chat.ChatReportReason): String = when (reason) {
    com.kungsbackacarcommunity.app.chat.ChatReportReason.HATE_OR_ABUSE -> "hate"
    com.kungsbackacarcommunity.app.chat.ChatReportReason.UNSAFE_DRIVING -> "dangerousAdvice"
    else -> reason.wire
}

internal data class ClubPendingSend(
    val operationId: String,
    val messageId: String,
    val text: String,
    val failed: Boolean = false,
    val accepted: Boolean = false,
    val submittedAtMillis: Long = System.currentTimeMillis(),
)

/** Match Channel/DM ordering while reconciling only the caller's own server echo. */
internal fun clubMessagesWithPending(
    delivered: List<ChannelMessage>,
    pending: List<ClubPendingSend>,
    uid: String,
): List<ChannelMessage> {
    val ownDeliveredIds = delivered.filter { it.senderUid == uid }.mapTo(mutableSetOf()) { it.id }
    val local = pending.filterNot { it.messageId in ownDeliveredIds }.map {
        ChannelMessage("pending:${it.messageId}", uid, it.text.trim(), null, null, it.submittedAtMillis, null,
            clientId = it.operationId,
            deliveryState = if (it.accepted) ChannelDeliveryState.Sent else if (it.failed) ChannelDeliveryState.Failed else ChannelDeliveryState.Sending)
    }
    return (delivered + local).sortedWith(compareBy<ChannelMessage> { it.createdAtMillis ?: Long.MAX_VALUE }.thenBy { it.id })
}

/** Driver shared-clubs uses sha256(uid + "_" + sha256(operationId)) as the document ID. */
internal fun clubPendingMessageId(uid: String, operationId: String): String {
    fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return sha256(uid + "_" + sha256(operationId))
}

internal fun clubPendingSendValues(rows: List<ClubPendingSend>): List<String> = rows.flatMap {
    listOf(it.operationId, it.messageId, it.text, it.accepted.toString(), it.submittedAtMillis.toString())
}

internal val clubPendingSendSaver = listSaver<List<ClubPendingSend>, String>(
    save = { clubPendingSendValues(it) },
    restore = { values -> values.chunked(5).map {
        val accepted = it[3].toBoolean()
        ClubPendingSend(it[0], it[1], it[2], failed = !accepted, accepted = accepted, submittedAtMillis = it[4].toLong())
    } },
)
