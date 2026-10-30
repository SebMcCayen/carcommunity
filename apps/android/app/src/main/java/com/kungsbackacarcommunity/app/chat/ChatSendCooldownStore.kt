package com.kungsbackacarcommunity.app.chat

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/** A shared public send deadline per signed-in UID. No drafts, conversation IDs or disk writes. */
object ChatSendCooldowns {
    private val gates = ConcurrentHashMap<String, ChatSendCooldown>()
    fun forUser(uid: String): ChatSendCooldown {
        require(uid.isNotBlank())
        return gates.getOrPut(uid) { ChatSendCooldown { SystemClock.elapsedRealtime() } }
    }
}
