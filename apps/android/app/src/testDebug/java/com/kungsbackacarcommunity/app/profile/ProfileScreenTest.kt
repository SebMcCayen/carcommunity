package com.kungsbackacarcommunity.app.profile

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val profile = UserProfile(displayName = "Sebbe", bio = "Volvo fan", onboardingComplete = true)

    @Test
    fun viewModeShowsProfileAndEditEntersEditMode() {
        composeTestRule.setContent {
            KccTheme {
                ProfileScreen(
                    profile = profile,
                    saveStatus = ProfileEditStatus.Idle,
                    onSave = { _, _, _ -> },
                    onBack = {},
                    onSignOut = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Sebbe").assertIsDisplayed()
        composeTestRule.onNodeWithText("Volvo fan").assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.profile_editButton)).performScrollTo().performClick()
        // Edit mode: the display-name field label appears.
        composeTestRule.onNodeWithText(str(R.string.profile_displayNameLabel)).assertIsDisplayed()
    }

    @Test
    fun savingSeededValidProfileInvokesOnSave() {
        var saved: Pair<String, String>? = null
        composeTestRule.setContent {
            KccTheme {
                ProfileScreen(
                    profile = profile,
                    saveStatus = ProfileEditStatus.Idle,
                    onSave = { name, bio, _ -> saved = name to bio },
                    onBack = {},
                    onSignOut = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.profile_editButton)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(str(R.string.profile_saveButton)).performScrollTo().performClick()
        assertEquals("Sebbe" to "Volvo fan", saved)
    }

    /**
     * The points card is the app's ONLY way into the full Kronpoäng ledger since
     * the profile menu's "Points" row was removed, so its tap-through is pinned:
     * the affordance is visible, it is announced as a BUTTON (not inert text),
     * and tapping it navigates.
     */
    @Test
    fun pointsCardOpensTheLedgerAndIsAnnouncedAsAButton() {
        var opened = 0
        composeTestRule.setContent {
            KccTheme {
                ProfileScreen(
                    profile = profile,
                    saveStatus = ProfileEditStatus.Idle,
                    onSave = { _, _, _ -> },
                    onBack = {},
                    onSignOut = {},
                    pointsBalance = 120L,
                    onOpenPoints = { opened++ },
                )
            }
        }
        composeTestRule
            .onNodeWithText(str(R.string.profile_pointsViewAll))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(str(R.string.profile_pointsTitle))
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        composeTestRule.onNodeWithText(str(R.string.profile_pointsTitle)).performClick()
        assertEquals(1, opened)
    }

    /**
     * The card must be operable by an ACCESSIBILITY SERVICE, not merely by a
     * finger. TalkBack does not synthesise a touch: it invokes
     * [SemanticsActions.OnClick] on the merged node, so a node that carries the
     * OnClick key with a null action announces as clickable, says "double tap
     * to activate" — and then does nothing. A plain `performClick()` cannot see
     * that: it drives the pointer input of the underlying `clickable`, which
     * works regardless. Hence this test performs the SEMANTICS action instead
     * and asserts the navigation callback actually fired.
     *
     * It also pins the custom label, which is the only reason the card writes
     * its own `onClick(label = ...)` at all: [androidx.compose.material3.Card]
     * with an `onClick` has no `onClickLabel` parameter to attach it to.
     */
    @Test
    fun pointsCardIsOperableThroughTheAccessibilityClickAction() {
        var opened = 0
        composeTestRule.setContent {
            KccTheme {
                ProfileScreen(
                    profile = profile,
                    saveStatus = ProfileEditStatus.Idle,
                    onSave = { _, _, _ -> },
                    onBack = {},
                    onSignOut = {},
                    pointsBalance = 120L,
                    onOpenPoints = { opened++ },
                )
            }
        }
        val card = composeTestRule.onNodeWithText(str(R.string.profile_pointsTitle))
        card.performScrollTo().assertHasClickAction()
        // The label survives on the merged node...
        card.assert(
            SemanticsMatcher("OnClick action carries the custom label") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == str(R.string.profile_pointsViewAll)
            },
        )
        // ...and so does a real, invocable action. performSemanticsAction is a
        // no-op when the action is null, so the callback assertion below — not
        // the call itself — is what fails if the action is missing.
        card.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opened)
    }

    /**
     * With no points repository wired ([onOpenPoints] null) the card must be a
     * plain summary: no "view all" affordance leading nowhere.
     */
    @Test
    fun pointsCardHasNoLedgerAffordanceWhenNotWired() {
        composeTestRule.setContent {
            KccTheme {
                ProfileScreen(
                    profile = profile,
                    saveStatus = ProfileEditStatus.Idle,
                    onSave = { _, _, _ -> },
                    onBack = {},
                    onSignOut = {},
                    pointsBalance = 120L,
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.profile_pointsViewAll)).assertDoesNotExist()
    }
}
