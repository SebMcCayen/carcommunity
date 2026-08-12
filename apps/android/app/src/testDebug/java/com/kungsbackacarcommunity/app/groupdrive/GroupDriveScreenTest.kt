package com.kungsbackacarcommunity.app.groupdrive

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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

/**
 * Compose UI tests for the group-driving screen (Phase 12 slice 11).
 */
@RunWith(AndroidJUnit4::class)
class GroupDriveScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun notParticipating_eligible_showsJoin() {
        var joined = 0
        composeTestRule.setContent {
            KccTheme {
                GroupDriveScreen(
                    participants = emptyList(),
                    myStatus = null,
                    canJoin = true,
                    actionStatus = GroupDriveActionStatus.Idle,
                    onJoin = { joined++ },
                    onSetStatus = {},
                    onLeave = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.groupDrive_joinButton)).performScrollTo().performClick()
        assertEquals(1, joined)
    }

    @Test
    fun participating_showsLeaveAndStatus_andSetStatusWorks() {
        var setStatus: GroupDriveStatus? = null
        composeTestRule.setContent {
            KccTheme {
                GroupDriveScreen(
                    participants =
                        listOf(GroupDriveParticipant("me", "Me", GroupDriveStatus.JOINED)),
                    myStatus = GroupDriveStatus.JOINED,
                    canJoin = true,
                    actionStatus = GroupDriveActionStatus.Idle,
                    onJoin = {},
                    onSetStatus = { setStatus = it },
                    onLeave = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.groupDrive_leaveButton)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.groupDrive_statusArrived)).performScrollTo().performClick()
        assertEquals(GroupDriveStatus.ARRIVED, setStatus)
    }

    @Test
    fun notEligible_notParticipating_showsGate() {
        composeTestRule.setContent {
            KccTheme {
                GroupDriveScreen(
                    participants = emptyList(),
                    myStatus = null,
                    canJoin = false,
                    actionStatus = GroupDriveActionStatus.Idle,
                    onJoin = {},
                    onSetStatus = {},
                    onLeave = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.groupDrive_memberRequired)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.groupDrive_joinButton)).assertDoesNotExist()
    }
}
