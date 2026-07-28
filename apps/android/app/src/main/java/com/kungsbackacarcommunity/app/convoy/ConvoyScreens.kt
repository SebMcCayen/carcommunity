package com.kungsbackacarcommunity.app.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.design.LocalKccStatusColors
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendsStatus
import com.kungsbackacarcommunity.app.friends.messageRes
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.shell.AeroLazyPage
import com.kungsbackacarcommunity.app.shell.AeroPage
import com.kungsbackacarcommunity.app.shell.AeroPageTitle
import com.kungsbackacarcommunity.app.shell.aeroLazyContentPadding

/** Test tag on the create-convoy submit button, so a UI test can assert it goes
 *  inert (disabled) during the create + post-create hand-off to the map. */
const val CONVOY_CREATE_SUBMIT_TAG = "convoy-create-submit"

// ---------------------------------------------------------------------------
// Convoy list
// ---------------------------------------------------------------------------

/**
 * The convoy management landing: the caller's convoys (status + member avatars,
 * a green dot on accepted members) and any pending invites with Accept/Decline.
 * Every backend error is surfaced via a `convoy.*` string keyed off the mapped
 * [ConvoyActionError] — never a raw message. Back is handled centrally by the
 * shell, so no Back affordance is rendered here.
 */
@Composable
fun ConvoyListScreen(
    status: ConvoyListStatus,
    actionError: ConvoyActionError?,
    busyConvoys: Set<String>,
    onCreate: () -> Unit,
    onOpenConvoy: (String) -> Unit,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    onClearActionError: () -> Unit,
    modifier: Modifier = Modifier,
    // The convoy a tapped invite notification was about, if that is why we are
    // here: its invite is pulled to the top of the section so a member with
    // several open invites is not left hunting for the one they just tapped.
    inviteDeepLinkConvoyId: String? = null,
    // Set only when that invite is NOT waiting any more — ended, already
    // answered, or gone. Shown as a notice so the tap is answered with a reason
    // instead of an unexplained list. Null in every other case.
    inviteDeepLinkOutcome: ConvoyInviteDeepLinkOutcome? = null,
    onDismissInviteDeepLinkNotice: () -> Unit = {},
) {
    // ITEM 1 client guard (UX only — the backend is the real gate): a caller who
    // is already an ACTIVE participant of a convoy (owner, or an accepted member
    // of a non-ended convoy — exactly what ConvoyBar.activeConvoy selects) may not
    // create OR accept into a second one. The affordances are disabled with an
    // explanation so they are TOLD, rather than hitting a raw failed-precondition.
    val alreadyInConvoy = ConvoyBar.activeConvoy(status) != null
    AeroLazyPage(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = aeroLazyContentPadding(),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s4),
        ) {
            item(key = "title") { AeroPageTitle(stringResource(R.string.convoy_title)) }

            item(key = "create") {
                Button(
                    onClick = onCreate,
                    enabled = !alreadyInConvoy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.convoy_createAction))
                }
                if (alreadyInConvoy) {
                    Text(
                        text = stringResource(R.string.convoy_alreadyInConvoyCreateHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            actionError?.let { error ->
                item(key = "action-error") {
                    ErrorBanner(text = stringResource(error.messageRes()), onDismiss = onClearActionError)
                }
            }

            // Why the invite you tapped isn't here. Deliberately an ErrorBanner
            // rather than a silent omission: the member acted, and an action
            // that produces no visible consequence is the bug being fixed.
            inviteDeepLinkOutcome?.let { outcome ->
                item(key = "invite-deep-link-notice") {
                    ErrorBanner(
                        text = stringResource(outcome.messageRes()),
                        onDismiss = onDismissInviteDeepLinkNotice,
                    )
                }
            }

            when (status) {
                ConvoyListStatus.Loading -> item(key = "loading") { CircularProgressIndicator() }

                is ConvoyListStatus.Error ->
                    item(key = "load-error") {
                        InfoNoticeCard(text = stringResource(status.error.messageRes()))
                    }

                is ConvoyListStatus.Loaded -> {
                    if (status.pendingInvites.isNotEmpty()) {
                        item(key = "invites-header") {
                            SectionHeader(stringResource(R.string.convoy_invitesTitle))
                        }
                        val invites =
                            ConvoyInviteDeepLink.inviteesFirst(
                                status.pendingInvites,
                                inviteDeepLinkConvoyId,
                            )
                        items(invites, key = { "invite-${it.convoyId}" }) { convoy ->
                            PendingInviteRow(
                                convoy = convoy,
                                working = convoy.convoyId in busyConvoys,
                                // Accepting joins a SECOND convoy — blocked while
                                // already in one. Declining stays available (it
                                // commits to nothing).
                                acceptBlocked = alreadyInConvoy,
                                onAccept = { onAccept(convoy.convoyId) },
                                onDecline = { onDecline(convoy.convoyId) },
                            )
                        }
                    }

                    item(key = "mine-header") {
                        SectionHeader(stringResource(R.string.convoy_myConvoysTitle))
                    }
                    if (status.myConvoys.isEmpty()) {
                        item(key = "mine-empty") {
                            Text(
                                text = stringResource(R.string.convoy_emptyMine),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(status.myConvoys, key = { "convoy-${it.convoyId}" }) { convoy ->
                            ConvoyCardRow(convoy = convoy, onClick = { onOpenConvoy(convoy.convoyId) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Transient placeholder for the detail route while the target convoy is not (yet)
 * resolvable — the list snapshot is still loading, or the convoy fell out of it
 * and the route is about to pop back to the list. Renders a neutral centered
 * spinner rather than a fully-wired-looking list whose actions would be dead.
 */
@Composable
fun ConvoyLoadingScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ConvoyCardRow(convoy: ConvoySummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = convoy.displayTitle(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = KccSpacing.s3),
                )
                StatusChip(convoy.status)
            }
            // A compact cluster of member avatars, each with a green dot when the
            // member has accepted (inviteStatus == Accepted).
            AvatarCluster(members = convoy.members)
        }
    }
}

/** A horizontal run of small member avatars with an accepted (green-dot) badge. */
@Composable
private fun AvatarCluster(members: List<ConvoyMember>, max: Int = 6) {
    Row(horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
        members.take(max).forEach { member ->
            MemberAvatar(
                avatarPath = member.avatarPath,
                size = KccSpacing.s8,
                accepted = member.inviteStatus == ConvoyInviteStatus.Accepted,
            )
        }
        val overflow = members.size - max
        if (overflow > 0) {
            Box(
                modifier =
                    Modifier
                        .size(KccSpacing.s8)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$overflow",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PendingInviteRow(
    convoy: ConvoySummary,
    working: Boolean,
    acceptBlocked: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            Text(
                text = convoy.displayTitle(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.convoy_invitedByLabel, convoy.ownerDisplayName()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                Button(onClick = onAccept, enabled = !working && !acceptBlocked) {
                    Text(stringResource(R.string.convoy_accept))
                }
                OutlinedButton(onClick = onDecline, enabled = !working) {
                    Text(stringResource(R.string.convoy_decline))
                }
            }
            if (acceptBlocked) {
                Text(
                    text = stringResource(R.string.convoy_alreadyInConvoyAcceptHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Create convoy
// ---------------------------------------------------------------------------

/**
 * Create-a-convoy: a multi-select picker over the caller's friends (from the
 * shared FriendsRepository), wired to `convoy-create`. Convoys are unnamed —
 * there is no title input; the list/detail render a neutral fallback label.
 *
 * On success there is NO confirmation page: the host observes
 * [CreateConvoyState.Created] and dismisses the whole convoy surface to the map,
 * where the convoy bar shows the new convoy. This screen only renders the picker
 * (and, inline, a create FAILURE) — a success just leaves it.
 */
@Composable
fun CreateConvoyScreen(
    friendsStatus: FriendsStatus,
    createState: CreateConvoyState,
    selectedUids: Set<String>,
    onToggleFriend: (String) -> Unit,
    onRetryFriends: (() -> Unit)?,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Inert while the create is in flight (Working) AND once it succeeds (Created):
    // on success this screen no longer swaps to a confirmation page, so without this
    // the picker + submit button would re-enable (Created is not Working, and the
    // selection is still non-empty) during the brief hand-off window before the host
    // navigates to the map — allowing a second `convoy.create` / a selection change.
    // Blocking both states keeps the surface interaction-proof until it leaves.
    val handingOff =
        createState is CreateConvoyState.Working || createState is CreateConvoyState.Created
    AeroLazyPage(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = aeroLazyContentPadding(),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s4),
        ) {
            item(key = "title") { AeroPageTitle(stringResource(R.string.convoy_createTitle)) }

            item(key = "pick-header") {
                SectionHeader(stringResource(R.string.convoy_pickFriendsTitle))
            }

            when (friendsStatus) {
                FriendsStatus.Loading -> item(key = "friends-loading") { CircularProgressIndicator() }

                is FriendsStatus.Error ->
                    item(key = "friends-error") {
                        Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                            // Render the SPECIFIC mapped failure, not one flat
                            // notice. The picker used to show
                            // convoy_friendsUnavailable for every error, so a
                            // backend outage, an expired session and a dropped
                            // connection were indistinguishable here — and the
                            // user was given no advice they could act on.
                            InfoNoticeCard(text = stringResource(friendsStatus.error.messageRes()))
                            // Only when a retry can actually work (a live
                            // coordinator); a null-repo build shows the notice alone.
                            if (onRetryFriends != null) {
                                TextButton(onClick = onRetryFriends) {
                                    Text(stringResource(R.string.convoy_friendsRetry))
                                }
                            }
                        }
                    }

                is FriendsStatus.Loaded ->
                    if (friendsStatus.friends.isEmpty()) {
                        item(key = "friends-empty") {
                            InfoNoticeCard(text = stringResource(R.string.convoy_noFriends))
                        }
                    } else {
                        items(friendsStatus.friends, key = { "f-${it.uid}" }) { friend ->
                            SelectableFriendRow(
                                friend = friend,
                                selected = friend.uid in selectedUids,
                                enabled = !handingOff,
                                onToggle = { onToggleFriend(friend.uid) },
                            )
                        }
                    }
            }

            if (createState is CreateConvoyState.Error) {
                item(key = "create-error") {
                    Text(
                        text = stringResource(createState.error.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            item(key = "submit") {
                // Disabled (and showing the spinner) both while Working and once
                // Created, so no second submit can fire during the hand-off to the
                // map. The spinner reads as a brief blocking state until navigation.
                Button(
                    onClick = onSubmit,
                    enabled = !handingOff && selectedUids.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().testTag(CONVOY_CREATE_SUBMIT_TAG),
                ) {
                    if (handingOff) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(KccSpacing.s6),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(stringResource(R.string.convoy_createSubmit))
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectableFriendRow(
    friend: FriendSummary,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = selected,
                        enabled = enabled,
                        role = Role.Checkbox,
                        onValueChange = { onToggle() },
                    )
                    .padding(KccSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            MemberAvatar(avatarPath = friend.avatarPath, size = KccSpacing.s10, accepted = false)
            Text(
                text = friend.displayName ?: stringResource(R.string.convoy_unknownMember),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(horizontal = KccSpacing.s3),
            )
            // Presentational only — the row's toggleable owns the click +
            // accessibility semantics (Role.Checkbox), avoiding a duplicate toggle
            // target. Mirrors the app's established selectable-row pattern.
            Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
        }
    }
}

/**
 * Invite-more-people picker for an EXISTING convoy, opened from the convoy bar's
 * invite control. The same friend multi-select as [CreateConvoyScreen] (reusing
 * [SelectableFriendRow]), wired to `convoy-invite` — but it grows the convoy the
 * caller is already in, so there is no "created" result to continue into: the host
 * observes [InviteConvoyState.Done] and closes the picker with a confirmation.
 *
 * A failed invite is surfaced inline here (the mapped [ConvoyActionError] message),
 * never a silent no-op.
 */
@Composable
fun ConvoyInvitePickerScreen(
    friendsStatus: FriendsStatus,
    inviteState: InviteConvoyState,
    selectedUids: Set<String>,
    onToggleFriend: (String) -> Unit,
    onRetryFriends: (() -> Unit)?,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    // Uids to hide from the candidate list: this convoy's current members plus
    // the caller (see ConvoySummary.inviteExcludedUids). Only friends NOT already
    // in the convoy are offered, so tapping a row always adds someone new. Derived
    // from the live convoy roster, so a friend who joins while the picker is open
    // drops out on the next recomposition.
    excludedUids: Set<String> = emptySet(),
) {
    val working = inviteState is InviteConvoyState.Working
    AeroLazyPage(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = aeroLazyContentPadding(),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s4),
        ) {
            item(key = "title") { AeroPageTitle(stringResource(R.string.convoy_inviteTitle)) }

            item(key = "pick-header") {
                SectionHeader(stringResource(R.string.convoy_pickFriendsTitle))
            }

            when (friendsStatus) {
                FriendsStatus.Loading -> item(key = "friends-loading") { CircularProgressIndicator() }

                is FriendsStatus.Error ->
                    item(key = "friends-error") {
                        Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                            InfoNoticeCard(text = stringResource(friendsStatus.error.messageRes()))
                            if (onRetryFriends != null) {
                                TextButton(onClick = onRetryFriends) {
                                    Text(stringResource(R.string.convoy_friendsRetry))
                                }
                            }
                        }
                    }

                is FriendsStatus.Loaded -> {
                    val candidates = invitableFriends(friendsStatus.friends, excludedUids)
                    when {
                        // No friends at all — the same "add friends first" nudge the
                        // create-convoy picker shows.
                        friendsStatus.friends.isEmpty() ->
                            item(key = "friends-empty") {
                                InfoNoticeCard(text = stringResource(R.string.convoy_noFriends))
                            }
                        // Has friends, but every one is already in this convoy — a
                        // distinct empty state so the picker never looks broken (an
                        // empty list with no explanation).
                        candidates.isEmpty() ->
                            item(key = "friends-all-in-convoy") {
                                InfoNoticeCard(
                                    text = stringResource(R.string.convoy_inviteNoInvitable),
                                )
                            }
                        else ->
                            items(candidates, key = { "f-${it.uid}" }) { friend ->
                                SelectableFriendRow(
                                    friend = friend,
                                    selected = friend.uid in selectedUids,
                                    enabled = !working,
                                    onToggle = { onToggleFriend(friend.uid) },
                                )
                            }
                    }
                }
            }

            if (inviteState is InviteConvoyState.Error) {
                item(key = "invite-error") {
                    Text(
                        text = stringResource(inviteState.error.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            item(key = "actions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        enabled = !working,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.convoy_inviteCancel))
                    }
                    Button(
                        onClick = onSubmit,
                        enabled = !working && selectedUids.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (working) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(KccSpacing.s6),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(stringResource(R.string.convoy_inviteSubmit))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Convoy detail (+ ended summary)
// ---------------------------------------------------------------------------

/**
 * A single convoy's members and their invite statuses. The owner sees Start
 * (while forming) and End (while active); a non-owner just sees the status. When
 * the convoy has ended, the post-drive [ConvoySummaryStats] is shown to ALL
 * members. The live-map is a separate surface and is deliberately not linked
 * here (a "+" chooser / driving-mode follow-up wires start-and-drive).
 *
 * @param onViewMember opens a roster member's read-only profile. Null (the
 *   config-less build) leaves the rows inert.
 * @param viewerUid the caller, whose OWN row never opens a profile — consistent
 *   with chat, where your own messages carry no sender affordance. Null when the
 *   caller is unknown, which simply leaves every row tappable.
 */
@Composable
fun ConvoyDetailScreen(
    convoy: ConvoySummary,
    working: Boolean,
    actionError: ConvoyActionError?,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onClearActionError: () -> Unit,
    modifier: Modifier = Modifier,
    onViewMember: ((String) -> Unit)? = null,
    viewerUid: String? = null,
) {
    AeroPage(title = convoy.displayTitle(), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
            StatusChip(convoy.status)
        }

        actionError?.let { error ->
            ErrorBanner(text = stringResource(error.messageRes()), onDismiss = onClearActionError)
        }

        if (convoy.status == ConvoyStatus.Ended) {
            ConvoyRecap.stateFor(convoy)?.let { ConvoyRecapCard(it) }
        }

        SectionHeader(stringResource(R.string.convoy_membersTitle))
        convoy.members.forEach { member ->
            MemberRow(
                member = member,
                isOwner = member.uid == convoy.ownerUid,
                // Tapping a member opens their read-only profile. Never wired for
                // the caller's own row, nor for a malformed member whose blank uid
                // would open a dead profile route.
                onClick =
                    onViewMember
                        ?.takeIf { member.uid.isNotBlank() && member.uid != viewerUid }
                        ?.let { { it(member.uid) } },
            )
        }

        if (convoy.viewerIsOwner) {
            when (convoy.status) {
                ConvoyStatus.Forming ->
                    Button(onClick = onStart, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.convoy_start))
                    }
                ConvoyStatus.Active ->
                    Button(onClick = onEnd, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.convoy_end))
                    }
                ConvoyStatus.Ended -> Unit
            }
        }
    }
}

/**
 * The ended-convoy recap shown to ALL members: how long it ran, how far (when the
 * backend populated it — otherwise an honest "not available", never a fake 0 km),
 * how many were in it, and — the part a bare summary lacked — WHO came along, with
 * their faces and names from the roster. Driven by the pure [ConvoyRecapState] so
 * the present/partial/absent-field logic stays testable off the Composable.
 */
@Composable
private fun ConvoyRecapCard(recap: ConvoyRecapState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            Text(
                text = stringResource(R.string.convoy_summaryTitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SummaryStatRow(
                label = stringResource(R.string.convoy_summaryDuration),
                value = ConvoyFormat.duration(recap.durationSeconds),
            )
            SummaryStatRow(
                label = stringResource(R.string.convoy_summaryParticipants),
                value = recap.participantCount.toString(),
            )
            SummaryStatRow(
                label = stringResource(R.string.convoy_summaryDistance),
                value =
                    recap.distanceMeters?.let { ConvoyFormat.distance(it) }
                        ?: stringResource(R.string.convoy_summaryDistanceUnavailable),
            )
            // The roster of who was actually in it. Only when we can name at least
            // one — an empty list (uids not populated) leaves the count row to tell
            // the honest total rather than showing an empty "who" header.
            if (recap.participants.isNotEmpty()) {
                SectionHeader(stringResource(R.string.convoy_summaryWho))
                recap.participants.forEach { participant ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
                    ) {
                        MemberAvatar(
                            avatarPath = participant.avatarPath,
                            size = KccSpacing.s8,
                            accepted = false,
                        )
                        Text(
                            text =
                                participant.displayName
                                    ?: stringResource(R.string.convoy_unknownMember),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MemberRow(member: ConvoyMember, isOwner: Boolean, onClick: (() -> Unit)?) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        // Announce the row as a button so its tap-to-open-profile
                        // affordance reaches accessibility services.
                        Modifier.clickable(role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            MemberAvatar(
                avatarPath = member.avatarPath,
                size = KccSpacing.s10,
                accepted = member.inviteStatus == ConvoyInviteStatus.Accepted,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.displayName ?: stringResource(R.string.convoy_unknownMember),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text =
                        if (isOwner) {
                            stringResource(R.string.convoy_roleOwner)
                        } else {
                            stringResource(member.inviteStatus.labelRes())
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------

@Composable
private fun MemberAvatar(avatarPath: String?, size: androidx.compose.ui.unit.Dp, accepted: Boolean) {
    val context = LocalContext.current
    val url = rememberStorageImageUrl(context, avatarPath)
    val dotColor = LocalKccStatusColors.current.success
    Box(modifier = Modifier.size(size)) {
        Box(
            modifier =
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(size * 0.6f),
                )
            }
        }
        // Green presence dot for an accepted member, ringed with the surface
        // colour so it stays legible over any avatar.
        if (accepted) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(size * 0.3f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(1.dp)
                        .clip(CircleShape)
                        .background(dotColor),
            )
        }
    }
}

@Composable
private fun StatusChip(status: ConvoyStatus) {
    val statusColors = LocalKccStatusColors.current
    val (label, color) =
        when (status) {
            ConvoyStatus.Forming ->
                stringResource(R.string.convoy_statusForming) to statusColors.warning
            ConvoyStatus.Active ->
                stringResource(R.string.convoy_statusActive) to statusColors.success
            ConvoyStatus.Ended ->
                stringResource(R.string.convoy_statusEnded) to MaterialTheme.colorScheme.onSurfaceVariant
        }
    Box(
        modifier =
            Modifier
                .clip(CircleShape)
                .border(1.dp, color, CircleShape)
                .padding(horizontal = KccSpacing.s3, vertical = KccSpacing.s1),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InfoNoticeCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(KccSpacing.s6),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = KccSpacing.s3),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.convoy_close)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Pure display helpers
// ---------------------------------------------------------------------------

@Composable
private fun ConvoySummary.displayTitle(): String =
    title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.convoy_untitled)

/** The owner's display name from the member list, or a neutral fallback. */
@Composable
private fun ConvoySummary.ownerDisplayName(): String =
    members.firstOrNull { it.uid == ownerUid }?.displayName
        ?: stringResource(R.string.convoy_unknownMember)

private fun ConvoyInviteStatus.labelRes(): Int =
    when (this) {
        ConvoyInviteStatus.Invited -> R.string.convoy_inviteInvited
        ConvoyInviteStatus.Accepted -> R.string.convoy_inviteAccepted
        ConvoyInviteStatus.Declined -> R.string.convoy_inviteDeclined
    }

/** The notice wording for an invite deep link that no longer leads anywhere. */
internal fun ConvoyInviteDeepLinkOutcome.messageRes(): Int =
    when (this) {
        // PENDING never reaches a notice (the invite is on screen), but the
        // mapping is total so a future caller cannot get a blank banner.
        ConvoyInviteDeepLinkOutcome.PENDING -> R.string.convoy_invitesTitle
        ConvoyInviteDeepLinkOutcome.ENDED -> R.string.convoy_inviteLinkEnded
        ConvoyInviteDeepLinkOutcome.ANSWERED -> R.string.convoy_inviteLinkAnswered
        ConvoyInviteDeepLinkOutcome.GONE -> R.string.convoy_inviteLinkGone
    }

internal fun ConvoyActionError.messageRes(): Int =
    when (this) {
        ConvoyActionError.SignedOut -> R.string.convoy_errorSignedOut
        ConvoyActionError.NotMember -> R.string.convoy_errorNotMember
        ConvoyActionError.Invalid -> R.string.convoy_errorInvalid
        ConvoyActionError.NotFound -> R.string.convoy_errorNotFound
        ConvoyActionError.NoInvitees -> R.string.convoy_errorNoInvitees
        ConvoyActionError.InviteGone -> R.string.convoy_errorInviteGone
        ConvoyActionError.CannotStart -> R.string.convoy_errorCannotStart
        ConvoyActionError.AlreadyEnded -> R.string.convoy_errorAlreadyEnded
        ConvoyActionError.LeaveFailed -> R.string.convoy_errorLeaveFailed
        ConvoyActionError.NotAllowed -> R.string.convoy_errorNotAllowed
        ConvoyActionError.AlreadyInConvoy -> R.string.convoy_errorAlreadyInConvoy
        ConvoyActionError.Generic -> R.string.convoy_errorGeneric
    }
