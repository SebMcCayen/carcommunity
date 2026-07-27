package com.kungsbackacarcommunity.app

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kungsbackacarcommunity.app.config.FeatureFlag
import com.kungsbackacarcommunity.app.design.KccAlpha
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.design.LocalSnackbarHostState
import com.kungsbackacarcommunity.app.account.AccountDeletionCoordinator
import com.kungsbackacarcommunity.app.account.AccountDeletionRoute
import com.kungsbackacarcommunity.app.badges.BadgeCounters
import com.kungsbackacarcommunity.app.badges.BadgeShowcase
import com.kungsbackacarcommunity.app.badges.BadgesRepository
import com.kungsbackacarcommunity.app.badges.BadgesRoute
import com.kungsbackacarcommunity.app.badges.BadgesState
import com.kungsbackacarcommunity.app.blocking.BlockingRepository
import com.kungsbackacarcommunity.app.blocking.BlockingRoute
import com.kungsbackacarcommunity.app.diagnostics.rememberClientErrorReporter
import com.kungsbackacarcommunity.app.drives.DriveLocationController
import com.kungsbackacarcommunity.app.drives.DriveRecordingGate
import com.kungsbackacarcommunity.app.drives.DriveStatsCalculator
import com.kungsbackacarcommunity.app.drives.DrivesRepository
import com.kungsbackacarcommunity.app.drives.DrivesRoute
import com.kungsbackacarcommunity.app.drives.DrivesState
import com.kungsbackacarcommunity.app.drives.RecordingState
import com.kungsbackacarcommunity.app.drives.RouteUploadRunner
import com.kungsbackacarcommunity.app.drives.SessionSummaryDialog
import com.kungsbackacarcommunity.app.drives.SingleSessionRecording
import com.kungsbackacarcommunity.app.billboards.BillboardsRepository
import com.kungsbackacarcommunity.app.billboards.BillboardsRoute
import com.kungsbackacarcommunity.app.chat.ChatCoordinator
import com.kungsbackacarcommunity.app.chat.EventChatRepository
import com.kungsbackacarcommunity.app.config.FeatureFlags
import com.kungsbackacarcommunity.app.config.FeatureGate
import com.kungsbackacarcommunity.app.config.MemberGating
import com.kungsbackacarcommunity.app.convoy.ConvoyBar
import com.kungsbackacarcommunity.app.convoy.ConvoyCoordinator
import com.kungsbackacarcommunity.app.convoy.ConvoyActionError
import com.kungsbackacarcommunity.app.convoy.ConvoyDestination
import com.kungsbackacarcommunity.app.convoy.ConvoyDestinationNavigationEvent
import com.kungsbackacarcommunity.app.convoy.ConvoyDestinationRepository
import com.kungsbackacarcommunity.app.convoy.ConvoyDestinationState
import com.kungsbackacarcommunity.app.convoy.ConvoyDestinations
import com.kungsbackacarcommunity.app.convoy.ConvoyInvitePickerScreen
import com.kungsbackacarcommunity.app.convoy.ConvoyListStatus
import com.kungsbackacarcommunity.app.convoy.ConvoyRepository
import com.kungsbackacarcommunity.app.convoy.ConvoyRoute
import com.kungsbackacarcommunity.app.convoy.ConvoyMapAwarenessOverlay
import com.kungsbackacarcommunity.app.convoy.ConvoyStatusBar
import com.kungsbackacarcommunity.app.convoy.InviteConvoyState
import com.kungsbackacarcommunity.app.convoy.invitableSelection
import com.kungsbackacarcommunity.app.convoy.messageRes
import com.kungsbackacarcommunity.app.convoy.UnavailableConvoyDestinationRepository
import com.kungsbackacarcommunity.app.crownhunt.CrownClaimStatus
import com.kungsbackacarcommunity.app.crownhunt.CrownCollectGate
import com.kungsbackacarcommunity.app.crownhunt.CrownFix
import com.kungsbackacarcommunity.app.crownhunt.CrownFixTracker
import com.kungsbackacarcommunity.app.crownhunt.CrownHuntCoordinator
import com.kungsbackacarcommunity.app.crownhunt.CrownHuntRepository
import com.kungsbackacarcommunity.app.crownhunt.CrownHuntRoute
import com.kungsbackacarcommunity.app.crownhunt.CrownLocation
import com.kungsbackacarcommunity.app.crownhunt.CrownMarkerStyle
import com.kungsbackacarcommunity.app.crownhunt.CrownQueryCenter
import com.kungsbackacarcommunity.app.crownhunt.CrownSpawn
import com.kungsbackacarcommunity.app.crownhunt.CrownSpawnController
import com.kungsbackacarcommunity.app.crownhunt.CrownSpawnLimits
import com.kungsbackacarcommunity.app.crownhunt.CrownSpawnPopup
import com.kungsbackacarcommunity.app.crownhunt.CrownSpawnQuery
import com.kungsbackacarcommunity.app.crownhunt.crownGlyphRes
import com.kungsbackacarcommunity.app.chatchannels.ChatHubPopup
import com.kungsbackacarcommunity.app.chatchannels.ChatHubRoute
import com.kungsbackacarcommunity.app.chatchannels.CommunityChatRepository
import com.kungsbackacarcommunity.app.chatchannels.ConvoyChatRepository
import com.kungsbackacarcommunity.app.dm.ChatRoute
import com.kungsbackacarcommunity.app.dm.ConversationListRoute
import com.kungsbackacarcommunity.app.dm.DmRepository
import com.kungsbackacarcommunity.app.events.EventSummary
import com.kungsbackacarcommunity.app.events.Events
import com.kungsbackacarcommunity.app.events.EventsListState
import com.kungsbackacarcommunity.app.events.EventsRepository
import com.kungsbackacarcommunity.app.events.EventsRoute
import com.kungsbackacarcommunity.app.events.RsvpCoordinator
import com.kungsbackacarcommunity.app.feedback.FeedbackCoordinator
import com.kungsbackacarcommunity.app.feedback.FeedbackReportRoute
import com.kungsbackacarcommunity.app.friends.FriendActionError
import com.kungsbackacarcommunity.app.friends.FriendsCoordinator
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import com.kungsbackacarcommunity.app.friends.FriendsRoute
import com.kungsbackacarcommunity.app.friends.FriendsStatus
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
import com.kungsbackacarcommunity.app.points.Points
import com.kungsbackacarcommunity.app.points.PointsEntriesState
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
import com.kungsbackacarcommunity.app.live.LiveMarker
import com.kungsbackacarcommunity.app.live.LiveSessionDuration
import com.kungsbackacarcommunity.app.live.NearbyLiveController
import com.kungsbackacarcommunity.app.live.NearbyLiveOverlay
import com.kungsbackacarcommunity.app.live.NearbyLiveSession
import com.kungsbackacarcommunity.app.location.BackgroundLocationController
import com.kungsbackacarcommunity.app.location.LocationAccess
import com.kungsbackacarcommunity.app.location.LocationAccessPrompt
import com.kungsbackacarcommunity.app.location.LocationPermissionRemedy
import com.kungsbackacarcommunity.app.location.currentLocationAccess
import com.kungsbackacarcommunity.app.location.locationPermissionRemedy
import com.kungsbackacarcommunity.app.location.openAppLocationSettings
import com.kungsbackacarcommunity.app.location.openDeviceLocationSettings
import com.kungsbackacarcommunity.app.location.shouldShowLocationRationale
import com.kungsbackacarcommunity.app.map.ConvoyCameraPlan
import com.kungsbackacarcommunity.app.map.ConvoyFocusMode
import com.kungsbackacarcommunity.app.map.ConvoyFocusPlanner
import com.kungsbackacarcommunity.app.map.ConvoyFocusStore
import com.kungsbackacarcommunity.app.map.ConvoyLatLng
import com.kungsbackacarcommunity.app.map.MapRoute
import com.kungsbackacarcommunity.app.map.toConvoyMemberPosition
import com.kungsbackacarcommunity.app.media.FirebaseMediaUploader
import com.kungsbackacarcommunity.app.media.ImageCompressor
import com.kungsbackacarcommunity.app.media.ImageEditFrameShape
import com.kungsbackacarcommunity.app.media.ImageEditScreen
import com.kungsbackacarcommunity.app.media.ImageUploadCoordinator
import com.kungsbackacarcommunity.app.media.ImageUploadStatus
import com.kungsbackacarcommunity.app.media.MediaUpload
import com.kungsbackacarcommunity.app.media.MediaUploader
import com.kungsbackacarcommunity.app.media.PickedImage
import com.kungsbackacarcommunity.app.media.rememberImagePickLauncher
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.navigation.CurrentLocation
import com.kungsbackacarcommunity.app.navigation.ExternalNavigation
import com.kungsbackacarcommunity.app.navigation.HttpMapboxSearchClient
import com.kungsbackacarcommunity.app.navigation.LatLng
import com.kungsbackacarcommunity.app.navigation.NavigationSearchScreen
import com.kungsbackacarcommunity.app.navigation.PrefsRecentSearchesStore
import com.kungsbackacarcommunity.app.navigation.PrefsSavedPlacesStore
import com.kungsbackacarcommunity.app.navigation.SavedPlace
import com.kungsbackacarcommunity.app.navigation.SavedPlaceEdit
import com.kungsbackacarcommunity.app.navigation.SavedPlaces
import com.kungsbackacarcommunity.app.navigation.SavedPlacesScreen
import com.kungsbackacarcommunity.app.navigation.SavedPlacesStore
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
import com.kungsbackacarcommunity.app.profile.ProfileStatsSummary
import com.kungsbackacarcommunity.app.profile.authedDestination
import com.kungsbackacarcommunity.app.auth.LoginRecordCoordinator
import com.kungsbackacarcommunity.app.push.ActiveChat
import com.kungsbackacarcommunity.app.push.ActiveChatRegistry
import com.kungsbackacarcommunity.app.push.PushDeepLink
import com.kungsbackacarcommunity.app.push.PushNavigator
import com.kungsbackacarcommunity.app.push.PushRegistrationCoordinator
import com.kungsbackacarcommunity.app.push.PushTarget
import com.kungsbackacarcommunity.app.push.RequestPushPermissionEffect
import com.kungsbackacarcommunity.app.shell.HubEntry
import com.kungsbackacarcommunity.app.shell.MapMode
import com.kungsbackacarcommunity.app.shell.HubScreen
import com.kungsbackacarcommunity.app.shell.sortedHubEntriesByLabel
import com.kungsbackacarcommunity.app.shell.SettingsScreen
import com.kungsbackacarcommunity.app.shell.LiveSessionBar
import com.kungsbackacarcommunity.app.shell.LiveShareAction
import com.kungsbackacarcommunity.app.shell.LiveSharePopup
import com.kungsbackacarcommunity.app.shell.LiveShareToggle
import com.kungsbackacarcommunity.app.incidents.CameraRequeryDecision
import com.kungsbackacarcommunity.app.incidents.Incident
import com.kungsbackacarcommunity.app.incidents.IncidentDetailsSheet
import com.kungsbackacarcommunity.app.incidents.IncidentMarkerStyle
import com.kungsbackacarcommunity.app.incidents.ConfirmOutcome
import com.kungsbackacarcommunity.app.incidents.IncidentPalette
import com.kungsbackacarcommunity.app.incidents.IncidentReportController
import com.kungsbackacarcommunity.app.incidents.IncidentType
import com.kungsbackacarcommunity.app.incidents.QueryAnchor
import com.kungsbackacarcommunity.app.incidents.ReportLocation
import com.kungsbackacarcommunity.app.incidents.ReportOutcome
import com.kungsbackacarcommunity.app.incidents.hasTrafikverketData
import com.kungsbackacarcommunity.app.incidents.incidentGlyphRes
import com.kungsbackacarcommunity.app.shell.EventMarkerInfoPopup
import com.kungsbackacarcommunity.app.shell.MapHome
import com.kungsbackacarcommunity.app.shell.MapCrownMarker
import com.kungsbackacarcommunity.app.shell.MapEventMarker
import com.kungsbackacarcommunity.app.shell.MapIncidentMarker
import com.kungsbackacarcommunity.app.shell.MapPoint
import com.kungsbackacarcommunity.app.shell.MapSurface
import com.kungsbackacarcommunity.app.shell.ShellBackResult
import com.kungsbackacarcommunity.app.shell.MapCover
import com.kungsbackacarcommunity.app.shell.ShellNavigation
import com.kungsbackacarcommunity.app.shell.ShellRoute
import com.kungsbackacarcommunity.app.shell.ShellTab
import com.kungsbackacarcommunity.app.shell.TranslucentShellPanel
import com.kungsbackacarcommunity.app.shell.GARAGE_PANEL_TEST_TAG
import com.kungsbackacarcommunity.app.shell.HISTORY_PANEL_TEST_TAG
import com.kungsbackacarcommunity.app.shell.SOCIAL_PANEL_TEST_TAG
import com.kungsbackacarcommunity.app.shell.rememberMapSurface
import com.kungsbackacarcommunity.app.shell.runIncidentConfirmation
import com.kungsbackacarcommunity.app.shell.runIncidentRemoval
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
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How often the map re-polls live.listNearby for nearby standalone sharers while
 * the Map tab is showing. Sharers move and start/stop, so unlike incidents this
 * is a steady poll rather than a cold-open one-shot. 20s balances freshness
 * against callable cost; each poll is one bounded geo query, and a sharer's
 * position between polls still streams live through their per-uid RTDB marker.
 */
private const val NEARBY_LIVE_POLL_MS = 20_000L

/**
 * Max nearby standalone sharers subscribed at once. live.listNearby can return
 * up to 200 (sorted by freshness); opening one RTDB observeLatest() stream per
 * uid in a dense area is real bandwidth/battery + backend load for markers the
 * overlay only draws while on-screen. Capping to the freshest N bounds the
 * concurrent listener count without a visible loss on a normal viewport.
 */
private const val MAX_NEARBY_LIVE_MARKERS = 50

/**
 * How long the map camera must be STILL before the incident layer reads the
 * viewport and considers a re-query. A pan/zoom emits a stream of camera
 * snapshots; debouncing by this window means the radius/centre are read from the
 * FINAL settled camera, not the intermediate frames of a fling. 500 ms sits in
 * the middle of the sensible 300–800 ms band: long enough that a flung/pinched
 * camera has come to rest (so one query, not a burst), short enough that the
 * layer feels like it follows the map. The meaningful-move gate then decides
 * whether the settle is even worth a callable.
 */
private const val INCIDENT_CAMERA_IDLE_DEBOUNCE_MS = 500L

/**
 * How often a fresh position fix is taken WHILE a Kronjakt crown popup is open.
 *
 * `crownHunt.claimSpawn` needs two fixes at least
 * [com.kungsbackacarcommunity.app.crownhunt.CrownSpawnLimits.MIN_DWELL_SECONDS]
 * (4 s) apart and derives its own speed from the pair, so a single sample can
 * never satisfy it. 2 s means a usable pair exists a couple of seconds after the
 * server's own minimum — as fast as the rule permits, and no faster.
 *
 * Deliberately scoped to the OPEN POPUP and nothing else: this is a
 * high-accuracy GPS read, and running it for the whole session (or in the
 * background) to keep a Collect button warm would be a real battery cost for a
 * feature the user is not currently looking at.
 */
private const val CROWN_FIX_INTERVAL_MS = 2_000L

/**
 * Stable feature key for the end-of-session drive save (the backend fingerprints
 * auto-filed issues on this plus the error code, so it must not drift).
 */
private const val FEATURE_DRIVE_SAVE = "drives.saveDrive"

/**
 * Crossfade duration (ms) between bottom-nav tabs. Long enough to read as a
 * deliberate transition rather than a snap, short enough that the shell still
 * feels immediate — Material's guidance for a simple within-screen fade, and in
 * the same register as the map's own short camera eases.
 */
private const val SHELL_TAB_FADE_MILLIS = 200

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
    convoyRepository: ConvoyRepository?,
    communityChatRepository: CommunityChatRepository?,
    convoyChatRepository: ConvoyChatRepository?,
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
    // Real Mapbox surface when a token is configured (on device), else the
    // neutral stub (config-less / CI) — see rememberMapSurface. Defaulted rather
    // than constructed inside so UI tests can pass a StubMapSurface they hold a
    // reference to and assert the shell's map wiring (kept alive across tabs,
    // stood down while covered). Production never passes this.
    mapSurface: MapSurface = rememberMapSurface(),
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

            // The ancestors of the currently-open [route], nearest parent last —
            // together they form the shell's route back-stack. Held separately
            // from [route] (rather than folding both into one list) so every
            // existing `route` read/`when(route)` stays untouched; this is the
            // ONLY addition. INVARIANT: non-empty only while [route] != null —
            // the openers/closer below keep the two in lock-step.
            //
            // Why it exists: opening a child from a hub (Settings → Blocked
            // users) used to overwrite the single `route`, losing the parent, so
            // Back dropped straight to the map. Pushing the parent here lets Back
            // pop ONE level back to it. Keyed on uid like [route] itself.
            var routeParents by
                rememberSaveable(uid) { mutableStateOf<ArrayList<ShellRoute>>(arrayListOf()) }

            // A fresh top-level entry — the map-home profile menu / social hub, a
            // push-notification tap, a map dialog. Starts a NEW stack, so Back
            // from it returns to the map/tab, never a stale parent.
            val openRootRoute = { target: ShellRoute ->
                routeParents = arrayListOf()
                route = target
            }
            // Navigate one level DEEPER. The current route (if any) becomes the
            // new route's parent — via the unit-tested [ShellNavigation.pushRoute]
            // so production and its test can't drift — so Back pops back to it.
            // Used for hub → child (Settings → Blocked users) and list → detail
            // (a conversation, a member profile). With nothing open it degrades
            // to a root entry (no parent), which is the honest result for a
            // list→detail reached directly from a push tap.
            val pushRoute = { target: ShellRoute ->
                routeParents = ArrayList(ShellNavigation.pushRoute(routeParents, route))
                route = target
            }
            // Leave the route stack entirely, back to a bare tab (Garage tab, the
            // map after a convoy is created, the address picker). Clears parents
            // alongside `route` so the back-stack can't outlive the route it
            // described.
            val clearRoutes = {
                routeParents = arrayListOf()
                route = null
            }

            // The map's manual day/night override (null = follow the app theme).
            //
            // Owned HERE, not inside MapHome, precisely because [route] above can
            // unmount MapHome: opening any full-screen route swaps the shell's
            // `else` branch away, disposing MapHome and — when this state lived
            // there — silently discarding the user's Day choice, so returning to
            // the map snapped it back to Night on a dark-themed device. This
            // scope survives every route change, so the override sticks for the
            // session. rememberSaveable additionally carries it across rotation
            // and process death, matching the old in-MapHome behaviour.
            val mapNightModeOverride =
                rememberSaveable(
                    stateSaver = Saver(
                        save = { it?.name },
                        // Safe parse: valueOf THROWS on an unknown constant (enum
                        // renamed by an update, corrupt saved state), which would
                        // crash the restore; find falls back to "follow the app
                        // theme".
                        restore = { saved ->
                            (saved as? String)?.let { name -> MapMode.entries.find { it.name == name } }
                        },
                    ),
                ) { mutableStateOf<MapMode?>(null) }

            // Defensive migration: selectedTab is rememberSaveable, so a session
            // saved by an older app version (when Create was a real content
            // destination) could restore it as ShellTab.Create — which now
            // renders as blank Unit, stranding the app on an empty content area.
            // Coerce any such restored Create back to Map on first composition.
            // This is safe against the live action path: tapping Create never
            // sets selectedTab to Create (ShellBottomBar.onSelect switches to Map
            // and raises the chooser), so this only ever rescues a stale restored
            // value and never interferes with the Create tab's action behaviour.
            LaunchedEffect(Unit) {
                if (selectedTab == ShellTab.Create) selectedTab = ShellTab.Map
            }

            // Tapping the bottom-nav "Create" tab opens the Map and raises this
            // transparent chooser: "Single session" (start a solo live-share
            // drive) vs "Convoy" (deep-link into the create-convoy flow). Create
            // never becomes a selected tab of its own (see ShellBottomBar.onSelect
            // below), so it can't get "stuck" selected — it's an action.
            var showCreateChooser by rememberSaveable { mutableStateOf(false) }

            // Chat hub open/close is local UI state: tapping the map's chat bubble
            // opens the chat hub as a TRANSPARENT popup *over* the map (no
            // scrim, map visible behind — the same idiom as the map-layers /
            // live-share popups) rather than navigating to a full opaque route.
            var chatHubOpen by rememberSaveable { mutableStateOf(false) }

            // Set true immediately before opening ShellRoute.Convoys from the
            // chooser's "Convoy" option so the convoy route deep-links straight
            // into its create-convoy sub-screen. Reset to false when the Social
            // hub opens Convoys the normal way (list first). ConvoyRoute consumes
            // it one-shot, so it never re-forces Create after a back-out.
            var convoyOpenCreate by rememberSaveable { mutableStateOf(false) }

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
                // From inside the conversation list (or a member profile) this
                // pushes, so Back returns there; from a push tap on the map it
                // starts a fresh stack (no parent) — pushRoute handles both.
                pushRoute(ShellRoute.Chat)
            }

            // Notification taps. MainActivity decodes the Intent extras and parks
            // the destination in PushNavigator (a process-level hand-off, because
            // the Intent arrives outside this composition and may arrive before it
            // exists on a cold start). Handling it here means a tap drives THIS
            // shell's ordinary route + payload state — the same assignments the
            // in-app affordances make — rather than a second navigation mechanism.
            //
            // Chat-hub destinations are forwarded whole to ChatHubRoute, which
            // owns the tab/channel sub-navigation; the rest map to a ShellRoute.
            //
            // INVARIANT — pendingChatHubLink is never read stale, and the reason
            // is structural rather than a clear-on-exit: the opener below is the
            // ONLY route into ShellRoute.ChatHub in the shell, and it always
            // writes a fresh link in the same frame. ChatHubRoute therefore
            // cannot be entered carrying a previous tap's destination. The map
            // bubble is not a counter-example — it opens ChatHubPopup, which
            // takes no pushDeepLink parameter at all. Back-out is likewise safe:
            // ChatHub is opened via openRootRoute (a fresh, parent-less stack),
            // so a Back pop from it lands on the map, never back INTO ChatHub —
            // the only entry stays this fresh-link opener.
            //
            // If you ever add a second way to reach ShellRoute.ChatHub, that
            // invariant dies and this must become a consume-and-clear.
            var pendingChatHubLink by remember { mutableStateOf<PushDeepLink?>(null) }
            // Event id from an event-reminder push tap, opened by EventsRoute on
            // entry and cleared the moment it consumes it (unlike ChatHub, the
            // Events route is reachable by normal navigation too, so a lingering
            // id would wrongly re-open the event on a later plain visit).
            var pendingEventDeepLinkId by remember { mutableStateOf<String?>(null) }
            val pushLink by PushNavigator.pending.collectAsState()
            LaunchedEffect(pushLink) {
                val link = PushNavigator.consume() ?: return@LaunchedEffect
                when (link.target) {
                    PushTarget.DM ->
                        // With the counterpart resolved, open the thread directly;
                        // without it, the conversation list is the honest landing.
                        // A push tap is a fresh top-level entry, so open as a ROOT
                        // (parent-less) stack — Back returns to the map, not to
                        // whatever route happened to be open when the tap arrived.
                        // openChat() is the in-app list → detail path (it pushes),
                        // which is why the payload is set + opened as a root here
                        // rather than routed through it, matching every other push
                        // target below.
                        if (link.entityId != null) {
                            dmChatOtherUid = link.entityId
                            dmChatOtherName = null
                            openRootRoute(ShellRoute.Chat)
                        } else {
                            openRootRoute(ShellRoute.Conversations)
                        }
                    PushTarget.COMMUNITY_CHAT,
                    PushTarget.CONVOY_CHAT,
                    -> {
                        pendingChatHubLink = link
                        openRootRoute(ShellRoute.ChatHub)
                    }
                    PushTarget.CONVOYS -> openRootRoute(ShellRoute.Convoys)
                    PushTarget.FRIENDS -> openRootRoute(ShellRoute.Friends)
                    PushTarget.EVENT -> {
                        // The backend sends the reminder's event id as entityId;
                        // open that event directly. Null (unknown event) falls
                        // through to the events list, EventsRoute's own default.
                        pendingEventDeepLinkId = link.entityId
                        openRootRoute(ShellRoute.Events)
                    }
                    PushTarget.SUBSCRIPTION -> openRootRoute(ShellRoute.Subscription)
                    PushTarget.NOTIFICATIONS -> openRootRoute(ShellRoute.Notifications)
                }
            }

            // Target member whose read-only profile is open, carried alongside the
            // payload-free ShellRoute.MemberProfile. Set by tapping a friend row.
            var memberProfileTargetUid by rememberSaveable { mutableStateOf<String?>(null) }
            val openMemberProfile = { targetUid: String ->
                if (targetUid.isNotBlank()) {
                    memberProfileTargetUid = targetUid
                    // Reached from a friend/chat row: push so Back returns to that
                    // list rather than dropping to the map.
                    pushRoute(ShellRoute.MemberProfile)
                }
            }

            val snackbarHostState = remember { SnackbarHostState() }
            val context = LocalContext.current

            // Crowd-sourced incidents layer (navigation feature). Guarded:
            // createIfAvailable returns null in a config-less / CI build, so the
            // map simply shows no incident markers and the report control is
            // hidden. The controller is the small API the sibling turn-by-turn
            // nav PR reuses (report at current location + nearby list).
            val incidentController =
                remember(context) { IncidentReportController.createIfAvailable(context) }

            // Nearby-public live-sharer discovery (live.listNearby). Built on the
            // existing live-location repository (null in a config-less/CI build →
            // no controller → no nearby layer). Holds only the discovery SEEDS
            // (uid + last position); the live stream comes from each uid's
            // per-uid RTDB observeLatest below, exactly like the convoy layer.
            val nearbyLiveController =
                remember(liveLocationRepository) {
                    liveLocationRepository?.let { NearbyLiveController(it) }
                }
            val incidentsFlow =
                remember(incidentController) {
                    incidentController?.nearbyIncidents ?: MutableStateFlow(emptyList<Incident>())
                }
            val nearbyIncidents by incidentsFlow.collectAsState()
            val incidentMarkers =
                remember(nearbyIncidents) {
                    nearbyIncidents.map { incident ->
                        // The host is where an IncidentType becomes the drawing
                        // primitives the map seam takes — the disc colour, the
                        // category glyph, and the glyph tint that keeps it
                        // readable on that particular disc.
                        MapIncidentMarker(
                            id = incident.id,
                            longitude = incident.longitude,
                            latitude = incident.latitude,
                            // Colour AND glyph. The glyph is what makes an
                            // accident tellable from roadwork at a glance on a
                            // moving map (and to a colour-blind driver); the
                            // colour is now the redundant second channel, not
                            // the only one. Resolved here because the map
                            // surface seam deliberately knows nothing about
                            // IncidentType.
                            colorArgb = IncidentPalette.colorArgb(incident.type),
                            iconRes = incidentGlyphRes(incident.type),
                            glyphColorArgb =
                                IncidentMarkerStyle.glyphColorArgb(incident.type),
                        )
                    }
                }
            // Whether the loaded incidents include any Trafikverket-imported row,
            // i.e. whether their open data is actually on screen. Gates the
            // "Källa: Trafikverket" credit in the layers popup.
            val trafikverketDataShown =
                remember(nearbyIncidents) { hasTrafikverketData(nearbyIncidents) }
            // Visibility of the "Traffic alerts" layer (Trafikverket + crowd-sourced
            // incidents) toggled from the map-layers popup. Defaults ON (the shared
            // road-info layer is visible to all users); persisted so the choice
            // survives rotation / process death. Gating the fetch below on this flag
            // means a user who turns the layer off stops polling, and turning it back
            // on re-fetches immediately.
            var incidentsLayerEnabled by rememberSaveable { mutableStateOf(true) }
            // The incident marker the user has TAPPED, resolved back from the id
            // the map surface published to the incident we already hold. Only the
            // id crosses the surface seam (the surface knows nothing about
            // incidents), so the resolution happens here.
            //
            // Deliberately derived from [nearbyIncidents] rather than snapshotted:
            // if a refresh drops the incident while its sheet is open (it expired,
            // or the user just removed it), the lookup returns null and the sheet
            // closes itself instead of sitting there describing something that is
            // no longer on the map.
            val tappedIncidentId by mapSurface.incidentTap.collectAsState()
            val tappedIncident =
                remember(tappedIncidentId, nearbyIncidents) {
                    tappedIncidentId?.let { id -> nearbyIncidents.firstOrNull { it.id == id } }
                }

            // Community event pins on the map, visible to EVERY signed-in user
            // (deliberate 2026-07 open-up: event locations are public). Reuses the
            // same published-events teaser listener the events list uses; the pure
            // [Events.mapPinEvents] filter keeps only published, upcoming,
            // positioned events (a cancelled or draft event never gets a pin).
            // Guarded off when no events repository is configured (CI/no-Firebase),
            // where the flow stays empty and the layer simply draws nothing.
            val publishedEventsState by
                remember(eventsRepository) {
                    eventsRepository?.observePublishedEvents()
                        ?: MutableStateFlow(EventsListState.Loading)
                }
                    .collectAsState(initial = EventsListState.Loading)
            val publishedEventsForMap: List<EventSummary> =
                (publishedEventsState as? EventsListState.Loaded)?.events ?: emptyList()
            // The "not past" cutoff is evaluated against a clock, so filtering once
            // per list change would leave an ended event pinned until an unrelated
            // Firestore snapshot arrived. Instead: filter now, then sleep exactly
            // until the soonest pinned event ends and filter again — one scheduled
            // wake-up rather than per-frame work or a poll (the same
            // delay-to-expiry shape the live-sharing expiry uses above). Falls out
            // of the loop when nothing left on the map is time-limited.
            val mapEventMarkers by
                produceState(initialValue = emptyList<MapEventMarker>(), publishedEventsForMap) {
                    while (true) {
                        val now = nowMillis()
                        value =
                            Events.mapPinEvents(publishedEventsForMap, now).mapNotNull { event ->
                                val lat = event.latitude
                                val lng = event.longitude
                                if (lat != null && lng != null) {
                                    MapEventMarker(id = event.id, longitude = lng, latitude = lat)
                                } else {
                                    null
                                }
                            }
                        val nextExpiry =
                            Events.nextPinExpiryMillis(publishedEventsForMap, now) ?: break
                        // Wake just AFTER the end instant so the re-filter sees the
                        // event as past (the filter keeps `end >= now`).
                        delay((nextExpiry - now + 1L).coerceAtLeast(1L))
                    }
                }
            // The event pin the user TAPPED, resolved back from the id the surface
            // published to the event we already hold. Derived (not snapshotted) so
            // an event that drops out of the list (cancelled, ended) closes its
            // popup rather than leaving it describing a pin no longer on the map.
            val tappedEventId by mapSurface.eventTap.collectAsState()
            val tappedEvent =
                remember(tappedEventId, publishedEventsForMap) {
                    tappedEventId?.let { id -> publishedEventsForMap.firstOrNull { it.id == id } }
                }
            // True while a removal is in flight, so the sheet can disable its
            // remove button. Keyed to the open incident: the sheet now survives
            // the round-trip, and a flag left set by a previous sheet would
            // arrive disabled.
            var incidentRemoveInFlight by
                remember(tappedIncidentId) { mutableStateOf(false) }
            // Same one-call-per-press guard for confirming someone else's report.
            // Keyed to the open incident so a flag left set by a previous sheet
            // does not arrive disabled on the next one.
            var incidentConfirmInFlight by
                remember(tappedIncidentId) { mutableStateOf(false) }
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
            // Bumped whenever the runtime location state may have changed (a
            // permission answer, or coming back from Settings). The platform
            // exposes no observable for either, so the state is re-read on this
            // key rather than polled.
            var locationAccessProbe by remember { mutableIntStateOf(0) }
            val locationPermissionLauncher =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) mapSurface.refreshLocationComponent()
                    // Re-read on BOTH answers: a denial is what raises the
                    // explanation card, and it used to be dropped silently.
                    locationAccessProbe++
                }
            var mapLocationPermissionRequested by rememberSaveable { mutableStateOf(false) }

            // Re-read on every resume so returning from the system settings page
            // — the one place a permanent denial or the device location switch
            // can be fixed — clears the card without the user hunting for a
            // refresh. Granting elsewhere and coming back must Just Work.
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) locationAccessProbe++
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            // Never on the config-less/CI stub: it has no puck, must not trigger a
            // system permission prompt, and must not grow a card the instrumented
            // UI tests never expected (stub-map contract).
            val locationAccess =
                if (hasMapboxToken) {
                    // Keyed on the probe so a grant/denial/settings round-trip is
                    // picked up; `context` is stable for the composition.
                    remember(locationAccessProbe, context) { currentLocationAccess(context) }
                } else {
                    LocationAccess.GRANTED
                }
            val locationRemedy =
                remember(locationAccessProbe, context, mapLocationPermissionRequested) {
                    locationPermissionRemedy(
                        canShowRationale = shouldShowLocationRationale(context),
                        alreadyAsked = mapLocationPermissionRequested,
                    )
                }
            // Dismissal is per-visit, not a preference: `remember` (not
            // rememberSaveable) and reset when the map is covered, matching the
            // map's other transient UI. The map is genuinely broken without a
            // position, so "Not now" silences the card for this look at the map
            // rather than forever — but it never re-appears while the user stays
            // on the tab, so it cannot nag on recomposition.
            // (Reset when the map is covered — see the LaunchedEffect below,
            // which lives where `mapCover` is in scope.)
            var locationPromptDismissed by remember { mutableStateOf(false) }
            var locationSettingsUnavailable by remember { mutableStateOf(false) }

            LaunchedEffect(selectedTab, mapSurface, hasMapboxToken, locationAccess) {
                if (selectedTab != ShellTab.Map || !hasMapboxToken) return@LaunchedEffect
                when {
                    // Already granted (this or a previous session): re-apply the
                    // component so the puck shows the moment the map is on screen.
                    locationAccess == LocationAccess.GRANTED ->
                        mapSurface.refreshLocationComponent()
                    // Not granted and not yet asked this session: prompt once.
                    // A denial now falls through to the explanation card below
                    // instead of vanishing.
                    locationAccess == LocationAccess.PERMISSION_DENIED &&
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

            // Community-chat unread flag (a lightweight per-user last-read marker,
            // no fan-out counter): drives the map chat bubble's "missed" dot AND
            // the chat hub's Community-tab dot. Gated like garageState so the two
            // Firestore listeners it opens (newest-message + userPrivate marker)
            // are live only while that dot can actually be seen — the Map tab
            // (bubble), the chat hub popup open over the map, or the legacy
            // ChatHub route fallback — and degrade to a constant `false`
            // otherwise. Because the popup opens over the map WITHOUT leaving the
            // Map tab (`selectedTab` stays Map), the `selectedTab == Map` term
            // already keeps the listener alive while the hub is open; `chatHubOpen`
            // is kept for clarity and `ShellRoute.ChatHub` covers the fallback
            // route. Guarded — no repo (config-less build) means never unread.
            val needsCommunityUnread =
                selectedTab == ShellTab.Map || route == ShellRoute.ChatHub || chatHubOpen
            val communityChatUnread by
                remember(communityChatRepository, uid, needsCommunityUnread) {
                    if (communityChatRepository != null && needsCommunityUnread) {
                        communityChatRepository.observeUnread(uid)
                    } else {
                        flowOf(false)
                    }
                }
                    .collectAsState(initial = false)

            // Single shared vehicles stream for the garage: exactly one Firestore
            // snapshot listener while the user is on the Garage tab — and none at
            // all off it (the flow degrades to a constant Loading). The list and
            // the add/edit form are one composable (GarageRoute), so the listener
            // survives moving between them instead of tearing down and
            // re-attaching. garageReloadKey is bumped by the list's "try again"
            // affordance to force a re-subscribe after a listener error.
            var garageReloadKey by rememberSaveable { mutableStateOf(0) }
            val inGarageSection = selectedTab == ShellTab.Garage
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
            val incidentReportSuccessText = stringResource(R.string.incidents_reportSuccess)
            val incidentReportErrorText = stringResource(R.string.incidents_reportError)
            val incidentLocationUnavailableText =
                stringResource(R.string.incidents_locationUnavailable)
            val incidentRemoveSuccessText = stringResource(R.string.incidents_removeSuccess)
            val incidentRemoveErrorText = stringResource(R.string.incidents_removeError)
            val incidentVerifySuccessText = stringResource(R.string.incidents_verifySuccess)
            val incidentVerifyAlreadyText = stringResource(R.string.incidents_verifyAlready)
            val incidentVerifyErrorText = stringResource(R.string.incidents_verifyError)

            // ── The ONE incident-reporting path ─────────────────────────────
            //
            // Hoisted here because there are now two entry points into it — the
            // map home's report control and the navigation view's — and they must
            // be the same report. Reporting from behind the wheel is exactly when
            // it matters most, so the nav view raises the same category picker and
            // files through the same `incidents-report` callable rather than
            // owning a second, drifting copy (it previously showed a "coming soon"
            // snackbar, which is now gone: the feature is live).
            //
            // The controller resolves the location itself, so neither call site
            // passes one, and the callable reads the caller's identity from the
            // Firebase Auth token rather than any threaded-through auth context —
            // which is why this works unchanged from inside the nav view.
            //
            // The snackbar is readable from navigation: SnackbarHost is the LAST
            // child of the shell's outer Box, so it draws OVER the full-screen nav
            // view rather than under it.
            val incidentReportingEnabled =
                incidentController != null &&
                    MemberGating.allows(profile?.activeMember == true)
            val reportIncident: (IncidentType, ReportLocation) -> Unit = { type, location ->
                incidentController?.let { controller ->
                    scope.launch {
                        // The location choice is resolved here: Current uses the
                        // controller's own GPS-fix path (unchanged), Chosen reports
                        // at the point the user placed on the map picker.
                        val outcome =
                            when (location) {
                                ReportLocation.Current -> controller.report(type)
                                is ReportLocation.Chosen ->
                                    controller.reportAt(type, location.location)
                            }
                        val text =
                            when (outcome) {
                                is ReportOutcome.Success -> incidentReportSuccessText
                                ReportOutcome.NoLocation -> incidentLocationUnavailableText
                                is ReportOutcome.Failed -> incidentReportErrorText
                            }
                        snackbarHostState.showSnackbar(text)
                    }
                }
            }

            // Keep the nearby-incidents layer LIVE around the user whenever the
            // Map tab is shown AND the "Traffic alerts" layer is enabled. The
            // incident layer is shared across all users, but each user only ever
            // learns of another user's report through listNearby — so a one-shot
            // fetch on tab-entry left everyone but the reporter looking at a stale
            // layer: a report made while they were already on the map never
            // appeared. pollNearby refreshes on a cadence (after a short cold-open
            // retry to acquire the first fix), so newly-reported incidents from
            // other users keep surfacing; each pass is best-effort and leaves the
            // previous markers intact on failure. Scoped to this effect, so it is
            // cancelled when the tab changes or the layer is toggled off; keyed on
            // incidentsLayerEnabled so toggling the layer back on re-fetches
            // immediately. Also keyed on mapSurface so a surface swap (token-driven
            // surface recreation, previews/tests) re-subscribes the poll to the
            // CURRENT surface instead of polling a stale, detached camera snapshot.
            // Camera-idle re-query pulses. Conflated so a pulse sent while a
            // refresh is in flight is coalesced rather than dropped or queued.
            // Keyed on mapSurface so a surface swap gets a fresh channel matching
            // the fresh poll (below), never a pulse from a detached camera.
            val incidentRequeryTicks =
                remember(mapSurface) { Channel<Unit>(Channel.CONFLATED) }
            LaunchedEffect(selectedTab, incidentController, incidentsLayerEnabled, mapSurface) {
                val controller = incidentController ?: return@LaunchedEffect
                if (selectedTab != ShellTab.Map || !incidentsLayerEnabled) return@LaunchedEffect
                // Query around the MAP CAMERA CENTRE, not only a GPS fix. The map
                // opens on the fixed default camera (Kungsbacka) and pans/follows
                // the user from there, so its centre is ALWAYS available — whereas
                // a GPS fix can be absent for the whole session (permission
                // denied, indoors, emulator, or just the first seconds). Polling
                // on the GPS fix alone left the shared incidents layer BLANK in
                // exactly those cases, even though incidents (including the
                // Trafikverket imports) exist in the DB around the visible area.
                // The one-shot GPS read is the fallback: it covers the brief
                // window before the real surface emits its first camera event, and
                // the camera-less stub (config-less / CI).
                val appContext = context.applicationContext
                controller.pollNearby(
                    // Radius follows the visible viewport (clamped server-side to
                    // [100 m, 50 km]); the stub reports a fixed sane default.
                    radiusProvider = { mapSurface.visibleRadiusMeters() },
                    centerProvider = {
                        mapSurface.cameraSnapshot.value?.let { snapshot ->
                            LatLng(
                                longitude = snapshot.longitude,
                                latitude = snapshot.latitude,
                            )
                        } ?: CurrentLocation.lastKnown(appContext)
                    },
                    // Re-query shortly after the camera settles from a meaningful
                    // pan/zoom, coalesced with the keep-alive above.
                    requeryTicks = incidentRequeryTicks,
                )
            }

            // ── Kronjakt crown layer (the AUTO-SPAWN half) ──────────────────
            //
            // Deliberately built out of the incident layer's parts rather than a
            // second, differently-tuned machine: the same camera-idle pump below,
            // the same conflated tick channel, the same two-phase poll shape (see
            // CrownSpawnController.pollNearby), and ViewportRadius via the same
            // mapSurface.visibleRadiusMeters() seam. The only thing that differs is
            // the cadence — 60 s keep-alive rather than 15 s, because the spawner
            // runs every 10 minutes and the shortest crown lives 6 hours, so a
            // faster poll has nothing to find and would only cost battery.
            val crownSpawnController =
                remember(context) { CrownSpawnController.createIfAvailable(context) }
            // BOTH flags. `crownHunt` is the feature as a whole; `crownHuntSpawn`
            // (contract default OFF) is the automatic half specifically, because an
            // auto-placed crown carries no admin's confirmation that its spot is
            // safe to stop at. With either off this is false, and false here means
            // the poll effect below returns immediately: no `crownSpawns` query is
            // ever issued, not a hidden layer that still costs reads.
            val crownSpawnEnabled =
                flags.isEnabled(FeatureFlag.CROWN_HUNT) &&
                    flags.isEnabled(FeatureFlag.CROWN_HUNT_SPAWN)
            val crownSpawnsFlow =
                remember(crownSpawnController) {
                    crownSpawnController?.nearbySpawns
                        ?: MutableStateFlow(emptyList<CrownSpawn>())
                }
            val crownSpawns by crownSpawnsFlow.collectAsState()
            val crownRequeryTicks =
                remember(mapSurface) { Channel<Unit>(Channel.CONFLATED) }
            LaunchedEffect(selectedTab, crownSpawnController, crownSpawnEnabled, mapSurface) {
                val controller = crownSpawnController ?: return@LaunchedEffect
                if (selectedTab != ShellTab.Map || !crownSpawnEnabled) {
                    // Leaving the map (or the flag going off) takes the layer down
                    // rather than freezing it: crowns are claimed once globally, so
                    // a frozen layer is a set of markers that may already be gone.
                    controller.clear()
                    return@LaunchedEffect
                }
                val appContext = context.applicationContext
                controller.pollNearby(
                    // Two gates, not one. The effect KEY above is what reacts to
                    // the flag actually changing (a flip cancels this loop and the
                    // early return above takes the layer down). This provider is
                    // the controller's own gate — checked before every single
                    // refresh — and it is what a unit test can drive, so "off means
                    // zero queries" is a proven property of the controller rather
                    // than a property of one call site's effect keys.
                    enabledProvider = { crownSpawnEnabled },
                    centerProvider = {
                        mapSurface.cameraSnapshot.value?.let { snapshot ->
                            CrownQueryCenter(
                                latitude = snapshot.latitude,
                                longitude = snapshot.longitude,
                            )
                        }
                            ?: CurrentLocation.lastKnown(appContext)?.let { fix ->
                                CrownQueryCenter(
                                    latitude = fix.latitude,
                                    longitude = fix.longitude,
                                )
                            }
                    },
                    radiusProvider = { mapSurface.visibleRadiusMeters() },
                    requeryTicks = crownRequeryTicks,
                )
            }
            // Turn camera-idle into meaningful-move re-query pulses. collectLatest
            // debounces: a new camera snapshot cancels the pending settle timer, so
            // the radius/centre are read only once the camera has been still for
            // INCIDENT_CAMERA_IDLE_DEBOUNCE_MS. The pure CameraRequeryDecision then
            // gates it, so jitter and settles at the same spot/zoom send nothing.
            //
            // ONE pump for BOTH layers, fanned out to their two channels. A second
            // copy for the crowns would mean two debounce timers and two anchors
            // racing on the same camera — twice the work to answer the same
            // question ("has the camera meaningfully moved?"), and two chances to
            // drift apart. The extra tick a layer receives when only the other one
            // moved is free: each controller re-checks its own re-query rule
            // (CameraRequeryDecision for incidents, cell-set equality for crowns)
            // and skips a pass that would return the same rows.
            LaunchedEffect(
                selectedTab,
                incidentController,
                incidentsLayerEnabled,
                crownSpawnController,
                crownSpawnEnabled,
                mapSurface,
            ) {
                if (selectedTab != ShellTab.Map) return@LaunchedEffect
                val pumpIncidents = incidentController != null && incidentsLayerEnabled
                val pumpCrowns = crownSpawnController != null && crownSpawnEnabled
                if (!pumpIncidents && !pumpCrowns) return@LaunchedEffect
                var lastAnchor: QueryAnchor? = null
                mapSurface.cameraSnapshot.collectLatest { snapshot ->
                    snapshot ?: return@collectLatest
                    delay(INCIDENT_CAMERA_IDLE_DEBOUNCE_MS)
                    val radius = mapSurface.visibleRadiusMeters() ?: return@collectLatest
                    val next =
                        QueryAnchor(
                            latitude = snapshot.latitude,
                            longitude = snapshot.longitude,
                            radiusMeters = radius,
                        )
                    if (CameraRequeryDecision.shouldRequery(lastAnchor, next)) {
                        lastAnchor = next
                        if (pumpIncidents) incidentRequeryTicks.trySend(Unit)
                        if (pumpCrowns) crownRequeryTicks.trySend(Unit)
                    }
                }
            }
            // Crowns → drawing primitives for the map seam, exactly as incidents
            // are above: the surface is handed a colour, a silhouette and a tint
            // and knows nothing about rarities. Empty whenever the feature is off,
            // so a flag flipped mid-session takes the markers off the map without
            // waiting for a poll pass.
            val crownMarkers =
                remember(crownSpawns, crownSpawnEnabled) {
                    if (!crownSpawnEnabled) {
                        emptyList()
                    } else {
                        crownSpawns.map { spawn ->
                            MapCrownMarker(
                                id = spawn.id,
                                longitude = spawn.longitude,
                                latitude = spawn.latitude,
                                discColorArgb = CrownMarkerStyle.discColorArgb(spawn.rarity),
                                iconRes = crownGlyphRes(spawn.rarity),
                                glyphColorArgb = CrownMarkerStyle.glyphColorArgb(spawn.rarity),
                                // Only the legendary tier has one; the other three
                                // pass null and are drawn without a halo.
                                glowColorArgb = CrownMarkerStyle.glowColorArgb(spawn.rarity),
                            )
                        }
                    }
                }
            LaunchedEffect(mapSurface, crownMarkers) {
                mapSurface.setCrownMarkers(crownMarkers)
            }
            // The crown the user tapped. LATCHED, in deliberate contrast to
            // [tappedIncident] just below, which is derived from the live list so a
            // vanished incident closes its sheet.
            //
            // A crown vanishes from the layer the instant it is COLLECTED — that is
            // the feature working, and it happens on success — so deriving the open
            // crown would take the "+100 KP, the crown is yours" confirmation off
            // the screen in the same frame it appeared. The latch is reset by
            // `remember(tappedCrownId)`, so a new tap always re-resolves.
            val tappedCrownId by mapSurface.crownTap.collectAsState()
            val openCrownSlot = remember(tappedCrownId) { mutableStateOf<CrownSpawn?>(null) }
            LaunchedEffect(tappedCrownId, crownSpawns) {
                val id = tappedCrownId ?: return@LaunchedEffect
                // Fill on the first emission that carries it; a later refresh that
                // drops the crown must NOT clear it (see above).
                if (openCrownSlot.value == null) {
                    openCrownSlot.value = crownSpawns.firstOrNull { it.id == id }
                }
            }
            val openCrown = if (tappedCrownId != null) openCrownSlot.value else null
            val crownClaimFlow =
                remember(crownSpawnController) {
                    crownSpawnController?.claimStatus
                        ?: MutableStateFlow<CrownClaimStatus>(CrownClaimStatus.Idle)
                }
            val crownClaimStatus by crownClaimFlow.collectAsState()
            // The rolling pair of fixes a claim needs (`crownHunt.claimSpawn` will
            // not accept one — a self-reported speed of zero is just a number the
            // client sent). Keyed to the open crown, so leaving the popup forgets
            // the pair rather than letting a stale one prove a later dwell.
            val crownFixTracker = remember(tappedCrownId) { CrownFixTracker() }
            var crownCurrentFix by remember(tappedCrownId) { mutableStateOf<CrownFix?>(null) }
            var crownPreviousFix by remember(tappedCrownId) { mutableStateOf<CrownFix?>(null) }
            // The claim has been ANSWERED — awarded or honestly refused. Terminal
            // for this popup: [CrownSpawnPopup] swaps the whole body for the
            // outcome, so there is no Collect button and no distance line left for
            // a fresh fix to feed. `Failed` is deliberately NOT terminal — that one
            // still offers a retry, which needs a live proof pair.
            val crownClaimDone = crownClaimStatus is CrownClaimStatus.Done
            // High-accuracy fixes, but ONLY while a crown popup is open AND the
            // claim is still open — so the cost is bounded by the user's own
            // attention rather than running for the whole session, and a member who
            // leaves the "+100 KP" confirmation on screen is not quietly holding
            // GPS at 2 s for as long as they admire it. A 2 s cadence gets a usable
            // proof pair a couple of seconds after the 4 s minimum dwell, which is
            // as fast as the server's own rule allows.
            LaunchedEffect(tappedCrownId, crownSpawnEnabled, crownClaimDone) {
                if (tappedCrownId == null || !crownSpawnEnabled) {
                    // With no crown open, drop any finished result. The status
                    // lives on the controller and outlives the popup, so a
                    // "someone got there first" left behind would greet the next
                    // crown the member opened — whichever route closed this one.
                    crownSpawnController?.resetClaim()
                    return@LaunchedEffect
                }
                // Answered: stop polling, but leave the popup and the last fixes
                // exactly as they are. Resetting here would replace the outcome the
                // member is reading; that belongs to the close path above.
                if (crownClaimDone) return@LaunchedEffect
                val appContext = context.applicationContext
                while (true) {
                    val fix = CrownLocation.currentFix(appContext)
                    if (fix != null) {
                        crownFixTracker.record(fix)
                        crownCurrentFix = crownFixTracker.latest
                        crownPreviousFix = crownFixTracker.proofPartner()
                    }
                    delay(CROWN_FIX_INTERVAL_MS)
                }
            }
            // Distance from the member to the open crown, or null with no fix. The
            // same haversine the rest of the app uses (via ViewportRadius), so a
            // distance shown here agrees with one computed anywhere else.
            val crownDistanceMeters =
                remember(openCrown, crownCurrentFix) {
                    val crown = openCrown
                    val fix = crownCurrentFix
                    if (crown == null || fix == null) {
                        null
                    } else {
                        CrownSpawnQuery.distanceMeters(
                            fix.latitude,
                            fix.longitude,
                            crown.latitude,
                            crown.longitude,
                        )
                    }
                }
            val crownCollectState =
                remember(crownSpawnEnabled, crownDistanceMeters, crownCurrentFix, openCrown) {
                    CrownCollectGate.evaluate(
                        featureEnabled = crownSpawnEnabled,
                        distanceMeters = crownDistanceMeters,
                        speedMetersPerSecond = crownCurrentFix?.speedMetersPerSecond,
                        collectRadiusMeters =
                            openCrown?.collectRadiusMeters
                                ?: CrownSpawnLimits.COLLECT_RADIUS_METERS,
                    )
                }
            // One key per opened crown, so a retry after a transport failure is the
            // SAME claim to the backend (which de-duplicates on it) rather than a
            // second attempt against the daily cap.
            val crownIdempotencyKey =
                remember(tappedCrownId) { java.util.UUID.randomUUID().toString() }

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
            // The user's saved places (Home/Work/favourites), also local
            // (SharedPreferences) so the shortcuts render instantly and work
            // offline. Keyed by uid — unlike recents, saved places are a curated
            // personal list, so two accounts sharing a device must not see each
            // other's Home. Device-local: they do NOT follow the user to a new
            // phone (cloud-syncing them per uid is a possible follow-up).
            val savedPlacesStore =
                remember(context, uid) { PrefsSavedPlacesStore(context, uid) }
            // Resolved in composition (lint: no resource lookups off the UI thread)
            // for the "no maps app" handoff fallback below.
            val navAppMissingText = stringResource(R.string.addressSearch_navAppMissing)

            // A map "navigate here?" gesture — a long-press on open map, or a
            // single tap on a place the basemap draws (a shop, a workshop, a
            // petrol station). The surface publishes both through ONE hook, so
            // both land in the SAME preview/confirmation here; the only difference
            // is that a tapped place arrives with its own name, which is shown
            // instead of the generic dropped-pin label. Cleared on the surface once
            // consumed so a later gesture re-triggers.
            var navSearchTarget by remember { mutableStateOf<LatLng?>(null) }
            var navSearchTargetName by remember { mutableStateOf<String?>(null) }
            // Set only when the picker was opened to CHANGE an already-saved
            // place's address (Saved-places screen → "Change address"): carries
            // WHICH place, so the save dialog pre-selects its kind (a re-pointed
            // Home saves back as Home, not a new Favourite) and sweeps the old row.
            // Cleared on every close of the overlay so no later open inherits it.
            var navSearchInitialEdit by remember { mutableStateOf<SavedPlaceEdit?>(null) }
            val pendingPlace by mapSurface.placeRequest.collectAsState()
            LaunchedEffect(pendingPlace) {
                val requested = pendingPlace ?: return@LaunchedEffect
                navSearchTarget = LatLng(requested.point.longitude, requested.point.latitude)
                navSearchTargetName = requested.name
                navSearchOpen = true
                mapSurface.consumePlaceRequest()
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
            // Viewing OTHERS on the map is the paid capability — but member
            // gating is currently DISABLED (config/MemberGating.kt), so every
            // signed-in user may view the roster. Backend parity holds: the
            // liveLocation/$uid/latest RTDB read rule has had its activeMember
            // term removed to match (firebase/database.rules.json). Re-locking
            // BOTH restores the subscription upsell in place of the roster map.
            // ALSO flag-gated: a server-disabled LIVE_LOCATION flag must fully
            // block opening the roster map / starting RTDB reads, so require the
            // flag (liveLocationEnabled) IN ADDITION to the member gate.
            val canViewLiveOthers =
                liveLocationEnabled && MemberGating.allows(profile?.activeMember == true)

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

            // --- Single-session drive recording -----------------------------
            // A Single (solo live-sharing) session records the drive alongside
            // the live marker so it can land in History. The recorder is fed by
            // the same in-screen fused-location source the manual recorder uses;
            // it is decoupled from the individual start/stop buttons and driven
            // ENTIRELY by [isSharing], so every start path records and every end
            // path (Stop / Hide / expiry) auto-saves the drive and raises the
            // Keep/Delete summary.
            // Guarded: with no drives backend (config-less/CI) nothing records
            // and live sharing still works.
            // The recording + any pending Keep/Delete summary live in the
            // process-scoped SingleSessionRecording holder, NOT in composition:
            // its lifetime is the live SESSION's (which already outlives the
            // Activity via the foreground service), so an Activity recreation —
            // rotation, the manifest doesn't lock orientation — must not drop the
            // coordinator, its points, or the prompt. Observed here so the UI
            // simply re-attaches after a recreation.
            val idleRecordingState =
                remember { MutableStateFlow<RecordingState>(RecordingState.Idle) }
            val activeRecording by SingleSessionRecording.active.collectAsState()
            val showSessionSummary by SingleSessionRecording.promptPending.collectAsState()
            val recordingState by
                (activeRecording?.state ?: idleRecordingState).collectAsState(
                    initial = RecordingState.Idle,
                )
            val driveKeptText = stringResource(R.string.savedDrives_driveKept)
            val driveDeletedText = stringResource(R.string.savedDrives_driveDeleted)
            val driveDiscardedText = stringResource(R.string.savedDrives_noDriveSaved)

            // Uploads the recorded route.bin to Cloud Storage after drives-save
            // creates the drive doc (the callable returns the path but does not
            // write the file). Null in a config-less / CI build — the drive then
            // saves without a route file, exactly as before an uploader existed.
            // Reuses the shared Storage upload boundary (FirebaseMediaUploader).
            val routeUploadRunner =
                remember(context) {
                    FirebaseMediaUploader.createIfAvailable(context)?.let { RouteUploadRunner(it) }
                }

            // Only record a drive the user could actually SAVE. drives-save is
            // member-gated (requireMemberActor) while live sharing is free, so
            // gating the recording on canShareLive — as v0.8.0 did — handed a
            // non-member an end-of-session prompt whose Save could only ever fail
            // with PERMISSION_DENIED, forever. The manual recorder
            // (RecordDriveScreen) already applies this same member rule.
            // Member gating is currently DISABLED (config/MemberGating.kt) and
            // drives-save admits any signed-in, non-suspended caller to match,
            // so this resolves to true for everyone. Routing it through the
            // switch (rather than the raw entitlement) is what keeps recording
            // aligned with saving: gating recording on the RAW flag while the
            // backend saves for everyone would invert the v0.8.0 bug — we would
            // refuse to record drives the server would happily store.
            val canRecordDrive =
                DriveRecordingGate.shouldRecord(
                    hasDrivesBackend = drivesRepository != null,
                    canShareLive = canShareLive,
                    passesMemberGate = MemberGating.allows(profile?.activeMember == true),
                )

            // Bind the recording lifecycle to the live-sharing state. Both calls
            // are idempotent, so re-running this after a recreation resumes the
            // existing recording / keeps the pending prompt rather than
            // restarting or clearing either.
            LaunchedEffect(isSharing) {
                if (isSharing) {
                    // canRecordDrive already covers the null repository; the
                    // explicit check is what smart-casts it for the start call.
                    if (drivesRepository != null && canRecordDrive) {
                        // Owned by this uid: signing out (or switching account)
                        // tears the recording down — see clearIfNotOwnedBy,
                        // driven from MainActivity's auth state.
                        SingleSessionRecording.start(uid, drivesRepository, routeUploadRunner) {
                            // Null when Play services are unavailable OR the
                            // fine-location permission isn't granted; either way
                            // no fixes can arrive and the session yields an
                            // honest duration-only summary. Evaluated HERE, as
                            // the session starts, so granting the permission and
                            // starting a new session gets a real controller.
                            DriveLocationController.createIfPermitted(context)
                        }
                    }
                } else {
                    // Session ended: stop recording and raise the end-of-session
                    // summary (the drive is then auto-saved by the effect below).
                    // The holder releases the GPS source here, at the real session
                    // end, rather than on composable disposal.
                    SingleSessionRecording.stop()
                }
            }

            // Bind the foreground POSITION PUBLISHER to the session itself, not
            // just to the manual Start button. A convoy auto-started session
            // (PR #527) flips isSharing true WITHOUT any manual start — that is
            // the whole point: accepting/starting a convoy is meant to make you
            // visible to the group without tapping "share live". But the only
            // writer of liveLocation/{uid}/latest is LocationSharingService, and
            // it used to be started ONLY from the manual single-session paths.
            // So an auto-started member had a live SESSION node server-side yet
            // never published a POSITION: every convoy member subscribing
            // observeLatest(uid) saw nothing, and no one appeared on the map.
            // Starting the publisher here — keyed on the session, not the button
            // — is the SINGLE source of truth for starting it: it covers the
            // manual AND the convoy-auto path with one rule, so the manual Start
            // handlers (startSingleSession, LiveLocationScreen onStart) no longer
            // call BackgroundLocationController.start themselves. The service
            // observes the session node and self-terminates when the session ends,
            // so there is nothing to stop here (the manual Stop/Hide paths still
            // stop it eagerly for instant feedback). Keyed on uid too so an
            // account switch that keeps a session active re-targets the publisher.
            LaunchedEffect(isSharing, uid) {
                if (isSharing && uid.isNotBlank()) {
                    BackgroundLocationController.start(context, uid)
                }
            }

            // Auto-file the drive-save failure the moment the user is shown it.
            // Keyed on the failure's code so ONE issue is filed per distinct
            // failure per prompt, not one per recomposition; the backend dedups
            // across users/devices on top of that. Fire-and-forget: the reporter
            // never throws, so a reporting problem cannot break the save prompt.
            val errorReporter = rememberClientErrorReporter()
            val saveFailure = recordingState as? RecordingState.Failed
            LaunchedEffect(saveFailure?.code, saveFailure != null) {
                if (saveFailure != null) {
                    errorReporter?.report(
                        feature = FEATURE_DRIVE_SAVE,
                        // App-generated and PII-free: no coordinates, no route,
                        // no uid — the point count is a bare magnitude and the
                        // code is what actually identifies the fault.
                        message = "Saving a live-session drive failed (${saveFailure.pointCount} points)",
                        code = saveFailure.code,
                    )
                }
            }

            // Auto-save a finished LIVE session's drive so it can never be lost by
            // a missed Save. When the session-end summary opens (PromptSave), the
            // drive is immediately persisted; the summary then asks Keep or Delete
            // over the already-saved ride. Launched on the composition [scope]
            // (NOT this effect's own coroutine) so moving off PromptSave — which
            // this very save causes — cannot cancel it mid-flight. Keyed on the
            // boolean so it fires once on entering PromptSave; an Activity
            // recreation that cancelled an in-flight save restored PromptSave, and
            // the re-created effect re-fires it (the drive is never left unsaved).
            val awaitingAutoSave = recordingState is RecordingState.PromptSave
            LaunchedEffect(awaitingAutoSave) {
                if (awaitingAutoSave) {
                    scope.launch { activeRecording?.autoSave(null) }
                }
            }

            // Terminal states release the recording so the next session starts
            // clean; a snackbar confirms the outcome. Kept / Deleted are the
            // live auto-save flow's terminals; Discarded is only reached when a
            // permanent (member-gate) save refusal is closed — nothing was saved.
            LaunchedEffect(recordingState) {
                when (recordingState) {
                    RecordingState.Kept -> {
                        SingleSessionRecording.clear()
                        snackbarHostState.showSnackbar(driveKeptText)
                    }
                    RecordingState.Deleted -> {
                        SingleSessionRecording.clear()
                        snackbarHostState.showSnackbar(driveDeletedText)
                    }
                    RecordingState.Discarded -> {
                        SingleSessionRecording.clear()
                        snackbarHostState.showSnackbar(driveDiscardedText)
                    }
                    else -> Unit
                }
            }

            // --- Live-session bar (map top strip) --------------------------
            // While a live session runs, the top of the map shows a compact pill
            // with the session's elapsed time and the distance driven this session.
            // Both values are derived here and handed to a dumb [LiveSessionBar].
            //
            // Elapsed is derived from the session's START. The drive recorder
            // captures that moment (startedAtMillis) the instant sharing begins;
            // when there is no recording (config-less build, or a caller who can't
            // record) fall back to deriving it from the live session itself — its
            // expiry minus the chosen duration. This value is STABLE (changes only
            // when a session starts), NOT per-second: the once-a-second ticker
            // lives inside [LiveSessionBar], so a running session recomposes only
            // the small bar rather than this whole (very large) composable.
            val liveSessionStartMillis: Long? =
                activeRecording?.startedAtMillis
                    ?: liveSession?.let { session ->
                        val expiry = session.expiresAtMillis
                        val durationMs =
                            session.duration?.let { it.hours.toLong() * 60L * 60L * 1000L }
                        if (expiry != null && durationMs != null) expiry - durationMs else null
                    }
            // Distance driven this session, straight off the recorder's running
            // total (0 before the first fix / when nothing is recording). Only
            // changes on GPS fixes, which the shell already observes via
            // recordingState — so it adds no extra recomposition.
            val liveSessionDistanceMeters =
                (recordingState as? RecordingState.Recording)?.distanceMeters ?: 0.0
            // Composed only while a session is actually sharing AND we have a start
            // to tick from; null otherwise composes nothing at all (no empty pill
            // in the search strip).
            val liveSessionStart = liveSessionStartMillis
            val liveSessionBarSlot: (@Composable () -> Unit)? =
                if (isSharing && liveSessionStart != null) {
                    {
                        LiveSessionBar(
                            sessionStartMillis = liveSessionStart,
                            distanceMeters = liveSessionDistanceMeters,
                        )
                    }
                } else {
                    null
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
                    openRootRoute(ShellRoute.LiveLocation)
                } else {
                    scope.launch {
                        snackbarHostState.showSnackbar(unavailableText)
                    }
                }
            }

            // Whether the live-share MANAGE sheet is shown. Raised by the centre
            // live control while a session runs (the bottom bar's live disc), it is
            // the new home for the three session controls that the removed
            // right-side broadcast button used to own: Stop, Hide me now, and Who
            // can see me (the audience screen). Only meaningful while sharing, so
            // it is force-closed the instant a session ends (expiry, sign-out, or
            // Stop from inside the sheet) by the effect below.
            var liveManageOpen by remember { mutableStateOf(false) }
            LaunchedEffect(isSharing) {
                if (!isSharing) liveManageOpen = false
            }

            // Start the Single session for the given duration. The drive
            // recording AND the foreground position publisher both auto-start off
            // the resulting isSharing flip (the session-bound effects above), so
            // neither is started explicitly here — LaunchedEffect(isSharing) is the
            // single source of truth for the publisher (the same wiring that makes
            // a convoy AUTO-started session publish).
            fun startSingleSession(duration: LiveSessionDuration) {
                liveLocationCoordinator?.let { c -> scope.launch { c.start(duration) } }
            }

            // Start the Single session IMMEDIATELY — no time/duration choice is
            // shown. When a start is actually possible we begin sharing at once
            // for the default window; otherwise fall back to the live screen /
            // unavailable snackbar (same gate as the toggle). Users can Extend
            // before expiry and Stop anytime, so a fixed default is fine.
            fun requestStartSingleSession() {
                if (liveLocationCoordinator != null && canShareLive) {
                    startSingleSession(LiveLocation.DEFAULT_SESSION_DURATION)
                } else {
                    openLiveShareFallback()
                }
            }

            /**
             * Ends the running live session. The single stop path, shared by the
             * bottom bar's STOP sign and [toggleLiveShare], so there is exactly
             * one definition of what stopping does.
             *
             * Does NOT ask anything itself: flipping `isSharing` to false is what
             * auto-saves the drive and raises the Keep/Delete summary (the
             * isSharing-bound effect above), and that dialog owns the "keep it or
             * delete the saved drive" choice.
             */
            fun stopLiveShare() {
                val c = liveLocationCoordinator
                if (c != null) {
                    scope.launch { c.stop() }
                } else {
                    openLiveShareFallback()
                }
                // Always stop the foreground service, even when unwired, so Stop
                // can never leave background location running.
                BackgroundLocationController.stop(context)
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
                        // Route Start through the single-session duration picker
                        // rather than starting with a hard-coded duration.
                        requestStartSingleSession()
                    }
                    LiveShareAction.Stop -> stopLiveShare()
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
                // can't drift. Closing the Map overlay drops the stashed
                // group-drive roster so MapHome's participant chip doesn't linger
                // after the overlay is dismissed.
                //
                // Closing the LiveLocation overlay clears any failed-command
                // status and NOTHING ELSE. It deliberately does NOT stop the
                // background service: navigating away from the live screen is not
                // "stop sharing", and tearing the service down here was exactly
                // what made live sharing die the moment the driver left the
                // screen. Sharing ends only via stopLiveShare/hideMeNow, the
                // session's own expiry, or sign-out — all of which the service
                // observes for itself.
                val closeRoute = {
                    // Per-route teardown applies to the route being CLOSED (the
                    // current top), before it is popped.
                    when (route) {
                        ShellRoute.LiveLocation -> liveLocationCoordinator?.reset()
                        ShellRoute.Map -> mapParticipantUids = ArrayList()
                        else -> Unit
                    }
                    // Pop ONE level: land on the parent hub if there is one
                    // (Settings ← Blocked users), otherwise clear the stack and
                    // return to the tab/map. Delegated to the unit-tested
                    // ShellNavigation.popRoute so production and its test agree.
                    val popped = ShellNavigation.popRoute(routeParents)
                    routeParents = ArrayList(popped.parents)
                    route = popped.current
                }

                // What, if anything, is drawn over the map. Delegated to the
                // unit-tested [ShellNavigation.mapCover] so production and its
                // tests can't drift; everything downstream (standing the surface
                // down, clearing its semantics, standing the map home's chrome
                // down, gating the chat hub) derives from this ONE value.
                val mapCover =
                    ShellNavigation.mapCover(
                        tab = selectedTab,
                        route = route,
                        navigating = navDestination != null,
                        navSearchOpen = navSearchOpen,
                    )

                // Collapse the location prompt as soon as another page covers the
                // map, matching how the map's other transient UI is reset (see
                // MapHome's LaunchedEffect(covered)). "Not now" is a momentary
                // affordance, not a preference: it silences the card for this look
                // at the map, and the next visit re-evaluates. The failed-to-open
                // -settings note is cleared with it — it describes one attempt.
                //
                // A translucent panel counts as a cover: it obscures the card's
                // place on the map, so the same "this look at the map is over"
                // reasoning applies. That matches the `covered` flag handed to
                // MapHome below, which is derived from this same [mapCover].
                LaunchedEffect(mapCover) {
                    if (mapCover != MapCover.None) {
                        locationPromptDismissed = false
                        locationSettingsUnavailable = false
                    }
                }

                // System Back: close an open route first; from a non-Map tab
                // return to the Map tab; from Map exit the app (no handler). The
                // decision is delegated to the unit-tested ShellNavigation.onBack
                // so production back behaviour and its tests can't drift. Nested
                // route BackHandlers compose deeper and take priority while
                // enabled, so this only fires at a route's own root.
                //
                // Back is also the panels' non-gesture dismissal: from History,
                // Social or Garage it returns to the Map tab, which is exactly
                // what pulling the panel down does. A drag-to-dismiss overlay
                // needs a route that is not a drag.
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

                // ── The one and only map ────────────────────────────────────────
                //
                // Composed here, once, underneath every page in the signed-in
                // shell, and never disposed while the user is signed in. Every
                // page below draws OVER it.
                //
                // This is the generalisation of the tab fix: the map used to live
                // inside the pages that show it (the map home AND the address
                // search each called MapSurface.Content), so any navigation that
                // swapped those pages destroyed the MapView and rebuilt it — a
                // whole style load with an empty GL surface on screen, which is
                // the white flash. Opening the search bar did it, closing it did
                // it again, and a long-press (which opens the same search overlay)
                // did it too. With one call site that nothing unmounts, there is
                // nothing to rebuild and nothing to flash.
                //
                // Full-bleed on purpose: one surface means one geometry, and the
                // pages that used to own a map disagreed about it anyway (the map
                // home inset its map above the bottom bar, the search did not).
                // The bottom bar is opaque and simply sits on top.
                Box(
                    modifier =
                        Modifier.fillMaxSize().then(
                            // A map the user cannot see must not answer to TalkBack
                            // through the page covering it.
                            if (mapCover != MapCover.None) Modifier.clearAndSetSemantics {} else Modifier,
                        ),
                ) {
                    mapSurface.Content(Modifier.fillMaxSize())
                }

                // Stand a HIDDEN map down (kills the pulsing puck's continuous GL
                // redraw + its GPS draw) and bring it back when it is visible
                // again. Keyed on the cover (not the tab/route) so moving between
                // two pages that both hide the map does not re-fire it.
                //
                // Transparent covers stay ACTIVE: the address search draws its
                // chrome over a map the user is still looking at and still expects
                // a puck on. Both this and MapHome's `covered` below are derived
                // from the same [mapCover], so "is it visible" and "is it live"
                // cannot drift apart.
                LaunchedEffect(mapCover, mapSurface) {
                    mapSurface.setActive(mapCover != MapCover.Opaque)
                }

                // The ONE way this app starts turn-by-turn navigation to a
                // coordinate. Hoisted out of the search overlay's Start button so
                // the convoy bar's "start navigation to the shared destination"
                // goes through exactly the same path — same SDK-vs-handoff
                // decision, same missing-maps-app fallback — instead of growing a
                // second, subtly different navigation launcher.
                val startNavigationTo: (LatLng, String) -> Unit = { dest, label ->
                    // Real in-app Mapbox turn-by-turn only exists in a build that
                    // bundles the Navigation SDK (NAV_SDK_ENABLED). The token-less
                    // noNav build (incl. the current Play release — its CI provides
                    // no MAPBOX_DOWNLOADS_TOKEN) would only show the "unavailable"
                    // stub, so there we hand off to the device's maps app for
                    // genuine turn-by-turn instead.
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
                }

                // Convoy status bar, hoisted here so the map home and turn-by-turn
                // navigation share ONE coordinator and therefore one source of
                // convoy truth — the same snapshot, the same member count, the same
                // in-flight guard — instead of each fetching its own. A null
                // repository (config-less build) yields no coordinator and no bar.
                //
                // The caller's convoy SET is loaded once via the callable and
                // re-fetched after each mutation. On TOP of that, the ONE active
                // convoy is watched live (observeActiveConvoy below): a shared
                // destination, or a member joining/leaving, set by someone else
                // then reaches this bar/map instantly rather than on the next
                // refresh — the live-position markers were already realtime (RTDB),
                // this closes the same gap for the convoy document itself.

                // The SHARED-destination repository. Deliberately the "no backend"
                // one: `convoy-setDestination` / `convoy-clearDestination` are not
                // deployed (see the ConvoyDestination file KDoc for the contract
                // they are waiting on), so it refuses every call without touching
                // the network and the bar's destination controls render disabled.
                //
                // WHEN THE BACKEND LANDS the body of this remember becomes
                // `FirebaseConvoyDestinationRepository.createIfAvailable(context)
                //     ?: UnavailableConvoyDestinationRepository`
                // and ConvoyDestinations.availability is flipped to Wired. That is
                // the whole client change — everything below already works.
                //
                // It is wrapped in `remember` now, while the value is still a
                // stable object and the wrapper looks redundant, precisely so that
                // swap stays a one-line edit. `createIfAvailable` builds a NEW
                // instance per call; assigned directly it would produce a
                // different repository on every recomposition, which changes a key
                // of the remember below and would rebuild ConvoyCoordinator each
                // time — re-running load() and resetting convoy state in a loop.
                val convoyDestinationRepository: ConvoyDestinationRepository =
                    remember { UnavailableConvoyDestinationRepository }
                val convoyBarCoordinator =
                    remember(convoyRepository, convoyDestinationRepository) {
                        convoyRepository?.let {
                            ConvoyCoordinator(it, convoyDestinationRepository)
                        }
                    }
                LaunchedEffect(convoyBarCoordinator) { convoyBarCoordinator?.load() }
                // Refresh the convoy snapshot whenever the map returns to the front.
                //
                // The bar's list is loaded once on entry and then live-watches only
                // the convoy that is ALREADY active (observeActiveConvoy derives its
                // target from the current snapshot), so a convoy the user JOINS — or
                // starts — from a screen layered over the map (the convoy list, a
                // chat invite) never reaches this bar: the snapshot that decides
                // whether the bar shows is simply stale. That is the "I can't see it
                // when I join another convoy" gap — the visibility RULE already
                // covers any accepted member (owner or not; see
                // [ConvoyBar.activeConvoy]), but it is fed month-old data. Re-loading
                // on every return to the map (mapCover -> None) picks up whatever
                // membership changed while the map was covered, so the bar appears
                // for a convoy the user is in whether they STARTED it or JOINED it.
                // load() never drops the status back to Loading, so this refreshes in
                // place — the current bar does not flicker while the new list lands.
                //
                // Only reloads on an actual RETURN to the map — a transition from a
                // covered state back to None — never on first composition. On entry
                // `mapCover` is already None, and `LaunchedEffect(convoyBarCoordinator)`
                // above has just run load() once; firing again here on that initial
                // None would be a redundant second load (extra Firestore reads /
                // listener churn) for no membership change. `mapWasCovered` gates it so
                // the reload happens only after the map has actually been covered and
                // uncovered at least once.
                var mapWasCovered by remember { mutableStateOf(false) }
                LaunchedEffect(mapCover) {
                    if (mapCover == MapCover.None) {
                        if (mapWasCovered) {
                            mapWasCovered = false
                            convoyBarCoordinator?.load()
                        }
                    } else {
                        mapWasCovered = true
                    }
                }
                // Live-watch the active convoy for as long as this screen exists.
                // The coroutine suspends inside observeActiveConvoy, so its whole
                // lifetime (and thus the Firestore listener's) is bounded by this
                // LaunchedEffect: leaving the composition cancels it and detaches
                // the listener. The listener re-targets itself when the active
                // convoy changes and attaches nothing when the caller is in none.
                LaunchedEffect(convoyBarCoordinator, uid) {
                    convoyBarCoordinator?.observeActiveConvoy(uid)
                }
                val convoyBarStatus: ConvoyListStatus =
                    convoyBarCoordinator?.status?.collectAsState()?.value
                        ?: ConvoyListStatus.Loading
                val convoyBarBusy =
                    convoyBarCoordinator?.busyConvoys?.collectAsState()?.value ?: emptySet()
                val convoyBarState = ConvoyBar.stateFor(convoyBarStatus, convoyBarBusy, uid)

                // A failed leave/end from the bar surfaces as a snackbar rather than
                // a silent no-op: the coordinator sets actionError, which we show
                // once and then clear. (Invite failures are surfaced inline in the
                // invite picker instead — see convoyInviteState below.)
                val convoyBarActionError: ConvoyActionError? =
                    convoyBarCoordinator?.actionError?.collectAsState()?.value
                val convoyBarActionErrorText =
                    convoyBarActionError?.let { stringResource(it.messageRes()) }
                LaunchedEffect(convoyBarActionError) {
                    if (convoyBarActionErrorText != null) {
                        snackbarHostState.showSnackbar(convoyBarActionErrorText)
                        convoyBarCoordinator?.clearActionError()
                    }
                }

                // Invite-more-people flow, driven from the bar's invite control. It
                // reuses the SAME friend multi-select the create-convoy flow uses
                // (ConvoyInvitePickerScreen / the shared FriendsRepository) and calls
                // `convoy-invite` through the bar's coordinator, so inviting grows
                // THIS convoy rather than creating a second one. convoyInviteConvoyId
                // non-null renders the picker over the map (see the overlay below).
                var convoyInviteConvoyId by rememberSaveable { mutableStateOf<String?>(null) }
                var convoyInviteSelected by
                    rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
                val convoyInviteFriendsCoordinator =
                    remember(friendsRepository) {
                        friendsRepository?.let { FriendsCoordinator(it) }
                    }
                val convoyInviteFriendsStatus: FriendsStatus =
                    convoyInviteFriendsCoordinator?.status?.collectAsState()?.value
                        ?: FriendsStatus.Error(FriendActionError.Generic)
                val convoyInviteState: InviteConvoyState =
                    convoyBarCoordinator?.inviteState?.collectAsState()?.value
                        ?: InviteConvoyState.Idle
                // Who the picker must NOT offer: the target convoy's current members
                // (owner + every invited/accepted/declined invitee) plus the caller,
                // so only friends actually addable via `convoy-invite` are shown.
                // Derived from the LIVE bar status (observeActiveConvoy), so it
                // recomputes as the roster changes — a friend who joins while the
                // picker is open drops out of the candidate list.
                val convoyInviteExcludedUids: Set<String> =
                    (convoyBarStatus as? ConvoyListStatus.Loaded)
                        ?.convoy(convoyInviteConvoyId ?: "")
                        ?.inviteExcludedUids(uid)
                        ?: emptySet()
                // Reconcile the selection against the LIVE exclusion set: if a chosen
                // friend joins the convoy (or is invited elsewhere) while the picker
                // is open, drop them from the selection so Submit's enabled-state and
                // the `convoy-invite` payload never carry a uid that's now already in
                // the convoy. Keyed on the excluded set (a structurally-equal set does
                // not re-fire), mirroring the convoyId-keying discipline of this flow.
                LaunchedEffect(convoyInviteExcludedUids) {
                    val pruned = invitableSelection(convoyInviteSelected, convoyInviteExcludedUids)
                    if (pruned != convoyInviteSelected) convoyInviteSelected = pruned
                }
                // (Re)load the friends snapshot each time the picker opens, so a
                // previously failed load is re-attempted rather than left stuck.
                LaunchedEffect(convoyInviteConvoyId, convoyInviteFriendsCoordinator) {
                    if (convoyInviteConvoyId != null) convoyInviteFriendsCoordinator?.load()
                }
                // The confirmation reflects reality via the backend's counts: how
                // many were actually invited and how many were skipped (already in /
                // not a friend / …) — one clear line for all-invited, all-skipped,
                // and mixed outcomes. Resolved composably (stringResource) so the
                // effect below only shows it.
                val convoyInviteDone = convoyInviteState as? InviteConvoyState.Done
                val convoyInviteConfirmText: String? =
                    when {
                        convoyInviteDone == null -> null
                        // None added — every requested invitee was skipped.
                        convoyInviteDone.invited.isEmpty() ->
                            stringResource(R.string.convoy_inviteConfirmNoneAdded)
                        // All requested invitees were added, nothing skipped.
                        convoyInviteDone.skipped.isEmpty() ->
                            stringResource(
                                R.string.convoy_inviteConfirmInvited,
                                convoyInviteDone.invited.size,
                            )
                        // Some in, some skipped.
                        else ->
                            stringResource(
                                R.string.convoy_inviteConfirmMixed,
                                convoyInviteDone.invited.size,
                                convoyInviteDone.skipped.size,
                            )
                    }
                // On a successful invite, close the picker, confirm, and reset the
                // sub-state so a later open starts clean.
                LaunchedEffect(convoyInviteState) {
                    if (convoyInviteState is InviteConvoyState.Done) {
                        convoyInviteConvoyId = null
                        convoyInviteSelected = emptySet()
                        convoyBarCoordinator?.resetInvite()
                        convoyInviteConfirmText?.let { snackbarHostState.showSnackbar(it) }
                    }
                }
                // Opens the invite picker for [convoyId] with a fresh selection.
                // resetInvite() clears any leftover Done/Error sub-state but is a
                // no-op while an invite is still Working, so re-opening cannot
                // clobber an in-flight invite's overlap guard.
                val openConvoyInvite: (String) -> Unit = { convoyId ->
                    convoyBarCoordinator?.resetInvite()
                    convoyInviteSelected = emptySet()
                    convoyInviteConvoyId = convoyId
                }
                // Removes the caller from [convoyId] (a member's Leave, confirmed in
                // the bar before this runs).
                val leaveConvoy: (String) -> Unit = { convoyId ->
                    convoyBarCoordinator?.let { c -> scope.launch { c.leave(convoyId) } }
                }

                // Track what happened to the destination the user is CURRENTLY
                // navigating to. The comparison is against the previous snapshot,
                // so a destination cleared or replaced by someone else is noticed
                // on the next convoy refresh — and never cancels the running
                // turn-by-turn (see ConvoyDestinationNavigationEvent).
                val currentConvoyDestination =
                    when (val d = convoyBarState?.destinationState) {
                        is ConvoyDestinationState.SetByMe -> d.destination
                        is ConvoyDestinationState.SetByOther -> d.destination
                        else -> null
                    }
                var previousConvoyDestination by
                    remember { mutableStateOf<ConvoyDestination?>(null) }
                var convoyDestinationEvent by
                    remember {
                        mutableStateOf<ConvoyDestinationNavigationEvent>(
                            ConvoyDestinationNavigationEvent.Unchanged,
                        )
                    }
                // Both of the above are session-scoped, so they must be cleared
                // when the ACTIVE CONVOY changes identity — including to null,
                // which is "left it / it ended".
                //
                // Without this they leak across convoys: leave a convoy while
                // navigating to its destination and the banner ("the shared
                // destination was removed") is still on screen when you join the
                // next one, now describing a convoy you are no longer in. Worse,
                // `previousConvoyDestination` would still hold the OLD convoy's
                // destination, so the first comparison inside the new convoy is
                // against a place from the previous one and can fabricate a
                // "destination changed" event that never happened.
                //
                // Declared BEFORE the event effect so that on a convoy switch the
                // reset runs first and the comparison below starts from a clean
                // slate. (#487 does the same thing for camera focus via
                // ConvoyFocusStore.onActiveConvoyChanged.)
                LaunchedEffect(convoyBarState?.convoyId) {
                    previousConvoyDestination = null
                    convoyDestinationEvent = ConvoyDestinationNavigationEvent.Unchanged
                }
                LaunchedEffect(currentConvoyDestination, navDestination) {
                    if (navDestination == null) {
                        // Navigation ended (arrived, or the user stopped it). The
                        // banner exists solely to say what happened to the
                        // destination they were DRIVING TO, and every one of its
                        // messages is phrased that way — "…while you were
                        // navigating", plus an offer to re-route to the
                        // replacement. Left up after navigation ends it is not
                        // merely stale, it asserts something false.
                        //
                        // It cannot clear itself below: navigationEvent() returns
                        // Unchanged as soon as navigatingTo is null, and the guard
                        // there only ever WRITES a non-Unchanged event, so nothing
                        // would ever take it down.
                        convoyDestinationEvent = ConvoyDestinationNavigationEvent.Unchanged
                        previousConvoyDestination = currentConvoyDestination
                        return@LaunchedEffect
                    }
                    val event =
                        ConvoyDestinations.navigationEvent(
                            previous = previousConvoyDestination,
                            current = currentConvoyDestination,
                            navigatingTo = navDestination,
                        )
                    if (event != ConvoyDestinationNavigationEvent.Unchanged) {
                        convoyDestinationEvent = event
                    }
                    previousConvoyDestination = currentConvoyDestination
                }

                // Opening the search overlay AS THE CONVOY'S PLACE PICKER rather
                // than as a plain navigate-somewhere search. One overlay, one set
                // of recents/saved places/long-press handling — the only
                // difference is the extra action in the route preview.
                var navSearchConvoyPick by rememberSaveable { mutableStateOf(false) }
                // null (rather than a bar that draws nothing) is what makes "not in
                // a convoy" compose literally nothing at all — no empty bar, no
                // placeholder, and no space reserved in the top chrome column.
                // ---- Convoy map awareness + camera focus ----------------------
                //
                // Two features off one source of truth: where the other people in
                // the convoy are (drawn as markers, or as edge arrows once they
                // leave the viewport) and what the camera frames (just you, or the
                // whole group). Both need the same input — the live positions of
                // the convoy's accepted members — so it is resolved once, here.
                val activeConvoy = ConvoyBar.activeConvoy(convoyBarStatus)

                // Session-scoped, and reset whenever the active convoy changes
                // identity — including to null, which is "left / ended". That reset
                // is what guarantees the camera goes back to normal instead of
                // being left zoomed out over a group that no longer exists.
                val convoyFocusStore = remember { ConvoyFocusStore() }
                LaunchedEffect(activeConvoy?.convoyId) {
                    convoyFocusStore.onActiveConvoyChanged(activeConvoy?.convoyId)
                }
                val convoyFocusMode by convoyFocusStore.mode.collectAsState()

                // Accepted members whose live position this convoy may read (the
                // backend already narrows that — see ConvoySummary.livePositionUids);
                // own uid dropped because the user is the puck, not a marker.
                val convoyLiveUids =
                    remember(activeConvoy?.convoyId, activeConvoy?.livePositionUids, uid) {
                        activeConvoy
                            ?.livePositionUids
                            .orEmpty()
                            .filter { it.isNotBlank() && it != uid }
                            .distinct()
                    }
                // One per-uid RTDB read each, combined — the same no-collection-scan
                // shape MapRoute uses, because the rules grant per-uid reads only.
                val convoyMarkersFlow: Flow<List<LiveMarker?>> =
                    remember(liveLocationRepository, convoyLiveUids) {
                        if (liveLocationRepository == null || convoyLiveUids.isEmpty()) {
                            flowOf(emptyList())
                        } else {
                            combine(
                                convoyLiveUids.map { liveLocationRepository.observeLatest(it) },
                            ) { it.toList() }
                        }
                    }
                val convoyMarkers by convoyMarkersFlow.collectAsState(initial = emptyList())
                val convoyMemberPositions =
                    remember(convoyMarkers) {
                        convoyMarkers.filterNotNull().map { it.toConvoyMemberPosition() }
                    }

                // The user's own live position. Only available while they are
                // live-sharing; without it the fit simply frames the others (and
                // falls back to plain follow when there is too little to fit).
                val ownLiveMarkerFlow: Flow<LiveMarker?> =
                    remember(uid, liveLocationRepository, activeConvoy?.convoyId) {
                        if (liveLocationRepository != null && uid.isNotBlank() && activeConvoy != null) {
                            liveLocationRepository.observeLatest(uid)
                        } else {
                            flowOf(null)
                        }
                    }
                val ownLiveMarker by ownLiveMarkerFlow.collectAsState(initial = null)

                // Push the framing decision at the map surface, which applies it
                // inside its EXISTING follow path (see MapSurface.setConvoyFit).
                // Null means "follow me" — and that is also what the planner
                // returns when it has too little to fit, so the restore path is the
                // same code as the never-enabled path rather than a special case
                // somebody can forget to write.
                LaunchedEffect(mapSurface, convoyFocusMode, ownLiveMarker, convoyMemberPositions) {
                    val plan =
                        ConvoyFocusPlanner.plan(
                            mode = convoyFocusMode,
                            ownPosition =
                                ownLiveMarker?.let { ConvoyLatLng(it.latitude, it.longitude) },
                            memberPositions =
                                convoyMemberPositions.map {
                                    ConvoyLatLng(it.latitude, it.longitude)
                                },
                        )
                    mapSurface.setConvoyFit(
                        points =
                            when (plan) {
                                is ConvoyCameraPlan.FollowSelf -> null
                                is ConvoyCameraPlan.FitConvoy ->
                                    plan.points.map { MapPoint(it.longitude, it.latitude) }
                            },
                        // The user's CHOICE, passed separately from the points:
                        // the planner also yields FollowSelf (null points) while
                        // focus is still on but nobody is sharing a position yet,
                        // and the surface must not read that transient gap as the
                        // user switching focus off. See MapSurface.setConvoyFit.
                        focusEnabled = convoyFocusMode == ConvoyFocusMode.Convoy,
                    )
                }

                // Composes nothing at all unless there is somebody to draw, so a
                // convoy where nobody is sharing yet adds no layer to the map.
                val convoyOverlaySlot: (@Composable () -> Unit)? =
                    if (convoyMemberPositions.isNotEmpty()) {
                        {
                            ConvoyMapAwarenessOverlay(
                                mapSurface = mapSurface,
                                members = convoyMemberPositions,
                            )
                        }
                    } else {
                        null
                    }

                // --- Nearby-public live sharers (live.listNearby discovery) ---
                // Poll the discovery callable around the current map centre while
                // the Map tab is showing. Each poll is one bounded geo query; a
                // sharer's motion between polls still streams live through their
                // per-uid RTDB marker below. A failed poll leaves the last list
                // intact (the controller swallows it), so a blip does not clear
                // the layer.
                LaunchedEffect(selectedTab, nearbyLiveController, mapSurface) {
                    val controller = nearbyLiveController ?: return@LaunchedEffect
                    if (selectedTab != ShellTab.Map) {
                        // Off the Map tab: drop the seeds so nearbyUids empties and
                        // the per-uid RTDB observeLatest listeners below are torn
                        // down — no background bandwidth/battery while the map is
                        // not on screen (the same selectedTab-gating other listeners
                        // in this shell use).
                        controller.clear()
                        return@LaunchedEffect
                    }
                    while (true) {
                        mapSurface.cameraSnapshot.value?.let { camera ->
                            controller.refresh(
                                LatLng(longitude = camera.longitude, latitude = camera.latitude),
                            )
                        }
                        delay(NEARBY_LIVE_POLL_MS)
                    }
                }

                val nearbySeedsFlow =
                    remember(nearbyLiveController) {
                        nearbyLiveController?.nearbySharers
                            ?: MutableStateFlow(emptyList<NearbyLiveSession>())
                    }
                val nearbySeeds by nearbySeedsFlow.collectAsState()

                // Discovery uids to draw: drop self and anyone already drawn by
                // the convoy layer (a convoy member who is ALSO broadcasting must
                // not appear twice, once per layer). The backend already excludes
                // self + blocked, but self is dropped again defensively. Capped at
                // MAX_NEARBY_LIVE_MARKERS: the backend can return up to 200 sorted
                // by freshness, and eagerly opening one RTDB observeLatest() stream
                // per uid in a dense area is real bandwidth/battery + backend load
                // for markers the overlay only draws while on-screen anyway. Taking
                // the freshest N bounds the concurrent listener count; distant/older
                // sharers simply aren't subscribed.
                val nearbyUids =
                    remember(nearbySeeds, convoyLiveUids, uid) {
                        val convoySet = convoyLiveUids.toSet()
                        nearbySeeds
                            .map { it.uid }
                            .filter { it.isNotBlank() && it != uid && it !in convoySet }
                            .distinct()
                            .take(MAX_NEARBY_LIVE_MARKERS)
                    }

                // One per-uid RTDB read each, combined — the SAME
                // no-collection-scan shape the convoy layer uses (the rules grant
                // per-uid reads only), so a returned uid renders from the live
                // stream, not the poll's stale seed.
                val nearbyMarkersFlow: Flow<List<LiveMarker?>> =
                    remember(liveLocationRepository, nearbyUids) {
                        if (liveLocationRepository == null || nearbyUids.isEmpty()) {
                            flowOf(emptyList())
                        } else {
                            combine(
                                nearbyUids.map { liveLocationRepository.observeLatest(it) },
                            ) { it.toList() }
                        }
                    }
                val nearbyMarkers by nearbyMarkersFlow.collectAsState(initial = emptyList())
                val nearbyLiveMarkers =
                    remember(nearbyMarkers) { nearbyMarkers.filterNotNull() }

                // Composes nothing at all unless somebody nearby is actually
                // sharing (and visible), so an empty neighbourhood adds no layer.
                val nearbyOverlaySlot: (@Composable () -> Unit)? =
                    if (nearbyLiveMarkers.isNotEmpty()) {
                        {
                            NearbyLiveOverlay(
                                mapSurface = mapSurface,
                                sharers = nearbyLiveMarkers,
                            )
                        }
                    } else {
                        null
                    }

                val convoyBarSlot: (@Composable (Boolean, Boolean) -> Unit)? =
                    if (convoyBarState != null && convoyBarCoordinator != null) {
                        { compact, showDestination ->
                            ConvoyStatusBar(
                                state = convoyBarState,
                                compact = compact,
                                showDestination = showDestination,
                                focusMode = convoyFocusMode,
                                onFocusModeChange = { convoyFocusStore.setMode(it) },
                                // The OWNER's End (group-wide, confirmed in the bar).
                                onEndConvoy = { convoyId ->
                                    scope.launch { convoyBarCoordinator.end(convoyId) }
                                },
                                // Opens the friend picker and grows THIS convoy via
                                // `convoy-invite`.
                                onInvite = openConvoyInvite,
                                // A member's Leave via `convoy-leave` (confirmed in
                                // the bar; routed on viewerIsOwner so it can never
                                // reach the owner's End path).
                                onLeaveConvoy = leaveConvoy,
                                // Reuse the map's own search / saved-places /
                                // long-press picker instead of a second one; it
                                // comes back through onSetAsConvoyDestination.
                                onSetDestination = {
                                    navSearchConvoyPick = true
                                    navSearchOpen = true
                                },
                                onClearDestination = { convoyId ->
                                    scope.launch {
                                        convoyBarCoordinator.clearDestination(convoyId)
                                    }
                                },
                                // The SAME navigation entry point the search
                                // flow's Start button uses — no parallel path.
                                onNavigateToDestination = { dest, label ->
                                    startNavigationTo(dest, label)
                                },
                                navigationEvent = convoyDestinationEvent,
                                onDismissNavigationEvent = {
                                    convoyDestinationEvent =
                                        ConvoyDestinationNavigationEvent.Unchanged
                                },
                            )
                        }
                    } else {
                        null
                    }

                if (navDestination != null) {
                    // Full-screen turn-by-turn navigation, entered from the route
                    // preview's "Start" button. Owns its own Back handling and map
                    // surface (its own Nav-SDK MapView) — which is why it counts as
                    // an OPAQUE cover and stands the shell's map down. On the
                    // config-less / CI build this is the no-SDK stub (see the
                    // src/noNav TurnByTurnNavScreen). The report affordance is wired
                    // to the SAME reporting path as the map home's control (see
                    // `reportIncident` above) — one callable, one picker, one set of
                    // result messages.
                    TurnByTurnNavScreen(
                        origin = null,
                        destination = navDestination!!,
                        destinationLabel = navDestinationLabel,
                        onExit = {
                            navDestination = null
                            mapSurface.setRouteOverlay(null)
                            navSearchOpen = false
                            // Closing the underlying picker: drop any change-address
                            // context so the next open starts uncontextualized.
                            navSearchInitialEdit = null
                        },
                        onReportIncident = reportIncident,
                        modifier = Modifier.fillMaxSize(),
                        incidentReportingEnabled = incidentReportingEnabled,
                        // Live location keeps running while the user navigates, so
                        // its control has to come WITH them into the navigation
                        // screen — wired to the same coordinator and the same
                        // start/hide actions as the map home's control below, so
                        // there is one live-sharing behaviour, not two.
                        isLiveSharing = isSharing,
                        canShareLive = canShareLive,
                        onStartLiveShare = { requestStartSingleSession() },
                        onHideMeNow = {
                            val c = liveLocationCoordinator
                            if (c != null) {
                                scope.launch { c.hideMeNow() }
                            } else {
                                openLiveShareFallback()
                            }
                            BackgroundLocationController.stop(context)
                        },
                        onOpenLiveShareDetails = { openLiveShareFallback() },
                        // Compact variant below the maneuver banner, WITH the
                        // shared-destination row (turn-by-turn has the vertical room
                        // for it — see the screen's KDoc).
                        convoyBar = convoyBarSlot?.let { bar -> { bar(true, true) } },
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
                            // Drop any long-press / place-tap target so re-opening
                            // via the search bar starts in the normal search-first
                            // state rather than re-previewing the last place.
                            navSearchTarget = null
                            navSearchTargetName = null
                            // Backing out of the picker must not leave the next
                            // plain search offering to set a convoy destination.
                            navSearchConvoyPick = false
                            // ...nor pre-frame the next save as a change-address.
                            navSearchInitialEdit = null
                        },
                        onStartNavigation = startNavigationTo,
                        // Only offered when this overlay was opened AS the convoy
                        // bar's place picker, and only enabled once
                        // `convoy-setDestination` exists.
                        onSetAsConvoyDestination =
                            if (navSearchConvoyPick && convoyBarState != null) {
                                { dest, label ->
                                    val coordinator = convoyBarCoordinator
                                    if (coordinator != null) {
                                        scope.launch {
                                            coordinator.setDestination(
                                                convoyId = convoyBarState.convoyId,
                                                latitude = dest.latitude,
                                                longitude = dest.longitude,
                                                label = label,
                                            )
                                        }
                                    }
                                    mapSurface.setRouteOverlay(null)
                                    navSearchOpen = false
                                    navSearchConvoyPick = false
                                    navSearchTarget = null
                                    navSearchTargetName = null
                                    navSearchInitialEdit = null
                                }
                            } else {
                                null
                            },
                        convoyDestinationEnabled = ConvoyDestinations.isWired,
                        recentStore = recentSearchesStore,
                        savedStore = savedPlacesStore,
                        initialTarget = navSearchTarget,
                        initialTargetName = navSearchTargetName,
                        initialSaveEdit = navSearchInitialEdit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (convoyInviteConvoyId != null && convoyBarCoordinator != null) {
                    // The convoy invite picker, opened from the bar's invite control,
                    // shown full-screen over the map. Reuses the create-flow's friend
                    // multi-select and calls `convoy-invite`. Back / Cancel closes it
                    // without inviting; a successful invite closes it via the
                    // LaunchedEffect(convoyInviteState) above.
                    val inviteConvoyId = convoyInviteConvoyId!!
                    // Back / Cancel dismisses the picker UI. resetInvite() clears a
                    // terminal Done/Error sub-state, but is a no-op while an invite
                    // is Working: dismissing mid-flight only closes the UI — the
                    // in-flight coroutine and its overlap guard stay intact and the
                    // invite runs to completion (its Done then fires the snackbar via
                    // LaunchedEffect(convoyInviteState) above). Blocking Back while
                    // Working would instead strand the user on a spinner for a
                    // fire-and-forget network call, so we let the UI close.
                    val closeInvite = {
                        convoyInviteConvoyId = null
                        convoyBarCoordinator.resetInvite()
                    }
                    BackHandler { closeInvite() }
                    ConvoyInvitePickerScreen(
                        friendsStatus = convoyInviteFriendsStatus,
                        inviteState = convoyInviteState,
                        selectedUids = convoyInviteSelected,
                        excludedUids = convoyInviteExcludedUids,
                        onToggleFriend = { friendUid ->
                            convoyInviteSelected =
                                if (friendUid in convoyInviteSelected) {
                                    convoyInviteSelected - friendUid
                                } else {
                                    convoyInviteSelected + friendUid
                                }
                        },
                        onRetryFriends =
                            convoyInviteFriendsCoordinator?.let { c ->
                                { scope.launch { c.load() } }
                            },
                        onSubmit = {
                            // Send only the still-invitable selection: guards the exact
                            // frame where a chosen friend was just excluded but the
                            // pruning effect above hasn't run yet.
                            val payload =
                                invitableSelection(
                                    convoyInviteSelected,
                                    convoyInviteExcludedUids,
                                ).toList()
                            // If every chosen friend was excluded in the meantime the
                            // payload is empty; `convoy-invite` rejects an empty
                            // inviteeUids list (schema 1..MAX), so skip the call
                            // rather than surface an avoidable server error.
                            if (payload.isNotEmpty()) {
                                scope.launch {
                                    convoyBarCoordinator.invite(inviteConvoyId, payload)
                                }
                            }
                        },
                        onCancel = closeInvite,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (route != null) {
                    RouteHost(
                        route = route!!,
                        uid = uid,
                        profileActiveMember = profile?.activeMember == true,
                        scope = scope,
                        onClose = closeRoute,
                        // Navigation from WITHIN an open route (a hub → its child,
                        // e.g. Settings → Blocked users) pushes onto the back-stack
                        // so Back pops back to the parent hub, not to the map.
                        onOpenRoute = { pushRoute(it) },
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
                                        // Opened from a roster (Events/Convoys);
                                        // push so Back returns to that roster.
                                        pushRoute(ShellRoute.Map)
                                    } else {
                                        // Distinguish WHY viewing is blocked: a
                                        // disabled LIVE_LOCATION flag → "not available"
                                        // (someone who passes the member gate
                                        // shouldn't see an upsell); otherwise it's
                                        // the subscription upsell for a caller who
                                        // fails the gate — unreachable while member
                                        // gating is disabled, since everyone passes.
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
                        onOpenGarageTab = {
                            clearRoutes()
                            selectedTab = ShellTab.Garage
                        },
                        badgesRepository = badgesRepository,
                        blockingRepository = blockingRepository,
                        friendsRepository = friendsRepository,
                        memberProfileRepository = memberProfileRepository,
                        memberProfileTargetUid = memberProfileTargetUid,
                        onOpenMemberProfile = openMemberProfile,
                        dmRepository = dmRepository,
                        convoyRepository = convoyRepository,
                        convoyOpenCreate = convoyOpenCreate,
                        // A newly created convoy shows no confirmation page: close the
                        // convoy overlay and land on the map, where the bar reflects
                        // the new (active) convoy. Clear the deep-link flag so a later
                        // re-entry from the Social hub opens list-first as normal.
                        onConvoyCreated = {
                            convoyOpenCreate = false
                            clearRoutes()
                            selectedTab = ShellTab.Map
                        },
                        chatHubPushLink = pendingChatHubLink,
                        eventDeepLinkId = pendingEventDeepLinkId,
                        onEventDeepLinkConsumed = { pendingEventDeepLinkId = null },
                        communityChatRepository = communityChatRepository,
                        communityChatUnread = communityChatUnread,
                        convoyChatRepository = convoyChatRepository,
                        dmChatOtherUid = dmChatOtherUid,
                        dmChatOtherName = dmChatOtherName,
                        onOpenChat = openChat,
                        pointsRepository = pointsRepository,
                        drivesRepository = drivesRepository,
                        garageRepository = garageRepository,
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
                        // The SAME per-uid store the navigation search reads/writes,
                        // so the Saved-places management screen and the inline save
                        // flow share one source of truth.
                        savedPlacesStore = savedPlacesStore,
                        // "Add a place" / "Change address" on the management screen
                        // reuse the existing address picker: close this route and
                        // open the navigation search (search-first). clearRoutes()
                        // (route + parent stack both cleared) so the picker returns
                        // to the map home, not to a stale saved-places snapshot.
                        onOpenAddressSearch = {
                            // "Add a place": a fresh save, so no change-address
                            // context — clear it in case one lingered.
                            navSearchInitialEdit = null
                            // Search-first: drop any lingering map-tap / post-
                            // navigation target (TurnByTurnNavScreen.onExit leaves
                            // navSearchTarget set) and convoy-pick so the picker
                            // opens on the search field, not a stale place preview.
                            navSearchTarget = null
                            navSearchTargetName = null
                            navSearchConvoyPick = false
                            clearRoutes()
                            navSearchOpen = true
                        },
                        // "Change address" for a specific saved place: carry its
                        // kind/label/id into the picker so the save UPDATES that
                        // place (re-pointed Home stays Home) rather than forking a
                        // Favourite. See SavePlaceDialog's use of initialSaveEdit.
                        onChangeSavedPlaceAddress = { place ->
                            navSearchInitialEdit = SavedPlaces.editOf(place)
                            // The re-point is the only context this open carries;
                            // clear any stale map-tap target / convoy-pick so it
                            // can't override the search-first change-address flow.
                            navSearchTarget = null
                            navSearchTargetName = null
                            navSearchConvoyPick = false
                            clearRoutes()
                            navSearchOpen = true
                        },
                    )
                } else {
                    // The shell's page frame. Deliberately a Box + bottom bar
                    // and NOT a Scaffold.
                    //
                    // Material3's Scaffold wraps its content in a Surface, and a
                    // non-clickable Surface installs an empty `pointerInput {}`
                    // whose only job is to BLOCK touch propagation to whatever is
                    // drawn beneath it. The map is a single full-bleed surface
                    // composed BELOW this frame, so that Surface swallowed every
                    // pan / pinch / rotate before the MapView ever saw it: the
                    // camera could then only be moved programmatically, which is
                    // exactly the "map is locked to my location, I can't move it
                    // with my fingers" bug. Making the Scaffold's container
                    // transparent (v0.8.2) fixed the PAINTING but left the touch
                    // blocking in place, which is why the map became visible but
                    // stayed frozen.
                    //
                    // A Box installs no pointer-input node of its own, so a
                    // gesture over empty map area falls through to the map, while
                    // the chrome and the bottom bar - which draw their own
                    // interactive nodes - keep receiving their touches exactly as
                    // before.
                    Box(
                        modifier =
                            Modifier.fillMaxSize().then(
                                // What the Scaffold's containerColor did: paint an
                                // opaque background under a page that hides the
                                // map. Only OPAQUE covers get it: the History /
                                // Social / Garage panels are translucent and must
                                // keep showing the live map through and above
                                // them, which is exactly why this is derived from
                                // the same [mapCover] as setActive, so "is it live"
                                // and "is it painted over" cannot drift apart.
                                if (mapCover == MapCover.Opaque) {
                                    Modifier.background(MaterialTheme.colorScheme.background)
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    // What the Scaffold's content padding gave the
                                    // body: clear of the bottom bar and of the
                                    // navigation-bar inset the bar sits in. Each
                                    // tab still manages its own top inset (the map
                                    // is full-bleed).
                                    .navigationBarsPadding()
                                    .padding(bottom = ShellBottomBarHeight),
                        ) {
                            // The map home's CHROME (search bar, floating controls, CTAs) — the
                            // map itself is the shell's single surface, composed above this and
                            // drawn behind it. This subtree is composed for EVERY tab, not just
                            // the Map tab, with the other tabs drawn over it, so the transient UI
                            // and Back wiring below survive a tab round-trip the way they did
                            // before the map outlived the tab.
                            //
                            // Inset above the bottom bar (unlike the full-bleed map underneath)
                            // so the floating controls never sit under the nav bar.
                            //
                            // Taken out of the semantics tree while covered, so TalkBack can't
                            // reach the map's controls through the page drawn on top of them.
                            Box(
                                modifier =
                                    Modifier.fillMaxSize().then(
                                        if (mapCover != MapCover.None) {
                                            Modifier.clearAndSetSemantics {}
                                        } else {
                                            Modifier
                                        },
                                    ),
                            ) {
                                MapHome(
                                    mapSurface = mapSurface,
                                    // Shell-owned so it survives route changes
                                    // (see the declaration for the bug this fixes).
                                    nightModeOverrideState = mapNightModeOverride,
                                    // Derived from the same [mapCover] that stands
                                    // the surface down: a map home that isn't the
                                    // page in front must not keep intercepting Back
                                    // or hold its transient UI open.
                                    covered = mapCover != MapCover.None,
                                    // Only drives the map puck now: the map home has
                                    // no live-share control of its own. Starting,
                                    // stopping, Hide-me-now and the audience screen
                                    // are reached through the centre live control
                                    // (see the shell's live-manage sheet below).
                                    isLiveSharing = isSharing,
                                    participantCount = mapParticipantUids.size,
                                    avatarUrl = mapAvatarUrl,
                                    userLabel =
                                        stringResource(R.string.shell_userMarkerLabel),
                                    // Tapping "Where to?" opens the address
                                    // search + directions overlay.
                                    onSearch = { navSearchOpen = true },
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
                                            // Top-level entries from the map-home
                                            // profile popup: each starts a fresh
                                            // stack, so Back returns to the map.
                                            onOpenRoute = { openRootRoute(it) },
                                            onSignOut = onSignOut,
                                        ),
                                    // Community-chat unread ("missed") dot:
                                    // shown as a single-count badge when the
                                    // newest community message post-dates the
                                    // caller's last-read marker. Cleared when
                                    // they open + read the Community channel.
                                    unreadChatCount = if (communityChatUnread) 1 else 0,
                                    // The chat bubble opens the chat hub
                                    // (Community / Convoys / Friends +
                                    // Notifications) as a TRANSPARENT popup over
                                    // the map (map stays visible behind), not a
                                    // full opaque route — see ChatHubPopup below.
                                    onOpenChat = { chatHubOpen = true },
                                    // Crowd-sourced incidents layer: draw the
                                    // fetched markers for everyone, and show the
                                    // report control when a repository is
                                    // configured (guarded off in CI/no-Firebase)
                                    // AND the caller passes the member gate.
                                    // That gate is currently OPEN (member gating
                                    // is disabled — config/MemberGating.kt), so
                                    // every signed-in user sees the control, and
                                    // the `incidents-report` callable admits them
                                    // to match (requireMemberActor resolves to
                                    // active-actor semantics while the backend
                                    // switch is off). The two MUST stay aligned:
                                    // if the callable is re-locked without this
                                    // gate, non-members would see an action that
                                    // fails on submit.
                                    incidentMarkers = incidentMarkers,
                                    // Community event pins for everyone (published,
                                    // upcoming, positioned). No layer toggle — event
                                    // locations are public. Tapping one opens the
                                    // event info popup composed below.
                                    eventMarkers = mapEventMarkers,
                                    // Credit Trafikverket only while their data is
                                    // actually on the map (so: not abroad, where the
                                    // Sweden-only importer contributes nothing).
                                    trafikverketDataShown = trafikverketDataShown,
                                    incidentsLayerEnabled = incidentsLayerEnabled,
                                    onIncidentsLayerEnabledChange = { incidentsLayerEnabled = it },
                                    // Hoisted above (shared with the turn-by-turn
                                    // report button) rather than inlined here, so
                                    // both call sites go through one lambda.
                                    incidentReportingEnabled = incidentReportingEnabled,
                                    onReportIncident = reportIncident,
                                    // Live-session pill in the top search strip
                                    // (between the search icon and the avatar) while
                                    // a session runs: elapsed time + distance driven.
                                    liveSessionBar = liveSessionBarSlot,
                                    // Convoy status bar, now on its OWN full-width row
                                    // directly BELOW the search strip. Every control
                                    // (member count, focus, invite, leave/End) is
                                    // inline — no expand step. The member count opens
                                    // a member-list popup; the shared-destination row
                                    // is omitted in this band (showDestination = false)
                                    // and appears in the taller turn-by-turn variant.
                                    convoyBar =
                                        convoyBarSlot?.let { bar -> { bar(false, false) } },
                                    // Convoy member markers + off-screen direction
                                    // arrows, drawn on the map under the chrome.
                                    convoyOverlay = convoyOverlaySlot,
                                    nearbyOverlay = nearbyOverlaySlot,
                                )

                                // Tapping an incident badge on the map opens its
                                // details. Composed inside the map-chrome subtree
                                // (which is taken out of the semantics tree while
                                // another tab covers the map), so a tap that landed
                                // just before a tab switch cannot leave a dialog
                                // hanging over an unrelated page.
                                //
                                // Rendered only while the tapped id still resolves
                                // to a loaded incident: an incident that expires (or
                                // is removed) out from under an open sheet closes it
                                // rather than leaving a sheet describing a marker
                                // that is no longer on the map.
                                val openIncident = tappedIncident
                                if (openIncident != null) {
                                    IncidentDetailsSheet(
                                        incident = openIncident,
                                        // Decides remove-vs-confirm. A null/blank uid
                                        // is never an owner, so a viewer we cannot
                                        // identify is never handed the remove action.
                                        viewerUid = uid,
                                        // Read once per sheet opening: the age line is
                                        // a coarse bucket ("12 min ago"), so ticking it
                                        // every frame would recompose the dialog
                                        // constantly to almost never change the text.
                                        nowMillis = remember(openIncident.id) { System.currentTimeMillis() },
                                        // "I confirm it's still here" on someone
                                        // else's report.
                                        // Confirms via `incidents-confirm`, which
                                        // bumps the shared count and extends the
                                        // incident's life; runIncidentConfirmation
                                        // closes the sheet on success and leaves it
                                        // open to retry on failure (e.g. the incident
                                        // just expired). The in-flight guard makes one
                                        // press one call.
                                        onConfirm = {
                                            val controller = incidentController
                                            if (controller != null && !incidentConfirmInFlight) {
                                                incidentConfirmInFlight = true
                                                scope.launch {
                                                    val outcome =
                                                        try {
                                                            runIncidentConfirmation(
                                                                controller = controller,
                                                                mapSurface = mapSurface,
                                                                incidentId = openIncident.id,
                                                            )
                                                        } finally {
                                                            incidentConfirmInFlight = false
                                                        }
                                                    snackbarHostState.showSnackbar(
                                                        when (outcome) {
                                                            is ConfirmOutcome.Success ->
                                                                if (outcome.alreadyConfirmed) {
                                                                    incidentVerifyAlreadyText
                                                                } else {
                                                                    incidentVerifySuccessText
                                                                }
                                                            is ConfirmOutcome.Failed ->
                                                                incidentVerifyErrorText
                                                        },
                                                    )
                                                }
                                            }
                                        },
                                        // The sheet is NOT dismissed up front: see
                                        // [runIncidentRemoval], which closes it only once
                                        // the backend has accepted. Dismissing before the
                                        // outcome was known closed the sheet for FAILED
                                        // removals too, so a removal that never happened
                                        // looked exactly like one that did, and took the
                                        // incident away before the user could retry.
                                        onRemove = {
                                            val controller = incidentController
                                            if (controller != null && !incidentRemoveInFlight) {
                                                incidentRemoveInFlight = true
                                                scope.launch {
                                                    val removed =
                                                        try {
                                                            runIncidentRemoval(
                                                                controller = controller,
                                                                mapSurface = mapSurface,
                                                                incidentId = openIncident.id,
                                                            )
                                                        } finally {
                                                            // Cleared even if the coroutine is
                                                            // cancelled, so a cancelled removal
                                                            // cannot wedge the button disabled.
                                                            incidentRemoveInFlight = false
                                                        }
                                                    snackbarHostState.showSnackbar(
                                                        if (removed) {
                                                            incidentRemoveSuccessText
                                                        } else {
                                                            incidentRemoveErrorText
                                                        },
                                                    )
                                                }
                                            }
                                        },
                                        removeInProgress = incidentRemoveInFlight,
                                        confirmInProgress = incidentConfirmInFlight,
                                        onDismiss = { mapSurface.consumeIncidentTap() },
                                    )
                                }
                                // Tapping a Kronjakt crown opens its popup: rarity,
                                // value, distance, and a Collect button that is live
                                // only within 75 m AND stopped. Composed in the same
                                // map-chrome subtree as the incident sheet, for the
                                // same reason — a tap that landed just before a tab
                                // switch cannot leave it hanging over another page.
                                val crownForPopup = openCrown
                                if (crownForPopup != null && crownSpawnEnabled) {
                                    CrownSpawnPopup(
                                        spawn = crownForPopup,
                                        state = crownCollectState,
                                        status = crownClaimStatus,
                                        distanceMeters = crownDistanceMeters,
                                        onCollect = {
                                            crownSpawnController?.let { controller ->
                                                scope.launch {
                                                    controller.collect(
                                                        spawn = crownForPopup,
                                                        current = crownCurrentFix,
                                                        previous = crownPreviousFix,
                                                        // Same key for every retry on
                                                        // this crown: the backend
                                                        // de-duplicates on it, so a
                                                        // failed-then-retried claim
                                                        // costs one attempt, not two.
                                                        idempotencyKey = crownIdempotencyKey,
                                                    )
                                                }
                                            }
                                        },
                                        onDismiss = {
                                            // Clear the RESULT as well as the tap:
                                            // the status flow lives on the
                                            // controller, so a "someone got there
                                            // first" left behind would greet the next
                                            // crown the user opened.
                                            crownSpawnController?.resetClaim()
                                            mapSurface.consumeCrownTap()
                                        },
                                    )
                                }

                                // Tapping a community event pin opens its info
                                // popup (title, when, place name, going count) with
                                // a way into the full event detail. Composed in the
                                // same map-chrome subtree as the incident sheet, so
                                // a tab switch takes it out of the semantics tree.
                                // Rendered only while the tapped id still resolves to
                                // a published event: an event that is cancelled or
                                // ends out from under an open popup closes it rather
                                // than describing a pin that is gone.
                                val openEvent = tappedEvent
                                if (openEvent != null) {
                                    val whenLabel =
                                        openEvent.startsAtMillis?.let { millis ->
                                            java.text.DateFormat
                                                .getDateTimeInstance(
                                                    java.text.DateFormat.MEDIUM,
                                                    java.text.DateFormat.SHORT,
                                                )
                                                .format(java.util.Date(millis))
                                        }
                                    EventMarkerInfoPopup(
                                        title = openEvent.title,
                                        whenLabel = whenLabel,
                                        locationName = openEvent.locationName,
                                        goingCount = openEvent.counts.going,
                                        onViewDetails = {
                                            // Reuse the event-reminder deep-link
                                            // plumbing: seed the id and switch to the
                                            // Events route, which opens that event's
                                            // detail on entry (EventsRoute.initialEventId).
                                            val id = openEvent.id
                                            mapSurface.consumeEventTap()
                                            pendingEventDeepLinkId = id
                                            route = ShellRoute.Events
                                        },
                                        onDismiss = { mapSurface.consumeEventTap() },
                                    )
                                }
                                // Location explanation, over the map rather than
                                // inside MapHome so the map chrome stays one
                                // concern. Anchored to the bottom so it does not
                                // sit under the search bar / profile button.
                                if (locationAccess.isBlocked && !locationPromptDismissed) {
                                    LocationAccessPrompt(
                                        access = locationAccess,
                                        remedy = locationRemedy,
                                        settingsUnavailable = locationSettingsUnavailable,
                                        onFix = {
                                            when {
                                                // The system dialog can still be
                                                // raised — much shorter than a
                                                // trip through Settings.
                                                locationAccess ==
                                                    LocationAccess.PERMISSION_DENIED &&
                                                    locationRemedy ==
                                                    LocationPermissionRemedy.REQUEST_AGAIN -> {
                                                    mapLocationPermissionRequested = true
                                                    locationPermissionLauncher.launch(
                                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                                    )
                                                }
                                                // Master switch off → the device
                                                // location page. The app's own
                                                // permission page cannot fix this.
                                                locationAccess ==
                                                    LocationAccess.SERVICES_OFF ->
                                                    locationSettingsUnavailable =
                                                        !openDeviceLocationSettings(context)
                                                // Permanently denied → this app's
                                                // details page, the only place the
                                                // permission can still be granted.
                                                else ->
                                                    locationSettingsUnavailable =
                                                        !openAppLocationSettings(context)
                                            }
                                        },
                                        onDismiss = { locationPromptDismissed = true },
                                        modifier =
                                            Modifier
                                                .align(Alignment.BottomCenter)
                                                .navigationBarsPadding()
                                                .padding(KccSpacing.s4),
                                    )
                                }
                            }

                            // The other three tabs render as TRANSLUCENT PANELS pulled down over
                            // the map (see TranslucentShellPanel), crossfaded so panels resolve
                            // into each other instead of snapping. Leaving a tab fades its panel
                            // back out to reveal the map, which was live underneath the whole
                            // time — that is the point of the panel: the map stays visible and,
                            // outside the card, still answers to touch.
                            Crossfade(
                                targetState = selectedTab,
                                animationSpec = tween(SHELL_TAB_FADE_MILLIS),
                                label = "shellTabContent",
                            ) { tab ->
                                when (tab) {
                                    // The map is already composed underneath, so the Map tab
                                    // draws no page of its own. Create is intercepted in
                                    // ShellBottomBar.onSelect (switches to Map + raises the
                                    // live-share prompt), so it never renders as its own tab —
                                    // it shares this branch for `when` exhaustiveness.
                                    ShellTab.Map, ShellTab.Create -> Unit

                                    ShellTab.History ->
                                        TranslucentShellPanel(
                                            onDismiss = { selectedTab = ShellTab.Map },
                                            testTag = HISTORY_PANEL_TEST_TAG,
                                        ) {
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
                                        }

                                    ShellTab.Social ->
                                        TranslucentShellPanel(
                                            onDismiss = { selectedTab = ShellTab.Map },
                                            testTag = SOCIAL_PANEL_TEST_TAG,
                                        ) {
                                            HubScreen(
                                                title = stringResource(R.string.shell_socialTitle),
                                                // Alphabetical by the DISPLAYED, localized label
                                                // (Issue 4) — see sortedHubEntriesByLabel. The
                                                // declaration order below is therefore not the
                                                // shown order, and English and Swedish order
                                                // differently, which is correct. Sorting wraps
                                                // the list rather than reordering the source so
                                                // each entry's availability gating stays exactly
                                                // where it was.
                                                entries =
                                                    sortedHubEntriesByLabel(
                                                        listOf(
                                                            HubEntry(
                                                                stringResource(R.string.shell_friendsTitle),
                                                                Icons.Filled.Groups,
                                                                if (friendsRepository != null) {
                                                                    { openRootRoute(ShellRoute.Friends) }
                                                                } else {
                                                                    null
                                                                },
                                                            ),
                                                            // Convoys intentionally removed from
                                                            // the Social menu (Issue 11): the
                                                            // convoy feature stays reachable via
                                                            // the "+" Create chooser's "Convoy"
                                                            // option and the chat hub's Convoys
                                                            // tab. Only this menu entry is gone.
                                                            HubEntry(
                                                                stringResource(R.string.shell_socialEvents),
                                                                Icons.Filled.Event,
                                                                if (eventsRepository != null) {
                                                                    { openRootRoute(ShellRoute.Events) }
                                                                } else {
                                                                    null
                                                                },
                                                            ),
                                                            HubEntry(
                                                                stringResource(R.string.shell_socialNotifications),
                                                                Icons.Filled.Notifications,
                                                                if (notificationsRepository != null) {
                                                                    { openRootRoute(ShellRoute.Notifications) }
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
                                                                    { openRootRoute(ShellRoute.CrownHunt) }
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
                                                                    { openRootRoute(ShellRoute.Partners) }
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
                                                                    { openRootRoute(ShellRoute.Billboards) }
                                                                } else {
                                                                    null
                                                                },
                                                            ),
                                                        ),
                                                        locale = LocalConfiguration.current.locales[0],
                                                    ),
                                            )
                                        }

                                    // The Garage tab IS the garage: it renders the
                                    // vehicle list and its "Add vehicle" button
                                    // directly. It used to show a hub whose only
                                    // remaining entry was a "Cars" button leading
                                    // here — a hop that hid the user's own cars
                                    // behind a tap, so it was removed along with the
                                    // hub screen itself.
                                    //
                                    // Managing your own garage is open to any
                                    // signed-in user (no longer member-gated, PR
                                    // #428); only the repo needs to be wired.
                                    ShellTab.Garage ->
                                        TranslucentShellPanel(
                                            onDismiss = { selectedTab = ShellTab.Map },
                                            testTag = GARAGE_PANEL_TEST_TAG,
                                        ) {
                                            if (garageRepository != null) {
                                                GarageRoute(
                                                    repository = garageRepository,
                                                    coordinator = garageCoordinator,
                                                    uid = uid,
                                                    garageState = garageState,
                                                    onRetry = { garageReloadKey++ },
                                                    mediaUploader = mediaUploader,
                                                )
                                            } else {
                                                LoadingScreen()
                                            }
                                        }
                                }
                            }
                        }
                        // Drawn AFTER the body so it sits on top, exactly the way
                        // Scaffold's bottomBar slot did. NavigationBar applies its
                        // own navigation-bar inset.
                        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                            ShellBottomBar(
                                selected = selectedTab,
                                onSelect = { tab ->
                                    // Create is an action, not a destination: open
                                    // the Map and raise the single/convoy chooser
                                    // rather than letting Create become the selected
                                    // tab (so it can never get "stuck" selected).
                                    if (tab == ShellTab.Create) {
                                        selectedTab = ShellTab.Map
                                        showCreateChooser = true
                                    } else {
                                        selectedTab = tab
                                    }
                                },
                                // While a session runs the centre "+" becomes the
                                // live-session disc: tapping it raises the manage
                                // sheet (Stop / Hide me now / Who can see me) rather
                                // than stopping outright, so the two privacy
                                // controls that lived only on the removed right-side
                                // broadcast button are reachable here. Stop is the
                                // sheet's prominent first action, so ending a
                                // session is still one flow through this one control.
                                isSharing = isSharing,
                                onManageLiveShare = { liveManageOpen = true },
                            )
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
                            openRootRoute(ShellRoute.WhatsNew)
                        },
                        onDismiss = acknowledge,
                    )
                }

                // Transparent chooser raised by the Create tab: "Single session"
                // starts a solo live-share drive via the shared toggle path;
                // "Convoy" deep-links into the create-convoy flow. Cancel or an
                // outside tap dismisses it, staying on the map.
                if (showCreateChooser) {
                    CreateChooserDialog(
                        onSingleSession = {
                            showCreateChooser = false
                            // Single session = start a solo live-share session
                            // (which records the drive and prompts to save it to
                            // History at end-of-session). Starts IMMEDIATELY for
                            // the default window — no time/duration is chosen.
                            // Guard on isSharing so confirming can never disturb
                            // an active session — the fallback still runs when
                            // unwired.
                            if (!isSharing) requestStartSingleSession()
                        },
                        onConvoy = {
                            showCreateChooser = false
                            // Deep-link straight into #417's create-convoy flow;
                            // the owner can start the convoy from its detail.
                            convoyOpenCreate = true
                            openRootRoute(ShellRoute.Convoys)
                        },
                        onDismiss = { showCreateChooser = false },
                    )
                }

                // Live-share MANAGE sheet: the transparent [LiveSharePopup] the
                // centre live control raises while a session runs. This is where
                // the two capabilities the removed right-side broadcast button used
                // to solely own now live — Hide me now, and Who can see me (the
                // full LiveLocationScreen via "More options") — alongside a
                // prominent Stop. Rendered at the shell (not in MapHome) so it is
                // reachable from every tab the bottom bar is on, and gated on
                // isSharing so it only ever appears for a live session. Wired to
                // the SAME stop/hide/details handlers as before, so nothing about
                // what these actions do changed — only where they are reached.
                if (liveManageOpen && isSharing) {
                    LiveSharePopup(
                        isSharing = true,
                        canShareLive = canShareLive,
                        // Unused while sharing (no Start row), kept non-null for the
                        // shared signature.
                        onStart = {},
                        onStop = { stopLiveShare() },
                        onHideMeNow = {
                            val c = liveLocationCoordinator
                            if (c != null) {
                                scope.launch { c.hideMeNow() }
                            } else {
                                openLiveShareFallback()
                            }
                            BackgroundLocationController.stop(context)
                        },
                        onOpenDetails = { openLiveShareFallback() },
                        onDismiss = { liveManageOpen = false },
                    )
                }

                // End-of-session summary: shown when a Single session ends with a
                // recording active. The drive is AUTO-SAVED (see the auto-save
                // effect above), and this dialog asks Keep (it stays in History)
                // or Delete (removed via drives-delete). Retry re-runs the save
                // after a transient failure; Discard closes a permanent (member-
                // gate) refusal, where nothing was saved.
                if (showSessionSummary && activeRecording != null) {
                    SessionSummaryDialog(
                        state = recordingState,
                        pointsProvider = { activeRecording?.recordedPoints() ?: emptyList() },
                        onKeep = { activeRecording?.keep() },
                        onDelete = { scope.launch { activeRecording?.delete() } },
                        onRetry = { scope.launch { activeRecording?.autoSave(null) } },
                        onDiscard = { activeRecording?.discard() },
                    )
                }

                // Chat hub as a TRANSPARENT popup over the map (Issue 4): a focusable
                // Popup with no dimming scrim and a translucent surface, so the live
                // map stays visible behind it — matching the map-layers and
                // live-share popups.
                //
                // The gate: the popup floats over the map, so it may only show while
                // the map home is the page in front — never over a full route, a
                // non-map tab, turn-by-turn, or the nav-search overlay. That is
                // precisely "nothing covers the map", so it reads the shell's single
                // [mapCover] rather than restating the condition (which is how the
                // nav-search term would have been missed when search stopped being
                // its own branch). Both the auto-close effect below and the render
                // condition read THIS value, so the two cannot drift apart.
                val chatHubGateOpen = mapCover == MapCover.None

                // Auto-close. `chatHubOpen` is rememberSaveable so a genuinely open
                // (and still valid) hub survives process death — but the popup only
                // RENDERS while the gate holds. Without this effect, losing the gate
                // would hide the popup while leaving the flag set, and the hub would
                // pop open again by itself the next time the user came back to the
                // map. Keyed on the same derived gate, so no gate can be lost without
                // clearing the flag.
                LaunchedEffect(chatHubGateOpen) {
                    if (!chatHubGateOpen) chatHubOpen = false
                }

                val chatHubVisible = chatHubOpen && chatHubGateOpen
                if (chatHubVisible) {
                    ChatHubPopup(
                        uid = uid,
                        communityChatRepository = communityChatRepository,
                        convoyChatRepository = convoyChatRepository,
                        friendsRepository = friendsRepository,
                        dmRepository = dmRepository,
                        notificationsRepository = notificationsRepository,
                        notificationsCoordinator = notificationsCoordinator,
                        communityUnread = communityChatUnread,
                        onClose = { chatHubOpen = false },
                        // Tapping a sender in a channel / the DM title opens their
                        // read-only profile — a shell ROUTE, which the hub popup's
                        // gate (route == null) does not survive. Close the hub
                        // explicitly rather than leaning on the auto-close effect,
                        // so the flag is cleared in the same frame as the
                        // navigation and never lingers set behind the route.
                        // Guarded: no profile repository (config-less build) leaves
                        // the affordances inert instead of closing the hub for a
                        // route that could only spin.
                        onViewProfile =
                            if (memberProfileRepository != null) {
                                { targetUid ->
                                    if (targetUid.isNotBlank()) {
                                        chatHubOpen = false
                                        openMemberProfile(targetUid)
                                    }
                                }
                            } else {
                                null
                            },
                        // Backs the block action on the long-press message sheet.
                        // Unlike onViewProfile this must NOT close the hub: the
                        // sheet and its confirm dialog compose inside it, and
                        // blocking from chat should leave the user where they were.
                        blockingRepository = blockingRepository,
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
 * Height of [ShellBottomBar]'s icon row (ABOVE the system navigation-bar inset
 * the bar reserves below itself). [ShellBottomBar] enforces this height on the
 * Material3 [NavigationBar] explicitly, so it is the single source of truth: it
 * is shorter than the M3 default 80dp container so the icons sit lower — closer
 * to the system bar with less dead space — while the bar still reserves the full
 * system inset below them. Overlays that must sit above the bar derive their
 * offset from this value (see the body padding in the shell), so changing it here
 * moves the bar and those overlays together and they cannot drift apart.
 */
internal val ShellBottomBarHeight = 64.dp

/**
 * The 5-tab bottom navigation; Map is the default, highlighted home tab.
 *
 * The centre item is dual-purpose: a "+" that raises the create chooser, and —
 * while [isSharing] — the live-session disc that raises the live-share manage
 * sheet ([onManageLiveShare]). That sheet is the single home for the whole
 * session's controls — Stop (its prominent first action), Hide me now, and Who
 * can see me — so this control still owns ending a session, and the two privacy
 * capabilities that used to live only on the map's now-removed right-side
 * broadcast button are reachable through it.
 *
 * The disc keeps its error-red fill while sharing (an active session drawing
 * attention); tapping it opens the sheet rather than stopping outright, so
 * stopping is one flow-through-one-control away instead of a single tap. The
 * content description reflects that it opens the live controls.
 *
 * `internal` rather than private so the "+"→live-disc swap can be tested against
 * this composable directly: the swap needs a RUNNING session, which the
 * whole-shell test cannot reach (it renders the no-Firebase configuration, where
 * there is no live-location repository and `isSharing` is always false).
 */
@Composable
internal fun ShellBottomBar(
    selected: ShellTab,
    onSelect: (ShellTab) -> Unit,
    isSharing: Boolean,
    onManageLiveShare: () -> Unit,
) {
    // 50%-alpha surface container so the map shows through the bar; icon-only
    // items (no labels) keep the tabs compact over the semi-transparent map.
    //
    // The Material3 NavigationBar hardcodes an 80dp icon row and reserves the
    // system navigation-bar inset BELOW it. Left at the default that 80dp centres
    // the icons high, leaving a wide dead band above the system bar. We pin the
    // total bar height to ShellBottomBarHeight (the icon row) PLUS that same
    // bottom inset, which shrinks the row from 80dp to ShellBottomBarHeight so the
    // icons drop lower — while windowInsetsPadding still reserves the FULL inset,
    // so the icons never slide under the system nav buttons. On gesture-nav phones
    // the inset is near-zero (bar sits low, tight gap); on 3-button-nav phones it
    // is large (bar floats above the buttons with clearance intact) — correct on
    // both because the reserved height tracks the device's real inset.
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    NavigationBar(
        modifier = Modifier.height(ShellBottomBarHeight + bottomInset),
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
        // The centre action is a "+" that starts a session, and — while one RUNS —
        // the live-session disc that raises the manage sheet: one control for the
        // session's whole life, so the way out is exactly where the way in was.
        // The sheet leads with Stop (which auto-saves the drive and raises the
        // Keep/Delete summary via the isSharing effect, where the "keep it or
        // delete the saved drive" choice is made);
        // it also carries Hide me now and Who can see me, the two controls the
        // map's removed right-side broadcast button used to own.
        NavigationBarItem(
            selected = !isSharing && selected == ShellTab.Create,
            onClick = { if (isSharing) onManageLiveShare() else onSelect(ShellTab.Create) },
            // Standout action: a glyph on a filled disc so it reads as a distinct
            // button rather than another tab, in both light and dark. The disc
            // turns error-red while sharing so the stop affordance is
            // unmistakable. The FILLED DISC is the deliberate part and is kept —
            // it is what stops the control washing out over the 50%-alpha bar.
            //
            // The glyph tint, however, is now taken from the theme instead of a
            // hardcoded Color.White. The disc is brandPrimary gold (#EAB54B) in
            // BOTH themes, and white-on-gold measures 1.87:1 — below the 3:1
            // WCAG minimum for graphical objects — so the old white glyph was
            // low-contrast in light AND dark, not just one of them. onPrimary is
            // inkBlack (the token system's own documented "dark text on gold"
            // decision, see KccTheme) and measures 10.97:1. The red stop disc
            // keeps a near-white glyph via onError (warmIvory, 5.36:1), so the
            // stop state is unchanged.
            icon = {
                Box(
                    modifier =
                        Modifier
                            .size(KccSpacing.s8)
                            .clip(CircleShape)
                            .background(
                                if (isSharing) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isSharing) Icons.Filled.Stop else Icons.Filled.Add,
                        // While sharing the glyph stays the recognisable stop
                        // square on the red disc, but tapping now opens the manage
                        // sheet (whose first action is Stop), so the label says
                        // "live location controls" rather than "stop" — honest for
                        // TalkBack about what the tap does.
                        contentDescription =
                            stringResource(
                                if (isSharing) R.string.shell_liveControls else R.string.shell_tabCreate,
                            ),
                        tint =
                            if (isSharing) {
                                MaterialTheme.colorScheme.onError
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
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
 * Transparent chooser shown over the map when the user taps the "Create" tab:
 * a translucent-surfaced [AlertDialog] offering two ways to drive —
 * **Single session** ([onSingleSession], the existing solo live-share drive) or
 * **Convoy** ([onConvoy], deep-linking into the create-convoy flow). [onDismiss]
 * (Cancel or an outside tap) leaves the user on the map. The "+" tab is an
 * action, so dismissing simply drops back to the map (Create is never a
 * selected tab).
 */
@Composable
private fun CreateChooserDialog(
    onSingleSession: () -> Unit,
    onConvoy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // Translucent surface so the map stays visible behind the chooser
        // (shared Aero token).
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = KccAlpha.aeroSurface),
        title = { Text(stringResource(R.string.shell_createChooserTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                Text(stringResource(R.string.shell_createChooserBody))
                CreateChooserOption(
                    icon = Icons.Filled.DirectionsCar,
                    title = stringResource(R.string.shell_createChooserSingle),
                    body = stringResource(R.string.shell_createChooserSingleBody),
                    onClick = onSingleSession,
                )
                CreateChooserOption(
                    icon = Icons.Filled.Groups,
                    title = stringResource(R.string.shell_createChooserConvoy),
                    body = stringResource(R.string.shell_createChooserConvoyBody),
                    onClick = onConvoy,
                )
            }
        },
        // No confirm button — each option card acts immediately; Cancel dismisses.
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.shell_liveSharePromptCancel))
            }
        },
    )
}

/**
 * A single tappable option row inside [CreateChooserDialog]: a leading [icon]
 * with a [title] and a supporting [body] line. Selecting it runs [onClick].
 */
@Composable
private fun CreateChooserOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
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
    /**
     * Switches to the Garage tab (and closes this route). The garage is no
     * longer a sub-route, so the retired [ShellRoute.Garage] redirects here.
     */
    onOpenGarageTab: () -> Unit,
    badgesRepository: BadgesRepository?,
    blockingRepository: BlockingRepository?,
    friendsRepository: FriendsRepository?,
    memberProfileRepository: MemberProfileRepository?,
    memberProfileTargetUid: String?,
    onOpenMemberProfile: (String) -> Unit,
    dmRepository: DmRepository?,
    convoyRepository: ConvoyRepository?,
    convoyOpenCreate: Boolean,
    // Invoked once a convoy is successfully created: the create flow shows no
    // confirmation page — it dismisses the convoy surface and lands on the Map
    // tab, where the convoy bar shows the new (active) convoy.
    onConvoyCreated: () -> Unit,
    // Destination of the push tap that opened the chat hub, if that is why it is
    // open. Forwarded to ChatHubRoute, which owns tab/channel sub-navigation.
    chatHubPushLink: PushDeepLink?,
    // Event id from an event-reminder push tap (null otherwise). Forwarded to
    // EventsRoute to open that event on entry; [onEventDeepLinkConsumed] clears
    // the shell's pending id once EventsRoute has taken it.
    eventDeepLinkId: String?,
    onEventDeepLinkConsumed: () -> Unit,
    communityChatRepository: CommunityChatRepository?,
    // Collected once in AuthenticatedApp (drives the map chat-bubble dot); passed
    // down so the chat hub reuses that single unread listener instead of starting
    // its own duplicate observeUnread subscription.
    communityChatUnread: Boolean,
    convoyChatRepository: ConvoyChatRepository?,
    dmChatOtherUid: String?,
    dmChatOtherName: String?,
    onOpenChat: (String, String?) -> Unit,
    pointsRepository: PointsRepository?,
    // Owner drives list, folded into the profile's "my stats" summary (same
    // owner query the History tab uses). Null in a config-less build.
    drivesRepository: DrivesRepository?,
    // Owner garage list. Used ONLY by the Profile route, to put an honest
    // "3 / 5 vehicles" bar under the Samlare ladder — the same owner query the
    // Garage tab runs, listened to only while the Profile route is composed.
    garageRepository: GarageRepository?,
    partnerApplicationCoordinator: PartnerApplicationCoordinator?,
    billboardsRepository: BillboardsRepository?,
    accountDeletionCoordinator: AccountDeletionCoordinator?,
    partnerStatsRepository: PartnerStatsRepository?,
    partnerStatsCoordinator: PartnerStatsCoordinator?,
    feedbackCoordinator: FeedbackCoordinator?,
    billingRepository: BillingRepository?,
    subscriptionVerifier: SubscriptionVerifier?,
    partnerStatsEnabled: Boolean,
    savedPlacesStore: SavedPlacesStore,
    onOpenAddressSearch: () -> Unit,
    onChangeSavedPlaceAddress: (SavedPlace) -> Unit,
) {
    val context = LocalContext.current
    // The one guarded profile-navigation callback every member-bearing surface
    // reuses (chat senders, the convoy roster, event-chat authors, event
    // attendee rows) — mirroring
    // the Friends screen's onViewProfile. Null when the profile repository isn't
    // wired (config-less build), which leaves those affordances inert rather than
    // navigating to a route that could only render a permanent spinner.
    val openProfileIfWired: ((String) -> Unit)? =
        if (memberProfileRepository != null) onOpenMemberProfile else null
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

            // The avatar pick currently being EDITED, and its display-only preview
            // decode. Both raw, unsanitised and inert: `avatarCropCandidate` is the
            // ORIGINAL pick (it only reaches Storage via compressForPublicUpload on
            // confirm) and `avatarCropPreview` is a Bitmap, which has no encoded
            // form to upload. Plain remember (bytes/bitmaps are too large for the
            // saved-instance Bundle — a process death just drops the pending edit).
            var avatarCropCandidate by remember(mediaUploader) { mutableStateOf<PickedImage?>(null) }
            var avatarCropPreview by remember(mediaUploader) { mutableStateOf<Bitmap?>(null) }
            val cancelAvatarCrop = {
                avatarCropCandidate = null
                avatarCropPreview = null
            }
            val avatarPicker =
                rememberImagePickLauncher(
                    // Read with a higher cap than the 5 MB upload cap so the raw
                    // pick reaches ImageCompressor (which shrinks it below the
                    // upload cap). Still bounded to avoid OOM; the upload precheck
                    // on the compressed result enforces PROFILE_IMAGE_MAX_BYTES.
                    maxBytes = MediaUpload.PROFILE_IMAGE_READ_MAX_BYTES,
                    // A photo WAS chosen but could not be read (cloud-only item
                    // that never downloaded, unreadable/oversized file, resolver
                    // error). Previously this was dropped silently, so the user
                    // saw nothing happen after picking; surface it as a failure so
                    // the change-avatar section shows the retry error. A plain
                    // cancel routes to onPicked(null) below and stays silent.
                    onPickFailed = { avatarCoordinator?.markFailed() },
                ) { picked ->
                    if (picked != null && avatarCoordinator != null && profileRepository != null) {
                        // Straight to the shared gesture editor — NOTHING is
                        // uploaded on picking. decodeForCrop hands back a display-
                        // only Bitmap; the pick's bytes are held untouched until the
                        // user confirms a crop/rotation, and only then does
                        // compressForPublicUpload (crop + free rotate + downscale +
                        // EXIF/GPS strip) consume them.
                        val preview =
                            ImageCompressor.decodeForCrop(
                                picked,
                                maxDimension = ImageCompressor.AVATAR_MAX_DIMENSION,
                            )
                        if (preview != null) {
                            avatarCropCandidate = picked
                            avatarCropPreview = preview
                        } else {
                            // Undecodable pick: could not be shown and could not have
                            // been sanitised either. Fail visibly.
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

            // "My stats" summary — assembled entirely from owner reads the app
            // already knows how to make, and scoped to THIS route: the three
            // listeners below only exist while the Profile route is composed and
            // tear down on leaving it. No new query or index is added.
            //   • drives  → the same owner list the History tab folds, run through
            //     the shared DriveStatsCalculator (from the drive-stats page);
            //   • badges  → the owner users/{uid}/badges list (the badge wall);
            //   • points  → the single pointsLedger/{uid}.balance doc, plus the
            //     same bounded newest-first entries listener the Kronpoäng screen
            //     uses (credits only are shown — see Points.recentEarnings);
            //   • garage  → the owner vehicles list, only so the Samlare ladder
            //     gets an honest bar; the Garage tab runs the identical query.
            //   • member since → users/{uid}.createdAt, already on `profile`.
            val drivesState by
                remember(drivesRepository, uid) {
                    drivesRepository?.observeDrives(uid) ?: flowOf(DrivesState.Loading)
                }
                    .collectAsState(initial = DrivesState.Loading)
            val badgesState by
                remember(badgesRepository, uid) {
                    badgesRepository?.observeBadges(uid) ?: flowOf(BadgesState.Loading)
                }
                    .collectAsState(initial = BadgesState.Loading)
            val pointsBalance by
                remember(pointsRepository, uid) {
                    pointsRepository?.observeBalance(uid) ?: flowOf<Long?>(null)
                }
                    .collectAsState(initial = null)
            val pointsEntriesState by
                remember(pointsRepository, uid) {
                    pointsRepository?.observeEntries(uid) ?: flowOf(PointsEntriesState.Loading)
                }
                    .collectAsState(initial = PointsEntriesState.Loading)
            val profileGarageState by
                remember(garageRepository, uid) {
                    garageRepository?.observeGarage(uid) ?: flowOf(GarageState.Loading)
                }
                    .collectAsState(initial = GarageState.Loading)
            // Start of the current calendar month (device time zone) — required by
            // the shared fold; the profile summary reads only its all-time fields,
            // but the value is kept correct rather than faked. Computed on each
            // composition (deliberately NOT cached in an unkeyed remember, matching
            // DriveStatsScreen) so it re-evaluates on the next recomposition after a
            // month rollover rather than staying pinned to the month the Profile
            // route first composed in. The value is deterministic within a month, so
            // the keyed statsSummary fold below still only recomputes when the drives,
            // badges, points, or the month change.
            val statsMonthStart =
                Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            val statsSummary =
                remember(drivesState, badgesState, pointsBalance, profile?.createdAtMillis, statsMonthStart) {
                    val loadedDrives = (drivesState as? DrivesState.Loaded)?.drives
                    val loadedBadges = (badgesState as? BadgesState.Loaded)?.badges
                    // Hold the section back until the two activity signals have
                    // both resolved, so a member with drives never flashes the
                    // "start driving" empty state before the drives list loads.
                    if (loadedDrives == null || loadedBadges == null) {
                        null
                    } else {
                        ProfileStatsSummary.from(
                            driveStats = DriveStatsCalculator.compute(loadedDrives, statsMonthStart),
                            badgeCount = loadedBadges.size,
                            pointsBalance = pointsBalance,
                            memberSinceMillis = profile?.createdAtMillis,
                        )
                    }
                }

            // The badge wall. Null until the owner badge list resolves, so the
            // section stays absent rather than flashing an empty wall at a member
            // who actually holds badges. Counters are the two the client can
            // observe HONESTLY from reads it already makes here (see
            // BadgeCounters); every other ladder renders its goal without a bar.
            val badgeShowcase =
                remember(badgesState, drivesState, profileGarageState, statsMonthStart) {
                    (badgesState as? BadgesState.Loaded)?.badges?.let { badges ->
                        val drives = (drivesState as? DrivesState.Loaded)?.drives
                        BadgeShowcase.from(
                            badges = badges,
                            counters =
                                BadgeCounters(
                                    savedDriveDistanceMeters =
                                        drives?.let {
                                            DriveStatsCalculator.compute(it, statsMonthStart)?.totalDistanceMeters
                                        },
                                    vehiclesInGarage =
                                        (profileGarageState as? GarageState.Loaded)?.vehicles?.size,
                                ),
                        )
                    }
                }
            // Credits only, newest first — a redeemed reward answers a different
            // question and belongs on the full Kronpoäng screen.
            val recentPointsEarnings =
                remember(pointsEntriesState) {
                    (pointsEntriesState as? PointsEntriesState.Loaded)
                        ?.let { Points.recentEarnings(it.entries) }
                        ?: emptyList()
                }

            val avatarCropping = avatarCropPreview
            val avatarCandidate = avatarCropCandidate
            // While editing an avatar, system/gesture Back cancels the EDIT and
            // returns to the profile rather than closing the whole route.
            BackHandler(enabled = avatarCropping != null) { cancelAvatarCrop() }
            if (avatarCropping != null && avatarCandidate != null && avatarCoordinator != null) {
                // Release the preview's pixels once it can no longer be drawn (same
                // onDispose rationale as the garage crop: recycling in the
                // cancel/confirm handler would race the outgoing frame).
                DisposableEffect(avatarCropping) {
                    onDispose { avatarCropping.recycle() }
                }
                // Avatars get the SQUARE frame shown as a CIRCLE mask (the profile
                // renders them round). The gesture editor replaces the profile
                // screen while open; confirming resolves an (angle, crop) pair that
                // compressForPublicUpload turns into stripped, cropped, rotated
                // bytes — the ONLY route from this pick to Storage.
                ImageEditScreen(
                    bitmap = avatarCropping,
                    frameShape = ImageEditFrameShape.CIRCLE,
                    initialAspect = 1f,
                    onConfirm = { rotationDegrees, crop ->
                        cancelAvatarCrop()
                        // Route scope, not the editor's: the editor leaves
                        // composition on cancelAvatarCrop above, and a screen-scoped
                        // coroutine would be cancelled mid-sanitise.
                        scope.launch {
                            val repo = profileRepository
                            if (repo != null) {
                                // Strip GPS + identifying metadata BEFORE upload:
                                // avatars are PUBLICLY readable by any member, so a
                                // selfie taken at home must never leak coordinates or
                                // a device fingerprint. compressForPublicUpload
                                // GUARANTEES the bytes are free of every STRIP_TAG,
                                // and fails closed (null) rather than upload raw
                                // bytes — with a crop/rotation requested there is no
                                // whole-frame fallback.
                                val sanitized =
                                    ImageCompressor.compressForPublicUpload(
                                        avatarCandidate,
                                        maxDimension = ImageCompressor.AVATAR_MAX_DIMENSION,
                                        crop = crop,
                                        rotationDegrees = rotationDegrees,
                                    )
                                if (sanitized != null) {
                                    val imageId = MediaUpload.newImageId(sanitized.contentType)
                                    val path = MediaUpload.profileImagePath(uid, imageId)
                                    avatarCoordinator.upload(sanitized, path) { storedPath ->
                                        repo.updateAvatarPath(uid, storedPath)
                                    }
                                } else {
                                    avatarCoordinator.markFailed()
                                }
                            }
                        }
                    },
                    onCancel = cancelAvatarCrop,
                )
            } else {
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
                    statsSummary = statsSummary,
                    badgeShowcase = badgeShowcase,
                    pointsBalance = pointsBalance,
                    recentPointsEarnings = recentPointsEarnings,
                )
            }
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
                    // The publisher starts off the session-bound
                    // LaunchedEffect(isSharing) (the single source of truth), not
                    // here — see startSingleSession.
                    liveLocationCoordinator?.let { c -> scope.launch { c.start(d) } }
                },
                onStop = {
                    liveLocationCoordinator?.let { c -> scope.launch { c.stop() } }
                    BackgroundLocationController.stop(context)
                },
                onExtend = {
                    // Keep sharing: a fresh capped window (never past 6h). The
                    // foreground service and drive recording continue untouched —
                    // only the session's expiry is pushed forward server-side.
                    liveLocationCoordinator?.let { c -> scope.launch { c.extend() } }
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
                    passesMemberGate = MemberGating.allows(profileActiveMember),
                    chatRepository = chatRepository,
                    chatCoordinator = chatCoordinator,
                    chatEnabled = chatEnabled,
                    groupDriveRepository = groupDriveRepository,
                    groupDriveCoordinator = groupDriveCoordinator,
                    onShowOnMap = onShowOnMap,
                    initialEventId = eventDeepLinkId,
                    onInitialEventConsumed = onEventDeepLinkConsumed,
                    onBack = onClose,
                    blockingRepository = blockingRepository,
                    onViewProfile = openProfileIfWired,
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.CrownHunt ->
            if (crownHuntRepository != null) {
                CrownHuntRoute(
                    repository = crownHuntRepository,
                    coordinator = crownHuntCoordinator,
                    passesMemberGate = MemberGating.allows(profileActiveMember),
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
                    passesMemberGate = MemberGating.allows(profileActiveMember),
                    onBack = onClose,
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.Notifications ->
            if (notificationsRepository != null) {
                // The other moment the POST_NOTIFICATIONS ask is self-evident:
                // the member opened their notification inbox. Asks at most once
                // ever (shared gate with the chat hub); a denial leaves this
                // screen fully functional.
                RequestPushPermissionEffect()
                NotificationsRoute(
                    repository = notificationsRepository,
                    coordinator = notificationsCoordinator,
                    uid = uid,
                    onBack = onClose,
                    // Lets a friend-request row be accepted/declined in place.
                    // Null in a config-less build: the inbox then renders
                    // without friend actions.
                    friendsRepository = friendsRepository,
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

        // Retired as a sub-route: the garage now lives directly on the Garage
        // TAB. Two callers still produce `route = Garage` — the welcome flow's
        // "Add a car" CTA, and older persisted state (rememberSaveable) from a
        // build where this was a real route. Both are served by switching to the
        // tab, which lands on exactly the screen they wanted.
        ShellRoute.Garage -> {
            LaunchedEffect(Unit) { onOpenGarageTab() }
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
                    onOpenMemberProfile = { uid ->
                        // A member-search suggestion opens that member's profile,
                        // which is where every action on a person lives. Guarded
                        // identically to the friend-row path above.
                        if (memberProfileRepository != null) onOpenMemberProfile(uid)
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
                    // Optional, like the blocking repo: without it the profile
                    // simply carries no friend action (config-less build).
                    friendsRepository = friendsRepository,
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.Convoys ->
            if (convoyRepository != null) {
                ConvoyRoute(
                    repository = convoyRepository,
                    friendsRepository = friendsRepository,
                    // Deep-link into create-convoy when reached from the map "+"
                    // chooser's "Convoy" option (list-first from the Social hub).
                    openCreateOnEntry = convoyOpenCreate,
                    // Tapping a roster member on the convoy detail opens their
                    // read-only profile; the caller's own row never navigates.
                    onViewMember = openProfileIfWired,
                    viewerUid = uid,
                    onConvoyCreated = onConvoyCreated,
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
                // Mirror of the chat hub's registration: while this thread is on
                // screen, a push for THIS conversation is suppressed (the member
                // is watching the messages arrive). Cleared on dispose.
                DisposableEffect(dmChatOtherUid) {
                    val chat = ActiveChat.Dm(dmChatOtherUid)
                    ActiveChatRegistry.set(chat)
                    onDispose { ActiveChatRegistry.clear(chat) }
                }
                ChatRoute(
                    repository = dmRepository,
                    uid = uid,
                    otherUid = dmChatOtherUid,
                    otherName = dmChatOtherName,
                    // The thread title names the other member; tapping it opens
                    // their read-only profile.
                    onViewProfile = openProfileIfWired,
                )
            } else {
                LoadingScreen()
            }

        // The chat hub opened from the map chat bubble. Each tab's
        // repository is nullable (guarded per tab), so the hub renders even in a
        // config-less build; onClose returns to the map.
        ShellRoute.ChatHub ->
            ChatHubRoute(
                uid = uid,
                communityChatRepository = communityChatRepository,
                convoyChatRepository = convoyChatRepository,
                friendsRepository = friendsRepository,
                dmRepository = dmRepository,
                notificationsRepository = notificationsRepository,
                notificationsCoordinator = notificationsCoordinator,
                communityUnread = communityChatUnread,
                onClose = onClose,
                onViewProfile = openProfileIfWired,
                // Backs the block action on the hub's long-press message sheet.
                blockingRepository = blockingRepository,
                pushDeepLink = chatHubPushLink,
            )

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

        ShellRoute.SavedPlaces ->
            SavedPlacesScreen(
                store = savedPlacesStore,
                onAddPlace = onOpenAddressSearch,
                // Re-pointing a shortcut reuses the same picker, carrying WHICH
                // place is being changed so its save dialog pre-selects the right
                // kind (a re-pointed Home saves back as Home via
                // NavigationController.savePlace) rather than defaulting to a new
                // Favourite — and, for a favourite, sweeps the stale row.
                onChangeLocation = onChangeSavedPlaceAddress,
            )

        ShellRoute.Settings ->
            SettingsScreen(
                onManageSubscription =
                    if (billingRepository != null && subscriptionVerifier != null) {
                        { onOpenRoute(ShellRoute.Subscription) }
                    } else {
                        null
                    },
                onSavedPlaces = { onOpenRoute(ShellRoute.SavedPlaces) },
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

// The garage hub's main-car header avatar (and its mainCarImagePath derivation)
// went away with the hub: the Garage tab now opens straight onto the vehicle
// list, where every car — main or not — shows its own photo on its card.
