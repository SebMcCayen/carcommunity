package com.kungsbackacarcommunity.app.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.AuthenticatedApp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.config.FeatureFlags
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.welcome.WelcomeStore
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the map-first, 5-tab shell. Rendered in the no-Firebase
 * (Unavailable) configuration — every repository/coordinator is null — which
 * still reaches the Main shell (see authedDestination). CI-run in the
 * instrumented-tests emulator job.
 */
@RunWith(AndroidJUnit4::class)
class MapFirstShellTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    /**
     * The one-time first-login welcome flow gates the Main shell on the first
     * reach for a uid. These tests target the shell itself, so mark the flow
     * already-seen for this uid (device-local WelcomeStore) — deterministic
     * regardless of prior device state — so [setShell] renders the shell directly.
     */
    @Before
    fun markWelcomeSeen() {
        WelcomeStore(InstrumentationRegistry.getInstrumentation().targetContext).markSeen(TEST_UID)
    }

    private fun setShell() {
        composeTestRule.setContent {
            KccTheme {
                AuthenticatedApp(
                    uid = TEST_UID,
                    authDisplayName = null,
                    profileRepository = null,
                    onboardingCoordinator = null,
                    profileEditCoordinator = null,
                    liveLocationRepository = null,
                    liveLocationCoordinator = null,
                    eventsRepository = null,
                    rsvpCoordinator = null,
                    chatRepository = null,
                    chatCoordinator = null,
                    groupDriveRepository = null,
                    groupDriveCoordinator = null,
                    crownHuntRepository = null,
                    crownHuntCoordinator = null,
                    partnersRepository = null,
                    offerCodeCoordinator = null,
                    notificationsRepository = null,
                    notificationsCoordinator = null,
                    notificationSettingsRepository = null,
                    notificationSettingsCoordinator = null,
                    garageRepository = null,
                    garageCoordinator = null,
                    mediaUploader = null,
                    badgesRepository = null,
                    blockingRepository = null,
                    friendsRepository = null,
                    dmRepository = null,
                    drivesRepository = null,
                    pointsRepository = null,
                    partnerApplicationCoordinator = null,
                    billboardsRepository = null,
                    accountDeletionCoordinator = null,
                    partnerStatsRepository = null,
                    partnerStatsCoordinator = null,
                    feedbackCoordinator = null,
                    billingRepository = null,
                    subscriptionVerifier = null,
                    pushRegistrationCoordinator = null,
                    loginRecordCoordinator = null,
                    flags = FeatureFlags.DEFAULTS,
                    onSignOut = {},
                )
            }
        }
    }

    private companion object {
        const val TEST_UID = "u1"
    }

    @Test
    fun mapHome_showsSearchBarAndFloatingControls() {
        setShell()
        // Map-first home renders (MapSurface stub behind the shell).
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
        // The search bar starts COLLAPSED to a round icon button (upper-left), so
        // the "Where to?" hint is hidden until the button is tapped.
        composeTestRule.onNodeWithTag(MAP_HOME_SEARCH_TAG).assertExists()
        composeTestRule.onNodeWithText(str(R.string.shell_searchHint)).assertDoesNotExist()
        // Floating controls: compass (top) + broadcast toggle off + layers +
        // recenter.
        composeTestRule.onNodeWithTag(MAP_HOME_COMPASS_TAG).assertExists()
        // Exercise the compass's tap action (reset-north): it has no observable
        // result in the no-Firebase stub (StubMapSurface.resetNorth is a no-op),
        // so this just proves the control is clickable and its wiring doesn't
        // crash the stubbed shell — matching how the other controls are driven.
        composeTestRule.onNodeWithTag(MAP_HOME_COMPASS_TAG).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_COMPASS_TAG).assertExists()
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_liveShareOff)).assertExists()
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_layersButton)).assertExists()
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_recenter)).assertExists()
    }

    @Test
    fun searchButton_expandsToFullBar() {
        setShell()
        // Collapsed by default: the round search button is shown, the full bar is
        // not.
        composeTestRule.onNodeWithTag(MAP_HOME_SEARCH_TAG).assertExists()
        composeTestRule.onNodeWithText(str(R.string.shell_searchHint)).assertDoesNotExist()
        // Tapping the round button expands the full-width "Where to?" bar.
        composeTestRule.onNodeWithTag(MAP_HOME_SEARCH_TAG).performClick()
        composeTestRule.onNodeWithText(str(R.string.shell_searchHint)).assertIsDisplayed()
    }

    @Test
    fun layersControl_opensAndDismissesLayersPopup() {
        setShell()
        // Tapping the layers control opens the transparent map-layers popup.
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_TAG).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_POPUP_TAG).assertIsDisplayed()
        // It exposes the incidents ("Traffic alerts") / traffic / night-mode / 3D
        // toggles.
        composeTestRule.onNodeWithText(str(R.string.shell_layersIncidents)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.shell_layersTraffic)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.shell_layersNightMode)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.shell_layers3d)).assertIsDisplayed()
        // Closing dismisses the popup.
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_layersClose)).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_POPUP_TAG).assertDoesNotExist()
    }

    @Test
    fun layersPopup_incidentsToggle_gatesTrafikverketAttribution() {
        setShell()
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_TAG).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_POPUP_TAG).assertIsDisplayed()
        // The incidents layer defaults ON, so the "Källa: Trafikverket" attribution
        // for the Trafikverket-sourced incidents is shown alongside the toggle row.
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_INCIDENTS_TAG).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(str(R.string.incidents_sourceTrafikverket))
            .assertIsDisplayed()
        // Turning the incidents layer off removes the attribution (no Trafikverket
        // data is on screen to credit) — the conditional wiring this test guards.
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_INCIDENTS_TAG).performClick()
        composeTestRule
            .onNodeWithText(str(R.string.incidents_sourceTrafikverket))
            .assertDoesNotExist()
    }

    @Test
    fun liveControl_opensAndDismissesLivePopup() {
        setShell()
        // The broadcast control is present. Select by its stable test tag rather
        // than its a11y label, which is free to change as the UX evolves.
        composeTestRule.onNodeWithTag(MAP_HOME_LIVE_TAG).assertExists()
        // Tapping it opens the transparent live-location popup over the map
        // (rather than toggling sharing directly).
        composeTestRule.onNodeWithTag(MAP_HOME_LIVE_TAG).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_LIVE_POPUP_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.shell_liveTitle)).assertIsDisplayed()
        // Sharing your OWN location is FREE (LIVE_LOCATION flag on in DEFAULTS,
        // not member-gated). Even in the no-Firebase config (not an active
        // member), the popup shows the start controls — duration picker + Start —
        // NOT the membership teaser, which is reserved for VIEWING others.
        composeTestRule
            .onNodeWithText(str(R.string.liveLocation_durationLabel))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(str(R.string.liveLocation_start))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(str(R.string.liveLocation_memberRequiredToShare))
            .assertDoesNotExist()
        // The map stays visible behind the transparent popup (no navigation).
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
        // Closing dismisses the popup.
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_liveClose)).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_LIVE_POPUP_TAG).assertDoesNotExist()
    }

    @Test
    fun chatBubble_opensAndDismissesPopup() {
        setShell()
        // The floating chat bubble is present (unread count is 0 → "Chat").
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_chat)).assertExists()
        // Tapping it opens the community-chat popup.
        composeTestRule.onNodeWithTag(MAP_HOME_CHAT_TAG).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_CHAT_POPUP_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.shell_chatTitle)).assertIsDisplayed()
        // Closing minimizes back to the bubble.
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_chatClose)).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_CHAT_POPUP_TAG).assertDoesNotExist()
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
    fun createTab_raisesLiveSharePrompt_thenDismisses() {
        setShell()
        // The Create tab is an action, not a destination: tapping it switches to
        // the Map and raises the transparent live-share prompt. Icon-only bottom
        // nav (labels are null), so the tab name lives on the contentDescription.
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabCreate)).performClick()
        // The prompt dialog appears — asserted via its unique body text.
        composeTestRule
            .onNodeWithText(str(R.string.shell_liveSharePromptBody))
            .assertIsDisplayed()
        // Cancel dismisses the prompt and leaves the user on the map home.
        composeTestRule
            .onNodeWithText(str(R.string.shell_liveSharePromptCancel))
            .performClick()
        composeTestRule
            .onNodeWithText(str(R.string.shell_liveSharePromptBody))
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
    }
}
