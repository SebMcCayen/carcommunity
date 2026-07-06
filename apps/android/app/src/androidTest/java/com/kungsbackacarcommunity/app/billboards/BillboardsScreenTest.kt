package com.kungsbackacarcommunity.app.billboards

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the billboards screen (Phase 12 slice 20).
 */
@RunWith(AndroidJUnit4::class)
class BillboardsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun list_showsHeadline_andTapReportsOpen() {
        var opened: String? = null
        composeTestRule.setContent {
            KccTheme {
                BillboardsScreen(
                    state = BillboardsState.Loaded(
                        listOf(Billboard("b1", "Summer sale", "20% off tyres", "c1")),
                    ),
                    onOpen = { opened = it },
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Summer sale").assertIsDisplayed()
        composeTestRule.onNodeWithText("Summer sale").performScrollTo().performClick()
        assertEquals("b1", opened)
    }

    @Test
    fun error_showsLoadError() {
        composeTestRule.setContent {
            KccTheme {
                BillboardsScreen(state = BillboardsState.Error, onOpen = {}, onBack = {})
            }
        }
        composeTestRule.onNodeWithText(str(R.string.billboard_loadError)).assertIsDisplayed()
    }
}
