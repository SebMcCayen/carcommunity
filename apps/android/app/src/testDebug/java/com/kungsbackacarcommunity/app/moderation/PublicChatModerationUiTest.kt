package com.kungsbackacarcommunity.app.moderation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import java.io.IOException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class PublicChatModerationUiTest {
    @get:Rule val compose = createComposeRule()
    private val capability = PublicChatModerationContext("admin", "member", "version", true, true, true)
    private class Repository(var result: PublicChatModerationContext?) : PublicChatModerationRepository {
        var contextCalls = 0
        override fun observeAdminCandidate() = flowOf(false)
        override fun observeOwnPause() = flowOf<PublicChatWritePause?>(null)
        override suspend fun context(target: PublicChatTarget): PublicChatModerationContext {
            contextCalls++
            return result ?: throw IOException("Unavailable")
        }
        override suspend fun decide(payload: Map<String, Any?>) { error("Unexpected mutation") }
    }

    @Test fun ordinaryUserHasNoAdminEntriesAndNoCapabilityLookup() {
        val repo = Repository(capability)
        compose.setContent { KccTheme {
            MessageActionsSheet("Member", false, ReportAvailability.Wired, {}, {}, {},
                publicModeration = PublicChatModerationSession("admin", repo, false),
                publicTarget = PublicChatTarget("community", "message", expectedAuthorUid = "member"))
        } }
        compose.onNodeWithTag("chat_admin_section").assertDoesNotExist()
        compose.onNodeWithTag("chat_admin_remove").assertDoesNotExist()
        compose.onNodeWithTag("chat_admin_pause").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, repo.contextCalls) }
    }

    @Test fun roleRevocationClosesEditorAndFailedRefreshDoesNotReuseOldCapability() {
        val repo = Repository(capability)
        val admin = mutableStateOf(true)
        compose.setContent { KccTheme {
            MessageActionsSheet("Member", false, ReportAvailability.Wired, {}, {}, {},
                publicModeration = PublicChatModerationSession("admin", repo, admin.value),
                publicTarget = PublicChatTarget("community", "message", expectedAuthorUid = "member"))
        } }
        compose.waitUntil { repo.contextCalls == 1 }
        compose.onNodeWithTag("chat_admin_remove").performScrollTo().performClick()
        compose.onNodeWithTag("chat_admin_editor").assertExists()
        compose.runOnIdle { admin.value = false; repo.result = null }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_admin_editor").assertDoesNotExist()
        compose.onNodeWithTag("chat_admin_remove").assertDoesNotExist()
        compose.runOnIdle { admin.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Role regain must request a fresh capability", 2, repo.contextCalls) }
        compose.onNodeWithTag("chat_admin_remove").assertDoesNotExist()
        compose.onNodeWithTag("chat_admin_pause").assertDoesNotExist()
    }

    @Test fun mismatchedActorOrAuthorCannotExposeAdminRows() {
        val repo = Repository(capability.copy(authorUid = "different-author"))
        compose.setContent { KccTheme {
            MessageActionsSheet("Member", false, ReportAvailability.Wired, {}, {}, {},
                publicModeration = PublicChatModerationSession("admin", repo, true),
                publicTarget = PublicChatTarget("community", "message", expectedAuthorUid = "member"))
        } }
        compose.waitUntil { repo.contextCalls == 1 }
        compose.onNodeWithTag("chat_admin_remove").assertDoesNotExist()
    }

    @Test fun accountSwitchDiscardsCapabilityFromPreviousAccount() {
        val repo = Repository(capability)
        val uid = mutableStateOf("admin")
        compose.setContent { KccTheme {
            MessageActionsSheet("Member", false, ReportAvailability.Wired, {}, {}, {},
                publicModeration = PublicChatModerationSession(uid.value, repo, true),
                publicTarget = PublicChatTarget("community", "message", expectedAuthorUid = "member"))
        } }
        compose.waitUntil { repo.contextCalls == 1 }
        compose.onNodeWithTag("chat_admin_remove").assertExists()
        compose.runOnIdle { uid.value = "different-account" }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Account change must request a fresh capability", 2, repo.contextCalls) }
        compose.onNodeWithTag("chat_admin_remove").assertDoesNotExist()
        compose.onNodeWithTag("chat_admin_pause").assertDoesNotExist()
    }

    @Test fun inFlightDecisionCannotBeSubmittedTwiceAndAdditionsDefaultToNo() {
        var submissions = 0
        val pending = CompletableDeferred<Unit>()
        compose.setContent { KccTheme {
            Column(Modifier.requiredSize(390.dp, 800.dp).verticalScroll(rememberScrollState())) {
                ChatModerationEditor(ChatAdminAction.Remove, capability, null, {},
                    onSubmit = { draft, _ ->
                        submissions++
                        assertFalse(draft.warn); assertFalse(draft.pauses)
                        pending.await()
                    })
            }
        } }
        compose.onNodeWithTag("chat_admin_warn_no").assertIsSelected()
        compose.onNodeWithTag("chat_admin_add_pause_no").assertIsSelected()
        compose.onNodeWithTag("chat_admin_reason").performTextInput("Reason")
        compose.onNodeWithTag("chat_admin_review").performScrollTo().performClick()
        compose.onNodeWithTag("chat_admin_confirm").performScrollTo().performClick()
        compose.onNodeWithTag("chat_admin_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("chat_admin_cancel").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, submissions); pending.complete(Unit) }
    }

    @Test fun combinedDeletionRequiresReviewAndRetainsStableOperationAfterTransportFailure() {
        val calls = mutableListOf<Pair<ChatModerationDraft, String>>()
        compose.setContent { KccTheme {
            Column(Modifier.requiredSize(390.dp, 800.dp).verticalScroll(rememberScrollState())) {
                ChatModerationEditor(ChatAdminAction.Remove, capability, MessageActionsPreview("Member", "Text"), {},
                    onSubmit = { draft, key -> calls += draft to key; if (calls.size == 1) throw IOException("Offline") })
            }
        } }
        compose.onNodeWithTag("chat_admin_confirm").assertDoesNotExist()
        compose.onNodeWithTag("chat_admin_reason").performTextInput("Reason")
        compose.onNodeWithTag("chat_admin_warn_yes").performScrollTo().performClick()
        compose.onNodeWithTag("chat_admin_add_pause_yes").performScrollTo().performClick()
        compose.onNodeWithTag("chat_admin_amount").performScrollTo().performTextInput("2")
        compose.onNodeWithTag("chat_admin_unit_days").performScrollTo().performClick()
        compose.onNodeWithTag("chat_admin_review").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithTag("chat_admin_confirm").performScrollTo().performClick()
        compose.onNodeWithTag("chat_admin_error").performScrollTo().assertExists()
        compose.onNodeWithTag("chat_admin_confirm").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, calls.size)
            assertEquals(calls[0], calls[1])
            assertEquals(ChatPauseUnit.Days, calls[0].first.unit)
            assertTrue(calls[0].first.warn && calls[0].first.pauses)
        }
    }

    @Test fun cancellingDoesNotSubmitAndPauseRequiresValidAmount() {
        var submissions = 0
        var cancelled = 0
        compose.setContent { KccTheme {
            Column(Modifier.requiredSize(390.dp, 800.dp).verticalScroll(rememberScrollState())) {
                ChatModerationEditor(ChatAdminAction.Pause, capability, null, { cancelled++ },
                    onSubmit = { _, _ -> submissions++ })
            }
        } }
        compose.onNodeWithTag("chat_admin_reason").performTextInput("Reason")
        compose.onNodeWithTag("chat_admin_review").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("chat_admin_amount").performScrollTo().performTextInput("0")
        compose.onNodeWithTag("chat_admin_review").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("chat_admin_cancel").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, cancelled); assertEquals(0, submissions) }
    }
}
