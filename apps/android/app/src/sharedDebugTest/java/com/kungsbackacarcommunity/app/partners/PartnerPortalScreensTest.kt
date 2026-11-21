package com.kungsbackacarcommunity.app.partners

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class PartnerPortalScreensTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    internal class FakePortal(reservationStatus: String = "reserved") : PartnerPortalRepository {
        var workspaceRole: String? = null
        var heatReads = 0
        var failFirstHeat = false
        var heat = PartnerTrafficHeatSnapshot(null, 90, 0, false, emptyList())
        private var trafficIdentity: String? = "fixture-manager"
        private val trafficListeners = mutableListOf<(String?) -> Unit>()
        override val trafficSessionKey get() = trafficIdentity
        override fun observeTrafficSession(onChange: (String?) -> Unit): AutoCloseable {
            trafficListeners += onChange
            return AutoCloseable { trafficListeners -= onChange }
        }
        fun changeTrafficAccount(value: String?) { trafficIdentity = value; trafficListeners.toList().forEach { it(value) } }
        override suspend fun driveHeat(companyId: String): PartnerTrafficHeatSnapshot {
            heatReads++
            if (failFirstHeat && heatReads == 1) error("Synthetic denied request")
            return heat
        }
        var reservedVersion: Int? = null
        var inspectCount = 0
        var redeemCount = 0
        var reserveCount = 0
        var firstRequestId: String? = null
        var secondRequestId: String? = null
        var failFirstReserve = false
        var alreadyUsed = false
        var tokenCount = 0
        val reservation = PartnerReservation("fixture-reservation", "offer", "company", "Fixture business", "Fixture offer", "Fixture terms", reservationStatus, Instant.now().plusSeconds(3600).toString(), null)
        override suspend fun offer(offerId: String) = PartnerOfferReservationDetail(ManagedPartnerOffer("offer", "company", "Fixture offer", "Teaser", "Description", "Fixture terms", "active", 3, 0, 0, 7, 20, null, null, false, 5, 2), null, alreadyUsed)
        override suspend fun reserve(offerId: String, requestId: String, expectedVersion: Int): PartnerReservation {
            reserveCount++; reservedVersion = expectedVersion
            if (reserveCount == 1) firstRequestId = requestId else secondRequestId = requestId
            if (failFirstReserve && reserveCount == 1) throw IllegalStateException("Fixture transport failure")
            return reservation
        }
        override suspend fun reservations() = listOf(reservation)
        override suspend fun token(reservationId: String): PartnerReservationToken {
            tokenCount++
            return PartnerReservationToken("fixture-opaque-token-1234567890", Instant.now().plusSeconds(60).toString())
        }
        override suspend fun cancel(reservationId: String) = reservation.copy(status = "cancelled")
        override suspend fun inspect(token: String): PartnerReservation { inspectCount++; return reservation }
        override suspend fun redeem(token: String, requestId: String): PartnerReservation { redeemCount++; return reservation.copy(status = "redeemed") }
        override suspend fun workspace() = PartnerWorkspace(workspaceRole?.let { role ->
            listOf(PartnerWorkspaceCompany("company", "Fixture business", "Public presentation", role, "active", null, null, null))
        }.orEmpty(), null)
        override suspend fun offers(companyId: String): List<ManagedPartnerOffer> = emptyList()
        override suspend fun submitOffer(companyId: String, fields: Map<String, Any?>) = Unit
        override suspend fun pause(companyId: String, offerId: String, paused: Boolean) = Unit
        override suspend fun updateProfile(companyId: String, description: String, phone: String, website: String) = Unit
        override suspend fun uploadLogo(companyId: String, base64: String) = Unit
        override suspend fun statistics(companyId: String, period: String) = PartnerAggregateStatistics("2026-11-01T00:00:00Z", 0, emptyList())
    }

    @Test fun termsAreShownAndReservationRetryUsesSameRequestAndVersion() {
        val repo = FakePortal().apply { failFirstReserve = true }
        compose.setContent { KccTheme { PartnerReserveOfferRoute(repo, "offer", {}) } }
        compose.onNodeWithText("Fixture terms").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.partnerPortal_noAppointment)).performScrollTo().assertIsDisplayed()
        capturePreview("partner-offer-preview.png")
        compose.onNodeWithText(text(R.string.partnerPortal_reserve)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, repo.reserveCount)
        compose.onNodeWithText(text(R.string.partnerPortal_reserve)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(2, repo.reserveCount)
        assertEquals(repo.firstRequestId, repo.secondRequestId)
        assertEquals(5, repo.reservedVersion)
        compose.onNodeWithText(text(R.string.partnerPortal_showQr)).performScrollTo().assertIsDisplayed()
    }

    @Test fun offerPreviewBackReturnsToItsParentInsteadOfClosingTheWholeDirectory() {
        val repo = FakePortal()
        var backCount = 0
        var dispatcher: androidx.activity.OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            KccTheme { PartnerReserveOfferRoute(repo, "offer", { backCount++ }) }
        }
        compose.onNodeWithText("Fixture terms").assertIsDisplayed()
        compose.runOnIdle { dispatcher!!.onBackPressed() }
        assertEquals(1, backCount)
    }

    @Test fun retainedClaimWithoutPersonalHistoryDoesNotOfferAnotherReservation() {
        val repo = FakePortal().apply { alreadyUsed = true }
        compose.setContent { KccTheme { PartnerReserveOfferRoute(repo, "offer", {}) } }
        compose.onNodeWithText(text(R.string.partnerPortal_alreadyUsed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.partnerPortal_reserve)).assertDoesNotExist()
        assertEquals(0, repo.reserveCount)
    }

    @Test fun inspectionDoesNotRedeemUntilStaffConfirms() {
        val repo = FakePortal()
        compose.setContent { KccTheme { PartnerRedemptionRoute(repo, null, {}) } }
        compose.onNodeWithText(text(R.string.partnerPortal_tokenInput)).performScrollTo().performTextInput("fixture-opaque-token-1234567890")
        compose.onNodeWithText(text(R.string.partnerPortal_inspect)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, repo.inspectCount)
        assertEquals(0, repo.redeemCount)
        compose.onNodeWithText("Fixture terms").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.partnerPortal_confirmRedeem)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, repo.redeemCount)
        val confirmed = compose.onNodeWithText(text(R.string.partnerPortal_redeemSuccess))
        confirmed.performScrollTo()
        // Clearing the consumed token can resize the native window while the
        // IME closes, after Compose itself is idle. Require actual visibility.
        compose.waitUntil(timeoutMillis = 5_000) { confirmed.isDisplayed() }
        confirmed.assertIsDisplayed()
    }

    @Test fun serverExpiredReservationDoesNotExposeQrEvenIfLocalExpiryLooksFuture() {
        val repo = FakePortal(reservationStatus = "expired")
        compose.setContent { KccTheme { PartnerReservationsRoute(repo, {}) } }
        compose.onNodeWithText("Fixture offer").performScrollTo().performClick()
        compose.onNodeWithText("Fixture terms").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.partnerPortal_expired)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.partnerPortal_showQr)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.partnerPortal_refreshQr)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.partnerPortal_showQr)).assertDoesNotExist()
        assertEquals(0, repo.tokenCount)
    }


    @Test fun managerCanOpenHeatFromItsCompanyWorkspace() {
        val repo = FakePortal().apply { workspaceRole = "manager" }
        compose.setContent { KccTheme { PartnerWorkspaceRoute(repo, {}) } }
        compose.onNodeWithText("Fixture business").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.partnerTraffic_title)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.partnerTraffic_empty)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.partnerTraffic_privacy)).performScrollTo().assertIsDisplayed()
        assertEquals(1, repo.heatReads)
    }

    @Test fun redeemerHasNoTrafficHeatEntryOrAutomaticHeatRead() {
        val repo = FakePortal().apply { workspaceRole = "redeemer" }
        compose.setContent { KccTheme { PartnerWorkspaceRoute(repo, {}) } }
        compose.onNodeWithText("Fixture business").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.partnerPortal_scan)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.partnerTraffic_title)).assertDoesNotExist()
        assertEquals(0, repo.heatReads)
    }

    @Test fun heatReadFailureOffersRetryAndAnEmptySnapshotDoesNotClaimZeroTraffic() {
        val repo = FakePortal().apply { failFirstHeat = true }
        compose.setContent { KccTheme { PartnerTrafficHeatScreen(repo, "company", {}, mapEnabled = false) } }
        compose.onNodeWithText(text(R.string.partnerPortal_error)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.partnerPortal_retry)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.partnerTraffic_empty)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.partnerPortal_error)).assertDoesNotExist()
        assertEquals(2, repo.heatReads)
    }

    @Test fun heatRetainsTextLegendAndCoverageWarningWithoutAMapAndClearsOnSignOut() {
        val repo = FakePortal().apply {
            heat = PartnerTrafficHeatSnapshot("2026-11-21T12:00:00Z", 90, 300, true, listOf(
                PartnerHeatArea("8a0894310217fff", 57.49, 12.08, listOf(
                    PartnerHeatCoordinate(57.48, 12.07), PartnerHeatCoordinate(57.50, 12.07), PartnerHeatCoordinate(57.49, 12.09)), 5),
            ))
        }
        compose.setContent { KccTheme { PartnerTrafficHeatScreen(repo, "company", {}, mapEnabled = false) } }
        compose.onNodeWithText(text(R.string.partnerTraffic_limited)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.partnerTraffic_mapUnavailable)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.partnerTraffic_band1)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.partnerTraffic_areas)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { repo.changeTrafficAccount(null) }
        compose.onNodeWithText(text(R.string.partnerPortal_accessDenied)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.partnerTraffic_band1)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.partnerTraffic_areas)).assertDoesNotExist()
        assertEquals(1, repo.heatReads)
    }

    private fun capturePreview(filename: String) {
        if (android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), filename)
        java.io.FileOutputStream(file).use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun scannerRejectsUrlsWithoutOpeningOrSendingThem() {
        val repo = FakePortal()
        compose.setContent { KccTheme { PartnerRedemptionRoute(repo, null, {}) } }
        compose.onNodeWithText(text(R.string.partnerPortal_tokenInput)).performScrollTo().performTextInput("https://example.com/qr")
        compose.onNodeWithText(text(R.string.partnerPortal_inspect)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(0, repo.inspectCount)
        assertEquals(0, repo.redeemCount)
        compose.onNodeWithText(text(R.string.partnerPortal_notConfirmed)).performScrollTo().assertIsDisplayed()
    }
}
