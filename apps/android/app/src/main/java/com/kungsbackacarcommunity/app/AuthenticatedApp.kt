package com.kungsbackacarcommunity.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.config.FeatureFlag
import com.kungsbackacarcommunity.app.design.LocalSnackbarHostState
import com.kungsbackacarcommunity.app.account.AccountDeletionCoordinator
import com.kungsbackacarcommunity.app.account.AccountDeletionRoute
import com.kungsbackacarcommunity.app.badges.BadgesRepository
import com.kungsbackacarcommunity.app.badges.BadgesRoute
import com.kungsbackacarcommunity.app.blocking.BlockingRepository
import com.kungsbackacarcommunity.app.blocking.BlockingRoute
import com.kungsbackacarcommunity.app.drives.DrivesRepository
import com.kungsbackacarcommunity.app.drives.DrivesRoute
import com.kungsbackacarcommunity.app.billboards.BillboardsRepository
import com.kungsbackacarcommunity.app.billboards.BillboardsRoute
import com.kungsbackacarcommunity.app.chat.ChatCoordinator
import com.kungsbackacarcommunity.app.chat.EventChatRepository
import com.kungsbackacarcommunity.app.config.FeatureFlags
import com.kungsbackacarcommunity.app.config.FeatureGate
import com.kungsbackacarcommunity.app.crownhunt.CrownHuntCoordinator
import com.kungsbackacarcommunity.app.crownhunt.CrownHuntRepository
import com.kungsbackacarcommunity.app.crownhunt.CrownHuntRoute
import com.kungsbackacarcommunity.app.events.EventsRepository
import com.kungsbackacarcommunity.app.events.EventsRoute
import com.kungsbackacarcommunity.app.events.RsvpCoordinator
import com.kungsbackacarcommunity.app.feedback.FeedbackCoordinator
import com.kungsbackacarcommunity.app.feedback.FeedbackReportRoute
import com.kungsbackacarcommunity.app.garage.GarageCoordinator
import com.kungsbackacarcommunity.app.garage.GarageRepository
import com.kungsbackacarcommunity.app.garage.GarageRoute
import com.kungsbackacarcommunity.app.groupdrive.GroupDriveCoordinator
import com.kungsbackacarcommunity.app.groupdrive.GroupDriveRepository
import com.kungsbackacarcommunity.app.notifications.NotificationsCoordinator
import com.kungsbackacarcommunity.app.notifications.NotificationSettingsCoordinator
import com.kungsbackacarcommunity.app.notifications.NotificationSettingsRepository
import com.kungsbackacarcommunity.app.notifications.NotificationSettingsRoute
import com.kungsbackacarcommunity.app.notifications.NotificationsRepository
import com.kungsbackacarcommunity.app.notifications.NotificationsRoute
import com.kungsbackacarcommunity.app.notifications.currentPushPermissionStatus
import com.kungsbackacarcommunity.app.notifications.openAppNotificationSettings
import com.kungsbackacarcommunity.app.partners.OfferCodeCoordinator
import com.kungsbackacarcommunity.app.partners.PartnerApplicationCoordinator
import com.kungsbackacarcommunity.app.partners.PartnerApplicationRoute
import com.kungsbackacarcommunity.app.partners.PartnersRepository
import com.kungsbackacarcommunity.app.partners.PartnersRoute
import com.kungsbackacarcommunity.app.points.PointsRepository
import com.kungsbackacarcommunity.app.points.PointsRoute
import com.kungsbackacarcommunity.app.privacy.PartnerStatsCoordinator
import com.kungsbackacarcommunity.app.privacy.PartnerStatsRepository
import com.kungsbackacarcommunity.app.privacy.PartnerStatsRoute
import com.kungsbackacarcommunity.app.live.LiveActionStatus
import com.kungsbackacarcommunity.app.live.LiveLocation
import com.kungsbackacarcommunity.app.live.LiveLocationCoordinator
import com.kungsbackacarcommunity.app.live.LiveLocationRepository
import com.kungsbackacarcommunity.app.live.LiveLocationScreen
import com.kungsbackacarcommunity.app.live.LiveSessionDuration
import com.kungsbackacarcommunity.app.location.BackgroundLocationController
import com.kungsbackacarcommunity.app.map.MapRoute
import com.kungsbackacarcommunity.app.media.ImageUploadCoordinator
import com.kungsbackacarcommunity.app.media.ImageUploadStatus
import com.kungsbackacarcommunity.app.media.MediaUpload
import com.kungsbackacarcommunity.app.media.MediaUploader
import com.kungsbackacarcommunity.app.media.rememberImagePickLauncher
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
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
import com.kungsbackacarcommunity.app.push.PushRegistrationCoordinator
import com.kungsbackacarcommunity.app.shell.HubEntry
import com.kungsbackacarcommunity.app.shell.HubScreen
import com.kungsbackacarcommunity.app.shell.LiveShareAction
import com.kungsbackacarcommunity.app.shell.LiveShareToggle
import com.kungsbackacarcommunity.app.shell.MapHome
import com.kungsbackacarcommunity.app.shell.ShellBackResult
import com.kungsbackacarcommunity.app.shell.ShellNavigation
import com.kungsbackacarcommunity.app.shell.ShellRoute
import com.kungsbackacarcommunity.app.shell.ShellTab
import com.kungsbackacarcommunity.app.shell.rememberMapSurface
import com.kungsbackacarcommunity.app.subscription.BillingRepository
import com.kungsbackacarcommunity.app.subscription.SubscriptionRoute
import com.kungsbackacarcommunity.app.subscription.SubscriptionVerifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The signed-in experience: observes the profile document to gate onboarding,
 * then renders the **map-first, 5-tab shell** ([mapFirstShell]) once onboarded.
 *
 * Integration layer — the routing decision ([authedDestination]) and every
 * screen it shows are independently unit/UI-tested; this composable only wires
 * repositories to those pieces. Repositories are nullable so the no-Firebase
 * (Unavailable) build still renders the main shell.
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
    groupDriveRepository: GroupDriveRepository?,
    groupDriveCoordinator: GroupDriveCoordinator?,
    crownHuntRepository: CrownHuntRepository?,
    crownHuntCoordinator: CrownHuntCoordinator?,
    partnersRepository: PartnersRepository?,
    offerCodeCoordinator: OfferCodeCoordinator?,
    notificationsRepository: NotificationsRepository?,
    notificationsCoordinator: NotificationsCoordinator?,
    notificationSettingsRepository: NotificationSettingsRepository?,
    notificationSettingsCoordinator: NotificationSettingsCoordinator?,
    garageRepository: GarageRepository?,
    garageCoordinator: GarageCoordinator?,
    mediaUploader: MediaUploader?,
    badgesRepository: BadgesRepository?,
    blockingRepository: BlockingRepository?,
    drivesRepository: DrivesRepository?,
    pointsRepository: PointsRepository?,
    partnerApplicationCoordinator: PartnerApplicationCoordinator?,
    billboardsRepository: BillboardsRepository?,
    accountDeletionCoordinator: AccountDeletionCoordinator?,
    partnerStatsRepository: PartnerStatsRepository?,
    partnerStatsCoordinator: PartnerStatsCoordinator?,
    feedbackCoordinator: FeedbackCoordinator?,
    billingRepository: BillingRepository?,
    subscriptionVerifier: SubscriptionVerifier?,
    pushRegistrationCoordinator: PushRegistrationCoordinator?,
    flags: FeatureFlags,
    onSignOut: () -> Unit,
    nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    val scope = rememberCoroutineScope()

    // Sign-in-time push-token registration: best-effort, once per signed-in
    // uid; failures stay inside the coordinator and never block the UI.
    LaunchedEffect(uid, pushRegistrationCoordinator) {
        pushRegistrationCoordinator?.registerCurrentToken()
    }

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

            // Selected bottom-nav tab (Map is the default home) and the
            // currently-open full-screen sub-route (null = show the tab).
            var selectedTab by rememberSaveable { mutableStateOf(ShellTab.DEFAULT) }
            var route by rememberSaveable { mutableStateOf<ShellRoute?>(null) }

            // Group-drive "show on map": stash roster uids, switch to the Map
            // tab. Preserved for the real Mapbox impl (the stub surfaces only a
            // count); mirrors the old MapRoute participant wiring.
            var mapParticipantUids by
                rememberSaveable { mutableStateOf<ArrayList<String>>(ArrayList()) }

            val snackbarHostState = remember { SnackbarHostState() }
            // Real Mapbox surface when a token is configured (on device),
            // else the neutral stub (config-less / CI) — see rememberMapSurface.
            val mapSurface = rememberMapSurface()
            val context = LocalContext.current
            // Resolved in composition (lint: resource lookups must not use
            // LocalContext.current) so the click lambdas can show them.
            val comingSoonText = stringResource(R.string.shell_comingSoon)
            val unavailableText = stringResource(R.string.shell_unavailable)

            // Flag-gated (not member-gated) reach to the live-location feature.
            val liveLocationEnabled =
                FeatureGate.isAvailable(
                    flags = flags,
                    flag = FeatureFlag.LIVE_LOCATION,
                    memberGated = false,
                    isActiveMember = profile?.activeMember == true,
                )
            // Starting a session is member-gated (backend parity).
            val canShareLive =
                FeatureGate.isAvailable(
                    flags = flags,
                    flag = FeatureFlag.LIVE_LOCATION,
                    memberGated = true,
                    isActiveMember = profile?.activeMember == true,
                )

            // Own live-location session drives the floating toggle's colour +
            // action (wired to the REAL live-location state).
            val liveSession by
                remember(uid, liveLocationRepository) {
                    liveLocationRepository?.observeOwnSession(uid) ?: flowOf(null)
                }
                    .collectAsState(initial = null)
            // Re-evaluate at expiry: a single nowMillis() snapshot would keep
            // isSharing == true if the app stays open past expiresAtMillis with
            // no other recomposition. Schedule one delay-to-expiry that flips
            // the state to false exactly when the session actually expires.
            val isSharing by
                produceState(
                    initialValue = LiveLocation.isSharing(liveSession, nowMillis()),
                    liveSession,
                ) {
                    value = LiveLocation.isSharing(liveSession, nowMillis())
                    val expiry = liveSession?.expiresAtMillis
                    if (value && expiry != null) {
                        val remaining = expiry - nowMillis()
                        if (remaining > 0) delay(remaining)
                        value = LiveLocation.isSharing(liveSession, nowMillis())
                    }
                }

            fun showComingSoon() {
                scope.launch {
                    snackbarHostState.showSnackbar(comingSoonText)
                }
            }

            CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
              Box(modifier = Modifier.fillMaxSize()) {
                // Single close path for the currently-open route, shared by
                // system-Back and each route's in-screen close so their teardown
                // can't drift. Closing the LiveLocation overlay tears down the
                // live session; closing the Map overlay also drops the stashed
                // group-drive roster so MapHome's participant chip doesn't linger
                // after the overlay is dismissed. reset()/stop() are idempotent,
                // so routing every close through here is safe.
                val closeRoute = {
                    when (route) {
                        ShellRoute.LiveLocation -> {
                            liveLocationCoordinator?.reset()
                            BackgroundLocationController.stop(context)
                        }
                        ShellRoute.Map -> mapParticipantUids = ArrayList()
                        else -> Unit
                    }
                    route = null
                }

                // System Back: close an open route first; from a non-Map tab
                // return to the Map tab; from Map exit the app (no handler). The
                // decision is delegated to the unit-tested ShellNavigation.onBack
                // so production back behaviour and its tests can't drift. Nested
                // route BackHandlers compose deeper and take priority while
                // enabled, so this only fires at a route's own root.
                val backResult = ShellNavigation.onBack(selectedTab, route)
                BackHandler(enabled = backResult != ShellBackResult.Exit) {
                    when (backResult) {
                        ShellBackResult.CloseRoute -> closeRoute()
                        ShellBackResult.GoToMapTab -> selectedTab = ShellTab.Map
                        // Enabled is false in the Exit case, so this is unreachable;
                        // returning here lets the system perform the default exit.
                        ShellBackResult.Exit -> Unit
                    }
                }

                if (route != null) {
                    RouteHost(
                        route = route!!,
                        uid = uid,
                        profileActiveMember = profile?.activeMember == true,
                        scope = scope,
                        onClose = closeRoute,
                        onOpenRoute = { route = it },
                        onSignOut = onSignOut,
                        // repositories / coordinators
                        profile = profile,
                        profileRepository = profileRepository,
                        profileEditCoordinator = profileEditCoordinator,
                        mediaUploader = mediaUploader,
                        liveLocationRepository = liveLocationRepository,
                        liveLocationCoordinator = liveLocationCoordinator,
                        nowMillis = nowMillis,
                        canShareLive = canShareLive,
                        eventsRepository = eventsRepository,
                        rsvpCoordinator = rsvpCoordinator,
                        chatRepository = chatRepository,
                        chatCoordinator = chatCoordinator,
                        chatEnabled =
                            FeatureGate.isAvailable(
                                flags = flags,
                                flag = FeatureFlag.CHAT,
                                memberGated = false,
                                isActiveMember = profile?.activeMember == true,
                            ),
                        groupDriveRepository = groupDriveRepository,
                        groupDriveCoordinator = groupDriveCoordinator,
                        mapParticipantUids = mapParticipantUids,
                        // Group-drive "show on map" opens the REAL Mapbox map as
                        // a full-screen overlay (existing map/ code, kept behind
                        // ShellRoute.Map) — the map-first home tab uses the
                        // MapSurface stub, this preserves the roster view.
                        onShowOnMap =
                            if (liveLocationRepository != null) {
                                { uids ->
                                    mapParticipantUids = ArrayList(uids)
                                    route = ShellRoute.Map
                                }
                            } else {
                                null
                            },
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
                        badgesRepository = badgesRepository,
                        blockingRepository = blockingRepository,
                        pointsRepository = pointsRepository,
                        partnerApplicationCoordinator = partnerApplicationCoordinator,
                        billboardsRepository = billboardsRepository,
                        accountDeletionCoordinator = accountDeletionCoordinator,
                        partnerStatsRepository = partnerStatsRepository,
                        partnerStatsCoordinator = partnerStatsCoordinator,
                        feedbackCoordinator = feedbackCoordinator,
                        billingRepository = billingRepository,
                        subscriptionVerifier = subscriptionVerifier,
                        // gates for the More hub
                        partnerStatsEnabled =
                            FeatureGate.isAvailable(
                                flags = flags,
                                flag = FeatureFlag.PARTNER_STATS,
                                memberGated = false,
                                isActiveMember = profile?.activeMember == true,
                            ),
                    )
                } else {
                    Scaffold(
                        // Each tab manages its own top inset (the map is
                        // full-bleed); the nav bar handles the bottom inset.
                        contentWindowInsets = WindowInsets(0),
                        bottomBar = {
                            ShellBottomBar(
                                selected = selectedTab,
                                onSelect = { selectedTab = it },
                            )
                        },
                    ) { padding ->
                        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                            when (selectedTab) {
                                ShellTab.Map ->
                                    MapHome(
                                        mapSurface = mapSurface,
                                        isLiveSharing = isSharing,
                                        participantCount = mapParticipantUids.size,
                                        userLabel =
                                            stringResource(R.string.shell_userMarkerLabel),
                                        onSearch = { showComingSoon() },
                                        onVoiceSearch = { showComingSoon() },
                                        onToggleLiveShare = {
                                            when (
                                                LiveShareToggle.action(
                                                    isSharing = isSharing,
                                                    canShare = canShareLive,
                                                    wired = liveLocationCoordinator != null,
                                                )
                                            ) {
                                                LiveShareAction.Start -> {
                                                    liveLocationCoordinator?.let { c ->
                                                        scope.launch {
                                                            c.start(LiveSessionDuration.ONE_HOUR)
                                                        }
                                                        BackgroundLocationController.start(context)
                                                    }
                                                }
                                                LiveShareAction.Stop -> {
                                                    liveLocationCoordinator?.let { c ->
                                                        scope.launch { c.stop() }
                                                        BackgroundLocationController.stop(context)
                                                    }
                                                }
                                                LiveShareAction.OpenScreen ->
                                                    if (liveLocationRepository != null) {
                                                        route = ShellRoute.LiveLocation
                                                    } else {
                                                        scope.launch {
                                                            snackbarHostState.showSnackbar(
                                                                unavailableText,
                                                            )
                                                        }
                                                    }
                                            }
                                        },
                                        // Layers control toggles the traffic
                                        // overlay (visible only on the real
                                        // Mapbox surface; a no-op on the stub).
                                        onLayers = {
                                            mapSurface.setTrafficEnabled(
                                                !mapSurface.trafficEnabled.value,
                                            )
                                        },
                                        onRecenter = { mapSurface.recenter() },
                                        onMusic = { showComingSoon() },
                                        // "Create route" opens the Create hub
                                        // (create event / share live location).
                                        onCreateRoute = { selectedTab = ShellTab.Create },
                                        onOpenMore = { route = ShellRoute.More },
                                    )

                                ShellTab.History ->
                                    if (drivesRepository != null) {
                                        DrivesRoute(
                                            repository = drivesRepository,
                                            uid = uid,
                                            isActiveMember = profile?.activeMember == true,
                                            onBack = { selectedTab = ShellTab.Map },
                                        )
                                    } else {
                                        HubScreen(
                                            title = stringResource(R.string.shell_tabHistory),
                                            entries = emptyList(),
                                        )
                                    }

                                ShellTab.Create ->
                                    HubScreen(
                                        title = stringResource(R.string.shell_createTitle),
                                        entries =
                                            listOf(
                                                HubEntry(
                                                    label = stringResource(R.string.shell_createEvent),
                                                    icon = Icons.Filled.Event,
                                                    onClick =
                                                        if (eventsRepository != null) {
                                                            { route = ShellRoute.Events }
                                                        } else {
                                                            null
                                                        },
                                                ),
                                                HubEntry(
                                                    label =
                                                        stringResource(R.string.shell_startLiveLocation),
                                                    icon = Icons.Filled.Podcasts,
                                                    onClick =
                                                        if (liveLocationRepository != null &&
                                                            liveLocationEnabled
                                                        ) {
                                                            { route = ShellRoute.LiveLocation }
                                                        } else {
                                                            null
                                                        },
                                                ),
                                            ),
                                    )

                                ShellTab.Social ->
                                    HubScreen(
                                        title = stringResource(R.string.shell_socialTitle),
                                        entries =
                                            listOf(
                                                HubEntry(
                                                    stringResource(R.string.shell_socialEvents),
                                                    Icons.Filled.Event,
                                                    if (eventsRepository != null) {
                                                        { route = ShellRoute.Events }
                                                    } else {
                                                        null
                                                    },
                                                ),
                                                HubEntry(
                                                    stringResource(R.string.shell_socialNotifications),
                                                    Icons.Filled.Notifications,
                                                    if (notificationsRepository != null) {
                                                        { route = ShellRoute.Notifications }
                                                    } else {
                                                        null
                                                    },
                                                ),
                                                HubEntry(
                                                    stringResource(R.string.shell_socialCrownHunt),
                                                    Icons.Filled.EmojiEvents,
                                                    if (crownHuntRepository != null &&
                                                        FeatureGate.isAvailable(
                                                            flags = flags,
                                                            flag = FeatureFlag.CROWN_HUNT,
                                                            memberGated = false,
                                                            isActiveMember = profile?.activeMember == true,
                                                        )
                                                    ) {
                                                        { route = ShellRoute.CrownHunt }
                                                    } else {
                                                        null
                                                    },
                                                ),
                                                HubEntry(
                                                    stringResource(R.string.shell_socialPartners),
                                                    Icons.Filled.Storefront,
                                                    if (partnersRepository != null &&
                                                        FeatureGate.isAvailable(
                                                            flags = flags,
                                                            flag = FeatureFlag.PARTNERS,
                                                            memberGated = false,
                                                            isActiveMember = profile?.activeMember == true,
                                                        )
                                                    ) {
                                                        { route = ShellRoute.Partners }
                                                    } else {
                                                        null
                                                    },
                                                ),
                                                HubEntry(
                                                    stringResource(R.string.shell_socialBillboards),
                                                    Icons.Filled.Campaign,
                                                    if (billboardsRepository != null &&
                                                        FeatureGate.isAvailable(
                                                            flags = flags,
                                                            flag = FeatureFlag.DIGITAL_BILLBOARDS,
                                                            memberGated = false,
                                                            isActiveMember = profile?.activeMember == true,
                                                        )
                                                    ) {
                                                        { route = ShellRoute.Billboards }
                                                    } else {
                                                        null
                                                    },
                                                ),
                                            ),
                                    )

                                ShellTab.Garage ->
                                    HubScreen(
                                        title = stringResource(R.string.shell_garageTitle),
                                        entries =
                                            listOf(
                                                HubEntry(
                                                    stringResource(R.string.shell_garageVehicles),
                                                    Icons.Filled.DirectionsCar,
                                                    if (garageRepository != null &&
                                                        profile?.activeMember == true
                                                    ) {
                                                        { route = ShellRoute.Garage }
                                                    } else {
                                                        null
                                                    },
                                                ),
                                                HubEntry(
                                                    stringResource(R.string.shell_garageBadges),
                                                    Icons.Filled.MilitaryTech,
                                                    if (badgesRepository != null) {
                                                        { route = ShellRoute.Badges }
                                                    } else {
                                                        null
                                                    },
                                                ),
                                                HubEntry(
                                                    stringResource(R.string.shell_garagePoints),
                                                    Icons.Filled.Stars,
                                                    if (pointsRepository != null) {
                                                        { route = ShellRoute.Points }
                                                    } else {
                                                        null
                                                    },
                                                ),
                                                HubEntry(
                                                    stringResource(R.string.shell_garageSubscription),
                                                    Icons.Filled.CardMembership,
                                                    if (billingRepository != null &&
                                                        subscriptionVerifier != null
                                                    ) {
                                                        { route = ShellRoute.Subscription }
                                                    } else {
                                                        null
                                                    },
                                                ),
                                            ),
                                    )
                            }
                        }
                    }
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier =
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                )
              }
            }
        }
    }
}

/** The 5-tab bottom navigation; Map is the default, highlighted home tab. */
@Composable
private fun ShellBottomBar(
    selected: ShellTab,
    onSelect: (ShellTab) -> Unit,
) {
    // 50%-alpha surface container so the map shows through the bar; icon-only
    // items (no labels) keep the tabs compact over the semi-transparent map.
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
    ) {
        NavigationBarItem(
            selected = selected == ShellTab.Map,
            onClick = { onSelect(ShellTab.Map) },
            icon = { Icon(Icons.Filled.Map, contentDescription = stringResource(R.string.shell_tabMap)) },
            label = null,
        )
        NavigationBarItem(
            selected = selected == ShellTab.History,
            onClick = { onSelect(ShellTab.History) },
            icon = { Icon(Icons.Filled.History, contentDescription = stringResource(R.string.shell_tabHistory)) },
            label = null,
        )
        NavigationBarItem(
            selected = selected == ShellTab.Create,
            onClick = { onSelect(ShellTab.Create) },
            icon = { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.shell_tabCreate)) },
            label = null,
        )
        NavigationBarItem(
            selected = selected == ShellTab.Social,
            onClick = { onSelect(ShellTab.Social) },
            icon = { Icon(Icons.Filled.Groups, contentDescription = stringResource(R.string.shell_tabSocial)) },
            label = null,
        )
        NavigationBarItem(
            selected = selected == ShellTab.Garage,
            onClick = { onSelect(ShellTab.Garage) },
            icon = { Icon(Icons.Filled.DirectionsCar, contentDescription = stringResource(R.string.shell_tabGarage)) },
            label = null,
        )
    }
}

/**
 * Renders the currently-open full-screen [route] over the tab shell. Each route
 * keeps the exact repository wiring + null-guards from the previous shell; its
 * own back affordance calls [onClose] to return to the tab hub. The "More" hub
 * (top-bar avatar) lists the profile/settings/account destinations, each of
 * which re-opens via [onOpenRoute].
 */
@Composable
private fun RouteHost(
    route: ShellRoute,
    uid: String,
    profileActiveMember: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    onClose: () -> Unit,
    onOpenRoute: (ShellRoute) -> Unit,
    onSignOut: () -> Unit,
    profile: com.kungsbackacarcommunity.app.profile.UserProfile?,
    profileRepository: ProfileRepository?,
    profileEditCoordinator: ProfileEditCoordinator?,
    mediaUploader: MediaUploader?,
    liveLocationRepository: LiveLocationRepository?,
    liveLocationCoordinator: LiveLocationCoordinator?,
    nowMillis: () -> Long,
    canShareLive: Boolean,
    eventsRepository: EventsRepository?,
    rsvpCoordinator: RsvpCoordinator?,
    chatRepository: EventChatRepository?,
    chatCoordinator: ChatCoordinator?,
    chatEnabled: Boolean,
    groupDriveRepository: GroupDriveRepository?,
    groupDriveCoordinator: GroupDriveCoordinator?,
    mapParticipantUids: List<String>,
    onShowOnMap: ((List<String>) -> Unit)?,
    crownHuntRepository: CrownHuntRepository?,
    crownHuntCoordinator: CrownHuntCoordinator?,
    partnersRepository: PartnersRepository?,
    offerCodeCoordinator: OfferCodeCoordinator?,
    notificationsRepository: NotificationsRepository?,
    notificationsCoordinator: NotificationsCoordinator?,
    notificationSettingsRepository: NotificationSettingsRepository?,
    notificationSettingsCoordinator: NotificationSettingsCoordinator?,
    garageRepository: GarageRepository?,
    garageCoordinator: GarageCoordinator?,
    badgesRepository: BadgesRepository?,
    blockingRepository: BlockingRepository?,
    pointsRepository: PointsRepository?,
    partnerApplicationCoordinator: PartnerApplicationCoordinator?,
    billboardsRepository: BillboardsRepository?,
    accountDeletionCoordinator: AccountDeletionCoordinator?,
    partnerStatsRepository: PartnerStatsRepository?,
    partnerStatsCoordinator: PartnerStatsCoordinator?,
    feedbackCoordinator: FeedbackCoordinator?,
    billingRepository: BillingRepository?,
    subscriptionVerifier: SubscriptionVerifier?,
    partnerStatsEnabled: Boolean,
) {
    val context = LocalContext.current
    when (route) {
        ShellRoute.More ->
            HubScreen(
                title = stringResource(R.string.shell_moreTitle),
                onBack = onClose,
                entries =
                    listOf(
                        HubEntry(
                            stringResource(R.string.shell_moreProfile),
                            Icons.Filled.Person,
                            if (profileEditCoordinator != null) {
                                { onOpenRoute(ShellRoute.Profile) }
                            } else {
                                null
                            },
                        ),
                        HubEntry(
                            stringResource(R.string.shell_moreNotificationSettings),
                            Icons.Filled.NotificationsActive,
                            if (notificationSettingsRepository != null) {
                                { onOpenRoute(ShellRoute.NotificationSettings) }
                            } else {
                                null
                            },
                        ),
                        HubEntry(
                            stringResource(R.string.shell_moreBlocked),
                            Icons.Filled.Block,
                            if (blockingRepository != null) {
                                { onOpenRoute(ShellRoute.Blocked) }
                            } else {
                                null
                            },
                        ),
                        HubEntry(
                            stringResource(R.string.shell_morePartnerApplication),
                            Icons.Filled.BusinessCenter,
                            if (partnerApplicationCoordinator != null) {
                                { onOpenRoute(ShellRoute.PartnerApplication) }
                            } else {
                                null
                            },
                        ),
                        HubEntry(
                            stringResource(R.string.shell_morePartnerStats),
                            Icons.Filled.BarChart,
                            if (partnerStatsRepository != null && partnerStatsEnabled) {
                                { onOpenRoute(ShellRoute.PartnerStats) }
                            } else {
                                null
                            },
                        ),
                        HubEntry(
                            stringResource(R.string.shell_moreFeedback),
                            Icons.Filled.BugReport,
                            if (feedbackCoordinator != null) {
                                { onOpenRoute(ShellRoute.Feedback) }
                            } else {
                                null
                            },
                        ),
                        HubEntry(
                            stringResource(R.string.shell_moreAccountDeletion),
                            Icons.Filled.DeleteForever,
                            if (accountDeletionCoordinator != null) {
                                { onOpenRoute(ShellRoute.AccountDeletion) }
                            } else {
                                null
                            },
                        ),
                        HubEntry(
                            stringResource(R.string.shell_moreSignOut),
                            Icons.AutoMirrored.Filled.Logout,
                            onSignOut,
                        ),
                    ),
            )

        ShellRoute.Profile -> {
            val saveStatus by
                (profileEditCoordinator?.status ?: flowOf(ProfileEditStatus.Idle))
                    .collectAsState(initial = ProfileEditStatus.Idle)
            val avatarCoordinator =
                remember(mediaUploader) {
                    mediaUploader?.let {
                        ImageUploadCoordinator(it, MediaUpload.PROFILE_IMAGE_MAX_BYTES)
                    }
                }
            val avatarStatus by
                (avatarCoordinator?.status ?: flowOf(ImageUploadStatus.Idle))
                    .collectAsState(initial = ImageUploadStatus.Idle)
            val avatarUrl = rememberStorageImageUrl(context, profile?.avatarPath)
            val avatarPicker =
                rememberImagePickLauncher(
                    maxBytes = MediaUpload.PROFILE_IMAGE_MAX_BYTES,
                ) { picked ->
                    val repo = profileRepository
                    if (picked != null && avatarCoordinator != null && repo != null) {
                        val imageId = MediaUpload.newImageId(picked.contentType)
                        val path = MediaUpload.profileImagePath(uid, imageId)
                        avatarCoordinator.upload(picked, path) { storedPath ->
                            repo.updateAvatarPath(uid, storedPath)
                        }
                    }
                }
            ProfileScreen(
                profile = profile,
                saveStatus = saveStatus,
                onSave = { name, bio ->
                    profileEditCoordinator?.let { c -> scope.launch { c.save(uid, name, bio) } }
                },
                onBack = {
                    onClose()
                    profileEditCoordinator?.reset()
                    avatarCoordinator?.reset()
                },
                onSignOut = onSignOut,
                avatarUrl = avatarUrl,
                avatarUploadStatus = avatarStatus,
                onChangeAvatar =
                    if (avatarCoordinator != null && profileRepository != null) {
                        {
                            avatarCoordinator.reset()
                            avatarPicker.pickImage()
                        }
                    } else {
                        null
                    },
            )
        }

        ShellRoute.LiveLocation -> {
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
                canShare = canShareLive,
                onStart = { d ->
                    liveLocationCoordinator?.let { c ->
                        scope.launch { c.start(d) }
                        BackgroundLocationController.start(context)
                    }
                },
                onStop = {
                    liveLocationCoordinator?.let { c -> scope.launch { c.stop() } }
                    BackgroundLocationController.stop(context)
                },
                onHideMeNow = {
                    liveLocationCoordinator?.let { c -> scope.launch { c.hideMeNow() } }
                    BackgroundLocationController.stop(context)
                },
                // onClose routes through the shared closeRoute handler, which
                // performs this LiveLocation teardown (reset + stop) itself.
                onBack = onClose,
            )
        }

        ShellRoute.Map ->
            // The real Mapbox map, used for the group-drive roster overlay. The
            // token guard lives in MapRoute; it renders an empty style (no
            // crash) when no token is configured, keeping the config-less build
            // green. The map-first home tab uses the MapSurface stub instead.
            MapRoute(
                repository = liveLocationRepository,
                uid = uid,
                participantUids = mapParticipantUids,
                onBack = onClose,
            )

        ShellRoute.Events ->
            if (eventsRepository != null) {
                EventsRoute(
                    repository = eventsRepository,
                    rsvpCoordinator = rsvpCoordinator,
                    uid = uid,
                    isActiveMember = profileActiveMember,
                    chatRepository = chatRepository,
                    chatCoordinator = chatCoordinator,
                    chatEnabled = chatEnabled,
                    groupDriveRepository = groupDriveRepository,
                    groupDriveCoordinator = groupDriveCoordinator,
                    onShowOnMap = onShowOnMap,
                    onBack = onClose,
                    blockingRepository = blockingRepository,
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.CrownHunt ->
            if (crownHuntRepository != null) {
                CrownHuntRoute(
                    repository = crownHuntRepository,
                    coordinator = crownHuntCoordinator,
                    isActiveMember = profileActiveMember,
                    onBack = onClose,
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.Partners ->
            if (partnersRepository != null) {
                PartnersRoute(
                    repository = partnersRepository,
                    offerCodeCoordinator = offerCodeCoordinator,
                    uid = uid,
                    isActiveMember = profileActiveMember,
                    onBack = onClose,
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.Notifications ->
            if (notificationsRepository != null) {
                NotificationsRoute(
                    repository = notificationsRepository,
                    coordinator = notificationsCoordinator,
                    uid = uid,
                    onBack = onClose,
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.NotificationSettings ->
            if (notificationSettingsRepository != null) {
                NotificationSettingsRoute(
                    repository = notificationSettingsRepository,
                    coordinator = notificationSettingsCoordinator,
                    uid = uid,
                    pushPermission = currentPushPermissionStatus(context),
                    onOpenSystemSettings = { openAppNotificationSettings(context) },
                    onBack = onClose,
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.Garage ->
            if (garageRepository != null) {
                GarageRoute(
                    repository = garageRepository,
                    coordinator = garageCoordinator,
                    uid = uid,
                    isActiveMember = profileActiveMember,
                    onBack = onClose,
                    mediaUploader = mediaUploader,
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.Badges ->
            if (badgesRepository != null) {
                BadgesRoute(
                    repository = badgesRepository,
                    uid = uid,
                    onBack = onClose,
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.Blocked ->
            if (blockingRepository != null) {
                BlockingRoute(
                    repository = blockingRepository,
                    uid = uid,
                    onBack = onClose,
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.Points ->
            if (pointsRepository != null) {
                PointsRoute(
                    repository = pointsRepository,
                    uid = uid,
                    onBack = onClose,
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.PartnerApplication ->
            if (partnerApplicationCoordinator != null) {
                PartnerApplicationRoute(
                    coordinator = partnerApplicationCoordinator,
                    onBack = onClose,
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.Billboards ->
            if (billboardsRepository != null) {
                BillboardsRoute(
                    repository = billboardsRepository,
                    onBack = onClose,
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.AccountDeletion ->
            if (accountDeletionCoordinator != null) {
                AccountDeletionRoute(
                    coordinator = accountDeletionCoordinator,
                    onDeleted = onSignOut,
                    onBack = onClose,
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.PartnerStats ->
            if (partnerStatsRepository != null) {
                PartnerStatsRoute(
                    repository = partnerStatsRepository,
                    coordinator = partnerStatsCoordinator,
                    uid = uid,
                    onBack = onClose,
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.Feedback ->
            if (feedbackCoordinator != null) {
                FeedbackReportRoute(
                    coordinator = feedbackCoordinator,
                    onBack = onClose,
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.Subscription ->
            if (billingRepository != null && subscriptionVerifier != null) {
                SubscriptionRoute(
                    billing = billingRepository,
                    verifier = subscriptionVerifier,
                    isActiveMember = profileActiveMember,
                    onBack = onClose,
                )
            } else {
                LoadingScreen()
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
