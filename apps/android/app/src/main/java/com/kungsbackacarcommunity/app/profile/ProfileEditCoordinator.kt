package com.kungsbackacarcommunity.app.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing progress of a profile save. */
sealed interface ProfileEditStatus {
    data object Idle : ProfileEditStatus

    data object Saving : ProfileEditStatus

    data object Saved : ProfileEditStatus

    data object Failed : ProfileEditStatus
}

/**
 * Orchestrates a profile save (Phase 12 slice 2). Pure Kotlin so the flow
 * is unit-testable with a fake [ProfileRepository].
 */
class ProfileEditCoordinator(
    private val repository: ProfileRepository,
) {
    private val state = MutableStateFlow<ProfileEditStatus>(ProfileEditStatus.Idle)
    val status: StateFlow<ProfileEditStatus> = state.asStateFlow()

    /**
     * Saves once; re-entrant calls while saving are ignored.
     *
     * [social] must be the CANONICAL handles from
     * [ProfileValidation.Result.social] — this coordinator does not re-parse
     * member input, so passing raw text here would store it verbatim.
     */
    suspend fun save(
        uid: String,
        displayName: String,
        bio: String,
        social: SocialHandles = SocialHandles.EMPTY,
    ) {
        if (state.value == ProfileEditStatus.Saving) return
        state.value = ProfileEditStatus.Saving
        try {
            repository.updateProfile(uid, displayName, bio, social)
            state.value = ProfileEditStatus.Saved
        } catch (cancellation: CancellationException) {
            state.value = ProfileEditStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = ProfileEditStatus.Failed
        }
    }

    /** Resets to idle after the UI consumes a terminal (Saved/Failed) state. */
    fun reset() {
        val current = state.value
        if (current == ProfileEditStatus.Saved || current == ProfileEditStatus.Failed) {
            state.value = ProfileEditStatus.Idle
        }
    }
}
