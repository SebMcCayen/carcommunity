package com.kungsbackacarcommunity.app.clubs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@Composable
internal fun ClubSentInvitationsDialog(clubId: String, repository: ClubRepository, onDismiss: () -> Unit) {
    var page by remember(clubId) { mutableStateOf(ClubPage<SentClubInvitation>(emptyList(), null)) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var confirmation by remember { mutableStateOf<SentClubInvitation?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val operationIds = remember(clubId) { mutableMapOf<SentClubInvitation, String>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    LaunchedEffect(clubId, refresh) {
        loading = true; error = null; page = ClubPage(emptyList(), null)
        try { page = repository.sentInvitations(clubId) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
        finally { loading = false }
    }
    val pending = page.items.filter { (clubInstant(it.expiresAt) ?: 0) > now }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.clubs_pendingInvitations)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let {
                    Text(clubError(it), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { refresh++ }, enabled = !busy && !loading) { Text(stringResource(R.string.clubs_retry)) }
                }
                if (!loading && error == null && pending.isEmpty()) Text(stringResource(R.string.clubs_noPendingInvitations))
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(pending, key = { it.recipientUid }) { invitation ->
                        Column {
                            Text(invitation.displayName.ifBlank { stringResource(R.string.clubs_member) }, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.clubs_invitedAt, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(clubInstant(invitation.createdAt)!!))))
                            TextButton(onClick = { confirmation = invitation }, enabled = !busy && !loading) {
                                Text(stringResource(R.string.clubs_revokeInvitation))
                            }
                        }
                    }
                    if (page.nextCursor != null) item {
                        TextButton(enabled = !busy && !loading, onClick = {
                            busy = true; error = null
                            scope.launch {
                                try {
                                    val cursor = page.nextCursor
                                    val next = repository.sentInvitations(clubId, cursor)
                                    page = ClubPage((page.items + next.items).distinctBy { it.recipientUid }, next.nextCursor?.takeIf { it != cursor })
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { page = ClubPage(emptyList(), null); error = e }
                                finally { busy = false }
                            }
                        }) { Text(stringResource(R.string.clubs_more)) }
                    }
                }
                TextButton(onClick = { refresh++ }, enabled = !busy && !loading) { Text(stringResource(R.string.clubs_refreshInvitations)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.clubs_close)) } })
    confirmation?.let { invitation ->
        AlertDialog(onDismissRequest = { if (!busy) confirmation = null },
            title = { Text(stringResource(R.string.clubs_revokeInvitation)) },
            text = { Text(stringResource(R.string.clubs_revokeInvitationConfirm, invitation.displayName.ifBlank { stringResource(R.string.clubs_member) })) },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("club-invitation-confirm-revoke"), enabled = !busy && (clubInstant(invitation.expiresAt) ?: 0) > now, onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            repository.manage("revokeInvitation", mapOf("clubId" to clubId, "targetUid" to invitation.recipientUid, "createdAt" to invitation.createdAt),
                                operationIds.getOrPut(invitation) { UUID.randomUUID().toString() })
                            confirmation = null; refresh++
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { confirmation = null; page = ClubPage(emptyList(), null); error = e }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.clubs_revokeInvitation)) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !busy) { Text(stringResource(R.string.clubs_cancel)) } })
    }
}
