package com.kungsbackacarcommunity.app.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SupporterBadgePreferenceCoordinator(private val savePreference: suspend (Boolean) -> Unit) {
    private val state = MutableStateFlow<ProfileEditStatus>(ProfileEditStatus.Idle)
    val status = state.asStateFlow()

    suspend fun save(show: Boolean) {
        if (state.value == ProfileEditStatus.Saving) return
        state.value = ProfileEditStatus.Saving
        try {
            savePreference(show)
            state.value = ProfileEditStatus.Saved
        } catch (cancelled: CancellationException) {
            state.value = ProfileEditStatus.Idle
            throw cancelled
        } catch (_: Exception) {
            state.value = ProfileEditStatus.Failed
        }
    }
}
