package com.kungsbackacarcommunity.app.groupdrive

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupDriveRedesignTest {
    @get:Rule val compose = createComposeRule()

    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun roster_showsActualActiveParticipants_andOmitsLeftParticipants() {
        compose.setContent {
            KccTheme {
                GroupDriveScreen(
                    participants = listOf(
                        GroupDriveParticipant("active", "A long participant display name", GroupDriveStatus.ON_THE_WAY),
                        GroupDriveParticipant("left", "Former participant", GroupDriveStatus.LEFT),
                    ),
                    myStatus = null, canJoin = true, actionStatus = GroupDriveActionStatus.Idle,
                    onJoin = {}, onSetStatus = {}, onLeave = {}, onBack = {},
                )
            }
        }
        compose.onNodeWithTag("group_drive_participant_active").assertIsDisplayed()
        compose.onNodeWithText("A long participant display name").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.groupDrive_statusOnTheWay)).assertIsDisplayed()
        compose.onNodeWithTag("group_drive_participant_left").assertDoesNotExist()
    }

    @Test
    fun join_reportsOnlyExplicitTap() {
        var joined = 0
        compose.setContent {
            KccTheme {
                GroupDriveScreen(
                    participants = emptyList(), myStatus = null, canJoin = true,
                    actionStatus = GroupDriveActionStatus.Idle,
                    onJoin = { joined++ }, onSetStatus = {}, onLeave = {}, onBack = {},
                )
            }
        }
        assertEquals(0, joined)
        compose.onNodeWithText(str(R.string.groupDrive_joinButton)).performScrollTo().performClick()
        assertEquals(1, joined)
    }

    @Test
    fun busy_joinRemainsDisabled() {
        compose.setContent {
            KccTheme {
                GroupDriveScreen(
                    participants = emptyList(), myStatus = null, canJoin = true,
                    actionStatus = GroupDriveActionStatus.Working,
                    onJoin = {}, onSetStatus = {}, onLeave = {}, onBack = {},
                )
            }
        }
        compose.onNodeWithText(str(R.string.groupDrive_joinButton)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun existingParticipantCanLeaveAfterJoinEligibilityIsLost() {
        var leaves = 0
        compose.setContent {
            KccTheme {
                GroupDriveScreen(
                    participants = List(30) { GroupDriveParticipant("test-$it", "Test participant $it", GroupDriveStatus.JOINED) },
                    myStatus = GroupDriveStatus.JOINED, canJoin = false,
                    actionStatus = GroupDriveActionStatus.Idle,
                    onJoin = {}, onSetStatus = {}, onLeave = { leaves++ }, onBack = {},
                )
            }
        }
        // Participation controls precede the long roster; loss of RSVP eligibility
        // must not trap an existing participant or imply starting location sharing.
        compose.onNodeWithText(str(R.string.groupDrive_leaveButton)).performScrollTo().performClick()
        assertEquals(1, leaves)
        compose.onNodeWithText(str(R.string.groupDrive_joinButton)).assertDoesNotExist()
    }

    @Test
    fun failedActionFeedbackRemainsVisibleBeforeLongRoster() {
        compose.setContent {
            KccTheme {
                GroupDriveScreen(
                    participants = List(30) { GroupDriveParticipant("test-$it", "Test participant $it", GroupDriveStatus.JOINED) },
                    myStatus = null, canJoin = true, actionStatus = GroupDriveActionStatus.Failed,
                    onJoin = {}, onSetStatus = {}, onLeave = {}, onBack = {},
                )
            }
        }
        compose.onNodeWithText(str(R.string.groupDrive_error)).assertIsDisplayed()
    }
}
