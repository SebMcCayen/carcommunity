package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import com.kungsbackacarcommunity.app.friends.FriendsResult
import kotlinx.coroutines.launch

/**
 * Community-channel integration route: subscribes the live newest-window
 * listener, drives [ChannelChatContent] through a [ChannelChatCoordinator], and
 * marks the channel read on open + whenever a new incoming message arrives while
 * it is open. Mirrors dm/ChatRoute.
 *
 * It also sources the @-picker's roster. There is NO community-wide member
 * listing to query — no repository exposes one, and adding a backend query is
 * outside the Android lane — so the roster is assembled from the two rosters
 * already in hand, at the cost of no new query:
 *  - the caller's friends, via one `friend-list` call ([friendsRepository]; null
 *    in a config-less build, which narrows the picker to senders alone);
 *  - the authors of the loaded messages, denormalized onto each message
 *    (senderUid + senderDisplayName) — i.e. whoever is actually talking in the
 *    conversation being replied to, which is the realistic reason to @ someone
 *    in an app-wide channel.
 *
 * The name map used for HIGHLIGHTING is deliberately a superset of that roster:
 * it keeps the caller's own name (the picker excludes it, since the server drops
 * self-mentions, but being mentioned BY someone else must still highlight),
 * resolved from the caller's own messages in the loaded window. A mentioned uid
 * outside all of these renders unhighlighted; they were still notified.
 */
@Composable
fun CommunityChannelRoute(
    repository: CommunityChatRepository,
    uid: String,
    friendsRepository: FriendsRepository? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val coordinator =
        remember(repository) {
            ChannelChatCoordinator(
                sender = { text, mentionedUids -> repository.post(text, mentionedUids) },
                pager = { before -> repository.loadOlder(before) },
                marker = { repository.markRead() },
            )
        }

    val messagesState by
        remember(repository) { repository.observeMessages() }
            .collectAsState(initial = ChannelMessagesState.Loading)
    val liveMessages = (messagesState as? ChannelMessagesState.Loaded)?.messages ?: emptyList()

    val older by coordinator.olderMessages.collectAsState()
    val displayed = remember(older, liveMessages) { ChannelThread.merge(older, liveMessages) }
    val sendStatus by coordinator.sendStatus.collectAsState()
    val pageStatus by coordinator.pageStatus.collectAsState()
    val droppedMentions by coordinator.droppedMentionCount.collectAsState()
    // The next older-page cursor: the earliest loaded message's ISO createdAt, or
    // null when it lacks createdAt. A null cursor makes older-paging a no-op, so
    // gate the "Load earlier" affordance on it and reuse the same cursor to page.
    val olderCursor = remember(displayed) { ChannelThread.oldestCursor(displayed) }

    // One `friend-list` fetch per open. A failure is silent: no friends merely
    // narrows the picker to the members talking in the channel, and a chat must
    // not surface a friends-graph error the reader can do nothing about.
    var friends by remember(friendsRepository) { mutableStateOf(emptyList<MentionCandidate>()) }
    LaunchedEffect(friendsRepository) {
        val result = friendsRepository?.list() ?: return@LaunchedEffect
        if (result is FriendsResult.Loaded) {
            friends =
                result.data.friends.mapNotNull { friend ->
                    val name =
                        friend.displayName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    MentionCandidate(friend.uid, name, friend.avatarPath)
                }
        }
    }

    val senders = remember(displayed) { MentionCandidates.sendersOf(displayed) }
    val mentionCandidates =
        remember(friends, senders, uid) {
            MentionCandidates.from(friends = friends, messageSenders = senders, selfUid = uid)
        }
    val mentionDisplayNames =
        remember(friends, senders) { MentionCandidates.displayNames(friends + senders) }

    // Mark read on open, and again when a new INCOMING message lands while open.
    LaunchedEffect(Unit) { coordinator.markRead() }
    LaunchedEffect(liveMessages.lastOrNull()?.id) {
        val newest = liveMessages.lastOrNull()
        if (newest != null && newest.senderUid != uid) coordinator.markRead()
    }

    ChannelChatContent(
        messages = displayed,
        currentUid = uid,
        loading = messagesState is ChannelMessagesState.Loading,
        emptyText = stringResource(R.string.channel_emptyCommunity),
        sendStatus = sendStatus,
        canLoadOlder = pageStatus != ChannelPageStatus.End &&
            displayed.size >= CHANNEL_MESSAGES_PAGE_SIZE &&
            olderCursor != null,
        isLoadingOlder = pageStatus == ChannelPageStatus.Loading,
        onSend = { text, mentionedUids -> scope.launch { coordinator.send(text, mentionedUids) } },
        onLoadOlder = { scope.launch { coordinator.loadOlder(olderCursor) } },
        onResetError = { coordinator.resetSendError() },
        modifier = modifier,
        mentionCandidates = mentionCandidates,
        mentionDisplayNames = mentionDisplayNames,
        droppedMentionCount = droppedMentions,
        onDismissDroppedMentions = { coordinator.dismissDroppedMentions() },
    )
}
