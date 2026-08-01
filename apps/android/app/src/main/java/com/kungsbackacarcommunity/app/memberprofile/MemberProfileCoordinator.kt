package com.kungsbackacarcommunity.app.memberprofile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the read-only member-profile screen. Pure Kotlin (no Firebase / Compose)
 * so it is unit-testable with fakes.
 *
 * It is the blocking-decision layer: before reading the target's docs it checks
 * whether the viewer has blocked them (via [isBlocked], supplied by the route
 * from the viewer's own owner-scoped block list). A blocked target short-circuits
 * to [MemberProfileState.Blocked] — the profile is still withheld, but the state
 * is now distinct from the not-found [MemberProfileState.Unavailable] so the
 * screen can offer the viewer an Unblock action on their OWN block (see
 * [MemberProfileState.Blocked] for why that reveals nothing). The block check is
 * best-effort: if it cannot be determined the route passes `false`, and the
 * profile read still runs (the viewer only ever sees who *they* blocked, never
 * who blocked them, so this cannot leak a block).
 */
class MemberProfileCoordinator(
    private val targetUid: String,
    private val repository: MemberProfileRepository,
    private val isBlocked: suspend (String) -> Boolean = { false },
) {
    private val _state = MutableStateFlow<MemberProfileState>(MemberProfileState.Loading)
    val state: StateFlow<MemberProfileState> = _state.asStateFlow()

    /**
     * One-shot load; re-runnable (e.g. from a retry).
     *
     * @param consultBlockList false skips the [isBlocked] short-circuit for this
     *   pass. Used only by [reloadAfterUnblock] — see there for why re-reading the
     *   block list right after the viewer's own unblock is unsafe.
     */
    suspend fun load(consultBlockList: Boolean = true) {
        _state.value = MemberProfileState.Loading
        try {
            if (consultBlockList && isBlocked(targetUid)) {
                _state.value = MemberProfileState.Blocked
                return
            }
            _state.value =
                when (val result = repository.loadMemberProfile(targetUid)) {
                    is MemberProfileResult.Loaded ->
                        MemberProfileState.Loaded(
                            result.profile,
                            result.vehicles,
                            result.badges,
                            result.pointsBalance,
                        )

                    MemberProfileResult.NotFound -> MemberProfileState.Unavailable
                    MemberProfileResult.Error -> MemberProfileState.Error
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            _state.value = MemberProfileState.Error
        }
    }

    /**
     * Reflects a block the viewer just completed successfully.
     *
     * Deliberately does NOT re-read the block list. The block is written by the
     * `blocking-block` CALLABLE, not by the client, so it gets no local latency
     * compensation: a block-list listener subscribed immediately afterwards can
     * legitimately serve the pre-block cached snapshot first, and a reload would
     * then land back on the loaded profile — the user's block apparently ignored.
     * The caller only invokes this once the callable has reported success, which
     * is strictly more authoritative than any snapshot, stale or not.
     */
    fun markBlocked() {
        _state.value = MemberProfileState.Blocked
    }

    /**
     * Re-loads the profile after the viewer successfully unblocked, skipping the
     * block check for that pass — the mirror of [markBlocked], and for the same
     * reason: a stale cached snapshot still listing the block would bounce the
     * viewer straight back to [MemberProfileState.Blocked] and make the unblock
     * look like it failed. The successful `blocking-unblock` call is the
     * authority; subsequent loads consult the (by then settled) list as normal.
     */
    suspend fun reloadAfterUnblock() = load(consultBlockList = false)
}
