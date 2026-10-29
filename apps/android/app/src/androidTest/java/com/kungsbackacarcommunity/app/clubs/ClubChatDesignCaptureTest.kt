package com.kungsbackacarcommunity.app.clubs

import android.graphics.Bitmap
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.chatchannels.*
import com.kungsbackacarcommunity.app.chatchannels.baseline.CommunityChatBeforePreview
import com.kungsbackacarcommunity.app.clubs.baseline.ClubChatBeforePreview
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.moderation.ChatSurface
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.TimeZone
import java.util.Locale

/** Same fixed messages, real production components, local repositories only. */
class ClubChatDesignCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureClubAndCommunity() {
        val args = InstrumentationRegistry.getArguments()
        val screen = args.getString("clubChatSurface") ?: "before"
        require(screen in setOf("before", "community", "community-current", "after"))
        val dark = args.getString("redesignTheme") != "light"
        val language = args.getString("redesignLocale") ?: "sv"
        require(language in setOf("sv", "en"))
        val fontScale = args.getString("clubChatFontScale")?.toFloat() ?: 1f
        require(fontScale in 1f..2f)
        val config = Configuration(compose.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        val localized = ContextThemeWrapper(compose.activity, compose.activity.theme).apply {
            applyOverrideConfiguration(config)
        }
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Stockholm"))
        try {
            compose.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                    LocalDensity provides Density(density, fontScale)) {
                    KccTheme(darkTheme = dark) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize()) {
                                Text("DESIGNPREVIEW · LOKALA TESTDATA", Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.tertiary)
                                Box(Modifier.weight(1f)) {
                                    when (screen) {
                                        "before" -> ClubChatBeforePreview("local-self", remember { ClubChatDesignRepository() })
                                        "after" -> ClubChatRoute("local-self", remember { ClubChatDesignRepository() })
                                        "community" -> CommunityChatBeforePreview(
                                            messages = clubChatDesignMessages.map { it.toDesignChannelMessage() },
                                            currentUid = "local-self", loading = false, emptyText = "",
                                            canLoadOlder = false, isLoadingOlder = false,
                                            onSend = { _, _, _ -> }, onRetry = {}, onLoadOlder = {},
                                            surface = ChatSurface.CommunityChannel,
                                        )
                                        "community-current" -> ChannelChatContent(
                                            messages = clubChatDesignMessages.map { it.toDesignChannelMessage() },
                                            currentUid = "local-self", loading = false, emptyText = "",
                                            canLoadOlder = false, isLoadingOlder = false,
                                            onSend = { _, _, _ -> }, onRetry = {}, onLoadOlder = {},
                                            surface = ChatSurface.CommunityChannel,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText("Jag hänger på! Ses där.").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val output = File(context.getExternalFilesDir(null), "club-chat-design")
            check(output.mkdirs() || output.isDirectory)
            File(output, "$screen-${if (dark) "dark" else "light"}-$language-$fontScale.png").outputStream().use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }
}

internal val clubChatDesignMembership = ClubMembership("local-club", ClubRole.Member, "2026-10-27T10:00:00Z")
internal val clubChatDesignMessages = listOf(
    ClubMessage("one", "local-member-a", "Testmedlem A", "Var samlas vi inför kvällens runda?", 1792936800000L),
    ClubMessage("two", "local-member-a", "Testmedlem A", "Jag är där vid sju 🚗", 1792936860000L),
    ClubMessage("three", "local-member-b", "Testmedlem B", "Vid stationen. Vi tar en lugn tur tillsammans.", 1792936920000L),
    ClubMessage("four", "local-self", "Testmedlem", "Jag hänger på! Ses där.", 1792936980000L),
)

private fun ClubMessage.toDesignChannelMessage() = ChannelMessage(
    id, authorUid, text, authorName, null, createdAtMillis, null,
)

/** All callable methods are inert or fail locally; no Firebase initialisation. */
internal open class ClubChatDesignRepository : ClubRepository {
    override fun chat() = flowOf<ClubChatState>(ClubChatState.Ready(clubChatDesignMembership, clubChatDesignMessages))
    override fun observeEnabled() = flowOf(true)
    override fun observePublicAttendanceReady() = flowOf(false)
    override fun observeAccess() = flowOf<ClubAccess>(ClubAccess.Member(clubChatDesignMembership))
    override suspend fun directory(cursor: String?) = ClubPage<Club>(emptyList(), null)
    override suspend fun state() = ClubState(membership = clubChatDesignMembership)
    override suspend fun get(clubId: String): Club = error("Local capture only")
    override suspend fun members(clubId: String, cursor: String?) = ClubPage<ClubMember>(emptyList(), null)
    override suspend fun manage(action: String, fields: Map<String, Any?>, operationId: String): Map<String, Any?> = error("Local capture only")
    override suspend fun uploadLogo(clubId: String, bytes: ByteArray): String = error("Local capture only")
    override suspend fun olderMessages(clubId: String, before: ClubMessageCursor) = ClubMessagePage(emptyList(), null, false)
    override fun observeMessageRows(clubId: String, messageIds: List<String>) = flowOf(emptyList<ClubMessage>())
    override suspend fun send(clubId: String, text: String, operationId: String): Unit = error("Local capture only")
    override suspend fun events(clubId: String, cursor: String?) = ClubPage<ClubEvent>(emptyList(), null)
    override fun eventParticipants(eventId: String) = flowOf(emptyList<Club>())
    override fun observeEventParticipants(eventId: String, clubIds: List<String>) = flowOf(emptyList<Club>())
    override fun eventAttendance(eventId: String, clubId: String) = flowOf<Boolean?>(false)
    override suspend fun moreEventParticipants(eventId: String, afterId: String) = emptyList<Club>()
    override suspend fun setEventAttendance(clubId: String, eventId: String, attending: Boolean, operationId: String) = Unit
    override suspend fun blockAuthor(uid: String) = Unit
}
