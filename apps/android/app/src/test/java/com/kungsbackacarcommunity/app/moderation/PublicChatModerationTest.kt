package com.kungsbackacarcommunity.app.moderation

import org.junit.Assert.*
import org.junit.Test

class PublicChatModerationTest {
    private val context = PublicChatModerationContext("admin", "member", "version", true, true, true)
    private val target = PublicChatTarget("community", "message", expectedAuthorUid = "member")

    @Test fun removalAllowsAllFourIndependentOptionalCombinationsAndNoImplicitWarning() {
        assertFalse(ChatModerationDraft(ChatAdminAction.Remove).warn)
        assertFalse(ChatModerationDraft(ChatAdminAction.Remove).pauses)
        for (warn in listOf(false, true)) for (pause in listOf(false, true)) {
            val draft = ChatModerationDraft(ChatAdminAction.Remove, "  Reason  ", warn, pause, "2", ChatPauseUnit.Days)
            assertTrue(draft.isValid(context))
            val payload = draft.payload(target, context, "operation-id")
            assertEquals("remove", payload["action"])
            assertEquals(warn, payload["warn"])
            assertEquals("Reason", payload["reason"])
            assertEquals(if (pause) mapOf("amount" to 2, "unit" to "days") else null, payload["pause"])
            assertFalse((payload["target"] as Map<*, *>).containsKey("expectedAuthorUid"))
        }
    }

    @Test fun standalonePauseRequiresDurationAndNeverDeletes() {
        assertFalse(ChatModerationDraft(ChatAdminAction.Pause, "Reason").isValid(context))
        val payload = ChatModerationDraft(ChatAdminAction.Pause, "Reason", amount = "30")
            .payload(target, context, "operation-id")
        assertEquals("pause", payload["action"])
        assertEquals(mapOf("amount" to 30, "unit" to "minutes"), payload["pause"])
        assertEquals(false, payload["warn"])
    }

    @Test fun durationsRejectAmbiguityOverflowAndValuesBeyondServerMaximum() {
        for (unit in ChatPauseUnit.entries) {
            for (invalid in listOf("", "0", "-1", "+1", "1.5", "one", " 1", "1e3", "9999999999999999999999")) {
                assertNull("$invalid $unit", ChatModerationDraft(ChatAdminAction.Pause, "Reason", amount = invalid, unit = unit).duration(30))
            }
        }
        assertEquals(30, ChatModerationDraft(ChatAdminAction.Pause, amount = "30", unit = ChatPauseUnit.Days).duration(30))
        assertNull(ChatModerationDraft(ChatAdminAction.Pause, amount = "31", unit = ChatPauseUnit.Days).duration(30))
        assertEquals(43200, ChatModerationDraft(ChatAdminAction.Pause, amount = "43200").duration(30))
        assertNull(ChatModerationDraft(ChatAdminAction.Pause, amount = "43201").duration(30))
    }

    @Test fun protectedTargetCanBeRemovedButCannotBeWarnedOrPaused() {
        val protected = context.copy(canWarn = false, canPause = false)
        assertTrue(ChatModerationDraft(ChatAdminAction.Remove, "Reason").isValid(protected))
        assertFalse(ChatModerationDraft(ChatAdminAction.Remove, "Reason", warn = true).isValid(protected))
        assertFalse(ChatModerationDraft(ChatAdminAction.Remove, "Reason", addPause = true, amount = "1").isValid(protected))
        assertFalse(ChatModerationDraft(ChatAdminAction.Pause, "Reason", amount = "1").isValid(protected))
        assertFalse(ChatModerationDraft(ChatAdminAction.Remove, " ").isValid(context))
        assertFalse(ChatModerationDraft(ChatAdminAction.Remove, "x".repeat(501)).isValid(context))
    }

    @Test fun targetCannotAddressPrivateCollectionsOrInjectPaths() {
        for (source in listOf("dm", "convoy", "club", "community/other")) {
            assertThrows(IllegalArgumentException::class.java) { PublicChatTarget(source, "message") }
        }
        assertThrows(IllegalArgumentException::class.java) { PublicChatTarget("event", "message") }
        assertThrows(IllegalArgumentException::class.java) { PublicChatTarget("community", "message", "event") }
        assertThrows(IllegalArgumentException::class.java) { PublicChatTarget("community", "../message") }
        assertEquals(mapOf("source" to "event", "eventId" to "event", "messageId" to "message"),
            PublicChatTarget("event", "message", "event").payload())
    }

    @Test fun reviewPreservesAnExistingLongerPauseAndUsesServerTime() {
        val existing = context.copy(serverNowMillis = 1_000_000, observedElapsedMillis = 1000, pauseUntilMillis = 10_000_000)
        val short = ChatModerationDraft(ChatAdminAction.Pause, "Reason", amount = "1")
        assertEquals(10_000_000L, previewPublicPauseUntil(existing, short, 2000))
        assertEquals(1_061_000L, previewPublicPauseUntil(existing.copy(pauseUntilMillis = null), short, 2000))
        assertNull(previewPublicPauseUntil(existing, ChatModerationDraft(ChatAdminAction.Remove, "Reason"), 2000))
    }

    @Test fun serverTimeAnchorsExpiryWithoutConsultingDeviceWallClock() {
        val deadline = publicChatPauseDeadline(1000, 5_060_000, 5_000_000)
        val pause = PublicChatWritePause(5_060_000, "Reason", deadline)
        assertEquals(61000, deadline)
        assertTrue(pause.activeAt(60999))
        assertFalse(pause.activeAt(61000))
        assertFalse(pause.activeAt(61001))
        assertEquals(1000, publicChatPauseDeadline(1000, 4_000_000, 5_000_000))
    }
}
