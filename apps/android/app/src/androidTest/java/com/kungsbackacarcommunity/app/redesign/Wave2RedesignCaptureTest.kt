package com.kungsbackacarcommunity.app.redesign

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import com.mapbox.maps.MapView
import com.mapbox.maps.RenderModeType
import com.kungsbackacarcommunity.app.shell.MapPoint
import com.kungsbackacarcommunity.app.shell.MapMode
import java.util.concurrent.atomic.AtomicBoolean
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import com.kungsbackacarcommunity.app.shell.MAP_DETAIL_SHEET_TAG
import com.kungsbackacarcommunity.app.map.LocalMapZoomController
import com.kungsbackacarcommunity.app.map.MapZoomController
import com.kungsbackacarcommunity.app.map.MapZoomPreference
import com.kungsbackacarcommunity.app.shell.MapSurface
import com.kungsbackacarcommunity.app.shell.MapLoadState
import com.kungsbackacarcommunity.app.shell.MapboxMapSurface
import com.kungsbackacarcommunity.app.shell.LocalAeroOwnerHeader
import com.kungsbackacarcommunity.app.shell.LocalAeroBackAvailable
import com.kungsbackacarcommunity.app.shell.AeroOwnerHeader
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.shell.rememberMapSurface
import com.kungsbackacarcommunity.app.friends.FriendsCataloguePreview
import com.kungsbackacarcommunity.app.moderation.MessageActionsCataloguePreview
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

/**
 * Runs production Compose views with local test-only inputs, never a live account.
 * The visible caption prevents these previews being mistaken for production data.
 * Optional instrumentation arguments: wave2Screen (one case or a comma-separated
 * batch), redesignTheme and redesignCaptureVariant. Sibling fixtures compile
 * after main integrates their respective androidTest sources.
 */
@org.junit.runner.RunWith(AndroidJUnit4::class)
class Wave2RedesignCaptureTest {
    // A populated chat legitimately requests notification access. These are
    // app-component captures, so grant it before Activity launch instead of
    // letting an OS permission window remove the active Compose hierarchy.
    @get:Rule(order = 0) val notificationPermission: ExternalResource = object : ExternalResource() {
        override fun before() {
            if (Build.VERSION.SDK_INT >= 33) {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.uiAutomation.grantRuntimePermission(
                    instrumentation.targetContext.packageName,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                )
            }
        }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureProductionViewsWithLocalTestData() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val screens = args.getString("wave2Screen")?.split(',')?.map(String::trim)
            ?.filter(String::isNotEmpty)?.distinct() ?: listOf(
            "map-place", "map-live", "chat", "driver", "driver-detail",
            "events", "event-detail", "event-detail-paid", "event-detail-creator",
            "event-create", "event-edit", "event-saving", "event-chat", "event-chat-restricted",
            "event-check-in", "event-group-drive", "event-share", "crown", "crown-shop", "crown-stats",
            "convoys", "convoy-invites", "convoy-create", "convoy-create-busy",
            "convoy-active", "convoy-active-destination", "convoy-ended", "convoy-ended-partial",
            "garage", "profile", "settings", "membership",
            "member-profile", "vehicle-detail", "vehicle-editor", "awards",
            "drive-detail", "drive-history", "drive-stats", "drive-save-failed",
            "friends", "message-actions", "saved-places-manage", "saved-place-edit",
            "S65", "S65-saved-locked", "S66", "S66-locked", "S67", "S68", "S68-unresolved", "S69",
            "S71", "S72", "S72-working", "S75", "S75-failed", "S75-deleting", "S76",
            "S80", "S80-plus", "S80-supporter", "S80-pending",
        )
        require(screens.isNotEmpty() && screens.all { it.matches(Regex("[a-zA-Z0-9_-]+")) })
        val variant = args.getString("redesignCaptureVariant") ?: "wave2-dark"
        require(variant.matches(Regex("[a-zA-Z0-9_-]+")))
        var selected by mutableStateOf(screens.first())
        var configuredSurface: MapSurface? = null
        rule.setContent {
            CatalogueTheme(selected, args.getString("redesignTheme") != "light") {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            // Native map fixtures keep their full viewport. Their
                            // visible test caption is overlaid once below instead of
                            // consuming height here and again in the fixture shell.
                            if (!selected.startsWith("map-")) {
                                Text(
                                    "DESIGNPREVIEW · LOKALA TESTDATA",
                                    Modifier.fillMaxWidth().padding(8.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                            Box(Modifier.weight(1f).testTag("wave2-case-$selected")) {
                                key(selected) {
                                    when (selected) {
                                        "map-place", "map-live" -> Wave2MapPreview(selected)
                                        "chat", "driver", "driver-detail" -> Wave2ChatPreview(selected)
                                        "events", "event-detail", "event-detail-paid", "event-detail-creator",
                                        "event-create", "event-edit", "event-saving", "event-chat", "event-chat-restricted",
                                        "event-check-in", "event-group-drive", "event-share" -> EventDesignPreview(selected)
                                        "crown", "crown-shop", "crown-stats" -> Wave2SocialPreview(selected)
                                        "convoys", "convoy-invites", "convoy-create", "convoy-create-busy",
                                        "convoy-active", "convoy-active-destination", "convoy-ended", "convoy-ended-partial" ->
                                            ConvoyCataloguePreview(selected)
                                        "garage", "profile", "member-profile", "vehicle-detail", "vehicle-editor", "awards",
                                        "drive-detail", "drive-history", "drive-stats", "drive-save-failed" -> Wave2GaragePreview(selected)
                                        "friends", "S36" -> FriendsCataloguePreview()
                                        "saved-places-manage", "saved-place-edit" -> SavedPlacesCataloguePreview(selected)
                                        "message-actions", "S39" -> MessageActionsCataloguePreview()
                                        "S65", "S65-saved-locked", "S66", "S66-locked", "S67", "S68", "S68-unresolved", "S69",
                                        "S71", "S72", "S72-working", "S75", "S75-failed", "S75-deleting", "S76",
                                        "S80", "S80-plus", "S80-supporter", "S80-pending" -> Pass3SettingsFixture(selected)
                                        "settings", "membership" -> Wave2SettingsPreview(selected)
                                        // MapShellCataloguePreview is not referenced
                                        // until main cherry-picks map-qa's fixture.
                                        "map-layers", "map-saved", "map-report", "map-create", "map-perks", "map-session", "map-convoy" -> {
                                            val zoom = remember { object : MapZoomController {
                                                private var value by mutableStateOf(14.5)
                                                override val browsingZoom get() = value
                                                override fun setBrowsingZoom(zoom: Double) { value = MapZoomPreference.snap(zoom) }
                                            } }
                                            CompositionLocalProvider(LocalMapZoomController provides zoom) {
                                                val surface = if (args.getString("redesignMap") == "configured") rememberMapSurface() else null
                                                check(surface == null || surface is MapboxMapSurface) { "Configured map capture requires a runtime Mapbox token; a stub is not map evidence." }
                                                SideEffect { configuredSurface = surface }
                                                MapShellCataloguePreview(selected, mapSurface = surface, zoomController = zoom,
                                                    initialMapMode = if (args.getString("redesignMapAppearance") == "day") MapMode.Day else null)
                                            }
                                        }
                                        else -> error("Unknown redesign capture case: $selected")
                                    }
                                }
                            }
                        }
                        if (selected.startsWith("map-") && selected !in MAP_SHELL_CATALOGUE_CASES && selected != "map-convoy") {
                            Surface(Modifier.align(Alignment.TopCenter)) {
                                Text(
                                    "DESIGNPREVIEW · LOKALA TESTDATA",
                                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }
            }
        }
        screens.forEach { screen ->
            rule.runOnIdle { selected = screen }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            if (screen == "crown-stats") {
                rule.onNodeWithTag("crownHubTab_2").performClick()
                rule.waitForIdle()
            }
            rule.onNodeWithTag("wave2-case-$screen").assertExists()
            if (screen in MAP_SHELL_CATALOGUE_CASES || screen == "map-convoy") {
                rule.waitUntil(5_000) {
                    runCatching {
                        val footer = rule.onNodeWithTag(MAP_CATALOGUE_FOOTER_TAG).fetchSemanticsNode().boundsInRoot
                        val tag = if (screen == "map-session" || screen == "map-convoy") "map_home_session_dock" else MAP_DETAIL_SHEET_TAG
                        val body = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                        footer.height > 0f && body.height > 0f && body.bottom <= footer.top + 1f
                    }.getOrDefault(false)
                }
                if (args.getString("redesignMap") == "configured") {
                    rule.waitUntil(30_000) { configuredSurface?.loadState?.value == MapLoadState.Loaded }
                    val rendered = AtomicBoolean()
                    var frameSubscription: com.mapbox.common.Cancelable? = null
                    rule.runOnIdle {
                        val map = checkNotNull(findMapView(rule.activity.window.decorView)).mapboxMap
                        frameSubscription = map.subscribeRenderFrameFinished {
                            if (it.renderMode == RenderModeType.FULL) rendered.set(true)
                        }
                        // Stable local fixture camera; cold-start preference
                        // restoration is tested independently, before this move.
                        configuredSurface?.centerOn(MapPoint(12.0755, 57.488))
                        if (args.getString("redesignMapAppearance") == "day") {
                            configuredSurface?.setMapMode(MapMode.Day)
                        }
                        map.triggerRepaint()
                    }
                    rule.waitUntil(20_000) { rendered.get() }
                    rule.runOnIdle { frameSubscription?.cancel() }
                    SystemClock.sleep(1_000) // Native camera/marker animations use uptime.
                }
            }
            // Semantic idleness is not a SurfaceFlinger fence: an OS screenshot
            // can still return the preceding window after a modal is replaced.
            // PixelCopy waits for the current Compose root's draw first. Select
            // the last root so modal windows receive the same fence as pages.
            rule.mainClock.advanceTimeBy(1_000L)
            rule.waitForIdle()
            rule.onAllNodes(isRoot()).onLast().captureToImage()
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100) // Let the compositor present the fenced buffer.
            instrumentation.uiAutomation.waitForIdle(500, 5000)
            check(instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId("android:id/aerr_close")?.isNotEmpty() != true) {
                "An OS ANR overlay invalidates the screenshot. Recover the emulator before retry."
            }
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = instrumentation.targetContext.contentResolver
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "$screen.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/carcommunity-redesign/$variant")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }))
                checkNotNull(resolver.openOutputStream(uri)).use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } else {
                val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "redesign/$variant")
                check(folder.mkdirs() || folder.isDirectory)
                File(folder, "$screen.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
            bitmap.recycle()
            if (screen == screens.last()) {
                args.getString("redesignHoldMillis")?.toLongOrNull()?.coerceIn(0L, 60_000L)
                    ?.takeIf { it > 0L }?.let(SystemClock::sleep)
            }
        }
    }
}

private fun findMapView(view: View): MapView? = when (view) {
    is MapView -> view
    is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findMapView(view.getChildAt(it)) }
    else -> null
}

@Composable
private fun CatalogueTheme(screen: String, dark: Boolean, content: @Composable () -> Unit) {
    val root = screen.startsWith("map-") || screen in setOf("garage", "chat", "driver", "events", "convoys")
    CompositionLocalProvider(
        LocalAeroOwnerHeader provides if (screen == "profile") null else AeroOwnerHeader(null, false, {}),
        LocalAeroBackAvailable provides !root,
    ) {
        KccTheme(darkTheme = dark, content = content)
    }
}
