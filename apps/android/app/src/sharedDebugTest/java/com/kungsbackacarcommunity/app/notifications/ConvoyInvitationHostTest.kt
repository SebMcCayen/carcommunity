package com.kungsbackacarcommunity.app.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.ShellBottomBar
import com.kungsbackacarcommunity.app.convoy.*
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.design.KccPalette
import com.kungsbackacarcommunity.app.shell.LiveSessionBar
import com.kungsbackacarcommunity.app.shell.ShellTab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Same host, native views and repositories seams on JVM and emulator. No Firebase. */
@RunWith(AndroidJUnit4::class)
class ConvoyInvitationHostTest {
    @get:Rule val rule = createComposeRule()
    private val notifications = Inbox()
    private val convoys = Convoys()
    private var elapsed by mutableLongStateOf(0L)
    private var joined = 0

    private fun show(dark: Boolean = false) {
        rule.setContent {
            KccTheme(darkTheme = dark) {
                Column(Modifier.fillMaxSize().background(if (dark) KccPalette.nightOverlay else KccPalette.warmIvory)) {
                    Spacer(Modifier.weight(1f))
                    ConvoyInvitationHost("me", notifications, convoys, remember { ConvoyCoordinator(convoys) },
                        inboxVisible = false, sharing = true, liveShareEnabled = true,
                        onJoined = { joined++ }, modifier = Modifier.fillMaxWidth().padding(16.dp), elapsedRealtime = { elapsed })
                    Box(Modifier.padding(horizontal = 16.dp).testTag("fixture_live_bar")) {
                        LiveSessionBar(0L, 12_400.0, null, now = { 754_000L })
                    }
                    ShellBottomBar(ShellTab.Map, {}, true, {}, false)
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            notifications.items.value = NotificationsState.Loaded(listOf(AppNotification(
                "invite", NotificationCategory.CONVOY_INVITE, "Konvoj", "Linnea bjuder in", null,
                false, System.currentTimeMillis(), relatedEntityId = "convoy", inviterDisplayName = "Linnea Andersson")))
        }
        rule.onNodeWithTag("convoy_invitation_card").assertIsDisplayed()
    }

    @Test fun joinAnswersDirectlyOnceAndHandsOffToMap() {
        show()
        rule.onNodeWithTag("convoy_invitation_join").performClick()
        rule.waitUntil(5_000) { convoys.answers.size == 1 }
        rule.waitForIdle()
        rule.onNodeWithTag("convoy_invitation_join").assertIsNotEnabled()
        rule.runOnIdle { convoys.gate.complete(Unit) }
        rule.waitUntil(5_000) { joined == 1 }
        rule.onNodeWithTag("convoy_invitation_card").assertDoesNotExist()
        assertEquals(listOf(true), convoys.answers)
    }

    @Test fun declineAnswersDirectlyWithoutJoining() {
        show()
        rule.runOnIdle { convoys.gate.complete(Unit) }
        rule.onNodeWithTag("convoy_invitation_decline").performClick()
        rule.waitUntil(5_000) { notifications.readIDs.isNotEmpty() }
        rule.onNodeWithTag("convoy_invitation_card").assertDoesNotExist()
        assertEquals(listOf(false), convoys.answers)
        assertEquals(0, joined)
    }

    @Test fun timeoutPreservesInboxAndDoesNotSendAnyResponse() {
        show()
        rule.runOnIdle { elapsed = 29_999 }
        rule.onNodeWithTag("convoy_invitation_card").assertIsDisplayed()
        rule.runOnIdle { elapsed = 30_000 }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("convoy_invitation_card").fetchSemanticsNodes().isEmpty() }
        assertTrue(convoys.answers.isEmpty())
        assertTrue(notifications.readIDs.isEmpty())
        assertFalse((notifications.items.value as NotificationsState.Loaded).items.single().isRead)
    }

    @Test fun responseCompletesAfterCardTimesOut() {
        show()
        rule.onNodeWithTag("convoy_invitation_join").performClick()
        rule.waitUntil(5_000) { convoys.answers.size == 1 }
        rule.runOnIdle { elapsed = 30_000 }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("convoy_invitation_card").fetchSemanticsNodes().isEmpty() }
        rule.runOnIdle { convoys.gate.complete(Unit) }
        rule.waitUntil(5_000) { joined == 1 }
        assertEquals(listOf(true), convoys.answers)
    }

    @Test fun failureAllowsRetryWithoutMarkingRead() {
        show()
        rule.runOnIdle { convoys.fail = true; convoys.gate.complete(Unit) }
        rule.onNodeWithTag("convoy_invitation_join").performClick()
        rule.waitUntil(5_000) { convoys.answers.size == 1 }
        rule.onNodeWithTag("convoy_invitation_card").assertIsDisplayed()
        rule.onNodeWithTag("convoy_invitation_decline").assertIsEnabled()
        assertTrue(notifications.readIDs.isEmpty())
        assertEquals(0, joined)
    }

    @Test fun compactCardStaysAboveLiveBarInDarkTheme() {
        show(dark = true)
        rule.onNodeWithText("Linnea Andersson").assertIsDisplayed()
        val card = rule.onNodeWithTag("convoy_invitation_card").fetchSemanticsNode().boundsInRoot
        val live = rule.onNodeWithTag("fixture_live_bar").fetchSemanticsNode().boundsInRoot
        assertTrue(card.bottom <= live.top)
    }

    private class Inbox : NotificationsRepository {
        val items = MutableStateFlow<NotificationsState>(NotificationsState.Loaded(emptyList()))
        val readIDs = mutableListOf<String>()
        override fun observeNotifications(uid: String): Flow<NotificationsState> = items
        override fun observeUnread(uid: String) = flowOf(false)
        override suspend fun markRead(notificationId: String) { readIDs += notificationId }
        override suspend fun markAllRead() = Unit
        override suspend fun markSeen() = Unit
        override suspend fun deleteNotification(notificationId: String) = Unit
        override suspend fun deleteAll() = Unit
    }
    private class Convoys : ConvoyRepository {
        val answers = mutableListOf<Boolean>()
        val gate = CompletableDeferred<Unit>()
        var fail = false
        var answered: Boolean? = null
        private fun convoy() = ConvoySummary("convoy", "owner", null, ConvoyStatus.Active,
            emptyList(), listOf("owner", "me"), ConvoyViewer(ConvoyRole.Member,
                when (answered) { true -> ConvoyInviteStatus.Accepted; false -> ConvoyInviteStatus.Declined; null -> ConvoyInviteStatus.Invited }),
            emptyList(), null, null, null, null)
        override suspend fun list() = ConvoyListResult.Loaded(listOf(convoy()), if (answered == null) listOf(convoy()) else emptyList())
        override fun observeConvoy(convoyId: String, callerUid: String?) = flowOf(convoy())
        override suspend fun respond(convoyId: String, accept: Boolean): ConvoyMutationResult {
            answers += accept
            gate.await()
            if (fail) return ConvoyMutationResult.Failed(ConvoyActionError.Generic)
            answered = accept
            return ConvoyMutationResult.Updated(convoy())
        }
        override suspend fun create(inviteeUids: List<String>, title: String?, vehicleId: String?) = CreateConvoyResult.Failed(ConvoyActionError.Generic)
        override suspend fun invite(convoyId: String, inviteeUids: List<String>) = CreateConvoyResult.Failed(ConvoyActionError.Generic)
        override suspend fun leave(convoyId: String): LeaveConvoyResult = error("unused")
        override suspend fun start(convoyId: String): ConvoyMutationResult = error("unused")
        override suspend fun end(convoyId: String): ConvoyMutationResult = error("unused")
    }
}
