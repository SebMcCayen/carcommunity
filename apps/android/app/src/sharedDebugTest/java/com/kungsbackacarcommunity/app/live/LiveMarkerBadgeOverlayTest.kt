package com.kungsbackacarcommunity.app.live

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.convoy.ConvoyMapAwarenessOverlay
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.map.ConvoyMemberPosition
import com.kungsbackacarcommunity.app.profile.ClubBadge
import com.kungsbackacarcommunity.app.profile.MembershipMapRing
import com.kungsbackacarcommunity.app.profile.MembershipMapRingSettingRow
import com.kungsbackacarcommunity.app.profile.ProfileEditStatus
import com.kungsbackacarcommunity.app.shell.MapCameraSnapshot
import com.kungsbackacarcommunity.app.shell.MapProjection
import com.kungsbackacarcommunity.app.shell.MapScreenPoint
import com.kungsbackacarcommunity.app.shell.StubMapSurface
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveMarkerBadgeOverlayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun mapToNavigationHandoffUsesOneObserverAndDisposalDoesNotClearOtherDrawnSources() {
        val showMap = mutableStateOf(true)
        val showNavigation = mutableStateOf(false)
        val latitude = mutableStateOf(57.4875)
        val sources = mutableStateMapOf<String, Set<String>>()
        val serverProfile = MutableStateFlow<LiveMarkerProfile?>(LiveMarkerProfile(MembershipMapRing(eligible = true)))
        var opened = 0
        var closed = 0
        val repository = object : LiveMarkerAppearanceRepository {
            override fun observeProfile(uid: String): Flow<LiveMarkerProfile?> = flow {
                opened++
                try {
                    emitAll(serverProfile)
                } finally { closed++ }
            }
        }
        compose.setContent { KccTheme {
            val density = LocalDensity.current.density
            val surface = remember { StubMapSurface().apply {
                setCameraSnapshotForTest(MapCameraSnapshot(57.4874, 12.0757, 12.0, 0.0, 0.0))
                setProjectionForTest { _, _ -> MapScreenPoint(120f * density, 60f * density) }
            } }
            val nearby = listOf(LiveMarker("nearby", latitude.value, 12.0757, "Nearby"))
            val members = listOf(ConvoyMemberPosition("convoy", latitude.value, 12.0757, "Convoy", null, 1_800_000_000_000L))
            val authorized = nearby.map { it.uid }.toSet() + members.map { it.uid }
            val drawn = sources.values.flatten().toSet().intersect(authorized)
            val appearances = rememberLiveMarkerAppearances(repository, "viewer", drawn)
            Column {
                if (showMap.value) HandoffLayersFixture(surface, nearby, members, appearances, sources, "map")
                if (showNavigation.value) HandoffLayersFixture(surface, nearby, members, appearances, sources, "navigation")
            }
        } }
        compose.onAllNodesWithTag(LIVE_MEMBERSHIP_RING_TAG).assertCountEquals(2)
        compose.runOnIdle { assertEquals(2, opened); showNavigation.value = true }
        compose.onAllNodesWithTag(LIVE_MEMBERSHIP_RING_TAG).assertCountEquals(4)
        compose.runOnIdle { showMap.value = false }
        compose.onAllNodesWithTag(LIVE_MEMBERSHIP_RING_TAG).assertCountEquals(2)
        repeat(3) {
            compose.runOnIdle { latitude.value += .00001 }
            compose.onAllNodesWithTag(LIVE_MEMBERSHIP_RING_TAG).assertCountEquals(2)
        }
        compose.runOnIdle { serverProfile.value = LiveMarkerProfile(MembershipMapRing(eligible = false)) }
        compose.onAllNodesWithTag(LIVE_MEMBERSHIP_RING_TAG).assertCountEquals(0)
        compose.runOnIdle { serverProfile.value = LiveMarkerProfile(MembershipMapRing(eligible = true)) }
        compose.onAllNodesWithTag(LIVE_MEMBERSHIP_RING_TAG).assertCountEquals(2)
        compose.runOnIdle {
            assertEquals(2, opened); assertEquals(0, closed)
            assertEquals(emptySet<String>(), sources["map-nearby"])
            assertEquals(setOf("nearby"), sources["navigation-nearby"])
            showNavigation.value = false
        }
        compose.onAllNodesWithTag(LIVE_MEMBERSHIP_RING_TAG).assertCountEquals(0)
        compose.runOnIdle { assertTrue(sources.values.all { it.isEmpty() }); assertEquals(opened, closed) }
    }

    @Test fun stationaryNearbyConvoyAndEdgeMarkersUpdateBothCosmeticsWithoutNewPositions() {
        val appearance = mutableStateOf(liveMarkerBadgeFixtureAppearance())
        var nearbyDrawn = emptySet<String>()
        var convoyDrawn = emptySet<String>()
        compose.setContent { KccTheme {
            LiveMarkerBadgeOverlayFixture(appearance.value,
                onNearbyDrawn = { nearbyDrawn = it }, onConvoyDrawn = { convoyDrawn = it })
        } }
        compose.onAllNodesWithTag(LIVE_MEMBERSHIP_RING_TAG).assertCountEquals(3)
        compose.onAllNodesWithTag(LIVE_CLUB_BADGE_TAG).assertCountEquals(3)
        compose.runOnIdle {
            assertEquals(setOf("nearby"), nearbyDrawn)
            assertEquals(setOf("convoy-on"), convoyDrawn)
        }
        compose.runOnIdle { appearance.value = appearance.value.copy(showMembershipMapRing = false) }
        compose.onAllNodesWithTag(LIVE_MEMBERSHIP_RING_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(LIVE_CLUB_BADGE_TAG).assertCountEquals(3)
        compose.runOnIdle { appearance.value = appearance.value.copy(showMembershipMapRing = true, clubBadge = null) }
        compose.onAllNodesWithTag(LIVE_MEMBERSHIP_RING_TAG).assertCountEquals(3)
        compose.onAllNodesWithTag(LIVE_CLUB_BADGE_TAG).assertCountEquals(0)
        compose.runOnIdle { appearance.value = LiveMarkerAppearance() }
        compose.onAllNodesWithTag(LIVE_MEMBERSHIP_RING_TAG).assertCountEquals(0)
        compose.onNodeWithTag("convoy_edge_arrow_convoy-edge-1").assertExists()
    }

    @Test fun lifecycleStopDetachesPublicListenersResumeAndAccountChangeReattachAndLogoutClears() {
        lateinit var owner: LocalOwner
        compose.runOnUiThread { owner = LocalOwner().apply { registry.currentState = Lifecycle.State.STARTED } }
        val session = mutableStateOf<String?>("viewer:1")
        var opened = 0
        var closed = 0
        val profile = MutableStateFlow<LiveMarkerProfile?>(LiveMarkerProfile(MembershipMapRing(eligible = true)))
        val repository = object : LiveMarkerAppearanceRepository {
            override fun observeProfile(uid: String): Flow<LiveMarkerProfile?> = flow {
                opened++
                try { emitAll(profile) } finally { closed++ }
            }
        }
        compose.setContent { KccTheme {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val appearances = rememberLiveMarkerAppearances(repository, session.value, setOf("drawn"))
                LiveCarPhotoBadge(null, appearances["drawn"] ?: LiveMarkerAppearance(),
                    ringColor = androidx.compose.ui.graphics.Color.Cyan, modifier = Modifier.size(38.dp))
            }
        } }
        compose.onNodeWithTag(LIVE_MEMBERSHIP_RING_TAG).assertExists()
        compose.runOnIdle { assertEquals(1, opened); owner.registry.currentState = Lifecycle.State.CREATED }
        compose.onNodeWithTag(LIVE_MEMBERSHIP_RING_TAG).assertDoesNotExist()
        compose.runOnIdle { assertEquals(opened, closed); owner.registry.currentState = Lifecycle.State.STARTED }
        compose.onNodeWithTag(LIVE_MEMBERSHIP_RING_TAG).assertExists()
        compose.runOnIdle { assertEquals(2, opened); session.value = "viewer:2" }
        compose.onNodeWithTag(LIVE_MEMBERSHIP_RING_TAG).assertExists()
        compose.runOnIdle { assertEquals(3, opened); assertEquals(2, closed); session.value = null }
        compose.onNodeWithTag(LIVE_MEMBERSHIP_RING_TAG).assertDoesNotExist()
        compose.runOnIdle { assertEquals(opened, closed) }
    }

    @Test fun settingDefaultsOnForIneligibleAccountAndRetainsOwnerOptOutWhileSaving() {
        val ring = mutableStateOf(MembershipMapRing())
        val status = mutableStateOf<ProfileEditStatus>(ProfileEditStatus.Idle)
        var saved: Boolean? = null
        compose.setContent { KccTheme { MembershipMapRingSettingRow(ring.value, status.value) { saved = it } } }
        compose.onNodeWithTag("membership_map_ring_setting").assertIsOn().performClick()
        compose.runOnIdle { assertEquals(false, saved); ring.value = MembershipMapRing(show = false); status.value = ProfileEditStatus.Saving }
        compose.onNodeWithTag("membership_map_ring_setting").assertIsOff().assertIsNotEnabled()
    }

    private class LocalOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}

@Composable
private fun HandoffLayersFixture(
    surface: MapProjection,
    nearby: List<LiveMarker>,
    members: List<ConvoyMemberPosition>,
    appearances: Map<String, LiveMarkerAppearance>,
    sources: MutableMap<String, Set<String>>,
    source: String,
) = key(source) {
    Box(Modifier.size(240.dp, 150.dp)) {
        NearbyLiveOverlay(surface, nearby, appearances = appearances,
            onDrawnUidsChanged = { sources["$source-nearby"] = it })
        ConvoyMapAwarenessOverlay(surface, members, nowMillis = { 1_800_000_000_000L }, appearances = appearances,
            onDrawnUidsChanged = { sources["$source-convoy"] = it })
    }
}

fun liveMarkerBadgeFixtureAppearance() = LiveMarkerAppearance(true,
    ClubBadge.fromField(mapOf("clubId" to "local-club", "clubName" to "Local Club")))

/** Real overlay renderers on local projection fixtures; no Firebase, GPS or Mapbox JNI. */
@Composable
fun LiveMarkerBadgeOverlayFixture(
    appearance: LiveMarkerAppearance,
    onNearbyDrawn: (Set<String>) -> Unit = {},
    onConvoyDrawn: (Set<String>) -> Unit = {},
) {
    val density = LocalDensity.current.density
    val latitude = 57.4874
    val longitude = 12.0757
    val now = 1_800_000_000_000L
    fun surface(offscreen: Boolean) = StubMapSurface().apply {
        setCameraSnapshotForTest(MapCameraSnapshot(latitude, longitude, 12.0, 0.0, 0.0))
        setProjectionForTest { _, _ -> MapScreenPoint(120f * density, if (offscreen) -9000f else 60f * density) }
    }
    val onScreen = remember(density) { surface(false) }
    val offScreen = remember(density) { surface(true) }
    val sharers = remember { listOf(LiveMarker("nearby", latitude + .0001, longitude, "Nearby test member")) }
    val members = remember { listOf(ConvoyMemberPosition("convoy-on", latitude + .0001, longitude, "Convoy test member", null, now)) }
    val edge = remember { listOf(
        ConvoyMemberPosition("convoy-edge-1", latitude + .5, longitude, "Edge test member", null, now),
        ConvoyMemberPosition("convoy-edge-2", latitude + .6, longitude, "Other edge member", null, now),
    ) }
    val appearances = listOf("nearby", "convoy-on", "convoy-edge-1", "convoy-edge-2").associateWith { appearance }
    Column {
        Text("Nearby · local fixture")
        Box(Modifier.size(240.dp, 150.dp)) {
            NearbyLiveOverlay(onScreen, sharers, appearances = appearances, onDrawnUidsChanged = onNearbyDrawn)
        }
        Text("Convoy · local fixture")
        Box(Modifier.size(240.dp, 150.dp)) {
            ConvoyMapAwarenessOverlay(onScreen, members, nowMillis = { now },
                appearances = appearances, onDrawnUidsChanged = onConvoyDrawn)
        }
        Text("Convoy direction · local fixture")
        Box(Modifier.size(240.dp, 150.dp)) {
            ConvoyMapAwarenessOverlay(offScreen, edge, nowMillis = { now }, appearances = appearances)
        }
    }
}
