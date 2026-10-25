package com.kungsbackacarcommunity.app.redesign

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.chatchannels.ChannelChatContent
import com.kungsbackacarcommunity.app.chatchannels.ChannelMessage
import com.kungsbackacarcommunity.app.chatchannels.ChatFeedHeader
import com.kungsbackacarcommunity.app.chatchannels.driverFeedStrings
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.driverfeed.DriverComment
import com.kungsbackacarcommunity.app.driverfeed.DriverFeedContent
import com.kungsbackacarcommunity.app.driverfeed.DriverFeedState
import com.kungsbackacarcommunity.app.driverfeed.DriverPost
import com.kungsbackacarcommunity.app.moderation.ChatSurface

/** Local screenshot data only. The integration harness owns visible fixture captions and KccTheme. */
@Composable
fun Wave2ChatPreview(screen: String) {
    val driver = screen == "driver" || screen == "driver-detail"
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            ChatFeedHeader(driverSelected = driver, onSelect = {})
            when (screen) {
                "chat" -> {
                    Text(stringResource(R.string.chatHub_tabCommunity),
                        modifier = Modifier.padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s2),
                        style = MaterialTheme.typography.titleLarge)
                    ChannelChatContent(
                        messages = listOf(
                            localMessage("one", "local-member-a", "Testmedlem A", "Var samlas vi inför kvällens runda?", 0),
                            localMessage("two", "local-member-b", "Testmedlem B", "Vid stationen. Vi tar en lugn tur tillsammans.", 1),
                            localMessage("three", "local-self", "Testmedlem", "Jag hänger på! Ses där 👋", 2),
                        ),
                        currentUid = "local-self", loading = false,
                        emptyText = stringResource(R.string.channel_emptyCommunity),
                        canLoadOlder = false, isLoadingOlder = false,
                        onSend = { _, _, _ -> }, onRetry = {}, onLoadOlder = {},
                        surface = ChatSurface.CommunityChannel,
                        modifier = Modifier.weight(1f),
                    )
                }
                "driver", "driver-detail" -> {
                    val post = DriverPost("local-post", "local-member-a", "Testmedlem A", "testmedlem",
                        "Kvällstur genom Kungsbacka.\nEn lugn runda och en paus vid stationen. Vilken väg körde ni?",
                        LOCAL_TIME, emptyList(), 12, 2)
                    DriverFeedContent(
                        state = if (screen == "driver-detail") DriverFeedState(selectedId = post.id, post = post,
                            comments = listOf(DriverComment("local-comment", "local-member-b", "Testmedlem B",
                                "Vi tog kustvägen. Fint väder hela kvällen!", LOCAL_TIME + 60_000)))
                        else DriverFeedState(posts = listOf(post,
                            post.copy(id = "local-post-two", author = "Testmedlem B", authorUid = "local-member-b",
                                text = "En paus längs vägen. Tack för en fin kväll tillsammans!", likes = 4, replies = 0))),
                        strings = driverFeedStrings(), modifier = Modifier.weight(1f),
                        onBack = {}, onRefresh = {}, onMore = {}, onOpen = {},
                    )
                }
                else -> error("Unsupported chat preview: $screen")
            }
        }
    }
}

private const val LOCAL_TIME = 1792936800000L

private fun localMessage(id: String, uid: String, name: String, text: String, minute: Int) = ChannelMessage(
    id = id, senderUid = uid, senderDisplayName = name, senderAvatarPath = null, text = text,
    createdAtMillis = LOCAL_TIME + minute * 60_000, createdAtIso = null,
)
