package com.kungsbackacarcommunity.app.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where tapping a push should land.
 *
 * Mirrors PUSH_DEEP_LINK_TARGETS in
 * functions/src/notifications/notifications-core.ts. Every value names a screen
 * the shell ALREADY has (a ShellRoute, or a tab within the chat hub) — this is
 * a vocabulary for existing destinations, not a second navigation graph. The
 * actual navigation is performed by the shell in the usual way; see
 * [PushNavigator].
 */
enum class PushTarget(val wire: String) {
    /** A 1:1 conversation. [PushDeepLink.entityId] is the OTHER member's uid. */
    DM("dm"),

    /** The community channel (no per-message anchor exists). */
    COMMUNITY_CHAT("community_chat"),

    /** A convoy's chat channel. [PushDeepLink.entityId] is the convoy id. */
    CONVOY_CHAT("convoy_chat"),

    /** The convoy list (where a pending invite is acted on). */
    CONVOYS("convoys"),

    /** Friends, including incoming requests. */
    FRIENDS("friends"),

    /** The events list. [PushDeepLink.entityId] is the event id when known. */
    EVENT("event"),

    SUBSCRIPTION("subscription"),

    /** The in-app inbox — also the fallback for anything unrecognised. */
    NOTIFICATIONS("notifications"),
    ;

    companion object {
        /** Unknown/absent targets degrade to the inbox rather than failing. */
        fun fromWire(value: String?): PushTarget =
            entries.firstOrNull { it.wire == value } ?: NOTIFICATIONS
    }
}

/** A tap destination: a target plus the entity it needs (null when none). */
data class PushDeepLink(
    val target: PushTarget,
    val entityId: String? = null,
)

/**
 * Hand-off point between a tapped notification and the Compose shell.
 *
 * The shell navigates by an enum `ShellRoute` plus per-route payload state held
 * inside `AuthenticatedApp` — there is no NavHost and therefore no URI to
 * dispatch to. A notification tap arrives on `MainActivity` as an Intent, which
 * is outside that composition. This object is the seam: the Activity parks the
 * decoded link here, and the shell collects it and drives its OWN existing
 * navigation with it. No route is duplicated and no parallel navigation stack
 * exists — the shell remains the only thing that navigates.
 *
 * Process-scoped on purpose: the Activity may publish before the shell is
 * composed (cold start from a notification), so the link has to survive until
 * something is there to consume it. [consume] clears it so a configuration
 * change cannot replay a navigation the member already performed.
 */
object PushNavigator {
    private val _pending = MutableStateFlow<PushDeepLink?>(null)

    /** The link awaiting handling, or null. */
    val pending: StateFlow<PushDeepLink?> = _pending.asStateFlow()

    /** Parks a link for the shell. A newer tap replaces an unconsumed one. */
    fun publish(link: PushDeepLink) {
        _pending.value = link
    }

    /** Takes the pending link (if any), clearing it so it fires exactly once. */
    fun consume(): PushDeepLink? = _pending.getAndSet(null)

    /**
     * Drops any pending link. Called on sign-out so a link decoded for the
     * previous member cannot navigate the next one.
     */
    fun clear() {
        _pending.value = null
    }

    private fun <T> MutableStateFlow<T>.getAndSet(newValue: T): T {
        while (true) {
            val current = value
            if (compareAndSet(current, newValue)) return current
        }
    }
}
