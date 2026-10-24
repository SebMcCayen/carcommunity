package com.kungsbackacarcommunity.app.driverfeed

internal fun driverReadFailure(reason: String?, code: String): DriverFeedFailure = when (reason) {
    "canonical-onboarding-required" -> DriverFeedFailure.CANONICAL_ONBOARDING
    "driver-onboarding-required" -> DriverFeedFailure.ONBOARDING
    "account-restricted" -> DriverFeedFailure.RESTRICTED
    "thread-unavailable" -> DriverFeedFailure.UNAVAILABLE
    else -> when (code) {
        "UNAUTHENTICATED", "PERMISSION_DENIED" -> DriverFeedFailure.RESTRICTED
        else -> DriverFeedFailure.ERROR
    }
}

internal fun pagePayload(after: DriverCursor?): Map<String, Any> = buildMap {
    put("limit", DRIVER_FEED_PAGE_SIZE)
    if (after != null) put("cursor", mapOf("seconds" to after.seconds, "nanoseconds" to after.nanoseconds, "id" to after.id))
}

internal fun driverWireList(result: Map<*, *>, key: String): List<Map<*, *>> {
    val values = result[key] as? List<*> ?: driverMalformed()
    if (values.size > DRIVER_FEED_PAGE_SIZE) driverMalformed()
    return values.map { it as? Map<*, *> ?: driverMalformed() }
}

internal fun driverWireCursor(result: Map<*, *>): DriverCursor? {
    if (!result.containsKey("nextCursor")) driverMalformed()
    val raw = result["nextCursor"] ?: return null
    val value = raw as? Map<*, *> ?: driverMalformed()
    val seconds = integer(value["seconds"]) ?: driverMalformed()
    val nanos = integer(value["nanoseconds"]) ?: driverMalformed()
    val id = value["id"] as? String ?: driverMalformed()
    if (seconds !in -62135596800L..253402300799L || nanos !in 0..999999999 ||
        !Regex("^[A-Za-z0-9_-]{8,128}$").matches(id)) driverMalformed()
    return DriverCursor(seconds, nanos.toInt(), id)
}

private fun integer(value: Any?): Long? {
    val number = value as? Number ?: return null
    return number.toDouble().takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
}

internal fun driverMalformed(): Nothing = throw DriverFeedException(DriverFeedFailure.ERROR)
