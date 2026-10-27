package com.kungsbackacarcommunity.app.convoy

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.map.ConvoyMemberPosition
import com.kungsbackacarcommunity.app.shell.MapLoadState
import com.kungsbackacarcommunity.app.shell.MapPoint
import com.kungsbackacarcommunity.app.shell.StubMapSurface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume
import kotlin.math.abs

/** Three actual production bindings sharing a local protocol fixture, not Firebase. */
@RunWith(AndroidJUnit4::class)
class ConvoyFollowMeBindingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val time = mutableLongStateOf(1_000_000L)
    private val convoy = mutableStateOf<String?>("one")
    private val uids = setOf("A", "B", "C")
    private val members = mutableStateOf(uids)
    private val positions = mutableStateOf(listOf(
        position("A", 12.0700), position("B", 12.0800), position("C", 12.0900)))
    private val maps = uids.associateWith { StubMapSurface(MapLoadState.Loaded, autoLoad = false) }
    private val server = SharedDocument().apply { emit("one", null) }
    private val clients = uids.associateWith { Client(it) }

    private fun position(uid: String, longitude: Double) = ConvoyMemberPosition(uid, 57.49, longitude,
        updatedAtMillis = time.longValue)

    private fun render() {
        rule.setContent {
            KccTheme {
                Column {
                    uids.forEach { uid ->
                        val active = rememberConvoyFollowMeBinding(clients.getValue(uid), convoy.value,
                            uid, members.value, positions.value.first { it.uid == uid }, positions.value,
                            maps.getValue(uid), now = { time.longValue })
                        Text("$uid:$active", Modifier.testTag("leading-$uid"))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun toggle(uid: String, active: Boolean) {
        val leader = rule.runOnIdle {
            server.toggle("one", uid, active)
            server.data["one"]?.leaderUid
        }
        if (leader == null) assertNoSharedPath()
        else assertSharedPath(leader, minimumPoints = 1)
    }

    private fun drive(uid: String, latitude: Double = 57.49) {
        rule.runOnIdle {
            time.longValue += FollowMeTrail.WRITE_THROTTLE_MS
            positions.value = positions.value.map {
                if (it.uid == uid) position(uid, it.longitude + 0.0005).copy(latitude = latitude) else it
            }
        }
        rule.waitForIdle()
    }

    private fun assertLeader(uid: String?) {
        uids.forEach { viewer -> rule.onNodeWithTag("leading-$viewer").assertTextEquals("$viewer:${uid == viewer}") }
    }

    private fun assertSharedPath(leader: String, minimumPoints: Int = 2, viewers: Set<String> = uids) {
        val longitude = rule.runOnIdle { positions.value.first { it.uid == leader }.longitude }
        // Idle alone does not establish that the shared flow and every map
        // effect have delivered the latest route. Wait for its actual endpoint
        // AND the whole expected path on each viewer, including its author.
        rule.waitUntil(timeoutMillis = 5_000L) {
            rule.runOnIdle {
                val state = server.data["one"]
                val expected = FollowMeTrail.decode(state?.polyline)
                state?.leaderUid == leader && expected.size >= minimumPoints &&
                    expected.lastOrNull()?.let { abs(it.longitude - longitude) < 0.00001 } == true &&
                    viewers.all { maps.getValue(it).followMeTrail.value == expected }
            }
        }
        rule.runOnIdle {
            val expected = FollowMeTrail.decode(server.data["one"]?.polyline)
            assertTrue("leader has the expected trail points", expected.size >= minimumPoints)
            assertEquals(longitude, expected.last().longitude, 0.00001)
            viewers.forEach { viewer ->
                assertEquals("same shared path on $viewer (including author)", expected, maps.getValue(viewer).followMeTrail.value)
            }
        }
    }

    private fun assertNoSharedPath(viewers: Set<String> = uids) {
        rule.waitUntil(timeoutMillis = 5_000L) {
            rule.runOnIdle { viewers.all { maps.getValue(it).followMeTrail.value == null } }
        }
        rule.runOnIdle { viewers.forEach { assertNull("route cleared on $it", maps.getValue(it).followMeTrail.value) } }
    }

    @Test fun activationMovementTakeoverAndOffReachAllThreeMaps() {
        render()
        assertLeader(null)
        toggle("A", true)
        assertLeader("A")
        drive("A")
        assertSharedPath("A")
        val previous = maps.getValue("C").followMeTrail.value
        toggle("B", true)
        assertLeader("B")
        assertNotEquals("old leader's line is removed before B drives", previous, maps.getValue("C").followMeTrail.value)
        drive("B")
        assertSharedPath("B")
        toggle("C", true)
        assertLeader("C")
        drive("C")
        assertSharedPath("C")
        toggle("C", false)
        assertLeader(null)
        assertNoSharedPath()
    }

    @Test fun aFormerLeadersOffAndLateWriteCannotEraseTheNewLeadersPath() {
        render()
        toggle("A", true)
        drive("A")
        val oldActivation = server.data["one"]!!.activationId
        val old = server.data["one"]!!.polyline!!
        toggle("B", true)
        drive("B")
        val accepted = server.data["one"]
        toggle("A", false)
        rule.runOnIdle { assertFalse(server.write("one", "A", old, oldActivation)) }
        assertEquals(accepted, server.data["one"])
        assertLeader("B")
        assertSharedPath("B")
    }

    @Test fun reactivationStartsWithANewPublisherInsteadOfReusingTheOldGeometry() {
        render()
        toggle("A", true)
        drive("A")
        val oldState = server.data["one"]!!
        toggle("A", false)
        rule.runOnIdle {
            time.longValue += 4_000
            positions.value = positions.value.map { if (it.uid == "A") position("A", 12.1) else it }
        }
        toggle("A", true)
        drive("A")
        assertSharedPath("A")
        assertTrue(FollowMeTrail.decode(server.data["one"]?.polyline).all { it.longitude >= 12.1 })
        rule.runOnIdle { assertFalse("old same-UID activation is rejected", server.write("one", "A", oldState.polyline!!, oldState.activationId)) }
        assertSharedPath("A")
    }

    @Test fun blockedWriteSerializesAndCoalescesSnapshotsWithoutLosingIntermediateCorners() {
        render()
        toggle("A", true)
        drive("A")
        assertSharedPath("A")
        val client = clients.getValue("A")
        val blocked = BlockedTrailWrite()
        val previousAttempts = rule.runOnIdle {
            client.nextBlockedWrite = blocked
            client.trailWriteAttempts.size
        }
        try {
            drive("A")
            rule.waitUntil(timeoutMillis = 5_000L) { rule.runOnIdle { blocked.started } }
            val captured = rule.runOnIdle { client.trailWriteAttempts.last() }
            // Separate applied fixes form a corner while IO remains blocked.
            // A collector that awaited writeTrail before buffering them would
            // resume at the final position and lose this intermediate geometry.
            drive("A", latitude = 57.4905)
            val corner = rule.runOnIdle {
                positions.value.first { it.uid == "A" }.let { MapPoint(it.longitude, it.latitude) }
            }
            drive("A")
            val expected = rule.runOnIdle {
                assertFalse("movement must not cancel the pending flush", blocked.cancelled)
                assertEquals("new fixes must not dispatch overlapping writes", previousAttempts + 1, client.trailWriteAttempts.size)
                val latest = positions.value.first { it.uid == "A" }
                FollowMeTrail.encode(FollowMeTrail.decode(captured.polyline) + corner + MapPoint(latest.longitude, latest.latitude))
            }
            rule.runOnIdle { blocked.release.complete(Unit) }
            assertSharedPath("A")
            rule.runOnIdle {
                assertEquals(true, blocked.accepted)
                assertEquals("queued snapshots coalesce into one subsequent flush", previousAttempts + 2, client.trailWriteAttempts.size)
                assertEquals(captured.convoyId, client.trailWriteAttempts.last().convoyId)
                assertEquals(captured.activationId, client.trailWriteAttempts.last().activationId)
                assertEquals(captured.activationId, server.data["one"]?.activationId)
                assertEquals("the final route retains the intermediate corner as well as the latest fix", expected, server.data["one"]?.polyline)
            }
        } finally {
            rule.runOnIdle { blocked.release.complete(Unit) }
        }
    }

    @Test fun blockedWriteFromCancelledActivationCannotOverwriteTheSameUsersNewRoute() {
        render()
        toggle("A", true)
        drive("A")
        assertSharedPath("A")
        val client = clients.getValue("A")
        val blocked = BlockedTrailWrite()
        rule.runOnIdle { client.nextBlockedWrite = blocked }
        try {
            drive("A")
            rule.waitUntil(timeoutMillis = 5_000L) { rule.runOnIdle { blocked.started } }
            val captured = rule.runOnIdle { client.trailWriteAttempts.last() }
            toggle("A", false)
            rule.runOnIdle { assertTrue("ending the activation cancels its collector", blocked.cancelled) }
            toggle("A", true)
            drive("A")
            assertSharedPath("A")
            val fresh = rule.runOnIdle {
                server.data["one"]!!.also { assertNotEquals(captured.activationId, it.activationId) }
            }
            rule.runOnIdle {
                blocked.release.complete(Unit)
                assertEquals("the old task still completes but its generation is rejected", false, blocked.accepted)
                assertEquals(fresh, server.data["one"])
            }
            assertSharedPath("A")
        } finally {
            rule.runOnIdle { blocked.release.complete(Unit) }
        }
    }

    @Test fun changingConvoyClearsOldStateBeforeTheNewListenerHasEmitted() {
        render()
        toggle("A", true)
        drive("A")
        assertSharedPath("A")
        rule.runOnIdle { convoy.value = "two" } // deliberately no initial snapshot for two
        rule.waitForIdle()
        assertLeader(null)
        assertNoSharedPath()
        assertFalse("no old leadership writes in new convoy", server.writes.any { it.first == "two" })
        rule.runOnIdle { server.emit("one", FollowMeState("B", "old", time.longValue)) }
        rule.waitForIdle()
        assertLeader(null)
        assertNoSharedPath()
    }

    @Test fun removedViewerClearsItsMapEvenBeforeTheSharedListenerFails() {
        render()
        toggle("A", true)
        drive("A")
        assertSharedPath("A")
        rule.runOnIdle { members.value = uids - "C" }
        rule.waitForIdle()
        assertNoSharedPath(setOf("C"))
        assertSharedPath("A", viewers = uids - "C")
    }

    @Test fun parkedLeaderStaysVisibleBetweenHeartbeatsAndADeadSignalStillAgesOut() {
        render()
        toggle("A", true)
        drive("A")
        val lastFix = time.longValue
        val writes = server.writes.size
        rule.runOnIdle { time.longValue = lastFix + 179_999L }
        rule.mainClock.advanceTimeBy(FollowMeTrail.STALE_RECHECK_MS)
        rule.waitForIdle()
        assertSharedPath("A")
        assertEquals("no stationary trail writes added", writes, server.writes.size)
        rule.runOnIdle { time.longValue = lastFix + FollowMeTrail.STALE_MS }
        rule.mainClock.advanceTimeBy(FollowMeTrail.STALE_RECHECK_MS)
        rule.waitForIdle()
        assertNoSharedPath()
        assertNotNull("freshness does not turn the feature off", server.data["one"])
    }

    private data class TrailWrite(val convoyId: String, val polyline: String, val activationId: String?)

    private class BlockedTrailWrite {
        val release = CompletableDeferred<Unit>()
        var started = false
        var cancelled = false
        var accepted: Boolean? = null
    }

    private inner class Client(private val uid: String) : ConvoyFollowMeRepository {
        val trailWriteAttempts = mutableListOf<TrailWrite>()
        var nextBlockedWrite: BlockedTrailWrite? = null

        override fun observeFollowMe(convoyId: String): Flow<FollowMeState?> = server.flow(convoyId)
        override suspend fun setFollowMe(convoyId: String, active: Boolean): Boolean {
            server.toggle(convoyId, uid, active)
            return server.data[convoyId]?.leaderUid == uid
        }
        override suspend fun writeTrail(convoyId: String, polyline: String, activationId: String?): Boolean {
            trailWriteAttempts += TrailWrite(convoyId, polyline, activationId)
            val blocked = nextBlockedWrite
            nextBlockedWrite = null
            if (blocked == null) return server.write(convoyId, uid, polyline, activationId)
            // Like the Firebase adapter's Task, completion survives coroutine
            // cancellation. Releasing it later can therefore expose reordering
            // and must still check the originally captured activation id.
            return suspendCancellableCoroutine { continuation ->
                blocked.started = true
                continuation.invokeOnCancellation { blocked.cancelled = true }
                blocked.release.invokeOnCompletion {
                    val accepted = server.write(convoyId, uid, polyline, activationId)
                    blocked.accepted = accepted
                    if (continuation.isActive) continuation.resume(accepted)
                }
            }
        }
    }

    /** Protocol fixture only; real callable/rules are separately emulator-tested. */
    private inner class SharedDocument {
        val data = mutableMapOf<String, FollowMeState?>()
        val writes = mutableListOf<Pair<String, String>>()
        private var generation = 0
        private val flows = mutableMapOf<String, MutableSharedFlow<FollowMeState?>>()
        fun flow(id: String) = flows.getOrPut(id) { MutableSharedFlow(replay = 1, extraBufferCapacity = 8) }
        fun emit(id: String, state: FollowMeState?) { data[id] = state; check(flow(id).tryEmit(state)) }
        fun toggle(id: String, uid: String, active: Boolean) {
            if (active && data[id]?.leaderUid != uid) emit(id, FollowMeState(uid, "", time.longValue, "activation-${++generation}"))
            else if (!active && data[id]?.leaderUid == uid) emit(id, null)
        }
        fun write(id: String, uid: String, line: String, activationId: String? = null): Boolean {
            if (data[id]?.leaderUid != uid || data[id]?.activationId != activationId) return false
            writes += id to uid
            emit(id, data[id]!!.copy(polyline = line, updatedAtMillis = time.longValue))
            return true
        }
    }
}
