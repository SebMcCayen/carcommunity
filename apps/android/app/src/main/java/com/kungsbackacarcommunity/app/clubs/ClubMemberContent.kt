package com.kungsbackacarcommunity.app.clubs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.friends.FirebaseFriendsRepository
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendsResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun ClubMemberContent(uid: String, club: Club, membership: ClubMembership, repository: ClubRepository,
    onOpenEvent: (String) -> Unit, onUpdated: () -> Unit, onOpenProfile: (String) -> Unit = {}) {
    var members by remember { mutableStateOf(ClubPage<ClubMember>(emptyList(), null)) }
    var refresh by remember { mutableIntStateOf(0) }
    var avatarGeneration by remember { mutableIntStateOf(0) }
    var avatars by remember { mutableStateOf<Map<String, ClubMemberAvatar>>(emptyMap()) }
    var loadingMembers by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var edit by remember { mutableStateOf(false) }
    var invite by remember { mutableStateOf(false) }
    var disband by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Pair<ClubMemberAction, ClubMember>?>(null) }
    val scope = rememberCoroutineScope()
    fun hydrateAvatars(page: ClubPage<ClubMember>, generation: Int) {
        val ids = page.items.map { it.uid }.toSet()
        if (ids.isEmpty()) return
        scope.launch {
            try {
                val loaded = currentClubAvatarPage(
                    request = { repository.memberAvatars(club.id, ids) },
                    isCurrent = { generation == avatarGeneration },
                )
                if (loaded != null) avatars = avatars + loaded
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Avatar failure cannot hide the already-loaded roster. */ }
        }
    }
    LaunchedEffect(club.id, refresh) {
        loadingMembers = true
        avatarGeneration++
        val generation = avatarGeneration
        try {
            members = repository.members(club.id)
            avatars = avatars.filterKeys { key -> members.items.any { it.uid == key } }
            error = null
            hydrateAvatars(members, generation)
        }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
        finally { loadingMembers = false }
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
    error?.let { Text(clubError(it), color = MaterialTheme.colorScheme.error) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    ClubStatistics(club.stats)
    ClubCalendar(repository, club.id, membership.role.managesMembers, onOpenEvent)
    Text(stringResource(R.string.clubs_members), style = MaterialTheme.typography.titleMedium)
    if (membership.role.managesMembers && club.visibility == "closed") {
        OutlinedButton(onClick = { invite = true }, enabled = !busy) { Text(stringResource(R.string.clubs_invite)) }
    }
    if (loadingMembers && members.items.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
    else if (members.items.isEmpty()) {
        Text(stringResource(R.string.clubPage_noMembers), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else Card(Modifier.fillMaxWidth(), colors = clubCardColors()) {
        Column {
            members.items.forEachIndexed { index, member ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                key(member.uid) {
                    ClubMemberRow(member.withAvatar(avatars), uid, membership.role, club.moderatorCount, busy,
                        onOpenProfile = onOpenProfile, onAction = { operation, target -> confirmation = operation to target })
                }
            }
        }
    }
    if (members.nextCursor != null) TextButton(enabled = !busy, onClick = {
        busy = true; scope.launch {
            try { val page = repository.members(club.id, members.nextCursor); members = ClubPage((members.items + page.items).distinctBy { it.uid }, page.nextCursor); hydrateAvatars(page, avatarGeneration) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
            finally { busy = false }
        }
    }) { Text(stringResource(R.string.clubs_more)) }
    if (membership.role == ClubRole.Owner) {
        Text(stringResource(R.string.clubs_manage), style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth(), colors = clubCardColors()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { error = null; edit = true }, enabled = !busy) { Text(stringResource(R.string.clubs_edit)) }
                ClubLogoControl(club.id, repository, enabled = !busy, onBusy = { busy = it },
                    onError = { error = it }, onUpdated = onUpdated)
                OutlinedButton(onClick = { disband = true }, enabled = !busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.clubs_disband))
                }
            }
        }
    }
    if (edit) ClubEditor(club, busy, { edit = false }, error = error) { fields -> action("update", fields) { edit = false } }
    if (invite) ClubInviteDialog(busy, { invite = false }) { target -> action("invite", mapOf("targetUid" to target)) { invite = false } }
    if (disband) AlertDialog(onDismissRequest = { disband = false }, title = { Text(stringResource(R.string.clubs_disband)) },
        text = { Text(stringResource(R.string.clubs_disbandConfirm)) }, confirmButton = { TextButton(onClick = { disband = false; action("disband") }, enabled = !busy) { Text(stringResource(R.string.clubs_confirm)) } },
        dismissButton = { TextButton(onClick = { disband = false }) { Text(stringResource(R.string.clubs_cancel)) } })
    confirmation?.let { (operation, target) ->
        val currentTarget = members.items.firstOrNull { it.uid == target.uid }
        val allowed = currentTarget != null &&
            operation in clubMemberActions(uid, membership.role, currentTarget) && operation.enabled(club.moderatorCount, busy)
        AlertDialog(onDismissRequest = { if (!busy) confirmation = null },
            title = { Text(operation.label()) },
            text = { Text(stringResource(R.string.clubs_memberActionConfirm, target.displayName)) },
            confirmButton = { TextButton(enabled = allowed, onClick = {
                if (allowed) {
                    confirmation = null
                    action(operation.operation, operation.fields(target.uid))
                }
            }) { Text(stringResource(R.string.clubs_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !busy) { Text(stringResource(R.string.clubs_cancel)) } })
    }
}

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
