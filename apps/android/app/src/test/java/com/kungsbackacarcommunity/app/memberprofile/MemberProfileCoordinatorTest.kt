package com.kungsbackacarcommunity.app.memberprofile

import com.kungsbackacarcommunity.app.badges.Badge
import com.kungsbackacarcommunity.app.garage.Vehicle
import com.kungsbackacarcommunity.app.garage.VehiclePowertrain
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberProfileCoordinatorTest {

    private class FakeRepo(
        var result: MemberProfileResult,
    ) : MemberProfileRepository {
        var calls = 0
        var lastTarget: String? = null

        override suspend fun loadMemberProfile(targetUid: String): MemberProfileResult {
            calls++
            lastTarget = targetUid
            return result
        }
    }

    private val profile = MemberProfile(uid = "u2", displayName = "Ada", bio = "Hi", avatarPath = null)
    private val car =
        Vehicle(
            id = "v1",
            make = "Volvo",
            model = "240",
            modelYear = 1989,
            powertrain = VehiclePowertrain.PETROL,
            engineDescription = null,
            isMainCar = true,
        )

    @Test
    fun loaded_result_maps_to_loaded_state() = runTest {
        val repo = FakeRepo(MemberProfileResult.Loaded(profile, listOf(car), MemberBadges.Available(emptyList())))
        val coordinator = MemberProfileCoordinator("u2", repo)

        coordinator.load()

        val state = coordinator.state.value
        assertTrue(state is MemberProfileState.Loaded)
        state as MemberProfileState.Loaded
        assertEquals(profile, state.profile)
        assertEquals(listOf(car), state.vehicles)
        assertEquals("u2", repo.lastTarget)
    }

    @Test
    fun badges_unavailable_passes_through() = runTest {
        val repo = FakeRepo(MemberProfileResult.Loaded(profile, emptyList(), MemberBadges.Unavailable))
        val coordinator = MemberProfileCoordinator("u2", repo)

        coordinator.load()

        val state = coordinator.state.value as MemberProfileState.Loaded
        assertEquals(MemberBadges.Unavailable, state.badges)
    }

    @Test
    fun badges_unknown_passes_through() = runTest {
        val repo = FakeRepo(MemberProfileResult.Loaded(profile, emptyList(), MemberBadges.Unknown))
        val coordinator = MemberProfileCoordinator("u2", repo)

        coordinator.load()

        val state = coordinator.state.value as MemberProfileState.Loaded
        assertEquals(MemberBadges.Unknown, state.badges)
    }

    @Test
    fun notFound_maps_to_unavailable() = runTest {
        val repo = FakeRepo(MemberProfileResult.NotFound)
        val coordinator = MemberProfileCoordinator("u2", repo)

        coordinator.load()

        assertEquals(MemberProfileState.Unavailable, coordinator.state.value)
    }

    @Test
    fun error_maps_to_error() = runTest {
        val repo = FakeRepo(MemberProfileResult.Error)
        val coordinator = MemberProfileCoordinator("u2", repo)

        coordinator.load()

        assertEquals(MemberProfileState.Error, coordinator.state.value)
    }

    @Test
    fun blocked_target_is_blocked_state_and_skips_read() = runTest {
        val repo = FakeRepo(MemberProfileResult.Loaded(profile, emptyList(), MemberBadges.Unavailable))
        val coordinator = MemberProfileCoordinator("u2", repo, isBlocked = { it == "u2" })

        coordinator.load()

        assertEquals(MemberProfileState.Blocked, coordinator.state.value)
        assertEquals("blocked target must not be fetched", 0, repo.calls)
    }

    @Test
    fun markBlocked_reflects_a_block_without_rereading_the_block_list() = runTest {
        // The block is written by the callable, so a block-list listener
        // subscribed right after can still serve the PRE-block snapshot. A
        // successful block must therefore be reflected from its own outcome —
        // here isBlocked still (staleley) says "not blocked", and the state must
        // be Blocked regardless.
        val repo = FakeRepo(MemberProfileResult.Loaded(profile, emptyList(), MemberBadges.Unavailable))
        val coordinator = MemberProfileCoordinator("u2", repo, isBlocked = { false })
        coordinator.load()

        coordinator.markBlocked()

        assertEquals(MemberProfileState.Blocked, coordinator.state.value)
    }

    @Test
    fun reloadAfterUnblock_ignores_a_stale_block_list_and_loads_the_profile() = runTest {
        // The mirror case: right after a successful unblock the cached list can
        // still list the block, which would bounce the viewer back to Blocked and
        // make the unblock look like it failed.
        val repo = FakeRepo(MemberProfileResult.Loaded(profile, listOf(car), MemberBadges.Unavailable))
        val coordinator = MemberProfileCoordinator("u2", repo, isBlocked = { true })
        coordinator.load()
        assertEquals(MemberProfileState.Blocked, coordinator.state.value)

        coordinator.reloadAfterUnblock()

        assertTrue(coordinator.state.value is MemberProfileState.Loaded)
        assertEquals(1, repo.calls)
    }

    @Test
    fun a_later_load_consults_the_block_list_again() = runTest {
        // reloadAfterUnblock's bypass is for that ONE pass only — it must not
        // leave the coordinator permanently ignoring blocks.
        val repo = FakeRepo(MemberProfileResult.Loaded(profile, emptyList(), MemberBadges.Unavailable))
        val coordinator = MemberProfileCoordinator("u2", repo, isBlocked = { true })

        coordinator.reloadAfterUnblock()
        assertTrue(coordinator.state.value is MemberProfileState.Loaded)

        coordinator.load()

        assertEquals(MemberProfileState.Blocked, coordinator.state.value)
    }

    @Test
    fun block_check_failure_surfaces_as_error() = runTest {
        val repo = FakeRepo(MemberProfileResult.Loaded(profile, emptyList(), MemberBadges.Unavailable))
        val coordinator =
            MemberProfileCoordinator("u2", repo, isBlocked = { error("block list read failed") })

        coordinator.load()

        assertEquals(MemberProfileState.Error, coordinator.state.value)
    }

    @Test
    fun badge_list_is_carried_when_available() = runTest {
        val badge = Badge(key = "first_event", fallbackName = "First meet", awardedAtMillis = 1L)
        val repo = FakeRepo(MemberProfileResult.Loaded(profile, emptyList(), MemberBadges.Available(listOf(badge))))
        val coordinator = MemberProfileCoordinator("u2", repo)

        coordinator.load()

        val badges = (coordinator.state.value as MemberProfileState.Loaded).badges
        assertTrue(badges is MemberBadges.Available)
        assertEquals(listOf(badge), (badges as MemberBadges.Available).badges)
    }
}
