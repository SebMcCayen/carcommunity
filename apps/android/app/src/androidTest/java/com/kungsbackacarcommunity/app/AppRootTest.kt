package com.kungsbackacarcommunity.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI smoke test (Phase 5 scaffold; CI-enforced since Phase 17 via
 * the instrumented-tests job in validate-android.yml, which runs
 * connectedDebugAndroidTest on an emulator for every PR).
 */
@RunWith(AndroidJUnit4::class)
class AppRootTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun appRoot_showsAppName() {
        val appName =
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.app_name)
        composeTestRule.setContent { AppRoot() }
        composeTestRule.onNodeWithText(appName).assertExists()
    }
}
