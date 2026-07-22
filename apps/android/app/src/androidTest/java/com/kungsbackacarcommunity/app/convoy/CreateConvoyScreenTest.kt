package com.kungsbackacarcommunity.app.convoy

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendsStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The create-convoy picker must go INERT during the create + the post-create
 * hand-off to the map. The flow no longer swaps to a "Convoy created" confirmation
 * page on success, so without this the submit button and friend rows would
 * re-enable while [CreateConvoyState.Created] is briefly on screen (it is not
 * [CreateConvoyState.Working] and the selection is still non-empty) — letting a
 * second tap fire a second `convoy.create` before the host navigates away.
 */
@RunWith(AndroidJUnit4::class)
class CreateConvoyScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val friends =
        FriendsStatus.Loaded(
            friends =
                listOf(
                    FriendSummary(
                        uid = "u2",
                        displayName = "Alice",
                        avatarPath = null,
                        friendsSince = null,
                    ),
                ),
            incoming = emptyList(),
            outgoing = emptyList(),
        )

    /**
     * With a friend selected and a create still to run (Idle), the submit button is
     * live — the baseline the inert-during-hand-off assertions below contrast with.
     */
    @Test
    fun submitEnabledWhenIdleWithSelection() {
        composeTestRule.setContent {
            KccTheme {
                CreateConvoyScreen(
                    friendsStatus = friends,
                    createState = CreateConvoyState.Idle,
                    selectedUids = setOf("u2"),
                    onToggleFriend = {},
                    onRetryFriends = null,
                    onSubmit = {},
                )
            }
        }
        composeTestRule.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).assertIsEnabled()
    }

    /**
     * Once the create has SUCCEEDED (Created), the submit button is disabled and a
     * click does not fire a second submit, and the friend picker is inert too — so
     * nothing can start a second `convoy.create` or change the selection during the
     * brief window before the host dismisses to the map.
     */
    @Test
    fun submitAndPickerInertOnceCreated() {
        var submits = 0
        var toggles = 0
        composeTestRule.setContent {
            KccTheme {
                CreateConvoyScreen(
                    friendsStatus = friends,
                    createState =
                        CreateConvoyState.Created(convoyId = "c1", skipped = emptyList()),
                    selectedUids = setOf("u2"),
                    onToggleFriend = { toggles++ },
                    onRetryFriends = null,
                    onSubmit = { submits++ },
                )
            }
        }

        val submit = composeTestRule.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG)
        submit.assertIsNotEnabled()
        submit.performClick()

        // The friend row is inert as well: tapping it must not change the selection.
        composeTestRule.onNodeWithText("Alice").performClick()

        composeTestRule.runOnIdle {
            assertEquals("no second create may fire during hand-off", 0, submits)
            assertEquals("selection is frozen during hand-off", 0, toggles)
        }
    }

    /** While the create is in flight (Working), the button is likewise disabled. */
    @Test
    fun submitDisabledWhileWorking() {
        composeTestRule.setContent {
            KccTheme {
                CreateConvoyScreen(
                    friendsStatus = friends,
                    createState = CreateConvoyState.Working,
                    selectedUids = setOf("u2"),
                    onToggleFriend = {},
                    onRetryFriends = null,
                    onSubmit = {},
                )
            }
        }
        composeTestRule.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).assertIsNotEnabled()
    }
}
