package com.kungsbackacarcommunity.app.partners

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.firebase.await

/** Server-owned data. No customer identity is exposed in business models. */
data class PartnerWorkspaceCompany(
    val companyId: String, val name: String, val description: String, val role: String,
    val status: String, val logoPath: String?, val phone: String?, val website: String?,
)
data class PartnerApplicationSummary(
    val applicationId: String, val status: String, val reviewReason: String?,
    val fields: Map<String, Any?>,
)
data class PartnerWorkspace(val companies: List<PartnerWorkspaceCompany>, val application: PartnerApplicationSummary?) {
    val activeCompanies: List<PartnerWorkspaceCompany> get() = companies.filter { it.status == "active" }
}
data class ManagedPartnerOffer(
    val offerId: String, val companyId: String, val title: String, val teaserText: String,
    val description: String, val terms: String, val status: String, val capacity: Int?,
    val reservedCount: Int, val redeemedCount: Int, val reservationDays: Int,
    val percentageDiscount: Int?, val availableFrom: String?, val availableUntil: String?,
    val reservationsPaused: Boolean, val version: Int, val remainingCount: Int?,
)
data class PartnerReservation(
    val reservationId: String, val offerId: String, val companyId: String,
    val companyName: String, val title: String, val terms: String, val status: String,
    val expiresAt: String, val redeemedAt: String?,
)
data class PartnerReservationToken(val token: String, val expiresAt: String)
data class PartnerOfferReservationDetail(val offer: ManagedPartnerOffer, val reservation: PartnerReservation?, val alreadyUsed: Boolean = false)
data class PartnerAggregateOffer(val offerId: String, val title: String, val totalRedeemed: Long)
data class PartnerAggregateStatistics(val updatedAt: String, val totalRedeemed: Long, val offers: List<PartnerAggregateOffer>)

interface PartnerPortalRepository {
    val trafficSessionKey: String? get() = null
    fun observeTrafficSession(onChange: (String?) -> Unit): AutoCloseable = AutoCloseable { }
    suspend fun driveHeat(companyId: String): PartnerTrafficHeatSnapshot = error("Traffic heat unavailable")
    suspend fun workspace(): PartnerWorkspace
    suspend fun offers(companyId: String): List<ManagedPartnerOffer>
    suspend fun submitOffer(companyId: String, fields: Map<String, Any?>)
    suspend fun pause(companyId: String, offerId: String, paused: Boolean)
    suspend fun updateProfile(companyId: String, description: String, phone: String, website: String)
    suspend fun uploadLogo(companyId: String, base64: String)
    suspend fun statistics(companyId: String, period: String): PartnerAggregateStatistics
    suspend fun offer(offerId: String): PartnerOfferReservationDetail
    suspend fun reserve(offerId: String, requestId: String, expectedVersion: Int): PartnerReservation
    suspend fun reservations(): List<PartnerReservation>
    suspend fun cancel(reservationId: String): PartnerReservation
    suspend fun token(reservationId: String): PartnerReservationToken
    suspend fun inspect(token: String): PartnerReservation
    suspend fun redeem(token: String, requestId: String): PartnerReservation
}

class FirebasePartnerPortalRepository private constructor(private val functions: FirebaseFunctions) : PartnerPortalRepository {
    private suspend fun call(action: String, fields: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val result = functions.getHttpsCallable("partners-portal").call(fields + ("action" to action)).await().data
        return requireNotNull(result.portalMap()) { "Invalid partner response" }
    }
    override val trafficSessionKey: String? get() = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
    override fun observeTrafficSession(onChange: (String?) -> Unit): AutoCloseable {
        val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
        val listener = com.google.firebase.auth.FirebaseAuth.AuthStateListener { onChange(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        return AutoCloseable { auth.removeAuthStateListener(listener) }
    }
    override suspend fun driveHeat(companyId: String) = parsePartnerTrafficHeat(call("driveHeat", mapOf("companyId" to companyId)))
    override suspend fun workspace(): PartnerWorkspace {
        val data = call("workspace")
        return PartnerWorkspace(data.maps("companies").map { row ->
            PartnerWorkspaceCompany(row.text("companyId"), row.text("name"), row.text("description"), row.text("role"),
                row.text("status"), row.optionalText("logoPath"), row.optionalText("phone"), row.optionalText("website"))
        }, data["application"].portalMap()?.let { PartnerApplicationSummary(it.text("applicationId"), it.text("status"), it.optionalText("reviewReason"), it) })
    }
    override suspend fun offers(companyId: String) = call("listOffers", mapOf("companyId" to companyId)).maps("offers").map(::parseManagedOffer)
    override suspend fun submitOffer(companyId: String, fields: Map<String, Any?>) { call("submitOffer", fields + ("companyId" to companyId)) }
    override suspend fun pause(companyId: String, offerId: String, paused: Boolean) { call("pauseReservations", mapOf("companyId" to companyId, "offerId" to offerId, "paused" to paused)) }
    override suspend fun updateProfile(companyId: String, description: String, phone: String, website: String) {
        call("updateProfile", mapOf("companyId" to companyId, "description" to description, "phone" to phone.ifBlank { null }, "website" to website.ifBlank { null }))
    }
    override suspend fun uploadLogo(companyId: String, base64: String) { call("uploadLogo", mapOf("companyId" to companyId, "logoBase64" to base64, "logoRightsConfirmed" to true)) }
    override suspend fun statistics(companyId: String, period: String): PartnerAggregateStatistics {
        val data = call("statistics", mapOf("companyId" to companyId, "period" to period))
        return PartnerAggregateStatistics(data.text("updatedAt"), data.number("totalRedeemed"),
            data.maps("partners").flatMap { it.maps("offers") }.map { PartnerAggregateOffer(it.text("offerId"), it.text("title"), it.number("totalRedeemed")) })
    }
    override suspend fun offer(offerId: String): PartnerOfferReservationDetail {
        val data = call("offer", mapOf("offerId" to offerId))
        return parseOfferReservationDetail(data)
    }
    override suspend fun reserve(offerId: String, requestId: String, expectedVersion: Int) = parseReservation(call("reserve", mapOf("offerId" to offerId, "requestId" to requestId, "expectedVersion" to expectedVersion)))
    override suspend fun reservations() = call("reservations").maps("reservations").map(::parseReservation)
    override suspend fun cancel(reservationId: String) = parseReservation(call("cancel", mapOf("reservationId" to reservationId)))
    override suspend fun token(reservationId: String): PartnerReservationToken {
        val data = call("token", mapOf("reservationId" to reservationId))
        return PartnerReservationToken(data.text("token"), data.text("expiresAt"))
    }
    override suspend fun inspect(token: String) = parseReservation(requireNotNull(call("inspect", mapOf("token" to token))["reservation"].portalMap()))
    override suspend fun redeem(token: String, requestId: String) = parseReservation(requireNotNull(call("redeem", mapOf("token" to token, "requestId" to requestId))["reservation"].portalMap()))
    companion object {
        fun createIfAvailable(context: Context): PartnerPortalRepository? =
            if (FirebaseApp.getApps(context).isEmpty()) null else FirebasePartnerPortalRepository(FirebaseFunctions.getInstance("europe-west1"))
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.portalMap(): Map<String, Any?>? = this as? Map<String, Any?>
internal fun Map<String, Any?>.text(key: String): String = this[key] as? String ?: ""
internal fun Map<String, Any?>.optionalText(key: String): String? = (this[key] as? String)?.takeIf(String::isNotBlank)
internal fun Map<String, Any?>.number(key: String): Long = (this[key] as? Number)?.toLong() ?: 0
internal fun Map<String, Any?>.maps(key: String): List<Map<String, Any?>> = (this[key] as? List<*>)?.mapNotNull { it.portalMap() }.orEmpty()
internal fun parseManagedOffer(row: Map<String, Any?>) = ManagedPartnerOffer(
    row.text("offerId"), row.text("companyId"), row.text("title"), row.text("teaserText"), row.text("description"), row.text("terms"), row.text("status"),
    (row["capacity"] as? Number)?.toInt(), row.number("reservedCount").toInt(), row.number("redeemedCount").toInt(),
    (row["reservationDays"] as? Number)?.toInt() ?: 7, (row["percentageDiscount"] as? Number)?.toInt(),
    row.optionalText("availableFrom"), row.optionalText("availableUntil"), row["reservationsPaused"] == true, row.number("version").toInt(), (row["remainingCount"] as? Number)?.toInt(),
)
internal fun parseReservation(row: Map<String, Any?>) = PartnerReservation(row.text("reservationId"), row.text("offerId"), row.text("companyId"),
    row.text("companyName"), row.text("title"), row.text("terms"), row.text("status"), row.text("expiresAt"), row.optionalText("redeemedAt"))
internal fun parseOfferReservationDetail(data: Map<String, Any?>) = PartnerOfferReservationDetail(
    parseManagedOffer(requireNotNull(data["offer"].portalMap())),
    data["reservation"].portalMap()?.let(::parseReservation),
    data["alreadyUsed"] == true,
)
/** Remaining visual QR lifetime uses monotonic elapsed time, never the phone's date. */
internal fun partnerQrSecondsRemaining(deadlineElapsed: Long, nowElapsed: Long): Int =
    ((deadlineElapsed - nowElapsed) / 1000).coerceIn(0L, 60L).toInt()
