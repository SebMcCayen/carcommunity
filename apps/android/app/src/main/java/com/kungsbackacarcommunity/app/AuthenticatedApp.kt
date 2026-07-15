package com.kungsbackacarcommunity.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.Campaign
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kungsbackacarcommunity.app.config.FeatureFlag
import com.kungsbackacarcommunity.app.design.KccSpacing
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
import com.kungsbackacarcommunity.app.memberprofile.MemberProfileRepository
import com.kungsbackacarcommunity.app.memberprofile.MemberProfileRoute
import com.kungsbackacarcommunity.app.garage.GarageCoordinator
import com.kungsbackacarcommunity.app.garage.GarageRepository
import com.kungsbackacarcommunity.app.garage.GarageRoute
import com.kungsbackacarcommunity.app.garage.GarageState
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
import com.kungsbackacarcommunity.app.navigation.ExternalNavigation
import com.kungsbackacarcommunity.app.navigation.HttpMapboxSearchClient
import com.kungsbackacarcommunity.app.navigation.LatLng
import com.kungsbackacarcommunity.app.navigation.NavigationSearchScreen
import com.kungsbackacarcommunity.app.navigation.PrefsRecentSearchesStore
import com.kungsbackacarcommunity.app.navigation.turnbyturn.TurnByTurnNavScreen
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
import com.kungsbackacarcommunity.app.auth.LoginRecordCoordinator
import com.kungsbackacarcommunity.app.push.PushRegistrationCoordinator
import com.kungsbackacarcommunity.app.shell.GarageHubScreen
import com.kungsbackacarcommunity.app.shell.HubEntry
import com.kungsbackacarcommunity.app.shell.HubScreen
import com.kungsbackacarcommunity.app.shell.SettingsScreen
import com.kungsbackacarcommunity.app.shell.LiveShareAction
import com.kungsbackacarcommunity.app.shell.LiveShareToggle
import com.kungsbackacarcommunity.app.incidents.Incident
import com.kungsbackacarcommunity.app.incidents.IncidentPalette
import com.kungsbackacarcommunity.app.incidents.IncidentReportController
import com.kungsbackacarcommunity.app.incidents.ReportOutcome
import com.kungsbackacarcommunity.app.shell.MapHome
import com.kungsbackacarcommunity.app.shell.MapIncidentMarker
import com.kungsbackacarcommunity.app.shell.ShellBackResult
import com.kungsbackacarcommunity.app.shell.ShellNavigation
import com.kungsbackacarcommunity.app.shell.ShellRoute
import com.kungsbackacarcommunity.app.shell.ShellTab
import com.kungsbackacarcommunity.app.shell.rememberMapSurface
import com.kungsbackacarcommunity.app.subscription.BillingRepository
import com.kungsbackacarcommunity.app.subscription.SubscriptionRoute
import com.kungsbackacarcommunity.app.subscription.SubscriptionVerifier
import com.kungsbackacarcommunity.app.welcome.WelcomeScreen
import com.kungsbackacarcommunity.app.welcome.WelcomeStore
import com.kungsbackacarcommunity.app.whatsnew.Changelog
import com.kungsbackacarcommunity.app.whatsnew.ChangelogLoader
import com.kungsbackacarcommunity.app.whatsnew.UpdateAnnouncement
import com.kungsbackacarcommunity.app.whatsnew.WhatsNewDialog
import com.kungsbackacarcommunity.app.whatsnew.WhatsNewRoute
import com.kungsbackacarcommunity.app.whatsnew.WhatsNewStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How many times the Map tab retries the nearby-incidents refresh while no
 * location fix is available yet, and the delay between attempts. Covers the
 * common cold-open case where the fused last-known location is momentarily null
 * (no fix yet), so the first refresh no-ops; a handful of retries lets a real
 * fix arrive and populate the layer without a busy loop. Once a fix is
 * available the loop stops after a single refresh, so an area with no active
 * incidents does not keep retrying.
 */
private const val INCIDENTS_REFRESH_ATTEMPTS = 5
private const val INCIDENTS_REFRESH_RETRY_MS = 3_000L

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
    memberProfileRepository: MemberProfileRepository?,
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
    loginRecordCoordinator: LoginRecordCoordinator?,
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

    // Sign-in-time last-login recording: best-effort auth-recordLogin, once per
    // signed-in uid, keeping userLifecycle/{uid}.lastLoginAt fresh for the inactive-
    // account sweep. Failures stay inside the coordinator and never block the UI.
    LaunchedEffect(uid, loginRecordCoordinator) {
        loginRecordCoordinator?.recordLogin()
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
            // One-time first-login welcome flow. Shown ONCE after profile
            // creation, on the first reach of the Main experience, and never
            // again for a returning user. "Seen" is persisted device-locally per
            // uid (WelcomeStore / SharedPreferences) — deliberately NOT account
            // state, so no backend/rules change. Skip and every CTA mark it seen;
            // a CTA additionally deep-links into the shell (membership / profile /
            // garage) via pendingWelcomeRoute, consumed as the shell's initial
            // route below. Keyed on uid so switching accounts on one device shows
            // each new user their own welcome once.
            val welcomeContext = LocalContext.current
            val welcomeStore = remember(welcomeContext) { WelcomeStore(welcomeContext) }
            var welcomeSeen by
                rememberSaveable(uid) { mutableStateOf(welcomeStore.hasSeenWelcome(uid)) }
            var pendingWelcomeRoute by
                rememberSaveable(uid) { mutableStateOf<ShellRoute?>(null) }

            if (!welcomeSeen) {
                // Every dismissal path (skip, "Get started", or a CTA) marks the
                // flow seen so it can't re-appear; a CTA also stashes the route to
                // open once the shell renders.
                val finishWelcome = { target: ShellRoute? ->
                    welcomeStore.markSeen(uid)
                    pendingWelcomeRoute = target
                    welcomeSeen = true
                }
                // Scope the whole welcome-flow composition by uid, consistent with
                // the welcome-gating/route state above. WelcomeScreen keeps its
                // current step in its own rememberSaveable; without this, a
                // different user signing in within the same Activity/process would
                // reuse the previous user's saved step instead of starting at
                // WelcomeStep.FIRST. key(uid) gives the subtree a new identity per
                // account so its saved state resets — without coupling the reusable
                // WelcomeScreen (and its @Preview) to a uid parameter.
                key(uid) {
                    WelcomeScreen(
                        onSeeMembership = { finishWelcome(ShellRoute.Subscription) },
                        onCompleteProfile = { finishWelcome(ShellRoute.Profile) },
                        onAddCar = { finishWelcome(ShellRoute.Garage) },
                        onFinish = { finishWelcome(null) },
                    )
                }
            } else {
            val profile = (profileState as? ProfileState.Loaded)?.profile

            // Selected bottom-nav tab (Map is the default home) and the
            // currently-open full-screen sub-route (null = show the tab).
            var selectedTab by rememberSaveable { mutableStateOf(ShellTab.DEFAULT) }
            // Initialised from any route a welcome-flow CTA requested (membership /
            // profile / garage), so finishing the welcome deep-links straight into
            // that screen; null (skip / "Get started") lands on the Map home. Only
            // consumed on the shell's first composition — a later state restore
            // uses the saved route, not this one-shot value. Keyed on uid (like the
            // welcome-gating state above) so a different user signing in within the
            // same Activity/process re-scopes the route to their own
            // pendingWelcomeRoute instead of inheriting the previous user's saved one.
            var route by rememberSaveable(uid) { mutableStateOf(pendingWelcomeRoute) }

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

            // Target member whose read-only profile is open, carried alongside the
            // payload-free ShellRoute.MemberProfile. Set by tapping a friend row.
            var memberProfileTargetUid by rememberSaveable { mutableStateOf<String?>(null) }
            val openMemberProfile = { targetUid: String ->
                if (targetUid.isNotBlank()) {
                    memberProfileTargetUid = targetUid
                    route = ShellRoute.MemberProfile
                }
            }

            val snackbarHostState = remember { SnackbarHostState() }
            // Real Mapbox surface when a token is configured (on device),
            // else the neutral stub (config-less / CI) — see rememberMapSurface.
            val mapSurface = rememberMapSurface()
            val context = LocalContext.current

            // Crowd-sourced incidents layer (navigation feature). Guarded:
            // createIfAvailable returns null in a config-less / CI build, so the
            // map simply shows no incident markers and the report control is
            // hidden. The controller is the small API the sibling turn-by-turn
            // nav PR reuses (report at current location + nearby list).
            val incidentController =
                remember(context) { IncidentReportController.createIfAvailable(context) }
            val incidentsFlow =
                remember(incidentController) {
                    incidentController?.nearbyIncidents ?: MutableStateFlow(emptyList<Incident>())
                }
            val nearbyIncidents by incidentsFlow.collectAsState()
            val incidentMarkers =
                remember(nearbyIncidents) {
                    nearbyIncidents.map { incident ->
                        MapIncidentMarker(
                            id = incident.id,
                            longitude = incident.longitude,
                            latitude = incident.latitude,
                            colorArgb = IncidentPalette.colorArgb(incident.type),
                        )
                    }
                }
            // Visibility of the "Traffic alerts" layer (Trafikverket + crowd-sourced
            // incidents) toggled from the map-layers popup. Defaults ON (the shared
            // road-info layer is visible to all users); persisted so the choice
            // survives rotation / process death. Gating the fetch below on this flag
            // means a user who turns the layer off stops polling, and turning it back
            // on re-fetches immediately.
            var incidentsLayerEnabled by rememberSaveable { mutableStateOf(true) }
            // Same condition rememberMapSurface uses to pick the real Mapbox
            // surface over the config-less/CI StubMapSurface. Only the real
            // surface has a GPS puck, so only it needs the runtime location
            // permission; the stub never does. Gating on this keeps a tokenless
            // build (CI, instrumented UI tests) from raising a system
            // permission prompt on the Map tab.
            val hasMapboxToken = stringResource(R.string.mapbox_access_token).isNotBlank()

            // Runtime fine-location permission for the map home. The Mapbox
            // location component (the blue GPS puck) is enabled at style-load but
            // silently renders NOTHING until this permission is granted, so we
            // must request it at runtime — declaring it in the manifest is not
            // enough on Android 6+. Unlike RecordDriveScreen (which requests on
            // an explicit user action), the map requests on first Map-tab open,
            // guarded to once per session (see the saveable flag below).
            // On grant we refresh the location component so the puck appears
            // without recreating the map (the provider does not retroactively
            // start once permission arrives). Requested once per session (a
            // saveable guard) so returning to the Map tab does not re-nag after a
            // denial; the stub (config-less / CI) no-ops refreshLocationComponent.
            val locationPermissionLauncher =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) mapSurface.refreshLocationComponent()
                }
            var mapLocationPermissionRequested by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(selectedTab, mapSurface, hasMapboxToken) {
                // Never on the config-less/CI stub: it has no puck and must not
                // trigger a system location-permission prompt (stub-map contract).
                if (selectedTab != ShellTab.Map || !hasMapboxToken) return@LaunchedEffect
                val granted =
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                when {
                    // Already granted (this or a previous session): re-apply the
                    // component so the puck shows the moment the map is on screen.
                    granted -> mapSurface.refreshLocationComponent()
                    // Not granted and not yet asked this session: prompt once.
                    !mapLocationPermissionRequested -> {
                        mapLocationPermissionRequested = true
                        locationPermissionLauncher.launch(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        )
                    }
                }
            }
            // Resolved avatar download URL for the map-home top-right profile
            // button (null → falls back to the generic account icon).
            val mapAvatarUrl = rememberStorageImageUrl(context, profile?.avatarPath)

            // Single shared vehicles stream for the whole garage section: the
            // garage hub header (main-car avatar) and the Cars sub-page both
            // derive from THIS state, so at most one Firestore snapshot
            // listener exists while the user is anywhere in the garage section
            // — and none at all outside it (the flow degrades to a constant
            // Loading). Because the remember keys don't change when moving
            // hub ↔ Cars, the listener survives that transition instead of
            // tearing down and re-attaching. garageReloadKey is bumped by the
            // Cars page's "try again" affordance to force a re-subscribe after
            // a listener error.
            var garageReloadKey by rememberSaveable { mutableStateOf(0) }
            val inGarageSection =
                selectedTab == ShellTab.Garage || route == ShellRoute.Garage
            val garageState by
                remember(garageRepository, uid, inGarageSection, garageReloadKey) {
                    if (garageRepository != null && inGarageSection) {
                        garageRepository.observeGarage(uid)
                    } else {
                        flowOf(GarageState.Loading)
                    }
                }
                    .collectAsState(initial = GarageState.Loading)
            // Resolved in composition (lint: resource lookups must not use
            // LocalContext.current) so the click lambdas can show them.
            val comingSoonText = stringResource(R.string.shell_comingSoon)
            val unavailableText = stringResource(R.string.shell_unavailable)
            // Upsell shown when a non-member tries to view others' live locations
            // on the map (sharing your own remains free).
            val viewLiveMembersOnlyText = stringResource(R.string.shell_viewLiveMembersOnly)
            // Shown instead of the upsell when viewing others is blocked because the
            // LIVE_LOCATION feature flag is off (not a membership issue) — so an active
            // member with the flag disabled doesn't see a misleading subscription upsell.
            val featureUnavailableText = stringResource(R.string.shell_unavailable)
            // Shown when the nav view's "Report incident/roadwork" is tapped while
            // the incidents feature (a sibling PR) is not yet present in this build.
            val reportComingSoonText = stringResource(R.string.turnByTurn_reportComingSoon)
            val incidentReportSuccessText = stringResource(R.string.incidents_reportSuccess)
            val incidentReportErrorText = stringResource(R.string.incidents_reportError)
            val incidentLocationUnavailableText =
                stringResource(R.string.incidents_locationUnavailable)

            // Refresh the nearby-incidents layer around the user whenever the Map
            // tab is shown AND the "Traffic alerts" layer is enabled. A single
            // one-shot refresh was unreliable: on a cold open the fused
            // last-known location is frequently null (no fix yet), so
            // refreshAroundCurrent no-ops and the layer stays empty — the map
            // shows nothing even though incidents exist in Firestore. So we retry
            // a few times with a short backoff until a real location fix arrives
            // (refreshAroundCurrent returns true → a single refresh ran) or the
            // attempts are exhausted; failures leave the previous markers intact.
            // We stop on the first successful fix rather than on a non-empty list,
            // so an area with no active incidents does not keep re-firing the
            // callable. Keyed on incidentsLayerEnabled so toggling the layer back
            // on re-fetches immediately.
            LaunchedEffect(selectedTab, incidentController, incidentsLayerEnabled) {
                val controller = incidentController ?: return@LaunchedEffect
                if (selectedTab != ShellTab.Map || !incidentsLayerEnabled) return@LaunchedEffect
                repeat(INCIDENTS_REFRESH_ATTEMPTS) { attempt ->
                    if (controller.refreshAroundCurrent()) return@LaunchedEffect
                    if (attempt < INCIDENTS_REFRESH_ATTEMPTS - 1) delay(INCIDENTS_REFRESH_RETRY_MS)
                }
            }

            // Address-search + directions overlay ("Where to?"). The Mapbox
            // search/directions client is guarded: with a blank token (CI / no
            // token) every call no-ops to empty/null and never hits the network
            // (see HttpMapboxSearchClient). Origin comes from the fused-location
            // provider, degrading to null (→ inline hint) without a fix/permission.
            var navSearchOpen by rememberSaveable { mutableStateOf(false) }
            // Turn-by-turn navigation target. Non-null → the full-screen nav view
            // is shown (over the search overlay). The origin is left to the nav
            // view, which navigates from the live GPS fix. Transient by design (a
            // process-death restart drops you back to the map, not mid-navigation).
            var navDestination by remember { mutableStateOf<LatLng?>(null) }
            var navDestinationLabel by remember { mutableStateOf("") }
            val mapboxToken = stringResource(R.string.mapbox_access_token)
            val searchLanguage = remember { java.util.Locale.getDefault().language }
            val searchClient =
                remember(mapboxToken, searchLanguage) {
                    HttpMapboxSearchClient(mapboxToken, searchLanguage)
                }
            val originProvider: suspend () -> LatLng? =
                remember(context) { { CurrentLocation.lastKnown(context) } }
            // Persists the last few selected places (SharedPreferences) so they
            // reappear in the search bar's empty state for one-tap re-selection.
            val recentSearchesStore =
                remember(context) { PrefsRecentSearchesStore(context) }
            // Resolved in composition (lint: no resource lookups off the UI thread)
            // for the "no maps app" handoff fallback below.
            val navAppMissingText = stringResource(R.string.addressSearch_navAppMissing)

            // Map long-press ("navigate here"): the surface publishes the pressed
            // coordinate; open the search/route overlay previewing that point.
            // Cleared on the surface once consumed so a later press re-triggers.
            var navSearchTarget by remember { mutableStateOf<LatLng?>(null) }
            val pendingLongPress by mapSurface.longPress.collectAsState()
            LaunchedEffect(pendingLongPress) {
                val pressed = pendingLongPress ?: return@LaunchedEffect
                navSearchTarget = LatLng(pressed.longitude, pressed.latitude)
                navSearchOpen = true
                mapSurface.consumeLongPress()
            }

            // Flag-gated (not member-gated) reach to the live-location feature.
            val liveLocationEnabled =
                FeatureGate.isAvailable(
                    flags = flags,
                    flag = FeatureFlag.LIVE_LOCATION,
                    memberGated = false,
                    isActiveMember = profile?.activeMember == true,
                )
            // Sharing your OWN location is FREE (backend parity: startSession /
            // updatePosition require only an authenticated, non-suspended user).
            // Flag-gated but NOT member-gated.
            val canShareLive =
                FeatureGate.isAvailable(
                    flags = flags,
                    flag = FeatureFlag.LIVE_LOCATION,
                    memberGated = false,
                    isActiveMember = profile?.activeMember == true,
                )
            // Viewing OTHERS on the map is the paid capability (backend parity:
            // the liveLocation/$uid/latest RTDB read rule requires activeMember).
            // A non-member gets a subscription upsell instead of the roster map.
            // ALSO flag-gated: a server-disabled LIVE_LOCATION flag must fully
            // block opening the roster map / starting RTDB reads, so require the
            // flag (liveLocationEnabled) IN ADDITION to the active-member gate.
            val canViewLiveOthers = liveLocationEnabled && profile?.activeMember == true

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

            // The pre-popup "unwired / not permitted" fallback: open the
            // (informational) live-location screen, or — when even the repository
            // is absent — surface the unavailable snackbar. Shared by
            // toggleLiveShare's OpenScreen branch, the map popup's "More options"
            // row, and the popup Start/Stop/Hide callbacks, so none of them can
            // silently no-op when the LiveLocationCoordinator isn't wired.
            fun openLiveShareFallback() {
                if (liveLocationRepository != null) {
                    route = ShellRoute.LiveLocation
                } else {
                    scope.launch {
                        snackbarHostState.showSnackbar(unavailableText)
                    }
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
                        liveLocationCoordinator?.let { c -> scope.launch { c.stop() } }
                        // Always stop the foreground service, even if the
                        // coordinator is somehow absent, so Stop can never leave
                        // background location running — matches the popup Stop
                        // callback and the LiveLocation route's teardown.
                        BackgroundLocationController.stop(context)
                    }
                    LiveShareAction.OpenScreen -> openLiveShareFallback()
                }
            }

            CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
              Box(modifier = Modifier.fillMaxSize()) {
                // Keep the screen awake (no lock/dim) while the user is actively
                // sharing live location OR has the turn-by-turn navigation overlay
                // open — a driver following a route or being tracked shouldn't have
                // the display sleep. Cleared automatically the moment both stop (see
                // KeepScreenOn), so the screen sleeps normally the rest of the time.
                KeepScreenOn(enabled = isSharing || navSearchOpen)

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

                if (navDestination != null) {
                    // Full-screen turn-by-turn navigation (Google-Maps style),
                    // entered from the route preview's "Start" button. Owns its own
                    // Back handling and map surface (its own Nav-SDK MapView). On the
                    // config-less / CI build this is the no-SDK stub (see the
                    // src/noNav TurnByTurnNavScreen). The report affordance is wired
                    // to a "coming soon" snackbar until the incidents feature (a
                    // sibling PR) lands — swap `onReportIncident` to that feature's
                    // entry point once it is present in this branch.
                    TurnByTurnNavScreen(
                        origin = null,
                        destination = navDestination!!,
                        destinationLabel = navDestinationLabel,
                        onExit = {
                            navDestination = null
                            mapSurface.setRouteOverlay(null)
                            navSearchOpen = false
                        },
                        onReportIncident = {
                            scope.launch {
                                snackbarHostState.showSnackbar(reportComingSoonText)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (navSearchOpen) {
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
                            // Drop any long-press target so re-opening via the
                            // search bar starts in the normal search-first state.
                            navSearchTarget = null
                        },
                        onStartNavigation = { dest, label ->
                            // Real in-app Mapbox turn-by-turn only exists in a build
                            // that bundles the Navigation SDK (NAV_SDK_ENABLED). The
                            // token-less noNav build (incl. the current Play release —
                            // its CI provides no MAPBOX_DOWNLOADS_TOKEN) would only
                            // show the "unavailable" stub, so there we hand off to the
                            // device's maps app for genuine turn-by-turn instead.
                            if (BuildConfig.NAV_SDK_ENABLED) {
                                navDestinationLabel = label
                                navDestination = dest
                            } else {
                                ExternalNavigation.launch(
                                    context = context,
                                    destination = dest,
                                    label = label,
                                    onUnavailable = {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(navAppMissingText)
                                        }
                                    },
                                )
                            }
                        },
                        recentStore = recentSearchesStore,
                        initialTarget = navSearchTarget,
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
                                    // Viewing others on the map is flag- AND
                                    // member-gated (canViewLiveOthers = LIVE_LOCATION
                                    // flag && activeMember; backend RTDB read rule
                                    // parity). A disabled flag or a non-member gets
                                    // the upsell/no-op instead of the roster map, so
                                    // no RTDB reads start — they can still share their
                                    // own location + see their own puck.
                                    if (canViewLiveOthers) {
                                        mapParticipantUids = ArrayList(uids)
                                        route = ShellRoute.Map
                                    } else {
                                        // Distinguish WHY viewing is blocked: a
                                        // disabled LIVE_LOCATION flag → "not available"
                                        // (an active member shouldn't see an upsell);
                                        // otherwise it's the non-member subscription upsell.
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (!liveLocationEnabled) {
                                                    featureUnavailableText
                                                } else {
                                                    viewLiveMembersOnlyText
                                                },
                                            )
                                        }
                                    }
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
                        garageState = garageState,
                        onGarageRetry = { garageReloadKey++ },
                        badgesRepository = badgesRepository,
                        blockingRepository = blockingRepository,
                        friendsRepository = friendsRepository,
                        memberProfileRepository = memberProfileRepository,
                        memberProfileTargetUid = memberProfileTargetUid,
                        onOpenMemberProfile = openMemberProfile,
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
                        // gates for sub-routes (e.g. the Settings hub)
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
                                        canShareLive = canShareLive,
                                        participantCount = mapParticipantUids.size,
                                        avatarUrl = mapAvatarUrl,
                                        userLabel =
                                            stringResource(R.string.shell_userMarkerLabel),
                                        // Tapping "Where to?" opens the address
                                        // search + directions overlay.
                                        onSearch = { navSearchOpen = true },
                                        // The broadcast control opens the transparent
                                        // live-location popup (over the map, no scrim)
                                        // with the session options, wired to the same
                                        // LiveLocationCoordinator as the full screen.
                                        // Wired: start the session + foreground
                                        // service. Unwired (no coordinator): fall
                                        // back to the live screen rather than
                                        // silently no-op'ing, matching the old
                                        // toggleLiveShare OpenScreen path.
                                        onStartLiveShare = { d ->
                                            val c = liveLocationCoordinator
                                            if (c != null) {
                                                scope.launch { c.start(d) }
                                                BackgroundLocationController.start(context)
                                            } else {
                                                openLiveShareFallback()
                                            }
                                        },
                                        onStopLiveShare = {
                                            val c = liveLocationCoordinator
                                            if (c != null) {
                                                scope.launch { c.stop() }
                                            } else {
                                                openLiveShareFallback()
                                            }
                                            // Always stop the foreground service,
                                            // even when unwired, so Stop can never
                                            // leave background location running.
                                            BackgroundLocationController.stop(context)
                                        },
                                        onHideMeNow = {
                                            val c = liveLocationCoordinator
                                            if (c != null) {
                                                scope.launch { c.hideMeNow() }
                                            } else {
                                                openLiveShareFallback()
                                            }
                                            BackgroundLocationController.stop(context)
                                        },
                                        // "More options" opens the full live screen;
                                        // unavailable (no Firebase) → a snackbar.
                                        onOpenLiveShareDetails = { openLiveShareFallback() },
                                        // The layers control opens the map-layers
                                        // popup (traffic / day-night / 3D toggles),
                                        // handled internally by MapHome against the
                                        // MapSurface seam.
                                        onRecenter = { mapSurface.recenter() },
                                        // The top-right profile button opens the
                                        // account menu as a transparent Popup
                                        // *over* the map (map stays visible)
                                        // rather than navigating to a full-screen
                                        // hub. Each entry still navigates to its
                                        // own full route (or signs out).
                                        moreMenuEntries =
                                            profileMenuEntries(
                                                profileEditCoordinator = profileEditCoordinator,
                                                dmRepository = dmRepository,
                                                pointsRepository = pointsRepository,
                                                badgesRepository = badgesRepository,
                                                partnerApplicationCoordinator =
                                                    partnerApplicationCoordinator,
                                                onOpenRoute = { route = it },
                                                onSignOut = onSignOut,
                                            ),
                                        // Placeholder: chat is per-event only
                                        // (EventChatRepository) — there is no
                                        // global/community unread-count source
                                        // client-side. Wire a real "missed
                                        // chats" count here once a backend
                                        // inbox exists (out of the Android lane).
                                        unreadChatCount = 0,
                                        // Crowd-sourced incidents layer: draw the
                                        // fetched markers for everyone, and show the
                                        // report control only when a repository is
                                        // configured (guarded off in CI/no-Firebase)
                                        // AND the user is an active member — the
                                        // `incidents-report` callable is member-gated
                                        // (requireMemberActor), so non-members must not
                                        // see an action that would fail on submit.
                                        incidentMarkers = incidentMarkers,
                                        incidentsLayerEnabled = incidentsLayerEnabled,
                                        onIncidentsLayerEnabledChange = { incidentsLayerEnabled = it },
                                        incidentReportingEnabled =
                                            incidentController != null && profile?.activeMember == true,
                                        onReportIncident = { type ->
                                            incidentController?.let { controller ->
                                                scope.launch {
                                                    val text =
                                                        when (controller.report(type)) {
                                                            is ReportOutcome.Success ->
                                                                incidentReportSuccessText
                                                            ReportOutcome.NoLocation ->
                                                                incidentLocationUnavailableText
                                                            is ReportOutcome.Failed ->
                                                                incidentReportErrorText
                                                        }
                                                    snackbarHostState.showSnackbar(text)
                                                }
                                            }
                                        },
                                    )

                                ShellTab.History ->
                                    if (drivesRepository != null) {
                                        DrivesRoute(
                                            repository = drivesRepository,
                                            uid = uid,
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
                                                    stringResource(R.string.shell_friendsTitle),
                                                    Icons.Filled.Groups,
                                                    if (friendsRepository != null) {
                                                        { route = ShellRoute.Friends }
                                                    } else {
                                                        null
                                                    },
                                                ),
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
                                        // The garage identity header shows the main
                                        // car's photo ONLY — the user's profile
                                        // picture is deliberately NOT shown here (the
                                        // garage is about cars, not profiles). When no
                                        // main car is set the hub falls back to the
                                        // car placeholder icon. Derived from the
                                        // hoisted shared garage stream — no listener
                                        // of its own.
                                        avatarUrl =
                                            rememberStorageImageUrl(
                                                context,
                                                mainCarImagePath(garageState),
                                            ),
                                        avatarContentDescription =
                                            stringResource(R.string.garage_headerImageAlt),
                                        vehiclesLabel =
                                            stringResource(R.string.shell_garageVehicles),
                                        onVehicles =
                                            if (garageRepository != null &&
                                                profile?.activeMember == true
                                            ) {
                                                { route = ShellRoute.Garage }
                                            } else {
                                                null
                                            },
                                    )
                            }
                        }
                    }
                }

                // After-update "what's new" popup: shown once per version when
                // the app opens after an UPDATE (never on first install, which
                // only records the baseline). Every dismissal path records the
                // current version so the popup can't re-show for it; "show all"
                // additionally opens the full changelog page. The decision
                // logic is the unit-tested Changelog.announcementFor.
                val whatsNewStore = remember(context) { WhatsNewStore(context) }
                var whatsNewAnnouncement by
                    remember { mutableStateOf<UpdateAnnouncement?>(null) }
                LaunchedEffect(whatsNewStore) {
                    val current = BuildConfig.VERSION_CODE
                    val lastSeen = whatsNewStore.lastSeenVersionCode()
                    when {
                        lastSeen == null -> {
                            // First install: stamp the baseline silently so the
                            // NEXT update shows what's new. No popup on first
                            // launch, so skip the changelog IO entirely.
                            whatsNewStore.markSeen(current)
                        }
                        lastSeen >= current -> {
                            // Not an update (same or older): nothing to show, and
                            // no need to read/parse the changelog.
                        }
                        else -> {
                            // A genuine update (lastSeen < current): only now do
                            // the raw-resource read + JSON parse off the main
                            // thread; the pure decision resumes on Main.
                            val entries =
                                withContext(Dispatchers.IO) { ChangelogLoader.load(context) }
                            val announcement =
                                Changelog.announcementFor(
                                    entries = entries,
                                    lastSeenVersionCode = lastSeen,
                                    currentVersionCode = current,
                                )
                            if (announcement != null) {
                                whatsNewAnnouncement = announcement
                            } else {
                                // Update with no changelog entries to announce:
                                // record the baseline silently.
                                whatsNewStore.markSeen(current)
                            }
                        }
                    }
                }
                whatsNewAnnouncement?.let { announcement ->
                    val acknowledge = {
                        whatsNewStore.markSeen(BuildConfig.VERSION_CODE)
                        whatsNewAnnouncement = null
                    }
                    WhatsNewDialog(
                        announcement = announcement,
                        onShowAll = {
                            acknowledge()
                            route = ShellRoute.WhatsNew
                        },
                        onDismiss = acknowledge,
                    )
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
}

/**
 * Height of [ShellBottomBar]. Mirrors the Material3 [NavigationBar] container
 * height (its own token is not public), so overlays that need to sit above the
 * bar can derive their offset from this single source instead of hard-coding it.
 */
internal val ShellBottomBarHeight = 80.dp

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
            // The Create ("+") tab starts live location / convoys — make it the
            // standout action: a WHITE plus on a filled primary disc so it reads
            // as a distinct button and stays high-contrast against the
            // semi-transparent nav bar in both light and dark (a bare white tint
            // would wash out over the light 50%-alpha surface). Behaviour is
            // unchanged — only the icon's appearance differs from the other tabs.
            icon = {
                Box(
                    modifier =
                        Modifier
                            .size(KccSpacing.s8)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.shell_tabCreate),
                        tint = Color.White,
                    )
                }
            },
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
 * own back affordance calls [onClose] to return to the tab hub. The map-home
 * profile menu (top-bar avatar, a transparent popup) opens these
 * profile/settings/account destinations via [onOpenRoute]. The retired
 * [ShellRoute.More] hub is handled here only as a migration-safe restore branch.
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
    garageState: GarageState,
    onGarageRetry: () -> Unit,
    badgesRepository: BadgesRepository?,
    blockingRepository: BlockingRepository?,
    friendsRepository: FriendsRepository?,
    memberProfileRepository: MemberProfileRepository?,
    memberProfileTargetUid: String?,
    onOpenMemberProfile: (String) -> Unit,
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
                        // Strip GPS + identifying metadata BEFORE upload: avatars are
                        // PUBLICLY readable by any authenticated member (storage.rules),
                        // so a selfie taken at the owner's home must never leak their
                        // coordinates or device fingerprint. compressForPublicUpload
                        // GUARANTEES the returned bytes are free of every STRIP_TAG (all
                        // GPS + identifying EXIF): the happy path re-encodes to JPEG
                        // (dropping all metadata), and if a pick can't be re-encoded it
                        // physically strips those tags or returns the original only when
                        // proven free of them — else it returns null and we fail closed /
                        // skip the upload rather than risk leaking source metadata.
                        val sanitized = ImageCompressor.compressForPublicUpload(picked)
                        if (sanitized != null) {
                            val imageId = MediaUpload.newImageId(sanitized.contentType)
                            val path = MediaUpload.profileImagePath(uid, imageId)
                            avatarCoordinator.upload(sanitized, path) { storedPath ->
                                repo.updateAvatarPath(uid, storedPath)
                            }
                        } else {
                            // Sanitisation failed (decode/re-encode returned null), so
                            // nothing was uploaded. Surface the failure instead of a
                            // silent no-op so the user knows to retry.
                            avatarCoordinator.markFailed()
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
                    garageState = garageState,
                    onRetry = onGarageRetry,
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
                    onViewProfile = { friend ->
                        // Guarded: only navigate when the profile repo is wired.
                        if (memberProfileRepository != null) onOpenMemberProfile(friend.uid)
                    },
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.MemberProfile ->
            if (memberProfileRepository != null && memberProfileTargetUid != null) {
                MemberProfileRoute(
                    repository = memberProfileRepository,
                    targetUid = memberProfileTargetUid,
                    viewerUid = uid,
                    blockingRepository = blockingRepository,
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
                onBlockedUsers =
                    if (blockingRepository != null) {
                        { onOpenRoute(ShellRoute.Blocked) }
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
                // Always available: the changelog ships bundled with the app.
                onWhatsNew = { onOpenRoute(ShellRoute.WhatsNew) },
            )

        ShellRoute.WhatsNew -> WhatsNewRoute()

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

        // Migration-safe: `More` is the retired full-screen profile hub, kept as
        // an enum constant only so older persisted state (route = More) still
        // restores to a valid constant. It's unreachable from the new UI, so if
        // it's ever restored we immediately return to the home hub via onClose()
        // (route = null) instead of leaving `route` set and rendering blank.
        ShellRoute.More -> {
            LaunchedEffect(Unit) { onClose() }
            LoadingScreen()
        }
    }
}

/**
 * Holds the display awake while [enabled] is true by setting the current view's
 * `keepScreenOn` (which toggles [android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]).
 * When the effect (re)starts it captures the view's PRIOR `keepScreenOn` value and
 * RESTORES it on dispose (or when [enabled] flips), so the screen dims/locks
 * normally the rest of the time without clobbering a keepScreenOn that something
 * else may have set on the same view.
 */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        if (enabled) {
            val previous = view.keepScreenOn
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = previous }
        } else {
            // Not our turn to keep the screen awake: leave the flag untouched so
            // we never clear a keepScreenOn that something else set on this view.
            onDispose {}
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

/**
 * The profile/account menu shown by the map-home top-right button. Rendered by
 * [com.kungsbackacarcommunity.app.shell.MapHome] as a transparent
 * [androidx.compose.ui.window.Popup] over the map
 * (not a full-screen hub), but the entries themselves still navigate to full
 * routes via [onOpenRoute] (or sign out). Unavailable entries carry a null
 * `onClick` and are omitted by the popup / [HubScreen].
 */
@Composable
private fun profileMenuEntries(
    profileEditCoordinator: ProfileEditCoordinator?,
    dmRepository: DmRepository?,
    pointsRepository: PointsRepository?,
    badgesRepository: BadgesRepository?,
    partnerApplicationCoordinator: PartnerApplicationCoordinator?,
    onOpenRoute: (ShellRoute) -> Unit,
    onSignOut: () -> Unit,
): List<HubEntry> =
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
            stringResource(R.string.dm_title),
            Icons.AutoMirrored.Filled.Message,
            if (dmRepository != null) {
                { onOpenRoute(ShellRoute.Conversations) }
            } else {
                null
            },
        ),
        HubEntry(
            stringResource(R.string.profile_points),
            Icons.Filled.Stars,
            if (pointsRepository != null) {
                { onOpenRoute(ShellRoute.Points) }
            } else {
                null
            },
        ),
        HubEntry(
            stringResource(R.string.profile_badges),
            Icons.Filled.MilitaryTech,
            if (badgesRepository != null) {
                { onOpenRoute(ShellRoute.Badges) }
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
    )

/**
 * The Cloud Storage path of the user's main car photo, or null when there is no
 * main car (or the garage is not loaded / the car has no photo). A pure
 * derivation over the single hoisted garage stream — it deliberately opens no
 * listener of its own, so the hub header and the Cars sub-page share one
 * Firestore snapshot listener for the whole garage section.
 */
private fun mainCarImagePath(state: GarageState): String? =
    (state as? GarageState.Loaded)
        ?.vehicles
        ?.firstOrNull { it.isMainCar }
        ?.imagePath
