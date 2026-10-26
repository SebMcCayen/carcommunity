package com.kungsbackacarcommunity.app.redesign

import androidx.compose.runtime.Composable
import com.kungsbackacarcommunity.app.convoy.ConvoyDestination
import com.kungsbackacarcommunity.app.convoy.ConvoyDetailScreen
import com.kungsbackacarcommunity.app.convoy.ConvoyInviteStatus
import com.kungsbackacarcommunity.app.convoy.ConvoyListScreen
import com.kungsbackacarcommunity.app.convoy.ConvoyListStatus
import com.kungsbackacarcommunity.app.convoy.ConvoyMember
import com.kungsbackacarcommunity.app.convoy.ConvoyRole
import com.kungsbackacarcommunity.app.convoy.ConvoyStatus
import com.kungsbackacarcommunity.app.convoy.ConvoySummary
import com.kungsbackacarcommunity.app.convoy.ConvoySummaryStats
import com.kungsbackacarcommunity.app.convoy.ConvoyViewer
import com.kungsbackacarcommunity.app.convoy.CreateConvoyScreen
import com.kungsbackacarcommunity.app.convoy.CreateConvoyState
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendsStatus

/** S49–S52, actual native components. Main supplies theme and a visible test-data label. */
@Composable
fun ConvoyCataloguePreview(screen: String) {
    val active = catalogueConvoy()
    val ended = active.copy(
        convoyId = "local-ended", title = "Testkonvoj · Avslutad", status = ConvoyStatus.Ended,
        endedAt = "2026-10-24T17:00:00Z",
        summary = ConvoySummaryStats(3_600L, active.memberUids, active.memberUids.size, null),
    )
    val invite = active.copy(
        convoyId = "local-invite", title = "Testkonvoj · Inbjudan",
        viewer = ConvoyViewer(ConvoyRole.Member, ConvoyInviteStatus.Invited),
    )
    when (screen) {
        "convoys", "convoy-invites" -> ConvoyListScreen(
            status = ConvoyListStatus.Loaded(
                convoys = if (screen == "convoys") listOf(active, invite, ended) else listOf(invite, ended),
                pendingInvites = listOf(invite),
            ),
            refreshError = null, actionError = null, busyConvoys = emptySet(),
            onCreate = {}, onOpenConvoy = {}, onAccept = {}, onDecline = {},
            onClearActionError = {}, onRefresh = {},
        )
        "convoy-create", "convoy-create-busy" -> CreateConvoyScreen(
            friendsStatus = FriendsStatus.Loaded(
                friends = (1..5).map { FriendSummary("local-friend-$it", "Testvän $it", null, null) },
                incoming = emptyList(), outgoing = emptyList(),
            ),
            createState = if (screen == "convoy-create-busy") CreateConvoyState.Working else CreateConvoyState.Idle,
            selectedUids = setOf("local-friend-1", "local-friend-3"), onToggleFriend = {},
            onRetryFriends = null, onSubmit = {},
        )
        "convoy-active", "convoy-active-destination", "convoy-ended", "convoy-ended-partial" -> {
            val convoy = when (screen) {
                "convoy-ended" -> ended
                "convoy-ended-partial" -> ended.copy(summary = null, endedAt = null)
                "convoy-active-destination" -> active.copy(destination = ConvoyDestination(
                    57.48, 12.07, "Testdestination · endast lokal fixture", "local-viewer", "Testförare 1", "2026-10-24T16:10:00Z",
                ))
                else -> active
            }
            ConvoyDetailScreen(
                convoy = convoy, working = false, actionError = null,
                onStart = {}, onEnd = {}, onClearActionError = {}, onViewMember = {},
                viewerUid = "local-viewer", onLeave = {},
            )
        }
        else -> error("Unknown convoy catalogue case: $screen")
    }
}

private fun catalogueConvoy(): ConvoySummary {
    val members = (1..3).map {
        ConvoyMember(
            uid = if (it == 1) "local-viewer" else "local-member-$it",
            role = if (it == 1) ConvoyRole.Owner else ConvoyRole.Member,
            inviteStatus = ConvoyInviteStatus.Accepted, joinedAt = "2026-10-24T16:00:00Z",
            displayName = "Testförare $it", avatarPath = null,
        )
    }
    return ConvoySummary(
        convoyId = "local-active", ownerUid = "local-viewer", title = "Testkonvoj · Kvällsrunda",
        status = ConvoyStatus.Active, members = members, memberUids = members.map { it.uid },
        viewer = ConvoyViewer(ConvoyRole.Owner, ConvoyInviteStatus.Accepted),
        livePositionUids = emptyList(), summary = null, createdAt = "2026-10-24T15:55:00Z",
        startedAt = "2026-10-24T16:00:00Z", endedAt = null,
    )
}
