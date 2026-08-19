package com.kungsbackacarcommunity.app.convoy

/**
 * The three convoy reactions a member can broadcast — the digital replacement for
 * flashing your lights. [wire] is the exact string the `convoy-sendReaction`
 * callable and the reaction document use (kept in lockstep with the backend's
 * reaction-core CONVOY_REACTION_KINDS), matched back on the receiving client to
 * pick the icon/caption.
 */
enum class ConvoyReactionKind(val wire: String) {
    /** "Police ahead" alert — the rate-limited, anti-spam one. */
    Police("police"),

    /** Hello / goodbye — the flash-your-lights greeting. */
    HelloGoodbye("hello"),

    /** "Follow me". */
    FollowMe("follow_me"),
    ;

    companion object {
        /** The kind for a stored wire value, or null for an unknown/legacy value. */
        fun fromWire(wire: String?): ConvoyReactionKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * CLIENT MIRROR of the server-enforced anti-spam cooldown windows (backend
 * reaction-core REACTION_COOLDOWN_MS). The SERVER is the source of truth — it
 * refuses a spammed send regardless of the client — but the buttons grey
 * themselves for the same window so a member sees the cooldown rather than
 * tapping into a rejection. Police is the strict 60s anti-spam window; the social
 * taps are shorter.
 */
val CONVOY_REACTION_COOLDOWN_MS: Map<ConvoyReactionKind, Long> =
    mapOf(
        ConvoyReactionKind.Police to 60_000L,
        ConvoyReactionKind.HelloGoodbye to 15_000L,
        ConvoyReactionKind.FollowMe to 30_000L,
    )

/** The cooldown window for [kind], in milliseconds. */
fun convoyReactionCooldownMs(kind: ConvoyReactionKind): Long =
    CONVOY_REACTION_COOLDOWN_MS.getValue(kind)

/**
 * PURE, immutable per-kind cooldown tracker for the reaction buttons — no Compose,
 * no clock of its own, so the anti-spam decision (is a kind ready? how long left?)
 * is unit-testable ([ConvoyReactionTest]). Every transition returns a NEW state.
 *
 * It tracks, per kind, the earliest instant a member may next send. A local send
 * bumps it forward by the client mirror window; a server refusal
 * (resource-exhausted with retryAfterMs) overrides it with the server's exact
 * remaining time — the server always wins.
 */
data class ConvoyReactionCooldownState(
    private val readyAtMs: Map<ConvoyReactionKind, Long> = emptyMap(),
) {
    /** True when [kind] may be sent at [nowMs] (never sent, or its window elapsed). */
    fun isReady(kind: ConvoyReactionKind, nowMs: Long): Boolean = remainingMs(kind, nowMs) <= 0L

    /** Milliseconds until [kind] may next be sent at [nowMs]; 0 when ready now. */
    fun remainingMs(kind: ConvoyReactionKind, nowMs: Long): Long {
        val readyAt = readyAtMs[kind] ?: return 0L
        val remaining = readyAt - nowMs
        return if (remaining > 0L) remaining else 0L
    }

    /** Records a local send of [kind] at [atMs], starting its client-mirror window. */
    fun recordSent(kind: ConvoyReactionKind, atMs: Long): ConvoyReactionCooldownState =
        copy(readyAtMs = readyAtMs + (kind to atMs + convoyReactionCooldownMs(kind)))

    /**
     * Applies the server's authoritative remaining time for [kind] after a
     * resource-exhausted refusal — the server knows the true window (e.g. after a
     * send from another device), so it overrides the local estimate. A
     * non-positive [retryAfterMs] clears the cooldown (the server says "send now").
     */
    fun applyServerCooldown(
        kind: ConvoyReactionKind,
        retryAfterMs: Long,
        nowMs: Long,
    ): ConvoyReactionCooldownState =
        if (retryAfterMs <= 0L) {
            clear(kind)
        } else {
            copy(readyAtMs = readyAtMs + (kind to nowMs + retryAfterMs))
        }

    /** Clears [kind]'s cooldown (e.g. after a failed send that never reached the server). */
    fun clear(kind: ConvoyReactionKind): ConvoyReactionCooldownState =
        copy(readyAtMs = readyAtMs - kind)
}

/**
 * The `convoy-sendReaction` callable payload for [kind] in [convoyId]. [clientId]
 * is the idempotency key (a retry replays the same reaction rather than
 * double-popping receivers); omitted when null. Pure, so the wire shape is
 * unit-testable.
 */
fun convoySendReactionPayload(
    convoyId: String,
    kind: ConvoyReactionKind,
    clientId: String? = null,
): Map<String, Any> =
    buildMap {
        put("convoyId", convoyId)
        put("kind", kind.wire)
        if (clientId != null) put("clientId", clientId)
    }
