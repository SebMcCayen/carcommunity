package com.kungsbackacarcommunity.app.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class ChatCooldownNoticeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun countdownRemainsReadableAtLargeTextWithoutATickingLiveRegion() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
            KccTheme { Box(Modifier.width(320.dp)) { ChatCooldownNotice(600) } }
        } }
        compose.onNodeWithTag("chat-flood-countdown").assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        val bounds = compose.onNodeWithTag("chat-flood-countdown").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width <= 320f)
    }
    @Test fun sameSenderSharesGateAcrossSurfacesAndOtherAccountDoesNot() {
        val firstUid = "cooldown-test-" + java.util.UUID.randomUUID()
        val first = ChatSendCooldowns.forUser(firstUid)
        val same = ChatSendCooldowns.forUser(firstUid)
        val other = ChatSendCooldowns.forUser(firstUid + "-other")
        first.apply(ChatRateLimit(30_000))
        assertSame(first, same)
        assertTrue(same.isBlocked())
        assertFalse(other.isBlocked())
    }
}
