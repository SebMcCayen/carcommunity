package com.kungsbackacarcommunity.app.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.StateFlow

/**
 * Saved outgoing rows, independent of the next composer draft. Own this inside the
 * existing account/room key. Bind immediately after remembering the coordinator.
 * Saving reads the flow synchronously, including changes not yet collected by Compose.
 * This holder never dispatches, retries, or mutates a coordinator.
 */
class ChatPendingOutbox<Message>(restored: List<Message> = emptyList()) {
    private val restoredRows = restored.toList()
    private var source: StateFlow<List<Message>>? = null

    /** Read only when constructing a coordinator, never replay into an existing one. */
    val initialPending: List<Message> get() = source?.value ?: restoredRows

    fun bind(pendingMessages: StateFlow<List<Message>>) { source = pendingMessages }

    companion object {
        fun <Message, Saved : Any> saver(
            messageSaver: Saver<Message, Saved>,
        ): Saver<ChatPendingOutbox<Message>, ArrayList<Saved>> = Saver(
            save = { outbox ->
                val rows = ArrayList<Saved>()
                for (message in outbox.initialPending) {
                    val saved = with(messageSaver) { save(message) } ?: return@Saver null
                    rows.add(saved)
                }
                rows
            },
            restore = { rows ->
                val messages = rows.map { messageSaver.restore(it) ?: return@Saver null }
                ChatPendingOutbox(messages)
            },
        )
    }
}

@Composable
fun <Message, Saved : Any> rememberChatPendingOutbox(
    messageSaver: Saver<Message, Saved>,
): ChatPendingOutbox<Message> = rememberSaveable(saver = ChatPendingOutbox.saver(messageSaver)) {
    ChatPendingOutbox<Message>()
}
