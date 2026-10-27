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
    val enabledSource = remember(repo) { repo?.observeEnabled() ?: flowOf(false) }
    val enabled by enabledSource.collectAsState(initial = false)
    if (!enabled || repo == null) return
    key(uid, eventId, repo) {
        val source = remember { repo.eventParticipants(eventId) }
        val first by source.collectAsState(initial = emptyList())
        val accessSource = remember { repo.observeAccess() }
        val access by accessSource.collectAsState(initial = ClubAccess.Unverified)
        var rest by remember { mutableStateOf<List<Club>>(emptyList()) }
        var hasMore by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<Throwable?>(null) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(first) { rest = emptyList(); hasMore = first.size == 25 }
        val clubs = (first + rest).distinctBy { it.id }
        val membership = (access as? ClubAccess.Member)?.membership
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.clubs_attending), style = MaterialTheme.typography.titleMedium)
            if (clubs.isEmpty()) Text(stringResource(R.string.clubs_noAttending))
            clubs.forEach { club -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { ClubLogo(club, Modifier.size(40.dp)); Text(club.name) } }
            if (hasMore) TextButton(enabled = !busy, onClick = {
                val lastId = clubs.lastOrNull()?.id ?: return@TextButton
                busy = true; scope.launch {
                    try { val next = repo.moreEventParticipants(eventId, lastId); rest = (rest + next).distinctBy { it.id }; hasMore = next.size == 25; error = null }
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
}
