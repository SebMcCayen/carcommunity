package com.kungsbackacarcommunity.app.memberprofile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.kungsbackacarcommunity.app.badges.Badge
import com.kungsbackacarcommunity.app.badges.badgeNameRes
import com.kungsbackacarcommunity.app.blocking.BlockActionStatus
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.friends.messageRes
import com.kungsbackacarcommunity.app.garage.Vehicle
import com.kungsbackacarcommunity.app.garage.labelRes
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.moderation.BlockConfirmDialog
import com.kungsbackacarcommunity.app.moderation.MessageModeration
import com.kungsbackacarcommunity.app.moderation.ReportAvailability
import com.kungsbackacarcommunity.app.moderation.UnblockConfirmDialog
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
 * Badges collapse to a soft "not shown" note when they aren't readable under the
 * current Security Rules (they are owner-only today) — see [MemberBadges].
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
        vehicles.sortedByDescending { it.isMainCar }.forEach { VehicleCard(it) }
    }
}

@Composable
private fun VehicleCard(vehicle: Vehicle) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            VehiclePhoto(vehicle.imagePath)
            Text(
                text = "${vehicle.make} ${vehicle.model} (${vehicle.modelYear})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(vehicle.powertrain.labelRes()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (vehicle.isMainCar) {
                Text(
                    text = stringResource(R.string.memberProfile_mainCar),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            vehicle.engineDescription?.takeIf { it.isNotBlank() }?.let { engine ->
                Text(
                    text = engine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            vehicle.modifications?.takeIf { it.isNotBlank() }?.let { mods ->
                Text(
                    text = mods,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VehiclePhoto(imagePath: String?) {
    val context = LocalContext.current
    val url = rememberStorageImageUrl(context, imagePath)
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(KccSpacing.s2)),
        )
    }
}

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

        is MemberBadges.Available ->
            if (badges.badges.isEmpty()) {
                Text(
                    text = stringResource(R.string.memberProfile_badgesEmpty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                badges.badges.forEach { BadgeCard(it) }
            }
    }
}

@Composable
private fun BadgeCard(badge: Badge) {
    val nameRes = badgeNameRes(badge.key)
    val name = if (nameRes != null) stringResource(nameRes) else (badge.fallbackName ?: badge.key)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            badge.awardedAtMillis?.let { millis ->
                val awardedDate = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
                Text(
                    text = stringResource(R.string.memberProfile_awardedOn, awardedDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
