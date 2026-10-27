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
        if (!enabled || repo == null) return@key
        val source = remember { repo.eventParticipants(eventId) }
        val first by source.collectAsState(initial = emptyList())
        val accessSource = remember { repo.observeAccess() }
        val access by accessSource.collectAsState(initial = ClubAccess.Unverified)
        // A changed first-page boundary starts a new paging session and cancels old requests.
        // Presentation-only changes must not discard the live observers for later pages.
        key(first.map { it.id }) { ClubAttendancePages(repo, eventId, first, access) }
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
    val rest = pages.flatMap { ids ->
        key(ids) {
            val source = remember { repo.observeEventParticipants(eventId, ids) }
            val rows by source.collectAsState(initial = emptyList())
            rows.filter { it.id in ids }
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
            // The club's private calendar is authoritative even beyond the first public page.
            val attendanceSource = remember(membership.clubId) { repo.eventAttendance(eventId, membership.clubId) }
            val attending by attendanceSource.collectAsState(initial = null)
            Text(stringResource(R.string.clubs_publicAttendance), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy && attending != null, onClick = {
                busy = true; scope.launch {
                    try { repo.setEventAttendance(membership.clubId, eventId, attending != true, UUID.randomUUID().toString()); error = null }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                    finally { busy = false }
                }
            }) { Text(stringResource(if (attending == true) R.string.clubs_unattend else R.string.clubs_attend)) }
        }
        error?.let { Text(clubError(it), color = MaterialTheme.colorScheme.error) }
    } }
}
