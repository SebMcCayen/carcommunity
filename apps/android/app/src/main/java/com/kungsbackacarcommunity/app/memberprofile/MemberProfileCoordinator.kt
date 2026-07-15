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
 * from the viewer's own owner-scoped block list). A blocked target — like a
 * not-found one — collapses to the neutral [MemberProfileState.Unavailable] so
 * the UI never distinguishes "blocked" from "doesn't exist". The block check is
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

    /** One-shot load; re-runnable (e.g. from a retry). */
    suspend fun load() {
        _state.value = MemberProfileState.Loading
        try {
            if (isBlocked(targetUid)) {
                _state.value = MemberProfileState.Unavailable
                return
            }
            _state.value =
                when (val result = repository.loadMemberProfile(targetUid)) {
                    is MemberProfileResult.Loaded ->
                        MemberProfileState.Loaded(result.profile, result.vehicles, result.badges)

                    MemberProfileResult.NotFound -> MemberProfileState.Unavailable
                    MemberProfileResult.Error -> MemberProfileState.Error
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            _state.value = MemberProfileState.Error
        }
    }
}
