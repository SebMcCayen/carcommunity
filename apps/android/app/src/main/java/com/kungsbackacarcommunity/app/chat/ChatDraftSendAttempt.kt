package com.kungsbackacarcommunity.app.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import java.util.UUID

/** Saved beside the existing private draft, never globally or on disk. No automatic send on restore. */
class ChatDraftSendAttempt private constructor(private var signature: String?, private var clientId: String?) {
    constructor() : this(null, null)
    fun idFor(text: String, replyId: String? = null, mentions: List<String> = emptyList()): String {
        // Length prefixes make arbitrary message characters unambiguous.
        val next = (listOf(text.trim(), replyId.orEmpty()) + mentions).joinToString("") { "${it.length}:$it" }
        if (next != signature || clientId == null) { signature = next; clientId = UUID.randomUUID().toString() }
        return checkNotNull(clientId)
    }
    fun owns(id: String?): Boolean = id != null && id == clientId
    fun acknowledge(id: String?) { if (id != null && id == clientId) { signature = null; clientId = null } }
    companion object {
        val saver = Saver<ChatDraftSendAttempt, List<String>>(
            save = { listOf(it.signature.orEmpty(), it.clientId.orEmpty()) },
            restore = { ChatDraftSendAttempt(it[0].ifEmpty { null }, it[1].ifEmpty { null }) },
        )
    }
}

@Composable
fun rememberChatDraftSendAttempt(): ChatDraftSendAttempt = rememberSaveable(saver = ChatDraftSendAttempt.saver) { ChatDraftSendAttempt() }
