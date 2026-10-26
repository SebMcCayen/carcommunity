package com.kungsbackacarcommunity.app.redesign

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kungsbackacarcommunity.app.chatchannels.PopulatedChatHubPreview
import com.kungsbackacarcommunity.app.chatchannels.ChatFeedHeader
import com.kungsbackacarcommunity.app.chatchannels.driverFeedStrings
import com.kungsbackacarcommunity.app.driverfeed.DriverComment
import com.kungsbackacarcommunity.app.driverfeed.DriverFeedContent
import com.kungsbackacarcommunity.app.driverfeed.DriverFeedState
import com.kungsbackacarcommunity.app.driverfeed.DriverPost

/** Local screenshot data only. The integration harness owns visible fixture captions and KccTheme. */
@Composable
fun Wave2ChatPreview(screen: String) {
    if (screen == "chat") {
        PopulatedChatHubPreview()
        return
    }
    val driver = screen == "driver" || screen == "driver-detail"
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            ChatFeedHeader(driverSelected = driver, onSelect = {})
            when (screen) {
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
