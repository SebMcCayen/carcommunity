package com.kungsbackacarcommunity.app.clubs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Canonical CC paths, legacy Driver URLs and asynchronous private-scope boundaries. */
class ClubMemberAvatarHydrationTest {
    private val driverUrl = "https://example.com/current-driver.jpg"
    private val stored = ClubMember("member", "Canonical name", ClubRole.Moderator, driverUrl)

    @Test fun ccPathWinsOverDriverUrlAndLeavesRosterIdentityAndRoleUntouched() {
        val avatar = clubMemberAvatar("member", mapOf("avatarPath" to "profileImages/member/new.jpg", "avatarUrl" to driverUrl))
        val hydrated = stored.withAvatar(mapOf("member" to avatar))
        assertEquals("profileImages/member/new.jpg", hydrated.avatarPath)
        assertNull(hydrated.avatarUrl)
        assertEquals(stored.uid, hydrated.uid)
        assertEquals(stored.displayName, hydrated.displayName)
        assertEquals(stored.role, hydrated.role)
    }

    @Test fun absentCcFieldUsesCurrentDriverUrlAndAbsentPublicProfileKeepsCallableCopy() {
        assertEquals(ClubMemberAvatar(null, driverUrl), clubMemberAvatar("member", mapOf("avatarUrl" to driverUrl)))
        assertSame(stored, stored.withAvatar(emptyMap()))
        assertSame(stored, stored.withAvatar(mapOf("someone-else" to ClubMemberAvatar("profileImages/someone-else/x.jpg", null))))
    }

    @Test fun explicitCcNullOrDeletedFieldsNeverResurrectLegacyDriverImage() {
        assertEquals(ClubMemberAvatar(null, null), clubMemberAvatar("member", mapOf("avatarPath" to null, "avatarUrl" to driverUrl)))
        assertEquals(ClubMemberAvatar(null, null), clubMemberAvatar("member", emptyMap()))
        assertEquals(ClubMemberAvatar(null, null), clubMemberAvatar("member", mapOf("avatarUrl" to null)))
        val removed = stored.withAvatar(mapOf("member" to clubMemberAvatar("member", mapOf("avatarPath" to null))))
        assertNull(removed.avatarPath)
        assertNull(removed.avatarUrl)
    }

    @Test fun malformedCrossUserPathsAndNonHttpsDriverImagesAreNotLoaded() {
        for (path in listOf("", "profileImages/other/x.jpg", "profileImages/member/../other.jpg", "https://example.com/x.jpg")) {
            assertEquals(ClubMemberAvatar(null, null), clubMemberAvatar("member", mapOf("avatarPath" to path, "avatarUrl" to driverUrl)))
        }
        assertNull(clubMemberAvatar("member", mapOf("avatarUrl" to "javascript:alert(1)")).avatarUrl)
    }

    @Test fun oneBoundedPageReadIsDeduplicatedAndHydrationIsKeyedByUidNotResultOrder() = runTest {
        var requests = 0
        var guards = 0
        val roster = listOf(stored, stored.copy(uid = "other"), stored)
        val avatars = loadClubMemberAvatars("viewer", roster.map { it.uid }.toSet(), { "viewer" }, read = { ids ->
            requests++
            assertEquals(setOf("member", "other"), ids)
            mapOf("other" to mapOf("avatarUrl" to driverUrl), "member" to mapOf("avatarPath" to "profileImages/member/a.jpg"))
        }, requireCurrentAccess = { guards++ })
        val result = roster.map { it.withAvatar(avatars) }
        assertEquals(roster.map { it.uid }, result.map { it.uid })
        assertEquals("profileImages/member/a.jpg", result[0].avatarPath)
        assertEquals(driverUrl, result[1].avatarUrl)
        assertEquals(1, requests)
        assertEquals(1, guards)
    }

    @Test fun emptyPageDoesNotReadAndOverLimitPageFailsBeforeIo() = runTest {
        var calls = 0
        val read: suspend (Set<String>) -> Map<String, Map<String, Any?>> = { calls++; emptyMap() }
        assertTrue(loadClubMemberAvatars("viewer", emptySet(), { "viewer" }, read, { calls++ }).isEmpty())
        try {
            loadClubMemberAvatars("viewer", (1..26).map { "member$it" }.toSet(), { "viewer" }, read, { calls++ })
            fail("Over-limit page must not issue public reads")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, calls)
    }

    @Test fun accountSwitchDuringPublicReadRejectsLateResponseBeforePrivateGuard() = runTest {
        var account = "viewer"
        var guards = 0
        try {
            loadClubMemberAvatars("viewer", setOf("member"), { account }, read = {
                account = "other-account"
                mapOf("member" to mapOf("avatarPath" to "profileImages/member/a.jpg"))
            }, requireCurrentAccess = { guards++ })
            fail("A late old-account response must not paint the new account")
        } catch (error: ClubException) { assertEquals("unauthenticated", error.code) }
        assertEquals(0, guards)
    }

    @Test fun accessRevocationAfterReadAndAccountSwitchInsideGuardAreBothRejected() = runTest {
        var account = "viewer"
        val read: suspend (Set<String>) -> Map<String, Map<String, Any?>> = { mapOf("member" to mapOf("avatarUrl" to driverUrl)) }
        try {
            loadClubMemberAvatars("viewer", setOf("member"), { account }, read) { throw ClubException("permission-denied") }
            fail("Revoked club access must reject overlay")
        } catch (error: ClubException) { assertEquals("permission-denied", error.code) }
        try {
            loadClubMemberAvatars("viewer", setOf("member"), { account }, read) { account = "other" }
            fail("Account switch while verifying membership must reject overlay")
        } catch (error: ClubException) { assertEquals("unauthenticated", error.code) }
    }

    @Test fun profileFailureIsBestEffortButCancellationIsNotSwallowed() = runTest {
        var guards = 0
        assertTrue(loadClubMemberAvatars("viewer", setOf("member"), { "viewer" },
            read = { throw IllegalStateException("offline") }, requireCurrentAccess = { guards++ }).isEmpty())
        assertEquals(1, guards)
        try {
            loadClubMemberAvatars("viewer", setOf("member"), { "viewer" },
                read = { throw CancellationException("disposed") }, requireCurrentAccess = { guards++ })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(1, guards)
    }

    @Test fun asynchronousPreviousRosterAndClubResponsesCannotReplaceCurrentOverlay() = runTest {
        var activeClub = "old-club"
        var generation = 1
        val gate = CompletableDeferred<Map<String, ClubMemberAvatar>>()
        val late = async {
            currentClubAvatarPage(request = { gate.await() }, isCurrent = { activeClub == "old-club" && generation == 1 })
        }
        testScheduler.runCurrent()
        activeClub = "new-club"
        generation++
        gate.complete(mapOf("member" to ClubMemberAvatar("profileImages/member/old.jpg", null)))
        assertNull(late.await())
        val current = currentClubAvatarPage(request = { mapOf("member" to ClubMemberAvatar(null, null)) },
            isCurrent = { activeClub == "new-club" && generation == 2 })
        assertEquals(ClubMemberAvatar(null, null), current?.get("member"))
    }

    @Test fun disposedMembershipCannotPublishEvenWhenImageLoaderIgnoresCancellation() = runTest {
        val gate = CompletableDeferred<Map<String, ClubMemberAvatar>>()
        var painted = false
        val job = launch {
            val result = currentClubAvatarPage(
                request = { withContext(NonCancellable) { gate.await() } },
                isCurrent = { true },
            )
            if (result != null) painted = true
        }
        testScheduler.runCurrent()
        job.cancel()
        gate.complete(mapOf("member" to ClubMemberAvatar("profileImages/member/old.jpg", null)))
        job.join()
        assertFalse(painted)
    }

}
