package com.kungsbackacarcommunity.app.shell

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.AuthenticatedApp
import com.kungsbackacarcommunity.app.config.FeatureFlags
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.profile.ProfileRepository
import com.kungsbackacarcommunity.app.update.AppUpdateSource
import com.kungsbackacarcommunity.app.welcome.WelcomeStore
import org.junit.Before
import org.junit.Rule

/**
 * Shared scaffolding for the map-first shell Compose UI tests.
 *
 * The suite that used to be one ~1450-line `MapFirstShellTest` was split into
 * several smaller cohesive classes (see #759 follow-up) because every `@Test`
 * launches a fresh [ComponentActivity] and all androidTest classes share ONE
 * emulator GL context; a single class with ~45 activity launches accumulated
 * enough MapView SurfaceViews to exhaust the emulator's ColorBuffers ("Failed
 * to find ColorBuffer") once the Robolectric migration shifted test ordering.
 * Smaller classes cap per-class GL churn so no single class tips over the limit.
 *
 * This base holds only the members EVERY split class shares — its own compose
 * rule (one instance per subclass, so each split class still gets a fresh rule),
 * the welcome-flow bypass, and the shell/host builders. Area-specific helpers
 * (place-menu, control-order, panel-geometry, update-gate fakes, …) live in the
 * class that uses them so nothing is over-shared and nothing is duplicated.
 *
 * Abstract + un-`@RunWith`-annotated on purpose: the instrumentation runner
 * skips it (no `@Test` methods, cannot be instantiated) so it is never run as a
 * test class of its own.
 */
abstract class MapFirstShellTestSupport {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * The device's status-bar height in px, read from the platform resource. Used to
     * assert bottom-anchored cards clear system UI at whatever window size the test
     * runs at (portrait, landscape or a resized/short window).
     */
    protected fun statusBarHeightPx(): Int {
        val res = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }

    protected fun str(id: Int) =
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

    protected fun setShell(
        mapSurface: MapSurface? = null,
        profileRepository: ProfileRepository? = null,
        // Null by default so the startup update gate resolves CLEAR immediately
        // (no Play, no CHECKING window) — these shell tests are about the shell,
        // not the gate, and must not depend on a real Play reading in the
        // emulator. The forced-update test below injects a source that gates.
        appUpdateSource: AppUpdateSource? = null,
    ) {
        composeTestRule.setContent {
            KccTheme {
                AuthenticatedApp(
                    uid = TEST_UID,
                    authDisplayName = null,
                    profileRepository = profileRepository,
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
                    badgeProgressRepository = null,
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
                    appUpdateSource = appUpdateSource,
                )
            }
        }
    }

    /** Opens a tab by its bottom-bar content description. */
    protected fun openTab(tabTitleRes: Int) {
        composeTestRule.onNodeWithContentDescription(str(tabTitleRes)).performClick()
        composeTestRule.waitForIdle()
    }

    protected companion object {
        const val TEST_UID = "u1"
    }
}
