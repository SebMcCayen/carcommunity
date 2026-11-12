package com.kungsbackacarcommunity.app.moderation

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.firebase.functions.FirebaseFunctionsException
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.shell.MapDetailSheet
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Lifecycle re-entry requires fresh authority. This session is never created for private chats. */
@Composable
fun rememberPublicChatModerationSession(uid: String): PublicChatModerationSession? {
    val context = LocalContext.current
    val repository = remember(context, uid) { FirebasePublicChatModerationRepository.createIfAvailable(context, uid) }
        ?: return null
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var epoch by remember(lifecycle) { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val listener = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) epoch++
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(listener)
        onDispose { lifecycle.removeObserver(listener) }
    }
    // key disposes the old server snapshot grant, even on rapid background/foreground transitions.
    return key(uid, repository, foreground, epoch) {
        val admin by remember(repository, foreground) {
            if (foreground) repository.observeAdminCandidate().catch { emit(false) } else flowOf(false)
        }.collectAsState(initial = false)
        val pause by remember(repository) { repository.observeOwnPause().catch { emit(null) } }.collectAsState(initial = null)
        PublicChatModerationSession(uid, repository, admin, pause)
    }
}

/** Server time is anchored to monotonic elapsed time: changing the phone clock cannot prolong the UI pause. */
@Composable
fun rememberPublicChatWritingPaused(session: PublicChatModerationSession?): Boolean {
    var now by remember(session?.pause) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(session?.pause) {
        while (session?.pause?.activeAt(now) == true) {
            delay(1000); now = SystemClock.elapsedRealtime()
        }
    }
    return session?.pause?.activeAt(now) == true
}

@Composable
fun PublicChatPauseNotice(pause: PublicChatWritePause?, active: Boolean) {
    if (active && pause != null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s2)
            .testTag("public_chat_pause_notice")) {
            Text(stringResource(R.string.chatAdmin_pausedUntil,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(pause.untilMillis))),
                style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.chatAdmin_scope), style = MaterialTheme.typography.bodySmall)
            if (pause.reason.isNotBlank()) Text(pause.reason, style = MaterialTheme.typography.bodySmall, maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/** One sheet owns normal actions, edit and review: no nested sheets or retained message snapshots. */
@Composable
internal fun ModeratedMessageActionsSheet(
    title: String,
    preview: MessageActionsPreview?,
    session: PublicChatModerationSession,
    target: PublicChatTarget,
    onDismiss: () -> Unit,
    content: @Composable (PublicChatModerationContext?, (ChatAdminAction) -> Unit) -> Unit,
) {
    var context by remember(target, session.uid, session.repository) { mutableStateOf<PublicChatModerationContext?>(null) }
    var action by remember(target, session.uid) { mutableStateOf<ChatAdminAction?>(null) }
    var rejected by remember(target, session.uid) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(target, session.uid, session.adminCandidate, session.repository, refresh) {
        context = null
        if (!session.adminCandidate) { context = null; action = null; return@LaunchedEffect }
        try {
            val loaded = session.repository.context(target)
            if (loaded.actorUid == session.uid && (target.expectedAuthorUid == null || loaded.authorUid == target.expectedAuthorUid)) { context = loaded; rejected = false }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
            context = null; action = null
        }
    }
    val editorState = remember(target, action, session.uid) { action?.let { ChatModerationEditorState(it) } }
    val capability = context?.takeIf { session.adminCandidate && !rejected && it.actorUid == session.uid }
    val editing = action?.takeIf { capability != null }
    val currentSession by rememberUpdatedState(session)
    MapDetailSheet(
        title = when (editing) {
            ChatAdminAction.Remove -> stringResource(R.string.chatAdmin_remove)
            ChatAdminAction.Pause -> stringResource(R.string.chatAdmin_pause)
            null -> title
        },
        onDismiss = { if (editorState?.busy?.value != true) onDismiss() },
        modifier = Modifier.testTag(MESSAGE_ACTIONS_SHEET_TEST_TAG),
    ) {
        if (editing == null || capability == null) {
            content(capability) { selected -> action = selected }
        } else key(target, editing, session.uid) {
            ChatModerationEditor(editing, capability,
                MessageActionsPreview(capability.authorName ?: preview?.authorName, capability.text ?: preview?.text.orEmpty()),
                state = editorState,
                onCancel = { action = null },
                onSubmit = { draft, operationId ->
                    // The repository also binds to the signed-in uid before and after the call.
                    check(currentSession.adminCandidate && currentSession.uid == capability.actorUid)
                    session.repository.decide(draft.payload(target, capability, operationId))
                    onDismiss()
                },
                onDenied = { rejected = true; action = null },
                onStale = { refresh++ },
            )
        }
    }
}

internal class ChatModerationEditorState(action: ChatAdminAction) {
    val draft = mutableStateOf(ChatModerationDraft(action))
    val reviewing = mutableStateOf(false)
    val operationId = mutableStateOf(UUID.randomUUID().toString())
    val busy = mutableStateOf(false)
    val failed = mutableStateOf(false)
    val stale = mutableStateOf(false)
}

@Composable
internal fun ChatModerationEditor(
    action: ChatAdminAction,
    context: PublicChatModerationContext,
    preview: MessageActionsPreview?,
    onCancel: () -> Unit,
    onSubmit: suspend (ChatModerationDraft, String) -> Unit,
    onDenied: () -> Unit = {},
    onStale: () -> Unit = {},
    state: ChatModerationEditorState? = null,
) {
    val editor = state ?: remember(action) { ChatModerationEditorState(action) }
    var draft by editor.draft
    var reviewing by editor.reviewing
    var operationId by editor.operationId
    var busy by editor.busy
    var failed by editor.failed
    var stale by editor.stale
    val scope = rememberCoroutineScope()
    fun edit(next: ChatModerationDraft) {
        if (next == draft) return
        draft = next; operationId = UUID.randomUUID().toString(); failed = false; stale = false
    }
    Column(Modifier.fillMaxWidth().testTag("chat_admin_editor"), verticalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
        preview?.let {
            Text(it.authorName ?: stringResource(R.string.chat_unknownAuthor), style = MaterialTheme.typography.titleSmall)
            Text(it.text, style = MaterialTheme.typography.bodyLarge, maxLines = 4)
        }
        if (!reviewing) {
            OutlinedTextField(draft.reason, { if (it.length <= 500) edit(draft.copy(reason = it)) },
                label = { Text(stringResource(R.string.chatAdmin_reason)) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("chat_admin_reason"), minLines = 2, maxLines = 4)
            if (context.canWarn) YesNoQuestion(stringResource(R.string.chatAdmin_warnAlso), draft.warn, !busy,
                "chat_admin_warn", { edit(draft.copy(warn = it)) })
            if (action == ChatAdminAction.Remove && context.canPause) {
                YesNoQuestion(stringResource(R.string.chatAdmin_pauseAlso), draft.addPause, !busy,
                    "chat_admin_add_pause", { edit(draft.copy(addPause = it)) })
            }
            if (draft.pauses) {
                OutlinedTextField(draft.amount, { if (it.length <= 8) edit(draft.copy(amount = it)) },
                    label = { Text(stringResource(R.string.chatAdmin_amount)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !busy, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("chat_admin_amount"))
                Row(horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                    ChatPauseUnit.entries.forEach { unit ->
                        FilterChip(selected = draft.unit == unit, onClick = { edit(draft.copy(unit = unit)) }, enabled = !busy,
                            label = { Text(stringResource(if (unit == ChatPauseUnit.Minutes) R.string.chatAdmin_minutes else R.string.chatAdmin_days)) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("chat_admin_unit_${unit.wire}"))
                    }
                }
                Text(stringResource(R.string.chatAdmin_maxDuration, context.maxPauseDays), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.chatAdmin_scope), style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = { reviewing = true }, enabled = draft.isValid(context) && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat_admin_review")) {
                Text(stringResource(R.string.chatAdmin_review))
            }
        } else {
            Text(stringResource(R.string.chatAdmin_reviewTitle), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (action == ChatAdminAction.Remove) R.string.chatAdmin_willRemove else R.string.chatAdmin_willPause))
            Text(draft.reason.trim())
            Text(stringResource(if (draft.warn) R.string.chatAdmin_warningYes else R.string.chatAdmin_warningNo))
            if (draft.pauses) {
                val unitLabel = stringResource(if (draft.unit == ChatPauseUnit.Minutes) R.string.chatAdmin_minutes else R.string.chatAdmin_days)
                Text(stringResource(R.string.chatAdmin_durationSummary, draft.amount, unitLabel))
                previewPublicPauseUntil(context, draft, SystemClock.elapsedRealtime())?.let { until ->
                    Text(stringResource(R.string.chatAdmin_expectedUntil,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(until))))
                }
                Text(stringResource(R.string.chatAdmin_keepLongerPause), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.chatAdmin_scope), style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = {
                if (busy) return@Button
                busy = true; failed = false
                scope.launch {
                    try { onSubmit(draft, operationId) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        failed = true
                        val code = (error as? FirebaseFunctionsException)?.code
                        if (code == FirebaseFunctionsException.Code.PERMISSION_DENIED || code == FirebaseFunctionsException.Code.UNAUTHENTICATED) onDenied()
                        if (code == FirebaseFunctionsException.Code.FAILED_PRECONDITION) { stale = true; reviewing = false; onStale() }
                    } finally { busy = false }
                }
            }, enabled = draft.isValid(context) && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat_admin_confirm")) {
                Text(stringResource(if (busy) R.string.chatAdmin_saving else R.string.chatAdmin_confirm))
            }
            TextButton(onClick = { reviewing = false }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat_admin_edit")) {
                Text(stringResource(R.string.chatAdmin_edit))
            }
        }
        if (failed) Text(stringResource(if (stale) R.string.chatAdmin_stale else R.string.chatAdmin_failed),
            color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("chat_admin_error"))
        TextButton(onClick = onCancel, enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat_admin_cancel")) {
            Text(stringResource(R.string.chatAdmin_cancel))
        }
    }
}

@Composable
private fun YesNoQuestion(label: String, yes: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s1)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2), verticalAlignment = Alignment.CenterVertically) {
            listOf(true, false).forEach { value ->
                FilterChip(selected = yes == value, onClick = { onChange(value) }, enabled = enabled,
                    label = { Text(stringResource(if (value) R.string.chatAdmin_yes else R.string.chatAdmin_no)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("${tag}_${if (value) "yes" else "no"}"))
            }
        }
    }
}
