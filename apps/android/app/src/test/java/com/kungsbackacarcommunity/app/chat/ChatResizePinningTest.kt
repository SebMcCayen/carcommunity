package com.kungsbackacarcommunity.app.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatResizePinningTest {
    private val bottom = ChatListResizeSnapshot(400, 20, "msg-last", 80, atBottom = true, userScrolling = false)

    @Test fun multilineComposerKeepsPreviouslyPinnedMessageVisible() {
        assertTrue(ChatListPinning.shouldRepinAfterResize(bottom, bottom.copy(viewportHeight = 340, atBottom = false)))
    }

    @Test fun wrappedFailureLabelKeepsTheSameOutgoingRowVisible() {
        assertTrue(ChatListPinning.shouldRepinAfterResize(bottom, bottom.copy(newestHeight = 120, atBottom = false)))
    }

    @Test fun resizingDoesNotMoveAReaderWhoWasBrowsingHistory() {
        assertFalse(ChatListPinning.shouldRepinAfterResize(bottom.copy(atBottom = false), bottom.copy(viewportHeight = 340, atBottom = false)))
    }

    @Test fun resizeNeverInterruptsUserDragOrFling() {
        val resized = bottom.copy(viewportHeight = 340, atBottom = false)
        assertFalse(ChatListPinning.shouldRepinAfterResize(bottom.copy(userScrolling = true), resized))
        assertFalse(ChatListPinning.shouldRepinAfterResize(bottom, resized.copy(userScrolling = true)))
    }

    @Test fun messageReplacementAndPaginationRemainOwnedByExistingFollowLogic() {
        assertFalse(ChatListPinning.shouldRepinAfterResize(bottom, bottom.copy(newestKey = "msg-other", newestHeight = 120, atBottom = false)))
        assertFalse(ChatListPinning.shouldRepinAfterResize(bottom, bottom.copy(itemCount = 21, viewportHeight = 340, atBottom = false)))
    }

    @Test fun unchangedOrGrowingViewportDoesNotCauseRepeatedScrollRequests() {
        assertFalse(ChatListPinning.shouldRepinAfterResize(bottom, bottom))
        assertFalse(ChatListPinning.shouldRepinAfterResize(bottom, bottom.copy(atBottom = false)))
        assertFalse(ChatListPinning.shouldRepinAfterResize(bottom, bottom.copy(viewportHeight = 450, atBottom = false)))
    }

    @Test fun emptyOrUnmeasuredLayoutHasNoScrollTarget() {
        assertFalse(ChatListPinning.shouldRepinAfterResize(bottom, bottom.copy(itemCount = 0, viewportHeight = 340, atBottom = false)))
        assertFalse(ChatListPinning.shouldRepinAfterResize(bottom.copy(viewportHeight = 0), bottom.copy(viewportHeight = 340, atBottom = false)))
    }
}
