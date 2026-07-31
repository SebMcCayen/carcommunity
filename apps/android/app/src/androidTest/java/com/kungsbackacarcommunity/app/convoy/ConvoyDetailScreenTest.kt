package com.kungsbackacarcommunity.app.convoy

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the convoy detail roster's "tap a member → open their
 * profile" entry point.
 */
@RunWith(AndroidJUnit4::class)
class ConvoyDetailScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Strings come from the resources, not retyped in English: the app's DEFAULT
     * resources are Swedish (`values/`), so a hard-coded English literal would
     * only match on an English-locale device.
     */
    private fun string(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun member(uid: String, name: String) =
        ConvoyMember(
            uid = uid,
            role = ConvoyRole.Member,
            inviteStatus = ConvoyInviteStatus.Accepted,
            joinedAt = null,
            displayName = name,
            avatarPath = null,
        )

    private fun convoy(
        members: List<ConvoyMember>,
        status: ConvoyStatus = ConvoyStatus.Forming,
        viewerRole: ConvoyRole = ConvoyRole.Member,
    ) =
        ConvoySummary(
            convoyId = "c1",
            ownerUid = "owner",
            title = "Trip",
            status = status,
            members = members,
            memberUids = members.map { it.uid },
            viewer = ConvoyViewer(viewerRole, ConvoyInviteStatus.Accepted),
            livePositionUids = emptyList(),
            summary = null,
            createdAt = null,
            startedAt = null,
            endedAt = null,
        )

    private fun setContent(members: List<ConvoyMember>, onViewMember: ((String) -> Unit)?) {
        composeTestRule.setContent {
            KccTheme {
                ConvoyDetailScreen(
                    convoy = convoy(members),
                    working = false,
                    actionError = null,
                    onStart = {},
                    onEnd = {},
                    onClearActionError = {},
                    onViewMember = onViewMember,
                    viewerUid = "me",
                )
            }
        }
    }

    @Test
    fun tappingMemberRow_opensThatMembersProfile() {
        var opened: String? = null
        setContent(listOf(member("other", "Alice")), onViewMember = { opened = it })

        composeTestRule.onNodeWithText("Alice").performClick()

        assertEquals("other", opened)
    }

    @Test
    fun ownRow_exposesNoProfileAffordance() {
        var opened: String? = null
        // Consistent with chat, where your own messages carry no sender
        // affordance: your own roster row never opens a profile. Assert the click
        // action is absent rather than clicking and checking nothing happened —
        // clicking a node with no click action silently does nothing, so that
        // would also pass for a row wired to a dead no-op button, which screen
        // readers would still announce as tappable.
        setContent(listOf(member("me", "Me")), onViewMember = { opened = it })

        composeTestRule.onNodeWithText("Me").assertHasNoClickAction()

        assertNull(opened)
    }

    // --- the two exits on the detail screen --------------------------------

    /** Three accepted members, so leaving still leaves two people driving. */
    private fun threeAccepted() =
        listOf(member("owner", "Olle"), member("me", "Me"), member("other", "Alice"))

    private fun setExitContent(
        viewerRole: ConvoyRole,
        members: List<ConvoyMember>,
        onEnd: () -> Unit = {},
        onLeave: (() -> Unit)? = {},
    ) {
        composeTestRule.setContent {
            KccTheme {
                ConvoyDetailScreen(
                    convoy = convoy(members, status = ConvoyStatus.Active, viewerRole = viewerRole),
                    working = false,
                    actionError = null,
                    onStart = {},
                    onEnd = onEnd,
                    onClearActionError = {},
                    viewerUid = "me",
                    onLeave = onLeave,
                )
            }
        }
    }

    /**
     * The leader sees BOTH exits as two separate buttons — the screen has the room
     * the bar does not, so the choice needs no dialog. Each must reach its own
     * action; a screen that wired both to End would be the bug.
     */
    @Test
    fun theLeaderSeesBothExits_andLeaveReachesTheLeaveAction() {
        var ended = false
        var left = false
        setExitContent(
            viewerRole = ConvoyRole.Owner,
            members = threeAccepted(),
            onEnd = { ended = true },
            onLeave = { left = true },
        )

        composeTestRule.onNodeWithTag(CONVOY_DETAIL_END_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CONVOY_DETAIL_LEAVE_TAG).performClick()
        composeTestRule.waitForIdle()
        // Leaving confirms first, exactly like the bar does.
        composeTestRule
            .onNodeWithText(string(R.string.convoy_barLeaveConfirmAction))
            .performClick()
        composeTestRule.waitForIdle()

        assertTrue("the leader's Leave must reach the leave action", left)
        assertFalse("the leader's Leave must not end the convoy for everyone", ended)
    }

    /**
     * A member who is not the leader is never shown "End convoy" — ending is
     * leader-only, and offering a button the backend refuses is worse than not
     * offering it.
     */
    @Test
    fun aNonLeaderSeesOnlyLeave() {
        setExitContent(viewerRole = ConvoyRole.Member, members = threeAccepted())
        composeTestRule.onNodeWithTag(CONVOY_DETAIL_END_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(CONVOY_DETAIL_LEAVE_TAG).assertIsDisplayed()
    }

    /**
     * A leader whose exit would end the convoy anyway is not offered two buttons
     * for one outcome — End alone, which is the honest description of both.
     */
    @Test
    fun aLeaderWhoseExitEndsTheConvoySeesEndAlone() {
        setExitContent(
            viewerRole = ConvoyRole.Owner,
            members = listOf(member("owner", "Olle"), member("me", "Me")),
        )
        composeTestRule.onNodeWithTag(CONVOY_DETAIL_END_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CONVOY_DETAIL_LEAVE_TAG).assertDoesNotExist()
    }

    /** No handler wired (a config-less build) → no dead control at all. */
    @Test
    fun withNoLeaveHandler_theLeaveControlIsOmittedRatherThanDead() {
        setExitContent(viewerRole = ConvoyRole.Member, members = threeAccepted(), onLeave = null)
        composeTestRule.onNodeWithTag(CONVOY_DETAIL_LEAVE_TAG).assertDoesNotExist()
    }
}
