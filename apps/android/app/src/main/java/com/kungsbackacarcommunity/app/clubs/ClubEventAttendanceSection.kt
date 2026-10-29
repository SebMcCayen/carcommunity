package com.kungsbackacarcommunity.app.clubs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ClubEventAttendanceSection(uid: String, eventId: String, repository: ClubRepository? = null) {
    val context = LocalContext.current
    val repo = remember(uid, repository) { repository ?: FirebaseClubRepository.createIfAvailable(context, uid) }
    key(uid, eventId, repo) {
        val enabledSource = remember { repo?.observePublicAttendanceReady() ?: flowOf(false) }
        val enabled by enabledSource.collectAsState(initial = false)
        if (enabled && repo != null) {
            val source = remember { repo.eventParticipants(eventId) }
            val first by source.collectAsState(initial = emptyList())
            val accessSource = remember { repo.observeAccess() }
            val access by accessSource.collectAsState(initial = ClubAccess.Unverified)
            // A changed first-page boundary starts a new paging session and cancels old requests.
            // Presentation-only changes must not discard the live observers for later pages.
            key(first.map { it.id }) { ClubAttendancePages(repo, eventId, first, access) }
        }
    }
}

@Composable
private fun ClubAttendancePages(repo: ClubRepository, eventId: String, first: List<Club>, access: ClubAccess) {
    var pages by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var afterId by remember { mutableStateOf(first.lastOrNull()?.id) }
    var hasMore by remember { mutableStateOf(first.size == 25) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val scope = rememberCoroutineScope()
    val rest = mutableListOf<Club>()
    for (ids in pages) {
        key(ids) {
            val source = remember(repo, eventId, ids) { repo.observeEventParticipants(eventId, ids) }
            val rows by source.collectAsState(initial = emptyList())
            rest.addAll(rows.filter { it.id in ids })
            Unit
        }
    }
    val clubs = (first + rest).distinctBy { it.id }.sortedBy { it.id }
    val membership = (access as? ClubAccess.Member)?.membership
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.clubs_attending), style = MaterialTheme.typography.titleMedium)
        if (clubs.isEmpty()) Text(stringResource(R.string.clubs_noAttending))
        clubs.forEach { club -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { ClubLogo(club, Modifier.size(40.dp)); Text(club.name) } }
        if (hasMore) TextButton(enabled = !busy, onClick = {
            if (busy) return@TextButton
            val lastId = afterId ?: return@TextButton
            busy = true; scope.launch {
                try {
                    val next = repo.moreEventParticipants(eventId, lastId)
                    // Retain only query IDs. A withdrawn row must never survive in a DTO cache,
                    // and its disappearance must not move the raw pagination boundary backwards.
                    pages = pages + next.map { it.id }.distinct().chunked(25)
                    afterId = next.lastOrNull()?.id ?: lastId
                    hasMore = next.size == 25
                    error = null
                }
                catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.clubs_more)) }
        if (membership?.role?.managesMembers == true) {
            // Role/club/epoch changes discard the private observer and any in-flight action.
            key(membership) { ClubEventAttendanceAction(repo, eventId, membership, busy) }
        }
        error?.let { Text(clubError(it), color = MaterialTheme.colorScheme.error) }
    } }
}

@Composable
private fun ClubEventAttendanceAction(repo: ClubRepository, eventId: String, membership: ClubMembership, pagingBusy: Boolean) {
    var attempt by remember { mutableStateOf(0) }
    key(attempt) {
        val source = remember { repo.eventAttendance(eventId, membership.clubId).attendanceStates() }
        val state by source.collectAsState(initial = ClubEventAttendanceState.Loading)
        var busy by remember { mutableStateOf(false) }
        var mutationError by remember { mutableStateOf<Throwable?>(null) }
        val scope = rememberCoroutineScope()
        val current = mutationError?.let { ClubEventAttendanceState.Unavailable(it) } ?: state
        val attending = (current as? ClubEventAttendanceState.Ready)?.attending
        Text(stringResource(R.string.clubs_publicAttendance), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !pagingBusy && !busy && attending != null, onClick = {
            if (busy || attending == null) return@OutlinedButton
            busy = true
            scope.launch {
                try {
                    repo.setEventAttendance(membership.clubId, eventId, !attending, UUID.randomUUID().toString())
                    // Require fresh status after success; never leave a stale toggle enabled.
                    attempt++
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { mutationError = e }
                finally { busy = false }
            }
        }) { Text(stringResource(if (attending == true) R.string.clubs_unattend else R.string.clubs_attend)) }
        when (current) {
            ClubEventAttendanceState.Loading -> Text(stringResource(R.string.clubs_loading))
            is ClubEventAttendanceState.Unavailable -> {
                Text(clubError(current.error), color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy && !pagingBusy, onClick = { attempt++ }) { Text(stringResource(R.string.clubs_retry)) }
            }
            is ClubEventAttendanceState.Ready -> Unit
        }
    }
}
