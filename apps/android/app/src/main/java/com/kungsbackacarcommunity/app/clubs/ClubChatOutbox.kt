package com.kungsbackacarcommunity.app.clubs

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver

/** Submitted text only; history and unsent drafts still belong to the verified foreground room. */
internal class ClubChatOutbox(val uid: String) {
    var proofEpoch by mutableIntStateOf(0)
        private set
    internal var current: ClubRoomOutbox? = null
        private set

    /** Called once for a newly verified room, never to dispatch messages. */
    fun open(membership: ClubMembership): ClubRoomOutbox {
        val rows = current?.takeIf {
            it.clubId == membership.clubId && it.joinedAt == membership.joinedAt
        }?.pending?.value.orEmpty()
        // Detach the previous room: a late cancelled send cannot alter a resumed
        // room, a different membership, or a retry now using the same operation ID.
        return ClubRoomOutbox(membership.clubId, membership.joinedAt, rows.map {
            if (it.accepted) it else it.copy(failed = true)
        }).also { current = it }
    }

    fun clear() {
        current = null
        // Also recreate the private room when rapid access changes are conflated
        // into one rendered frame; it must never keep an obsolete room handle.
        proofEpoch++
    }

    fun restore(clubId: String, joinedAt: String, rows: List<ClubPendingSend>) {
        current = ClubRoomOutbox(clubId, joinedAt, rows)
    }
}

internal class ClubRoomOutbox(val clubId: String, val joinedAt: String, rows: List<ClubPendingSend>) {
    val pending = mutableStateOf(rows)
}

internal fun clubChatOutboxSaver(uid: String) = listSaver<ClubChatOutbox, String>(
    save = { owner ->
        val room = owner.current
        listOf(owner.uid, room?.clubId.orEmpty(), room?.joinedAt.orEmpty()) +
            (room?.let { clubPendingSendValues(it.pending.value) } ?: emptyList())
    },
    restore = { values ->
        ClubChatOutbox(uid).also { owner ->
            // Include the account in the payload as well as its stable Compose key.
            if (values.size >= 3 && values[0] == uid && values[1].isNotBlank() &&
                values[2].isNotBlank() && (values.size - 3) % 5 == 0) {
                val rows = runCatching { clubPendingSendSaver.restore(values.drop(3)) }.getOrNull()
                if (rows != null) owner.restore(values[1], values[2], rows)
            }
        }
    },
)
