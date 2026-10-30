package com.kungsbackacarcommunity.app.clubs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.AeroPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** State is keyed to the authenticated UID and is never saved across accounts. */
@Composable
fun SharedClubRoute(uid: String, onOpenEvent: (String) -> Unit = {}, repository: ClubRepository? = null) {
    val context = LocalContext.current
    val repo = remember(uid, repository) { repository ?: FirebaseClubRepository.createIfAvailable(context, uid) }
    key(uid, repo) {
        val enabledSource = remember(repo) { repo?.observeEnabled() ?: kotlinx.coroutines.flow.flowOf(false) }
        val enabled by enabledSource.collectAsState(initial = false)
        val accessSource = remember(repo) { repo?.observeAccess() ?: kotlinx.coroutines.flow.flowOf(ClubAccess.Unverified) }
        val access by accessSource.collectAsState(initial = ClubAccess.Unverified)
        val invitationSource = remember(repo) { repo?.observeInvitationsRevision() ?: kotlinx.coroutines.flow.flowOf(null) }
        val invitationsRevision by invitationSource.collectAsStateWithLifecycle(initialValue = null)
        var state by remember { mutableStateOf<ClubState?>(null) }
        var directory by remember { mutableStateOf(ClubPage<Club>(emptyList(), null)) }
        var selected by remember { mutableStateOf<Club?>(null) }
        var error by remember { mutableStateOf<Throwable?>(null) }
        var busy by remember { mutableStateOf(false) }
        var refresh by remember { mutableIntStateOf(0) }
        var create by remember { mutableStateOf(false) }
        var confirmLeave by remember { mutableStateOf(false) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) { refresh++; now = System.currentTimeMillis() }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(repo, enabled, access, invitationsRevision, refresh) {
            if (!enabled || repo == null) { state = null; selected = null; directory = ClubPage(emptyList(), null); return@LaunchedEffect }
            try {
                state = repo.state()
                val own = state?.club
                if (own != null) selected = own
                else if (access !is ClubAccess.Member) {
                    if (selected?.id == (access as? ClubAccess.Member)?.membership?.clubId) selected = null
                    directory = repo.directory()
                }
                error = null
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
        }
        fun runAction(action: String, fields: Map<String, Any?>, after: () -> Unit = {}) {
            if (busy || repo == null) return
            busy = true
            error = null
            scope.launch {
                try { repo.manage(action, fields, UUID.randomUUID().toString()); error = null; after(); refresh++ }
                catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                finally { busy = false }
            }
        }
        AeroPage(title = stringResource(R.string.clubs_title), ownerShortcutEnabled = false) {
            when {
                !enabled || repo == null -> Text(stringResource(R.string.clubs_unavailable))
                else -> {
                    error?.let { Text(clubError(it), color = MaterialTheme.colorScheme.error); TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.clubs_retry)) } }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val current = state
                    if (current == null) { Text(stringResource(R.string.clubs_loading)) }
                    else {
                        if (current.legacyConflict) Text(stringResource(R.string.clubs_conflict), color = MaterialTheme.colorScheme.error)
                        clubInstant(current.joinAllowedAt)?.takeIf { it > now }?.let {
                            Text(stringResource(R.string.clubs_cooldown, clubDate(it)))
                        }
                        ClubInvitations(
                            invitations = current.invitations,
                            now = now,
                            busy = busy || invitationsRevision == null || access == ClubAccess.Unverified,
                            canAccept = current.membership == null && access == ClubAccess.None &&
                                !current.legacyConflict && !clubCooldownActive(current, now),
                            onRespond = { invitation, accept ->
                                runAction(if (accept) "acceptInvitation" else "declineInvitation",
                                    mapOf("clubId" to invitation.clubId))
                            },
                        )
                        val club = selected
                        if (club == null) {
                            Text(stringResource(R.string.clubs_directory), style = MaterialTheme.typography.titleLarge)
                            Button(onClick = { error = null; create = true }, enabled = !busy && current.membership == null && !clubCooldownActive(current, now)) { Text(stringResource(R.string.clubs_create)) }
                            if (directory.items.isEmpty()) Text(stringResource(R.string.clubs_empty))
                            directory.items.forEach { item -> ClubCard(item, onOpen = { selected = item }) }
                            if (directory.nextCursor != null) TextButton(enabled = !busy, onClick = {
                                busy = true; scope.launch {
                                    try { val next = repo.directory(directory.nextCursor); directory = ClubPage((directory.items + next.items).distinctBy { it.id }, next.nextCursor) }
                                    catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                                    finally { busy = false }
                                }
                            }) { Text(stringResource(R.string.clubs_more)) }
                        } else {
                            val membership = (access as? ClubAccess.Member)?.membership?.takeIf { it.clubId == club.id }
                            if (current.club?.id != club.id) TextButton(onClick = { selected = null }) { Text(stringResource(R.string.clubs_back)) }
                            ClubCard(club)
                            if (membership == null) {
                                Text(stringResource(R.string.clubs_readOnly))
                                if (club.visibility == "open") Button(onClick = { runAction("join", mapOf("clubId" to club.id)) },
                                    enabled = !busy && current.membership == null && !clubCooldownActive(current, now)) { Text(stringResource(R.string.clubs_join)) }
                            } else {
                                // Losing proof disposes every private child, including rosters/calendar/drafts.
                                key(membership) {
                                    ClubMemberContent(uid, club, membership, repo, onOpenEvent, onUpdated = { refresh++ })
                                }
                                OutlinedButton(onClick = { confirmLeave = true }, enabled = !busy) { Text(stringResource(R.string.clubs_leave)) }
                            }
                        }
                    }
                }
            }
        }
        if (create) ClubEditor(null, busy = busy, error = error, onDismiss = { create = false }, onSave = { fields ->
            runAction("create", fields) { create = false }
        })
        if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text(stringResource(R.string.clubs_leave)) },
            text = { Text(stringResource(R.string.clubs_leaveConfirm)) }, confirmButton = { TextButton(onClick = {
                confirmLeave = false; selected?.let { club -> runAction("leave", mapOf("clubId" to club.id)) { selected = null; state = null } }
            }, enabled = !busy) { Text(stringResource(R.string.clubs_confirm)) } }, dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.clubs_cancel)) } })
    }
}

@Composable
internal fun ClubEditor(club: Club?, busy: Boolean, onDismiss: () -> Unit, error: Throwable? = null,
    catalogLoader: (suspend () -> ClubLocationCatalog)? = null, onSave: (Map<String, Any?>) -> Unit) {
    var name by remember(club?.id) { mutableStateOf(club?.name.orEmpty()) }
    var description by remember(club?.id) { mutableStateOf(club?.description.orEmpty()) }
    var location by remember(club?.id) { mutableStateOf(club?.location.orEmpty()) }
    var selection by remember(club?.id) { mutableStateOf(ClubLocationSelection()) }
    var catalogAttempt by remember(club?.id) { mutableIntStateOf(0) }
    val catalogLoad = if (club == null) rememberClubLocationCatalog(catalogAttempt, catalogLoader) else null
    val catalog = (catalogLoad as? ClubCatalogLoad.Ready)?.catalog
    var closed by remember(club?.id) { mutableStateOf(club?.visibility == "closed") }
    val fields = clubPresentationFields(name, description, if (club == null) catalog?.wireLocation(selection) else location, closed)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(if (club == null) R.string.clubs_create else R.string.clubs_edit)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { if (it.length <= 60) name = it }, label = { Text(stringResource(R.string.clubs_name)) }, singleLine = true, enabled = !busy)
            OutlinedTextField(description, { if (it.length <= 500) description = it }, label = { Text(stringResource(R.string.clubs_description)) }, minLines = 3, enabled = !busy)
            if (club != null) {
                // Existing string-only locations can be ambiguous. Keep intentional legacy
                // editing instead of guessing a county or silently replacing stored text.
                OutlinedTextField(location, { if (it.length <= 80) location = it }, label = { Text(stringResource(R.string.clubs_location)) }, singleLine = true, enabled = !busy)
            } else when (catalogLoad) {
                is ClubCatalogLoad.Ready -> ClubLocationPicker(catalogLoad.catalog, selection, busy) { selection = it }
                ClubCatalogLoad.Failed -> {
                    Text(stringResource(R.string.clubs_locationCatalogError), color = MaterialTheme.colorScheme.error)
                    TextButton(enabled = !busy, onClick = { catalogAttempt++ }) { Text(stringResource(R.string.clubs_retry)) }
                }
                else -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.clubs_locationLoading))
                }
            }
            Row { Switch(closed, { closed = it }, enabled = !busy); Text(stringResource(if (closed) R.string.clubs_closed else R.string.clubs_open), Modifier.padding(12.dp)) }
            if (fields == null) Text(stringResource(R.string.clubs_invalidForm), style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(clubError(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        } }, confirmButton = { TextButton(enabled = fields != null && !busy, onClick = { if (!busy) fields?.let(onSave) }) { Text(stringResource(R.string.clubs_save)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.clubs_cancel)) } })
}
