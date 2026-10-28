package com.kungsbackacarcommunity.app.navigation

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.kungsbackacarcommunity.app.AppRoot
import com.kungsbackacarcommunity.app.AuthenticatedApp
import com.kungsbackacarcommunity.app.MainActivity
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.auth.AuthState
import com.kungsbackacarcommunity.app.coachmark.CoachMarkStore
import com.kungsbackacarcommunity.app.config.FeatureFlags
import com.kungsbackacarcommunity.app.shell.StubMapSurface
import com.kungsbackacarcommunity.app.welcome.WelcomeStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity/Intent delivery with local auth/search seams; never initializes cloud data.
 * Run with -e verifyDriverHandoff true after installing the current Driver fixture
 * APK to exercise its actual location button, metadata lookup and native channel.
 * The default CI path tests the same receiving URI via Android ACTION_VIEW.
 */
@RunWith(AndroidJUnit4::class)
class GeoQueryHandoffTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val auth = mutableStateOf<AuthState>(AuthState.SignedIn(TEST_UID, null))
    private val surface = StubMapSurface()
    private val queries = Collections.synchronizedList(mutableListOf<String>())
    private var failure = false
    private var results: List<PlaceSuggestion> = listOf(
        PlaceSuggestion("centre", "Torget i centrum", "Kungsbacka", LatLng(12.075, 57.487)),
        PlaceSuggestion("station", "Torget vid stationen", "Kungsbacka", LatLng(12.080, 57.490)),
    )
    private val client = object : MapboxSearchClient {
        override suspend fun geocode(query: String, proximity: LatLng?): List<PlaceSuggestion> {
            queries.add(query)
            if (failure) throw IOException("Fixture offline")
            return results
        }
        override suspend fun route(origin: LatLng, destination: LatLng): RouteSummary? = null
    }
    private val patched = mutableSetOf<Activity>()
    private val activities = Collections.synchronizedList(mutableListOf<MainActivity>())
    private var dropPendingAfterSave = false
    private lateinit var lifecycle: ActivityLifecycleCallback

    @Before fun prepare() {
        MapLinkNavigator.clear()
        WelcomeStore(context).markSeen(TEST_UID)
        CoachMarkStore(context).markSeen(TEST_UID)
        // Replace repositories/auth only in instrumentation. Every receiving
        // MainActivity still executes its REAL onCreate/onNewIntent parsing.
        // A second task must not let the background shell steal its pending URI.
        lifecycle = ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity && stage == Stage.RESUMED && patched.add(activity)) {
                activities.add(activity)
                activity.setContent {
                    AppRoot(
                        authState = auth.value,
                        onSignInClick = { auth.value = AuthState.SignedIn(TEST_UID, null) },
                        signedInContent = { _, _ -> TestShell() },
                        darkTheme = false,
                    )
                }
            }
            if (activity is MainActivity && stage == Stage.DESTROYED && dropPendingAfterSave) {
                // Simulate the process-scoped queue disappearing AFTER instance
                // state has been saved, before the new Activity restores it.
                MapLinkNavigator.clear()
                dropPendingAfterSave = false
            }
        }
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle)
    }

    @After fun cleanup() {
        ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)
        instrumentation.runOnMainSync { activities.forEach { if (!it.isFinishing) it.finish() } }
        instrumentation.waitForIdleSync()
        MapLinkNavigator.clear()
    }

    private fun str(id: Int) = context.getString(id)
    private fun link(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        .setPackage(context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    private fun launch(uri: String) {
        launchActivity(link(uri))
    }
    private fun launchActivity(intent: Intent) {
        val previousCount = activities.size
        context.startActivity(intent)
        compose.waitUntil(10_000) { activities.size > previousCount }
        compose.waitForIdle()
    }
    private fun recreate() {
        val previous = activities.last()
        instrumentation.runOnMainSync { previous.recreate() }
        compose.waitUntil(10_000) { activities.lastOrNull() !== previous }
        compose.waitForIdle()
    }
    private fun assertQuery(text: String) {
        compose.waitUntil(10_000) { queries.contains(text) }
        compose.onNodeWithTag(NAV_SEARCH_INPUT_TEST_TAG).assertTextEquals(text)
    }
    private fun evidence(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureGeoEvidence") != "true") return
        val file = File(context.getExternalFilesDir(null), "geo-query/$name.png")
        file.parentFile?.mkdirs()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun capabilityFlag_isAdvertisedOnTheApplication() {
        val info = context.packageManager.getApplicationInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_META_DATA,
        )
        assertTrue(info.metaData?.getBoolean("com.kungsbackacarcommunity.app.SUPPORTS_GEO_QUERY", false) == true)
    }

    @Test fun coldSwedishQuery_waitsThroughSignIn_andRequiresChoosingAmongMatches() {
        auth.value = AuthState.SignedOut
        val text = "Åsa torg & café"
        launch("geo:0,0?q=" + Uri.encode(text))
        compose.onNodeWithText(str(R.string.auth_googleLoginButton)).assertIsDisplayed()
        assertEquals(GeoUriTarget.Query(text), MapLinkNavigator.pending.value)
        assertTrue(queries.isEmpty())
        compose.onNodeWithText(str(R.string.auth_googleLoginButton)).performClick()
        assertQuery(text)
        compose.onNodeWithText(results[0].name).assertIsDisplayed()
        compose.onNodeWithText(results[1].name).assertIsDisplayed()
        assertNull(surface.routeOverlay.value)
        evidence("swedish-query-choices")
        compose.onNodeWithText(results[1].name).performClick()
        compose.waitUntil(5_000) { surface.routeOverlay.value != null }
        assertEquals(results[1].point.longitude, surface.routeOverlay.value!!.destination.longitude, 0.0)
        assertEquals(results[1].point.latitude, surface.routeOverlay.value!!.destination.latitude, 0.0)
        assertNull(MapLinkNavigator.pending.value)
    }

    @Test fun warmLinks_replacePointPreview_andRepeatTheSameQuery() {
        val query = "Kungsbacka torg"
        launch("geo:0,0?q=" + Uri.encode(query))
        assertQuery(query)
        compose.onNodeWithText(results[0].name).performClick()
        compose.waitUntil(5_000) { surface.routeOverlay.value != null }
        val coordinate = "geo:0,0?q=" + Uri.encode("57.48,12.09(Torget & café)")
        context.startActivity(link(coordinate))
        compose.waitUntil(5_000) {
            surface.routeOverlay.value?.destination?.longitude == 12.09
        }
        assertEquals(57.48, surface.routeOverlay.value!!.destination.latitude, 0.0)
        val count = queries.count { it == query }
        context.startActivity(link("geo:0,0?q=" + Uri.encode(query)))
        compose.waitUntil(5_000) { queries.count { it == query } > count }
        assertQuery(query)
        compose.onNodeWithText(results[1].name).assertIsDisplayed()
        compose.onNodeWithTag(NAV_ROUTE_SHEET_TEST_TAG).assertDoesNotExist()
        assertNull(surface.routeOverlay.value)
    }

    @Test fun warmQuery_dismissesThePreviousSaveEditor_andEditsOnlyTheNewSelection() {
        launch("geo:0,0?q=Kungsbacka%20torg")
        assertQuery("Kungsbacka torg")
        compose.onNodeWithText(results[0].name).performClick()
        compose.onNodeWithTag(NAV_SAVE_TEST_TAG).performClick()
        compose.onNodeWithTag(NAV_SAVE_EDITOR_TEST_TAG).assertIsDisplayed()

        context.startActivity(link("geo:0,0?q=" + Uri.encode("Åsa torg")))
        assertQuery("Åsa torg")
        compose.onNodeWithTag(NAV_SAVE_EDITOR_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(NAV_ROUTE_SHEET_TEST_TAG).assertDoesNotExist()
        assertNull(surface.routeOverlay.value)

        compose.onNodeWithText(results[1].name).performClick()
        compose.onNodeWithTag(NAV_SAVE_TEST_TAG).performClick()
        compose.onNodeWithTag(NAV_SAVE_EDITOR_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(NAV_SAVE_NAME_TEST_TAG).assertTextContains(results[1].name)
    }

    @Test fun noMatches_keepsTheIncomingTextEditable() {
        results = emptyList()
        launch("geo:0,0?q=Finns%20inte")
        assertQuery("Finns inte")
        compose.onNodeWithText(str(R.string.addressSearch_noResults)).assertIsDisplayed()
        evidence("empty-results")
        assertNull(surface.routeOverlay.value)
        results = listOf(PlaceSuggestion("one", "En träff", null, LatLng(12.08, 57.48)))
        compose.onNodeWithTag(NAV_SEARCH_INPUT_TEST_TAG).performTextReplacement("Kungsbacka")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("En träff").fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(surface.routeOverlay.value) // A single match is not auto-selected either.
    }

    @Test fun networkFailure_keepsQuery_andRecoversOnEditing() {
        failure = true
        launch("geo:0,0?q=Kungsbacka%20torg")
        assertQuery("Kungsbacka torg")
        compose.onNodeWithText(str(R.string.addressSearch_searchError)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.addressSearch_noResults)).assertDoesNotExist()
        evidence("network-error")
        assertNull(surface.routeOverlay.value)
        failure = false
        compose.onNodeWithTag(NAV_SEARCH_INPUT_TEST_TAG).performTextReplacement("Åsa torg")
        assertQuery("Åsa torg")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(results[0].name).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(str(R.string.addressSearch_searchError)).assertDoesNotExist()
    }

    @Test fun pendingQuery_survivesRecreation_withoutReplayingAConsumedIntent() {
        auth.value = AuthState.SignedOut
        launch("geo:0,0?q=Kungsbacka%20torg")
        compose.onNodeWithText(str(R.string.auth_googleLoginButton)).assertIsDisplayed()
        // A notification/launcher can replace MainActivity.intent while the
        // map target still waits behind authentication. Restore the target,
        // not that unrelated Intent, after losing process-scoped state.
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        compose.waitUntil(5_000) { activities.last().intent.action != Intent.ACTION_VIEW }
        dropPendingAfterSave = true
        recreate()
        compose.onNodeWithText(str(R.string.auth_googleLoginButton)).assertIsDisplayed()
        assertEquals(GeoUriTarget.Query("Kungsbacka torg"), MapLinkNavigator.pending.value)
        compose.onNodeWithText(str(R.string.auth_googleLoginButton)).performClick()
        assertQuery("Kungsbacka torg")
        instrumentation.runOnMainSync { activities.last().onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag(NAV_SEARCH_TEST_TAG).assertDoesNotExist()
        recreate()
        compose.waitForIdle()
        assertNull(MapLinkNavigator.pending.value)
        compose.onNodeWithTag(NAV_SEARCH_TEST_TAG).assertDoesNotExist()
    }

    @Test fun pendingCoordinate_survivesRecreation_afterAnUnrelatedIntent() {
        auth.value = AuthState.SignedOut
        launch("geo:0,0?q=" + Uri.encode("57.48,12.09(Torget & café)"))
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        compose.waitUntil(5_000) { activities.last().intent.action != Intent.ACTION_VIEW }
        dropPendingAfterSave = true
        recreate()
        assertEquals(
            GeoUriTarget.Point(LatLng(12.09, 57.48), "Torget & café"),
            MapLinkNavigator.pending.value,
        )
        compose.onNodeWithText(str(R.string.auth_googleLoginButton)).performClick()
        compose.waitUntil(5_000) { surface.routeOverlay.value?.destination?.longitude == 12.09 }
        assertEquals(57.48, surface.routeOverlay.value!!.destination.latitude, 0.0)
        assertNull(MapLinkNavigator.pending.value)
    }

    @Test fun externalLocationButton_opensThePrefilledSearch() {
        launchActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitForIdle()
        if (InstrumentationRegistry.getArguments().getString("verifyDriverHandoff") == "true") {
            val info = context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
            assertTrue(info.metaData.getBoolean("com.kungsbackacarcommunity.app.SUPPORTS_GEO_QUERY", false))
            context.startActivity(
                Intent().setClassName("com.kungsbackacarcommunity.driver", "com.kungsbackacarcommunity.driver.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            clickExternalText("Träffar")
            clickExternalText("Visa eventets plats")
        } else {
            context.startActivity(link("geo:0,0?q=Kungsbacka%20torg"))
        }
        assertQuery("Kungsbacka torg")
        compose.onNodeWithText(results[0].name).assertIsDisplayed()
        compose.onNodeWithText(results[1].name).assertIsDisplayed()
        assertNull(surface.routeOverlay.value)
        evidence("external-query-choices")
    }

    private fun clickExternalText(text: String) {
        val deadline = System.currentTimeMillis() + 20_000
        var visibleLabels = emptyList<String>()
        while (System.currentTimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val nodes = ArrayDeque<AccessibilityNodeInfo>()
            root?.let(nodes::add)
            val labels = mutableListOf<String>()
            // Flutter exposes these buttons as virtual accessibility nodes with
            // content descriptions; its provider need not implement Android's
            // findAccessibilityNodeInfosByText. Traverse the actual tree instead.
            while (nodes.isNotEmpty()) {
                val node = nodes.removeFirst()
                val label = node.text?.toString() ?: node.contentDescription?.toString()
                if (!label.isNullOrBlank()) labels.add(label)
                if (node.text?.toString() == text || node.contentDescription?.toString() == text) {
                    var clickable: AccessibilityNodeInfo? = node
                    while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                    if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
            }
            visibleLabels = labels
            Thread.sleep(150)
        }
        evidence("driver-control-missing")
        throw AssertionError("Driver fixture control not found: $text; visible labels: $visibleLabels")
    }

    @Composable private fun TestShell() {
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
            leaderboardRepository = null,
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
            driveHistoryRepository = null,
            pointsRepository = null,
            partnerApplicationCoordinator = null,
            billboardsRepository = null,
            accountDeletionCoordinator = null,
            partnerStatsRepository = null,
            partnerStatsCoordinator = null,
            leaderboardVisibilityRepository = null,
            leaderboardVisibilityCoordinator = null,
            feedbackCoordinator = null,
            billingRepository = null,
            subscriptionVerifier = null,
            pushRegistrationCoordinator = null,
            loginRecordCoordinator = null,
            flags = FeatureFlags.DEFAULTS,
            onSignOut = {},
            mapSurface = surface,
            appUpdateSource = null,
            navigationSearchClient = client,
        )
    }

    private companion object { const val TEST_UID = "geo-query-fixture" }
}
