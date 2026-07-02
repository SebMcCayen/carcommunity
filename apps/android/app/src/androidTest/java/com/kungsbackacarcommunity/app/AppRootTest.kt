package com.kungsbackacarcommunity.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Placeholder Compose UI test proving the instrumented test target is wired
 * (Phase 5). Runs on an emulator/device — not part of PR validation CI.
 */
@RunWith(AndroidJUnit4::class)
class AppRootTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun appRoot_showsAppName() {
        composeTestRule.setContent { AppRoot() }
        composeTestRule.onNodeWithText("Kungsbacka Car Community").assertExists()
    }
}
