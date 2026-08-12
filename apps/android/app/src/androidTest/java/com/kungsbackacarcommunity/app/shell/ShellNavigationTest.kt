package com.kungsbackacarcommunity.app.shell

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.profile.ProfileRepository
import com.kungsbackacarcommunity.app.profile.ProfileState
import com.kungsbackacarcommunity.app.profile.SocialHandles
import com.kungsbackacarcommunity.app.profile.UserProfile
import com.kungsbackacarcommunity.app.update.AppUpdateAvailability
import com.kungsbackacarcommunity.app.update.AppUpdateSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Shell-level navigation and the startup update gate: the account-menu popup and
 * its entries, the five-tab bottom bar, the Create chooser, and the forced- vs
 * clear-update gating of backend wiring. Part of the map-first shell suite split
 * out of `MapFirstShellTest` (see [MapFirstShellTestSupport]).
 */
@RunWith(AndroidJUnit4::class)
class ShellNavigationTest : MapFirstShellTestSupport() {

    // ── Startup update gate ─────────────────────────────────────────────────

    /**
     * A [ProfileRepository] that records whether its snapshot listener was ever
     * asked for. `observeProfile` is the FIRST backend-dependent thing the shell
     * does, so "was it called" is the proxy for "did any shell wiring start".
     */
    private class RecordingProfileRepository : ProfileRepository {
        var observeCalls = 0
            private set

        override fun observeProfile(uid: String): Flow<ProfileState> {
            observeCalls += 1
            return flowOf(
                ProfileState.Loaded(
                    UserProfile(
                        displayName = "Tester",
                        bio = null,
                        onboardingComplete = true,
                        social = SocialHandles.EMPTY,
                    ),
                ),
            )
        }

        override suspend fun updateProfile(
            uid: String,
            displayName: String,
            bio: String,
            social: SocialHandles,
        ) = Unit

        override suspend fun updateAvatarPath(uid: String, avatarPath: String) = Unit
    }

    /** A Play source that always reports a mandatory (blocking) update. */
    private class ForcingUpdateSource : AppUpdateSource {
        override suspend fun fetch(): AppUpdateAvailability =
            AppUpdateAvailability(
                availableVersionCode = 999,
                isFlexibleAllowed = true,
                isImmediateAllowed = true,
                priority = AppUpdateAvailability.MAX_PRIORITY,
                isImmediateInProgress = false,
                isDownloaded = false,
            )

        override fun startFlow(
            launcher: ActivityResultLauncher<IntentSenderRequest>,
            immediate: Boolean,
        ): Boolean = true

        override fun onDownloadComplete(onDownloaded: () -> Unit): () -> Unit = {}

        override fun completeUpdate(): Boolean = false
    }

    /**
     * The regression guard for the actual first-launch crash: when a mandatory
     * update is live, the shell's backend wiring must NOT start. The profile
     * snapshot listener — the first and most load-bearing of that wiring, since
     * it feeds the destination router — is never even constructed, and the
     * blocking update screen is what the member sees instead of the shell.
     */
    @Test
    fun forcedUpdate_gatesShell_withoutStartingBackendWiring() {
        val profileRepo = RecordingProfileRepository()
        setShell(profileRepository = profileRepo, appUpdateSource = ForcingUpdateSource())
        composeTestRule.waitForIdle()

        // The blocking "update required" screen is shown...
        composeTestRule.onNodeWithText(str(R.string.appUpdate_requiredTitle)).assertIsDisplayed()
        // ...the map-first shell never composed...
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertDoesNotExist()
        // ...and, the point of the whole fix, no backend wiring started: the
        // profile listener was not even asked for, so nothing it could throw
        // inside ever ran.
        composeTestRule.runOnIdle {
            assertEquals(
                "the profile listener must not start while a forced update gates the shell",
                0,
                profileRepo.observeCalls,
            )
        }
    }

    /**
     * The flip side, so the gate is not just "always block": with no update to
     * force (a null source resolves CLEAR at once), the shell composes as normal
     * and DOES start its backend wiring — the deferral is conditional, not a
     * permanent lock-out.
     */
    @Test
    fun clearUpdate_composesShell_andStartsBackendWiring() {
        val profileRepo = RecordingProfileRepository()
        setShell(profileRepository = profileRepo, appUpdateSource = null)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(str(R.string.appUpdate_requiredTitle)).assertDoesNotExist()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
        composeTestRule.runOnIdle {
            assertTrue(
                "the profile listener must start once the gate is clear",
                profileRepo.observeCalls >= 1,
            )
        }
    }

    @Test
    fun profileButton_opensAccountMenuPopupOverMap() {
        setShell()
        // The top-right profile/account button is present.
        composeTestRule.onNodeWithTag(MAP_HOME_MORE_TAG).assertExists()
        // Tapping it opens the account menu as a popup (not a full-screen hub).
        composeTestRule.onNodeWithTag(MAP_HOME_MORE_TAG).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_MORE_POPUP_TAG).assertIsDisplayed()
        // The always-available entries are shown (every repo is null in this
        // no-Firebase config, so the gated entries are omitted).
        composeTestRule.onNodeWithText(str(R.string.shell_moreSettings)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.shell_moreSignOut)).assertIsDisplayed()
        // The whole point: the map stays visible behind the popup (transparent
        // Popup, no dimming scrim, no navigation to a separate page).
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
    }

    @Test
    fun profileMenuEntry_navigatesAndDismissesPopup() {
        setShell()
        composeTestRule.onNodeWithTag(MAP_HOME_MORE_TAG).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_MORE_POPUP_TAG).assertIsDisplayed()
        // Selecting an entry runs its navigation action AND closes the popup:
        // Settings opens the full-screen Settings route (replacing the map home)
        // and the menu popup is gone.
        composeTestRule.onNodeWithText(str(R.string.shell_moreSettings)).performClick()
        composeTestRule.onNodeWithText(str(R.string.settingsMenu_title)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(MAP_HOME_MORE_POPUP_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun bottomNav_switchesTabsAndBack() {
        setShell()
        // All five tabs are present. Icon-only bottom nav: labels are null and the
        // tab name lives on the icon's contentDescription, so select by that.
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabHistory)).assertExists()
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabCreate)).assertExists()
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabSocial)).assertExists()
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabGarage)).assertExists()

        // Switching to History leaves the map home.
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabHistory)).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertDoesNotExist()

        // Returning to the Map tab restores the map home.
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabMap)).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
    }

    @Test
    fun createTab_raisesChooser_thenDismisses() {
        setShell()
        // The Create tab is an action, not a destination: tapping it switches to
        // the Map and raises the transparent single/convoy chooser. Icon-only
        // bottom nav (labels are null), so the tab name lives on the
        // contentDescription.
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabCreate)).performClick()
        // The chooser appears — asserted via its two option titles.
        composeTestRule
            .onNodeWithText(str(R.string.shell_createChooserSingle))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(str(R.string.shell_createChooserConvoy))
            .assertIsDisplayed()
        // Cancel dismisses the chooser and leaves the user on the map home.
        composeTestRule
            .onNodeWithText(str(R.string.shell_liveSharePromptCancel))
            .performClick()
        composeTestRule
            .onNodeWithText(str(R.string.shell_createChooserSingle))
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
    }
}
