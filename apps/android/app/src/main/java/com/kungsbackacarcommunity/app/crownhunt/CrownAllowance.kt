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
            fun integer(key: String): Int? {
                val n = (data[key] as? Number)?.toDouble() ?: return null
                return n.takeIf { it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()
            }
            val cap = integer("cap")?.takeIf { it > 0 } ?: return null
            val remaining = integer("remaining")?.takeIf { it <= cap } ?: return null
            val reset = runCatching { Instant.parse(data["resetsAt"] as? String) }.getOrNull() ?: return null
            return CrownAllowance(cap, remaining, reset)
        }
    }
}
