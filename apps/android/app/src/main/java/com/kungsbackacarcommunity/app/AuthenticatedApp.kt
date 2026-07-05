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
import com.kungsbackacarcommunity.app.config.FeatureFlag
import com.kungsbackacarcommunity.app.chat.ChatCoordinator
import com.kungsbackacarcommunity.app.chat.EventChatRepository
import com.kungsbackacarcommunity.app.config.FeatureFlags
import com.kungsbackacarcommunity.app.config.FeatureGate
import com.kungsbackacarcommunity.app.events.EventsRepository
import com.kungsbackacarcommunity.app.events.EventsRoute
import com.kungsbackacarcommunity.app.events.RsvpCoordinator
import com.kungsbackacarcommunity.app.home.HomeScreen
import com.kungsbackacarcommunity.app.live.LiveActionStatus
import com.kungsbackacarcommunity.app.live.LiveLocationCoordinator
import com.kungsbackacarcommunity.app.live.LiveLocationRepository
import com.kungsbackacarcommunity.app.live.LiveLocationScreen
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
    liveLocationRepository: LiveLocationRepository?,
    liveLocationCoordinator: LiveLocationCoordinator?,
    eventsRepository: EventsRepository?,
    rsvpCoordinator: RsvpCoordinator?,
    chatRepository: EventChatRepository?,
    chatCoordinator: ChatCoordinator?,
    flags: FeatureFlags,
    onSignOut: () -> Unit,
    nowMillis: () -> Long = { System.currentTimeMillis() },
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
            var destination by rememberSaveable { mutableStateOf(MainDestination.Home) }

            // Flag-gated (not member-gated): reaching the live-location screen.
            // Sharing itself is member-gated inside the screen (backend parity).
            val liveLocationEnabled =
                FeatureGate.isAvailable(
                    flags = flags,
                    flag = FeatureFlag.LIVE_LOCATION,
                    memberGated = false,
                    isActiveMember = profile?.activeMember == true,
                )

            when (destination) {
                MainDestination.Profile -> {
                    val saveStatus by
                        (profileEditCoordinator?.status ?: flowOf(ProfileEditStatus.Idle))
                            .collectAsState(initial = ProfileEditStatus.Idle)
                    ProfileScreen(
                        profile = profile,
                        saveStatus = saveStatus,
                        onSave = { name, bio ->
                            profileEditCoordinator?.let { c -> scope.launch { c.save(uid, name, bio) } }
                        },
                        onBack = {
                            destination = MainDestination.Home
                            profileEditCoordinator?.reset()
                        },
                        onSignOut = onSignOut,
                    )
                }

                MainDestination.LiveLocation -> {
                    val session by
                        remember(uid, liveLocationRepository) {
                            liveLocationRepository?.observeOwnSession(uid) ?: flowOf(null)
                        }
                            .collectAsState(initial = null)
                    val actionStatus by
                        (liveLocationCoordinator?.status ?: flowOf(LiveActionStatus.Idle))
                            .collectAsState(initial = LiveActionStatus.Idle)
                    LiveLocationScreen(
                        session = session,
                        nowMillis = nowMillis(),
                        actionStatus = actionStatus,
                        // Sharing requires membership (backend live.startSession
                        // is member-gated); the screen still offers hide-me-now.
                        canShare =
                            FeatureGate.isAvailable(
                                flags = flags,
                                flag = FeatureFlag.LIVE_LOCATION,
                                memberGated = true,
                                isActiveMember = profile?.activeMember == true,
                            ),
                        onStart = { d ->
                            liveLocationCoordinator?.let { c -> scope.launch { c.start(d) } }
                        },
                        onStop = {
                            liveLocationCoordinator?.let { c -> scope.launch { c.stop() } }
                        },
                        onHideMeNow = {
                            liveLocationCoordinator?.let { c -> scope.launch { c.hideMeNow() } }
                        },
                        onBack = {
                            destination = MainDestination.Home
                            liveLocationCoordinator?.reset()
                        },
                    )
                }

                MainDestination.Events -> {
                    if (eventsRepository != null) {
                        EventsRoute(
                            repository = eventsRepository,
                            rsvpCoordinator = rsvpCoordinator,
                            uid = uid,
                            isActiveMember = profile?.activeMember == true,
                            chatRepository = chatRepository,
                            chatCoordinator = chatCoordinator,
                            // Chat is flag-gated; member/RSVP eligibility is
                            // decided per-event inside the route.
                            chatEnabled =
                                FeatureGate.isAvailable(
                                    flags = flags,
                                    flag = FeatureFlag.CHAT,
                                    memberGated = false,
                                    isActiveMember = profile?.activeMember == true,
                                ),
                            onBack = { destination = MainDestination.Home },
                        )
                    } else {
                        // Unreachable: the Home entry is gated on eventsRepository
                        // != null. Render the shell rather than mutate state here.
                        LoadingScreen()
                    }
                }

                MainDestination.Home -> {
                    HomeScreen(
                        displayName = profile?.displayName ?: authDisplayName,
                        onSignOut = onSignOut,
                        // Only offer the Profile screen when editing is actually
                        // available (Firebase configured); otherwise Save is a no-op.
                        onOpenProfile =
                            if (profileEditCoordinator != null) {
                                { destination = MainDestination.Profile }
                            } else {
                                null
                            },
                        showLiveLocationTeaser = liveLocationEnabled,
                        // Entitlement-gated (as documented): active membership only.
                        showMemberValue = profile?.activeMember == true,
                        // Reachable when the flag is on and Firebase is configured.
                        onOpenLiveLocation =
                            if (liveLocationEnabled && liveLocationRepository != null) {
                                { destination = MainDestination.LiveLocation }
                            } else {
                                null
                            },
                        // Events are core (no feature flag); reachable when
                        // Firebase is configured.
                        onOpenEvents =
                            if (eventsRepository != null) {
                                { destination = MainDestination.Events }
                            } else {
                                null
                            },
                    )
                }
            }
        }
    }
}

/** In-app destinations within the authenticated Main shell (no NavHost yet). */
private enum class MainDestination { Home, Profile, LiveLocation, Events }

@Composable
private fun LoadingScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator()
        }
    }
}
