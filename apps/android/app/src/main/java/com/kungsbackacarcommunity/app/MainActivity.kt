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
import com.kungsbackacarcommunity.app.badges.FirebaseBadgesRepository
import com.kungsbackacarcommunity.app.chat.ChatCoordinator
import com.kungsbackacarcommunity.app.chat.FirebaseEventChatRepository
import com.kungsbackacarcommunity.app.crownhunt.CrownHuntCoordinator
import com.kungsbackacarcommunity.app.crownhunt.FirebaseCrownHuntRepository
import com.kungsbackacarcommunity.app.events.FirebaseEventsRepository
import com.kungsbackacarcommunity.app.events.RsvpCoordinator
import com.kungsbackacarcommunity.app.garage.FirebaseGarageRepository
import com.kungsbackacarcommunity.app.garage.GarageCoordinator
import com.kungsbackacarcommunity.app.groupdrive.FirebaseGroupDriveRepository
import com.kungsbackacarcommunity.app.groupdrive.GroupDriveCoordinator
import com.kungsbackacarcommunity.app.notifications.FirebaseNotificationsRepository
import com.kungsbackacarcommunity.app.notifications.NotificationsCoordinator
import com.kungsbackacarcommunity.app.points.FirebasePointsRepository
import com.kungsbackacarcommunity.app.partners.FirebasePartnersRepository
import com.kungsbackacarcommunity.app.partners.OfferCodeCoordinator
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
        val chatRepository = FirebaseEventChatRepository.createIfAvailable(applicationContext)
        val chatCoordinator = chatRepository?.let { ChatCoordinator(it) }
        val groupDriveRepository = FirebaseGroupDriveRepository.createIfAvailable(applicationContext)
        val groupDriveCoordinator = groupDriveRepository?.let { GroupDriveCoordinator(it) }
        val crownHuntRepository = FirebaseCrownHuntRepository.createIfAvailable(applicationContext)
        val crownHuntCoordinator = crownHuntRepository?.let { CrownHuntCoordinator(it) }
        val partnersRepository = FirebasePartnersRepository.createIfAvailable(applicationContext)
        val offerCodeCoordinator = partnersRepository?.let { OfferCodeCoordinator(it) }
        val notificationsRepository =
            FirebaseNotificationsRepository.createIfAvailable(applicationContext)
        val notificationsCoordinator =
            notificationsRepository?.let { NotificationsCoordinator(it) }
        val garageRepository = FirebaseGarageRepository.createIfAvailable(applicationContext)
        val garageCoordinator = garageRepository?.let { GarageCoordinator(it) }
        val badgesRepository = FirebaseBadgesRepository.createIfAvailable(applicationContext)
        val pointsRepository = FirebasePointsRepository.createIfAvailable(applicationContext)

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
                        chatRepository = chatRepository,
                        chatCoordinator = chatCoordinator,
                        groupDriveRepository = groupDriveRepository,
                        groupDriveCoordinator = groupDriveCoordinator,
                        crownHuntRepository = crownHuntRepository,
                        crownHuntCoordinator = crownHuntCoordinator,
                        partnersRepository = partnersRepository,
                        offerCodeCoordinator = offerCodeCoordinator,
                        notificationsRepository = notificationsRepository,
                        notificationsCoordinator = notificationsCoordinator,
                        garageRepository = garageRepository,
                        garageCoordinator = garageCoordinator,
                        badgesRepository = badgesRepository,
                        pointsRepository = pointsRepository,
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
