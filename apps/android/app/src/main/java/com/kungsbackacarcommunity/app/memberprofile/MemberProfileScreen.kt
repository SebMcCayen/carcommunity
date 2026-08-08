package com.kungsbackacarcommunity.app.memberprofile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.badges.BadgeGlyph
import com.kungsbackacarcommunity.app.badges.BadgeGridRows
import com.kungsbackacarcommunity.app.badges.BadgeMedallionSize
import com.kungsbackacarcommunity.app.badges.BadgeMedallionTile
import com.kungsbackacarcommunity.app.badges.MilestoneBadge
import com.kungsbackacarcommunity.app.badges.PublicBadgeWall
import com.kungsbackacarcommunity.app.badges.badgeNameRes
import com.kungsbackacarcommunity.app.badges.ladderNameRes
import com.kungsbackacarcommunity.app.badges.tierNameRes
import com.kungsbackacarcommunity.app.blocking.BlockActionStatus
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.friends.FriendRelationship
import com.kungsbackacarcommunity.app.friends.messageRes
import com.kungsbackacarcommunity.app.garage.Vehicle
import com.kungsbackacarcommunity.app.garage.VehicleCard
import com.kungsbackacarcommunity.app.garage.VehiclePhotoStyle
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.moderation.BlockConfirmDialog
import com.kungsbackacarcommunity.app.moderation.MessageModeration
import com.kungsbackacarcommunity.app.moderation.ReportAvailability
import com.kungsbackacarcommunity.app.moderation.UnblockConfirmDialog
import com.kungsbackacarcommunity.app.profile.ProfileSocialLinksRow
import com.kungsbackacarcommunity.app.shell.AeroPage
import java.text.DateFormat
import java.util.Date

/**
 * Read-only view of another member's public profile: avatar, display name, bio,
 * their garage cars (with the main-car photo highlighted), and — when readable —
 * their awards. The profile DATA stays read-only: this screen never mutates and
 * never reveals owner-only data.
 *
 * It does carry the actions a member needs ON another member, all of them about
 * the VIEWER's own relationship to them rather than about their profile:
 *
 *  - **Friend action** (when [friendState] is non-null): one control that
 *    reflects the current relationship — Add friend / Cancel request / Accept +
 *    Decline / a "Friends" status. It sits directly under the header because it
 *    is the point of visiting someone's profile; the safety actions stay at the
 *    bottom. See [MemberFriendControl].
 *  - **Block / Unblock** (when [onBlock]/[onUnblock] are wired): blocking a
 *    loaded profile confirms first, and on success the route settles the screen
 *    on [MemberProfileState.Blocked] — the profile withheld, offering the
 *    Unblock that undoes it.
 *  - **Report user**: rendered DISABLED with an explanatory note, because no
 *    report-a-user callable exists yet — the action must not look like it filed
 *    a report it cannot file. See
 *    [com.kungsbackacarcommunity.app.moderation.MessageModeration] for the exact
 *    backend this is waiting on.
 *
 * Badges are PUBLIC and render as the member's badge wall — medallions at the
 * tier they reached — because achievements are meant to be shown off. The wall
 * carries no progress bar and no counter: see [MemberBadgeWall]. It collapses to
 * a soft note if the read is denied or fails — see [MemberBadges].
 *
 * @param onBlock blocks the viewed member; null (config-less build, or the
 *   viewer's own profile) omits the block action.
 * @param onUnblock unblocks them from the [MemberProfileState.Blocked] state;
 *   null omits the unblock action, leaving that state a bare notice.
 * @param friendState the viewer's friend relationship to this member; null (a
 *   config-less build, or the viewer's own profile) omits the friend action
 *   entirely. It is only ever rendered on a LOADED profile — a withheld
 *   ([MemberProfileState.Blocked]) or missing one offers no friend action, so a
 *   blocked member can never be befriended from here.
 * @param onMessage opens a 1:1 DM with this member; rendered as the bottom
 *   **Message** action ONLY when the viewer is already a friend (and DM is
 *   wired). Null omits it.
 * @param onUnfriend removes the friendship (`friend-remove`); rendered as the
 *   bottom **Unfriend** action ONLY when the viewer is already a friend, and
 *   confirm-guarded by this screen before it fires. Null omits it. On success
 *   the friend state falls back so Message/Unfriend disappear and Block remains.
 */
@Composable
fun MemberProfileScreen(
    state: MemberProfileState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onBlock: (() -> Unit)? = null,
    onUnblock: (() -> Unit)? = null,
    blockStatus: BlockActionStatus = BlockActionStatus.Idle,
    friendState: MemberFriendState? = null,
    onAddFriend: () -> Unit = {},
    onCancelRequest: () -> Unit = {},
    onAcceptRequest: () -> Unit = {},
    onDeclineRequest: () -> Unit = {},
    onUnfriend: (() -> Unit)? = null,
    onMessage: (() -> Unit)? = null,
) {
    val title =
        (state as? MemberProfileState.Loaded)?.profile?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.memberProfile_title)
    var confirmingBlock by rememberSaveable { mutableStateOf(false) }
    var confirmingUnblock by rememberSaveable { mutableStateOf(false) }
    var confirmingUnfriend by rememberSaveable { mutableStateOf(false) }

    AeroPage(title = title, modifier = modifier) {
        if (blockStatus == BlockActionStatus.Failed) {
            Text(
                text = stringResource(R.string.blocking_errorGeneric),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        when (state) {
            MemberProfileState.Loading ->
                Box(
                    modifier = Modifier.fillMaxWidth().padding(KccSpacing.s6),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

            MemberProfileState.Unavailable ->
                NoticeCard(text = stringResource(R.string.memberProfile_unavailable))

            MemberProfileState.Blocked -> {
                NoticeCard(text = stringResource(R.string.memberProfile_blocked))
                if (onUnblock != null) {
                    Button(
                        onClick = { confirmingUnblock = true },
                        enabled = blockStatus != BlockActionStatus.Working,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.blocking_unblock))
                    }
                }
            }

            MemberProfileState.Error ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
                        verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
                    ) {
                        Text(
                            text = stringResource(R.string.memberProfile_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(onClick = onRetry) {
                            Text(stringResource(R.string.memberProfile_retry))
                        }
                    }
                }

            is MemberProfileState.Loaded -> {
                // Already friends is expressed by the bottom actions (Message +
                // Unfriend), not a top status line — so a member profile leads
                // straight into the content once you're friends.
                val isFriend = friendState?.relationship == FriendRelationship.Friends
                ProfileHeader(state.profile, state.pointsBalance)
                if (friendState != null) {
                    FriendAction(
                        friendState = friendState,
                        onAddFriend = onAddFriend,
                        onCancelRequest = onCancelRequest,
                        onAcceptRequest = onAcceptRequest,
                        onDeclineRequest = onDeclineRequest,
                    )
                }
                CarsSection(state.vehicles)
                BadgesSection(state.badges)
                StatsSection(state.profile.createdAtMillis)
                MemberActions(
                    onBlock = onBlock?.let { { confirmingBlock = true } },
                    blockStatus = blockStatus,
                    // Message + Unfriend appear only when the viewer IS a friend
                    // of this member; otherwise the bottom group is just Block.
                    onMessage = if (isFriend) onMessage else null,
                    onUnfriend =
                        if (isFriend && onUnfriend != null) {
                            { confirmingUnfriend = true }
                        } else {
                            null
                        },
                    friendActionEnabled = friendState?.enabled ?: true,
                )
            }
        }
    }

    if (confirmingBlock) {
        BlockConfirmDialog(
            memberName = (state as? MemberProfileState.Loaded)?.profile?.displayName,
            onConfirm = {
                confirmingBlock = false
                onBlock?.invoke()
            },
            onDismiss = { confirmingBlock = false },
        )
    }
    if (confirmingUnblock) {
        UnblockConfirmDialog(
            onConfirm = {
                confirmingUnblock = false
                onUnblock?.invoke()
            },
            onDismiss = { confirmingUnblock = false },
        )
    }
    if (confirmingUnfriend) {
        // Same confirm copy the Friends screen uses (friends_removeConfirm*),
        // so unfriending reads identically wherever it is offered.
        val memberName =
            (state as? MemberProfileState.Loaded)?.profile?.displayName
                ?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.memberProfile_unknownMember)
        AlertDialog(
            onDismissRequest = { confirmingUnfriend = false },
            title = { Text(stringResource(R.string.friends_removeConfirmTitle, memberName)) },
            text = { Text(stringResource(R.string.friends_removeConfirmBody)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingUnfriend = false
                        onUnfriend?.invoke()
                    },
                ) {
                    Text(stringResource(R.string.friends_removeConfirmAction))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingUnfriend = false }) {
                    Text(stringResource(R.string.friends_removeCancel))
                }
            },
        )
    }
}

/**
 * The one friend control for this profile, chosen by the viewer's relationship
 * to its owner ([MemberFriendState.control]).
 *
 * Every control disables itself while a callable is in flight — that, plus the
 * coordinator's own in-flight guard, is what stops a double-tap from sending
 * two requests. An unknown relationship (still loading, or the graph failed to
 * load) renders NOTHING rather than a speculative "Add friend".
 *
 * A failure is shown inline, under the control, using the same `friends_*`
 * strings the Friends screen maps ([messageRes]) — never a raw callable
 * message. The state itself is untouched by a failure, so the control the user
 * tapped is still the one they are looking at when they read the error.
 */
@Composable
private fun FriendAction(
    friendState: MemberFriendState,
    onAddFriend: () -> Unit,
    onCancelRequest: () -> Unit,
    onAcceptRequest: () -> Unit,
    onDeclineRequest: () -> Unit,
) {
    val control = friendState.control
    // Neither None nor Friends draws a top control: None has nothing to offer,
    // and Friends is now expressed by the bottom Message + Unfriend actions. An
    // error is still surfaced in both cases (e.g. an unfriend that failed).
    if (control == MemberFriendControl.None || control == MemberFriendControl.Friends) {
        if (friendState.error == null) return
    }
    val enabled = friendState.enabled

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
        when (control) {
            MemberFriendControl.None -> Unit

            MemberFriendControl.Add ->
                Button(
                    onClick = onAddFriend,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.memberProfile_addFriend))
                }

            MemberFriendControl.CancelRequest -> {
                Text(
                    text = stringResource(R.string.memberProfile_requestPending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onCancelRequest,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.memberProfile_cancelRequest))
                }
            }

            MemberFriendControl.Respond -> {
                Text(
                    text = stringResource(R.string.memberProfile_wantsToBeFriends),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onAcceptRequest,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.friends_accept))
                }
                TextButton(
                    onClick = onDeclineRequest,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.friends_decline))
                }
            }

            // Expressed by the bottom Message + Unfriend actions, not here.
            MemberFriendControl.Friends -> Unit
        }

        friendState.error?.let { error ->
            Text(
                text = stringResource(error.messageRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The bottom action group on a loaded profile, below the profile content.
 *
 * When the viewer is a FRIEND of this member ([onMessage] / [onUnfriend]
 * non-null), it leads with the two friend actions — **Message** (opens the 1:1
 * DM) and **Unfriend** (confirm-guarded by the screen, then `friend-remove`) —
 * above the safety actions. For a non-friend both are null and the group is just
 * the safety actions, exactly as before.
 *
 * "Report user" is omitted entirely while no `moderation.reportUser` callable
 * exists to submit to ([MessageModeration.reportUserAvailability]) — the same
 * hide-don't-disable rule the message action sheet follows. It reappears when
 * that callable lands; nothing else here changes.
 *
 * @param friendActionEnabled false while a friend callable is already in flight,
 *   which is what stops a double-tap from firing two unfriends.
 */
@Composable
private fun MemberActions(
    onBlock: (() -> Unit)?,
    blockStatus: BlockActionStatus,
    onMessage: (() -> Unit)? = null,
    onUnfriend: (() -> Unit)? = null,
    friendActionEnabled: Boolean = true,
) {
    val canReportUser =
        MessageModeration.reportUserAvailability == ReportAvailability.Wired
    if (onBlock == null && onMessage == null && onUnfriend == null && !canReportUser) {
        // Nothing actionable: not a friend, no blocking wired, reporting has no
        // backend.
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
        if (onMessage != null) {
            Button(
                onClick = onMessage,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.friends_message))
            }
        }
        if (onUnfriend != null) {
            OutlinedButton(
                onClick = onUnfriend,
                enabled = friendActionEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.friends_remove))
            }
        }
        if (onBlock != null) {
            OutlinedButton(
                onClick = onBlock,
                enabled = blockStatus != BlockActionStatus.Working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.blocking_blockUser))
            }
        }
        if (canReportUser) {
            TextButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.moderation_reportUser))
            }
        }
    }
}

@Composable
private fun ProfileHeader(profile: MemberProfile, pointsBalance: Long?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s5),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            Avatar(profile.avatarPath)
            // Directly under the profile picture, as asked. Renders NOTHING
            // when this member has filled none in — no empty row, no
            // placeholders (ProfileSocialLinksRow).
            ProfileSocialLinksRow(handles = profile.social)
            Text(
                text = profile.displayName?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.memberProfile_unknownMember),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            // The member's public points, the eye-catching headline of the
            // profile — a big number directly under the nickname (Seb, 2026-08).
            // "How active they have been", front and centre rather than buried in
            // a stats row lower down. A member with no wallet yet has a null
            // balance, rendered as 0 — they genuinely have no points.
            PointsHeadline(pointsBalance)
            profile.bio?.takeIf { it.isNotBlank() }?.let { bio ->
                Text(
                    text = bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The member's PUBLIC points balance, rendered as the profile's HEADLINE — a
 * large, emphasised number with the "Crown Points" label beneath it, sitting
 * directly under the nickname so it is the first thing the eye lands on. This is
 * the whole of the member's points on their profile: there is no second, lower
 * card (the owner-only recent-earnings ledger is never shown for another
 * member). A null balance renders as 0 — a member with no wallet genuinely has
 * no points.
 */
@Composable
private fun PointsHeadline(balance: Long?) {
    val value = balance ?: 0L
    val label = stringResource(R.string.profile_pointsTitle)
    // Merge the number + label into ONE accessible announcement so TalkBack reads
    // "<n> Crown Points" as a single coherent phrase instead of the two separate
    // nodes ("<n>" then "Crown Points") the split Texts would otherwise expose.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
        modifier = Modifier.clearAndSetSemantics { contentDescription = "$value $label" },
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Avatar(avatarPath: String?) {
    val context = LocalContext.current
    val url = rememberStorageImageUrl(context, avatarPath)
    Box(
        modifier =
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(96.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

/**
 * The member "Stats" card — deliberately MINIMAL (Seb, 2026-08): a self-titled
 * card showing just "Member since", from the public `users/{uid}.createdAt`.
 * Drive totals are NOT shown: they are computed from the member's owner-only
 * ride history, which this screen never reads. When the join date is unknown (a
 * very old account with no `createdAt`) the whole card is omitted rather than
 * showing an empty one.
 */
@Composable
private fun StatsSection(createdAtMillis: Long?) {
    if (createdAtMillis == null) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            Text(
                text = stringResource(R.string.memberProfile_statsTitle),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.memberProfile_statsMemberSince),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(createdAtMillis)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun CarsSection(vehicles: List<Vehicle>) {
    Text(
        text = stringResource(R.string.memberProfile_carsTitle),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (vehicles.isEmpty()) {
        Text(
            text = stringResource(R.string.memberProfile_carsEmpty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        // Main car(s) first so the highlighted photo leads the list.
        vehicles.sortedByDescending { it.isMainCar }.forEach { MemberVehicleCard(it) }
    }
}

/**
 * A public profile shows the car photo as a full-width 16:9 postcard with softly
 * rounded corners, rather than My Garage's circular badge: a stranger's car is
 * being introduced, so it gets the wider, more photographic crop. Declared at
 * file scope so the style object is not rebuilt on every recomposition.
 */
private val MemberVehiclePhotoStyle =
    VehiclePhotoStyle.FullWidth(aspectRatio = 16f / 9f, cornerRadius = KccSpacing.s2)

/**
 * One of the member's cars, read-only: the shared [VehicleCard] with the public
 * profile's look (wide photo, 8dp rows, the plate line) and no manage actions —
 * this screen never mutates the profile it is viewing.
 */
@Composable
private fun MemberVehicleCard(vehicle: Vehicle) {
    VehicleCard(
        vehicle = vehicle,
        photoStyle = MemberVehiclePhotoStyle,
        mainCarLabelRes = R.string.memberProfile_mainCar,
        registrationPlateFormatRes = R.string.memberProfile_registrationPlate,
        contentSpacing = KccSpacing.s2,
    )
}

/**
 * The other member's badge wall — the trophies, and nothing else.
 *
 * Badges are public (firebase/firestore.rules: `users/{uid}/badges` is readable
 * by any authenticated user) precisely so they can be shown off, so this renders
 * the same medallions the owner sees on their own profile, at the tier they
 * reached.
 *
 * WHAT IS DELIBERATELY ABSENT: progress bars, counter numbers, next-rung goals,
 * and the ladders they have not started. Reaching a rung is the public fact; how
 * far along they are toward the next one is not, and the ladders they have never
 * touched are a to-do list that belongs on their own profile, not on a stranger's
 * view of them. This is enforced by the model, not by discipline —
 * [PublicBadgeWall] has no field that could carry any of it.
 */
@Composable
private fun BadgesSection(badges: MemberBadges) {
    Text(
        text = stringResource(R.string.memberProfile_badgesTitle),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    when (badges) {
        MemberBadges.Unavailable ->
            Text(
                text = stringResource(R.string.memberProfile_badgesUnavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        MemberBadges.Unknown ->
            Text(
                text = stringResource(R.string.memberProfile_badgesLoadError),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        is MemberBadges.Available -> {
            // Folded during composition and keyed on the award list, so scrolling
            // the profile doesn't refold the wall on every recomposition.
            val wall = remember(badges.badges) { PublicBadgeWall.from(badges.badges) }
            if (!wall.hasAnyBadge) {
                Text(
                    text = stringResource(R.string.memberProfile_badgesEmpty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                MemberBadgeWall(wall)
            }
        }
    }
}

@Composable
private fun MemberBadgeWall(wall: PublicBadgeWall) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            Text(
                text = stringResource(R.string.badgeShowcase_subtitle, wall.earnedCount, wall.totalCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (wall.ladders.isNotEmpty()) {
                BadgeGridRows(items = wall.ladders, perRow = 3) { standing ->
                    val name =
                        stringResource(ladderNameRes(standing.ladder.id)) + " " +
                            stringResource(tierNameRes(standing.highestRung.tier))
                    BadgeMedallionTile(
                        glyph = BadgeGlyph.Ladder(standing.ladder.id),
                        tier = standing.highestRung.tier,
                        earned = true,
                        label = stringResource(ladderNameRes(standing.ladder.id)),
                        contentDescription = stringResource(R.string.badgeShowcase_medallionEarned, name),
                        caption = stringResource(tierNameRes(standing.highestRung.tier)),
                        medallionSize = BadgeMedallionSize,
                    )
                }
            }

            if (wall.milestones.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.badgeShowcase_milestonesTitle),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                BadgeGridRows(items = wall.milestones, perRow = 4) { milestone ->
                    val name = milestoneName(milestone)
                    BadgeMedallionTile(
                        glyph = BadgeGlyph.Milestone(milestone.key),
                        tier = null,
                        earned = true,
                        label = name,
                        contentDescription = stringResource(R.string.badgeShowcase_medallionEarned, name),
                    )
                }
            }
        }
    }
}

@Composable
private fun milestoneName(milestone: MilestoneBadge): String {
    val res = badgeNameRes(milestone.key)
    return if (res != null) stringResource(res) else (milestone.fallbackName ?: milestone.key)
}

@Composable
private fun NoticeCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
