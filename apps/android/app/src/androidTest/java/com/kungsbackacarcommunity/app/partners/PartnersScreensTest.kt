package com.kungsbackacarcommunity.app.partners

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
 * Compose UI tests for the partner screens (Phase 12 slice 17).
 */
@RunWith(AndroidJUnit4::class)
class PartnersScreensTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun company() =
        PartnerCompany(
            id = "c1",
            name = "Bilverkstan",
            category = PartnerCategory.WORKSHOP,
            description = "Full service",
            website = "https://example.com",
            phone = "010-1234",
            latitude = null,
            longitude = null,
        )

    private fun offer() =
        PartnerOffer(
            id = "o1",
            companyId = "c1",
            title = "20% off",
            teaserText = "Members save 20%",
            offerType = PartnerOfferType.PERCENTAGE_DISCOUNT,
        )

    @Test
    fun list_tapCompany_reportsId() {
        var opened: String? = null
        composeTestRule.setContent {
            KccTheme {
                PartnersListScreen(
                    state = CompaniesState.Loaded(listOf(company())),
                    onOpenCompany = { opened = it },
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Bilverkstan").performScrollTo().performClick()
        assertEquals("c1", opened)
    }

    @Test
    fun detail_nonMember_seesHint_noSaveOrCode() {
        composeTestRule.setContent {
            KccTheme {
                PartnerDetailScreen(
                    company = company(),
                    offers = listOf(offer()),
                    savedOfferIds = emptySet(),
                    passesMemberGate = false,
                    expandedOfferId = null,
                    expandedOfferDetail = null,
                    codeStatus = OfferCodeStatus.Idle,
                    onToggleExpand = {},
                    onShowCode = {},
                    onToggleSave = { _, _ -> },
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.partnerOffers_memberRequiredHint)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.partnerOffers_showCode)).assertDoesNotExist()
    }

    @Test
    fun detail_member_expanded_showsCodeAfterReveal() {
        composeTestRule.setContent {
            KccTheme {
                PartnerDetailScreen(
                    company = company(),
                    offers = listOf(offer()),
                    savedOfferIds = setOf("o1"),
                    passesMemberGate = true,
                    expandedOfferId = "o1",
                    expandedOfferDetail = OfferMemberDetail("Great deal", null, "No cash value"),
                    codeStatus = OfferCodeStatus.Shown("o1", "SAVE20"),
                    onToggleExpand = {},
                    onShowCode = {},
                    onToggleSave = { _, _ -> },
                    onBack = {},
                )
            }
        }
        // Saved → shows the unsave label, and the revealed code is visible.
        composeTestRule.onNodeWithText(str(R.string.partnerOffers_unsaveOffer)).assertIsDisplayed()
        composeTestRule.onNodeWithText("SAVE20", substring = true).assertIsDisplayed()
    }

    @Test
    fun detail_member_toggleSave_reportsInverse() {
        var saveCall: Pair<String, Boolean>? = null
        composeTestRule.setContent {
            KccTheme {
                PartnerDetailScreen(
                    company = company(),
                    offers = listOf(offer()),
                    savedOfferIds = emptySet(),
                    passesMemberGate = true,
                    expandedOfferId = null,
                    expandedOfferDetail = null,
                    codeStatus = OfferCodeStatus.Idle,
                    onToggleExpand = {},
                    onShowCode = {},
                    onToggleSave = { id, saved -> saveCall = id to saved },
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.partnerOffers_saveOffer)).performScrollTo().performClick()
        assertEquals("o1" to true, saveCall)
    }
}
