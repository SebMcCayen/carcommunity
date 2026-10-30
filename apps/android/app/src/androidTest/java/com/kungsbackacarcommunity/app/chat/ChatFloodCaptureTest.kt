package com.kungsbackacarcommunity.app.chat

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.chatchannels.*
import com.kungsbackacarcommunity.app.clubs.*
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.dm.ChatScreen
import com.kungsbackacarcommunity.app.dm.DmMessage
import com.kungsbackacarcommunity.app.moderation.ChatSurface
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Current-run screenshot seam: real bodies/composers, a frozen 30-second pause and local-only data. */
class ChatFloodCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun captureCooldownAndEmojiWall() {
        val args = InstrumentationRegistry.getArguments()
        val screen = args.getString("chatFloodSurface") ?: "community"
        require(screen in setOf("community", "convoy", "club", "dm", "event"))
        val dark = args.getString("redesignTheme") != "light"
        val scale = args.getString("chatFloodFontScale")?.toFloat() ?: 1f
        require(scale in 1f..2f)
        val wall = "🚗".repeat(2000)
        val messages = listOf(ChannelMessage("wall", "local-other", wall, "Testmedlem", null, 1792936800000L, null))
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) { KccTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize()) {
                    Text("DESIGNPREVIEW · LOKALA TESTDATA", Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
                    Box(Modifier.weight(1f)) {
                        when (screen) {
                            "community", "convoy" -> ChannelChatContent(messages, "local-self", false, "", false, false,
                                onSend = { _, _, _ -> }, onRetry = {}, onLoadOlder = {}, cooldownSeconds = 30,
                                surface = if (screen == "convoy") ChatSurface.ConvoyChannel else ChatSurface.CommunityChannel)
                            "dm" -> ChatScreen("Testmedlem", listOf(DmMessage("wall", "local-other", wall, 1792936800000L, null)),
                                "local-self", false, false, false, onSend = { _, _ -> }, onRetry = {}, onLoadOlder = {}, cooldownSeconds = 30)
                            "event" -> EventChatScreen(ChatMessagesState.Loaded(listOf(
                                ChatMessage("wall", "local-other", "Testmedlem", wall, false, false, 1792936800000L))),
                                "local-self", true, ChatSendStatus.Idle, ChatReportStatus.Idle, onSend = {}, onReport = { _, _ -> },
                                onReportDismiss = {}, onBack = {}, cooldownSeconds = 30, eventTitle = "Lokal testträff")
                            "club" -> {
                                val repo = remember { object : ClubChatDesignRepository() {
                                    override fun chat() = flowOf<ClubChatState>(ClubChatState.Ready(clubChatDesignMembership,
                                        listOf(ClubMessage("wall", "local-other", "Testmedlem", wall, 1792936800000L))))
                                } }
                                val gate = remember {
                                    val cooldown = ChatSendCooldown { 0L }
                                    cooldown.apply(ChatRateLimit(30_000))
                                    cooldown
                                }
                                ClubChatRoute("local-self", repo, sendCooldown = gate)
                            }
                        }
                    }
                } }
            } }
        }
        compose.onNodeWithTag("chat-body-toggle-wall").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chat-flood-countdown").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "chat-flood")
        check(output.mkdirs() || output.isDirectory)
        File(output, "$screen-${if (dark) "dark" else "light"}-$scale.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
