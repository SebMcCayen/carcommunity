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
fun SharedClubRoute(
    uid: String,
    onOpenEvent: (String) -> Unit = {},
    repository: ClubRepository? = null,
    onOpenProfile: (String) -> Unit = {},
    initialClubId: String? = null,
    onInitialClubConsumed: () -> Unit = {},
) {
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
        var tab by remember { mutableIntStateOf(if (initialClubId == null) 0 else 1) }
        var openingClub by remember { mutableStateOf(false) }
        var handledInitialClubId by remember { mutableStateOf<String?>(null) }
        val consumeInitialClub by rememberUpdatedState(onInitialClubConsumed)
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
            error = null
            try {
                val freshState = repo.state()
                state = freshState
                // A map link can select our own club in the directory tab.
                // Keep that detail fresh after management without replacing other selections.
                freshState.club?.let { own -> if (selected?.id == own.id) selected = own }
                // Directory reads are public and available to members too.
                directory = repo.directory()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
        }
        LaunchedEffect(repo, enabled, initialClubId, refresh) {
            val target = initialClubId?.takeUnless { it == handledInitialClubId } ?: return@LaunchedEffect
            if (!enabled || repo == null) return@LaunchedEffect
            tab = 1
            selected = null
            openingClub = true
            try {
                selected = repo.get(target)
                handledInitialClubId = target
                consumeInitialClub()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e }
            finally { openingClub = false }
        }
        fun runAction(action: String, fields: Map<String, Any?>, after: () -> Unit = {}) {
            if (busy || repo == null) return
            busy = true
            error = null
            scope.launch {
                try {
                    repo.manage(action, fields, UUID.randomUUID().toString())
                    error = null
                    if (action in setOf("create", "join", "acceptInvitation")) { tab = 0; selected = null }
                    after(); refresh++
                }
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
                        TabRow(selectedTabIndex = tab) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.clubs_myClub)) })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.clubs_allClubs)) })
                        }
                        val club = if (tab == 0) current.club else selected
                        if (openingClub && tab == 1) {
                            Text(stringResource(R.string.clubs_loading))
                        } else if (club == null && tab == 0) {
                            Text(stringResource(R.string.clubs_noMembership))
                            Button(onClick = { error = null; create = true }, enabled = !busy && current.membership == null && !clubCooldownActive(current, now)) { Text(stringResource(R.string.clubs_create)) }
                            TextButton(onClick = { tab = 1 }) { Text(stringResource(R.string.clubs_allClubs)) }
                        } else if (club == null) {
                            Text(stringResource(R.string.clubs_directory), style = MaterialTheme.typography.titleLarge)
                            val otherClubs = directory.items.filter { it.id != current.club?.id }
                            if (otherClubs.isEmpty() && directory.nextCursor == null) Text(stringResource(R.string.clubs_empty))
                            otherClubs.forEach { item -> ClubCard(item, onOpen = { selected = item }) }
                            if (directory.nextCursor != null) TextButton(enabled = !busy, onClick = {
                                busy = true; scope.launch {
                                    try { val next = repo.directory(directory.nextCursor); directory = ClubPage((directory.items + next.items).distinctBy { it.id }, next.nextCursor) }
                                    catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                                    finally { busy = false }
                                }
                            }) { Text(stringResource(R.string.clubs_more)) }
                        } else {
                            val membership = (access as? ClubAccess.Member)?.membership?.takeIf { it.clubId == club.id }
                            if (tab == 1) TextButton(onClick = { selected = null }) { Text(stringResource(R.string.clubs_back)) }
                            ClubCard(club)
                            if (membership == null) {
                                Text(stringResource(R.string.clubs_readOnly))
                                if (club.visibility == "open") Button(onClick = { runAction("join", mapOf("clubId" to club.id)) },
                                    enabled = !busy && current.membership == null && !clubCooldownActive(current, now)) { Text(stringResource(R.string.clubs_join)) }
                            } else {
                                // Losing proof disposes every private child, including rosters/calendar/drafts.
                                key(membership) {
                                    ClubMemberContent(uid, club, membership, repo, onOpenEvent, onUpdated = { refresh++ }, onOpenProfile = onOpenProfile)
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
                confirmLeave = false; state?.club?.let { club -> runAction("leave", mapOf("clubId" to club.id)) { selected = null; state = null } }
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
