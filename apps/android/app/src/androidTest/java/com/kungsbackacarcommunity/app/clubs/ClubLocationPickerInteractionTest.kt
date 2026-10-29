package com.kungsbackacarcommunity.app.clubs

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android dialogs/keyboard with the packaged catalog; no account or cloud writes. */
@RunWith(AndroidJUnit4::class)
class ClubLocationPickerInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun countySearchResetAndSelectedPairWorkInRealWindows() {
        val submitted = mutableListOf<Map<String, Any?>>()
        rule.setContent { KccTheme {
            ClubEditor(null, false, onDismiss = {}) { submitted += it }
        } }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("club-county-field").fetchSemanticsNodes().isNotEmpty()
        }
        fun str(id: Int) = rule.activity.getString(id)
        rule.onNode(hasText(str(R.string.clubs_name)) and hasSetTextAction())
            .performScrollTo().performTextInput("Emulator club")
        rule.onNode(hasText(str(R.string.clubs_description)) and hasSetTextAction())
            .performScrollTo().performTextInput("Local UI validation only")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithTag("club-locality-field").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText(str(R.string.clubs_save)).assertIsNotEnabled()

        fun county(code: String) {
            rule.onNodeWithTag("club-county-field").performScrollTo().performClick()
            rule.onNodeWithTag("club-county-list").performScrollToNode(hasTestTag("club-county-$code"))
            rule.onNodeWithTag("club-county-$code").performClick()
        }
        fun locality(query: String, code: String) {
            rule.onNodeWithTag("club-locality-field").performScrollTo().performClick()
            rule.onNodeWithTag("club-locality-search").performTextInput(query)
            rule.onNodeWithTag("club-locality-list").performScrollToNode(hasTestTag("club-locality-$code"))
            rule.onNodeWithTag("club-locality-$code").assertIsDisplayed()
            capture("locality-search-keyboard")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            rule.onNodeWithTag("club-locality-list").performScrollToNode(hasTestTag("club-locality-$code"))
            capture("locality-search")
            rule.onNodeWithTag("club-locality-$code").performClick()
        }
        county("13")
        locality("Åsa", "1384TB126")
        rule.onNodeWithTag("club-locality-field").assertTextContains("Åsa")
        rule.onNodeWithText(str(R.string.clubs_save)).assertIsEnabled()
        county("14")
        rule.onNodeWithTag("club-locality-field").assertTextContains(str(R.string.clubs_chooseLocality))
        rule.onNodeWithText(str(R.string.clubs_save)).assertIsNotEnabled()
        county("13")
        rule.onNodeWithTag("club-locality-field").performScrollTo().performClick()
        rule.onNodeWithTag("club-locality-search").performTextInput("No such locality 123")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText(str(R.string.addressSearch_noResults)).assertIsDisplayed()
        rule.onNodeWithTag("club-locality-search").performTextReplacement("Kungsbacka")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithTag("club-locality-list").performScrollToNode(hasTestTag("club-locality-1384TC117"))
        rule.onNodeWithTag("club-locality-1384TC117").performClick()
        rule.onNodeWithTag("club-locality-field").assertTextContains("Kungsbacka")
        capture("selected-location")
        rule.onNodeWithText(str(R.string.clubs_save)).performClick()
        rule.runOnIdle {
            assertEquals(1, submitted.size)
            assertEquals("Kungsbacka, Hallands län", submitted.single()["location"])
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5000)
        check(instrumentation.uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId("android:id/aerr_close").isNullOrEmpty())
        val variant = InstrumentationRegistry.getArguments().getString("clubCaptureVariant") ?: "default"
        require(variant.matches(Regex("[a-zA-Z0-9_-]+")))
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = instrumentation.targetContext.contentResolver
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/carcommunity-club-location/$variant")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
            checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } else {
            val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "club-location/$variant")
            check(folder.mkdirs() || folder.isDirectory)
            File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        bitmap.recycle()
    }
}
