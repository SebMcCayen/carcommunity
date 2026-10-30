package com.kungsbackacarcommunity.app.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** One private composer attempt. A server row may beat the callable response. */
internal class ChatSendAcknowledgement {
    private data class Pending(val id: String, val accepted: () -> Unit)
    private var pending by mutableStateOf<Pending?>(null)
    val pendingId: String? get() = pending?.id

    fun track(id: String, accepted: () -> Unit) {
        pending = Pending(id, accepted)
    }

    fun acknowledge(id: String) {
        val attempt = pending?.takeIf { it.id == id } ?: return
        // Consume before calling: a later RPC response must not clear a newer draft.
        pending = null
        attempt.accepted()
    }
}

/** IDs must come from this room's OWN delivered rows, never Sending/Failed bubbles. */
@Composable
internal fun rememberChatSendAcknowledgement(deliveredIds: Set<String>): ChatSendAcknowledgement {
    val acknowledgement = remember { ChatSendAcknowledgement() }
    val pendingId = acknowledgement.pendingId
    LaunchedEffect(deliveredIds, pendingId) {
        if (pendingId != null && pendingId in deliveredIds) acknowledgement.acknowledge(pendingId)
    }
    return acknowledgement
}
