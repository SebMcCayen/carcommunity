package com.kungsbackacarcommunity.app.clubs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.friends.FirebaseFriendsRepository
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendsResult
import com.kungsbackacarcommunity.app.media.ImageCompressor
import com.kungsbackacarcommunity.app.media.rememberImagePickLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.Locale

@Composable
internal fun ClubMemberContent(uid: String, club: Club, membership: ClubMembership, repository: ClubRepository,
    onOpenEvent: (String) -> Unit, onUpdated: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    var members by remember { mutableStateOf(ClubPage<ClubMember>(emptyList(), null)) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var edit by remember { mutableStateOf(false) }
    var invite by remember { mutableStateOf(false) }
    var disband by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Pair<String, ClubMember>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(club.id, refresh) {
        try { members = repository.members(club.id); error = null }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
    }
    fun action(action: String, fields: Map<String, Any?> = emptyMap(), done: () -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { repository.manage(action, fields + mapOf("clubId" to club.id), UUID.randomUUID().toString()); error = null; refresh++; done(); onUpdated() }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
            finally { busy = false }
        }
    }
    val imagePicker = rememberImagePickLauncher(maxBytes = 12L * 1024 * 1024,
        onPickFailed = { error = ClubException("invalid-logo") }) { pick ->
        if (pick != null && !busy) {
            busy = true
            try {
                // Re-encode even when source is smaller: never upload raw EXIF-bearing picks.
                var safe = ImageCompressor.compressForPublicUpload(pick, maxDimension = 512, quality = 80)
                if (safe != null && safe.sizeBytes > 256 * 1024) safe = ImageCompressor.compressForPublicUpload(pick, maxDimension = 384, quality = 65)
                if (safe == null || safe.contentType != "image/jpeg" || safe.sizeBytes > 256 * 1024) throw ClubException("invalid-logo")
                val path = repository.uploadLogo(club.id, safe.bytes)
                repository.manage("update", mapOf("clubId" to club.id, "logoPath" to path), UUID.randomUUID().toString())
                error = null; onUpdated()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
            finally { busy = false }
        }
    }
    error?.let { Text(clubError(it), color = MaterialTheme.colorScheme.error) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(stringResource(R.string.clubs_statistics), style = MaterialTheme.typography.titleMedium)
    // A wrapping column keeps long translated labels and enlarged text fully readable.
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatLine(stringResource(R.string.clubs_drives), club.stats.driveCount.toString())
        StatLine(stringResource(R.string.clubs_distance), String.format(locale, "%.1f", club.stats.distanceMeters / 1000))
        StatLine(stringResource(R.string.clubs_duration), String.format(locale, "%.1f", club.stats.durationSeconds / 3600.0))
        Text(stringResource(R.string.clubs_statsNote), style = MaterialTheme.typography.bodySmall)
    } }
    ClubCalendar(repository, club.id, membership.role.managesMembers, onOpenEvent)
    if (membership.role == ClubRole.Owner) {
        Text(stringResource(R.string.clubs_manage), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { error = null; edit = true }, enabled = !busy) { Text(stringResource(R.string.clubs_edit)) }
        OutlinedButton(onClick = imagePicker::pickImage, enabled = !busy) { Text(stringResource(R.string.clubs_logo)) }
        Text(stringResource(R.string.clubs_logoHint), style = MaterialTheme.typography.bodySmall)
    }
    if (membership.role.managesMembers && club.visibility == "closed") OutlinedButton(onClick = { invite = true }, enabled = !busy) { Text(stringResource(R.string.clubs_invite)) }
    Text(stringResource(R.string.clubs_members), style = MaterialTheme.typography.titleMedium)
    members.items.forEach { member ->
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text(member.displayName.ifBlank { stringResource(R.string.clubs_member) }, style = MaterialTheme.typography.titleSmall)
            Text(member.role.label(), style = MaterialTheme.typography.bodySmall)
            if (membership.role == ClubRole.Owner && member.uid != uid) {
                TextButton(enabled = !busy && (member.role == ClubRole.Moderator || club.moderatorCount < 3), onClick = { confirmation = "moderator" to member }) {
                    Text(stringResource(if (member.role == ClubRole.Moderator) R.string.clubs_removeModerator else R.string.clubs_makeModerator))
                }
                TextButton(enabled = !busy, onClick = { confirmation = "transfer" to member }) { Text(stringResource(R.string.clubs_transfer)) }
            }
            if (membership.role.canRemove(uid, member)) TextButton(enabled = !busy, onClick = { confirmation = "remove" to member }) { Text(stringResource(R.string.clubs_remove)) }
        } }
    }
    if (members.nextCursor != null) TextButton(enabled = !busy, onClick = {
        busy = true; scope.launch {
            try { val page = repository.members(club.id, members.nextCursor); members = ClubPage((members.items + page.items).distinctBy { it.uid }, page.nextCursor) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
            finally { busy = false }
        }
    }) { Text(stringResource(R.string.clubs_more)) }
    if (membership.role == ClubRole.Owner) OutlinedButton(onClick = { disband = true }, enabled = !busy) { Text(stringResource(R.string.clubs_disband)) }
    if (edit) ClubEditor(club, busy, { edit = false }, error = error) { fields -> action("update", fields) { edit = false } }
    if (invite) ClubInviteDialog(busy, { invite = false }) { target -> action("invite", mapOf("targetUid" to target)) { invite = false } }
    if (disband) AlertDialog(onDismissRequest = { disband = false }, title = { Text(stringResource(R.string.clubs_disband)) },
        text = { Text(stringResource(R.string.clubs_disbandConfirm)) }, confirmButton = { TextButton(onClick = { disband = false; action("disband") }, enabled = !busy) { Text(stringResource(R.string.clubs_confirm)) } },
        dismissButton = { TextButton(onClick = { disband = false }) { Text(stringResource(R.string.clubs_cancel)) } })
    confirmation?.let { (operation, target) -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(stringResource(R.string.clubs_action)) },
        text = { Text(stringResource(R.string.clubs_memberActionConfirm, target.displayName)) },
        confirmButton = { TextButton(enabled = !busy, onClick = { confirmation = null; action(operation, mapOf("targetUid" to target.uid) + if (operation == "moderator") mapOf("enabled" to (target.role != ClubRole.Moderator)) else emptyMap()) }) { Text(stringResource(R.string.clubs_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.clubs_cancel)) } }) }
}

@Composable
private fun StatLine(label: String, value: String) { Text("$label: $value", style = MaterialTheme.typography.bodyLarge) }

@Composable
private fun ClubInviteDialog(busy: Boolean, onDismiss: () -> Unit, onInvite: (String) -> Unit) {
    val context = LocalContext.current
    var friends by remember { mutableStateOf<List<FriendSummary>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(refresh) {
        loading = true
        try {
            when (val data = FirebaseFriendsRepository.createIfAvailable(context)?.list()) {
                is FriendsResult.Loaded -> { friends = data.data.friends; failed = false }
                else -> failed = true
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.clubs_inviteFriends)) },
        text = { Column {
            OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.clubs_search)) }, singleLine = true)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) { Text(stringResource(R.string.clubs_error)); TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.clubs_retry)) } }
            else if (!loading && friends.isEmpty()) Text(stringResource(R.string.clubs_noFriends))
            LazyColumn(Modifier.heightIn(max = 320.dp)) { items(friends.filter { it.displayName.orEmpty().contains(search, ignoreCase = true) }.sortedBy { it.displayName }, key = { it.uid }) { friend ->
                TextButton(enabled = !busy, onClick = { onInvite(friend.uid) }) { Text(friend.displayName ?: stringResource(R.string.clubs_member)) }
            } }
        } }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.clubs_close)) } })
}
