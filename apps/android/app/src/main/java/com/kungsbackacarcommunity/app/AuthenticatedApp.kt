package com.kungsbackacarcommunity.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import com.kungsbackacarcommunity.app.dm.ChatRoute
import com.kungsbackacarcommunity.app.dm.ConversationListRoute
import com.kungsbackacarcommunity.app.dm.DmRepository
import com.kungsbackacarcommunity.app.events.EventsRepository
import com.kungsbackacarcommunity.app.events.EventsRoute
import com.kungsbackacarcommunity.app.events.RsvpCoordinator
import com.kungsbackacarcommunity.app.feedback.FeedbackCoordinator
import com.kungsbackacarcommunity.app.feedback.FeedbackReportRoute
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import com.kungsbackacarcommunity.app.friends.FriendsRoute
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
import com.kungsbackacarcommunity.app.media.ImageCompressor
import com.kungsbackacarcommunity.app.media.ImageUploadCoordinator
import com.kungsbackacarcommunity.app.media.ImageUploadStatus
import com.kungsbackacarcommunity.app.media.MediaUpload
import com.kungsbackacarcommunity.app.media.MediaUploader
import com.kungsbackacarcommunity.app.media.rememberImagePickLauncher
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.navigation.CurrentLocation
import com.kungsbackacarcommunity.app.navigation.HttpMapboxSearchClient
import com.kungsbackacarcommunity.app.navigation.LatLng
import com.kungsbackacarcommunity.app.navigation.NavigationSearchScreen
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
import com.kungsbackacarcommunity.app.shell.GarageHubScreen
import com.kungsbackacarcommunity.app.shell.HubEntry
import com.kungsbackacarcommunity.app.shell.HubScreen
import com.kungsbackacarcommunity.app.shell.SettingsScreen
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
    friendsRepository: FriendsRepository?,
    dmRepository: DmRepository?,
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

            // Defensive migration: selectedTab is rememberSaveable, so a session
            // saved by an older app version (when Create was a real content
            // destination) could restore it as ShellTab.Create — which now
            // renders as blank Unit, stranding the app on an empty content area.
            // Coerce any such restored Create back to Map on first composition.
            // This is safe against the live action path: tapping Create never
            // sets selectedTab to Create (ShellBottomBar.onSelect switches to Map
            // and raises the prompt), so this only ever rescues a stale restored
            // value and never interferes with the Create tab's action behaviour.
            LaunchedEffect(Unit) {
                if (selectedTab == ShellTab.Create) selectedTab = ShellTab.Map
            }

            // Tapping the bottom-nav "Create" tab opens the Map and raises this
            // transparent prompt asking whether to start sharing live location —
            // Create never becomes a selected tab of its own (see
            // ShellBottomBar.onSelect below).
            var showLiveSharePrompt by rememberSaveable { mutableStateOf(false) }

            // Group-drive "show on map": stash roster uids, switch to the Map
            // tab. Preserved for the real Mapbox impl (the stub surfaces only a
            // count); mirrors the old MapRoute participant wiring.
            var mapParticipantUids by
                rememberSaveable { mutableStateOf<ArrayList<String>>(ArrayList()) }

            // DM thread target (the other member) carried alongside ShellRoute.Chat,
            // which — like every ShellRoute — is a payload-free enum. Set by the
            // Friends "Message" button and by tapping an inbox row.
            var dmChatOtherUid by rememberSaveable { mutableStateOf<String?>(null) }
            var dmChatOtherName by rememberSaveable { mutableStateOf<String?>(null) }
            val openChat = { otherUid: String, otherName: String? ->
                dmChatOtherUid = otherUid
                dmChatOtherName = otherName
                route = ShellRoute.Chat
            }

            val snackbarHostState = remember { SnackbarHostState() }
            // Real Mapbox surface when a token is configured (on device),
            // else the neutral stub (config-less / CI) — see rememberMapSurface.
            val mapSurface = rememberMapSurface()
            val context = LocalContext.current
            // Resolved avatar download URL for the map-home top-right profile
            // button (null → falls back to the generic account icon).
            val mapAvatarUrl = rememberStorageImageUrl(context, profile?.avatarPath)
            // Resolved in composition (lint: resource lookups must not use
            // LocalContext.current) so the click lambdas can show them.
            val comingSoonText = stringResource(R.string.shell_comingSoon)
            val unavailableText = stringResource(R.string.shell_unavailable)

            // Address-search + directions overlay ("Where to?"). The Mapbox
            // search/directions client is guarded: with a blank token (CI / no
            // token) every call no-ops to empty/null and never hits the network
            // (see HttpMapboxSearchClient). Origin comes from the fused-location
            // provider, degrading to null (→ inline hint) without a fix/permission.
            var navSearchOpen by rememberSaveable { mutableStateOf(false) }
            val mapboxToken = stringResource(R.string.mapbox_access_token)
            val searchLanguage = remember { java.util.Locale.getDefault().language }
            val searchClient =
                remember(mapboxToken, searchLanguage) {
                    HttpMapboxSearchClient(mapboxToken, searchLanguage)
                }
            val originProvider: suspend () -> LatLng? =
                remember(context) { { CurrentLocation.lastKnown(context) } }

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

            // Single live-share entry point shared by the map's broadcast toggle
            // and the Create-tab prompt, so both honour the same member-gating and
            // "open the live screen when not permitted/unwired" fallback.
            fun toggleLiveShare() {
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
                                snackbarHostState.showSnackbar(unavailableText)
                            }
                        }
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

                if (navSearchOpen) {
                    // Full-screen address-search + directions overlay. Renders
                    // the same map surface behind it (MapHome is not composed
                    // while this is open, so the single MapView is free), draws
                    // the picked route on it, and owns its own Back handling.
                    // Closing wipes the route overlay so nothing lingers on the
                    // map-home map afterwards.
                    NavigationSearchScreen(
                        mapSurface = mapSurface,
                        searchClient = searchClient,
                        originProvider = originProvider,
                        onClose = {
                            mapSurface.setRouteOverlay(null)
                            navSearchOpen = false
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (route != null) {
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
                        friendsRepository = friendsRepository,
                        dmRepository = dmRepository,
                        dmChatOtherUid = dmChatOtherUid,
                        dmChatOtherName = dmChatOtherName,
                        onOpenChat = openChat,
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
                                onSelect = { tab ->
                                    // Create is an action, not a destination: open
                                    // the Map and raise the live-share prompt rather
                                    // than letting Create become the selected tab.
                                    if (tab == ShellTab.Create) {
                                        selectedTab = ShellTab.Map
                                        showLiveSharePrompt = true
                                    } else {
                                        selectedTab = tab
                                    }
                                },
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
                                        avatarUrl = mapAvatarUrl,
                                        userLabel =
                                            stringResource(R.string.shell_userMarkerLabel),
                                        // Tapping "Where to?" opens the address
                                        // search + directions overlay.
                                        onSearch = { navSearchOpen = true },
                                        // Voice search (speech-to-text) is a
                                        // follow-up; still a coming-soon hint.
                                        onVoiceSearch = { showComingSoon() },
                                        onToggleLiveShare = { toggleLiveShare() },
                                        // Layers control toggles the traffic
                                        // overlay (visible only on the real
                                        // Mapbox surface; a no-op on the stub).
                                        onLayers = {
                                            mapSurface.setTrafficEnabled(
                                                !mapSurface.trafficEnabled.value,
                                            )
                                        },
                                        onRecenter = { mapSurface.recenter() },
                                        onOpenMore = { route = ShellRoute.More },
                                        // Placeholder: chat is per-event only
                                        // (EventChatRepository) — there is no
                                        // global/community unread-count source
                                        // client-side. Wire a real "missed
                                        // chats" count here once a backend
                                        // inbox exists (out of the Android lane).
                                        unreadChatCount = 0,
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

                                // Create is intercepted in ShellBottomBar.onSelect
                                // (switches to Map + raises the live-share prompt),
                                // so it never renders as its own tab. This branch
                                // exists only for `when` exhaustiveness.
                                ShellTab.Create -> Unit

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
                                    GarageHubScreen(
                                        title = stringResource(R.string.shell_garageTitle),
                                        avatarUrl =
                                            rememberStorageImageUrl(
                                                context,
                                                profile?.avatarPath,
                                            ),
                                        avatarContentDescription =
                                            stringResource(R.string.profile_avatarAlt),
                                        friendsLabel = stringResource(R.string.shell_garageFriends),
                                        vehiclesLabel =
                                            stringResource(R.string.shell_garageVehicles),
                                        onFriends = { route = ShellRoute.Friends },
                                        onVehicles =
                                            if (garageRepository != null &&
                                                profile?.activeMember == true
                                            ) {
                                                { route = ShellRoute.Garage }
                                            } else {
                                                null
                                            },
                                        secondaryEntries =
                                            listOf(
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

                // Transparent prompt raised by the Create tab: Confirm starts
                // live sharing via the shared toggle path; Cancel or an outside
                // tap dismisses it, staying on the map.
                if (showLiveSharePrompt) {
                    LiveSharePromptDialog(
                        onConfirm = {
                            showLiveSharePrompt = false
                            // The prompt only ever asks to START sharing, but
                            // toggleLiveShare() maps to Stop while a session is
                            // active. Guard on the live-time isSharing so
                            // confirming can never stop an active session; the
                            // Start / open-screen fallbacks still run when not
                            // already sharing.
                            if (!isSharing) toggleLiveShare()
                        },
                        onDismiss = { showLiveSharePrompt = false },
                    )
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
 * Transparent confirmation prompt shown over the map when the user taps the
 * "Create" tab: a translucent-surfaced [AlertDialog] asking whether to start
 * sharing live location. [onConfirm] runs the shared live-share toggle;
 * [onDismiss] (Cancel or an outside tap) leaves the user on the map.
 */
@Composable
private fun LiveSharePromptDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // Translucent surface so the map stays visible behind the prompt.
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        title = { Text(stringResource(R.string.shell_startLiveLocation)) },
        text = { Text(stringResource(R.string.shell_liveSharePromptBody)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.shell_liveSharePromptConfirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.shell_liveSharePromptCancel))
            }
        },
    )
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
    friendsRepository: FriendsRepository?,
    dmRepository: DmRepository?,
    dmChatOtherUid: String?,
    dmChatOtherName: String?,
    onOpenChat: (String, String?) -> Unit,
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
                            stringResource(R.string.shell_friendsTitle),
                            Icons.Filled.Groups,
                            if (friendsRepository != null) {
                                { onOpenRoute(ShellRoute.Friends) }
                            } else {
                                null
                            },
                        ),
                        HubEntry(
                            stringResource(R.string.dm_title),
                            Icons.AutoMirrored.Filled.Message,
                            if (dmRepository != null) {
                                { onOpenRoute(ShellRoute.Conversations) }
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
                            stringResource(R.string.shell_moreSettings),
                            Icons.Filled.Settings,
                            { onOpenRoute(ShellRoute.Settings) },
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
                    // Read with a higher cap than the 5 MB upload cap so the raw
                    // pick reaches ImageCompressor (which shrinks it below the
                    // upload cap). Still bounded to avoid OOM; the upload precheck
                    // on the compressed result enforces PROFILE_IMAGE_MAX_BYTES.
                    maxBytes = MediaUpload.PROFILE_IMAGE_READ_MAX_BYTES,
                ) { picked ->
                    val repo = profileRepository
                    if (picked != null && avatarCoordinator != null && repo != null) {
                        // Downscale + JPEG-re-encode before upload so avatars stay
                        // small (Storage cost + well under the byte cap).
                        val compressed = ImageCompressor.compress(picked)
                        val imageId = MediaUpload.newImageId(compressed.contentType)
                        val path = MediaUpload.profileImagePath(uid, imageId)
                        avatarCoordinator.upload(compressed, path) { storedPath ->
                            repo.updateAvatarPath(uid, storedPath)
                        }
                    }
                }
            // The on-screen Back button is gone (system Back closes the route),
            // so its former coordinator cleanup now runs when the Profile route
            // leaves composition — regardless of how it was dismissed.
            DisposableEffect(profileEditCoordinator, avatarCoordinator) {
                onDispose {
                    profileEditCoordinator?.reset()
                    avatarCoordinator?.reset()
                }
            }
            ProfileScreen(
                profile = profile,
                saveStatus = saveStatus,
                onSave = { name, bio ->
                    profileEditCoordinator?.let { c -> scope.launch { c.save(uid, name, bio) } }
                },
                onBack = onClose,
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

        ShellRoute.Friends ->
            if (friendsRepository != null) {
                FriendsRoute(
                    repository = friendsRepository,
                    onMessageFriend = { friend ->
                        // Guarded: only offer to open a thread when DM is wired.
                        if (dmRepository != null) onOpenChat(friend.uid, friend.displayName)
                    },
                    onOpenMessages = {
                        if (dmRepository != null) onOpenRoute(ShellRoute.Conversations)
                    },
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.Conversations ->
            if (dmRepository != null) {
                ConversationListRoute(
                    repository = dmRepository,
                    uid = uid,
                    onOpenConversation = { conversation ->
                        // DmMapper.conversation can yield an empty other uid when the
                        // members list is malformed; don't navigate into a broken chat
                        // route that would later send with an invalid uid.
                        if (conversation.otherUser.uid.isNotBlank()) {
                            onOpenChat(conversation.otherUser.uid, conversation.otherUser.displayName)
                        }
                    },
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.Chat ->
            if (dmRepository != null && dmChatOtherUid != null) {
                ChatRoute(
                    repository = dmRepository,
                    uid = uid,
                    otherUid = dmChatOtherUid,
                    otherName = dmChatOtherName,
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

        ShellRoute.Settings ->
            SettingsScreen(
                onManageSubscription =
                    if (billingRepository != null && subscriptionVerifier != null) {
                        { onOpenRoute(ShellRoute.Subscription) }
                    } else {
                        null
                    },
                onNotificationSettings =
                    if (notificationSettingsRepository != null) {
                        { onOpenRoute(ShellRoute.NotificationSettings) }
                    } else {
                        null
                    },
                onPartnerStats =
                    if (partnerStatsRepository != null && partnerStatsEnabled) {
                        { onOpenRoute(ShellRoute.PartnerStats) }
                    } else {
                        null
                    },
                onFeedback =
                    if (feedbackCoordinator != null) {
                        { onOpenRoute(ShellRoute.Feedback) }
                    } else {
                        null
                    },
                onDeleteAccount =
                    if (accountDeletionCoordinator != null) {
                        { onOpenRoute(ShellRoute.AccountDeletion) }
                    } else {
                        null
                    },
            )

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
