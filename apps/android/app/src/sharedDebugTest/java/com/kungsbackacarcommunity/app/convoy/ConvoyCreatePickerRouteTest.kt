package com.kungsbackacarcommunity.app.convoy

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.friends.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production route, including accounting before the host dismisses it. */
@RunWith(AndroidJUnit4::class)
class ConvoyCreatePickerRouteTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var backDispatcher: androidx.activity.OnBackPressedDispatcher
    private val alice = FriendSummary("a", "Alice", null, null)
    private val bob = FriendSummary("b", "Bob", null, null)

    private class Usage : ConvoyInviteUsageStore {
        val recorded = java.util.concurrent.CopyOnWriteArrayList<Pair<String, List<String>>>()
        override fun counts(uid: String) = if (uid == "me") mapOf("b" to 4) else emptyMap()
        override fun record(uid: String, invitedUids: List<String>) { recorded += uid to invitedUids }
    }

    private class Repo : ConvoyRepository {
        var gate: CompletableDeferred<Unit>? = null
        var calls = 0
        var payload = emptyList<String>()
        var fail = false
        override suspend fun list() = ConvoyListResult.Loaded(emptyList(), emptyList())
        override fun observeConvoy(convoyId: String, callerUid: String?) = emptyFlow<ConvoySummary?>()
        override suspend fun create(inviteeUids: List<String>, title: String?, vehicleId: String?): CreateConvoyResult {
            calls++
            payload = inviteeUids
            gate?.await()
            return if (fail) CreateConvoyResult.Failed(ConvoyActionError.Generic) else CreateConvoyResult.Created(
                ConvoySummary("created", "me", null, ConvoyStatus.Active, emptyList(), emptyList(),
                    null, emptyList(), null, null, null, null),
                listOf("b"), listOf(SkippedInvitee("a", ConvoySkipReason.NotFriend)),
            )
        }
        override suspend fun respond(convoyId: String, accept: Boolean) = error("unused")
        override suspend fun invite(convoyId: String, inviteeUids: List<String>) = error("unused")
        override suspend fun leave(convoyId: String): LeaveConvoyResult = error("unused")
        override suspend fun start(convoyId: String) = error("unused")
        override suspend fun end(convoyId: String) = error("unused")
    }

    private class Friends(private val friends: List<FriendSummary>) : FriendsRepository {
        override suspend fun list() = FriendsResult.Loaded(FriendsData(friends, emptyList(), emptyList()))
        override suspend fun sendRequestByNickname(nickname: String) = error("unused")
        override suspend fun sendRequestToUid(toUid: String) = error("unused")
        override suspend fun respond(requestId: String, accept: Boolean) = error("unused")
        override suspend fun cancelRequest(toUid: String) = error("unused")
        override suspend fun remove(friendUid: String) = error("unused")
    }

    private fun open(repo: Repo, usage: Usage, friends: List<FriendSummary> = listOf(alice, bob)) {
        val friendRepo = Friends(friends)
        compose.setContent {
            backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            KccTheme {
                var dismissed by remember { mutableStateOf(false) }
                if (!dismissed) ConvoyRoute(
                    repository = repo, friendsRepository = friendRepo, viewerUid = "me",
                    openCreateOnEntry = true, inviteUsageStore = usage,
                    onConvoyCreated = { dismissed = true },
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Bob").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    @Test fun topActionRankingSearchAndHiddenSelectionSubmitOnlyConfirmedUsage() {
        val repo = Repo()
        val usage = Usage()
        open(repo, usage)
        // Wait for the asynchronous local hint read without assuming that every
        // row fits the host viewport (this fixture also runs on native devices).
        val toggleable = SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)
        compose.waitUntil(10_000) {
            val first = compose.onAllNodes(toggleable).fetchSemanticsNodes().firstOrNull()
            val bobNode = compose.onAllNodesWithText("Bob").fetchSemanticsNodes().firstOrNull()
            first != null && first.id == bobNode?.id
        }
        val submit = compose.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).fetchSemanticsNode().boundsInRoot
        val bob = compose.onNodeWithText("Bob").fetchSemanticsNode().boundsInRoot
        assertTrue("submit above list", submit.bottom <= bob.top)
        compose.onNodeWithText("Bob").performClick()
        compose.onNodeWithTag(CONVOY_CREATE_SEARCH_TAG).performTextReplacement("  ALI  ")
        compose.onNodeWithText("Bob").assertDoesNotExist()
        compose.onNodeWithText("Alice").performClick()
        compose.onNodeWithTag(CONVOY_CREATE_SEARCH_TAG).performTextReplacement("nobody")
        compose.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).assertIsEnabled().performClick()
        compose.waitUntil(10_000) { usage.recorded.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(setOf("a", "b"), repo.payload.toSet())
        assertEquals(listOf("me" to listOf("b")), usage.recorded)
        assertEquals(1, repo.calls)
        compose.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).assertDoesNotExist()
    }

    @Test fun pendingSubmitDisablesControlsAndFailureKeepsSelectionForSuccessfulRetry() {
        val repo = Repo().apply { gate = CompletableDeferred(); fail = true }
        val usage = Usage()
        open(repo, usage)
        compose.onNodeWithText("Bob").performClick()
        compose.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).performClick()
        compose.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(CONVOY_CREATE_SEARCH_TAG).assertIsNotEnabled()
        compose.onNodeWithText("Bob").assertIsNotEnabled()
        compose.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).performClick()
        assertEquals(1, repo.calls)
        assertTrue(usage.recorded.isEmpty())
        compose.runOnIdle { repo.gate!!.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).assertIsEnabled()
        assertTrue(usage.recorded.isEmpty())
        compose.runOnIdle { repo.fail = false }
        compose.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).performClick()
        compose.waitUntil(10_000) { usage.recorded.size == 1 }
        assertEquals(2, repo.calls)
        assertEquals(listOf("b"), repo.payload)
    }

    @Test fun backAndReopenCannotResetAnInFlightSubmitOrLoseSelection() {
        val repo = Repo().apply { gate = CompletableDeferred() }
        val usage = Usage()
        open(repo, usage)
        compose.onNodeWithText("Bob").performClick()
        compose.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).performClick()
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNodeWithTag(CONVOY_LIST_TAG).assertExists()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(com.kungsbackacarcommunity.app.R.string.convoy_createAction)).performClick()
        compose.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).assertIsNotEnabled().performClick()
        assertEquals(1, repo.calls)
        compose.runOnIdle { repo.gate!!.complete(Unit) }
        compose.waitUntil(10_000) { usage.recorded.size == 1 }
        assertEquals(listOf("b"), repo.payload)
        assertEquals(1, repo.calls)
    }

    @Test fun actionIsVisibleBeforeScrollingALongFriendList() {
        open(Repo(), Usage(), listOf(bob) + (1..70).map { FriendSummary("f$it", "Friend $it", null, null) })
        compose.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Bob").performClick()
        compose.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).assertIsDisplayed().assertIsEnabled()
    }
}
