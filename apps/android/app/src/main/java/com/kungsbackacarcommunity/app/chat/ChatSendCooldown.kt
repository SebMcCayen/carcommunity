package com.kungsbackacarcommunity.app.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val CHAT_MAX_RETRY_AFTER_MS = 600_000L
private val CHAT_MONOTONIC_ORIGIN = System.nanoTime()

/** Validated public callable metadata; never retains the error message or chat content. */
data class ChatRateLimit(val retryAfterMs: Long, val blockedUntilMillis: Long? = null, val serverNowMillis: Long? = null)

object ChatRateLimits {
    fun parse(code: String?, details: Any?): ChatRateLimit? {
        if (code?.removePrefix("functions/")?.lowercase()?.replace('_', '-') != "resource-exhausted") return null
        val fields = details as? Map<*, *> ?: return null
        if (fields["reason"] != "chat-rate-limited") return null
        val retry = integer(fields["retryAfterMs"]) ?: return null
        val until = integer(fields["blockedUntilMillis"])
        val now = integer(fields["serverNowMillis"])
        val candidate = ChatRateLimit(retry, until, now)
        val trusted = candidate.trustedServerDeadline() != null
        return ChatRateLimit(retry.coerceAtMost(CHAT_MAX_RETRY_AFTER_MS),
            until.takeIf { trusted }, now.takeIf { trusted })
    }

    private fun integer(value: Any?): Long? {
        val n = (value as? Number)?.toDouble() ?: return null
        // Callable JSON numbers must be finite, safe nonnegative integer milliseconds.
        if (!n.isFinite() || n < 0 || n > 9_007_199_254_740_991.0 || n % 1.0 != 0.0) return null
        return n.toLong()
    }
}

class ChatRateLimitedException(val rateLimit: ChatRateLimit) : Exception("chat-rate-limited")

/** A trusted pair must describe exactly this bounded relative duration, including an expired zero. */
internal fun ChatRateLimit.trustedServerDeadline(): Long? {
    val until = blockedUntilMillis ?: return null
    val now = serverNowMillis ?: return null
    if (until !in 0L..9_007_199_254_740_991L || now !in 0L..9_007_199_254_740_991L) return null
    if (retryAfterMs !in 0L..CHAT_MAX_RETRY_AFTER_MS || until < now || until - now != retryAfterMs) return null
    return until
}

/** Pure monotonic gate. Expiry only enables a MANUAL send; it never dispatches anything. */
class ChatSendCooldown(private val elapsedMillis: () -> Long = { (System.nanoTime() - CHAT_MONOTONIC_ORIGIN) / 1_000_000 }) {
    private val deadline = MutableStateFlow(0L)
    // Retain this public server pause identity even after its LOCAL timer expires.
    // No content/recipient/room data. The lock serializes identity and local deadline together.
    private var highestServerDeadline: Long? = null
    val deadlineMillis: StateFlow<Long> = deadline.asStateFlow()
    fun remainingMillis(): Long = if (deadline.value == 0L) 0L else (deadline.value - elapsedMillis()).coerceIn(0, CHAT_MAX_RETRY_AFTER_MS)
    fun remainingSeconds(): Int = ((remainingMillis() + 999) / 1000).toInt()
    fun isBlocked(): Boolean = remainingMillis() > 0

    @Synchronized fun apply(limit: ChatRateLimit) {
        val serverDeadline = limit.trustedServerDeadline()
        if (serverDeadline != null) {
            val known = highestServerDeadline
            if (known != null && serverDeadline <= known) return
            highestServerDeadline = serverDeadline
        }
        // With no trusted absolute pair, only the bounded relative contract is available.
        val delay = limit.retryAfterMs.coerceIn(0, CHAT_MAX_RETRY_AFTER_MS)
        if (delay == 0L) return
        val now = elapsedMillis()
        val until = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
        deadline.value = maxOf(deadline.value, until)
    }
}
