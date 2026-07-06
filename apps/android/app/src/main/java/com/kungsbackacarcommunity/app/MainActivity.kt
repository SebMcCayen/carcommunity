package com.kungsbackacarcommunity.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.kungsbackacarcommunity.app.auth.AuthState
import com.kungsbackacarcommunity.app.auth.FirebaseAuthRepository
import com.kungsbackacarcommunity.app.auth.GoogleCredentialTokenProvider
import com.kungsbackacarcommunity.app.auth.SignInCoordinator
import com.kungsbackacarcommunity.app.auth.SignInStatus
import com.kungsbackacarcommunity.app.config.FeatureFlagsStore
import com.kungsbackacarcommunity.app.config.FirebaseFeatureFlagsRepository
import com.kungsbackacarcommunity.app.events.FirebaseEventsRepository
import com.kungsbackacarcommunity.app.events.RsvpCoordinator
import com.kungsbackacarcommunity.app.live.FirebaseLiveLocationRepository
import com.kungsbackacarcommunity.app.live.LiveLocationCoordinator
import com.kungsbackacarcommunity.app.onboarding.FirebaseOnboardingRepository
import com.kungsbackacarcommunity.app.onboarding.OnboardingCoordinator
import com.kungsbackacarcommunity.app.profile.FirebaseProfileRepository
import com.kungsbackacarcommunity.app.profile.ProfileEditCoordinator
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Feature flags are refreshed on launch and every resume (mapping:
    // poll-on-focus is enough for MVP); the store starts at contract defaults.
    private val featureFlagsStore by lazy {
        FeatureFlagsStore(FirebaseFeatureFlagsRepository.createIfAvailable(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Guarded Firebase wiring: each createIfAvailable returns null when
        // google-services.json is absent (CI/local validation builds), so the
        // app renders an unauthenticated shell instead of crashing.
        val authRepository = FirebaseAuthRepository.createIfAvailable(applicationContext)
        val signInCoordinator =
            authRepository?.let {
                SignInCoordinator(
                    tokenProvider = GoogleCredentialTokenProvider(this),
                    repository = it,
                )
            }
        val profileRepository = FirebaseProfileRepository.createIfAvailable(applicationContext)
        val onboardingCoordinator =
            FirebaseOnboardingRepository.createIfAvailable(applicationContext)
                ?.let { OnboardingCoordinator(it) }
        val profileEditCoordinator = profileRepository?.let { ProfileEditCoordinator(it) }
        val liveLocationRepository =
            FirebaseLiveLocationRepository.createIfAvailable(applicationContext)
        val liveLocationCoordinator =
            liveLocationRepository?.let { LiveLocationCoordinator(it) }
        val eventsRepository = FirebaseEventsRepository.createIfAvailable(applicationContext)
        val rsvpCoordinator = eventsRepository?.let { RsvpCoordinator(it) }

        setContent {
            val authState =
                authRepository?.authState?.collectAsState()?.value ?: AuthState.Unavailable
            val signInStatus =
                signInCoordinator?.status?.collectAsState()?.value ?: SignInStatus.Idle
            val flags by featureFlagsStore.flags.collectAsState()

            AppRoot(
                authState = authState,
                signInStatus = signInStatus,
                onSignInClick = {
                    signInCoordinator?.let { coordinator ->
                        lifecycleScope.launch { coordinator.signIn() }
                    }
                },
                // signOut flips Firebase auth state; the authState listener
                // re-renders AppRoot back to the sign-in screen reactively.
                onSignOutClick = { authRepository?.signOut() },
                signedInContent = { uid, displayName ->
                    AuthenticatedApp(
                        uid = uid,
                        authDisplayName = displayName,
                        profileRepository = profileRepository,
                        onboardingCoordinator = onboardingCoordinator,
                        profileEditCoordinator = profileEditCoordinator,
                        liveLocationRepository = liveLocationRepository,
                        liveLocationCoordinator = liveLocationCoordinator,
                        eventsRepository = eventsRepository,
                        rsvpCoordinator = rsvpCoordinator,
                        flags = flags,
                        onSignOut = { authRepository?.signOut() },
                    )
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { featureFlagsStore.refresh() }
    }
}
