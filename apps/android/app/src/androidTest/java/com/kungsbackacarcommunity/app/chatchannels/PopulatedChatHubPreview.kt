package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kungsbackacarcommunity.app.driverfeed.DriverFeedContent
import com.kungsbackacarcommunity.app.driverfeed.DriverFeedState
import kotlinx.coroutines.flow.flowOf

/**
 * Test-only S29/S30 capture: the actual ChatHubRoute, including source selector,
 * hub Back, unread channel strip, populated route and composer. The host supplies
 * KccTheme, locale, viewport and font scale; no Firebase or production writes.
 */
@Composable
fun PopulatedChatHubPreview(onClose: () -> Unit = {}) {
    val repository = remember { PreviewCommunityRepository() }
    ChatHubRoute(
        uid = "local-self",
        communityChatRepository = repository, convoyChatRepository = null,
        friendsRepository = null, dmRepository = null,

        communityUnread = true, convoysUnread = true, friendsUnread = true,
        onClose = onClose, showHubBackArrow = true,
        driverContent = {
            DriverFeedContent(DriverFeedState(), driverFeedStrings(),
                onBack = {}, onRefresh = {}, onMore = {}, onOpen = {})
        },
    )
}

private class PreviewCommunityRepository : CommunityChatRepository {
    override fun observeMessages() = flowOf(ChannelMessagesState.Loaded(
        listOf(
            message("one", "local-member-a", "Testmedlem A", "Var samlas vi inför kvällens runda?", 0),
            message("two", "local-member-b", "Testmedlem B", "Vid stationen. Vi tar en lugn tur tillsammans.", 1),
            message("three", "local-self", "Testmedlem", "Jag hänger på! Ses där.", 2),
        ),
    ))
    override fun observeUnread(uid: String) = flowOf(true)
    override suspend fun post(text: String, mentionedUids: List<String>, clientId: String?) =
        ChannelSendResult.Failed(ChannelSendError.Generic)
    override suspend fun loadOlder(before: String) = ChannelOlderResult.Failed
    override suspend fun markRead() = Unit

    private fun message(id: String, uid: String, name: String, text: String, minute: Int) =
        ChannelMessage(id, uid, text, name, null, 1792936800000L + minute * 60_000, null)
}
