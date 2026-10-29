package com.kungsbackacarcommunity.app.clubs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ClubChatRoute(
    uid: String,
    repository: ClubRepository? = null,
    liveProfiles: LiveProfileRepository? = null,
) {
    val context = LocalContext.current
    val repo = remember(uid, repository) { repository ?: FirebaseClubRepository.createIfAvailable(context, uid) }
    val profiles = remember(uid, repo, liveProfiles) {
        liveProfiles ?: if (repo is FirebaseClubRepository) FirebaseLiveProfileRepository.sharedOrEmpty(context)
        else LiveProfileRepository.EMPTY
    }
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
    if (foreground) key(uid, repo, entryEpoch) { VerifiedClubChat(uid, repo, profiles) }
}

@Composable
private fun VerifiedClubChat(uid: String, repo: ClubRepository?, profiles: LiveProfileRepository) {
    val source = remember(repo) { repo?.chat() ?: flowOf(ClubChatState.Unavailable) }
    val state by source.collectAsState(initial = ClubChatState.Unavailable)
    val ready = state as? ClubChatState.Ready
    if (ready == null || repo == null) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.clubs_noClubChat)) }
    } else {
        key(uid, repo, ready.membership) { ClubChatRoom(uid, repo, ready, profiles) }
    }
}

@Composable
private fun ClubChatRoom(uid: String, repository: ClubRepository, ready: ClubChatState.Ready, profiles: LiveProfileRepository) {
    val membership = ready.membership
    var pages by remember { mutableStateOf<List<ClubMessagePage>>(emptyList()) }
    var retainedLiveIds by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var pageCursor by remember { mutableStateOf<ClubMessageCursor?>(null) }
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var operationId by remember { mutableStateOf(UUID.randomUUID().toString()) }
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
        val source = remember(profiles, senderUids) {
            // A cosmetic profile failure must never terminate the live Club message stream.
            flow { emitAll(profiles.observeProfiles(senderUids)) }.catch { emit(emptyMap()) }
        }
        source.collectAsState(initial = emptyMap()).value
    }
    val messages = clubChatPresentation(visibleMessages, ready.hiddenUids, live)
    fun sendText(text: String, clearDraft: Boolean) {
        if (busy || text.isBlank()) return
        val id = if (clearDraft) operationId else {
            emojiOperation?.takeIf { it.first == text }?.second ?: UUID.randomUUID().toString().also {
                emojiOperation = text to it
            }
        }
        busy = true
        scope.launch {
            try {
                repository.send(membership.clubId, text.trim(), id)
                currentCoroutineContext().ensureActive()
                if (clearDraft) {
                    draft = TextFieldValue("")
                    operationId = UUID.randomUUID().toString()
                } else emojiOperation = null
                error = null
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
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
                onRetry = {}, onViewProfile = null,
                ownMessageActionsEnabled = membership.role.managesMembers,
                onShowLocationOnMap = null,
                onMessageLongPress = { if (!busy) actionsMessageId = it.id },
                modifier = Modifier.testTag("club-message-list"),
            )
        }
        ChannelChatComposer(
            draft = draft,
            onValueChange = { value ->
                if (value.text.codePointCount(0, value.text.length) <= 2000) {
                    if (value.text != draft.text) operationId = UUID.randomUUID().toString()
                    draft = value
                }
            },
            onSend = { sendText(draft.text, clearDraft = true) },
            onQuickEmoji = { sendText(it, clearDraft = false) },
            canSend = draft.text.isNotBlank(), enabled = !busy,
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
