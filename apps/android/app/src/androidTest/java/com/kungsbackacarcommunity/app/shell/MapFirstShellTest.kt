package com.kungsbackacarcommunity.app.shell

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.AuthenticatedApp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.chatchannels.CHAT_HUB_TEST_TAG
import com.kungsbackacarcommunity.app.config.FeatureFlags
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.welcome.WelcomeStore
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * The device's status-bar height in px, read from the platform resource. Used to
     * assert the chat-hub card clears system UI at whatever window size the test
     * runs at (portrait, landscape or a resized/short window).
     */
    private fun statusBarHeightPx(): Int {
        val res = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }

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

    private fun setShell(mapSurface: MapSurface? = null) {
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
                    memberProfileRepository = null,
                    dmRepository = null,
                    convoyRepository = null,
                    communityChatRepository = null,
                    convoyChatRepository = null,
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
                    // Defaults to rememberMapSurface() (the stub in CI) exactly
                    // like production; tests that need to assert the map wiring
                    // pass a stub they hold a reference to.
                    mapSurface = mapSurface ?: rememberMapSurface(),
                )
            }
        }
    }

    /**
     * The map home must NOT be disposed when the user visits another tab.
     *
     * Leaving it used to unmount the map, which on the real surface tears the
     * Mapbox MapView down and rebuilds it (style reload and all) on the way
     * back — the window has nothing to show for those frames, which is the
     * blank blink this guards against. Asserted through the stub's
     * composition counter: one entry for the whole round-trip, not one per
     * visit to the Map tab.
     */
    @Test
    fun switchingTabs_keepsTheMapComposed() {
        val surface = StubMapSurface()
        setShell(mapSurface = surface)
        composeTestRule.runOnIdle { assertEquals(1, surface.contentCompositions) }

        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabGarage)).performClick()
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabMap)).performClick()

        // Still the SAME map: had it been disposed on the way to Garage, coming
        // back would have entered the composition a second time.
        composeTestRule.runOnIdle { assertEquals(1, surface.contentCompositions) }
    }

    /**
     * The flip side of keeping the map alive: a map nobody can see must not keep
     * pulsing its puck and drawing GPS fixes, so the shell stands it down while
     * another tab covers it and brings it back on return.
     */
    @Test
    fun coveringTheMap_deactivatesIt_andReturningReactivatesIt() {
        val surface = StubMapSurface()
        setShell(mapSurface = surface)
        composeTestRule.runOnIdle { assertTrue(surface.isActive) }

        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabSocial)).performClick()
        composeTestRule.runOnIdle { assertFalse(surface.isActive) }

        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabMap)).performClick()
        composeTestRule.runOnIdle { assertTrue(surface.isActive) }
    }

    /**
     * Back on another tab must NOT be intercepted by the covered map.
     *
     * The map home stays composed while another tab is shown, which also keeps
     * its `BackHandler(enabled = searchExpanded)` registered with the activity's
     * dispatcher — visibility has no bearing on that. So a search bar left
     * expanded on the Map tab would go on eating Back presses from Social, and
     * because MapHome's handler is added AFTER the shell's it wins: Back would
     * appear to do nothing (it collapses a search bar nobody can see) instead of
     * returning to the Map tab.
     */
    @Test
    fun backOnAnotherTab_isNotSwallowedByTheCoveredMapSearch() {
        setShell()
        // Expand the map's search bar, then leave the Map tab with it expanded.
        composeTestRule.onNodeWithTag(MAP_HOME_SEARCH_TAG).performClick()
        composeTestRule.onNodeWithText(str(R.string.shell_searchHint)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabSocial)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        // Back belongs to the visible tab: it returns to the Map tab
        // (ShellNavigation.onBack), rather than being swallowed by the map.
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
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
        // member), the popup shows the Start control — NOT the membership teaser,
        // which is reserved for VIEWING others.
        composeTestRule
            .onNodeWithText(str(R.string.liveLocation_start))
            .assertIsDisplayed()
        // The 1h/2h/4h duration picker has MOVED off this broadcast control to
        // the single-session start flow, so it is no longer shown in the popup.
        composeTestRule
            .onNodeWithText(str(R.string.liveLocation_durationLabel))
            .assertDoesNotExist()
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
    fun chatBubble_opensAndDismissesChatHub() {
        setShell()
        // The floating chat bubble is present (unread count is 0 → "Chat").
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_chat)).assertExists()
        // Tapping it opens the chat hub as a TRANSPARENT popup over the
        // map (the map stays composed behind it, not a full opaque route).
        composeTestRule.onNodeWithTag(MAP_HOME_CHAT_TAG).performClick()
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertIsDisplayed()
        // The map stays visible behind the transparent popup.
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
        // The Community / Convoys / Friends / Notifications tabs are shown.
        composeTestRule.onNodeWithText(str(R.string.chatHub_tabCommunity)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.chatHub_tabConvoys)).assertIsDisplayed()
        // Closing returns to the map (the hub popup is gone).
        composeTestRule.onNodeWithContentDescription(str(R.string.chatHub_close)).performClick()
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
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

    /**
     * Regression: the chat-hub popup card must be strictly SHORTER than the window,
     * leaving a real, visible, tappable strip of map above it — and a tap in that
     * strip must dismiss the hub. A full-height card (e.g. `fillMaxHeight()`, whose
     * preceding padding lands inside its own footprint) would leave no genuine
     * "outside" and make the dismiss layer unreachable.
     */
    @Test
    fun chatHubPopup_leavesUncoveredMapStripAboveCard_andTapThereDismisses() {
        setShell()
        composeTestRule.onNodeWithTag(MAP_HOME_CHAT_TAG).performClick()
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertIsDisplayed()

        // The card is bottom-anchored, so an uncovered strip exists iff its top edge
        // sits strictly below the window top. A full-height card reports top=0 and
        // fails HERE, which is what gives this test its teeth. 16.dp is a floor for
        // "actually visible + tappable", not a mirror of the layout's tuning knob —
        // the production fraction stays private and is deliberately not asserted on.
        val cardTop =
            composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).getUnclippedBoundsInRoot().top
        assertTrue(
            "chat-hub card top was $cardTop — expected a strip of map above the card",
            cardTop > 16.dp,
        )

        // ...and that strip must clear system UI, not just be non-zero: the card's
        // top may never sit under the status bar, or the hub's own top bar renders
        // beneath it. This is what stops the height fraction from being taken
        // against the raw window — on a short window (landscape / split-screen) a
        // fraction of the WINDOW can be smaller than the status bar, while a
        // fraction of the SAFE AREA cannot.
        val statusBarTop = with(composeTestRule.density) { statusBarHeightPx().toDp() }
        assertTrue(
            "chat-hub card top was $cardTop — expected it to clear the " +
                "$statusBarTop status bar so the hub's top bar isn't under system UI",
            cardTop >= statusBarTop,
        )

        // That strip is the dismiss affordance: tap inside it and the hub closes
        // while the map stays. The tap point is DERIVED from the measured card top
        // and the device density rather than a fixed pixel offset — touch input is
        // in px while the bounds above are in dp, so a magic px offset could land
        // outside the strip (above the window) on a low-density device. Midway
        // between the window top and the card's top edge is provably inside the
        // strip at any density; y is negative because it is node-relative and the
        // strip sits above the card.
        val stripMidYPx = with(composeTestRule.density) { cardTop.toPx() } / 2f
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).performTouchInput {
            click(Offset(width / 2f, -stripMidYPx))
        }
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
    }

    /**
     * Regression: a tap INSIDE the chat-hub card must never dismiss the hub, even on
     * a spot no child consumes. The dismiss layer behind the card is `fillMaxSize()`,
     * so this pins that the card itself swallows the touch rather than letting it
     * fall through to that layer. Every repository is null in this configuration, so
     * the card's body is the non-interactive `TabPlaceholder` (a plain Box + Text) —
     * exactly the "empty area / placeholder" case.
     */
    @Test
    fun chatHubPopup_tapInsideCardOnNonInteractiveArea_doesNotDismiss() {
        setShell()
        composeTestRule.onNodeWithTag(MAP_HOME_CHAT_TAG).performClick()
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertIsDisplayed()
        // Sanity: the body really is the non-interactive placeholder, not a live
        // channel with its own click handlers.
        composeTestRule.onNodeWithText(str(R.string.chatHub_unavailable)).assertIsDisplayed()

        // Tap well inside the card, in the placeholder body: coordinates are derived
        // from the card's own measured size (node-relative px), not magic pixels.
        // 75% down clears the top bar and the tab row, so nothing interactive is
        // under the finger — if the card failed to consume the touch it would reach
        // the dismiss layer behind it and close the hub.
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).performTouchInput {
            click(Offset(width / 2f, height * 0.75f))
        }
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.chatHub_unavailable)).assertIsDisplayed()
    }

    /**
     * Regression: the chat hub must not re-open by itself. `chatHubOpen` is
     * rememberSaveable but the hub only renders while the map shell is the active
     * branch, so losing that gate has to CLEAR the flag — otherwise the hub
     * silently stays "open" and pops up again on returning to the map.
     *
     * The hub is dismissed via the map strip BEFORE switching tabs, because that is
     * the only way a user can leave the Map tab: the hub's card covers the bottom
     * nav bar, so a tap on a tab lands on the card and is inert. That was equally
     * true of the previous Popup presentation — that popup window was touch-modal
     * (its flags carried no FLAG_NOT_TOUCH_MODAL), so it swallowed the tap the same
     * way. Only a test-injected click reached the tab, because Compose dispatches it
     * straight to the node's own window and so bypassed the popup entirely; now that
     * the hub composes in the host window (see ChatHubInsetsTest for why it must),
     * the injected click meets the same card a finger does. The old sequence
     * therefore asserted a path no user could take.
     */
    @Test
    fun chatHub_doesNotReappearAfterLeavingAndReturningToMapTab() {
        setShell()
        // Open the hub over the map.
        composeTestRule.onNodeWithTag(MAP_HOME_CHAT_TAG).performClick()
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertIsDisplayed()

        // A tab tap while the hub is open is inert — the card is in the way.
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabHistory)).performClick()
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()

        // Dismiss the hub the way a user must: tap the uncovered strip of map above
        // the card (derived from the measured card top — see the strip test).
        val cardTop =
            composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).getUnclippedBoundsInRoot().top
        val stripMidYPx = with(composeTestRule.density) { cardTop.toPx() } / 2f
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).performTouchInput {
            click(Offset(width / 2f, -stripMidYPx))
        }
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertDoesNotExist()

        // Now the tabs are reachable again: leaving the Map tab takes the map home
        // away rather than floating it under another tab.
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabHistory)).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertDoesNotExist()

        // Returning to the Map tab restores the map WITHOUT re-opening the hub —
        // the user gets the map, not a chat hub they never re-opened.
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabMap)).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertDoesNotExist()
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
