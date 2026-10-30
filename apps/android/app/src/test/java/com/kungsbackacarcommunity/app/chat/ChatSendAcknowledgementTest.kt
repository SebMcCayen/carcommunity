package com.kungsbackacarcommunity.app.chat

import org.junit.Assert.*
import org.junit.Test

class ChatSendAcknowledgementTest {
    @Test fun liveDeliveryThenRpcConsumesOnlyOneAcknowledgement() {
        val acknowledgement = ChatSendAcknowledgement()
        var accepted = 0
        acknowledgement.track("attempt") { accepted++ }
        acknowledgement.acknowledge("attempt")
        acknowledgement.acknowledge("attempt")
        assertEquals(1, accepted)
        assertNull(acknowledgement.pendingId)
    }

    @Test fun delayedOldResponseCannotConsumeNewAttempt() {
        val acknowledgement = ChatSendAcknowledgement()
        val accepted = mutableListOf<String>()
        acknowledgement.track("old") { accepted += "old" }
        acknowledgement.acknowledge("old")
        acknowledgement.track("new") { accepted += "new" }
        acknowledgement.acknowledge("old")
        assertEquals("new", acknowledgement.pendingId)
        assertEquals(listOf("old"), accepted)
        acknowledgement.acknowledge("new")
        assertEquals(listOf("old", "new"), accepted)
    }

    @Test fun unrelatedDeliveredIdLeavesPrivateAttemptPending() {
        val acknowledgement = ChatSendAcknowledgement()
        acknowledgement.track("mine") { fail("Unrelated row must not acknowledge") }
        acknowledgement.acknowledge("other")
        assertEquals("mine", acknowledgement.pendingId)
    }
}
