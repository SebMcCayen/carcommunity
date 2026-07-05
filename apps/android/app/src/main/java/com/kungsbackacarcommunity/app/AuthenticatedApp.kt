package com.kungsbackacarcommunity.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kungsbackacarcommunity.app.home.HomeScreen
import com.kungsbackacarcommunity.app.onboarding.OnboardingCoordinator
import com.kungsbackacarcommunity.app.onboarding.OnboardingScreen
import com.kungsbackacarcommunity.app.onboarding.OnboardingStatus
import com.kungsbackacarcommunity.app.profile.AuthedDestination
import com.kungsbackacarcommunity.app.profile.ProfileEditCoordinator
import com.kungsbackacarcommunity.app.profile.ProfileEditStatus
import com.kungsbackacarcommunity.app.profile.ProfileRepository
import com.kungsbackacarcommunity.app.profile.ProfileScreen
import com.kungsbackacarcommunity.app.profile.ProfileState
import com.kungsbackacarcommunity.app.profile.authedDestination
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The signed-in experience (Phase 12 slice 2): observes the profile
 * document to gate onboarding, then routes between the home shell and the
 * profile screen.
 *
 * Integration layer — the routing decision ([authedDestination]) and every
 * screen it shows are independently unit/UI-tested; this composable only
 * wires repositories to those pieces. Repositories are nullable so the
 * no-Firebase (Unavailable) build still renders the main shell.
 */
@Composable
fun AuthenticatedApp(
    uid: String,
    authDisplayName: String?,
    profileRepository: ProfileRepository?,
    onboardingCoordinator: OnboardingCoordinator?,
    profileEditCoordinator: ProfileEditCoordinator?,
    onSignOut: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val profileFlow =
        remember(uid, profileRepository) {
            profileRepository?.observeProfile(uid) ?: flowOf(ProfileState.Unavailable)
        }
    val profileState by profileFlow.collectAsState(initial = ProfileState.Loading)

    when (authedDestination(profileState)) {
        AuthedDestination.Loading -> LoadingScreen()

        AuthedDestination.Onboarding -> {
            val onboardingStatus by
                (onboardingCoordinator?.status ?: flowOf(OnboardingStatus.Idle))
                    .collectAsState(initial = OnboardingStatus.Idle)
            OnboardingScreen(
                status = onboardingStatus,
                onSubmit = { name ->
                    onboardingCoordinator?.let { c -> scope.launch { c.submit(name) } }
                },
            )
        }

        AuthedDestination.Main -> {
            val profile = (profileState as? ProfileState.Loaded)?.profile
            val saveStatus by
                (profileEditCoordinator?.status ?: flowOf(ProfileEditStatus.Idle))
                    .collectAsState(initial = ProfileEditStatus.Idle)
            var showProfile by rememberSaveable { mutableStateOf(false) }

            if (showProfile) {
                ProfileScreen(
                    profile = profile,
                    saveStatus = saveStatus,
                    onSave = { name, bio ->
                        profileEditCoordinator?.let { c -> scope.launch { c.save(uid, name, bio) } }
                    },
                    onBack = {
                        showProfile = false
                        profileEditCoordinator?.reset()
                    },
                    onSignOut = onSignOut,
                )
            } else {
                HomeScreen(
                    displayName = profile?.displayName ?: authDisplayName,
                    onSignOut = onSignOut,
                    onOpenProfile = { showProfile = true },
                )
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator()
        }
    }
}
