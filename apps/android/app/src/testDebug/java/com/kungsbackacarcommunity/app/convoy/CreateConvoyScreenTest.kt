package com.kungsbackacarcommunity.app.convoy

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendsStatus
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
     * Once the create has SUCCEEDED (Created), the submit button AND the friend rows
     * are disabled — so nothing can start a second `convoy.create` or change the
     * selection during the brief window before the host dismisses to the map.
     *
     * Both are asserted with [assertIsNotEnabled] (the repo convention for a disabled
     * button — see e.g. VehicleDetailScreenTest / EventChatScreenTest), which checks
     * the Disabled semantics flag directly and is therefore NON-vacuous: a disabled
     * control cannot invoke its onClick/onToggle. We deliberately do NOT click-and-
     * assert-nothing (that passes even on a live control that merely no-ops), nor use
     * assertHasNoClickAction — a disabled Compose Button/toggleable still exposes a
     * DISABLED OnClick action (assertHasNoClickAction is for truly non-clickable rows,
     * like the caller's own "Me" row), so that assertion would spuriously fail here.
     */
    @Test
    fun submitAndPickerInertOnceCreated() {
        composeTestRule.setContent {
            KccTheme {
                CreateConvoyScreen(
                    friendsStatus = friends,
                    createState =
                        CreateConvoyState.Created(convoyId = "c1", skipped = emptyList()),
                    selectedUids = setOf("u2"),
                    onToggleFriend = {},
                    onRetryFriends = null,
                    onSubmit = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).assertIsNotEnabled()
        composeTestRule.onNodeWithText("Alice").assertIsNotEnabled()
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
    @Test
    fun capDisablesOnlyUnselectedRowsAndSearchPreservesDeselect() {
        val people = (1..51).map { FriendSummary("u$it", "Friend $it", null, null) }
        composeTestRule.setContent {
            KccTheme {
                var selected by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf((1..50).map { "u$it" }.toSet())
                }
                CreateConvoyScreen(
                    friendsStatus = FriendsStatus.Loaded(people, emptyList(), emptyList()),
                    createState = CreateConvoyState.Idle, selectedUids = selected,
                    onToggleFriend = { uid -> selected = if (uid in selected) selected - uid else selected + uid },
                    onRetryFriends = null, onSubmit = {},
                )
            }
        }
        // The full name is intentionally also the query: hasText matches both
        // EditableText on the enabled search field and Text on the friend row.
        // Target the toggleable row so the cap assertion cannot hit the editor.
        fun friendRow(name: String) = composeTestRule.onNode(
            hasText(name) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState),
        )

        composeTestRule.onNodeWithTag(CONVOY_CREATE_SEARCH_TAG).performTextReplacement("Friend 51")
        composeTestRule.onNodeWithTag(CONVOY_CREATE_SEARCH_TAG).assertIsEnabled().assertTextContains("Friend 51")
        friendRow("Friend 51").assertIsOff().assertIsNotEnabled()
        composeTestRule.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).assertIsEnabled()

        composeTestRule.onNodeWithTag(CONVOY_CREATE_SEARCH_TAG).performTextReplacement("Friend 50")
        friendRow("Friend 50").assertIsOn().assertIsEnabled().performClick()
        friendRow("Friend 50").assertIsOff()

        composeTestRule.onNodeWithTag(CONVOY_CREATE_SEARCH_TAG).performTextReplacement("Friend 51")
        friendRow("Friend 51").assertIsOff().assertIsEnabled().performClick()
        friendRow("Friend 51").assertIsOn()

        // Selecting the replacement restores the cap; the deselected friend is
        // disabled again, while the hidden selections still enable submission.
        composeTestRule.onNodeWithTag(CONVOY_CREATE_SEARCH_TAG).performTextReplacement("Friend 50")
        friendRow("Friend 50").assertIsOff().assertIsNotEnabled()
        composeTestRule.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).assertIsEnabled()
    }

    @Test
    fun staleSelectionCannotSubmitWhileFriendsAreLoadingOrUnavailable() {
        var status by androidx.compose.runtime.mutableStateOf<FriendsStatus>(FriendsStatus.Loading)
        composeTestRule.setContent {
            KccTheme {
                CreateConvoyScreen(status, CreateConvoyState.Idle, setOf("u2"), {}, null, {})
            }
        }
        composeTestRule.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).assertIsNotEnabled()
        composeTestRule.runOnIdle { status = FriendsStatus.Error(com.kungsbackacarcommunity.app.friends.FriendActionError.Network) }
        composeTestRule.onNodeWithTag(CONVOY_CREATE_SUBMIT_TAG).assertIsNotEnabled()
    }

}
