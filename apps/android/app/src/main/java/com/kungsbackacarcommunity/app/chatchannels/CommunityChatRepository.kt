package com.kungsbackacarcommunity.app.chatchannels

import kotlinx.coroutines.flow.Flow

/**
 * Community (app-wide) chat access. Reads are a live Firestore listener on
 * `communityChat/global/messages` (rules grant any active member the read);
 * sending, older-page pagination, and mark-read go through the member-gated
 * `communityChat-*` callables (europe-west1). Firebase-free interface so the
 * coordinator/screens are unit- and UI-testable with fakes (mirrors DmRepository).
 *
 * Unread is a lightweight per-user LAST-READ marker
 * (`userPrivate/{uid}.communityChatLastReadAt`, owner-only readable) rather than a
 * fan-out counter: [observeUnread] combines the newest-message listener with the
 * marker so the caller sees a dot when the newest message post-dates their marker.
 */
interface CommunityChatRepository {
    /** Live newest-window of the community channel, chronological. */
    fun observeMessages(): Flow<ChannelMessagesState>

    /**
     * Live "has unread" flag for [uid]: true while the newest message is from
     * someone else and newer than the caller's last-read marker. Emits false
     * once [markRead] runs (or while the channel is empty).
     */
    fun observeUnread(uid: String): Flow<Boolean>

    /** `communityChat-post` — posts [text] to the global channel. */
    suspend fun post(text: String): ChannelSendResult

    /** `communityChat-list` — an older page before the [before] ISO cursor. */
    suspend fun loadOlder(before: String): ChannelOlderResult

    /** `communityChat-markRead` — stamps the caller's last-read marker. Idempotent. */
    suspend fun markRead()
}
