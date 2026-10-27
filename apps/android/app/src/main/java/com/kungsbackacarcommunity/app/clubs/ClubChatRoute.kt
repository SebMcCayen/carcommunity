package com.kungsbackacarcommunity.app.clubs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kungsbackacarcommunity.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ClubChatRoute(uid: String, repository: ClubRepository? = null) {
    val context = LocalContext.current
    val repo = remember(uid, repository) { repository ?: FirebaseClubRepository.createIfAvailable(context, uid) }
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
    if (foreground) key(uid, repo, entryEpoch) { VerifiedClubChat(uid, repo) }
}

@Composable
private fun VerifiedClubChat(uid: String, repo: ClubRepository?) {
    val source = remember(repo) { repo?.chat() ?: flowOf(ClubChatState.Unavailable) }
    val state by source.collectAsState(initial = ClubChatState.Unavailable)
    val ready = state as? ClubChatState.Ready
    if (ready == null || repo == null) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.clubs_noClubChat)) }
    } else {
        key(uid, repo, ready.membership) { ClubChatRoom(uid, repo, ready) }
    }
}

@Composable
private fun ClubChatRoom(uid: String, repository: ClubRepository, ready: ClubChatState.Ready) {
    val membership = ready.membership
    var pages by remember { mutableStateOf<List<ClubMessagePage>>(emptyList()) }
    var pageCursor by remember { mutableStateOf<ClubMessageCursor?>(null) }
    var draft by remember { mutableStateOf("") }
    var operationId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(false) }
    var paging by remember { mutableStateOf(false) }
    var pageHasOlder by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var reporting by remember { mutableStateOf<ClubMessage?>(null) }
    var reported by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val olderRows = mutableListOf<ClubMessage>()
    pages.forEach { page -> key(page.ids) {
        val source = remember(repository, page.ids) { repository.observeMessageRows(membership.clubId, page.ids) }
        val rows by source.collectAsState(initial = emptyList())
        olderRows.addAll(rows)
        Unit
    } }
    val rawMessages = (ready.messages + olderRows).distinctBy { it.id }
        .sortedWith(compareByDescending<ClubMessage> { it.createdAtMillis ?: Long.MAX_VALUE }.thenByDescending { it.id })
    val messages = rawMessages.filter { it.authorUid !in ready.hiddenUids }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (error != null) Text(clubError(error!!), color = MaterialTheme.colorScheme.error)
        if (reported) Text(stringResource(R.string.clubs_reported), style = MaterialTheme.typography.bodySmall)
        if (messages.isEmpty()) Text(stringResource(R.string.clubs_emptyChat), Modifier.padding(12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(messages, key = { it.id }) { message ->
                var menu by remember(message.id) { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(message.authorName ?: stringResource(R.string.clubs_member), style = MaterialTheme.typography.labelMedium)
                        Text(message.text, style = MaterialTheme.typography.bodyMedium)
                        message.createdAtMillis?.let { Text(clubDate(it), style = MaterialTheme.typography.labelSmall) }
                    }
                    if (message.authorUid != uid || membership.role.managesMembers) Box {
                        IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Filled.MoreVert, stringResource(R.string.clubs_messageActions)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (message.authorUid != uid) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.clubs_report)) }, onClick = { menu = false; reporting = message })
                                DropdownMenuItem(text = { Text(stringResource(R.string.clubs_block)) }, onClick = {
                                    menu = false; busy = true; scope.launch {
                                        try { repository.blockAuthor(message.authorUid); error = null }
                                        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                                        finally { busy = false }
                                    }
                                })
                            }
                            if (membership.role.managesMembers) DropdownMenuItem(text = { Text(stringResource(R.string.clubs_hideMessage)) }, onClick = {
                                menu = false; busy = true; scope.launch {
                                    try { repository.manage("removeMessage", mapOf("clubId" to membership.clubId, "messageId" to message.id), UUID.randomUUID().toString()); error = null }
                                    catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                                    finally { busy = false }
                                }
                            })
                        }
                    }
                } }
            }
            val nextCursor = pageCursor ?: ready.oldestCursor
            if (nextCursor != null && (if (pages.isEmpty()) ready.hasOlder else pageHasOlder)) item {
                TextButton(enabled = !paging, onClick = {
                    paging = true; scope.launch {
                        try { val page = repository.olderMessages(membership.clubId, nextCursor); pages = pages + page; pageCursor = page.nextCursor; pageHasOlder = page.hasMore; error = null }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                        finally { paging = false }
                    }
                }) { Text(stringResource(R.string.clubs_older)) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft, { value -> if (value.codePointCount(0, value.length) <= 2000) { draft = value; operationId = UUID.randomUUID().toString() } },
                Modifier.weight(1f), label = { Text(stringResource(R.string.clubs_message)) }, maxLines = 4, enabled = !busy)
            TextButton(enabled = draft.isNotBlank() && !busy, onClick = {
                busy = true; scope.launch {
                    try { repository.send(membership.clubId, draft.trim(), operationId); draft = ""; operationId = UUID.randomUUID().toString(); error = null }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.clubs_send)) }
        }
    }
    reporting?.let { message ->
        com.kungsbackacarcommunity.app.moderation.ReportReasonDialog(
            onDismiss = { reporting = null },
            onSelect = { reason ->
                reporting = null; busy = true
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
