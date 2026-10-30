package com.kungsbackacarcommunity.app.live

import com.kungsbackacarcommunity.app.profile.ClubBadge
import com.kungsbackacarcommunity.app.profile.MembershipMapRing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveMarkerAppearanceControllerTest {
    private val club = ClubBadge.fromField(mapOf("clubId" to "club-a", "clubName" to "Local Club"))!!
    private val paid = LiveMarkerProfile(MembershipMapRing(eligible = true), club)

    @Test fun `same drawn UID roster retains listeners across GPS updates and only roster deltas subscribe`() = runTest {
        val repo = FakeRepository()
        repo.profile("a").value = paid; repo.profile("b").value = paid
        val controller = LiveMarkerAppearanceController(repo, backgroundScope)
        controller.setRoster("viewer:1", setOf("a", "b")); runCurrent()
        repeat(100) { controller.setRoster("viewer:1", setOf("b", "a")) }; runCurrent()
        assertEquals(mapOf("a" to 1, "b" to 1), repo.opened)
        controller.setRoster("viewer:1", setOf("b", "c")); runCurrent()
        assertEquals(1, repo.closed["a"])
        assertEquals(1, repo.opened["b"])
        assertEquals(1, repo.opened["c"])
        assertFalse(controller.appearances.value.containsKey("a"))
        controller.close(); runCurrent()
        assertEquals(repo.opened, repo.closed)
    }

    @Test fun `server revocation removes ring with stationary roster and no extra profile reads`() = runTest {
        val repo = FakeRepository(); repo.profile("a").value = paid
        val controller = LiveMarkerAppearanceController(repo, backgroundScope)
        controller.setRoster("viewer:1", setOf("a")); runCurrent()
        assertTrue(controller.appearances.value.getValue("a").showMembershipMapRing)
        repo.profile("a").value = paid.copy(membershipMapRing = MembershipMapRing(eligible = false))
        runCurrent()
        assertFalse(controller.appearances.value.getValue("a").showMembershipMapRing)
        assertEquals(club, controller.appearances.value.getValue("a").clubBadge)
        assertEquals(mapOf("a" to 1), repo.opened)
        controller.close()
    }

    @Test fun `eligibility opt-out affiliation replacement removal and missing profile update live`() = runTest {
        val repo = FakeRepository(); repo.profile("a").value = paid
        val controller = LiveMarkerAppearanceController(repo, backgroundScope)
        controller.setRoster("viewer:1", setOf("a")); runCurrent()
        repo.profile("a").value = paid.copy(membershipMapRing = MembershipMapRing(true, show = false)); runCurrent()
        assertFalse(controller.appearances.value.getValue("a").showMembershipMapRing)
        val replacement = ClubBadge.fromField(mapOf("clubId" to "club-b", "clubName" to "New Club"))!!
        repo.profile("a").value = paid.copy(clubBadge = replacement); runCurrent()
        assertEquals(replacement, controller.appearances.value.getValue("a").clubBadge)
        repo.profile("a").value = paid.copy(clubBadge = null); runCurrent()
        assertNull(controller.appearances.value.getValue("a").clubBadge)
        assertTrue(controller.appearances.value.getValue("a").showMembershipMapRing)
        repo.profile("a").value = paid.copy(membershipMapRing = MembershipMapRing()); runCurrent()
        assertFalse(controller.appearances.value.getValue("a").showMembershipMapRing)
        repo.profile("a").value = null; runCurrent()
        assertTrue(controller.appearances.value.isEmpty())
        assertEquals(mapOf("a" to 1), repo.opened)
        controller.close()
    }

    @Test fun `account and same UID auth-session changes immediately clear data and detach old roster`() = runTest {
        val repo = FakeRepository(); repo.profile("a").value = paid
        val controller = LiveMarkerAppearanceController(repo, backgroundScope)
        controller.setRoster("viewer:1", setOf("a")); runCurrent()
        controller.setRoster("viewer:2", setOf("a"))
        assertTrue(controller.appearances.value.isEmpty())
        runCurrent(); assertEquals(2, repo.opened["a"]); assertEquals(1, repo.closed["a"])
        controller.setRoster("other:1", setOf("b"))
        assertTrue(controller.appearances.value.isEmpty()); runCurrent()
        repo.profile("a").value = paid.copy(clubBadge = null); runCurrent()
        assertFalse(controller.appearances.value.containsKey("a"))
        controller.setRoster(null, setOf("b")); runCurrent()
        assertTrue(controller.appearances.value.isEmpty())
        assertEquals(repo.opened, repo.closed)
    }

    @Test fun `stop releases listeners and resume observes current server boolean without a GPS fix`() = runTest {
        val repo = FakeRepository(); repo.profile("a").value = paid
        val controller = LiveMarkerAppearanceController(repo, backgroundScope)
        controller.setRoster("viewer:1", setOf("a")); runCurrent()
        controller.close(); runCurrent()
        assertTrue(controller.appearances.value.isEmpty()); assertEquals(repo.opened, repo.closed)
        repo.profile("a").value = paid.copy(membershipMapRing = MembershipMapRing(eligible = false))
        controller.setRoster("viewer:1", setOf("a")); runCurrent()
        assertFalse(controller.appearances.value.getValue("a").showMembershipMapRing)
        assertEquals(2, repo.opened["a"])
        controller.close()
    }

    @Test fun `roster budget is bounded deterministic and never subscribes malformed or undrawn UIDs`() = runTest {
        val repo = FakeRepository()
        val controller = LiveMarkerAppearanceController(repo, backgroundScope)
        val requested = (0..149).map { "uid-${it.toString().padStart(3, '0')}" }.toSet() + setOf("", "bad/path")
        controller.setRoster("viewer:1", requested); runCurrent()
        assertEquals(MAX_LIVE_MARKER_APPEARANCE_UIDS, repo.opened.size)
        assertEquals((0..99).map { "uid-${it.toString().padStart(3, '0')}" }.toSet(), repo.opened.keys)
        controller.close(); runCurrent(); assertEquals(repo.opened, repo.closed)
    }

    @Test fun `listener failure clears cosmetics rather than retaining a stale positive`() = runTest {
        val fail = CompletableDeferred<Unit>()
        val repository = object : LiveMarkerAppearanceRepository {
            override fun observeProfile(uid: String): Flow<LiveMarkerProfile?> = flow {
                emit(paid); fail.await(); error("Fixture read denial")
            }
        }
        val controller = LiveMarkerAppearanceController(repository, backgroundScope)
        controller.setRoster("viewer:1", setOf("a")); runCurrent()
        assertTrue(controller.appearances.value.getValue("a").showMembershipMapRing)
        fail.complete(Unit); runCurrent()
        assertTrue(controller.appearances.value.isEmpty())
        controller.close()
    }

    private class FakeRepository : LiveMarkerAppearanceRepository {
        val opened = mutableMapOf<String, Int>()
        val closed = mutableMapOf<String, Int>()
        private val data = mutableMapOf<String, MutableStateFlow<LiveMarkerProfile?>>()
        fun profile(uid: String) = data.getOrPut(uid) { MutableStateFlow(null) }
        override fun observeProfile(uid: String): Flow<LiveMarkerProfile?> = flow {
            opened[uid] = (opened[uid] ?: 0) + 1
            try { emitAll(profile(uid)) } finally { closed[uid] = (closed[uid] ?: 0) + 1 }
        }
    }
}
