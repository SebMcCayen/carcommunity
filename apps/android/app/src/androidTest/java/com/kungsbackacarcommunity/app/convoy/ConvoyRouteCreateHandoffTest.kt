package com.kungsbackacarcommunity.app.convoy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendsData
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import com.kungsbackacarcommunity.app.friends.FriendsResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end cover for the post-create hand-off through [ConvoyRoute]: selecting a
 * friend and submitting fires EXACTLY ONE `convoy.create`, invokes the host's
 * dismissal once, and leaves the create screen — so the picker/submit are gone (not
 * re-enabled) once creation succeeds and the route is torn down.
 *
 * Complements [CreateConvoyScreenTest], which proves the create screen is inert
 * WHILE `CreateConvoyState.Created` is on screen (a tap fires no second submit).
 * The route's `LaunchedEffect(createState)` navigates FIRST, then `resetCreate()`,
 * so the create screen is dismissed before the Idle reset could re-enable it.
 */
@RunWith(AndroidJUnit4::class)
class ConvoyRouteCreateHandoffTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val alice =
        FriendSummary(uid = "u2", displayName = "Alice", avatarPath = null, friendsSince = null)

    private fun summary() =
        ConvoySummary(
            convoyId = "c1",
            ownerUid = "me",
            title = null,
            status = ConvoyStatus.Forming,
            members = emptyList(),
            memberUids = emptyList(),
            viewer = ConvoyViewer(ConvoyRole.Owner, ConvoyInviteStatus.Accepted),
            livePositionUids = emptyList(),
            summary = null,
            createdAt = null,
            startedAt = null,
            endedAt = null,
        )

    /** Records how many times `convoy.create` ran; every other call is unused here. */
    private inner class FakeConvoyRepository : ConvoyRepository {
        var createCount = 0

        override suspend fun list(): ConvoyListResult =
            ConvoyListResult.Loaded(convoys = emptyList(), pendingInvites = emptyList())

        override fun observeConvoy(convoyId: String, callerUid: String?): Flow<ConvoySummary?> =
            emptyFlow()

        override suspend fun create(inviteeUids: List<String>, title: String?): CreateConvoyResult {
            createCount++
            return CreateConvoyResult.Created(
                convoy = summary(),
                invited = inviteeUids,
                skipped = emptyList(),
            )
        }

        override suspend fun respond(convoyId: String, accept: Boolean) = error("unused")

        override suspend fun invite(convoyId: String, inviteeUids: List<String>) = error("unused")

        override suspend fun leave(convoyId: String) = error("unused")

        override suspend fun start(convoyId: String) = error("unused")

        override suspend fun end(convoyId: String) = error("unused")
    }

    private inner class FakeFriendsRepository : FriendsRepository {
        override suspend fun list(): FriendsResult =
            FriendsResult.Loaded(
                FriendsData(friends = listOf(alice), incoming = emptyList(), outgoing = emptyList()),
            )

        override suspend fun sendRequestByNickname(nickname: String) = error("unused")

        override suspend fun sendRequestToUid(toUid: String) = error("unused")

        override suspend fun respond(requestId: String, accept: Boolean) = error("unused")

        override suspend fun remove(friendUid: String) = error("unused")
    }

    private fun selectAliceAndSubmit() {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Alice").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Alice").performClick()
        composeTestRule.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).performClick()
    }

    /**
     * The MAP path: the host dismisses the route on success (real production wiring,
     * modelled by removing [ConvoyRoute] from composition when `onConvoyCreated`
     * fires). After submit: exactly one create ran, the host was dismissed once, and
     * the create submit control is gone — the screen never re-enables after reset.
     */
    @Test
    fun successFiresOneCreateThenHostDismissesAndScreenIsGone() {
        val repo = FakeConvoyRepository()
        var handoffs = 0
        composeTestRule.setContent {
            KccTheme {
                var dismissed by remember { mutableStateOf(false) }
                if (!dismissed) {
                    ConvoyRoute(
                        repository = repo,
                        friendsRepository = FakeFriendsRepository(),
                        openCreateOnEntry = true,
                        onConvoyCreated = {
                            handoffs++
                            dismissed = true
                        },
                    )
                }
            }
        }

        selectAliceAndSubmit()

        composeTestRule.waitUntil(timeoutMillis = 5_000) { handoffs == 1 }
        composeTestRule.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).assertDoesNotExist()
        assertEquals("exactly one convoy.create", 1, repo.createCount)
        assertEquals("host dismissed exactly once", 1, handoffs)
    }

    /**
     * The FALLBACK path (null host, e.g. a config-less/test surface with no map):
     * [ConvoyRoute] navigates to the new convoy's detail itself, then resets — so the
     * create screen is likewise gone after exactly one create.
     */
    @Test
    fun nullHostNavigatesOffCreateAfterOneCreate() {
        val repo = FakeConvoyRepository()
        composeTestRule.setContent {
            KccTheme {
                ConvoyRoute(
                    repository = repo,
                    friendsRepository = FakeFriendsRepository(),
                    openCreateOnEntry = true,
                    onConvoyCreated = null,
                )
            }
        }

        selectAliceAndSubmit()

        composeTestRule.waitUntil(timeoutMillis = 5_000) { repo.createCount == 1 }
        composeTestRule.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).assertDoesNotExist()
        assertEquals("exactly one convoy.create", 1, repo.createCount)
    }
}
