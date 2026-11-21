package com.kungsbackacarcommunity.app.partners

import android.os.SystemClock
import androidx.compose.runtime.*
import java.time.Instant
import kotlinx.coroutines.CancellationException

internal const val PARTNER_HEAT_MAX_AREAS = 1000

data class PartnerHeatCoordinate(val latitude: Double, val longitude: Double)
data class PartnerHeatArea(val cellId: String, val latitude: Double, val longitude: Double, val boundary: List<PartnerHeatCoordinate>, val band: Int)
data class PartnerTrafficHeatSnapshot(val generatedAt: String?, val windowDays: Int, val expiresInSeconds: Int, val truncated: Boolean, val cells: List<PartnerHeatArea>)

internal fun parsePartnerTrafficHeat(data: Map<String, Any?>): PartnerTrafficHeatSnapshot {
    fun integer(value: Any?, range: IntRange): Int {
        val number = (value as? Number)?.toDouble() ?: error("Invalid heat value")
        require(number.isFinite() && number == number.toInt().toDouble() && number.toInt() in range)
        return number.toInt()
    }
    fun coordinate(row: Map<String, Any?>): PartnerHeatCoordinate {
        val lat = (row["latitude"] as? Number)?.toDouble() ?: error("Missing coordinate")
        val lon = (row["longitude"] as? Number)?.toDouble() ?: error("Missing coordinate")
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
        return PartnerHeatCoordinate(lat, lon)
    }
    val days = integer(data["windowDays"], 90..90)
    val expires = integer(data["expiresInSeconds"], 0..172800)
    require(data["generatedAt"] == null || data["generatedAt"] is String)
    val generated = data["generatedAt"] as? String
    generated?.let { Instant.parse(it) }
    val rows = data["cells"] as? List<*> ?: error("Missing heat areas")
    require(rows.size <= PARTNER_HEAT_MAX_AREAS)
    val cells = rows.map { value ->
        val row = value.portalMap() ?: error("Invalid heat area")
        val center = coordinate(row)
        val id = row["cellId"] as? String ?: error("Missing heat cell")
        require(id.matches(Regex("[0-9a-f]{15,16}")))
        val boundary = (row["boundary"] as? List<*>)?.map { coordinate(it.portalMap() ?: error("Invalid boundary")) } ?: error("Missing boundary")
        require(boundary.size in 3..10)
        PartnerHeatArea(id, center.latitude, center.longitude, boundary, integer(row["band"], 1..5))
    }
    require(cells.map { it.cellId }.toSet().size == cells.size)
    require(cells.isEmpty() || (generated != null && expires > 0))
    return PartnerTrafficHeatSnapshot(generated, days, expires, data["truncated"] as? Boolean ?: error("Missing coverage"), cells)
}

/** Memory-only, request-start monotonic expiry; late results cannot cross account/company boundaries. */
@Stable
internal class PartnerTrafficHeatState(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    var snapshot by mutableStateOf<PartnerTrafficHeatSnapshot?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<Int?>(null); private set
    var expired by mutableStateOf(false); private set
    private var generation = 0
    private var deadline = 0L
    private var snapshotAccount: String? = null
    private var snapshotCompany: String? = null
    fun clear() {
        generation++; snapshot = null; busy = false; error = null; expired = false
        deadline = 0L; snapshotAccount = null; snapshotCompany = null
    }
    fun expireIfNeeded() {
        if (snapshot?.cells?.isNotEmpty() == true && clock() >= deadline) { snapshot = null; expired = true }
    }
    suspend fun load(repository: PartnerPortalRepository, companyId: String) {
        val account = repository.trafficSessionKey
        if (account == null || snapshotAccount != account || snapshotCompany != companyId) clear()
        else { generation++; error = null; expireIfNeeded() }
        if (account == null) return
        val version = generation
        val started = clock()
        busy = true
        try {
            val result = repository.driveHeat(companyId)
            if (version != generation) return
            if (account != repository.trafficSessionKey) { clear(); return }
            deadline = started + result.expiresInSeconds * 1000L
            snapshotAccount = account; snapshotCompany = companyId
            snapshot = result; expired = false
            expireIfNeeded()
        } catch (cancelled: CancellationException) { if (version == generation) clear(); throw cancelled }
        catch (e: Exception) {
            if (version == generation) {
                clear()
                if (account == repository.trafficSessionKey) error = portalFailureMessage(e)
            }
        }
        finally { if (version == generation) busy = false }
    }
}
