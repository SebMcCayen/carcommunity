package com.kungsbackacarcommunity.app.redesign

import androidx.compose.runtime.Composable
import com.kungsbackacarcommunity.app.account.AccountDeletionScreen
import com.kungsbackacarcommunity.app.account.AccountDeletionStatus
import com.kungsbackacarcommunity.app.blocking.BlockActionStatus
import com.kungsbackacarcommunity.app.blocking.BlockedUser
import com.kungsbackacarcommunity.app.blocking.BlockedUsersState
import com.kungsbackacarcommunity.app.blocking.BlockingScreen
import com.kungsbackacarcommunity.app.partners.CompaniesState
import com.kungsbackacarcommunity.app.partners.CompanyState
import com.kungsbackacarcommunity.app.partners.OfferCodeStatus
import com.kungsbackacarcommunity.app.partners.OfferDetailState
import com.kungsbackacarcommunity.app.partners.OffersState
import com.kungsbackacarcommunity.app.partners.PartnerCategory
import com.kungsbackacarcommunity.app.partners.PartnerCompany
import com.kungsbackacarcommunity.app.partners.PartnerDetailScreen
import com.kungsbackacarcommunity.app.partners.PartnerOffer
import com.kungsbackacarcommunity.app.partners.PartnerOfferType
import com.kungsbackacarcommunity.app.partners.PartnersRootScreen
import com.kungsbackacarcommunity.app.partners.PartnersRootSection
import com.kungsbackacarcommunity.app.partners.PartnerApplicationScreen
import com.kungsbackacarcommunity.app.partners.PartnerApplicationStatus
import com.kungsbackacarcommunity.app.privacy.PartnerStatsScreen
import com.kungsbackacarcommunity.app.privacy.PartnerStatsConsentState
import com.kungsbackacarcommunity.app.privacy.PartnerStatsSaveStatus
import com.kungsbackacarcommunity.app.privacy.LeaderboardVisibilityState
import com.kungsbackacarcommunity.app.notifications.NotificationSettingsScreen
import com.kungsbackacarcommunity.app.notifications.NotificationSettingsState
import com.kungsbackacarcommunity.app.notifications.NotificationSettingsSaveStatus
import com.kungsbackacarcommunity.app.notifications.NotificationPreferences
import com.kungsbackacarcommunity.app.notifications.PushPermissionStatus
import com.kungsbackacarcommunity.app.subscription.PurchaseFlowStatus
import com.kungsbackacarcommunity.app.subscription.SubscriptionScreen
import com.kungsbackacarcommunity.app.whatsnew.ChangelogEntry
import com.kungsbackacarcommunity.app.whatsnew.WhatsNewScreen

/**
 * Test APK only. MAIN supplies theme/locale/viewport and a visible fixture caption.
 * No repositories, Firebase, billing clients, remote images or real mutations are constructed.
 * S69 delegates to the existing settings fixture with its local ThemeController.
 */
@Composable
fun Pass3SettingsFixture(screen: String) {
    val company = PartnerCompany(
        id = "fixture-partner", name = "Fixture partner", category = PartnerCategory.WORKSHOP,
        description = "Test-only partner description.", website = null, phone = null,
        latitude = null, longitude = null, address = "Fixture address",
    )
    val offer = PartnerOffer(
        id = "fixture-offer", companyId = company.id, title = "Fixture member benefit",
        teaserText = "Test-only public teaser.", offerType = PartnerOfferType.MEMBER_BENEFIT,
    )
    when (screen) {
        "S65", "S65-saved-locked" -> PartnersRootScreen(
            state = CompaniesState.Loaded(listOf(company)),
            offersState = OffersState.Loaded(listOf(offer)),
            savedOffersState = OffersState.Loaded(listOf(offer)),
            canAccessMemberOffers = screen == "S65",
            section = if (screen == "S65") PartnersRootSection.DIRECTORY else PartnersRootSection.SAVED,
            onSectionChange = {}, onOpenCompany = {}, onOpenSavedOffer = { _, _ -> }, onBack = {},
        )
        "S66", "S66-locked" -> PartnerDetailScreen(
            companyState = CompanyState.Loaded(company), offers = listOf(offer),
            offersState = OffersState.Loaded(listOf(offer)), savedOfferIds = emptySet(),
            canAccessMemberOffers = screen == "S66", expandedOfferId = null,
            expandedOfferDetailState = OfferDetailState.Loading, codeStatus = OfferCodeStatus.Idle,
            onToggleExpand = {}, onShowCode = {}, onToggleSave = { _, _ -> }, onBack = {},
        )
        "S69" -> Wave2SettingsPreview("settings")
        "S67" -> PartnerApplicationScreen(PartnerApplicationStatus.Idle, {}, {})
        "S68", "S68-unresolved" -> PartnerStatsScreen(
            consent = if (screen == "S68") PartnerStatsConsentState.Chosen(false)
                else PartnerStatsConsentState.Unknown,
            saveStatus = PartnerStatsSaveStatus.Idle, onSave = {}, onBack = {},
            leaderboardVisibility = LeaderboardVisibilityState.DefaultShown,
            onSaveLeaderboard = {},
        )
        "S71" -> NotificationSettingsScreen(
            state = NotificationSettingsState.Loaded(NotificationPreferences.ALL_ENABLED),
            pushPermission = PushPermissionStatus.GRANTED,
            saveStatus = NotificationSettingsSaveStatus.Idle,
            onToggle = { _, _, _ -> }, onOpenSystemSettings = {}, onBack = {},
        )
        "S72", "S72-working" -> BlockingScreen(
            state = BlockedUsersState.Loaded(
                (1..12).map { BlockedUser("fixture-$it", "Fixture user $it", null) }
            ),
            actionStatus = if (screen == "S72-working") BlockActionStatus.Working else BlockActionStatus.Idle,
            onUnblock = {}, onBack = {},
        )
        "S75", "S75-failed", "S75-deleting" -> AccountDeletionScreen(
            status = when (screen) {
                "S75-failed" -> AccountDeletionStatus.Failed
                "S75-deleting" -> AccountDeletionStatus.Deleting
                else -> AccountDeletionStatus.Idle
            },
            onDelete = {}, onBack = {},
        )
        "S76" -> WhatsNewScreen(
            entries = (3 downTo 1).map {
                ChangelogEntry(it, "fixture-$it", "2026-10-0$it", emptyList(),
                    listOf("Test-only release note that wraps at larger text sizes.", "Another test-only change."))
            },
        )
        "S80", "S80-plus", "S80-supporter", "S80-pending" -> SubscriptionScreen(
            isActiveMember = screen in setOf("S80-plus", "S80-supporter"),
            currentTier = when (screen) {
                "S80-plus" -> "plus"
                "S80-supporter" -> "supporter"
                else -> null
            },
            status = if (screen == "S80-pending") PurchaseFlowStatus.Pending else PurchaseFlowStatus.Ready,
            canSubscribe = true, canChangePlan = false,
            canManageSubscription = screen in setOf("S80-plus", "S80-supporter"),
            onSubscribe = {}, onManageSubscription = {}, onRestore = {}, onBack = {},
        )
        else -> error("Unknown settings fixture: $screen")
    }
}
