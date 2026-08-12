package com.kungsbackacarcommunity.app.partners

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the partner-application form (Phase 12 slice 18).
 */
@RunWith(AndroidJUnit4::class)
class PartnerApplicationScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun missingFields_showsRequiredError() {
        composeTestRule.setContent {
            KccTheme {
                PartnerApplicationScreen(
                    status = PartnerApplicationStatus.Idle,
                    onSubmit = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.partners_submitButton)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(str(R.string.partners_fieldRequired)).assertIsDisplayed()
    }

    @Test
    fun completeForm_submitsPayload() {
        var submitted: PartnerApplicationInput? = null
        composeTestRule.setContent {
            KccTheme {
                PartnerApplicationScreen(
                    status = PartnerApplicationStatus.Idle,
                    onSubmit = { submitted = it },
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.partners_companyNameLabel)).performTextInput("Bilverkstan")
        composeTestRule.onNodeWithText(str(R.string.partners_categoryWorkshop)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(str(R.string.partners_contactNameLabel)).performTextInput("Ada")
        composeTestRule.onNodeWithText(str(R.string.partners_contactEmailLabel)).performTextInput("ada@example.com")
        composeTestRule.onNodeWithText(str(R.string.partners_submitButton)).performScrollTo().performClick()
        assertNotNull(submitted)
        assertEquals("Bilverkstan", submitted!!.companyName)
        assertEquals(PartnerCategory.WORKSHOP, submitted!!.category)
    }

    @Test
    fun done_showsSuccess() {
        composeTestRule.setContent {
            KccTheme {
                PartnerApplicationScreen(
                    status = PartnerApplicationStatus.Done,
                    onSubmit = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.partners_submitSuccess)).assertIsDisplayed()
    }
}
