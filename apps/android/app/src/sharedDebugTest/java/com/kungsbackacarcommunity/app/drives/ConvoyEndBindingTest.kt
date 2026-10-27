package com.kungsbackacarcommunity.app.drives

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.convoy.*
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.live.*
import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production observation, command effects and dialog, with independently delayed backend streams. */
@RunWith(AndroidJUnit4::class)
class ConvoyEndBindingTest {
    @get:Rule val rule = createComposeRule()
    private val restoration by lazy { StateRestorationTester(rule) }
    private val live = FakeLive()
    private val coordinator = LiveLocationCoordinator(live)
    private val documents = FakeConvoys()
    private val load = mutableStateOf<LiveSessionLoad>(LiveSessionLoad.Loaded(auto()))
    private val uid = mutableStateOf("member")
    private val userStop = mutableStateOf(false)
    private var stops = 0
    private var errors = 0
    private lateinit var binding: ConvoyEndBinding
    private val now = 10_000L

    private fun auto(id: String = "old", convoy: String = "convoy") = LiveSessionInfo(
        id, LiveSessionStatus.ACTIVE, LiveSessionDuration.SIX_HOURS, 1_000_000L,
        convoyAutoStarted = true, convoyId = convoy,
    )
    private fun manual() = auto("solo").copy(convoyAutoStarted = false, convoyId = null)
    private fun doc(id: String = "convoy", status: ConvoyStatus = ConvoyStatus.Active) = ConvoySummary(
        id, "owner", null, status, emptyList(), listOf("member"),
        ConvoyViewer(ConvoyRole.Member, ConvoyInviteStatus.Accepted), emptyList(), null, null, null, null,
    )
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun render() {
        restoration.setContent {
            KccTheme {
                binding = rememberConvoyEndBinding(
                    uid.value, documents, load.value,
                    LiveLocation.isSharing(load.value.sessionOrNull, now), userStop.value,
                    coordinator, true, { now },
                    onStopRecording = { stops++; SingleSessionRecording.stop(SavePromptReason.ConvoyEnded) },
                    onCommandFailed = { errors++ }, timeoutMillis = 1_000L,
                )
                if (binding.promptSessionId != null) ConvoyEndChoiceDialog(binding.choose)
            }
        }
        rule.waitForIdle()
    }
    @Before fun setUp() {
        SingleSessionRecording.clearIfNotOwnedBy(null)
        val drives = object : DrivesRepository {
            override fun observeDrives(uid: String): Flow<DrivesState> = flowOf(DrivesState.Loading)
            override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult = error("No autosave in binding")
            override suspend fun deleteDrive(rideId: String) = Unit
        }
        SingleSessionRecording.start("member", drives, sessionId = "old") { null }
        SingleSessionRecording.active.value!!.addFix(57.0, 12.0, 1_000L)
        SingleSessionRecording.active.value!!.addFix(57.001, 12.0, 2_000L)
        documents.snapshot.value = doc()
    }
    @After fun tearDown() = SingleSessionRecording.clearIfNotOwnedBy(null)
    private fun endDocument() {
        rule.runOnIdle { documents.snapshot.value = doc(status = ConvoyStatus.Ended) }
        rule.onNodeWithText(text(R.string.convoy_endedContinueTitle)).assertIsDisplayed()
    }
    private fun chooseContinue() = rule.onNodeWithText(text(R.string.convoy_endedContinueAsSingle)).performClick()
    private fun chooseEnd() = rule.onNodeWithText(text(R.string.convoy_endedContinueEnd)).performClick()
    private fun assertRecording() {
        rule.runOnIdle {
            assertEquals(0, stops)
            assertTrue(SingleSessionRecording.active.value!!.state.value is RecordingState.Recording)
            assertEquals(2, SingleSessionRecording.active.value!!.recordedPoints().size)
        }
    }

    @Test fun endedDocumentPromptsWhileOwnSessionAndEndRpcAreStillActive() {
        render()
        // No coordinator mutation/optimistic hide is used to raise this choice.
        assertNull(binding.promptSessionId)
        endDocument()
        assertRecording()
        rule.runOnIdle { assertEquals(listOf("convoy" to "member"), documents.observed) }
        assertEquals("old", load.value.sessionOrNull?.sessionId)
    }

    @Test fun continueWaitsForDifferentManualSessionAndPreservesRecorderThroughSlowStop() {
        render()
        val recording = SingleSessionRecording.active.value
        endDocument()
        chooseContinue()
        rule.runOnIdle {
            assertEquals(1, live.starts)
            assertTrue(binding.holdRecording)
            assertFalse(binding.suppressOldSessionStart)
            // Repeated old ACTIVE frames cannot be mistaken for transfer success.
            load.value = LiveSessionLoad.Loaded(auto().copy(vehicleId = "still-old"))
        }
        assertRecording()
        rule.runOnIdle { load.value = LiveSessionLoad.Loaded(auto().copy(status = LiveSessionStatus.STOPPED)) }
        assertRecording()
        rule.runOnIdle { load.value = LiveSessionLoad.Loaded(manual()) }
        rule.runOnIdle {
            assertFalse(binding.holdRecording)
            assertSame(recording, SingleSessionRecording.active.value)
            assertEquals(0, live.stops)
        }
        assertRecording()
    }

    @Test fun endActuallyStopsOldActiveSessionAndWaitsForConfirmationBeforeSaving() {
        live.stopGate = CompletableDeferred()
        render()
        endDocument()
        chooseEnd()
        rule.runOnIdle { assertEquals(1, live.stops) }
        assertRecording()
        rule.runOnIdle {
            live.stopGate!!.complete(Unit)
            load.value = LiveSessionLoad.Loaded(auto().copy(status = LiveSessionStatus.STOPPED))
        }
        rule.runOnIdle {
            assertEquals(1, stops)
            assertTrue(binding.suppressOldSessionStart)
            assertTrue(SingleSessionRecording.active.value!!.state.value is RecordingState.PromptSave)
        }
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { assertNull(binding.promptSessionId); assertEquals(1, live.stops) }
    }

    @Test fun failedContinueWithOldSessionLiveReturnsChoiceWithoutOrphaning() {
        live.failStart = true
        render()
        endDocument()
        chooseContinue()
        rule.onNodeWithText(text(R.string.convoy_endedContinueTitle)).assertIsDisplayed()
        assertRecording()
        rule.runOnIdle { assertEquals(1, errors) }
    }

    @Test fun failedContinueAfterStopSavesRecording() {
        live.failStart = true
        render()
        endDocument()
        rule.runOnIdle { load.value = LiveSessionLoad.Loaded(auto().copy(status = LiveSessionStatus.STOPPED)) }
        chooseContinue()
        rule.runOnIdle {
            assertEquals(1, stops)
            assertEquals(1, errors)
            assertNull(binding.promptSessionId)
            assertTrue(SingleSessionRecording.active.value!!.state.value is RecordingState.PromptSave)
        }
    }

    @Test fun failedEndKeepsLiveRecordingAndOffersRetry() {
        live.failStop = true
        render()
        endDocument()
        chooseEnd()
        rule.onNodeWithText(text(R.string.convoy_endedContinueTitle)).assertIsDisplayed()
        assertRecording()
        rule.runOnIdle { assertEquals(1, live.stops); assertEquals(1, errors) }
    }

    @Test fun timeoutAfterOldSessionStopsSavesInsteadOfLeavingOrphanRecording() {
        live.startGate = CompletableDeferred()
        render()
        endDocument()
        chooseContinue()
        rule.runOnIdle { load.value = LiveSessionLoad.Loaded(auto().copy(status = LiveSessionStatus.STOPPED)) }
        rule.mainClock.advanceTimeBy(1_100L)
        rule.runOnIdle {
            assertEquals(1, stops)
            assertNull(binding.promptSessionId)
            assertTrue(SingleSessionRecording.active.value!!.state.value is RecordingState.PromptSave)
        }
    }

    @Test fun timeoutWithOldSessionActiveOffersRetryInsteadOfFalselyConfirmingTransfer() {
        live.startGate = CompletableDeferred()
        render()
        endDocument()
        chooseContinue()
        rule.mainClock.advanceTimeBy(1_100L)
        rule.onNodeWithText(text(R.string.convoy_endedContinueTitle)).assertIsDisplayed()
        assertRecording()
    }

    @Test fun rotationDuringContinueDoesNotIssueStartTwiceOrAcceptOldSession() {
        live.startGate = CompletableDeferred()
        render()
        endDocument()
        chooseContinue()
        rule.runOnIdle { load.value = LiveSessionLoad.Loading }
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { load.value = LiveSessionLoad.Loaded(auto()) }
        rule.runOnIdle { assertEquals(1, live.starts); assertTrue(binding.holdRecording) }
        rule.runOnIdle { load.value = LiveSessionLoad.Loaded(manual()) }
        rule.runOnIdle { assertFalse(binding.holdRecording) }
        assertRecording()
    }

    @Test fun replacementSessionDismissesOldChoiceAndCannotBeStoppedByStaleEndClick() {
        render()
        endDocument()
        val oldChoice = binding.choose
        rule.runOnIdle { load.value = LiveSessionLoad.Loaded(manual()) }
        rule.runOnIdle { oldChoice(ConvoyEndChoice.EndSession) }
        rule.runOnIdle { assertEquals(0, live.stops) }
        assertRecording()
    }

    @Test fun nullDeniedAndManualSessionsDoNotPromptAndAccountSwitchDropsOldChoice() {
        documents.snapshot.value = null
        render()
        rule.runOnIdle { assertNull(binding.promptSessionId) }
        rule.runOnIdle { documents.snapshot.value = doc("unrelated", ConvoyStatus.Ended) }
        rule.runOnIdle { assertNull(binding.promptSessionId) }
        rule.runOnIdle { documents.snapshot.value = doc(status = ConvoyStatus.Ended); load.value = LiveSessionLoad.Loaded(manual()) }
        rule.runOnIdle { assertNull(binding.promptSessionId) }
        rule.runOnIdle { load.value = LiveSessionLoad.Loaded(auto()) }
        rule.onNodeWithText(text(R.string.convoy_endedContinueTitle)).assertIsDisplayed()
        rule.runOnIdle { uid.value = "other"; load.value = LiveSessionLoad.Loading }
        rule.runOnIdle { assertNull(binding.promptSessionId) }
    }

    @Test fun selfStopHideAndExpiryDoNotRaiseConvoyChoice() {
        userStop.value = true
        documents.snapshot.value = doc(status = ConvoyStatus.Ended)
        render()
        rule.runOnIdle { assertNull(binding.promptSessionId) }
        rule.runOnIdle { load.value = LiveSessionLoad.Loaded(auto().copy(status = LiveSessionStatus.STOPPED)) }
        rule.runOnIdle { userStop.value = false }
        rule.runOnIdle { assertNull(binding.promptSessionId) }
        rule.runOnIdle { load.value = LiveSessionLoad.Loaded(auto("expired").copy(expiresAtMillis = now)) }
        rule.runOnIdle { assertNull(binding.promptSessionId) }
    }

    private class FakeLive : LiveLocationRepository {
        var starts = 0
        var stops = 0
        var failStart = false
        var failStop = false
        var startGate: CompletableDeferred<Unit>? = null
        var stopGate: CompletableDeferred<Unit>? = null
        override suspend fun startSession(duration: LiveSessionDuration, vehicleId: String?) {
            starts++
            if (failStart) error("refused")
            startGate?.await()
        }
        override suspend fun stopSession() {
            stops++
            if (failStop) error("refused")
            stopGate?.await()
        }
        override suspend fun hideMeNow() = Unit
        override suspend fun updatePosition(coordinate: LiveCoordinate) = Unit
        override suspend fun listNearby(center: LatLng, radiusMeters: Double) = emptyList<NearbyLiveSession>()
        override fun observeOwnSession(uid: String): Flow<LiveSessionInfo?> = flowOf(null)
        override fun observeLatest(uid: String): Flow<LiveMarker?> = flowOf(null)
    }
    private class FakeConvoys : ConvoyRepository {
        val snapshot = MutableStateFlow<ConvoySummary?>(null)
        val observed = mutableListOf<Pair<String, String?>>()
        override fun observeConvoy(convoyId: String, callerUid: String?): Flow<ConvoySummary?> {
            observed += convoyId to callerUid
            return snapshot
        }
        override suspend fun list(): ConvoyListResult = error("unused")
        override suspend fun create(inviteeUids: List<String>, title: String?, vehicleId: String?): CreateConvoyResult = error("unused")
        override suspend fun respond(convoyId: String, accept: Boolean): ConvoyMutationResult = error("unused")
        override suspend fun invite(convoyId: String, inviteeUids: List<String>): CreateConvoyResult = error("unused")
        override suspend fun leave(convoyId: String): LeaveConvoyResult = error("unused")
        override suspend fun start(convoyId: String): ConvoyMutationResult = error("unused")
        override suspend fun end(convoyId: String): ConvoyMutationResult = error("unused")
    }
}
