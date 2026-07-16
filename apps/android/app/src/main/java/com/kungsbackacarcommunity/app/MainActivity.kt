package com.kungsbackacarcommunity.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.toArgb
import com.kungsbackacarcommunity.app.design.KccDarkColors
import com.kungsbackacarcommunity.app.design.KccLightColors
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.kungsbackacarcommunity.app.auth.AuthState
import com.kungsbackacarcommunity.app.auth.FirebaseAuthRepository
import com.kungsbackacarcommunity.app.auth.GoogleCredentialTokenProvider
import com.kungsbackacarcommunity.app.account.AccountDeletionCoordinator
import com.kungsbackacarcommunity.app.account.FirebaseAccountDeletionRepository
import com.kungsbackacarcommunity.app.auth.FirebaseLoginRecorder
import com.kungsbackacarcommunity.app.auth.LoginRecordCoordinator
import com.kungsbackacarcommunity.app.auth.NoopSignInFailureReporter
import com.kungsbackacarcommunity.app.auth.SignInCoordinator
import com.kungsbackacarcommunity.app.auth.SignInStatus
import com.kungsbackacarcommunity.app.diagnostics.DiagnosticsSignInFailureReporter
import com.kungsbackacarcommunity.app.diagnostics.FirebaseDiagnosticsReporter
import com.kungsbackacarcommunity.app.config.FeatureFlagsStore
import com.kungsbackacarcommunity.app.config.FirebaseFeatureFlagsRepository
import com.kungsbackacarcommunity.app.badges.FirebaseBadgesRepository
import com.kungsbackacarcommunity.app.blocking.FirebaseBlockingRepository
import com.kungsbackacarcommunity.app.friends.FirebaseFriendsRepository
import com.kungsbackacarcommunity.app.memberprofile.FirebaseMemberProfileRepository
import com.kungsbackacarcommunity.app.drives.FirebaseDrivesRepository
import com.kungsbackacarcommunity.app.drives.SingleSessionRecording
import com.kungsbackacarcommunity.app.billboards.FirebaseBillboardsRepository
import com.kungsbackacarcommunity.app.chat.ChatCoordinator
import com.kungsbackacarcommunity.app.chat.FirebaseEventChatRepository
import com.kungsbackacarcommunity.app.chatchannels.FirebaseCommunityChatRepository
import com.kungsbackacarcommunity.app.chatchannels.FirebaseConvoyChatRepository
import com.kungsbackacarcommunity.app.crownhunt.CrownHuntCoordinator
import com.kungsbackacarcommunity.app.crownhunt.FirebaseCrownHuntRepository
import com.kungsbackacarcommunity.app.convoy.FirebaseConvoyRepository
import com.kungsbackacarcommunity.app.dm.FirebaseDmRepository
import com.kungsbackacarcommunity.app.events.FirebaseEventsRepository
import com.kungsbackacarcommunity.app.events.RsvpCoordinator
import com.kungsbackacarcommunity.app.garage.FirebaseGarageRepository
import com.kungsbackacarcommunity.app.garage.GarageCoordinator
import com.kungsbackacarcommunity.app.feedback.FeedbackCoordinator
import com.kungsbackacarcommunity.app.feedback.FirebaseFeedbackRepository
import com.kungsbackacarcommunity.app.groupdrive.FirebaseGroupDriveRepository
import com.kungsbackacarcommunity.app.groupdrive.GroupDriveCoordinator
import com.kungsbackacarcommunity.app.notifications.FirebaseNotificationSettingsRepository
import com.kungsbackacarcommunity.app.notifications.FirebaseNotificationsRepository
import com.kungsbackacarcommunity.app.notifications.NotificationSettingsCoordinator
import com.kungsbackacarcommunity.app.notifications.NotificationsCoordinator
import com.kungsbackacarcommunity.app.points.FirebasePointsRepository
import com.kungsbackacarcommunity.app.privacy.FirebasePartnerStatsRepository
import com.kungsbackacarcommunity.app.privacy.PartnerStatsCoordinator
import com.kungsbackacarcommunity.app.partners.FirebasePartnerApplicationRepository
import com.kungsbackacarcommunity.app.partners.FirebasePartnersRepository
import com.kungsbackacarcommunity.app.partners.OfferCodeCoordinator
import com.kungsbackacarcommunity.app.partners.PartnerApplicationCoordinator
import com.kungsbackacarcommunity.app.live.FirebaseLiveLocationRepository
import com.kungsbackacarcommunity.app.media.FirebaseMediaUploader
import com.kungsbackacarcommunity.app.live.LiveLocationCoordinator
import com.kungsbackacarcommunity.app.onboarding.FirebaseOnboardingRepository
import com.kungsbackacarcommunity.app.onboarding.OnboardingCoordinator
import com.kungsbackacarcommunity.app.profile.FirebaseProfileRepository
import com.kungsbackacarcommunity.app.profile.ProfileEditCoordinator
import com.kungsbackacarcommunity.app.push.FirebasePushTokenRepository
import com.kungsbackacarcommunity.app.push.FirebasePushTokenSource
import com.kungsbackacarcommunity.app.push.PushRegistrationCoordinator
import com.kungsbackacarcommunity.app.subscription.FirebaseSubscriptionVerifier
import com.kungsbackacarcommunity.app.subscription.PlayBillingRepository
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Feature flags are refreshed on launch and every resume (mapping:
    // poll-on-focus is enough for MVP); the store starts at contract defaults.
    private val featureFlagsStore by lazy {
        FeatureFlagsStore(FirebaseFeatureFlagsRepository.createIfAvailable(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw edge-to-edge so map/content renders behind the system bars. The
        // OS navigation bar is tinted with the theme surface at 25% opacity (75%
        // transparent) so the map shows through it; that tint — and the bar icon
        // light/dark contrast — is applied from a theme-reactive effect in
        // setContent below, so it tracks auth-state navigation (e.g. the
        // forced-dark sign-in screen) rather than only the launch configuration.
        // isNavigationBarContrastEnforced is disabled (theme-independent) so the
        // platform does not overlay its own opaque scrim.
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        // Guarded Firebase wiring: each createIfAvailable returns null when
        // google-services.json is absent (CI/local validation builds), so the
        // app renders an unauthenticated shell instead of crashing.
        val authRepository = FirebaseAuthRepository.createIfAvailable(applicationContext)
        // Pre-auth sign-in failures are reported through the PUBLIC diagnostics
        // callable (featureArea sign_in); the backend files a deduplicated
        // GitHub issue. Guarded like the rest of the Firebase wiring — null in
        // config-less builds, in which case failures simply aren't reported.
        val signInFailureReporter =
            FirebaseDiagnosticsReporter.createIfAvailable(applicationContext)?.let { reporter ->
                DiagnosticsSignInFailureReporter(
                    reporter = reporter,
                    appVersion = BuildConfig.VERSION_NAME,
                    buildNumber = BuildConfig.VERSION_CODE.toString(),
                    osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                )
            }
        val signInCoordinator =
            authRepository?.let {
                SignInCoordinator(
                    tokenProvider = GoogleCredentialTokenProvider(this),
                    repository = it,
                    failureReporter = signInFailureReporter ?: NoopSignInFailureReporter,
                )
            }
        // Last-login recording: best-effort auth-recordLogin call once a user is
        // signed in (AuthenticatedApp invokes it), keeping userLifecycle/{uid}.lastLoginAt
        // fresh for the inactive-account sweep. Guarded like the rest of the
        // Firebase wiring — null in config-less builds.
        val loginRecordCoordinator =
            FirebaseLoginRecorder.createIfAvailable(applicationContext)
                ?.let { LoginRecordCoordinator(it) }
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
        val notificationSettingsRepository =
            FirebaseNotificationSettingsRepository.createIfAvailable(applicationContext)
        val notificationSettingsCoordinator =
            notificationSettingsRepository?.let { NotificationSettingsCoordinator(it) }
        val garageRepository = FirebaseGarageRepository.createIfAvailable(applicationContext)
        val garageCoordinator = garageRepository?.let { GarageCoordinator(it) }
        // Shared Cloud Storage uploader (avatar + vehicle photos); guarded.
        val mediaUploader = FirebaseMediaUploader.createIfAvailable(applicationContext)
        val badgesRepository = FirebaseBadgesRepository.createIfAvailable(applicationContext)
        val blockingRepository = FirebaseBlockingRepository.createIfAvailable(applicationContext)
        val friendsRepository = FirebaseFriendsRepository.createIfAvailable(applicationContext)
        val memberProfileRepository =
            FirebaseMemberProfileRepository.createIfAvailable(applicationContext)
        val dmRepository = FirebaseDmRepository.createIfAvailable(applicationContext)
        val convoyRepository = FirebaseConvoyRepository.createIfAvailable(applicationContext)
        val communityChatRepository =
            FirebaseCommunityChatRepository.createIfAvailable(applicationContext)
        val convoyChatRepository =
            FirebaseConvoyChatRepository.createIfAvailable(applicationContext)
        val drivesRepository = FirebaseDrivesRepository.createIfAvailable(applicationContext)
        val pointsRepository = FirebasePointsRepository.createIfAvailable(applicationContext)
        val partnerApplicationCoordinator =
            FirebasePartnerApplicationRepository.createIfAvailable(applicationContext)
                ?.let { PartnerApplicationCoordinator(it) }
        val billboardsRepository = FirebaseBillboardsRepository.createIfAvailable(applicationContext)
        val accountDeletionCoordinator =
            FirebaseAccountDeletionRepository.createIfAvailable(applicationContext)
                ?.let { AccountDeletionCoordinator(it) }
        val partnerStatsRepository =
            FirebasePartnerStatsRepository.createIfAvailable(applicationContext)
        val partnerStatsCoordinator = partnerStatsRepository?.let { PartnerStatsCoordinator(it) }
        // "Report a problem" → feedback.reportIssue callable (files a public
        // GitHub issue). Guarded like the rest of the Firebase wiring.
        val feedbackCoordinator =
            FirebaseFeedbackRepository.createIfAvailable(applicationContext)
                ?.let { FeedbackCoordinator(it) }
        // FCM token registration (Phase 12 slice 21, push portion): the
        // guarded callable repository + token source feed the coordinator,
        // which AuthenticatedApp invokes once a user is signed in. Null in
        // config-less builds like the rest of the Firebase wiring.
        val pushTokenRepository =
            FirebasePushTokenRepository.createIfAvailable(
                applicationContext,
                appVersion = BuildConfig.VERSION_NAME,
                buildNumber = BuildConfig.VERSION_CODE.toString(),
            )
        val pushTokenSource = FirebasePushTokenSource.createIfAvailable(applicationContext)
        val pushRegistrationCoordinator =
            if (pushTokenRepository != null && pushTokenSource != null) {
                PushRegistrationCoordinator(pushTokenRepository, pushTokenSource)
            } else {
                null
            }
        // Play Billing needs no google-services.json (public Maven, no Firebase);
        // the verifier is guarded like the other callables.
        val billingRepository = PlayBillingRepository.createIfAvailable(applicationContext)
        val subscriptionVerifier =
            FirebaseSubscriptionVerifier.createIfAvailable(applicationContext)

        setContent {
            val authState =
                authRepository?.authState?.collectAsState()?.value ?: AuthState.Unavailable
            val signInStatus =
                signInCoordinator?.status?.collectAsState()?.value ?: SignInStatus.Idle
            val flags by featureFlagsStore.flags.collectAsState()

            // Tear down a single-session drive recording when the signed-in user
            // goes away. The recording is process-scoped ON PURPOSE (its lifetime
            // is the live session's, which outlives the Activity — see
            // SingleSessionRecording), so a genuine teardown needs an explicit
            // trigger or its fused-location updates and in-memory drive would run
            // on with no UI left to resolve them.
            //
            // Keying on the signed-in uid is the semantic trigger, and it sits
            // HERE rather than inside AuthenticatedApp because sign-out unmounts
            // that composable outright (it swaps screens without recreating the
            // Activity, per the theme effect below) — an effect inside it could
            // never observe its own removal. It is also inherently
            // rotation-immune: a recreation re-runs this with the SAME uid, which
            // clearIfNotOwnedBy ignores, so a live recording and any pending
            // save/discard prompt survive. Sign-out (null) or an account switch
            // (different uid) tears it down; an unsaved drive is dropped there
            // deliberately, since it belongs to the departing account.
            val signedInUid = (authState as? AuthState.SignedIn)?.uid
            LaunchedEffect(signedInUid) {
                SingleSessionRecording.clearIfNotOwnedBy(signedInUid)
            }

            // Tint the OS bars from the CURRENTLY displayed theme, not once in
            // onCreate: auth-state navigation swaps screens without recreating
            // the Activity, and SignInScreen (signed-out) forces KccTheme's dark
            // scheme regardless of the system setting, while the authed /
            // unavailable shells follow the system theme (KccTheme default). So
            // the rendered surface is dark whenever the system is dark OR we are
            // on the forced-dark sign-in screen. Keying the effect on that
            // resolved darkness recomputes the nav-bar tint and the bar icon
            // (light/dark) contrast on every sign-in/out, so the OS bars always
            // match the surface actually drawn behind them.
            val displayDark = authState == AuthState.SignedOut || isSystemInDarkTheme()
            val window = window
            DisposableEffect(displayDark) {
                window.navigationBarColor = translucentSystemNavBarColor(displayDark)
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    // Dark surface -> light (non-light) bar icons, and vice versa.
                    isAppearanceLightStatusBars = !displayDark
                    isAppearanceLightNavigationBars = !displayDark
                }
                onDispose {}
            }

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
                        notificationSettingsRepository = notificationSettingsRepository,
                        notificationSettingsCoordinator = notificationSettingsCoordinator,
                        garageRepository = garageRepository,
                        garageCoordinator = garageCoordinator,
                        mediaUploader = mediaUploader,
                        badgesRepository = badgesRepository,
                        blockingRepository = blockingRepository,
                        friendsRepository = friendsRepository,
                        memberProfileRepository = memberProfileRepository,
                        dmRepository = dmRepository,
                        convoyRepository = convoyRepository,
                        communityChatRepository = communityChatRepository,
                        convoyChatRepository = convoyChatRepository,
                        drivesRepository = drivesRepository,
                        pointsRepository = pointsRepository,
                        partnerApplicationCoordinator = partnerApplicationCoordinator,
                        billboardsRepository = billboardsRepository,
                        accountDeletionCoordinator = accountDeletionCoordinator,
                        partnerStatsRepository = partnerStatsRepository,
                        partnerStatsCoordinator = partnerStatsCoordinator,
                        feedbackCoordinator = feedbackCoordinator,
                        billingRepository = billingRepository,
                        subscriptionVerifier = subscriptionVerifier,
                        pushRegistrationCoordinator = pushRegistrationCoordinator,
                        loginRecordCoordinator = loginRecordCoordinator,
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

    /**
     * The theme surface color at 25% alpha (75% transparent) for the given
     * darkness, so the OS navigation bar lets the map/surface behind it show
     * through. Called from a theme-reactive effect (see setContent) rather than
     * onCreate so it tracks auth-state navigation — e.g. the forced-dark sign-in
     * screen — not just configuration changes.
     */
    private fun translucentSystemNavBarColor(darkTheme: Boolean): Int {
        val surface =
            if (darkTheme) KccDarkColors.surfaceBackground else KccLightColors.surfaceBackground
        return surface.copy(alpha = 0.25f).toArgb()
    }
}
