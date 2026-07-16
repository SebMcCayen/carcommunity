package com.kungsbackacarcommunity.app.convoy

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun member(uid: String, name: String) =
        ConvoyMember(
            uid = uid,
            role = ConvoyRole.Member,
            inviteStatus = ConvoyInviteStatus.Accepted,
            joinedAt = null,
            displayName = name,
            avatarPath = null,
        )

    private fun convoy(members: List<ConvoyMember>) =
        ConvoySummary(
            convoyId = "c1",
            ownerUid = "owner",
            title = "Trip",
            status = ConvoyStatus.Forming,
            members = members,
            memberUids = members.map { it.uid },
            viewer = ConvoyViewer(ConvoyRole.Member, ConvoyInviteStatus.Accepted),
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
}
