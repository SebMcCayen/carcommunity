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
import com.kungsbackacarcommunity.app.shell.StubMapSurface
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
        rule.runOnIdle { server.toggle("one", uid, active) }
        rule.waitForIdle()
    }

    private fun drive(uid: String) {
        rule.runOnIdle {
            time.longValue += FollowMeTrail.WRITE_THROTTLE_MS
            positions.value = positions.value.map {
                if (it.uid == uid) position(uid, it.longitude + 0.0005) else it
            }
        }
        rule.waitForIdle()
    }

    private fun assertLeader(uid: String?) {
        uids.forEach { viewer -> rule.onNodeWithTag("leading-$viewer").assertTextEquals("$viewer:${uid == viewer}") }
    }

    private fun assertSharedPath(leader: String) {
        val expected = FollowMeTrail.decode(server.data["one"]?.polyline)
        assertTrue("moving leader has a drawable trail", expected.size >= 2)
        assertEquals(positions.value.first { it.uid == leader }.longitude, expected.last().longitude, 0.00001)
        maps.forEach { (viewer, map) -> assertEquals("same shared path on $viewer (including author)", expected, map.followMeTrail.value) }
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
        maps.forEach { (_, map) -> assertNull(map.followMeTrail.value) }
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

    @Test fun changingConvoyClearsOldStateBeforeTheNewListenerHasEmitted() {
        render()
        toggle("A", true)
        drive("A")
        assertSharedPath("A")
        rule.runOnIdle { convoy.value = "two" } // deliberately no initial snapshot for two
        rule.waitForIdle()
        assertLeader(null)
        maps.forEach { (_, map) -> assertNull(map.followMeTrail.value) }
        assertFalse("no old leadership writes in new convoy", server.writes.any { it.first == "two" })
        rule.runOnIdle { server.emit("one", FollowMeState("B", "old", time.longValue)) }
        rule.waitForIdle()
        assertLeader(null)
        maps.forEach { (_, map) -> assertNull(map.followMeTrail.value) }
    }

    @Test fun removedViewerClearsItsMapEvenBeforeTheSharedListenerFails() {
        render()
        toggle("A", true)
        drive("A")
        assertSharedPath("A")
        rule.runOnIdle { members.value = uids - "C" }
        rule.waitForIdle()
        assertNull(maps.getValue("C").followMeTrail.value)
        assertNotNull(maps.getValue("A").followMeTrail.value)
        assertNotNull(maps.getValue("B").followMeTrail.value)
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
        maps.forEach { (_, map) -> assertNull("dead signal hides route", map.followMeTrail.value) }
        assertNotNull("freshness does not turn the feature off", server.data["one"])
    }

    private inner class Client(private val uid: String) : ConvoyFollowMeRepository {
        override fun observeFollowMe(convoyId: String): Flow<FollowMeState?> = server.flow(convoyId)
        override suspend fun setFollowMe(convoyId: String, active: Boolean): Boolean {
            server.toggle(convoyId, uid, active)
            return server.data[convoyId]?.leaderUid == uid
        }
        override suspend fun writeTrail(convoyId: String, polyline: String, activationId: String?): Boolean = server.write(convoyId, uid, polyline, activationId)
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
