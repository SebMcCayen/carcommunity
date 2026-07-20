package com.kungsbackacarcommunity.app.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which chat surface the member is looking at RIGHT NOW, if any.
 *
 * Purpose: not posting a notification for the conversation already on screen.
 * Buzzing someone about a message they are watching arrive is the fastest way
 * to make them disable notifications wholesale, which would also cost them the
 * ones they do want.
 *
 * Process-scoped because the consumer is [KccMessagingService], a service
 * component with no access to the Compose composition. Chat screens publish
 * their surface while composed and clear it on dispose, so backgrounding the
 * app (which disposes the composition) correctly re-enables notifications.
 *
 * Suppressing display does NOT suppress the in-app notification: the inbox item
 * is written by the backend regardless, so nothing is lost — the member simply
 * sees it in the app rather than as a banner.
 */
sealed interface ActiveChat {
    /** A 1:1 conversation with [otherUid]. */
    data class Dm(val otherUid: String) : ActiveChat

    /** The community channel. */
    data object Community : ActiveChat

    /** The chat channel of convoy [convoyId]. */
    data class Convoy(val convoyId: String) : ActiveChat
}

object ActiveChatRegistry {
    private val _active = MutableStateFlow<ActiveChat?>(null)

    val active: StateFlow<ActiveChat?> = _active.asStateFlow()

    /** Marks [chat] as on screen. */
    fun set(chat: ActiveChat) {
        _active.value = chat
    }

    /**
     * Clears [chat] if it is still the active one. Passing the expected value
     * avoids a disposing screen wiping the entry of the screen that replaced
     * it — Compose can compose the new screen before disposing the old.
     */
    fun clear(chat: ActiveChat) {
        _active.compareAndSet(chat, null)
    }

    /** Clears unconditionally (sign-out). */
    fun clear() {
        _active.value = null
    }

    /**
     * Whether a push for [link] would duplicate what is already on screen.
     *
     * Only chat targets can be duplicates. A friend request or convoy invite is
     * still worth a banner even while a chat is open — it is not the thing being
     * looked at.
     */
    fun suppresses(link: PushDeepLink): Boolean {
        val current = _active.value ?: return false
        return when (link.target) {
            PushTarget.DM -> current is ActiveChat.Dm && current.otherUid == link.entityId
            PushTarget.COMMUNITY_CHAT -> current is ActiveChat.Community
            PushTarget.CONVOY_CHAT ->
                current is ActiveChat.Convoy && current.convoyId == link.entityId
            else -> false
        }
    }
}
