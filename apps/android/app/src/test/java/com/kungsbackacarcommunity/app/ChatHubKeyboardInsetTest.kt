package com.kungsbackacarcommunity.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHubKeyboardInsetTest {
    @Test
    fun keyboardClosed_reservesTheVisibleShellBottomBar() {
        assertTrue(shouldReserveChatHubBottomBar(imeBottomPx = 0))
    }

    @Test
    fun keyboardOpen_doesNotLeaveBottomBarSpaceBelowTheComposer() {
        assertFalse(shouldReserveChatHubBottomBar(imeBottomPx = 720))
    }
}
