package com.kungsbackacarcommunity.app.drives

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.convoy.*
import com.kungsbackacarcommunity.app.live.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalConvoyExitBindingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ownEndNeverPresentsChoiceWhenConvoyEchoArrivesFirst() = ownExit(sessionFirst = false)
    @Test fun ownEndNeverPresentsChoiceWhenSessionEchoArrivesFirst() = ownExit(sessionFirst = true)
    @Test fun ownLeaveNeverPresentsChoice() = ownExit(sessionFirst = false, leave = true)

    @Test fun successfulEndConsumesSessionBeforeItsDelayedStopEcho() {
        val h = Harness()
        mount(h)
        compose.runOnIdle { h.scope.launch { h.repo.end("convoy") } }
        compose.waitForIdle()
        endConvoy(h)
        compose.runOnIdle { h.raw.completion.complete(true) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(h.binding.suppressOldSessionStart) }
        stopSession(h)
        compose.runOnIdle {
            assertFalse(h.everPresented)
            assertFalse(h.binding.holdRecording)
            assertNull(h.binding.promptSessionId)
        }
    }

    private fun ownExit(sessionFirst: Boolean, leave: Boolean = false) {
        val h = Harness()
        mount(h)
        compose.runOnIdle { h.scope.launch { if (leave) h.repo.leave("convoy") else h.repo.end("convoy") } }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(h.repo.localExits.value.pending.contains("convoy")) }
        if (sessionFirst) stopSession(h) else endConvoy(h)
        compose.runOnIdle {
            assertNull(h.binding.promptSessionId)
            assertTrue(h.binding.holdRecording)
        }
        if (sessionFirst) endConvoy(h) else stopSession(h)
        compose.runOnIdle {
            assertNull(h.binding.promptSessionId)
            assertTrue(h.binding.holdRecording)
            h.raw.completion.complete(true)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(h.everPresented)
            assertNull(h.binding.promptSessionId)
            assertFalse(h.binding.holdRecording)
            assertTrue(h.binding.suppressOldSessionStart)
            assertEquals(0, h.stops)
        }
    }

    @Test fun failedExitReleasesFenceAndStillOffersRemoteEndChoice() {
        val h = Harness()
        mount(h)
        compose.runOnIdle { h.scope.launch { h.repo.end("convoy") } }
        compose.waitForIdle()
        endConvoy(h)
        stopSession(h)
        compose.runOnIdle {
            assertNull(h.binding.promptSessionId)
            h.raw.completion.complete(false)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("auto", h.binding.promptSessionId)
            assertTrue(h.binding.holdRecording)
            assertTrue(h.repo.localExits.value.completed.isEmpty())
            assertEquals(0, h.stops)
        }
    }

    @Test fun someoneElsesEndStillOffersContinuation() {
        val h = Harness()
        mount(h)
        endConvoy(h)
        compose.runOnIdle { assertEquals("auto", h.binding.promptSessionId) }
        stopSession(h)
        compose.runOnIdle {
            assertEquals("auto", h.binding.promptSessionId)
            assertTrue(h.binding.holdRecording)
            assertEquals(0, h.stops)
        }
    }

    private fun mount(h: Harness) {
        compose.setContent {
            h.scope = rememberCoroutineScope()
            val exits by h.repo.localExits.collectAsState()
            val session = h.session.value
            h.binding = rememberConvoyEndBinding(
                uid = "me", repository = h.repo, sessionLoad = LiveSessionLoad.Loaded(session),
                sharing = session.status == LiveSessionStatus.ACTIVE, userEndedSession = false,
                coordinator = null, canStartSingle = true, now = { 1_000L },
                onStopRecording = { h.stops++ }, onCommandFailed = {},
                localConvoyExit = session.convoyId in exits.completed,
                pendingConvoyExit = session.convoyId in exits.pending,
            )
            SideEffect { if (h.binding.promptSessionId != null) h.everPresented = true }
        }
        compose.waitForIdle()
    }

    private fun stopSession(h: Harness) {
        compose.runOnIdle { h.session.value = h.session.value.copy(status = LiveSessionStatus.STOPPED) }
        compose.waitForIdle()
    }

    private fun endConvoy(h: Harness) {
        compose.runOnIdle { h.raw.convoy.value = h.raw.convoy.value.copy(status = ConvoyStatus.Ended) }
        compose.waitForIdle()
    }

    private class Harness {
        val raw = Repository()
        val repo = LocalConvoyExitRepository(raw)
        val session = mutableStateOf(LiveSessionInfo("auto", LiveSessionStatus.ACTIVE,
            LiveSessionDuration.SIX_HOURS, 100_000L, convoyAutoStarted = true, convoyId = "convoy"))
        lateinit var scope: CoroutineScope
        lateinit var binding: ConvoyEndBinding
        var everPresented = false
        var stops = 0
    }

    private class Repository : ConvoyRepository {
        val convoy = MutableStateFlow(ConvoySummary("convoy", "me", null, ConvoyStatus.Active,
            emptyList(), listOf("me"), ConvoyViewer(ConvoyRole.Owner, ConvoyInviteStatus.Accepted),
            emptyList(), null, null, null, null))
        val completion = CompletableDeferred<Boolean>()
        override fun observeConvoy(convoyId: String, callerUid: String?) = convoy
        override suspend fun end(convoyId: String): ConvoyMutationResult =
            if (completion.await()) ConvoyMutationResult.Updated(convoy.value)
            else ConvoyMutationResult.Failed(ConvoyActionError.Generic)
        override suspend fun leave(convoyId: String): LeaveConvoyResult =
            if (completion.await()) LeaveConvoyResult.Left(convoy.value, 0, ConvoyLeaveOutcome.LeftAndEnded, null)
            else LeaveConvoyResult.Failed(ConvoyActionError.Generic)
        override suspend fun list(): ConvoyListResult = error("unused")
        override suspend fun create(inviteeUids: List<String>, title: String?, vehicleId: String?): CreateConvoyResult = error("unused")
        override suspend fun respond(convoyId: String, accept: Boolean): ConvoyMutationResult = error("unused")
        override suspend fun invite(convoyId: String, inviteeUids: List<String>): CreateConvoyResult = error("unused")
        override suspend fun start(convoyId: String): ConvoyMutationResult = error("unused")
    }
}
