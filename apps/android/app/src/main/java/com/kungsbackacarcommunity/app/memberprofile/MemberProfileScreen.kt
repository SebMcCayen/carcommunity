package com.kungsbackacarcommunity.app.memberprofile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
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
) {
    val title =
        (state as? MemberProfileState.Loaded)?.profile?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.memberProfile_title)
    var confirmingBlock by rememberSaveable { mutableStateOf(false) }
    var confirmingUnblock by rememberSaveable { mutableStateOf(false) }

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
                ProfileHeader(state.profile)
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
                MemberActions(
                    onBlock = onBlock?.let { { confirmingBlock = true } },
                    blockStatus = blockStatus,
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
    if (control == MemberFriendControl.None && friendState.error == null) return
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

            MemberFriendControl.Friends ->
                Text(
                    text = stringResource(R.string.memberProfile_friendsAlready),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
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
 * The viewer's safety actions on a loaded profile. Deliberately last on the page,
 * below the profile content: they are a rarely-used escape hatch, not the point
 * of the screen.
 *
 * "Report user" is omitted entirely while no `moderation.reportUser` callable
 * exists to submit to ([MessageModeration.reportUserAvailability]) — the same
 * hide-don't-disable rule the message action sheet follows. It reappears when
 * that callable lands; nothing else here changes.
 */
@Composable
private fun MemberActions(onBlock: (() -> Unit)?, blockStatus: BlockActionStatus) {
    val canReportUser =
        MessageModeration.reportUserAvailability == ReportAvailability.Wired
    if (onBlock == null && !canReportUser) {
        // Nothing actionable: no blocking wired, and reporting has no backend.
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
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
private fun ProfileHeader(profile: MemberProfile) {
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
