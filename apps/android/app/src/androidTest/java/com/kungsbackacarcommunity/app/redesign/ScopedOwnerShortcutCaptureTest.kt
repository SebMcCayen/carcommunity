package com.kungsbackacarcommunity.app.redesign

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.friends.FriendsCataloguePreview
import com.kungsbackacarcommunity.app.notifications.AppNotification
import com.kungsbackacarcommunity.app.notifications.NotificationCategory
import com.kungsbackacarcommunity.app.notifications.NotificationsScreen
import com.kungsbackacarcommunity.app.notifications.NotificationsState
import com.kungsbackacarcommunity.app.shell.AeroOwnerAvatarTag
import com.kungsbackacarcommunity.app.shell.AeroBackButtonTag
import com.kungsbackacarcommunity.app.shell.AeroOwnerHeader
import com.kungsbackacarcommunity.app.shell.LocalAeroBackAvailable
import com.kungsbackacarcommunity.app.shell.LocalAeroOwnerHeader
import com.kungsbackacarcommunity.app.shell.MAP_HOME_MORE_TAG
import com.kungsbackacarcommunity.app.shell.MapHome
import com.kungsbackacarcommunity.app.shell.MapLoadState
import com.kungsbackacarcommunity.app.shell.StubMapSurface
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native screenshots only: local inputs, inherited owner header, no accounts or cloud reads. */
@RunWith(AndroidJUnit4::class)
class ScopedOwnerShortcutCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureScopedScreensAndMapControl() {
        val args = InstrumentationRegistry.getArguments()
        val screens = listOf("garage", "vehicle-detail", "friends", "settings", "notifications", "drive-history", "drive-detail", "map")
        val selected = mutableStateOf(screens.first())
        val dark = args.getString("redesignTheme") != "light"
        val fontScale = args.getString("shortcutFontScale")?.toFloat() ?: 1f
        require(fontScale in 1f..2f)
        val variant = if (dark) "dark" else "light"
        compose.setContent {
            KccTheme(darkTheme = dark) {
                BackHandler {}
                CompositionLocalProvider(
                    LocalAeroOwnerHeader provides AeroOwnerHeader(null, true, {}),
                    LocalAeroBackAvailable provides (selected.value !in setOf("garage", "map")),
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Text("DESIGNPREVIEW · LOKALA TESTDATA · ${selected.value}")
                        Box(Modifier.weight(1f)) {
                            when (selected.value) {
                                "garage", "vehicle-detail", "drive-history", "drive-detail" -> Wave2GaragePreview(selected.value)
                                "friends" -> FriendsCataloguePreview()
                                "settings" -> Wave2SettingsPreview("settings")
                                "notifications" -> NotificationsScreen(
                                    state = NotificationsState.Loaded(listOf(AppNotification(
                                        id = "local-notification", category = NotificationCategory.EVENT_REMINDER,
                                        title = "Lokal testnotis", previewText = "Endast lokala testdata", body = null,
                                        isRead = false, createdAtMillis = null,
                                    ))),
                                    onMarkRead = {}, onMarkAllRead = {}, onBack = {},
                                )
                                "map" -> {
                                    val surface = remember { StubMapSurface(MapLoadState.Loaded, autoLoad = false) }
                                    MapHome(
                                        mapSurface = surface, isLiveSharing = false, participantCount = 0,
                                        userLabel = "Lokal testprofil", onSearch = {}, onOpenSavedPlaces = {}, moreMenuEntries = emptyList(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        for (screen in screens) {
            compose.runOnIdle { selected.value = screen }
            compose.waitForIdle()
            compose.onNodeWithTag(AeroOwnerAvatarTag).assertDoesNotExist()
            if (screen == "map") compose.onNodeWithTag(MAP_HOME_MORE_TAG).assertIsDisplayed()
            if (screen !in setOf("garage", "map")) compose.onNodeWithTag(AeroBackButtonTag).assertIsDisplayed()
            val output = File(compose.activity.getExternalFilesDir(null), "owner-shortcut-$variant-$fontScale-$screen.png")
            output.outputStream().use { stream ->
                check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        }
    }
}
