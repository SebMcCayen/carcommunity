package com.kungsbackacarcommunity.app.redesign

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.CreateChooserDialog
import com.kungsbackacarcommunity.app.convoy.ConvoyBar
import com.kungsbackacarcommunity.app.convoy.ConvoyBarState
import com.kungsbackacarcommunity.app.convoy.ConvoyBarMember
import com.kungsbackacarcommunity.app.convoy.ConvoyStatusBar
import com.kungsbackacarcommunity.app.ShellBottomBar
import com.kungsbackacarcommunity.app.ShellBottomBarHeight
import com.kungsbackacarcommunity.app.crownhunt.PerkDeployItem
import com.kungsbackacarcommunity.app.crownhunt.PerkDeployMenuState
import com.kungsbackacarcommunity.app.crownhunt.PerkDeployPopup
import com.kungsbackacarcommunity.app.crownhunt.PerkDeployStatus
import com.kungsbackacarcommunity.app.crownhunt.PerkKind
import com.kungsbackacarcommunity.app.crownhunt.CrownRarity
import com.kungsbackacarcommunity.app.crownhunt.CrownMarkerStyle
import com.kungsbackacarcommunity.app.crownhunt.crownGlyphRes
import com.kungsbackacarcommunity.app.shell.MapCrownMarker
import com.kungsbackacarcommunity.app.incidents.IncidentAgeOption
import com.kungsbackacarcommunity.app.incidents.IncidentTypePickerDialog
import com.kungsbackacarcommunity.app.map.LocalMapZoomController
import com.kungsbackacarcommunity.app.map.MapZoomController
import com.kungsbackacarcommunity.app.map.MapZoomPreference
import com.kungsbackacarcommunity.app.navigation.LatLng
import com.kungsbackacarcommunity.app.navigation.PlaceSuggestion
import com.kungsbackacarcommunity.app.navigation.SavedPlace
import com.kungsbackacarcommunity.app.navigation.SavedPlaceKind
import com.kungsbackacarcommunity.app.shell.DockedMapSheetHost
import com.kungsbackacarcommunity.app.shell.LiveSessionBar
import com.kungsbackacarcommunity.app.shell.LiveSharePopup
import com.kungsbackacarcommunity.app.shell.LocalDockedMapSheetHost
import com.kungsbackacarcommunity.app.shell.MapHome
import com.kungsbackacarcommunity.app.shell.MapLayersPopup
import com.kungsbackacarcommunity.app.shell.MapLoadState
import com.kungsbackacarcommunity.app.shell.MapMode
import com.kungsbackacarcommunity.app.shell.MapSurface
import com.kungsbackacarcommunity.app.shell.SavedPlacesPickerSheet
import com.kungsbackacarcommunity.app.shell.ShellTab
import com.kungsbackacarcommunity.app.shell.StubMapSurface
import com.kungsbackacarcommunity.app.shell.mapSheetViewport

const val MAP_CATALOGUE_ROOT_TAG = "map_catalogue_root"
const val MAP_CATALOGUE_FOOTER_TAG = "map_catalogue_footer"
val MAP_SHELL_CATALOGUE_CASES = listOf(
    "map-layers", "map-saved", "map-report", "map-create", "map-perks", "map-session",
)

/**
 * Full native shell for S03/S07/S09/S13/S14/S16/S26 capture and interaction QA.
 * Host supplies KccTheme/locale/text scaling. All contents are explicitly local
 * test data; no auth, Firebase, billing, GPS or repositories are constructed.
 * Default map is StubMapSurface; optional surface supports separately configured
 * imagery captures without changing the shell geometry or adding production data.
 *
 * Integration seam: main's CreateChooserDialog must become internal (presentation
 * only) so this fixture can exercise the actual chooser rather than a test copy.
 * PerkDeployPopup requires the crown lane's shared-sheet conversion as well.
 */
@Composable
fun MapShellCataloguePreview(
    screen: String,
    mapSurface: MapSurface? = null,
    zoomController: MapZoomController? = null,
    initialMapMode: MapMode? = null,
    onFooterAction: (ShellTab) -> Unit = {},
    onSheetDismiss: () -> Unit = {},
    onLocalAction: (String) -> Unit = {},
) {
    require(screen in MAP_SHELL_CATALOGUE_CASES || screen == "map-convoy") { "Unknown map shell case: $screen" }
    val surface = mapSurface ?: remember { StubMapSurface(MapLoadState.Loaded, autoLoad = false) }
    val localZoom = remember { CatalogueZoomController() }
    val zoom = zoomController ?: localZoom
    val live = screen == "map-session" || screen == "map-convoy"
    var panel by remember(screen) { mutableStateOf(screen.takeUnless { live }) }
    var selectedTab by remember { mutableStateOf(ShellTab.Map) }
    val nightOverride = remember { mutableStateOf(initialMapMode) }
    var incidentsOn by remember { mutableStateOf(true) }
    var age by remember { mutableStateOf(IncidentAgeOption.ALL) }
    var participating by remember { mutableStateOf(true) }
    val traffic by surface.trafficEnabled.collectAsState()
    val mode by surface.mapMode.collectAsState()
    val is3d by surface.is3d.collectAsState()
    val dismiss = { panel = null; onSheetDismiss() }
    val action: (String) -> Unit = { onLocalAction(it); dismiss() }

    CompositionLocalProvider(LocalMapZoomController provides zoom) {
        // AuthenticatedApp owns this lifecycle in production. Standalone native
        // captures must activate rendering as well as waiting for a loaded style.
        DisposableEffect(surface) {
            surface.setActive(true)
            onDispose { surface.setActive(false) }
        }
        // Local visual fixtures, never spawned/claimed through a backend.
        LaunchedEffect(surface) {
            surface.setCrownMarkers(CrownRarity.entries.mapIndexed { index, rarity ->
                MapCrownMarker("fixture-crown-$index", 12.071 + index * 0.003, 57.488,
                    CrownMarkerStyle.discColorArgb(rarity), crownGlyphRes(rarity),
                    CrownMarkerStyle.glyphColorArgb(rarity), CrownMarkerStyle.glowColorArgb(rarity))
            })
        }
        DockedMapSheetHost {
            val host = LocalDockedMapSheetHost.current
            Box(Modifier.fillMaxSize().testTag(MAP_CATALOGUE_ROOT_TAG)) {
                surface.Content(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().navigationBarsPadding()
                    .padding(bottom = ShellBottomBarHeight).mapSheetViewport()) {
                    MapHome(
                        mapSurface = surface,
                        isLiveSharing = live,
                        participantCount = if (live) 1 else 0,
                        userLabel = "Lokal testförare",
                        onSearch = {},
                        onOpenSavedPlaces = { panel = "map-saved" },
                        moreMenuEntries = emptyList(),
                        nightModeOverrideState = nightOverride,
                        incidentReportingEnabled = true,
                        incidentsLayerEnabled = incidentsOn,
                        onIncidentsLayerEnabledChange = { incidentsOn = it },
                        onReportIncident = { type, _ -> action("report:${type.name}") },
                        crownHuntPerksEnabled = true,
                        onOpenPerks = { panel = "map-perks" },
                        liveSessionBar = if (live) {
                            { LiveSessionBar(sessionStartMillis = 0L, distanceMeters = 12_400.0,
                                speedSample = null, now = { 754_000L }) }
                        } else null,
                        onManageLiveSession = { panel = "map-session" },
                        convoyBar = if (screen == "map-convoy") {
                            {
                                ConvoyStatusBar(
                                    state = ConvoyBarState(
                                        convoyId = "local-catalogue-convoy",
                                        members = (1..4).map { ConvoyBarMember("local-$it", "Testförare $it", null) },
                                        viewerIsOwner = true, busy = false,
                                        inviteAvailability = ConvoyBar.inviteAvailability,
                                        leaveAvailability = ConvoyBar.leaveAvailability,
                                    ),
                                    onEndConvoy = {}, onLeaveConvoy = {},
                                    onInvite = {}, onOpenChat = {},
                                )
                            }
                        } else null,
                    )
                }
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().testTag(MAP_CATALOGUE_FOOTER_TAG)) {
                    ShellBottomBar(
                        selected = selectedTab,
                        isSharing = live,
                        onSelect = { tab ->
                            host?.dismissAll()
                            onFooterAction(tab)
                            if (tab == ShellTab.Create) panel = "map-create" else selectedTab = tab
                        },
                        onManageLiveShare = {
                            host?.dismissAll()
                            panel = "map-session"
                        },
                    )
                }
                // This label is outside mapSheetViewport, so the preview remains
                // identifiable when the host hides covered map accessibility nodes.
                    Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding()) {
                        Text("DESIGNPREVIEW · LOKALA TESTDATA", Modifier.padding(4.dp),
                            style = MaterialTheme.typography.labelSmall)
                    }
            }
            when (panel) {
                "map-layers" -> MapLayersPopup(
                    incidentsOn = incidentsOn, onIncidentsChange = { incidentsOn = it },
                    trafikverketDataShown = false, trafficOn = traffic,
                    nightMode = mode == MapMode.Night, is3d = is3d,
                    onTrafficChange = surface::setTrafficEnabled,
                    onNightModeChange = { night ->
                        nightOverride.value = if (night) MapMode.Night else MapMode.Day
                        surface.setMapMode(nightOverride.value!!)
                    },
                    on3dChange = surface::set3dEnabled,
                    browsingZoom = zoom.browsingZoom, onBrowsingZoomChange = zoom::setBrowsingZoom,
                    incidentMaxAge = age, onIncidentMaxAgeChange = { age = it },
                    crownHuntParticipating = participating,
                    onCrownHuntParticipatingChange = { participating = it },
                    onDismiss = dismiss, followsTheme = nightOverride.value == null,
                    onFollowTheme = { nightOverride.value = null },
                )
                "map-saved" -> SavedPlacesPickerSheet(
                    places = catalogueSavedPlaces, onSelect = { action("saved:${it.id}") }, onDismiss = dismiss,
                )
                "map-report" -> IncidentTypePickerDialog(
                    onPick = { action("report:${it.name}") }, onDismiss = dismiss,
                )
                "map-create" -> CreateChooserDialog(
                    onSingleSession = { action("create:single") },
                    onConvoy = { action("create:convoy") }, onDismiss = dismiss,
                )
                "map-perks" -> PerkDeployPopup(
                    menuState = PerkDeployMenuState.Loaded(cataloguePerks, isEmpty = false),
                    status = PerkDeployStatus.Idle, nowMillis = 0L,
                    onDeploy = { action("perk:${it.perkId}") }, onDismiss = dismiss,
                    onOpenShop = { action("perk:shop") },
                )
                "map-session" -> LiveSharePopup(
                    isSharing = true, canShareLive = true, onStart = {},
                    onHideMeNow = { action("session:hide") }, onOpenDetails = { action("session:details") },
                    onStop = { action("session:stop") }, onDismiss = dismiss,
                )
            }
        }
    }
}

private class CatalogueZoomController : MapZoomController {
    private var value by mutableStateOf(14.5)
    override val browsingZoom get() = value
    override fun setBrowsingZoom(zoom: Double) { value = MapZoomPreference.snap(zoom) }
}

private val catalogueSavedPlaces = List(8) { index ->
    SavedPlace("qa-saved-$index", SavedPlaceKind.Favourite, "Testplats ${index + 1}",
        PlaceSuggestion("qa-place-$index", "Lokal testplats ${index + 1}", "Testadress ${index + 1}",
            LatLng(57.49 + index * 0.001, 12.08)))
}

private val cataloguePerks = PerkKind.entries.map { kind ->
    val id = when (kind) {
        PerkKind.TRAP -> "spike_strip"
        PerkKind.SHIELD -> "shield"
        PerkKind.BOOST -> "boost"
    }
    PerkDeployItem(perkId = id, kind = kind, name = "Lokal testperk", nameEn = "Local test perk",
        ownedCount = 2, activeUntilMillis = null, activeTrapCount = 0, active = false, activatable = true)
}
