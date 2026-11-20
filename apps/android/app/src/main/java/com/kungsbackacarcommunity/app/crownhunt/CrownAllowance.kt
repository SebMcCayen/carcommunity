package com.kungsbackacarcommunity.app.crownhunt

import java.time.Instant

/** Server snapshot at collection time. Never used to authorize a claim locally. */
data class CrownAllowance(
    val cap: Int,
    val remaining: Int,
    val resetsAt: Instant,
) {
    companion object {
        fun fromWire(value: Any?): CrownAllowance? {
            val data = value as? Map<*, *> ?: return null
            val cap = crownReceiptInteger(data["cap"])?.takeIf { it == 2250 || it == 3000 } ?: return null
            val remaining = crownReceiptInteger(data["remaining"])?.takeIf { it <= cap } ?: return null
            val reset = runCatching { Instant.parse(data["resetsAt"] as? String) }.getOrNull() ?: return null
            return CrownAllowance(cap, remaining, reset)
        }
    }
}

/** Optional receipt amounts are omitted when malformed, never truncated or fabricated. */
internal fun crownReceiptInteger(raw: Any?): Int? {
    val value = (raw as? Number)?.toDouble() ?: return null
    return value.takeIf { it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()
}
