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
                    offersState = OffersState.Loaded(listOf(offer())),
                    onOpenCompany = { opened = it },
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Bilverkstan").performScrollTo().performClick()
        assertEquals("c1", opened)
    }

    @Test
    fun directory_showsLocalizedSingularOfferCount() {
        composeTestRule.setContent {
            KccTheme {
                PartnersListScreen(
                    state = CompaniesState.Loaded(listOf(company())),
                    offersState = OffersState.Loaded(listOf(offer())),
                    onOpenCompany = {},
                    onBack = {},
                )
            }
        }
        val expected =
            InstrumentationRegistry.getInstrumentation().targetContext.getString(
                R.string.partnerOffers_offerCountOne,
                1,
            )
        composeTestRule.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun directory_doesNotShowZeroWhileOffersAreLoading() {
        composeTestRule.setContent {
            KccTheme {
                PartnersListScreen(
                    state = CompaniesState.Loaded(listOf(company())),
                    offersState = OffersState.Loading,
                    onOpenCompany = {},
                    onBack = {},
                )
            }
        }
        val zeroOffers =
            InstrumentationRegistry.getInstrumentation().targetContext.getString(
                R.string.partnerOffers_offerCountOther,
                0,
            )
        composeTestRule.onNodeWithText(zeroOffers).assertDoesNotExist()
    }

    @Test
    fun directory_doesNotShowCountWhenOfferSnapshotIsCapped() {
        composeTestRule.setContent {
            KccTheme {
                PartnersListScreen(
                    state = CompaniesState.Loaded(listOf(company())),
                    offersState = OffersState.Loaded(emptyList(), isExhaustive = false),
                    onOpenCompany = {},
                    onBack = {},
                )
            }
        }
        val zeroOffers =
            InstrumentationRegistry.getInstrumentation().targetContext.getString(
                R.string.partnerOffers_offerCountOther,
                0,
            )
        composeTestRule.onNodeWithText(zeroOffers).assertDoesNotExist()
    }

    @Test
    fun detail_doesNotClaimNoOffersWhenOfferSnapshotIsCapped() {
        composeTestRule.setContent {
            KccTheme {
                PartnerDetailScreen(
                    companyState = CompanyState.Loaded(company()),
                    offers = emptyList(),
                    offersState = OffersState.Loaded(emptyList(), isExhaustive = false),
                    savedOfferIds = emptySet(),
                    canAccessMemberOffers = true,
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
        composeTestRule.onNodeWithText(str(R.string.partnerOffers_noOffers)).assertDoesNotExist()
    }

    @Test
    fun detail_offerFailure_showsRetry() {
        var retries = 0
        composeTestRule.setContent {
            KccTheme {
                PartnerDetailScreen(
                    companyState = CompanyState.Loaded(company()),
                    offers = emptyList(),
                    offersState = OffersState.Error,
                    savedOfferIds = emptySet(),
                    canAccessMemberOffers = true,
                    expandedOfferId = null,
                    expandedOfferDetail = null,
                    codeStatus = OfferCodeStatus.Idle,
                    onToggleExpand = {},
                    onShowCode = {},
                    onToggleSave = { _, _ -> },
                    onBack = {},
                    onRetryOffers = { retries++ },
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.partnerOffers_loadError)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.partners_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun savedSection_tapOffer_reportsCompanyAndOfferIds() {
        var opened: Pair<String, String>? = null
        composeTestRule.setContent {
            KccTheme {
                PartnersRootScreen(
                    state = CompaniesState.Loaded(listOf(company())),
                    offersState = OffersState.Loaded(listOf(offer())),
                    savedOffersState = OffersState.Loaded(listOf(offer())),
                    canAccessMemberOffers = true,
                    section = PartnersRootSection.SAVED,
                    onSectionChange = {},
                    onOpenCompany = {},
                    onOpenSavedOffer = { companyId, offerId -> opened = companyId to offerId },
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText("20% off").performScrollTo().performClick()
        assertEquals("c1" to "o1", opened)
    }

    @Test
    fun detail_freeUser_seesUpgradePrompt_noSaveOrCode() {
        composeTestRule.setContent {
            KccTheme {
                PartnerDetailScreen(
                    companyState = CompanyState.Loaded(company().copy(latitude = 57.49, longitude = 12.07)),
                    offers = listOf(offer()),
                    savedOfferIds = emptySet(),
                    canAccessMemberOffers = false,
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
        composeTestRule
            .onNodeWithText(str(R.string.partnerOffers_upgradeForMemberOffers))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.partnerOffers_showCode)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.partnerOffers_saveOffer)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.partners_callButton)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.partners_websiteButton)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.partners_navigateButton)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun detail_companyFailure_showsRetry() {
        var retries = 0
        composeTestRule.setContent {
            KccTheme {
                PartnerDetailScreen(
                    companyState = CompanyState.Error,
                    offers = emptyList(),
                    savedOfferIds = emptySet(),
                    canAccessMemberOffers = true,
                    expandedOfferId = null,
                    expandedOfferDetail = null,
                    codeStatus = OfferCodeStatus.Idle,
                    onToggleExpand = {},
                    onShowCode = {},
                    onToggleSave = { _, _ -> },
                    onBack = {},
                    onRetryCompany = { retries++ },
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.partners_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun detail_paidSubscriber_expanded_showsCodeAfterReveal() {
        composeTestRule.setContent {
            KccTheme {
                PartnerDetailScreen(
                    companyState = CompanyState.Loaded(company()),
                    offers = listOf(offer()),
                    savedOfferIds = setOf("o1"),
                    canAccessMemberOffers = true,
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
        composeTestRule.onNodeWithText(str(R.string.partnerOffers_unsaveOffer)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("SAVE20", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun detail_member_toggleSave_reportsInverse() {
        var saveCall: Pair<String, Boolean>? = null
        composeTestRule.setContent {
            KccTheme {
                PartnerDetailScreen(
                    companyState = CompanyState.Loaded(company()),
                    offers = listOf(offer()),
                    savedOfferIds = emptySet(),
                    canAccessMemberOffers = true,
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
