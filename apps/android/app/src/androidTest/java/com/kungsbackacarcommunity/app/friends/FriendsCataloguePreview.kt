package com.kungsbackacarcommunity.app.friends

import androidx.compose.runtime.Composable

/** S36 local screenshot input. The integration harness owns locale, theme, font scale and viewport. */
@Composable
fun FriendsCataloguePreview() {
    FriendsScreen(
        status = FriendsStatus.Loaded(
            friends = listOf(FriendSummary("local-a", "Testmedlem A", null, null),
                FriendSummary("local-b", "Testmedlem B", null, null)),
            incoming = listOf(FriendRequestSummary("local-request", "local-c", "local-self",
                FriendRequestDirection.Incoming, FriendUser("local-c", "Testmedlem C", null), null)),
            outgoing = emptyList(),
        ),
        addState = AddFriendState.Idle, actionError = null, busyRows = emptySet(),
        onSend = {}, onChooseCandidate = {}, onDismissAdd = {}, onAccept = {}, onDecline = {},
        onCancel = {}, onRemove = {}, onClearActionError = {}, onMessageFriend = {}, onViewProfile = {},
    )
}
