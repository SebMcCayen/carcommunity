package com.kungsbackacarcommunity.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.toArgb
import com.kungsbackacarcommunity.app.design.KccDarkColors
import com.kungsbackacarcommunity.app.design.KccLightColors
import com.kungsbackacarcommunity.app.design.ThemeController
import com.kungsbackacarcommunity.app.crownhunt.CrownHuntParticipationController
import com.kungsbackacarcommunity.app.crownhunt.CrownHuntParticipationPreferenceStore
import com.kungsbackacarcommunity.app.design.ThemePreference
import com.kungsbackacarcommunity.app.design.ThemePreferenceStore
import com.kungsbackacarcommunity.app.incidents.IncidentAgeFilterController
import com.kungsbackacarcommunity.app.incidents.IncidentAgeFilterPreferenceStore
import com.kungsbackacarcommunity.app.incidents.IncidentAgeOption
import com.kungsbackacarcommunity.app.map.MapZoomController
import com.kungsbackacarcommunity.app.map.MapZoomPreferenceStore
import com.kungsbackacarcommunity.app.navigation.GeoUriParser
import com.kungsbackacarcommunity.app.navigation.GeoUriTarget
import com.kungsbackacarcommunity.app.navigation.MapLinkNavigator
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.kungsbackacarcommunity.app.auth.AuthRepository
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
import com.kungsbackacarcommunity.app.badges.FirebaseBadgeProgressRepository
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
import com.kungsbackacarcommunity.app.leaderboard.FirebaseLeaderboardRepository
import com.kungsbackacarcommunity.app.notifications.FirebaseNotificationSettingsRepository
import com.kungsbackacarcommunity.app.notifications.FirebaseNotificationsRepository
import com.kungsbackacarcommunity.app.notifications.NotificationSettingsCoordinator
import com.kungsbackacarcommunity.app.notifications.NotificationsCoordinator
import com.kungsbackacarcommunity.app.points.FirebasePointsRepository
import com.kungsbackacarcommunity.app.privacy.FirebaseLeaderboardVisibilityRepository
import com.kungsbackacarcommunity.app.privacy.FirebasePartnerStatsRepository
import com.kungsbackacarcommunity.app.privacy.LeaderboardVisibilityCoordinator
import com.kungsbackacarcommunity.app.privacy.PartnerStatsCoordinator
import com.kungsbackacarcommunity.app.partners.FirebasePartnerApplicationRepository
import com.kungsbackacarcommunity.app.partners.FirebasePartnersRepository
import com.kungsbackacarcommunity.app.partners.OfferCodeCoordinator
import com.kungsbackacarcommunity.app.partners.PartnerApplicationCoordinator
import com.kungsbackacarcommunity.app.live.FirebaseLiveLocationRepository
import com.kungsbackacarcommunity.app.media.FirebaseMediaUploader
import com.kungsbackacarcommunity.app.media.StorageDownloadUrlCache
import com.kungsbackacarcommunity.app.live.LiveLocationCoordinator
import com.kungsbackacarcommunity.app.live.LiveShareStart
import com.kungsbackacarcommunity.app.onboarding.FirebaseOnboardingRepository
import com.kungsbackacarcommunity.app.onboarding.OnboardingCoordinator
import com.kungsbackacarcommunity.app.profile.FirebaseProfileRepository
import com.kungsbackacarcommunity.app.profile.ProfileEditCoordinator
import com.kungsbackacarcommunity.app.push.ActiveChatRegistry
import com.kungsbackacarcommunity.app.push.FirebasePushTokenRepository
import com.kungsbackacarcommunity.app.push.FirebasePushTokenSource
import com.kungsbackacarcommunity.app.push.KccMessagingService
import com.kungsbackacarcommunity.app.push.PushNavigator
import com.kungsbackacarcommunity.app.push.PushRegistrationCoordinator
import com.kungsbackacarcommunity.app.subscription.FirebaseSubscriptionVerifier
import com.kungsbackacarcommunity.app.subscription.FirebaseSubscriptionStateRepository
import com.kungsbackacarcommunity.app.shell.LiveSessionAnchor
import com.kungsbackacarcommunity.app.subscription.PlayBillingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    // Feature flags are refreshed on launch and every resume (mapping:
    // poll-on-focus is enough for MVP); the store starts at contract defaults.
    private val featureFlagsStore by lazy {
        FeatureFlagsStore(FirebaseFeatureFlagsRepository.createIfAvailable(applicationContext))
    }

    // The user's Automatic/Light/Dark choice (Settings -> Appearance).
    // Device-local SharedPreferences, so it is available before sign-in and
    // survives process death; collected in setContent so a change re-themes the
    // running app with no restart.
    private val themePreferenceStore by lazy { ThemePreferenceStore(applicationContext) }

    // The user's resting map-zoom choice ("how far away the focus is", map-layers
    // popup). Device-local SharedPreferences for the same reasons as the theme
    // store above: available before sign-in, survives process death, collected in
    // setContent so a change applies to the running map with no restart.
    private val mapZoomPreferenceStore by lazy { MapZoomPreferenceStore(applicationContext) }

    // The user's Trafikverket alert max-age filter (map-layers popup). Device-local
    // SharedPreferences for the same reasons as the stores above; collected in
    // setContent so a change re-filters the running alert layer with no restart.
    private val incidentAgeFilterPreferenceStore by lazy {
        IncidentAgeFilterPreferenceStore(applicationContext)
    }

    // Whether the user takes part in Kronjakt (Crown Hunt); default participating.
    // Device-local SharedPreferences for the same reasons as the stores above;
    // collected in setContent so opting in/out shows/hides the crown layer + UI on
    // the running map with no restart.
    private val crownHuntParticipationPreferenceStore by lazy {
        CrownHuntParticipationPreferenceStore(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cold start from a notification tap: park the link before the shell
        // composes, so it is already waiting when the shell starts collecting.
        // Guarded against savedInstanceState so a rotation does not replay a
        // navigation the member already performed (the Activity is recreated
        // with the SAME launch Intent).
        if (savedInstanceState == null) {
            publishPushDeepLink(intent)
            // Same cold-start reasoning for an incoming map link (the member
            // picked KCC from Android's "Open with"/default-handler chooser for
            // a geo:/google.navigation: URI): park the point before the shell
            // composes so it is already waiting, and guard on savedInstanceState
            // so a rotation does not replay a navigation already performed.
            publishMapLinkDeepLink(intent)
        }

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
        val leaderboardRepository =
            FirebaseLeaderboardRepository.createIfAvailable(applicationContext)
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
        val badgeProgressRepository =
            FirebaseBadgeProgressRepository.createIfAvailable(applicationContext)
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
        val leaderboardVisibilityRepository =
            FirebaseLeaderboardVisibilityRepository.createIfAvailable(applicationContext)
        val leaderboardVisibilityCoordinator =
            leaderboardVisibilityRepository?.let { LeaderboardVisibilityCoordinator(it) }
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
        val subscriptionStateRepository =
            FirebaseSubscriptionStateRepository.createIfAvailable(applicationContext)

        setContent {
            val authState =
                authRepository?.authState?.collectAsState()?.value ?: AuthState.Unavailable
            val signInStatus =
                signInCoordinator?.status?.collectAsState()?.value ?: SignInStatus.Idle
            val flags by featureFlagsStore.flags.collectAsState()

            // THE app-wide light/dark decision, made exactly once, here.
            // isSystemInDarkTheme() is an input to it — not the answer — so an
            // explicit Light/Dark preference wins over the system flipping
            // (scheduled sunset->sunrise, battery saver, manual toggle).
            // Everything downstream reads the result: KccTheme via AppRoot, the
            // OS bar tinting below, and the map's day/night default through
            // LocalKccDarkTheme.
            val themePreference by themePreferenceStore.preference.collectAsState()
            // Only Automatic reads the system theme, so an explicit Light/Dark
            // choice doesn't subscribe this composition to system dark-mode
            // flips it would ignore anyway — the sticky modes stay inert (no
            // wasted recomposition) rather than recomputing to the same value.
            // Still routed through resolveDark so it remains the ONE place the
            // dark/light decision is made (the Light/Dark arg is unused there).
            val systemInDark = if (themePreference == ThemePreference.SYSTEM) isSystemInDarkTheme() else false
            val appDark = themePreference.resolveDark(systemInDark)

            // Lets the Settings screen read and change the preference without
            // threading two more parameters through AuthenticatedApp and
            // RouteHost. Re-created when the preference changes so `preference`
            // reports the current value.
            val themeController =
                remember(themePreference) {
                    object : ThemeController {
                        override val preference = themePreference

                        override fun setPreference(preference: ThemePreference) {
                            themePreferenceStore.set(preference)
                        }
                    }
                }

            // Resting map-zoom preference, exposed to the map-layers popup the same
            // way as the theme: collected here so a change re-applies live, and
            // handed down through LocalMapZoomController rather than threaded through
            // AuthenticatedApp/MapHome. Re-created when the value changes so
            // `browsingZoom` reports the current one.
            val mapBrowsingZoom by mapZoomPreferenceStore.browsingZoom.collectAsState()
            val mapZoomController =
                remember(mapBrowsingZoom) {
                    object : MapZoomController {
                        override val browsingZoom = mapBrowsingZoom

                        override fun setBrowsingZoom(zoom: Double) {
                            mapZoomPreferenceStore.set(zoom)
                        }
                    }
                }

            // Kronjakt participation, exposed to the map-layers popup the same way
            // as the zoom above: collected here so opting in/out shows/hides the
            // crown layer + UI live, handed down through
            // LocalCrownHuntParticipationController. Re-created when the value
            // changes so `participating` reports the current one.
            val crownHuntParticipating by
                crownHuntParticipationPreferenceStore.participating.collectAsState()
            val crownHuntParticipationController =
                remember(crownHuntParticipating) {
                    object : CrownHuntParticipationController {
                        override val participating = crownHuntParticipating

                        override fun setParticipating(participating: Boolean) {
                            crownHuntParticipationPreferenceStore.set(participating)
                        }
                    }
                }

            // Trafikverket alert max-age filter, exposed to the map-layers popup the
            // same way as the zoom above: collected here so a change re-filters the
            // drawn alerts live, handed down through LocalIncidentAgeFilterController.
            val incidentMaxAge by incidentAgeFilterPreferenceStore.maxAge.collectAsState()
            val incidentAgeFilterController =
                remember(incidentMaxAge) {
                    object : IncidentAgeFilterController {
                        override val maxAge = incidentMaxAge

                        override fun setMaxAge(option: IncidentAgeOption) {
                            incidentAgeFilterPreferenceStore.set(option)
                        }
                    }
                }

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
                // The live-session bar's latched start is process-scoped for the
                // same reason and gets the same owner-uid teardown. The shell
                // itself already refuses to READ another account's anchor, so this
                // is the release for the case where no shell is left to overwrite
                // it: sign-out.
                LiveSessionAnchor.clearIfNotOwnedBy(signedInUid)
                // Same reasoning for the optimistic live-start overlay: it is
                // process-scoped, so signing out mid-start must not carry a "you
                // are sharing" claim into the next session. Only on sign-out
                // (null), never on a re-run with the same uid — an Activity
                // recreation must LEAVE a start in flight alone, which is half the
                // reason the overlay is process-scoped in the first place.
                if (signedInUid == null) LiveShareStart.clear()
            }

            // Live feature-flag delivery, scoped to the authenticated session.
            // config/featureFlags requires isAuthenticated() (firestore.rules),
            // so the realtime listener is only attached once a Firebase session
            // exists — attaching before sign-in would just hit permission-denied.
            // Keying on the uid tears the listener down on sign-out / account
            // switch (the flow's awaitClose removes the Firestore registration —
            // no leak) and re-attaches for the next session. This is the DURABLE
            // fix for the "stuck on defaults" bug: unlike the one-shot poll, the
            // listener auto-reconnects and re-delivers the real value the moment a
            // read succeeds, so an affected member's crownHuntSpawn flips true.
            LaunchedEffect(signedInUid) {
                if (signedInUid != null) featureFlagsStore.observe()
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
            //
            // [appDark] — not isSystemInDarkTheme() — so the bars follow the
            // user's theme preference along with the rest of the app.
            val displayDark = authState == AuthState.SignedOut || appDark
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
                darkTheme = appDark,
                themeController = themeController,
                mapZoomController = mapZoomController,
                crownHuntParticipationController = crownHuntParticipationController,
                incidentAgeFilterController = incidentAgeFilterController,
                onSignInClick = {
                    signInCoordinator?.let { coordinator ->
                        lifecycleScope.launch { coordinator.signIn() }
                    }
                },
                // signOut flips Firebase auth state; the authState listener
                // re-renders AppRoot back to the sign-in screen reactively.
                onSignOutClick = { signOut(authRepository, pushRegistrationCoordinator) },
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
                        leaderboardRepository = leaderboardRepository,
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
                        badgeProgressRepository = badgeProgressRepository,
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
                        leaderboardVisibilityRepository = leaderboardVisibilityRepository,
                        leaderboardVisibilityCoordinator = leaderboardVisibilityCoordinator,
                        feedbackCoordinator = feedbackCoordinator,
                        billingRepository = billingRepository,
                        subscriptionVerifier = subscriptionVerifier,
                        subscriptionStateRepository = subscriptionStateRepository,
                        pushRegistrationCoordinator = pushRegistrationCoordinator,
                        loginRecordCoordinator = loginRecordCoordinator,
                        flags = flags,
                        onSignOut = { signOut(authRepository, pushRegistrationCoordinator) },
                    )
                },
            )
        }
    }

    /**
     * Warm start from a notification tap. The messaging service launches
     * MainActivity with CLEAR_TOP | SINGLE_TOP and the manifest declares
     * launchMode="singleTop", so an already-running task is reused and the new
     * extras arrive here rather than in a fresh onCreate.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep getIntent() consistent with what was just handled; otherwise a
        // later recreation would resurrect the stale launch Intent.
        setIntent(intent)
        publishPushDeepLink(intent)
        // A map link delivered to the already-running (singleTop) task arrives
        // here rather than in a fresh onCreate; handle it the same way.
        publishMapLinkDeepLink(intent)
    }

    /** Hands a tapped notification's destination to the shell, if there is one. */
    private fun publishPushDeepLink(intent: Intent?) {
        KccMessagingService.deepLinkFrom(intent)?.let(PushNavigator::publish)
    }

    /**
     * Hands an incoming map link (geo: / google.navigation:) to the shell as an
     * in-app navigate-here point, if the Intent is one and carries a real
     * coordinate. Only ACTION_VIEW Intents are considered, so a plain launcher
     * start never triggers it. A free-text address query ([GeoUriTarget.Query])
     * is intentionally ignored — the chooser still offered a real maps app
     * alongside KCC, and geocoding an arbitrary address from a deep link is out
     * of scope (see GeoUriTarget.Query).
     */
    private fun publishMapLinkDeepLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        val target = GeoUriParser.parse(data.toString())
        if (target is GeoUriTarget.Point) MapLinkNavigator.publish(target)
    }

    /**
     * Signs out, unregistering this device's push token FIRST.
     *
     * Without the unregister, a shared or handed-on phone keeps receiving the
     * previous member's DMs indefinitely — the backend has no way to know the
     * device changed hands. [PushDisplay.shouldDisplay] already refuses to
     * DISPLAY them while signed out, but that is a client-side guard on data
     * that should never have been sent; this closes it at the source.
     *
     * The unregister is best-effort and time-bounded: it needs the auth token
     * that sign-out is about to invalidate, so it must run first, but a member
     * on a bad connection must never be trapped in a signed-in state by it.
     * On timeout or failure sign-out proceeds anyway, and the stale token is
     * then cleaned up on the server side the first time FCM reports it dead or
     * when the next member registers it (the hash doc id is per-token, so a new
     * sign-in on the same device writes the same document under the new uid).
     */
    private fun signOut(
        authRepository: AuthRepository?,
        pushRegistrationCoordinator: PushRegistrationCoordinator?,
    ) {
        // Local push state belongs to the departing member — drop it now so a
        // pending deep link cannot navigate whoever signs in next.
        PushNavigator.clear()
        // A pending map-link point is just a coordinate (no privacy weight), but
        // clear it for symmetry so no session change carries a stale navigation.
        MapLinkNavigator.clear()
        ActiveChatRegistry.clear()
        // Same reasoning for the resolved-image-URL cache: a Firebase download
        // URL is a bearer link — the token in it IS the authorisation — so the
        // departing member's cached URLs must not outlive their session here.
        StorageDownloadUrlCache.clear(this)

        if (pushRegistrationCoordinator == null) {
            authRepository?.signOut()
            return
        }
        lifecycleScope.launch {
            try {
                withTimeoutOrNull(PUSH_UNREGISTER_TIMEOUT_MS) {
                    pushRegistrationCoordinator.unregisterCurrentToken()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Best-effort; never block sign-out on it.
            } finally {
                authRepository?.signOut()
            }
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

    private companion object {
        /**
         * Upper bound on how long sign-out waits for the push-token unregister.
         * Short on purpose: sign-out must feel immediate, and a stale token has
         * a server-side fallback (see [signOut]).
         */
        const val PUSH_UNREGISTER_TIMEOUT_MS = 3_000L
    }
}
