package com.kungsbackacarcommunity.app.moderation

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.chatchannels.ChannelChatContent
import com.kungsbackacarcommunity.app.chatchannels.ChannelMessage
import com.kungsbackacarcommunity.app.design.KccTheme
import java.io.File
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Device rendering and interaction with production components and local data only. */
class PublicChatModerationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class LocalRepository : PublicChatModerationRepository {
        var contextCalls = 0
        val decisions = mutableListOf<Map<String, Any?>>()
        override fun observeAdminCandidate() = flowOf(false)
        override fun observeOwnPause() = flowOf<PublicChatWritePause?>(null)
        override suspend fun context(target: PublicChatTarget): PublicChatModerationContext {
            contextCalls++
            return PublicChatModerationContext("local-admin", "local-member", "a".repeat(64),
                canRemove = true, canPause = true, canWarn = true,
                authorName = "Alex", text = "Vi ses vid stationen.",
                serverNowMillis = System.currentTimeMillis(), observedElapsedMillis = SystemClock.elapsedRealtime())
        }
        override suspend fun decide(payload: Map<String, Any?>) { decisions += payload }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "chat-moderation-qa")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun render(repository: LocalRepository, admin: androidx.compose.runtime.State<Boolean>, surface: ChatSurface = ChatSurface.CommunityChannel) {
        compose.setContent {
            KccTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    ChannelChatContent(
                        messages = listOf(ChannelMessage("local-message", "local-member", "Vi ses vid stationen.",
                            "Alex", null, 1794510000000L, null)),
                        currentUid = "local-admin", loading = false, emptyText = "",
                        canLoadOlder = false, isLoadingOlder = false,
                        onSend = { _, _, _ -> }, onRetry = {}, onLoadOlder = {},
                        surface = surface, chatRepliesEnabled = true,
                        publicModeration = PublicChatModerationSession("local-admin", repository, admin.value),
                    )
                }
            }
        }
        compose.onNodeWithText("Vi ses vid stationen.").performTouchInput { longClick() }
    }

    @Test fun removeWithWarningAndDaysPauseOnlySubmitsAfterReview() {
        val repository = LocalRepository()
        render(repository, mutableStateOf(true))
        compose.waitUntil(10000) { compose.onAllNodesWithTag("chat_admin_remove").fetchSemanticsNodes().isNotEmpty() }
        screenshot("admin-menu-dark")
        compose.onNodeWithTag("chat_admin_remove").performScrollTo().performClick()
        compose.onNodeWithTag("chat_admin_warn_no").assertIsSelected()
        compose.onNodeWithTag("chat_admin_add_pause_no").assertIsSelected()
        compose.onNodeWithTag("chat_admin_reason").performScrollTo().performTextInput("Bryter mot chattreglerna")
        compose.onNodeWithTag("chat_admin_warn_yes").performScrollTo().performClick()
        compose.onNodeWithTag("chat_admin_add_pause_yes").performScrollTo().performClick()
        compose.onNodeWithTag("chat_admin_amount").performScrollTo().performTextInput("2")
        compose.onNodeWithTag("chat_admin_unit_days").performScrollTo().performClick()
        screenshot("delete-warning-days-dark")
        compose.onNodeWithTag("chat_admin_review").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(repository.decisions.isEmpty()) }
        screenshot("combined-review-dark")
        compose.onNodeWithTag("chat_admin_confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { repository.decisions.size == 1 }
        compose.runOnIdle {
            val payload = repository.decisions.single()
            assertEquals("remove", payload["action"])
            assertEquals(true, payload["warn"])
            assertEquals(mapOf("amount" to 2, "unit" to "days"), payload["pause"])
        }
    }

    @Test fun ordinaryUserNeverRequestsOrSeesAdminCapabilities() {
        val repository = LocalRepository()
        render(repository, mutableStateOf(false))
        compose.onNodeWithTag(MESSAGE_ACTIONS_REPLY_TEST_TAG).assertExists()
        compose.onNodeWithTag("chat_admin_section").assertDoesNotExist()
        screenshot("ordinary-menu-dark")
        compose.runOnIdle { assertEquals(0, repository.contextCalls); assertTrue(repository.decisions.isEmpty()) }
    }

    @Test fun revocationRemovesOpenEditorWithoutSubmitting() {
        val repository = LocalRepository()
        val admin = mutableStateOf(true)
        render(repository, admin)
        compose.waitUntil(10000) { compose.onAllNodesWithTag("chat_admin_remove").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_admin_remove").performScrollTo().performClick()
        compose.onNodeWithTag("chat_admin_reason").performScrollTo().performTextInput("Reason")
        compose.runOnIdle { admin.value = false }
        compose.onNodeWithTag("chat_admin_editor").assertDoesNotExist()
        compose.onNodeWithTag("chat_admin_section").assertDoesNotExist()
        compose.runOnIdle { assertTrue(repository.decisions.isEmpty()) }
    }

    @Test fun convoyIgnoresPublicAdminSession() {
        val repository = LocalRepository()
        render(repository, mutableStateOf(true), ChatSurface.ConvoyChannel)
        compose.onNodeWithTag(MESSAGE_ACTIONS_REPLY_TEST_TAG).assertExists()
        compose.onNodeWithTag("chat_admin_section").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, repository.contextCalls) }
    }
}
