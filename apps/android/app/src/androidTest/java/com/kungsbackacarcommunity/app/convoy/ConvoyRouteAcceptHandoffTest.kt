package com.kungsbackacarcommunity.app.convoy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The accept side of [ConvoyRoute], end to end: what a member SEES when they
 * accept a convoy invite they reached from a notification, and where they end up.
 *
 * ## The bug this pins
 * Accepting used to answer with "You've already answered that invite." The
 * accept was never duplicated — the coordinator's in-flight guard and the row's
 * disabled button already ruled that out, and the backend's own rejection maps
 * to a DIFFERENT string ("That invite is no longer available"). The notice came
 * from the invite deep link's freshness re-check, which is re-derived from the
 * live list on every recomposition: a successful accept is exactly what takes
 * the invite out of `pendingInvites` while leaving the joined convoy in
 * `convoys`, which is the shape the check reads as "answered elsewhere". The
 * member's own tap was being reported back to them as a stale-link warning.
 */
@RunWith(AndroidJUnit4::class)
class ConvoyRouteAcceptHandoffTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /** The app's DEFAULT resources are Swedish; English lives in `values-en/`. */
    private fun string(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun summary(inviteStatus: ConvoyInviteStatus) =
        ConvoySummary(
            convoyId = CONVOY_ID,
            ownerUid = "owner",
            title = null,
            status = ConvoyStatus.Active,
            members =
                listOf(
                    ConvoyMember(
                        uid = "owner",
                        role = ConvoyRole.Owner,
                        inviteStatus = ConvoyInviteStatus.Accepted,
                        joinedAt = null,
                        displayName = OWNER_NAME,
                        avatarPath = null,
                    ),
                ),
            memberUids = listOf("owner", "me"),
            viewer = ConvoyViewer(ConvoyRole.Member, inviteStatus),
            livePositionUids = emptyList(),
            summary = null,
            createdAt = null,
            startedAt = null,
            endedAt = null,
        )

    /**
     * Serves the pending invite until it is answered, then the SAME convoy with
     * no pending invite — i.e. exactly what the backend returns after a
     * successful accept, and exactly the shape that used to trip the notice.
     */
    private inner class FakeConvoyRepository : ConvoyRepository {
        var respondCalls = 0
        var lastAccept: Boolean? = null
        private var answered = false

        override suspend fun list(): ConvoyListResult =
            if (answered) {
                ConvoyListResult.Loaded(
                    convoys = listOf(summary(ConvoyInviteStatus.Accepted)),
                    pendingInvites = emptyList(),
                )
            } else {
                val invite = summary(ConvoyInviteStatus.Invited)
                ConvoyListResult.Loaded(convoys = listOf(invite), pendingInvites = listOf(invite))
            }

        override fun observeConvoy(convoyId: String, callerUid: String?): Flow<ConvoySummary?> =
            emptyFlow()

        override suspend fun respond(convoyId: String, accept: Boolean): ConvoyMutationResult {
            respondCalls++
            lastAccept = accept
            answered = true
            return ConvoyMutationResult.Updated(summary(ConvoyInviteStatus.Accepted))
        }

        override suspend fun create(inviteeUids: List<String>, title: String?) = error("unused")

        override suspend fun invite(convoyId: String, inviteeUids: List<String>) = error("unused")

        override suspend fun leave(convoyId: String) = error("unused")

        override suspend fun start(convoyId: String) = error("unused")

        override suspend fun end(convoyId: String) = error("unused")
    }

    private fun awaitInviteRow() {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText(string(R.string.convoy_accept))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /**
     * THE REGRESSION. Arrive on the deep-linked invite, accept it, and the
     * "already answered" notice must never appear — while the accept itself
     * fires exactly once and hands the convoy off to the host.
     */
    @Test
    fun acceptingADeepLinkedInviteNeverSaysItWasAlreadyAnswered() {
        val repo = FakeConvoyRepository()
        var joined: String? = null
        var joins = 0
        composeTestRule.setContent {
            KccTheme {
                var dismissed by remember { mutableStateOf(false) }
                if (!dismissed) {
                    ConvoyRoute(
                        repository = repo,
                        friendsRepository = null,
                        inviteDeepLinkConvoyId = CONVOY_ID,
                        onConvoyJoined = { convoyId ->
                            joins++
                            joined = convoyId
                            // The production host closes the whole surface.
                            dismissed = true
                        },
                    )
                }
            }
        }

        awaitInviteRow()
        composeTestRule.onNodeWithText(string(R.string.convoy_accept)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) { joins == 1 }
        composeTestRule
            .onNodeWithText(string(R.string.convoy_inviteLinkAnswered))
            .assertDoesNotExist()
        assertEquals("exactly one convoy.respond", 1, repo.respondCalls)
        assertEquals(true, repo.lastAccept)
        assertEquals("the host is told WHICH convoy to frame", CONVOY_ID, joined)
        assertEquals("handed off exactly once", 1, joins)
    }

    /**
     * Declining the same deep-linked invite empties the pending list in exactly
     * the same way, so it must be just as silent — and it must NOT hand off to
     * the map, because the member did not join anything.
     */
    @Test
    fun decliningADeepLinkedInviteIsSilentAndDoesNotHandOff() {
        val repo = FakeConvoyRepository()
        var joins = 0
        composeTestRule.setContent {
            KccTheme {
                ConvoyRoute(
                    repository = repo,
                    friendsRepository = null,
                    inviteDeepLinkConvoyId = CONVOY_ID,
                    onConvoyJoined = { joins++ },
                )
            }
        }

        awaitInviteRow()
        composeTestRule.onNodeWithText(string(R.string.convoy_decline)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) { repo.respondCalls == 1 }
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(string(R.string.convoy_inviteLinkAnswered))
            .assertDoesNotExist()
        assertEquals(false, repo.lastAccept)
        assertEquals("a decline joins nothing", 0, joins)
    }

    /**
     * The fallback host (config-less / test surface with no map): the accept
     * still runs and still says nothing, but there is nowhere to dissolve to, so
     * the route stays on its refreshed list.
     */
    @Test
    fun withNoMapHostTheAcceptStillSucceedsAndStaysPut() {
        val repo = FakeConvoyRepository()
        composeTestRule.setContent {
            KccTheme {
                ConvoyRoute(
                    repository = repo,
                    friendsRepository = null,
                    inviteDeepLinkConvoyId = CONVOY_ID,
                    onConvoyJoined = null,
                )
            }
        }

        awaitInviteRow()
        composeTestRule.onNodeWithText(string(R.string.convoy_accept)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) { repo.respondCalls == 1 }
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(string(R.string.convoy_inviteLinkAnswered))
            .assertDoesNotExist()
        // Still on the convoy surface: the list's own title is there.
        composeTestRule.onNodeWithText(string(R.string.convoy_title)).assertExists()
        assertEquals("exactly one convoy.respond", 1, repo.respondCalls)
    }

    private companion object {
        const val CONVOY_ID = "c1"
        const val OWNER_NAME = "Owner"
    }
}
