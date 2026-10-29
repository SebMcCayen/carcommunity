package com.kungsbackacarcommunity.app.clubs

import com.kungsbackacarcommunity.app.chatchannels.ChannelMessage
import com.kungsbackacarcommunity.app.chatchannels.ChannelThread
import com.kungsbackacarcommunity.app.profile.LiveProfile

/** Presentation only: Club's authorization, paging cursor and wire model stay in Club. */
internal fun clubChatPresentation(
    messages: List<ClubMessage>,
    hiddenUids: Set<String>,
    profiles: Map<String, LiveProfile> = emptyMap(),
): List<ChannelMessage> = ChannelThread.hydrate(
    messages.asSequence()
        .filterNot { it.authorUid in hiddenUids }
        .distinctBy { it.id }
        .sortedWith(compareBy<ClubMessage> { it.createdAtMillis ?: Long.MAX_VALUE }.thenBy { it.id })
        .map { ChannelMessage(it.id, it.authorUid, it.text, it.authorName, null, it.createdAtMillis, null) }
        .toList(),
    profiles,
)
