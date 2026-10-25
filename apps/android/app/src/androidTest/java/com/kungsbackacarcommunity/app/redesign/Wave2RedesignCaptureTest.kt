package com.kungsbackacarcommunity.app.redesign

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.design.KccTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * Runs production Compose views with local test-only inputs, never a live account.
 * The visible caption prevents these previews being mistaken for production data.
 * Optional instrumentation arguments: wave2Screen, redesignTheme and redesignCaptureVariant.
 */
@org.junit.runner.RunWith(AndroidJUnit4::class)
class Wave2RedesignCaptureTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureProductionViewsWithLocalTestData() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val screens = args.getString("wave2Screen")?.let(::listOf) ?: listOf(
            "map-place", "map-live", "chat", "driver", "driver-detail", "events", "event-detail", "crown",
            "garage", "profile", "settings", "membership",
        )
        val variant = args.getString("redesignCaptureVariant") ?: "wave2-dark"
        require(variant.matches(Regex("[a-zA-Z0-9_-]+")))
        var selected by mutableStateOf(screens.first())
        rule.setContent {
            KccTheme(darkTheme = args.getString("redesignTheme") != "light") {
                Surface(Modifier.fillMaxSize()) {
                    Column {
                        Text(
                            "DESIGNPREVIEW · LOKALA TESTDATA",
                            Modifier.fillMaxWidth().padding(8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Box(Modifier.weight(1f).testTag("wave2-case-$selected")) {
                            key(selected) {
                                when (selected) {
                                    "map-place", "map-live" -> Wave2MapPreview(selected)
                                    "chat", "driver", "driver-detail" -> Wave2ChatPreview(selected)
                                    "events", "event-detail", "crown" -> Wave2SocialPreview(selected)
                                    "garage", "profile" -> Wave2GaragePreview(selected)
                                    "settings", "membership" -> Wave2SettingsPreview(selected)
                                    else -> error("Unknown redesign capture case: $selected")
                                }
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
            rule.onNodeWithTag("wave2-case-$screen").assertExists()
            // Semantic idleness is not a SurfaceFlinger fence: an OS screenshot
            // can still return the preceding window after a modal is replaced.
            // PixelCopy waits for the current Compose root's draw first. Select
            // the last root so modal windows receive the same fence as pages.
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
        }
    }
}
