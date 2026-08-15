package com.kungsbackacarcommunity.app

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
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
import com.kungsbackacarcommunity.app.badges.BadgeProgressRepository
import com.kungsbackacarcommunity.app.badges.BadgesRepository
import com.kungsbackacarcommunity.app.badges.BadgesState
import com.kungsbackacarcommunity.app.blocking.BlockingRepository
import com.kungsbackacarcommunity.app.blocking.BlockingRoute
import com.kungsbackacarcommunity.app.diagnostics.CrashEvents
import com.kungsbackacarcommunity.app.diagnostics.CrashKeys
import com.kungsbackacarcommunity.app.diagnostics.CrashTelemetryText
import com.kungsbackacarcommunity.app.diagnostics.LogcatDriveRecordingLog
import com.kungsbackacarcommunity.app.diagnostics.NoopCrashTelemetry
import com.kungsbackacarcommunity.app.diagnostics.rememberClientErrorReporter
import com.kungsbackacarcommunity.app.diagnostics.rememberCrashTelemetry
import com.kungsbackacarcommunity.app.drives.DriveLocationController
import com.kungsbackacarcommunity.app.drives.DriveRecordingGate
import com.kungsbackacarcommunity.app.drives.DriveStatsCalculator
import com.kungsbackacarcommunity.app.drives.ConvoyEndChoice
import com.kungsbackacarcommunity.app.drives.ConvoyEndResolution
import com.kungsbackacarcommunity.app.drives.ConvoyEndSessionChoice
import com.kungsbackacarcommunity.app.drives.DrivesRepository
import com.kungsbackacarcommunity.app.drives.DrivesRoute
import com.kungsbackacarcommunity.app.drives.DrivesState
import com.kungsbackacarcommunity.app.drives.EndedSessionAction
import com.kungsbackacarcommunity.app.drives.RecordingState
import com.kungsbackacarcommunity.app.drives.RouteUploadRunner
import com.kungsbackacarcommunity.app.drives.SavePromptReason
import com.kungsbackacarcommunity.app.drives.DriveRecordingJournal
import com.kungsbackacarcommunity.app.drives.DriveSavedDialog
import com.kungsbackacarcommunity.app.drives.SessionSummaryDialog
import com.kungsbackacarcommunity.app.drives.SingleSessionRecording
import com.kungsbackacarcommunity.app.drives.savePromptReason
import com.kungsbackacarcommunity.app.billboards.Billboard
import com.kungsbackacarcommunity.app.billboards.BillboardCallToAction
import com.kungsbackacarcommunity.app.billboards.BillboardInteractionType
import com.kungsbackacarcommunity.app.billboards.BillboardMapPopup
import com.kungsbackacarcommunity.app.billboards.BillboardVisibility
import com.kungsbackacarcommunity.app.billboards.BillboardsRepository
import com.kungsbackacarcommunity.app.billboards.BillboardsRoute
import com.kungsbackacarcommunity.app.billboards.BillboardsState
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
import com.kungsbackacarcommunity.app.convoy.ConvoyExitChoice
import com.kungsbackacarcommunity.app.convoy.ConvoyInviteStatus
import com.kungsbackacarcommunity.app.convoy.ConvoyLeaveOutcome
import com.kungsbackacarcommunity.app.convoy.ConvoyStopAction
import com.kungsbackacarcommunity.app.convoy.LiveSessionConvoyStop
import com.kungsbackacarcommunity.app.convoy.LiveSessionStopPlan
import com.kungsbackacarcommunity.app.convoy.ConvoyInvitePickerScreen
import com.kungsbackacarcommunity.app.convoy.ConvoyListStatus
import com.kungsbackacarcommunity.app.convoy.ConvoyRepository
import com.kungsbackacarcommunity.app.convoy.ConvoyRoute
import com.kungsbackacarcommunity.app.convoy.ConvoyMapAwarenessOverlay
import com.kungsbackacarcommunity.app.convoy.ConvoyStatus
import com.kungsbackacarcommunity.app.convoy.ConvoyStatusBar
import com.kungsbackacarcommunity.app.convoy.InviteConvoyState
import com.kungsbackacarcommunity.app.convoy.invitableSelection
import com.kungsbackacarcommunity.app.convoy.messageRes
import com.kungsbackacarcommunity.app.convoy.UnavailableConvoyDestinationRepository
import com.kungsbackacarcommunity.app.crownhunt.ClaimCoordinate
import com.kungsbackacarcommunity.app.crownhunt.CrownClaimStatus
import com.kungsbackacarcommunity.app.crownhunt.CrownCollectGate
import com.kungsbackacarcommunity.app.crownhunt.CrownCollectSignalTracker
import com.kungsbackacarcommunity.app.crownhunt.CrownFix
import com.kungsbackacarcommunity.app.crownhunt.CrownFixTracker
import com.kungsbackacarcommunity.app.crownhunt.CrownHuntClaimStatus
import com.kungsbackacarcommunity.app.crownhunt.CrownHuntCoordinator
import com.kungsbackacarcommunity.app.crownhunt.CrownHuntPoint
import com.kungsbackacarcommunity.app.crownhunt.CrownHuntPointsState
import com.kungsbackacarcommunity.app.crownhunt.CrownHuntRepository
import com.kungsbackacarcommunity.app.crownhunt.CrownHuntRoute
import com.kungsbackacarcommunity.app.crownhunt.CrownLocation
import com.kungsbackacarcommunity.app.crownhunt.CrownMarkerStyle
import com.kungsbackacarcommunity.app.crownhunt.CrownPointMarkers
import com.kungsbackacarcommunity.app.crownhunt.CrownRange
import com.kungsbackacarcommunity.app.crownhunt.FirebaseCrownHuntStatsRepository
import com.kungsbackacarcommunity.app.crownhunt.CrownPointPopup
import com.kungsbackacarcommunity.app.crownhunt.CrownQueryCenter
import com.kungsbackacarcommunity.app.crownhunt.CrownSpawn
import com.kungsbackacarcommunity.app.crownhunt.CrownSpawnController
import com.kungsbackacarcommunity.app.crownhunt.CrownSpawnLimits
import com.kungsbackacarcommunity.app.crownhunt.CrownSpawnPopup
import com.kungsbackacarcommunity.app.crownhunt.CrownSpawnQuery
import com.kungsbackacarcommunity.app.crownhunt.LocalCrownHuntParticipationController
import com.kungsbackacarcommunity.app.crownhunt.crownGlyphRes
import com.kungsbackacarcommunity.app.crownhunt.crownPointGlyphRes
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
import com.kungsbackacarcommunity.app.notifications.ConvoyFacts
import com.kungsbackacarcommunity.app.notifications.ConvoyNotificationLink
import com.kungsbackacarcommunity.app.notifications.NotificationTapAction
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
import com.kungsbackacarcommunity.app.live.LiveCommandResult
import com.kungsbackacarcommunity.app.live.LiveLocation
import com.kungsbackacarcommunity.app.live.LiveLocationCoordinator
import com.kungsbackacarcommunity.app.live.LiveLocationRepository
import com.kungsbackacarcommunity.app.live.LiveLocationScreen
import com.kungsbackacarcommunity.app.live.LiveMarker
import com.kungsbackacarcommunity.app.live.LiveSessionDuration
import com.kungsbackacarcommunity.app.live.LiveSessionLoad
import com.kungsbackacarcommunity.app.live.LiveSessionRecordingLifecycle
import com.kungsbackacarcommunity.app.live.defaultStartDrivingVehicleId
import com.kungsbackacarcommunity.app.live.LiveShareStart
import com.kungsbackacarcommunity.app.live.LiveShareStop
import com.kungsbackacarcommunity.app.live.LiveStartAttempt
import com.kungsbackacarcommunity.app.live.OptimisticLiveStop
import com.kungsbackacarcommunity.app.live.LiveMapLayers
import com.kungsbackacarcommunity.app.live.NearbyLiveController
import com.kungsbackacarcommunity.app.live.NearbyLiveOverlay
import com.kungsbackacarcommunity.app.live.NearbyLiveSession
import com.kungsbackacarcommunity.app.live.OptimisticLiveStart
import com.kungsbackacarcommunity.app.location.BackgroundLocationController
import com.kungsbackacarcommunity.app.location.DriveBatteryOptimizationPrompt
import com.kungsbackacarcommunity.app.location.CurrentSpeed
import com.kungsbackacarcommunity.app.location.GeoLinks
import com.kungsbackacarcommunity.app.location.LocationAccess
import com.kungsbackacarcommunity.app.location.LocationAccessPrompt
import com.kungsbackacarcommunity.app.location.LocationShare
import com.kungsbackacarcommunity.app.location.ShareableLocation
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
import com.kungsbackacarcommunity.app.navigation.ActiveNavigation
import com.kungsbackacarcommunity.app.navigation.CurrentLocation
import com.kungsbackacarcommunity.app.navigation.ExternalNavigation
import com.kungsbackacarcommunity.app.navigation.HttpMapboxSearchClient
import com.kungsbackacarcommunity.app.navigation.LatLng
import com.kungsbackacarcommunity.app.navigation.MapLinkNavigator
import com.kungsbackacarcommunity.app.navigation.NavResumePolicy
import com.kungsbackacarcommunity.app.navigation.NavResumeStore
import com.kungsbackacarcommunity.app.navigation.NavigationSearchScreen
import com.kungsbackacarcommunity.app.navigation.PlaceSuggestion
import com.kungsbackacarcommunity.app.navigation.PrefsRecentSearchesStore
import com.kungsbackacarcommunity.app.navigation.PrefsSavedPlacesStore
import com.kungsbackacarcommunity.app.navigation.SavedPlace
import com.kungsbackacarcommunity.app.navigation.SavedPlaceEdit
import com.kungsbackacarcommunity.app.navigation.SavedPlaceKind
import com.kungsbackacarcommunity.app.navigation.SavedPlaces
import com.kungsbackacarcommunity.app.navigation.SavedPlacesScreen
import com.kungsbackacarcommunity.app.navigation.SavedPlacesStore
import com.kungsbackacarcommunity.app.navigation.turnbyturn.TurnByTurnNavScreen
import com.kungsbackacarcommunity.app.onboarding.OnboardingCoordinator
import com.kungsbackacarcommunity.app.onboarding.OnboardingScreen
import com.kungsbackacarcommunity.app.onboarding.OnboardingStatus
import com.kungsbackacarcommunity.app.profile.AuthedDestination
import com.kungsbackacarcommunity.app.profile.FirebaseLiveProfileRepository
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
import com.kungsbackacarcommunity.app.shell.CompassModePreferenceStore
import com.kungsbackacarcommunity.app.shell.MapLayersPreferenceStore
import com.kungsbackacarcommunity.app.shell.MapCompassMode
import com.kungsbackacarcommunity.app.shell.MapMode
import com.kungsbackacarcommunity.app.shell.HubScreen
import com.kungsbackacarcommunity.app.shell.sortedHubEntriesByLabel
import com.kungsbackacarcommunity.app.shell.SettingsScreen
import com.kungsbackacarcommunity.app.shell.LiveSessionAnchor
import com.kungsbackacarcommunity.app.shell.LiveSessionBar
import com.kungsbackacarcommunity.app.shell.LiveSessionElapsed
import com.kungsbackacarcommunity.app.shell.LiveShareAction
import com.kungsbackacarcommunity.app.shell.LiveSharePopup
import com.kungsbackacarcommunity.app.shell.LiveShareToggle
import com.kungsbackacarcommunity.app.incidents.CameraRequeryDecision
import com.kungsbackacarcommunity.app.incidents.ClearOutcome
import com.kungsbackacarcommunity.app.incidents.Incident
import com.kungsbackacarcommunity.app.incidents.IncidentAgeFilter
import com.kungsbackacarcommunity.app.incidents.IncidentClearRejection
import com.kungsbackacarcommunity.app.incidents.IncidentDetails
import com.kungsbackacarcommunity.app.incidents.IncidentDetailsSheet
import com.kungsbackacarcommunity.app.incidents.IncidentMarkerStyle
import com.kungsbackacarcommunity.app.incidents.IncidentPoint
import com.kungsbackacarcommunity.app.incidents.ConfirmOutcome
import com.kungsbackacarcommunity.app.incidents.IncidentPalette
import com.kungsbackacarcommunity.app.incidents.IncidentReportController
import com.kungsbackacarcommunity.app.incidents.IncidentType
import com.kungsbackacarcommunity.app.incidents.LocalIncidentAgeFilterController
import com.kungsbackacarcommunity.app.incidents.QueryAnchor
import com.kungsbackacarcommunity.app.incidents.ReportLocation
import com.kungsbackacarcommunity.app.incidents.ReportOutcome
import com.kungsbackacarcommunity.app.incidents.hasTrafikverketData
import com.kungsbackacarcommunity.app.incidents.incidentGlyphRes
import com.kungsbackacarcommunity.app.shell.EventMarkerInfoPopup
import com.kungsbackacarcommunity.app.shell.MapHome
import com.kungsbackacarcommunity.app.shell.LocalAeroBackAvailable
import com.kungsbackacarcommunity.app.shell.MapCrownMarker
import com.kungsbackacarcommunity.app.shell.MapBillboardMarker
import com.kungsbackacarcommunity.app.shell.MapEventMarker
import com.kungsbackacarcommunity.app.shell.MapIncidentMarker
import com.kungsbackacarcommunity.app.shell.MapPlaceRequest
import com.kungsbackacarcommunity.app.shell.MapPoint
import com.kungsbackacarcommunity.app.shell.MapProjection
import com.kungsbackacarcommunity.app.shell.MapQueryViewport
import com.kungsbackacarcommunity.app.shell.MapSurface
import com.kungsbackacarcommunity.app.shell.PlaceActionsSheet
import com.kungsbackacarcommunity.app.shell.SaveLocationDialog
import com.kungsbackacarcommunity.app.shell.SavedPlacesPickerSheet
import com.kungsbackacarcommunity.app.shell.ShareLocationSheet
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
import com.kungsbackacarcommunity.app.shell.currentIncidentClearFix
import com.kungsbackacarcommunity.app.shell.runIncidentClearVote
import com.kungsbackacarcommunity.app.shell.runIncidentConfirmation
import com.kungsbackacarcommunity.app.shell.runIncidentRemoval
import com.kungsbackacarcommunity.app.subscription.BillingRepository
import com.kungsbackacarcommunity.app.subscription.SubscriptionRoute
import com.kungsbackacarcommunity.app.subscription.SubscriptionVerifier
import com.kungsbackacarcommunity.app.update.AppStartupUpdateGate
import com.kungsbackacarcommunity.app.update.AppUpdateCheck
import com.kungsbackacarcommunity.app.update.AppUpdateSource
import com.kungsbackacarcommunity.app.update.AppUpdateDecision
import com.kungsbackacarcommunity.app.update.AppUpdateDialog
import com.kungsbackacarcommunity.app.update.ForcedUpdateGate
import com.kungsbackacarcommunity.app.update.AppUpdateDismissalStore
import com.kungsbackacarcommunity.app.update.AppUpdateFlowOutcome
import com.kungsbackacarcommunity.app.update.AppUpdateFlowResult
import com.kungsbackacarcommunity.app.update.PlayStoreLink
import com.kungsbackacarcommunity.app.update.rememberAppStartupUpdateGate
import com.kungsbackacarcommunity.app.update.rememberPlayAppUpdateSource
import com.kungsbackacarcommunity.app.welcome.WelcomeScreen
import com.kungsbackacarcommunity.app.welcome.WelcomeStore
import com.kungsbackacarcommunity.app.whatsnew.Changelog
import com.kungsbackacarcommunity.app.whatsnew.ChangelogLoader
import com.kungsbackacarcommunity.app.whatsnew.UpdateAnnouncement
import com.kungsbackacarcommunity.app.whatsnew.WhatsNewDialog
import com.kungsbackacarcommunity.app.whatsnew.WhatsNewRoute
import com.kungsbackacarcommunity.app.whatsnew.WhatsNewStore
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.CancellationException
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
 * How often the dwell tracker is PRE-WARMED with a fresh fix while the member is
 * near a collectable crown but has not opened one yet.
 *
 * A touch SLOWER than the open-popup claim cadence ([CROWN_FIX_INTERVAL_MS], 2 s):
 * pre-warming is not racing a countdown, it only has to land two fixes past
 * [com.kungsbackacarcommunity.app.crownhunt.CrownSpawnLimits.MIN_DWELL_SECONDS]
 * apart before the member taps, so the Collect button is live the moment the
 * popup opens instead of after the old "tap, tap, then it works" wait. The loop
 * STOPS the instant a proof partner has aged in (see the pre-warm effect), so it
 * costs a couple of fixes, not a sustained poll. Deliberately gated to "in range
 * of a collectable crown, popup closed" and run at balanced power, so a parked
 * phone showing crowns far away pays nothing.
 */
private const val CROWN_PREWARM_FIX_INTERVAL_MS = 3_000L

/**
 * How often the crown MAP layer refreshes the member's coarse location to decide
 * which crowns are within collect range (coloured) vs out of range (greyed).
 *
 * Ten seconds — far slower than the 2 s claim-fix cadence, because a marker only
 * needs to change colour when the member crosses a ~75 m ring, which walking or
 * driving takes seconds to do; a faster poll would spend battery to move a colour
 * boundary no one is watching that closely. The in-range SET is diffed, so a poll
 * that does not cross any ring rebuilds no markers at all.
 */
private const val CROWN_RANGE_LOCATION_INTERVAL_MS = 10_000L

/**
 * Chooses the (current, previous) fixes that drive the crown popup from [tracker]
 * at wall-clock [nowMillis].
 *
 * Prefers a valid dwell PAIR ([CrownFixTracker.proofPair]) so Collect goes live
 * whenever a claim is actually possible — never stranded in "confirming" while a
 * usable pair sits in the buffer. When no pair is achievable yet it still returns
 * a fresh best-accuracy current for the distance line, with a null partner so the
 * gate honestly shows the confirming state.
 */
private fun applyCrownFix(
    tracker: CrownFixTracker,
    nowMillis: Long,
): Pair<CrownFix?, CrownFix?> {
    val pair = tracker.proofPair(nowMillis)
    return if (pair != null) {
        pair.current to pair.previous
    } else {
        tracker.bestRecent(nowMillis) to null
    }
}

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
 * Carries the turn-by-turn destination through activity recreation (rotation,
 * theme change, or a reclaim-and-rebuild while backgrounded), so a stray
 * interruption no longer ends navigation. Saved as [longitude, latitude] — the
 * two Bundle-safe doubles a [LatLng] is — and restored only from a well-formed
 * pair, so a corrupt bundle degrades to "no destination" rather than crashing.
 */
private val navLatLngSaver: Saver<LatLng?, DoubleArray> =
    Saver(
        save = { it?.let { p -> doubleArrayOf(p.longitude, p.latitude) } },
        restore = { arr ->
            if (arr.size == 2) LatLng(longitude = arr[0], latitude = arr[1]) else null
        },
    )

/**
 * The signed-in experience: observes the profile document to gate onboarding,
 * then renders the **map-first, 5-tab shell** ([mapFirstShell]) once onboarded.
 *
 * Integration layer — the routing decision ([authedDestination]) and every
 * screen it shows are independently unit/UI-tested; this composable only wires
 * repositories to those pieces. Repositories are nullable so the no-Firebase
 * (Unavailable) build still renders the main shell.
 */
/**
 * A pending "end live session while a convoy is active" prompt. Frozen at the
 * moment Stop is tapped so a background convoy refresh (roster/leadership change)
 * cannot re-point which convoy or which exits the open dialog acts on.
 */
private data class ConvoyStopPromptState(
    val convoyId: String,
    val exitChoice: ConvoyExitChoice,
)

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
    badgeProgressRepository: BadgeProgressRepository?,
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
    // Google Play In-App Updates source for the startup gate below, and reused
    // by the in-shell flexible prompt further down (one AppUpdateManager, not
    // two). Injectable ONLY so a UI test can force a chosen verdict; production
    // always takes the Play-backed default. Constructing it touches no app
    // wire-data, so evaluating it ahead of the gate is safe.
    appUpdateSource: AppUpdateSource? = rememberPlayAppUpdateSource(),
) {
    // THE GATE RUNS FIRST — ahead of every backend-dependent startup effect in
    // this composable (the push/login LaunchedEffects, the profile snapshot
    // listener, and the whole Main shell below). That ordering is the fix, not a
    // detail: an outdated build that a backend contract has moved out from under
    // can throw inside one of those effects before any overlay would get a frame,
    // so the "please update" verdict has to gate whether the shell composes AT
    // ALL, not race it. See AppUpdateGate.
    //
    //  - FORCED  -> render the blocking update screen and nothing else; the early
    //    return guarantees no shell wiring below ever composes on this launch.
    //  - CHECKING -> a bare loading screen (no listeners, no callables) until Play
    //    answers; bounded by AppStartupUpdate.CHECK_TIMEOUT_MILLIS and skipped
    //    outright on a non-Play install, so an up-to-date member is not made to
    //    wait.
    //  - CLEAR (the default, and every failure/timeout) -> the shell composes
    //    exactly as before.
    val startupUpdateGate by rememberAppStartupUpdateGate(appUpdateSource)
    if (startupUpdateGate == AppStartupUpdateGate.FORCED) {
        ForcedUpdateGate(source = appUpdateSource)
        return
    }
    if (startupUpdateGate == AppStartupUpdateGate.CHECKING) {
        LoadingScreen()
        return
    }

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
                onSubmit = { name, partnerStatsOptIn ->
                    onboardingCoordinator?.let { c ->
                        scope.launch { c.submit(name, partnerStatsOptIn) }
                    }
                },
                // Anonymised partner statistics are default-on / opt-out; only
                // surface the onboarding step when the feature is enabled so it
                // stays consistent with the Settings entry's gate.
                partnerStatsEnabled =
                    FeatureGate.isAvailable(
                        flags = flags,
                        flag = FeatureFlag.PARTNER_STATS,
                        memberGated = false,
                        isActiveMember = false,
                    ),
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
            // A drive to open in History straight away — set by the "History" action
            // on the auto-keep "Drive saved" dialog (#856), which also switches to the
            // History tab; DrivesRoute pre-selects it and clears it back to null.
            var pendingDriveDetailRideId by rememberSaveable { mutableStateOf<String?>(null) }
            // Drives the auto-keep "Drive saved" confirmation dialog (#856): the drive
            // is already saved in the background, so this is a purely informational
            // window with OK (dismiss) and History (open Drives/History) — NOT a
            // Keep/Delete decision. `visible` is SET once the drive is saved; the id
            // (if any) is which drive the History action deep-links to.
            var driveSavedDialogVisible by rememberSaveable { mutableStateOf(false) }
            var driveSavedDialogRideId by rememberSaveable { mutableStateOf<String?>(null) }
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

            // Device-local persistence for the map-layers popup toggles (traffic
            // alerts, Mapbox congestion overlay, night-mode override, 3D buildings).
            // Seeds the hoisted states / the surface below on start-up and records
            // every toggle, so a user's map layers survive a cold restart instead of
            // snapping back to defaults. Mirrors compassModeStore just below.
            val mapLayersContext = LocalContext.current
            val mapLayersStore =
                remember(mapLayersContext) { MapLayersPreferenceStore(mapLayersContext) }

            // The map's manual day/night override (null = follow the app theme).
            //
            // Owned HERE, not inside MapHome, precisely because [route] above can
            // unmount MapHome: opening any full-screen route swaps the shell's
            // `else` branch away, disposing MapHome and — when this state lived
            // there — silently discarding the user's Day choice, so returning to
            // the map snapped it back to Night on a dark-themed device. This
            // scope survives every route change, so the override sticks for the
            // session. rememberSaveable additionally carries it across rotation,
            // and — seeded from [mapLayersStore] and written back below — across a
            // cold restart / process kill too (matching the compass preference).
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
                    // Seed from disk: the stored override, or null (follow the app
                    // theme) when unset. rememberSaveable restores its own bundle
                    // value across system-initiated process death; on a genuine cold
                    // start (bundle gone) this lambda runs and reads the durable store.
                ) { mutableStateOf(mapLayersStore.readNightMode()) }

            // Persist any day/night change durably so it survives a cold restart.
            // The first run writes the seed back (an idempotent same-value no-op),
            // and every toggle records the user's new pick (null clears it back to
            // "follow the app theme").
            LaunchedEffect(mapNightModeOverride.value) {
                mapLayersStore.writeNightMode(mapNightModeOverride.value)
            }

            // The map's compass orientation (course-up default vs north-up).
            //
            // Owned HERE for the SAME reason mapNightModeOverride is: opening a
            // full-screen route disposes MapHome, so holding this only inside it
            // would drop the user's pick on the way back. Unlike the day/night
            // override — which is a session preference kept alive only by
            // rememberSaveable — the compass choice is DURABLY persisted:
            // [CompassModePreferenceStore] (device-local SharedPreferences) seeds
            // the state on start-up and records every change, so a user who picks
            // north-up keeps it across a cold restart, while a user who has never
            // chosen gets the first-run default (course-up, MapCompassMode.DEFAULT).
            val compassModeContext = LocalContext.current
            val compassModeStore =
                remember(compassModeContext) { CompassModePreferenceStore(compassModeContext) }
            val mapCompassMode =
                rememberSaveable(
                    stateSaver = Saver(
                        save = { it.name },
                        // Crash-safe restore, matching the day/night saver above:
                        // an unknown constant falls back to the default rather than
                        // throwing the way valueOf would.
                        restore = { saved -> MapCompassMode.fromStoredName(saved as? String) },
                    ),
                    // Seed from disk: the stored pick, or course-up when unset.
                    // rememberSaveable restores its own bundle value across process
                    // death; on a genuine cold start (bundle gone) this lambda runs
                    // and reads the durable store instead.
                ) { mutableStateOf(compassModeStore.read()) }

            // Persist any change durably so it survives a cold restart / process
            // kill. The first run writes the seed back (an idempotent same-value
            // write), and every toggle records the user's new pick.
            LaunchedEffect(mapCompassMode.value) {
                compassModeStore.write(mapCompassMode.value)
            }

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

            // Crash-report context: THE single navigation hook for Crashlytics.
            //
            // `selectedTab` + `route` are the whole of "which screen is the user
            // on" in this shell, and both live here, so one effect keyed on the
            // pair covers every tab switch, sub-route open, push deep-link and
            // Back press — including the ones that never touch a callback. That
            // is deliberately the ONLY navigation breadcrumb: the log is bounded
            // (~64 entries) and a per-interaction crumb would evict the entries
            // that actually explain a crash.
            //
            // PII: both values are enum NAMES from the shell's fixed route
            // vocabulary (ShellTab / ShellRoute). No uid, no target member, no
            // conversation id — ShellRoute.Chat says "a DM thread was open", and
            // that is all it is allowed to say.
            val crashTelemetry = rememberCrashTelemetry()
            LaunchedEffect(crashTelemetry, selectedTab, route) {
                crashTelemetry?.run {
                    setKey(CrashKeys.SHELL_TAB, selectedTab.name)
                    setKey(CrashKeys.SHELL_ROUTE, route?.name ?: CrashKeys.NONE)
                    log(CrashEvents.NAV, CrashTelemetryText.navDetail(selectedTab.name, route?.name))
                }
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

            // Where the hub should LAND when it is opened by something that names a
            // destination — today only the convoy bar's chat icon, which opens the
            // hub straight onto its convoy's channel instead of the default
            // Community tab. Null for the plain chat bubble.
            //
            // Deliberately NOT rememberSaveable, unlike `chatHubOpen`: it is a
            // one-shot intent for THIS open, not a preference worth restoring. If
            // process death re-opens a saved hub, landing on Community is the right
            // default — it is the one destination that is never wrong.
            var chatHubLandingLink by remember { mutableStateOf<PushDeepLink?>(null) }
            // Cleared on every CLOSE, keyed on the flag itself so no close path can
            // forget: leaving it set would re-land the next plain chat-bubble tap on
            // a convoy the member did not ask for. Setting the link and opening the
            // hub happen in the same frame, so this never races the open.
            LaunchedEffect(chatHubOpen) {
                if (!chatHubOpen) chatHubLandingLink = null
            }

            // Set true immediately before opening ShellRoute.Convoys from the
            // chooser's "Convoy" option so the convoy route deep-links straight
            // into its create-convoy sub-screen. Reset to false when the Social
            // hub opens Convoys the normal way (list first). ConvoyRoute consumes
            // it one-shot, so it never re-forces Create after a back-out.
            var convoyOpenCreate by rememberSaveable { mutableStateOf(false) }

            // The car the owner picked in the "Start driving" popup before choosing
            // Convoy, carried into the create-convoy flow so the owner's auto-started
            // convoy session denormalizes the SAME car as a Single session would.
            // Consumed one-shot by the convoy create call (cleared once handed over);
            // null → the server falls back to the owner's main car.
            var pendingConvoyVehicleId by rememberSaveable { mutableStateOf<String?>(null) }

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
            // bubble and the convoy bar's chat icon are not counter-examples —
            // they open ChatHubPopup, whose landing link is the separate,
            // cleared-on-close `chatHubLandingLink` above. Back-out is likewise safe:
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
            // Convoy id from a convoy-invite notification tap — the in-app inbox
            // row, and (once the backend sends it) the push. Consumed by
            // ConvoyRoute on entry, which clears it, so a later plain visit to
            // the convoy list cannot re-raise a notice about an old invite.
            var pendingConvoyInviteId by remember { mutableStateOf<String?>(null) }
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
                    PushTarget.CONVOYS -> {
                        // BACKEND GAP: buildPushDeepLink in
                        // functions/src/notifications/notifications-core.ts maps
                        // 'convoy_invite' to { target: 'convoys', entityId: null }
                        // — it DROPS the convoy id it was handed, even though the
                        // inbox item stores it as relatedEntityId. So this is null
                        // today and the tap lands on the convoy list, which is at
                        // least where the invite is answered. Threaded anyway, so
                        // the day the backend stops dropping the id the push lands
                        // on the exact invite with no client change.
                        pendingConvoyInviteId = link.entityId
                        openRootRoute(ShellRoute.Convoys)
                    }
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
                remember(liveLocationRepository, crashTelemetry) {
                    liveLocationRepository?.let {
                        NearbyLiveController(it, crashTelemetry ?: NoopCrashTelemetry)
                    }
                }
            val incidentsFlow =
                remember(incidentController) {
                    incidentController?.nearbyIncidents ?: MutableStateFlow(emptyList<Incident>())
                }
            val nearbyIncidents by incidentsFlow.collectAsState()
            // The user's Trafikverket alert max-age filter (device-local, ambient).
            // A pure CLIENT-SIDE display filter — it changes nothing in Firestore or
            // the sync; it only decides which of the already-loaded incidents get
            // drawn. Reading it here (the same value the layers-popup slider writes)
            // means changing the setting recomposes and re-filters the markers live.
            val incidentMaxAge = LocalIncidentAgeFilterController.current.maxAge
            // Trafikverket alerts older than the chosen age are dropped BEFORE
            // markers/attribution are built, so the age-out is invisible downstream.
            // Member reports and alerts with no usable posted-time are never dropped
            // (see IncidentAgeFilter). `now` is snapped when this recomputes, i.e.
            // whenever the setting changes OR the incident list does (each live poll
            // replaces it) — it does NOT advance on its own while both sit still. So
            // an alert can cross the boundary a little before the next list change
            // re-evaluates it. That imprecision is immaterial at these thresholds
            // (6 h … 30 days): deliberately no ticking-clock recomposition is wired
            // just to age a display filter out to the second.
            val visibleIncidents =
                remember(nearbyIncidents, incidentMaxAge) {
                    IncidentAgeFilter.visible(
                        nearbyIncidents,
                        System.currentTimeMillis(),
                        incidentMaxAge,
                    )
                }
            val incidentMarkers =
                remember(visibleIncidents) {
                    visibleIncidents.map { incident ->
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
                            // Both the disc and the glyph tint follow the
                            // reported-gone state: a marker somebody has voted
                            // gone is washed out and struck through, and the
                            // glyph colour has to be re-picked to stay readable
                            // on the pale disc. Resolved through the one pure
                            // style object so the map marker, the sheet's badge
                            // and the tests cannot compute the fade three
                            // different ways.
                            colorArgb =
                                IncidentMarkerStyle.discColorArgb(
                                    incident.type,
                                    incident.reportedCleared,
                                ),
                            iconRes = incidentGlyphRes(incident.type),
                            glyphColorArgb =
                                IncidentMarkerStyle.glyphColorArgb(
                                    incident.type,
                                    incident.reportedCleared,
                                ),
                            reportedCleared = incident.reportedCleared,
                        )
                    }
                }
            // Whether the VISIBLE incidents include any Trafikverket-imported row,
            // i.e. whether their open data is actually on screen. Gates the
            // "Källa: Trafikverket" credit in the layers popup — computed off the
            // age-filtered list so the credit disappears when the filter hides every
            // Trafikverket alert (nothing of theirs is on screen to attribute).
            val trafikverketDataShown =
                remember(visibleIncidents) { hasTrafikverketData(visibleIncidents) }
            // Visibility of the "Traffic alerts" layer (Trafikverket + crowd-sourced
            // incidents) toggled from the map-layers popup. Defaults ON (the shared
            // road-info layer is visible to all users); seeded from [mapLayersStore]
            // and written back below so the choice survives rotation AND a cold
            // restart / process kill, not just system-initiated recreation. Gating
            // the fetch below on this flag means a user who turns the layer off stops
            // polling, and turning it back on re-fetches immediately.
            var incidentsLayerEnabled by
                rememberSaveable { mutableStateOf(mapLayersStore.readIncidents()) }
            // Persist the traffic-alerts layer choice durably (no-op on the first
            // same-value write; records every genuine toggle).
            LaunchedEffect(incidentsLayerEnabled) {
                mapLayersStore.writeIncidents(incidentsLayerEnabled)
            }

            // The Mapbox congestion overlay and the 3D-buildings camera live in the
            // surface (their StateFlows are the single source of truth), so unlike
            // the two hoisted states above they need seeding INTO the surface and
            // observing back OUT of it. On start-up — and again on a Stub -> real
            // Mapbox surface swap, which resets a fresh surface to its field defaults
            // (traffic off, 3D on) — push the stored choice into the surface, then
            // collect each flow to record every subsequent toggle. Seeding writes the
            // stored value straight back through the collectors, which the store's
            // no-op-on-unchanged guard swallows, so a launch that changes nothing
            // touches no disk. Keyed on mapSurface so the collectors follow the swap.
            LaunchedEffect(mapSurface) {
                mapSurface.setTrafficEnabled(mapLayersStore.readTrafficCongestion())
                mapSurface.set3dEnabled(mapLayersStore.read3d())
                launch {
                    mapSurface.trafficEnabled.collect { mapLayersStore.writeTrafficCongestion(it) }
                }
                launch {
                    mapSurface.is3d.collect { mapLayersStore.write3d(it) }
                }
            }
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
            // ---- Sponsored billboards layer ---------------------------------
            // Billboards are admin-managed and appear in exactly ONE place in
            // the app: as markers on this map. There is no member-facing menu
            // entry, no layer toggle and no list screen in the navigation —
            // whether a billboard exists for a member to see at all is an admin
            // decision, enforced by the read rule on `billboards`, not by
            // anything here.
            val billboardsEnabled = flags.isEnabled(FeatureFlag.DIGITAL_BILLBOARDS)
            // ONE bounded snapshot listener for the whole signed-in session,
            // deliberately NOT keyed on the selected tab and NOT re-issued for a
            // camera move. See FirebaseBillboardsRepository for the cost
            // reasoning: billboards are a few dozen slow-moving, human-curated
            // records, so a viewport query would re-read them on every settled
            // pan and buy nothing; keying on the tab would re-read the whole set
            // on every tab switch. With the flag off no listener is attached at
            // all — "off" costs nothing rather than costing a hidden layer's
            // worth of reads.
            val billboardsStateFlow =
                remember(billboardsRepository, billboardsEnabled) {
                    if (billboardsRepository != null && billboardsEnabled) {
                        billboardsRepository.observeActiveBillboards()
                    } else {
                        flowOf(BillboardsState.Loaded(emptyList()))
                    }
                }
            val billboardsState by
                billboardsStateFlow.collectAsState(initial = BillboardsState.Loading)
            val loadedBillboards =
                (billboardsState as? BillboardsState.Loaded)?.billboards ?: emptyList()
            // The window re-check. The query and the rule already exclude
            // anything the server does not currently call map-visible, but the
            // sweep that maintains that flag runs on a ten-minute cadence, so
            // there is a bounded interval in which an expired billboard is still
            // flagged visible and still sitting in this open listener. Rather
            // than re-filter on a timer, filter now and then sleep exactly until
            // the next boundary any loaded billboard has — the same one
            // scheduled wake-up shape the event pins use for their "not past"
            // cutoff. Falls out of the loop once nothing loaded is time-limited.
            val visibleBillboards by
                produceState(initialValue = emptyList<Billboard>(), loadedBillboards) {
                    while (true) {
                        val now = nowMillis()
                        value = BillboardVisibility.visibleAt(loadedBillboards, now)
                        val next =
                            BillboardVisibility.nextBoundaryMillis(loadedBillboards, now) ?: break
                        delay((next - now + 1L).coerceAtLeast(1L))
                    }
                }
            val mapBillboardMarkers =
                remember(visibleBillboards) {
                    visibleBillboards.map { billboard ->
                        MapBillboardMarker(
                            id = billboard.id,
                            longitude = billboard.longitude,
                            latitude = billboard.latitude,
                        )
                    }
                }
            // The billboard the user TAPPED, resolved back from the id the
            // surface published. Derived (not snapshotted) so a billboard that
            // is paused, or whose window closes, while its popup is open CLOSES
            // that popup rather than leaving an advert on screen that the member
            // is no longer meant to be shown.
            val tappedBillboardId by mapSurface.billboardTap.collectAsState()
            val tappedBillboard =
                remember(tappedBillboardId, visibleBillboards) {
                    tappedBillboardId?.let { id -> visibleBillboards.firstOrNull { it.id == id } }
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
            // Same one-call-per-press guard for the "Nej, den är borta" clear vote.
            var incidentClearInFlight by
                remember(tappedIncidentId) { mutableStateOf(false) }
            // The viewer's own position, read ONCE per opened sheet, purely to
            // decide whether to OFFER the clear vote. It is not the position the
            // vote is made from — that is a fresh high-accuracy fix taken at the
            // moment of the tap (see the onReportCleared wiring) — and it is not a
            // security control either: the server re-computes the distance and
            // rejects the vote itself. This only spares a member 30 km away a
            // round-trip to be told what we could already tell them.
            val incidentViewerLocation by
                produceState<IncidentPoint?>(initialValue = null, key1 = tappedIncidentId) {
                    value =
                        if (tappedIncidentId == null) {
                            null
                        } else {
                            CurrentLocation
                                .lastKnown(context.applicationContext)
                                ?.let {
                                    IncidentPoint(
                                        latitude = it.latitude,
                                        longitude = it.longitude,
                                    )
                                }
                        }
                }
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

            // The user's cars for the "Start driving" popup's round-photo picker.
            // Observed ONLY while the chooser is open (one short-lived listener),
            // separate from the Garage-tab stream above so opening the chooser from
            // any tab shows the cars without navigating to the Garage. Degrades to a
            // constant Loading (→ empty list) when closed or unwired.
            val createChooserGarageState by
                remember(garageRepository, uid, showCreateChooser) {
                    if (garageRepository != null && showCreateChooser) {
                        garageRepository.observeGarage(uid)
                    } else {
                        flowOf(GarageState.Loading)
                    }
                }
                    .collectAsState(initial = GarageState.Loading)
            val createChooserVehicles =
                (createChooserGarageState as? GarageState.Loaded)?.vehicles ?: emptyList()
            // The car the user tapped in the picker; null means "not yet chosen",
            // which resolves to the default (main car → first car → none) so the
            // preselection matches the server fallback. Reset when the chooser
            // closes so the next open starts from the default again.
            var startDrivingCarId by remember { mutableStateOf<String?>(null) }
            val effectiveStartDrivingCarId =
                startDrivingCarId ?: defaultStartDrivingVehicleId(createChooserVehicles)

            // Resolved in composition (lint: resource lookups must not use
            // LocalContext.current) so the click lambdas can show them.
            val comingSoonText = stringResource(R.string.shell_comingSoon)
            val unavailableText = stringResource(R.string.shell_unavailable)
            // Shown when STARTING a live session fails (the callable errored, or
            // never answered) — the optimistic STOP sign is taken back at the same
            // moment, so the user is told why the control returned to "+".
            val liveErrorText = stringResource(R.string.liveLocation_error)
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
            val incidentClearedSuccessText = stringResource(R.string.incidents_clearedSuccess)
            val incidentClearedRemovedText = stringResource(R.string.incidents_clearedRemoved)
            val incidentClearedAlreadyText = stringResource(R.string.incidents_clearedAlready)
            val incidentClearedErrorText = stringResource(R.string.incidents_clearedError)
            val incidentClearedTooFarText = stringResource(R.string.incidents_clearedTooFar)
            val incidentClearedNoLocationText =
                stringResource(R.string.incidents_clearedNoLocation)
            val incidentClearedImportedText =
                stringResource(R.string.incidents_clearedImportedExplanation)

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
            // Where the TURN-BY-TURN map is looking, while it exists.
            //
            // The poll below is anchored to the shell camera, and the shell map is
            // stood down the instant navigation starts (see the setActive effect):
            // its camera then stops moving, so a drive that starts in Kungsbacka
            // goes on asking about Kungsbacka all the way to Göteborg and the
            // incident layer ahead of the driver is never fetched. That is the
            // other half of "while navigating I don't see Trafikverket's
            // accidents" — the first half being that nothing DREW them there.
            //
            // A plain MutableStateFlow rather than Compose state on purpose: it is
            // read only from the poll's provider lambdas, never during
            // composition, so the navigation map reporting a new viewport must not
            // (and does not) recompose anything.
            //
            // It changes WHERE the poll looks and nothing else. No extra pass, no
            // shorter interval, no second caller: `incidents.listNearby` is
            // rate-limited per user server-side, so the cadence stays exactly the
            // 15 s keep-alive plus the camera-idle pulses below — and those pulses
            // come from the SHELL camera, which is frozen while navigating, so
            // driving adds no pulses at all.
            val navQueryViewport = remember { MutableStateFlow<MapQueryViewport?>(null) }
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
                    // The NAVIGATION map's viewport wins while there is one — it
                    // is the map the user is actually looking at, and the shell
                    // camera behind it is frozen.
                    radiusProvider = {
                        navQueryViewport.value?.radiusMeters
                            ?: mapSurface.visibleRadiusMeters()
                    },
                    centerProvider = {
                        navQueryViewport.value?.let { viewport ->
                            LatLng(
                                longitude = viewport.longitude,
                                latitude = viewport.latitude,
                            )
                        } ?: mapSurface.cameraSnapshot.value?.let { snapshot ->
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
            // The member's own opt-in. Default is participating (unset → true);
            // when they opt OUT the whole game comes off THIS screen — every crown
            // marker (admin points AND spawns) and every crown popup — without
            // touching the backend. Read from the CompositionLocal the activity
            // provides, which re-provides a fresh controller on change, so flipping
            // the toggle recomposes this and shows/hides live.
            val crownHuntParticipating = LocalCrownHuntParticipationController.current.participating
            val crownHuntFeatureEnabled = flags.isEnabled(FeatureFlag.CROWN_HUNT)
            // Curated admin points (`crownHuntPoints`) need only the feature flag,
            // NOT the spawn flag: each carries an admin's own safe-location
            // confirmation, so it is safe to draw even with the auto-spawn engine
            // dark. Gated additionally by participation.
            val adminCrownsVisible =
                CrownPointMarkers.crownsVisible(crownHuntFeatureEnabled, crownHuntParticipating)
            // BOTH flags AND participation. `crownHunt` is the feature as a whole;
            // `crownHuntSpawn` (contract default OFF) is the automatic half
            // specifically, because an auto-placed crown carries no admin's
            // confirmation that its spot is safe to stop at. With any off this is
            // false, and false here means the poll effect below returns
            // immediately: no `crownSpawns` query is ever issued, not a hidden
            // layer that still costs reads.
            val crownSpawnEnabled =
                crownHuntFeatureEnabled &&
                    flags.isEnabled(FeatureFlag.CROWN_HUNT_SPAWN) &&
                    crownHuntParticipating
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
            // The member's coarse location, refreshed on a slow cadence WHILE a
            // crown layer is visible, purely to decide which crowns are within
            // collect range (and so drawn in colour rather than greyed). Not the
            // 2 s high-accuracy fix the claim flow uses — that runs only with a
            // popup open; this is a cheap balanced-accuracy poll that bounds how
            // often the in-range sets can churn, so the markers never thrash on
            // tiny moves.
            //
            // Uses CurrentLocation.currentFix (a FRESH fix each pass), NOT
            // lastKnown: lastKnown prefers the fused provider's passive
            // `lastLocation` cache, which only advances while something else is
            // actively requesting updates — so a member driving into range kept
            // reading the same stale, out-of-range coordinate and the crown never
            // recoloured. currentFix asks the provider to recompute the position,
            // so the in-range set tracks the member across the ring.
            val anyCrownLayerActive = crownSpawnEnabled || adminCrownsVisible
            var crownUserLocation by remember { mutableStateOf<LatLng?>(null) }
            LaunchedEffect(anyCrownLayerActive) {
                if (!anyCrownLayerActive) {
                    crownUserLocation = null
                    return@LaunchedEffect
                }
                val appContext = context.applicationContext
                while (true) {
                    crownUserLocation = CurrentLocation.currentFix(appContext)
                    delay(CROWN_RANGE_LOCATION_INTERVAL_MS)
                }
            }
            // The spawn ids the member is within collect range of. Keyed on the
            // location + the crown set, so it recomputes when either moves — but
            // the marker list below is keyed on this SET, so the (more expensive)
            // marker rebuild fires only when a crown actually crosses the range
            // boundary, not on every location tick.
            val inRangeSpawnIds =
                remember(crownSpawns, crownUserLocation) {
                    val loc = crownUserLocation ?: return@remember null
                    crownSpawns
                        .filter {
                            CrownRange.isInRange(
                                loc.latitude, loc.longitude, it.latitude, it.longitude,
                                it.collectRadiusMeters,
                            )
                        }
                        .map { it.id }
                        .toSet()
                }
            // Crowns → drawing primitives for the map seam, exactly as incidents
            // are above: the surface is handed a colour, a silhouette and a tint
            // and knows nothing about rarities. Empty whenever the feature is off,
            // so a flag flipped mid-session takes the markers off the map without
            // waiting for a poll pass. A crown OUT of collect range is drawn in the
            // neutral out-of-range slate; it lights up to its rarity colour once
            // the member is close enough — the same rule that gates the popup.
            val crownMarkers =
                remember(crownSpawns, crownSpawnEnabled, inRangeSpawnIds) {
                    if (!crownSpawnEnabled) {
                        emptyList()
                    } else {
                        crownSpawns.map { spawn ->
                            // null in-range set = no fix yet → colour normally,
                            // rather than greying the whole layer.
                            val inRange = inRangeSpawnIds == null || spawn.id in inRangeSpawnIds
                            MapCrownMarker(
                                id = spawn.id,
                                longitude = spawn.longitude,
                                latitude = spawn.latitude,
                                discColorArgb = CrownMarkerStyle.discColorArgb(spawn.rarity, inRange),
                                iconRes = crownGlyphRes(spawn.rarity),
                                glyphColorArgb =
                                    CrownMarkerStyle.glyphColorArgb(spawn.rarity, inRange),
                                // Only a legendary IN range glows; out of range it
                                // carries no halo, so "walk to that one" is reserved
                                // for a legendary the member can actually reach.
                                glowColorArgb = CrownMarkerStyle.glowColorArgb(spawn.rarity, inRange),
                            )
                        }
                    }
                }
            // ── Kronjakt crown layer (the HAND-PLACED admin half) ───────────
            //
            // The gap Seb hit: an admin creates and activates a Kronjakt point
            // and NOTHING appears on the user map. Active `crownHuntPoints` were
            // always readable "for map display" (the security rule says so) and
            // the listener + index already existed for the hub list — but nothing
            // ever turned those points into markers. This is that missing step.
            //
            // Listener gated on visibility: with the feature off or the member
            // opted out we subscribe to nothing, matching the spawn layer's "off
            // means no reads" posture. The bounded query
            // ([CrownHunt.ACTIVE_POINTS_QUERY_LIMIT], the existing composite
            // index) is the same one the hub already runs, so no new index.
            val crownPointsFlow =
                remember(crownHuntRepository, adminCrownsVisible) {
                    if (crownHuntRepository != null && adminCrownsVisible) {
                        crownHuntRepository.observeActivePoints()
                    } else {
                        flowOf(CrownHuntPointsState.Loaded(emptyList<CrownHuntPoint>()))
                    }
                }
            val crownPointsState by
                crownPointsFlow.collectAsState(initial = CrownHuntPointsState.Loading)
            val crownPointGlyph = crownPointGlyphRes()
            // Admin points the member is within collect range of — the same
            // greying rule as the spawns, over each point's own geofence radius.
            val inRangePointIds =
                remember(crownPointsState, crownUserLocation) {
                    val loc = crownUserLocation ?: return@remember null
                    val points =
                        (crownPointsState as? CrownHuntPointsState.Loaded)?.points ?: emptyList()
                    points
                        .filter { point ->
                            val lat = point.latitude
                            val lon = point.longitude
                            lat != null && lon != null &&
                                CrownRange.isInRange(
                                    loc.latitude, loc.longitude, lat, lon,
                                    point.geofenceRadiusMeters ?: CrownSpawnLimits.COLLECT_RADIUS_METERS,
                                )
                        }
                        .map { it.id }
                        .toSet()
                }
            val crownPointMarkers =
                remember(crownPointsState, adminCrownsVisible, crownPointGlyph, inRangePointIds) {
                    val points =
                        (crownPointsState as? CrownHuntPointsState.Loaded)?.points ?: emptyList()
                    CrownPointMarkers.markers(
                        points,
                        adminCrownsVisible,
                        crownPointGlyph,
                        inRangeIds = inRangePointIds,
                    )
                }
            // Both crown sources share the ONE surface layer: the admin points and
            // the auto-spawns are drawn together (each already empty when its own
            // gate is off), so a member sees every crown that is meant for them
            // regardless of which pipeline placed it.
            val allCrownMarkers =
                remember(crownMarkers, crownPointMarkers) { crownPointMarkers + crownMarkers }
            LaunchedEffect(mapSurface, allCrownMarkers) {
                mapSurface.setCrownMarkers(allCrownMarkers)
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
            // client sent).
            //
            // SESSION-scoped, not keyed to the open crown, so it is PRE-WARMED: the
            // range poll below feeds it while the member is near a crown, so a
            // proof partner has usually already aged in the instant a popup opens —
            // the fix for the "stand still, tap, tap, then it collects" lag. It is
            // not cleared on leaving a popup; the tracker prunes anything past
            // MAX_DWELL itself, and the server's freshness + dwell checks reject a
            // stale pair, so keeping the warm recent history costs nothing and
            // avoids throwing away a still-valid dwell the member just earned.
            val crownFixTracker = remember { CrownFixTracker() }
            var crownCurrentFix by remember(tappedCrownId) { mutableStateOf<CrownFix?>(null) }
            var crownPreviousFix by remember(tappedCrownId) { mutableStateOf<CrownFix?>(null) }
            // Counts refused (NeedsPosition) taps for THIS crown so a genuinely
            // stuck dwell — the cause that never reaches the server — can be
            // signalled once. Keyed per crown, so the dedup is structural: one
            // signal per opened crown, no timer.
            val crownCollectSignalTracker =
                remember(tappedCrownId) { CrownCollectSignalTracker() }
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
                // Seed from the PRE-WARMED tracker before the first fresh read, so
                // the distance line — and, when the range poll already warmed a
                // pair, a live Collect button — are there the instant the popup
                // opens rather than a fix cadence later.
                applyCrownFix(crownFixTracker, System.currentTimeMillis()).let { (cur, prev) ->
                    crownCurrentFix = cur
                    crownPreviousFix = prev
                }
                while (true) {
                    val fix = CrownLocation.currentFix(appContext)
                    if (fix != null) {
                        crownFixTracker.record(fix)
                        applyCrownFix(crownFixTracker, System.currentTimeMillis())
                            .let { (cur, prev) ->
                                crownCurrentFix = cur
                                crownPreviousFix = prev
                            }
                    }
                    delay(CROWN_FIX_INTERVAL_MS)
                }
            }
            // Pre-warm the dwell tracker from the map's ongoing location WHILE the
            // member is near a collectable crown but no popup is open yet, so a
            // proof partner has already aged in the moment they tap one. Gated to
            // "in range, popup closed" so it only runs where a collect is actually
            // plausible; the open-popup loop above takes over (at the faster claim
            // cadence, high accuracy) the instant a crown is tapped.
            //
            // Bounded so it never sits on GPS: it reads at BALANCED power (the
            // timing, not the precise position, is all pre-warming needs — the
            // popup's own high-accuracy loop refines the fix on open) and STOPS the
            // moment a proof partner is available. So the cost is a couple of
            // samples when the member first parks by a crown, not a sustained poll
            // while they linger.
            val crownNearCollectable =
                crownSpawnEnabled && (inRangeSpawnIds?.isNotEmpty() == true)
            LaunchedEffect(crownNearCollectable, tappedCrownId) {
                if (!crownNearCollectable || tappedCrownId != null) return@LaunchedEffect
                // Already warm from an earlier pass — nothing to poll for. Uses the
                // FRESH pair-readiness check (proofPair(now)), so a stale pair from a
                // previous visit does not skip the warm-up.
                if (crownFixTracker.proofPair(System.currentTimeMillis()) != null) {
                    return@LaunchedEffect
                }
                val appContext = context.applicationContext
                while (crownFixTracker.proofPair(System.currentTimeMillis()) == null) {
                    CrownLocation.currentFix(appContext, highAccuracy = false)
                        ?.let { crownFixTracker.record(it) }
                    if (crownFixTracker.proofPair(System.currentTimeMillis()) != null) break
                    delay(CROWN_PREWARM_FIX_INTERVAL_MS)
                }
            }
            // Whether the two-fix stationary proof is ready, and — if not — a
            // friendly whole-second hint for the confirming button. The gate turns
            // "in range and stopped but no proof yet" into an honest, DISABLED
            // "confirming you're stopped" instead of a live button that refuses.
            val crownDwellReady =
                remember(crownCurrentFix, crownPreviousFix) {
                    val current = crownCurrentFix
                    val previous = crownPreviousFix
                    current != null && previous != null &&
                        CrownCollectGate.isDwellProofUsable(previous, current)
                }
            val crownDwellSecondsRemaining =
                remember(crownDwellReady, crownCurrentFix) {
                    if (crownDwellReady) {
                        null
                    } else {
                        crownFixTracker.secondsUntilProofReady(System.currentTimeMillis())
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
                remember(
                    crownSpawnEnabled,
                    crownDistanceMeters,
                    crownCurrentFix,
                    openCrown,
                    crownDwellReady,
                    crownDwellSecondsRemaining,
                ) {
                    CrownCollectGate.evaluate(
                        featureEnabled = crownSpawnEnabled,
                        distanceMeters = crownDistanceMeters,
                        speedMetersPerSecond = crownCurrentFix?.speedMetersPerSecond,
                        collectRadiusMeters =
                            openCrown?.collectRadiusMeters
                                ?: CrownSpawnLimits.COLLECT_RADIUS_METERS,
                        dwellProofReady = crownDwellReady,
                        dwellSecondsRemaining = crownDwellSecondsRemaining,
                        accuracyMeters = crownCurrentFix?.accuracyMeters,
                    )
                }
            // One key per opened crown, so a retry after a transport failure is the
            // SAME claim to the backend (which de-duplicates on it) rather than a
            // second attempt against the daily cap.
            val crownIdempotencyKey =
                remember(tappedCrownId) { java.util.UUID.randomUUID().toString() }

            // ── Tapped HAND-PLACED admin point ──────────────────────────────
            //
            // The SAME crown-tap channel carries a tap on either source, so the id
            // is resolved against the admin points too. Spawn ids and point ids are
            // both Firestore auto-ids (globally unique), so exactly one of the two
            // slots fills. Latched like [openCrown], so a refresh that changes the
            // active-points set does not yank a popup the member is reading.
            val openCrownPointSlot =
                remember(tappedCrownId) { mutableStateOf<CrownHuntPoint?>(null) }
            LaunchedEffect(tappedCrownId, crownPointsState) {
                val id = tappedCrownId ?: return@LaunchedEffect
                if (openCrownPointSlot.value == null) {
                    val points =
                        (crownPointsState as? CrownHuntPointsState.Loaded)?.points ?: emptyList()
                    openCrownPointSlot.value = points.firstOrNull { it.id == id }
                }
            }
            val openCrownPoint =
                if (tappedCrownId != null && adminCrownsVisible) openCrownPointSlot.value else null
            val crownPointClaimFlow =
                remember(crownHuntCoordinator) {
                    crownHuntCoordinator?.status
                        ?: MutableStateFlow<CrownHuntClaimStatus>(CrownHuntClaimStatus.Idle)
                }
            val crownPointClaimStatus by crownPointClaimFlow.collectAsState()
            // When the game goes off screen with a crown popup still OPEN — the
            // member opts out, or the feature flag flips — consume the pending tap
            // and clear both claim controllers. Without this the tap latch survives
            // the hide, so opting back in would resurrect the old popup and a
            // lingering "someone got there first" could greet the next crown.
            val anyCrownLayerVisible = crownSpawnEnabled || adminCrownsVisible
            LaunchedEffect(anyCrownLayerVisible) {
                if (!anyCrownLayerVisible && tappedCrownId != null) {
                    mapSurface.consumeCrownTap()
                    crownSpawnController?.resetClaim()
                    crownHuntCoordinator?.reset()
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
            // view, which navigates from the live GPS fix.
            //
            // rememberSaveable, not remember: a backgrounded app whose activity is
            // recreated (config change, or the system reclaiming and rebuilding it
            // while the user was off replying to a text) used to lose this and end
            // navigation the moment they returned. Carrying it in saved instance
            // state keeps the nav view up across that recreation. Full process
            // death is handled separately by the durable [NavResumeStore] + the
            // "continue last navigation?" prompt below.
            var navDestination by rememberSaveable(stateSaver = navLatLngSaver) {
                mutableStateOf<LatLng?>(null)
            }
            var navDestinationLabel by rememberSaveable { mutableStateOf("") }
            // Durable record of the in-progress navigation, for the resume prompt
            // after a cold start. Written on start, cleared on a confirmed exit.
            val navResumeStore = remember(context) { NavResumeStore(context) }
            // Raised by the BACK key while navigating → "exit navigation?" confirm.
            var showExitNavConfirm by remember { mutableStateOf(false) }
            // A persisted navigation eligible to resume on this launch, or null.
            var navResumeCandidate by remember { mutableStateOf<ActiveNavigation?>(null) }
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

            // Move the app's OWN map to a coordinate, IN-APP: the existing
            // navigate-here preview (the route + framed destination the long-press
            // used to raise directly). This is the single "move map to point" path,
            // shared by the place menu's "Navigate here", a chat geo-link tap, and a
            // saved-places pick — it NEVER fires an external ACTION_VIEW / maps app.
            val moveMapToPoint: (Double, Double, String?) -> Unit = { lat, lng, name ->
                navSearchTarget = LatLng(longitude = lng, latitude = lat)
                navSearchTargetName = name
                navSearchOpen = true
            }

            // A long-press on open map, or a tap on a basemap place, now raises the
            // place-actions MENU (navigate / copy position / save) with an animated
            // pin on the point — instead of jumping straight into the navigate-here
            // preview. "Navigate here" then feeds [moveMapToPoint], the same flow
            // the gesture used to trigger directly. Null when no menu is open.
            var placeMenuTarget by remember { mutableStateOf<MapPlaceRequest?>(null) }
            // The saved-locations picker the map's saved-places control opens.
            var savedPlacesPickerOpen by remember { mutableStateOf(false) }
            // The point pending a NAME in the save-location popup ("Save this
            // location"): naming it persists it as a favourite.
            var saveLocationTarget by remember { mutableStateOf<MapPlaceRequest?>(null) }
            // A resolved location pending a friend to share it with — set by the
            // Saved-places long-press Share (or any other share entry point), and
            // consumed by the ShareLocationSheet below.
            var shareLocationTarget by remember { mutableStateOf<ShareableLocation?>(null) }
            val pendingPlace by mapSurface.placeRequest.collectAsState()
            LaunchedEffect(pendingPlace) {
                val requested = pendingPlace ?: return@LaunchedEffect
                placeMenuTarget = requested
                mapSurface.consumePlaceRequest()
            }

            val clipboard = LocalClipboardManager.current
            val positionCopiedText = stringResource(R.string.shell_placeCopied)
            val positionSavedText = stringResource(R.string.shell_placeSaved)
            val sharedLocationName = stringResource(R.string.shell_sharedLocationName)

            // Incoming map-link deep link. MainActivity parses a geo: /
            // google.navigation: URI the member opened WITH KCC (from Android's
            // "Open with"/default-handler chooser) and parks the point in
            // MapLinkNavigator — a process-level hand-off for the same reason as
            // PushNavigator: the Intent arrives outside this composition and may
            // precede it on a cold start. Consumed exactly once here and driven
            // through the SAME in-app [moveMapToPoint] flow a chat geo-link tap or
            // a saved-place pick uses (which raises the navigate-here preview over
            // whatever is showing — see ShellNav.mapCover, where navSearchOpen is
            // a Transparent cover that wins over the current tab/route). It never
            // fires an external maps app. The link's own label names the pin when
            // it carries one; otherwise the shared "shared location" name is used.
            val mapLinkTarget by MapLinkNavigator.pending.collectAsState()
            LaunchedEffect(mapLinkTarget) {
                val target = MapLinkNavigator.consume() ?: return@LaunchedEffect
                val name = target.label?.takeIf { it.isNotBlank() } ?: sharedLocationName
                moveMapToPoint(target.point.latitude, target.point.longitude, name)
            }
            // Share-to-friend confirmation copy, resolved in composition (the send
            // callback fires off the UI thread and cannot call stringResource). The
            // "shared" line keeps its %1$s placeholder and is String.format-ed with
            // the friend name at callback time — a plain-string format, so no
            // LocalContext resource lookup happens off-composition.
            val locationSharedTemplate = stringResource(R.string.shell_locationShared)
            val sharedLocationFriendFallback =
                stringResource(R.string.shell_locationSharedFriendFallback)
            val locationShareFailedText = stringResource(R.string.shell_locationShareFailed)

            // The long-press place-actions menu (+ its animated map pin, drawn by
            // MapHome from [placeMenuTarget]). Each action clears the menu.
            placeMenuTarget?.let { target ->
                PlaceActionsSheet(
                    placeName = target.name,
                    coordinateText =
                        GeoLinks.coordinateLabel(
                            latitude = target.point.latitude,
                            longitude = target.point.longitude,
                        ),
                    onNavigate = {
                        val point = target.point
                        placeMenuTarget = null
                        moveMapToPoint(point.latitude, point.longitude, target.name)
                    },
                    onCopy = {
                        val point = target.point
                        placeMenuTarget = null
                        clipboard.setText(
                            AnnotatedString(
                                GeoLinks.formatForClipboard(point.latitude, point.longitude),
                            ),
                        )
                        scope.launch { snackbarHostState.showSnackbar(positionCopiedText) }
                    },
                    // "Save this location" now opens a naming popup (below) rather
                    // than saving silently: the member names the point — or leaves
                    // it blank for a coordinate name.
                    onSave = {
                        saveLocationTarget = target
                        placeMenuTarget = null
                    },
                    onDismiss = { placeMenuTarget = null },
                )
            }

            // The save-location naming popup raised by "Save this location". A blank
            // name is filled from the GPS coordinate (LocationShare.resolveName), so
            // a saved point is never nameless.
            saveLocationTarget?.let { target ->
                val latLng =
                    LatLng(longitude = target.point.longitude, latitude = target.point.latitude)
                SaveLocationDialog(
                    initialName = target.name.orEmpty(),
                    coordinateHint = LocationShare.coordinateName(latLng),
                    onSave = { rawName ->
                        val label = LocationShare.resolveName(rawName, latLng)
                        // Reuses the EXISTING saved-places store (the same instance
                        // the address search reads/writes) — no new store — so a
                        // saved pin round-trips into the search bar's saved list.
                        savedPlacesStore.save(
                            SavedPlaces.create(
                                kind = SavedPlaceKind.Favourite,
                                place =
                                    PlaceSuggestion(
                                        id = "",
                                        name = label,
                                        address = null,
                                        point = latLng,
                                    ),
                                label = label,
                            ),
                        )
                        saveLocationTarget = null
                        scope.launch { snackbarHostState.showSnackbar(positionSavedText) }
                    },
                    onDismiss = { saveLocationTarget = null },
                )
            }

            // The shared "share a location with a friend" picker, hosted once for
            // every entry point (the Saved-places long-press Share, and any other
            // share entry). Delivery reuses the existing DM send path — no new backend.
            // Guarded on both repositories (a config-less build wires neither).
            val fRepo = friendsRepository
            val dRepo = dmRepository
            // Only render when both repositories are wired; a config-less build
            // (neither) simply has no friends/DM features, so a stale target is
            // inert — no state is written during composition to clear it.
            if (fRepo != null && dRepo != null) {
                shareLocationTarget?.let { location ->
                    ShareLocationSheet(
                        location = location,
                        friendsRepository = fRepo,
                        dmRepository = dRepo,
                        onShared = { friendName ->
                            shareLocationTarget = null
                            val name = friendName?.takeIf { it.isNotBlank() } ?: sharedLocationFriendFallback
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    String.format(locationSharedTemplate, name),
                                )
                            }
                        },
                        onSendFailed = {
                            scope.launch { snackbarHostState.showSnackbar(locationShareFailedText) }
                        },
                        onDismiss = { shareLocationTarget = null },
                    )
                }
            }

            // The saved-locations picker: tapping a place moves the map to it via
            // the SAME in-app [moveMapToPoint] flow a chat geo-link tap uses.
            if (savedPlacesPickerOpen) {
                SavedPlacesPickerSheet(
                    places = savedPlacesStore.saved(),
                    onSelect = { place ->
                        savedPlacesPickerOpen = false
                        moveMapToPoint(
                            place.place.point.latitude,
                            place.place.point.longitude,
                            place.place.name,
                        )
                    },
                    onDismiss = { savedPlacesPickerOpen = false },
                )
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
            //
            // Collected as a LOAD STATE (Loading until the flow first emits, then
            // Loaded) rather than a bare nullable: after an Activity recreation
            // (rotation) the collector is momentarily back on its placeholder null
            // while the RTDB listener re-attaches, which is indistinguishable BY
            // VALUE from "no session" and used to trip the recording-lifecycle
            // effect into treating a rotation as a session end (stopping the drive
            // and raising its save prompt). [liveSessionObserved] gates that off —
            // see the recording effect below. See [LiveSessionLoad].
            val liveSessionLoad by
                produceState<LiveSessionLoad>(
                    initialValue = LiveSessionLoad.Loading,
                    uid,
                    liveLocationRepository,
                ) {
                    val flow = liveLocationRepository?.observeOwnSession(uid) ?: flowOf(null)
                    flow.collect { value = LiveSessionLoad.Loaded(it) }
                }
            val liveSession = liveSessionLoad.sessionOrNull
            val liveSessionObserved = liveSessionLoad.observed
            // #726 invariant guard for the recording-stop effect below: an ACTIVE
            // convoy implies an ongoing live session, so a not-sharing read while
            // a convoy is active is a config-change transient, never a real end.
            // Latched (rememberSaveable) so it SURVIVES the Activity recreation: on
            // rotation the convoy snapshot (derived far below, next to the bar) is
            // itself briefly Loading, so the live value would read false in exactly
            // the window we must protect. The write, keyed on the convoy snapshot
            // having loaded, is done where that snapshot exists; the restored value
            // bridges the gap until it does. See [LiveSessionRecordingLifecycle].
            var convoyActiveLatched by rememberSaveable { mutableStateOf(false) }
            // Whether the member ended THIS session themselves — tapped Stop / Hide
            // me now, or chose End/Leave in the convoy-stop dialog (all of which run
            // through stopLiveShare/hideMeNow below). Distinguishes a self-stop from
            // a convoy-auto session the backend stopped BECAUSE the convoy ended
            // under them, so the save prompt only explains the latter (see
            // [savePromptReason]). Reset when a new session starts; rememberSaveable
            // so a recreation between the tap and the stop effect can't lose it and
            // mislabel a deliberate stop as a convoy end.
            var userEndedSession by rememberSaveable { mutableStateOf(false) }
            // A convoy the member was IN was ended by someone else, stopping their
            // convoy-auto session under them. Instead of stopping the recording and
            // going straight to the save prompt, we ask: end the session too, or keep
            // going solo (transfer the session to a standalone single session with no
            // gap). Holds the ENDED session's id while its choice dialog is open; null
            // = no choice pending. rememberSaveable so a convoy that ends while the
            // app is backgrounded still prompts on return, and the dialog survives an
            // Activity recreation — mirroring the process-scoped save-prompt state.
            // Keyed by uid (matching the other auth-scoped saveable state in this
            // file) so an account switch within the same process resets it — a
            // stopped session id belonging to the previous user must never raise this
            // prompt for the next one.
            var convoyEndPromptSessionId by rememberSaveable(uid) { mutableStateOf<String?>(null) }
            // The convoy-ended session the member has ALREADY answered for (End or
            // Continue). Because the recording-stop effect re-derives the convoy-ended
            // condition from the still-stopped session on every re-run/recreation, this
            // stops the choice dialog re-raising over the save prompt (End) or over the
            // freshly-transferred solo session before it echoes back (Continue). Keyed
            // by session id, so a LATER convoy end (a different session) still prompts.
            var convoyEndDecidedSessionId by rememberSaveable(uid) { mutableStateOf<String?>(null) }
            // A convoy-ended session for which the member chose "Continue as a single
            // session" and the transfer (starting a fresh solo session) is IN FLIGHT
            // but not yet confirmed. Held separately from [convoyEndDecidedSessionId]
            // because a transfer can still FAIL (the start is refused / returns
            // Busy/Failed / never echoes a session): marking it decided up front would
            // permanently suppress the dialog and leave the recording running with no
            // session. While it is pending the dialog is NOT re-raised, and a
            // resolution effect below turns it into either "decided" (the new session
            // was confirmed — the recording carried straight into it) or a clean
            // stop+save (the start never produced a session), so Continue can never
            // orphan the recording. Keyed by uid + survives recreation, like the
            // markers above, so a transfer in flight across a rotation still resolves.
            var convoyTransferPendingSessionId by
                rememberSaveable(uid) { mutableStateOf<String?>(null) }
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

            // --- Optimistic start (instant STOP sign + live bar) -------------
            // [isSharing] above is the TRUTH, but it only becomes true once the
            // start callable has returned AND the server's session write has
            // echoed back down the RTDB listener. Waiting for that left the "+"
            // sitting there for the whole round trip after a tap. The tap now
            // also records an attempt in [LiveShareStart], and everything the
            // user LOOKS at reads [isSharingUi] = observed OR attempt-pending, so
            // the control flips on the next frame.
            //
            // Deliberately NOT used for the session-bound side effects (drive
            // recording, foreground publisher, KeepScreenOn): those stay on the
            // observed session, so a start that fails can never leave a phantom
            // recording or a running service behind.
            val startAttempt by LiveShareStart.attempt.collectAsState()
            // Truth landed → drop the overlay. Keyed on the attempt too, so an
            // attempt recorded while a session is ALREADY observed (a convoy tap
            // by someone who is already sharing) is discarded straight away
            // rather than lingering to outlive the session it can't belong to.
            LaunchedEffect(isSharing, startAttempt) {
                if (isSharing) LiveShareStart.reconcile(true)
            }
            // Expiry backstop: no attempt may outlive its deadline, so a hung or
            // silently-sessionless start always returns the control to "+".
            // A never-answered command (InFlight) also says so; a command that
            // SUCCEEDED but produced no session (live-share disabled server-side,
            // a convoy that was still forming) reverts silently — nothing failed
            // from the user's point of view. clearIf keeps a deadline firing at
            // the same moment as a newer tap from wiping the newer attempt.
            LaunchedEffect(startAttempt) {
                val until = OptimisticLiveStart.pendingUntilMillis(startAttempt) ?: return@LaunchedEffect
                val remaining = until - nowMillis()
                if (remaining > 0) delay(remaining)
                LiveShareStart.clearIf(startAttempt)
                if (startAttempt is LiveStartAttempt.InFlight) {
                    snackbarHostState.showSnackbar(liveErrorText)
                }
            }

            // --- Optimistic STOP (instant hide of STOP sign + live bar + dot) -
            // The exact mirror of the optimistic START above, for #798: tapping
            // Stop used to leave the sharing chrome up for the whole server round
            // trip while the stopped session echoed back. The tap now records a
            // stop attempt in [LiveShareStop], and everything the user LOOKS at
            // treats "sharing MINUS a live stop attempt" as sharing, so the chrome
            // clears on the next frame. Like the start overlay it does NOT drive the
            // session-bound side effects (the drive Keep/Delete summary, the
            // publisher) — those stay on the observed session, so a stop that fails
            // can never tear a still-live recording/service down.
            val stopAttempt by LiveShareStop.attempt.collectAsState()
            // Truth landed (the session is no longer observed sharing) → drop the
            // overlay. AND drop it the moment a start overlay appears: a fresh start
            // (manual OR convoy-auto, which never runs through stopLiveShare) must
            // not be masked by a stale stop attempt from the previous session, which
            // would force isSharingUi false until the stop attempt expired.
            LaunchedEffect(isSharing, startAttempt, stopAttempt) {
                if (!isSharing) LiveShareStop.reconcile(false)
                if (startAttempt != LiveStartAttempt.None) LiveShareStop.clear()
            }
            // Expiry backstop: no stop attempt may outlive its deadline, so a hung
            // or silently-unechoed stop always lets the observed truth show through
            // again rather than hiding a session that may still be live.
            LaunchedEffect(stopAttempt) {
                val until = OptimisticLiveStop.pendingUntilMillis(stopAttempt) ?: return@LaunchedEffect
                val remaining = until - nowMillis()
                if (remaining > 0) delay(remaining)
                LiveShareStop.clearIf(stopAttempt)
            }
            // Observed-or-optimistically-started sharing, MINUS a live stop attempt.
            val isSharingUi =
                OptimisticLiveStart.isSharing(isSharing, startAttempt, nowMillis()) &&
                    !OptimisticLiveStop.isStopping(stopAttempt, nowMillis())

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
            val driveSavedText = stringResource(R.string.savedDrives_savedDialogMessage)
            val driveSavedOkText = stringResource(R.string.savedDrives_savedDialogOk)
            val driveSavedHistoryText = stringResource(R.string.savedDrives_savedDialogHistory)
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

            // Persists the in-flight recording to disk (#849) so the OS killing the
            // backgrounded process — routine on Samsung / under Doze — no longer
            // loses the drive: a relaunched-but-still-live session resumes it. Kept
            // under the app's private filesDir.
            val driveRecordingJournal =
                remember(context) {
                    DriveRecordingJournal(File(context.filesDir, "drive-journals"))
                }

            // Low-noise structured log of the drive-recording lifecycle (#849
            // follow-up): start / resume / point milestones / stop, plus the
            // app-launch route-restore below. Emitted to logcat under the
            // "DriveRecording" tag so a recurrence of "the in-progress drive
            // vanished after a restart" is traceable from a device log without
            // per-fix spam.
            val driveRecordingLog = remember { LogcatDriveRecordingLog() }

            // Restore the on-screen route after a process death (#849 follow-up).
            // When a relaunched-but-still-live session RESUMES a persisted drive,
            // its journalled points are back in the recorder — but the map's
            // breadcrumb tail is a memory-only buffer that starts EMPTY on a cold
            // start, so the drive the user is still recording looks lost until they
            // have driven another window's worth. Re-seed the tail from the resumed
            // route so the road already driven redraws at once. A fresh drive
            // exposes no resumed points, so this is a no-op then. Keyed on the
            // coordinator instance so it runs once per session (its
            // resumedRoutePoints are fixed at start); seedBreadcrumb is itself
            // idempotent + race-safe (applied only while sharing with an empty tail).
            LaunchedEffect(activeRecording) {
                val coordinator = activeRecording ?: return@LaunchedEffect
                val resumed = coordinator.resumedRoutePoints
                if (resumed.isNotEmpty()) {
                    mapSurface.seedBreadcrumb(
                        resumed.map { MapPoint(longitude = it.longitude, latitude = it.latitude) },
                    )
                    driveRecordingLog.restoredToMap(coordinator.sourceSessionId, resumed.size)
                }
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
            //
            // Keyed on [liveSessionObserved] as well as [isSharing] so the STOP
            // path only fires once the session flow has actually emitted. On a
            // rotation the recreated composition briefly reads isSharing == false
            // off the flow's not-yet-loaded placeholder; stopping there tore the
            // live drive down and raised its save/discard prompt on every rotate.
            // [LiveSessionRecordingLifecycle.shouldStopRecording] withholds the
            // stop until the flow is loaded, so rotation is a no-op while a real
            // end (Stop / Hide / expiry / convoy end — all of which arrive with
            // the flow loaded) still stops and auto-saves.
            // Keyed on (liveSession != null) too: an observed session can go from
            // a transient MISSING read to a real status=stopped object without
            // isSharing/observed/convoy changing, and the stop decision reads that
            // presence — so the effect must re-run when it flips.
            LaunchedEffect(isSharing, liveSessionObserved, liveSession != null, convoyActiveLatched) {
                if (isSharing) {
                    // canRecordDrive already covers the null repository; the
                    // explicit check is what smart-casts it for the start call.
                    if (drivesRepository != null && canRecordDrive) {
                        // Owned by this uid: signing out (or switching account)
                        // tears the recording down — see clearIfNotOwnedBy,
                        // driven from MainActivity's auth state.
                        SingleSessionRecording.start(
                            uid,
                            drivesRepository,
                            routeUploadRunner,
                            // Key the ride on the LIVE-SESSION id so this client
                            // save and the server-side convoy finalize dedupe onto
                            // one `rides/{uid}_{sessionId}` document (see
                            // SingleSessionRecording.start / functions
                            // live.cleanupExpired). isSharing is true here BECAUSE
                            // this session is active, so its id is present.
                            sessionId = liveSession?.sessionId,
                            // Record which car is being driven (the session's
                            // denormalized cover photo + garage id) so the History
                            // card can draw a round photo of it and the drive links
                            // back to the vehicle. Null when the sharer has no car —
                            // History then shows no car photo.
                            carImagePath = liveSession?.mainCar?.imagePath,
                            vehicleId = liveSession?.vehicleId,
                            // Crash-resilient recording: resumes this drive if the
                            // process is killed while the session stays live (#849).
                            journal = driveRecordingJournal,
                            // Structured lifecycle log (start/resume/milestone/stop)
                            // so a future recurrence is diagnosable (#849 follow-up).
                            log = driveRecordingLog,
                        ) {
                            // Null when Play services are unavailable OR the
                            // fine-location permission isn't granted; either way
                            // no fixes can arrive and the session yields an
                            // honest duration-only summary. Evaluated HERE, as
                            // the session starts, so granting the permission and
                            // starting a new session gets a real controller.
                            DriveLocationController.createIfPermitted(context)
                        }
                    }
                } else if (
                    LiveSessionRecordingLifecycle.shouldStopRecording(
                        sharing = isSharing,
                        sessionObserved = liveSessionObserved,
                        // A real observed end (Stop / Hide-me-now / expiry) leaves
                        // a status=stopped/expired session node, so this is true
                        // and the convoy guard below does NOT apply — hide-me-now
                        // stops without leaving the convoy and must still save.
                        sessionPresent = liveSession != null,
                        // Withheld only for the MISSING-session re-sync transient
                        // while a convoy is (or, across the recreation, was) still
                        // active — see the latch above and #726.
                        convoyActive = convoyActiveLatched,
                    )
                ) {
                    // Session genuinely ended: stop recording and raise the
                    // end-of-session summary (the drive is then auto-saved by the
                    // effect below). The holder releases the GPS source here, at
                    // the real session end, rather than on composable disposal.
                    // Withheld while the session flow has not re-emitted after a
                    // recreation, so a rotation neither stops nor prompts.
                    //
                    // A convoy-auto session that stopped without the member ending
                    // it themselves — and before its 6h expiry — was stopped by the
                    // convoy ending (functions stopConvoyAutoSession), so the summary
                    // explains that rather than reading the neutral "Drive saved" out
                    // of nowhere. A session that simply hit its expiry (its own clock
                    // ran out, convoy possibly still running) stays neutral.
                    val endedByExpiry =
                        liveSession?.expiresAtMillis?.let { it <= nowMillis() } == true
                    val reason =
                        savePromptReason(
                            convoyAutoStarted = liveSession?.convoyAutoStarted == true,
                            userEndedSession = userEndedSession,
                            endedByExpiry = endedByExpiry,
                        )
                    when (val action = ConvoyEndSessionChoice.onSessionEnded(reason)) {
                        is EndedSessionAction.StopAndSave -> {
                            // Use the reason the decision carried, not the recomputed
                            // one, so the two can't drift if onSessionEnded ever maps
                            // to a different reason (identical today).
                            SingleSessionRecording.stop(action.reason)
                            // Consume the self-stop marker now the reason is captured,
                            // so the NEXT session is judged fresh. Reset HERE rather
                            // than on session start: the convoy-stop dialog flips
                            // convoyActiveLatched (a key of this effect) mid-stop while
                            // isSharing is still true, so a reset in the sharing branch
                            // could wipe the marker between the user's choice and the
                            // actual stop. stop() already froze the reason, so a re-run
                            // reading the cleared flag is a harmless no-op.
                            userEndedSession = false
                        }
                        EndedSessionAction.AskEndOrContinue -> {
                            // Someone else ended a convoy the member was in, so the
                            // backend stopped their convoy-auto session. DON'T stop the
                            // recording yet: ask first (End vs Continue as a single
                            // session). Key the prompt on the ended session's id and
                            // skip it once that session has been answered, so this
                            // effect re-running (recreation / a further emission of the
                            // still-stopped session) can't re-open the dialog.
                            //
                            // reason == ConvoyEnded here, which requires
                            // convoyAutoStarted == true and therefore a non-null
                            // liveSession carrying a non-null sessionId — so this
                            // `!= null` only exists to smart-cast the nullable local,
                            // never to guard a real null.
                            val endedSessionId = liveSession?.sessionId
                            if (endedSessionId != null &&
                                endedSessionId != convoyEndDecidedSessionId &&
                                // Also skip while a Continue transfer for this session
                                // is still in flight: the old stopped session is still
                                // what we observe until the new one echoes, so without
                                // this the dialog would re-open mid-transfer.
                                endedSessionId != convoyTransferPendingSessionId
                            ) {
                                convoyEndPromptSessionId = endedSessionId
                            }
                        }
                    }
                }
            }

            // Resolve an in-flight "Continue as a single session" transfer so it can
            // NEVER leave the recording orphaned. Choosing Continue starts a fresh
            // solo session but does not mark the convoy-ended session decided — this
            // effect does, once the outcome is known:
            //  - the new session is CONFIRMED (isSharing observed true): the recording
            //    carried straight into it; mark decided so the dialog never re-opens.
            //  - the start never produced a session (refused / Busy / Failed / timed
            //    out): [isSharingUi] is observed OR an optimistic attempt still
            //    pending, so once it reads false the optimistic overlay has already
            //    given up (its own IN_FLIGHT_TIMEOUT_MS / ECHO_GRACE_MS grace windows).
            //    Fall back to a clean stop+save with the convoy-ended prompt.
            // Keyed on both sharing signals AND the pending id, so it re-runs the
            // moment the transfer resolves either way; a no-op until then (right after
            // Continue, the just-recorded attempt keeps isSharingUi true).
            LaunchedEffect(isSharing, isSharingUi, convoyTransferPendingSessionId) {
                val pending = convoyTransferPendingSessionId ?: return@LaunchedEffect
                if (isSharing) {
                    convoyEndDecidedSessionId = pending
                    convoyTransferPendingSessionId = null
                } else if (!isSharingUi) {
                    convoyEndDecidedSessionId = pending
                    convoyTransferPendingSessionId = null
                    SingleSessionRecording.stop(SavePromptReason.ConvoyEnded)
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
            //
            // #849 note — the foreground service reliably starts at a SOLO drive
            // start (the manual start always runs in the foreground where a FGS
            // start is allowed). The one window with briefly NO foreground service
            // is a CONVOY-AUTO session that flips isSharing true while the app is
            // BACKGROUNDED: a background FGS start is refused, so
            // BackgroundLocationController records a pending start and retries it on
            // the next foreground. During that gap nothing publishes AND the drive
            // is memory-only — which is exactly why the recording is journaled to
            // disk (DriveRecordingJournal): if the OS kills the process in that
            // window, the relaunched-but-still-live session resumes the drive rather
            // than losing it.
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

            // Reports a genuinely stuck crown collect — the dwell-not-ready cause
            // that never reaches the server — through the same client-error
            // pipeline, so a backend detector can count it. Fired from the collect
            // ATTEMPT (see the popup's onCollect below), NOT off a claim-status
            // transition: `collect()` sets the status to NeedsPosition and returns,
            // so a StateFlow watcher would see identical values on re-taps and drop
            // them — the counter would reach one and never the threshold. Counting
            // per tap is what makes the signal fire. Kept as a composition-stable
            // lambda so the popup callback closes over one instance; the tracker is
            // per-crown and latches, so it still cannot spam.
            val reportCrownCollectRefused: () -> Unit = {
                val count = crownCollectSignalTracker.onRefused()
                if (count != null) {
                    errorReporter?.report(
                        feature = CrownCollectSignalTracker.SIGNAL_FEATURE,
                        // App-generated and PII-free: a bare refusal count, no
                        // coordinates, no crown id, no uid. Worded to be true AT
                        // REPORT TIME — the member may close the popup without ever
                        // succeeding, so it makes no "resolved" claim.
                        message =
                            "Crown collect refused $count times (stationary proof not ready)",
                        code = CrownCollectSignalTracker.SIGNAL_CODE,
                    )
                }
            }

            // Auto-save a finished LIVE session's drive so it can never be lost by
            // a missed Save. When the session ends (PromptSave), the drive is
            // immediately persisted in the background. Launched on the composition
            // [scope] (NOT this effect's own coroutine) so moving off PromptSave —
            // which this very save causes — cannot cancel it mid-flight. Keyed on
            // the boolean so it fires once on entering PromptSave; an Activity
            // recreation that cancelled an in-flight save restored PromptSave, and
            // the re-created effect re-fires it (the drive is never left unsaved).
            val awaitingAutoSave = recordingState is RecordingState.PromptSave
            LaunchedEffect(awaitingAutoSave) {
                if (awaitingAutoSave) {
                    scope.launch { activeRecording?.autoSave(null) }
                }
            }

            // Keep every drive by default (#853). A finished live session's drive
            // is already auto-saved above; rather than opening a Keep/Delete prompt
            // over the already-saved ride, KEEP it automatically the moment the
            // background save is under way. The user removes an unwanted drive from
            // the History list instead (DrivesScreen delete). keep() resolves to the
            // terminal Kept instantly when the save has already landed, or parks in
            // KeptPendingSave until it does — never finalizing on an unconfirmed
            // save, so a drive is never lost; a definitive save failure still
            // surfaces the retry prompt (SessionSummaryDialog) rather than being
            // silently kept. Keyed on the boolean so it fires once on entering
            // SavedPendingChoice, matching the auto-save effect above.
            val awaitingAutoKeep = recordingState is RecordingState.SavedPendingChoice
            LaunchedEffect(awaitingAutoKeep) {
                if (awaitingAutoKeep) {
                    activeRecording?.keep()
                }
            }

            // Terminal states release the recording so the next session starts
            // clean; the outcome is confirmed to the user. Kept / Deleted are the
            // live auto-save flow's terminals; Discarded is only reached when a
            // permanent (member-gate) save refusal is closed — nothing was saved.
            LaunchedEffect(recordingState) {
                when (recordingState) {
                    RecordingState.Kept -> {
                        // Capture the saved ride id BEFORE clear() releases the
                        // coordinator, so the dialog's History action can deep-link
                        // to it. Then raise the informational "Drive saved" dialog —
                        // the drive is already saved in the background, so this only
                        // confirms it (OK dismisses; History opens Drives/History).
                        // Setting the state synchronously (no suspend) is safe even
                        // though clear() flips recordingState to Idle and re-keys
                        // this effect: the writes have already landed by then.
                        // The owner asked for a window rather than the earlier
                        // snackbar (#856) so the confirmation can't be missed.
                        val rideId = activeRecording?.savedRideId
                        SingleSessionRecording.clear()
                        driveSavedDialogRideId = rideId
                        driveSavedDialogVisible = true
                    }
                    RecordingState.Deleted -> {
                        // Show the confirmation on the composition [scope], NOT this
                        // effect's own coroutine: clear() flips recordingState to Idle,
                        // which re-keys and CANCELS this LaunchedEffect — a suspend
                        // showSnackbar called here would be cancelled before it renders
                        // (the confirmation would flicker or never show).
                        SingleSessionRecording.clear()
                        scope.launch { snackbarHostState.showSnackbar(driveDeletedText) }
                    }
                    RecordingState.Discarded -> {
                        // Same re-key/cancel hazard as Deleted above: launch on the
                        // composition [scope] so clear() can't cancel the confirmation.
                        SingleSessionRecording.clear()
                        scope.launch { snackbarHostState.showSnackbar(driveDiscardedText) }
                    }
                    else -> Unit
                }
            }

            // --- Live-session bar (map top strip) --------------------------
            // While a live session runs, the top of the map shows a compact pill
            // with the session's elapsed time and the distance driven this session.
            // Both values are derived here and handed to a dumb [LiveSessionBar].
            //
            // Elapsed is counted from the session's START, and WHICH start is the
            // whole subtlety — see [LiveSessionElapsed], which owns the rules.
            // Short version: `now` is the device clock, so the start has to be a
            // device instant too, and the only one that is both device-clocked and
            // really the session's start is the moment start was TAPPED.
            //
            // The value below is the OBSERVED fallback, used when there is no tap
            // to go on (a session that was already running when the app opened).
            // It reconstructs the start from the session itself — its expiry minus
            // the chosen duration — which is a SERVER instant, hence only a
            // fallback. The drive recorder's startedAtMillis is a device instant
            // but it is the moment RECORDING started, which is "now" for a session
            // resumed in a fresh process; it is therefore the LAST resort, for the
            // degraded case where the session carries no usable expiry/duration
            // (better a bar counting from when we noticed than a STOP disc with no
            // bar at all).
            //
            // Whatever is chosen is STABLE (it changes only when a session starts),
            // NOT per-second: the once-a-second ticker lives inside
            // [LiveSessionBar], so a running session recomposes only the small bar
            // rather than this whole (very large) composable.
            val observedSessionStartMillis: Long? =
                liveSession?.let { session ->
                    val expiry = session.expiresAtMillis
                    val durationMs =
                        session.duration?.let { it.hours.toLong() * 60L * 60L * 1000L }
                    if (expiry != null && durationMs != null) expiry - durationMs else null
                }
                    ?: activeRecording?.startedAtMillis
            // One clock read for the whole derivation, so the anchor cannot be
            // clamped against a `now` a millisecond older than the one it is
            // compared with.
            val liveBarNowMillis = nowMillis()
            // The latched anchor: resolved during composition (NOT in an effect) so
            // the bar renders on the very first frame the STOP disc does, and
            // written back so an Activity recreation mid-session keeps it.
            // Keyed by uid: an anchor left behind by a DIFFERENT account is not read
            // at all, so switching between two sharing accounts can never open the
            // second one's bar on the first one's elapsed time.
            val latchedSessionAnchor by LiveSessionAnchor.anchor.collectAsState()
            val liveSessionStartMillis: Long? =
                LiveSessionElapsed.anchorMillis(
                    latchedMillis = LiveSessionAnchor.startMillisFor(latchedSessionAnchor, uid),
                    sharing = isSharingUi,
                    tapStartMillis =
                        OptimisticLiveStart.pendingTapMillis(startAttempt, liveBarNowMillis),
                    observedStartMillis = observedSessionStartMillis,
                    nowMillis = liveBarNowMillis,
                )
            SideEffect { LiveSessionAnchor.set(uid, liveSessionStartMillis) }
            // Distance driven this session, straight off the recorder's running
            // total (0 before the first fix / when nothing is recording). Only
            // changes on GPS fixes, which the shell already observes via
            // recordingState — so it adds no extra recomposition.
            val liveSessionDistanceMeters =
                (recordingState as? RecordingState.Recording)?.distanceMeters ?: 0.0
            // Current speed for the same bar: the platform's own Location.speed off
            // the latest fix, published by the session's GPS source (null until a
            // fix carries one). Process-scoped like the recording itself, so an
            // Activity recreation mid-session does not blank the readout; the bar
            // decides whether the reading is still fresh enough to show. It changes
            // only on GPS fixes, exactly like the distance above, so it adds no
            // recomposition cadence the shell did not already have.
            val liveSessionSpeed by CurrentSpeed.sample.collectAsState()
            // Composed only while a session is actually sharing AND we have a start
            // to tick from; null otherwise composes nothing at all (no empty pill
            // in the search strip).
            val liveSessionStart = liveSessionStartMillis
            val liveSessionBarSlot: (@Composable () -> Unit)? =
                if (isSharingUi && liveSessionStart != null) {
                    {
                        LiveSessionBar(
                            sessionStartMillis = liveSessionStart,
                            distanceMeters = liveSessionDistanceMeters,
                            speedSample = liveSessionSpeed,
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

            // Whether the live-share STOP sheet is shown. Raised by the centre live
            // control while a session runs (the bottom bar's live disc), it is the
            // confirm step for ending the session and holds nothing else: "Hide me
            // now" and "More options" were removed from it. Only meaningful while
            // sharing, so it is force-closed the instant a session ends (expiry,
            // sign-out, or Stop from inside the sheet) by the effect below.
            var liveManageOpen by remember { mutableStateOf(false) }
            // A pending "what about the convoy?" prompt raised when Stop is tapped
            // while a convoy is active (Bug: the session used to just stop, leaving
            // the convoy orphaned). Non-null → the dialog below is shown instead of
            // stopping immediately. Force-closed with the sheet when sharing ends.
            var convoyStopPrompt by remember { mutableStateOf<ConvoyStopPromptState?>(null) }
            LaunchedEffect(isSharingUi) {
                if (!isSharingUi) {
                    liveManageOpen = false
                    convoyStopPrompt = null
                }
            }

            // Start the Single session for the given duration. The drive
            // recording AND the foreground position publisher both auto-start off
            // the resulting isSharing flip (the session-bound effects above), so
            // neither is started explicitly here — LaunchedEffect(isSharing) is the
            // single source of truth for the publisher (the same wiring that makes
            // a convoy AUTO-started session publish).
            //
            // What IS done here is the optimistic overlay: the attempt is recorded
            // BEFORE the callable is issued so the control flips immediately, and
            // it is resolved the moment the callable answers — settled (wait for
            // the session to echo back) or failed (take the STOP sign back and say
            // so). [LiveShareStart.request] returning false is the double-tap
            // guard: a second tap while the first is in flight issues nothing.
            fun startSingleSession(
                duration: LiveSessionDuration,
                // The garage car the user picked in the "Start driving" popup;
                // null (the default for every other caller — the map toggle, the
                // convoy-end transfer) lets the server pick their main car.
                vehicleId: String? = null,
            ) {
                val c = liveLocationCoordinator ?: return
                if (!LiveShareStart.request(nowMillis(), observedSharing = isSharing)) return
                // A fresh start must never be hidden by a stale optimistic-stop
                // overlay from the session the user just ended: clear it so the new
                // STOP sign shows at once (isSharingUi subtracts a live stop attempt).
                LiveShareStop.clear()
                scope.launch {
                    val result =
                        try {
                            c.start(duration, vehicleId)
                        } catch (cancellation: CancellationException) {
                            // The scope died mid-start (sign-out, Activity teardown):
                            // nothing will resolve this attempt, so drop it rather
                            // than leave a STOP sign waiting on the timeout.
                            LiveShareStart.failed()
                            throw cancellation
                        }
                    when (result) {
                        LiveCommandResult.Success ->
                            // Issued and accepted: hold the overlay for the short
                            // echo window while the session finds its way down.
                            LiveShareStart.settled(nowMillis())
                        LiveCommandResult.Failed, LiveCommandResult.Busy ->
                            // Busy belongs HERE, not with Success: the coordinator
                            // short-circuits on a command already in flight, so
                            // startSession was never called and there is no session
                            // coming. Holding the overlay for the echo window would
                            // show a STOP sign for something that never started.
                            //
                            // Only speak up if the attempt was still ours: a start
                            // that had already timed out has said this once.
                            if (LiveShareStart.failed()) {
                                snackbarHostState.showSnackbar(liveErrorText)
                            }
                    }
                }
            }

            // Start the Single session IMMEDIATELY — no time/duration choice is
            // shown. When a start is actually possible we begin sharing at once
            // for the 6h default window; otherwise fall back to the live screen /
            // unavailable snackbar (same gate as the toggle). The session
            // auto-stops at 6h with no prolong prompt, and Stop is always
            // available, so a fixed default is fine.
            fun requestStartSingleSession(vehicleId: String? = null) {
                if (liveLocationCoordinator != null && canShareLive) {
                    startSingleSession(LiveLocation.DEFAULT_SESSION_DURATION, vehicleId)
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
                // The member is ending their own session, so the end-of-session
                // save prompt stays neutral even on a convoy-auto session — they
                // know why it stopped (see [savePromptReason]).
                userEndedSession = true
                // Drop any optimistic "starting…" overlay first: stopping while a
                // start is still in flight must return the control to "+" at once,
                // not keep a STOP sign alive on an attempt the user just abandoned.
                // (If that start does land server-side, the observed session brings
                // the STOP sign back and this stop path is available again.)
                LiveShareStart.clear()
                val c = liveLocationCoordinator
                if (c != null) {
                    // Optimistic STOP: hide the sharing chrome on the NEXT frame
                    // (#798) instead of after the stopped session echoes back — BUT
                    // only claim the overlay when this tap can actually INITIATE a
                    // stop. If a live command is already in flight, c.stop() will
                    // short-circuit as Busy without issuing anything; hiding the
                    // chrome for it would say "not sharing" while the session is
                    // still live (a privacy leak — e.g. tapping Stop while a Start is
                    // in flight). A genuine stop double-tap already claimed the
                    // overlay on its first tap, so the chrome stays correctly hidden.
                    val initiatedStop = OptimisticLiveStop.claimsStop(c.status.value == LiveActionStatus.Working)
                    if (initiatedStop) LiveShareStop.request(nowMillis())
                    scope.launch {
                        val result =
                            try {
                                c.stop()
                            } catch (cancellation: CancellationException) {
                                // The scope died mid-stop (sign-out, Activity
                                // teardown): nothing will resolve this attempt, so
                                // drop it rather than hide a session on a stop that
                                // never ran.
                                if (initiatedStop) LiveShareStop.failed()
                                throw cancellation
                            }
                        when (result) {
                            LiveCommandResult.Success ->
                                // Issued and accepted: hold the overlay for the
                                // short echo window while the stopped session finds
                                // its way down.
                                if (initiatedStop) LiveShareStop.settled(nowMillis())
                            LiveCommandResult.Failed ->
                                // The stop genuinely failed: revert so the STOP sign
                                // returns rather than hiding a still-live session.
                                // Only speak up if the attempt was still ours.
                                if (initiatedStop && LiveShareStop.failed()) {
                                    snackbarHostState.showSnackbar(liveErrorText)
                                }
                            LiveCommandResult.Busy ->
                                // No stop was issued (a command was already in
                                // flight). Never leave the chrome hidden over a
                                // still-live session: revert our optimistic attempt
                                // if a rare race (Working between the check and
                                // c.stop()) let us claim one. When we did not claim
                                // (initiatedStop == false) there is nothing to
                                // revert — the first tap's overlay, if any, stands.
                                if (initiatedStop) LiveShareStop.failed()
                        }
                    }
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
                        // The UI state, so a toggle tapped during an optimistic
                        // start reads as Stop (matching what is on screen) rather
                        // than firing a second Start.
                        isSharing = isSharingUi,
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
                // The surface's own layer state, read here so the turn-by-turn
                // layers popup can show it. MapHome collects the same two flows
                // for its own copy of the popup; these are the SAME flows, so the
                // two popups cannot disagree about what is switched on.
                val mapTrafficOn by mapSurface.trafficEnabled.collectAsState()
                val mapIs3d by mapSurface.is3d.collectAsState()

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
                        // Durably record the in-progress navigation so a process
                        // death can offer to resume it. Refreshed each start, so
                        // the staleness clock tracks the latest session. Only the
                        // in-app SDK path persists — the external-maps handoff owns
                        // no session of ours to resume.
                        navResumeCandidate = null
                        navResumeStore.save(
                            ActiveNavigation(
                                destination = dest,
                                label = label,
                                startedAtMillis = System.currentTimeMillis(),
                            ),
                        )
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
                // Refreshes the bar's roster profiles from live users/{uid}; see
                // ConvoyCoordinator.liveProfiles. Unlike the destination repository
                // above this needs no `remember`: sharedOrEmpty returns the
                // process-wide instance (or the EMPTY singleton), so it is already
                // stable and cannot rebuild the coordinator on recomposition.
                val convoyLiveProfiles =
                    FirebaseLiveProfileRepository.sharedOrEmpty(LocalContext.current)
                val convoyBarCoordinator =
                    remember(convoyRepository, convoyDestinationRepository, convoyLiveProfiles) {
                        convoyRepository?.let {
                            ConvoyCoordinator(it, convoyDestinationRepository, convoyLiveProfiles)
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

                // Unread count for the convoy bar's chat badge.
                //
                // Hoisted here — beside the bar's own state and NOT inside the bar —
                // for the same reason communityChatUnread is hoisted above: this is
                // the always-visible map shell, so a listener started here runs for
                // the whole session unless something narrows it. Two things do.
                //
                //  - The ACTIVE CONVOY, and only it. The id is the same one
                //    `ConvoyBar.stateFor` picks below, so the count can never
                //    describe a different convoy than the badge sits on. It goes
                //    null the moment the convoy ends or the caller leaves, which
                //    tears the listener down with it — no per-convoy fan-out, and
                //    nothing left running for a convoy that is over.
                //  - Whether the bar is actually ON SCREEN: the uncovered map home,
                //    or turn-by-turn (the other full-screen map that hosts the bar).
                //    Behind a translucent panel or an opaque route the badge cannot
                //    be read, so the flow degrades to a constant 0 and both
                //    listeners detach — the same "don't subscribe a chat nobody is
                //    looking at" rule the off-screen chat tabs already follow.
                //
                // Guarded: no repository (config-less build) means no count, and the
                // bar then renders no chat control at all (see onOpenChat below).
                val convoyBarConvoyId = ConvoyBar.activeConvoy(convoyBarStatus)?.convoyId
                val convoyBarOnScreen = mapCover == MapCover.None || navDestination != null
                val convoyChatUnread by
                    remember(convoyChatRepository, uid, convoyBarConvoyId, convoyBarOnScreen) {
                        if (convoyChatRepository != null &&
                            convoyBarConvoyId != null &&
                            convoyBarOnScreen
                        ) {
                            convoyChatRepository.observeUnread(convoyBarConvoyId, uid)
                        } else {
                            flowOf(0)
                        }
                    }
                        .collectAsState(initial = 0)

                val convoyBarState =
                    ConvoyBar.stateFor(convoyBarStatus, convoyBarBusy, uid, convoyChatUnread)

                // The convoy this map is actively DRIVING (Active, not merely
                // Forming), shared by the live-session stop flow below. Two uses:
                //  - it latches the #726 recording-stop guard far above (a rotation
                //    while in a convoy must not raise the drive save prompt), and
                //  - it decides whether ending the live session must first ask what
                //    to do with the convoy (end it for everyone vs leave it
                //    running) instead of orphaning an active convoy.
                val liveStopActiveConvoy =
                    ConvoyBar.activeConvoy(convoyBarStatus)
                        ?.takeIf { it.status == ConvoyStatus.Active }
                // Whether the convoy snapshot has actually answered yet: only then
                // may the latch drop to false. During the recreation window it is
                // Loading, so the latch keeps its restored value and bridges the
                // rotation. A config-less build has no coordinator and no convoy,
                // so it counts as loaded (the latch simply stays false).
                val convoyStopLoaded =
                    convoyBarCoordinator == null || convoyBarStatus !is ConvoyListStatus.Loading
                LaunchedEffect(convoyStopLoaded, liveStopActiveConvoy != null) {
                    if (convoyStopLoaded) convoyActiveLatched = liveStopActiveConvoy != null
                }

                // The convoy facts the notification inbox re-derives its convoy
                // rows against.
                //
                // COST: nothing. This is a projection of the snapshot the convoy
                // BAR is already holding — one `convoy-list` call the shell makes
                // on entry and refreshes when the map comes back to the front —
                // so an inbox of any length resolves for zero extra reads, with
                // no per-row fetch and no second listener. The trade is
                // staleness, and it is deliberately one-directional: a convoy
                // that ended since the last load still looks live, so the worst
                // case is a row that stays tappable and is then told the truth on
                // arrival (see ConvoyInviteDeepLink). The opposite error —
                // striking through a live invite because we happen to hold no
                // facts — is the one that must not happen, which is why an
                // unknown convoy resolves to UNRESOLVED and not to ENDED.
                val convoyNotificationFacts: Map<String, ConvoyFacts> =
                    remember(convoyBarStatus) {
                        (convoyBarStatus as? ConvoyListStatus.Loaded)
                            ?.convoys
                            ?.associate { convoy ->
                                val ended = convoy.status == ConvoyStatus.Ended
                                convoy.convoyId to
                                    ConvoyFacts(
                                        ended = ended,
                                        inviteOpen =
                                            !ended &&
                                                convoy.viewer?.inviteStatus ==
                                                ConvoyInviteStatus.Invited,
                                    )
                            }
                            .orEmpty()
                    }

                // Tapping a convoy row in the inbox. Not remembered: it closes
                // over shell state that changes, and it is a two-field data class
                // whose expensive half (the facts map) is remembered above.
                //
                // The convoy list is the EXISTING invite/respond UI (its
                // pending-invite section is wired to convoy-respond), so this
                // opens that — no parallel invite screen is introduced. A null
                // convoy id still opens it: the invite is on that list either
                // way, which is the honest degradation when the id is missing.
                val convoyNotificationLink =
                    ConvoyNotificationLink(
                        facts = convoyNotificationFacts,
                        onOpen = { action ->
                            when (action) {
                                is NotificationTapAction.OpenConvoyInvite -> {
                                    // The inbox can be the chat hub POPUP's tab,
                                    // and the popup's gate only holds while no
                                    // route is open. Close it in the same frame
                                    // as the navigation rather than leaning on
                                    // the auto-close effect — the same rule the
                                    // hub's onViewProfile already follows.
                                    chatHubOpen = false
                                    pendingConvoyInviteId = action.convoyId
                                    openRootRoute(ShellRoute.Convoys)
                                }
                                // The screen never dispatches these (it only
                                // calls onOpen for actions that navigate); the
                                // branch keeps the when exhaustive.
                                NotificationTapAction.ConvoyEnded,
                                NotificationTapAction.None,
                                -> Unit
                            }
                        },
                    )

                // Tapping an event notification (event_created and the other event
                // categories) in the inbox opens that event's detail — the same
                // pendingEventDeepLinkId → Events-route mechanism a PushTarget.EVENT
                // tap uses, so the in-app inbox matches the push. Closes the chat
                // hub popup in the same frame as the navigation (its gate only holds
                // while no route is open), exactly as the convoy onOpen above does.
                val openEventFromNotification: (String) -> Unit = { eventId ->
                    chatHubOpen = false
                    pendingEventDeepLinkId = eventId
                    openRootRoute(ShellRoute.Events)
                }

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
                // Removes the caller from [convoyId] (Leave, confirmed in the bar
                // before this runs). Any accepted member, the LEADER included.
                val leaveConvoy: (String) -> Unit = { convoyId ->
                    convoyBarCoordinator?.let { c -> scope.launch { c.leave(convoyId) } }
                }

                // What the caller's own exit DID, said once.
                //
                // It cannot be read off the refreshed snapshot: after a successful
                // leave the convoy simply drops out of the caller's list, which
                // looks identical whether the others are still driving, the convoy
                // ended behind them, or they handed leadership on. Only the server
                // knows which, so its answer is what is shown — and it is cleared
                // as soon as it has been, so it never re-appears on recomposition.
                val convoyLeftNotice by
                    (convoyBarCoordinator?.leftNotice ?: remember { MutableStateFlow(null) })
                        .collectAsState()
                val convoyLeftMessage =
                    convoyLeftNotice?.let { notice ->
                        stringResource(
                            when {
                                notice.outcome == ConvoyLeaveOutcome.LeftAndEnded ->
                                    R.string.convoy_leftAndEndedToast
                                notice.transferredLeadership -> R.string.convoy_leftAsLeaderToast
                                else -> R.string.convoy_leftConvoyToast
                            },
                        )
                    }
                LaunchedEffect(convoyLeftNotice) {
                    val message = convoyLeftMessage ?: return@LaunchedEffect
                    convoyBarCoordinator?.clearLeftNotice()
                    snackbarHostState.showSnackbar(message)
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

                // ONE answer to "is there anybody to draw?", used by all THREE
                // overlay slots below — the map home's convoy layer, its nearby
                // layer, and turn-by-turn's combined layer. Navigation showing a
                // different set of people from the map is exactly the bug being
                // fixed, so the decision is not restated per call site. Both
                // rosters are the host's existing, already-gated ones (see
                // [LiveMapLayers]); no navigation-specific source, no second
                // subscription.
                val liveLayerPlan =
                    LiveMapLayers.plan(
                        convoyMemberCount = convoyMemberPositions.size,
                        nearbySharerCount = nearbyLiveMarkers.size,
                    )

                // Composes nothing at all unless there is somebody to draw, so a
                // convoy where nobody is sharing yet adds no layer to the map.
                // Declared here rather than beside the convoy roster above so all
                // THREE overlay slots read the one [liveLayerPlan] and cannot
                // disagree about who is on the map.
                val convoyOverlaySlot: (@Composable () -> Unit)? =
                    if (liveLayerPlan.convoy) {
                        {
                            ConvoyMapAwarenessOverlay(
                                mapSurface = mapSurface,
                                members = convoyMemberPositions,
                            )
                        }
                    } else {
                        null
                    }

                // Composes nothing at all unless somebody nearby is actually
                // sharing (and visible), so an empty neighbourhood adds no layer.
                val nearbyOverlaySlot: (@Composable () -> Unit)? =
                    if (liveLayerPlan.nearby) {
                        {
                            NearbyLiveOverlay(
                                mapSurface = mapSurface,
                                sharers = nearbyLiveMarkers,
                            )
                        }
                    } else {
                        null
                    }

                // The SAME live-member layers the map home draws, but bound to
                // whatever map is asking rather than to the shell surface. That
                // binding is the whole fix for "I couldn't see other people
                // sharing their live location in Navigation": turn-by-turn owns a
                // second, Navigation-SDK MapView, and every marker above was
                // projected against `mapSurface`, which is stood down the moment
                // navigation starts — so there was simply nothing drawing them.
                //
                // Deliberately the same values (`convoyMemberPositions`,
                // `nearbyLiveMarkers`), the same per-uid RTDB listeners already
                // open above, and therefore the same entitlement gating: this
                // opens no second subscription and grants no visibility the map
                // home does not already have.
                val liveMembersOverlaySlot: (@Composable (MapProjection) -> Unit)? =
                    if (liveLayerPlan.any) {
                        { projection ->
                            if (liveLayerPlan.convoy) {
                                ConvoyMapAwarenessOverlay(
                                    mapSurface = projection,
                                    members = convoyMemberPositions,
                                )
                            }
                            if (liveLayerPlan.nearby) {
                                NearbyLiveOverlay(
                                    mapSurface = projection,
                                    sharers = nearbyLiveMarkers,
                                )
                            }
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
                                // The LEADER's End — group-wide, and only ever
                                // reached from the leader's own exit choice or
                                // chooser (the bar decides; see ConvoyExitChoice).
                                onEndConvoy = { convoyId ->
                                    scope.launch { convoyBarCoordinator.end(convoyId) }
                                },
                                // Opens the friend picker and grows THIS convoy via
                                // `convoy-invite`.
                                onInvite = openConvoyInvite,
                                // The convoy's CHAT: opens the same chat hub the
                                // map's chat bubble opens — no second chat
                                // presentation — but landed on this convoy's
                                // channel via the hub's existing deep-link
                                // parameter. Opening it is what marks the channel
                                // read (ConvoyChannelRoute), so the badge clears
                                // through the ordinary path rather than a second
                                // one that could disagree with it. Null in a
                                // config-less build, which omits the control.
                                onOpenChat =
                                    if (convoyChatRepository != null) {
                                        { convoyId ->
                                            chatHubLandingLink =
                                                PushDeepLink(PushTarget.CONVOY_CHAT, convoyId)
                                            chatHubOpen = true
                                        }
                                    } else {
                                        null
                                    },
                                // Leave via `convoy-leave`, confirmed in the bar.
                                // Wired for EVERY accepted member now, the leader
                                // included — leaving is one of the two exits, not
                                // the non-leader's consolation prize, and the
                                // server transfers leadership rather than refusing
                                // a leader who wants out. A non-leader's tap is
                                // still routed here by ConvoyExitChoice, so it can
                                // never reach the group-wide End path.
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
                                // Tap a JOINED member → their read-only profile
                                // (the same route friend/chat rows open), or centre
                                // the map on their live marker.
                                onOpenMemberProfile =
                                    if (memberProfileRepository != null) {
                                        openMemberProfile
                                    } else {
                                        null
                                    },
                                // Only on the full map home — the turn-by-turn
                                // variant's camera is the Navigation SDK's, not this
                                // surface's. Centres on the member's current shared
                                // position; a member not sharing one is not in
                                // [memberLocations] and the action is offered
                                // disabled.
                                onGoToMemberLocation =
                                    if (!compact) {
                                        { targetUid ->
                                            convoyMemberPositions
                                                .firstOrNull { it.uid == targetUid }
                                                ?.let { pos ->
                                                    mapSurface.centerOn(
                                                        MapPoint(
                                                            longitude = pos.longitude,
                                                            latitude = pos.latitude,
                                                        ),
                                                    )
                                                }
                                        }
                                    } else {
                                        null
                                    },
                                memberLocations =
                                    convoyMemberPositions.map { it.uid }.toSet(),
                            )
                        }
                    } else {
                        null
                    }

                // The one true way out of navigation: tears down the nav view AND
                // forgets the persisted record, so a user-confirmed exit can never
                // later resurface as a "continue navigation?" prompt. Shared by the
                // back-key confirm and by every other exit affordance.
                val performNavExit: () -> Unit = {
                    navDestination = null
                    navDestinationLabel = ""
                    navResumeStore.clear()
                    showExitNavConfirm = false
                    mapSurface.setRouteOverlay(null)
                    navSearchOpen = false
                    // Closing the underlying picker: drop any change-address
                    // context so the next open starts uncontextualized.
                    navSearchInitialEdit = null
                }

                // One-shot on entry: if a previous session was navigating and was
                // interrupted (process death / cold start) rather than exited, and
                // the record is fresh and not already running, stand up the resume
                // offer. A stale or malformed leftover is cleared instead of shown.
                //
                // Only on an in-app-SDK build: resume re-enters in-app navigation
                // (startNavigationTo's NAV_SDK path), and only that path persists a
                // record in the first place. On the token-less noNav build we never
                // offer — a leftover record from a previous SDK-enabled install
                // stays untouched rather than surfacing a "continue" the external-
                // maps handoff could neither fulfil nor clear.
                LaunchedEffect(Unit) {
                    if (!BuildConfig.NAV_SDK_ENABLED) return@LaunchedEffect
                    val saved = navResumeStore.read()
                    val now = System.currentTimeMillis()
                    when {
                        NavResumePolicy.shouldOfferResume(
                            persisted = saved,
                            nowMillis = now,
                            currentlyNavigating = navDestination != null,
                        ) -> navResumeCandidate = saved
                        saved != null && navDestination == null -> navResumeStore.clear()
                    }
                }

                // BACK-key exit confirm. Shown over the live nav view; a stray back
                // press lands here instead of ending navigation. Cancel keeps
                // driving; Exit runs the shared teardown (which also forgets the
                // resume record, so a confirmed exit never re-offers).
                if (showExitNavConfirm && navDestination != null) {
                    AlertDialog(
                        onDismissRequest = { showExitNavConfirm = false },
                        title = { Text(stringResource(R.string.turnByTurn_exitConfirmTitle)) },
                        text = { Text(stringResource(R.string.turnByTurn_exitConfirmBody)) },
                        confirmButton = {
                            TextButton(onClick = performNavExit) {
                                Text(stringResource(R.string.turnByTurn_exitConfirmConfirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showExitNavConfirm = false }) {
                                Text(stringResource(R.string.turnByTurn_exitConfirmCancel))
                            }
                        },
                    )
                }

                // Resume-last-navigation offer. Only when we are NOT already
                // navigating. Continue re-enters navigation to the saved
                // destination (which re-persists a fresh record); Dismiss forgets
                // it so it is asked once, not on every launch.
                val resumeCandidate = navResumeCandidate
                if (resumeCandidate != null && navDestination == null) {
                    AlertDialog(
                        onDismissRequest = {
                            navResumeCandidate = null
                            navResumeStore.clear()
                        },
                        title = { Text(stringResource(R.string.turnByTurn_resumeTitle)) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.turnByTurn_resumeBody,
                                    resumeCandidate.label,
                                ),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    navResumeCandidate = null
                                    startNavigationTo(
                                        resumeCandidate.destination,
                                        resumeCandidate.label,
                                    )
                                },
                            ) {
                                Text(stringResource(R.string.turnByTurn_resumeConfirm))
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    navResumeCandidate = null
                                    navResumeStore.clear()
                                },
                            ) {
                                Text(stringResource(R.string.turnByTurn_resumeDismiss))
                            }
                        },
                    )
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
                        onExit = performNavExit,
                        // BACK while driving asks first — a stray back press (or the
                        // muscle-memory swipe on the way back from a text) must not
                        // silently end navigation. The confirm dialog is rendered
                        // below, in this main module, so it stays locally testable.
                        onBackPressed = { showExitNavConfirm = true },
                        onReportIncident = reportIncident,
                        modifier = Modifier.fillMaxSize(),
                        incidentReportingEnabled = incidentReportingEnabled,
                        // The layers popup's rows, wired to the SAME state the map
                        // home's are, so a toggle flipped while driving is the
                        // same toggle when the user gets back to the map. All
                        // four now take effect on the navigation map itself —
                        // night, traffic and 3D always did, and "Traffic alerts"
                        // does since the incident badges are drawn there too.
                        incidentsLayerEnabled = incidentsLayerEnabled,
                        onIncidentsLayerEnabledChange = { incidentsLayerEnabled = it },
                        // The SAME badge list the map home draws, on the
                        // navigation map's own layer. No second query and no
                        // second renderer — the screen hands it to the shared
                        // IncidentMarkerLayer the shell surface uses.
                        incidentMarkers = incidentMarkers,
                        // ...and the navigation camera becomes the anchor for the
                        // one incident poll while it exists, so the layer keeps
                        // up with the driver instead of with the stood-down shell
                        // map. Cleared to null on exit.
                        onQueryViewport = { navQueryViewport.value = it },
                        trafikverketDataShown = trafikverketDataShown,
                        trafficEnabled = mapTrafficOn,
                        onTrafficEnabledChange = { mapSurface.setTrafficEnabled(it) },
                        nightMode = mapNightModeOverride.value?.let { it == MapMode.Night },
                        onNightModeChange = { on ->
                            val mode = if (on) MapMode.Night else MapMode.Day
                            mapNightModeOverride.value = mode
                            // Apply to the shell surface too, so the map home is
                            // already in the chosen mode when navigation exits.
                            mapSurface.setMapMode(mode)
                        },
                        is3d = mapIs3d,
                        on3dEnabledChange = { mapSurface.set3dEnabled(it) },
                        // The map home's chat bubble + unread badge, opening the
                        // same hub popup.
                        unreadChatCount = if (communityChatUnread) 1 else 0,
                        onOpenChat = { chatHubOpen = true },
                        // The map home's saved-places control, on the same shared
                        // stack; opens the same saved-locations picker.
                        onOpenSavedPlaces = { savedPlacesPickerOpen = true },
                        // The ongoing live-session pill (elapsed + distance +
                        // speed). Starting turn-by-turn navigation used to hide it
                        // along with the whole map-home chrome; a session that is
                        // still sharing must keep its indicator on screen while
                        // driving, so it is handed to the nav screen's top chrome
                        // exactly as the convoy bar is.
                        liveSessionBar = liveSessionBarSlot,
                        // Compact variant below the maneuver banner, WITH the
                        // shared-destination row (turn-by-turn has the vertical room
                        // for it — see the screen's KDoc).
                        convoyBar = convoyBarSlot?.let { bar -> { bar(true, true) } },
                        // Convoy members + nearby public sharers, drawn on the
                        // NAVIGATION map's own projection.
                        liveMembersOverlay = liveMembersOverlaySlot,
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
                    // Every pushed full-screen sub-route renders through RouteHost.
                    // Providing LocalAeroBackAvailable = true here — and ONLY here —
                    // makes each Aero page show its pinned in-app Back arrow, so
                    // gesture-nav users (no visible system Back button) can always go
                    // back. Tab roots and the translucent panels don't go through
                    // RouteHost, so they keep the default (false) and stay arrow-free.
                    CompositionLocalProvider(LocalAeroBackAvailable provides true) {
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
                        onUserEndLiveSession = { userEndedSession = true },
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
                        // Chat geo-link tap → show that point on the map, in-app.
                        onShowLocationOnMap = { lat, lng ->
                            moveMapToPoint(lat, lng, sharedLocationName)
                        },
                        // Event Navigate → the SAME in-app navigate-to-point flow,
                        // carrying the event's own label rather than a shared-name.
                        onNavigateToPoint = moveMapToPoint,
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
                        badgeProgressRepository = badgeProgressRepository,
                        blockingRepository = blockingRepository,
                        friendsRepository = friendsRepository,
                        memberProfileRepository = memberProfileRepository,
                        memberProfileTargetUid = memberProfileTargetUid,
                        onOpenMemberProfile = openMemberProfile,
                        dmRepository = dmRepository,
                        convoyRepository = convoyRepository,
                        convoyOpenCreate = convoyOpenCreate,
                        convoyCreateVehicleId = pendingConvoyVehicleId,
                        // A newly created convoy shows no confirmation page: close the
                        // convoy overlay and land on the map, where the bar reflects
                        // the new (active) convoy. Clear the deep-link flag so a later
                        // re-entry from the Social hub opens list-first as normal.
                        onConvoyCreated = {
                            convoyOpenCreate = false
                            // The picked car has been handed to the create call; drop
                            // it so a later convoy created without the popup falls
                            // back to the owner's main car.
                            pendingConvoyVehicleId = null
                            clearRoutes()
                            selectedTab = ShellTab.Map
                        },
                        // Accepting an invite lands the same way a create does —
                        // no confirmation page, straight to the map — and
                        // additionally asks the camera to frame the group, which
                        // is the thing the member just joined and the only reason
                        // they are being taken here.
                        //
                        // A REQUEST rather than setMode: the accepted convoy is
                        // not the active convoy until the bar's coordinator
                        // refreshes on return to the map, and that refresh resets
                        // the mode (see ConvoyFocusStore). The store consumes the
                        // request when the convoy arrives. Nothing degenerate can
                        // come of it — with no other member's position known the
                        // planner keeps plain follow-me (ConvoyFocusPlanner.plan),
                        // and the camera reframes by itself the moment somebody
                        // does start sharing.
                        //
                        // selectedTab is set unconditionally, so accepting while
                        // another tab was underneath still lands on the map.
                        onConvoyJoined = { convoyId ->
                            convoyOpenCreate = false
                            convoyFocusStore.requestConvoyFocusOnJoin(convoyId)
                            clearRoutes()
                            selectedTab = ShellTab.Map
                        },
                        chatHubPushLink = pendingChatHubLink,
                        eventDeepLinkId = pendingEventDeepLinkId,
                        onEventDeepLinkConsumed = { pendingEventDeepLinkId = null },
                        convoyInviteDeepLinkId = pendingConvoyInviteId,
                        onConvoyInviteDeepLinkConsumed = { pendingConvoyInviteId = null },
                        convoyNotificationLink = convoyNotificationLink,
                        openEventFromNotification = openEventFromNotification,
                        communityChatRepository = communityChatRepository,
                        communityChatUnread = communityChatUnread,
                        convoyChatRepository = convoyChatRepository,
                        dmChatOtherUid = dmChatOtherUid,
                        dmChatOtherName = dmChatOtherName,
                        onOpenChat = openChat,
                        pointsRepository = pointsRepository,
                        drivesRepository = drivesRepository,
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
                        // "Share" a saved place: hand its resolved name + point to
                        // the shared friend picker hosted on the map screen.
                        onShareLocation = { name, point ->
                            shareLocationTarget = ShareableLocation(name, point)
                        },
                    )
                    }
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
                                    // Shell-owned + store-backed so the compass
                                    // pick survives route changes AND a cold
                                    // restart; default is course-up (see the
                                    // declaration above).
                                    compassModeState = mapCompassMode,
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
                                    // The saved-places control opens the saved-
                                    // locations picker; a pick moves the map via
                                    // the shared in-app move-to-point flow. (The
                                    // dedicated recenter button was removed: the
                                    // compass control re-centres on the user and
                                    // there is a ~10s idle auto-return.)
                                    onOpenSavedPlaces = { savedPlacesPickerOpen = true },
                                    // The point the place menu is anchored on,
                                    // drawn as an animated pin while the menu is
                                    // open; null hides the pin.
                                    droppedPin =
                                        placeMenuTarget?.point?.let {
                                            LatLng(longitude = it.longitude, latitude = it.latitude)
                                        },
                                    // The top-right profile button opens the
                                    // account menu as a transparent Popup
                                    // *over* the map (map stays visible)
                                    // rather than navigating to a full-screen
                                    // hub. Each entry still navigates to its
                                    // own full route (or signs out).
                                    moreMenuEntries =
                                        profileMenuEntries(
                                            profileEditCoordinator = profileEditCoordinator,
                                            friendsRepository = friendsRepository,
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
                                    // Sponsored billboards, drawn as their own
                                    // layer with their own tap intent. Already
                                    // filtered to what the server says is
                                    // map-visible AND to what the schedule
                                    // allows right now; empty when the
                                    // digitalBillboards flag is off.
                                    billboardMarkers = mapBillboardMarkers,
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
                                        // "Nej, den är borta". Takes a FRESH
                                        // high-accuracy fix at the moment of the tap —
                                        // reusing the cached last-known position that
                                        // gated the button would be exactly the stale
                                        // sample the backend's freshness check exists
                                        // to reject, and would also be weaker evidence
                                        // than the member deserves credit for.
                                        onReportCleared = {
                                            val controller = incidentController
                                            if (controller != null && !incidentClearInFlight) {
                                                incidentClearInFlight = true
                                                scope.launch {
                                                    val outcome =
                                                        try {
                                                            runIncidentClearVote(
                                                                controller = controller,
                                                                mapSurface = mapSurface,
                                                                incidentId = openIncident.id,
                                                                fixProvider = {
                                                                    currentIncidentClearFix(
                                                                        context,
                                                                    )
                                                                },
                                                            )
                                                        } finally {
                                                            incidentClearInFlight = false
                                                        }
                                                    snackbarHostState.showSnackbar(
                                                        when (outcome) {
                                                            is ClearOutcome.Success ->
                                                                when {
                                                                    outcome.alreadyVoted ->
                                                                        incidentClearedAlreadyText
                                                                    outcome.removed ->
                                                                        incidentClearedRemovedText
                                                                    else ->
                                                                        incidentClearedSuccessText
                                                                }
                                                            ClearOutcome.NoLocation ->
                                                                incidentClearedNoLocationText
                                                            // Each rejection says what
                                                            // would actually make it
                                                            // work; only the anti-fraud
                                                            // one is deliberately vague,
                                                            // because naming the signal
                                                            // that tripped would tell an
                                                            // abuser what to change.
                                                            is ClearOutcome.Rejected ->
                                                                when (outcome.rejection) {
                                                                    IncidentClearRejection
                                                                        .OUT_OF_RANGE,
                                                                    ->
                                                                        incidentClearedTooFarText
                                                                    IncidentClearRejection
                                                                        .IMPORTED,
                                                                    ->
                                                                        incidentClearedImportedText
                                                                    IncidentClearRejection
                                                                        .POSITION_TOO_OLD,
                                                                    IncidentClearRejection.INACTIVE,
                                                                    IncidentClearRejection
                                                                        .NOT_COUNTED,
                                                                    ->
                                                                        incidentClearedErrorText
                                                                }
                                                            is ClearOutcome.Failed ->
                                                                incidentClearedErrorText
                                                        },
                                                    )
                                                }
                                            }
                                        },
                                        clearEligibility =
                                            IncidentDetails.clearVoteEligibility(
                                                incident = openIncident,
                                                viewerLocation = incidentViewerLocation,
                                            ),
                                        removeInProgress = incidentRemoveInFlight,
                                        confirmInProgress = incidentConfirmInFlight,
                                        clearInProgress = incidentClearInFlight,
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
                                val crownNavLabel =
                                    stringResource(R.string.crownHunt_navDestinationCrown)
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
                                                    // Count THIS tap when it was
                                                    // refused for want of a dwell
                                                    // proof — collect() resolved it
                                                    // locally without a callable, so
                                                    // only the attempt path can see
                                                    // it. The tracker reports once,
                                                    // past its threshold.
                                                    if (controller.claimStatus.value ==
                                                        CrownClaimStatus.NeedsPosition
                                                    ) {
                                                        reportCrownCollectRefused()
                                                    }
                                                }
                                            }
                                        },
                                        onNavigate = {
                                            // Drive to the crown through the app's ONE
                                            // navigate-to-a-point flow, then close the
                                            // popup so navigation is not competing with
                                            // an open sheet.
                                            startNavigationTo(
                                                LatLng(
                                                    longitude = crownForPopup.longitude,
                                                    latitude = crownForPopup.latitude,
                                                ),
                                                crownNavLabel,
                                            )
                                            crownSpawnController?.resetClaim()
                                            mapSurface.consumeCrownTap()
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
                                // Tapping a HAND-PLACED admin Kronjakt point opens
                                // its own popup: name, reward, and a Collect button
                                // whose eligibility (geofence, cooldown, daily cap)
                                // the backend owns. A single fresh fix is fetched at
                                // tap-collect time — an admin point needs no dwell
                                // proof — and the localized result is shown in place.
                                val pointForPopup = openCrownPoint
                                if (pointForPopup != null && adminCrownsVisible) {
                                    CrownPointPopup(
                                        point = pointForPopup,
                                        status = crownPointClaimStatus,
                                        // In range iff the range poll placed this
                                        // point in the in-range set (false with no
                                        // fix yet — the button waits, with a hint,
                                        // rather than looking live and being refused).
                                        collectInRange =
                                            inRangePointIds?.contains(pointForPopup.id) == true,
                                        onNavigate = {
                                            val lat = pointForPopup.latitude
                                            val lon = pointForPopup.longitude
                                            if (lat != null && lon != null) {
                                                startNavigationTo(
                                                    LatLng(longitude = lon, latitude = lat),
                                                    pointForPopup.title,
                                                )
                                            }
                                            crownHuntCoordinator?.reset()
                                            mapSurface.consumeCrownTap()
                                        },
                                        onCollect = {
                                            crownHuntCoordinator?.let { coordinator ->
                                                scope.launch {
                                                    val fix =
                                                        CrownLocation.currentFix(
                                                            context.applicationContext,
                                                        )
                                                    val coordinate =
                                                        fix?.let {
                                                            ClaimCoordinate(
                                                                latitude = it.latitude,
                                                                longitude = it.longitude,
                                                                recordedAtIso =
                                                                    java.time.Instant
                                                                        .ofEpochMilli(
                                                                            it.recordedAtMillis,
                                                                        )
                                                                        .toString(),
                                                                speedMetersPerSecond =
                                                                    it.speedMetersPerSecond,
                                                                accuracyMeters = it.accuracyMeters,
                                                            )
                                                        }
                                                    coordinator.claim(
                                                        pointForPopup.id,
                                                        coordinate,
                                                        // Same key across retries on
                                                        // this point: the backend
                                                        // de-duplicates, so a retry
                                                        // costs one attempt, not two.
                                                        crownIdempotencyKey,
                                                    )
                                                }
                                            }
                                        },
                                        onDismiss = {
                                            // Clear the shared coordinator result as
                                            // well as the tap, so an outcome left
                                            // behind cannot greet the next point (or
                                            // the hub screen) the member opens.
                                            crownHuntCoordinator?.reset()
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
                                // Tapping a sponsored billboard opens its popup
                                // (sponsorship label, headline, message, and the
                                // partner link when there is one). Composed in
                                // the same map-chrome subtree as the incident
                                // sheet and the event popup, so a tab switch
                                // takes it out of the semantics tree.
                                //
                                // Rendered only while the tapped id still
                                // resolves to a currently-visible billboard: one
                                // that an admin pauses, or whose window closes,
                                // while the popup is open takes the popup with
                                // it rather than leaving an advert on screen the
                                // member is no longer meant to be shown.
                                val openBillboard = tappedBillboard
                                if (openBillboard != null) {
                                    val action =
                                        remember(openBillboard) {
                                            BillboardCallToAction.resolve(openBillboard)
                                        }
                                    // The `open` impression, recorded once per
                                    // opened billboard (keyed on the id, so
                                    // recompositions do not re-report it). Best
                                    // effort by design — the callable's own
                                    // contract is that analytics never block the
                                    // member's action, so a failure here must
                                    // not surface or retry.
                                    LaunchedEffect(openBillboard.id, billboardsRepository) {
                                        runCatching {
                                            billboardsRepository?.recordInteraction(
                                                openBillboard.id,
                                                BillboardInteractionType.OPEN,
                                            )
                                        }
                                    }
                                    BillboardMapPopup(
                                        headline = openBillboard.headline,
                                        message = openBillboard.message,
                                        ctaLabel = action?.let { stringResource(it.labelRes) },
                                        onCallToAction = {
                                            val target = action ?: return@BillboardMapPopup
                                            scope.launch {
                                                runCatching {
                                                    billboardsRepository?.recordInteraction(
                                                        openBillboard.id,
                                                        target.interactionType,
                                                    )
                                                }
                                            }
                                            // The launch itself is separate from
                                            // the reporting above and must not
                                            // wait on it: a member who taps
                                            // "Ring" gets the dialler whether or
                                            // not the analytics write lands.
                                            runCatching {
                                                context.startActivity(
                                                    Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse(target.uri),
                                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                                )
                                            }
                                            mapSurface.consumeBillboardTap()
                                        },
                                        onDismiss = { mapSurface.consumeBillboardTap() },
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

                            // Whether the Garage panel currently has its add/edit form
                            // open. Declared OUTSIDE the Crossfade so it survives the
                            // branch recomposing, and drives two things below: the
                            // Garage panel's nested-scroll dismiss is DISARMED while a
                            // form is open (issue #796), and a panel-owned dismiss is
                            // routed into GarageRoute instead of leaving the tab.
                            var garageFormOpen by remember { mutableStateOf(false) }
                            // Bumped each time the Garage panel is dismissed (drag-handle,
                            // outside-tap, accessibility) WHILE its form is open, so
                            // GarageRoute runs the same confirm-if-dirty + cleanup as Back
                            // rather than the tab being torn out from under a half-filled
                            // new car.
                            var garageDismissTick by remember { mutableIntStateOf(0) }

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
                                                    // Opened straight to a drive by the
                                                    // "Drive saved" dialog's History action
                                                    // (#856); consumed back to null.
                                                    initialRideId = pendingDriveDetailRideId,
                                                    onInitialRideConsumed = {
                                                        pendingDriveDetailRideId = null
                                                    },
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
                                                            // Friends intentionally removed from
                                                            // the Social menu: it MOVED to the
                                                            // map-home profile menu (see
                                                            // profileMenuEntries), which is now
                                                            // its only menu entry point. The
                                                            // route, the screen and the
                                                            // PushTarget.FRIENDS tap are all
                                                            // unchanged.
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
                                                            // Notifications intentionally removed
                                                            // from the Social menu: the inbox is
                                                            // the chat hub's own Notifications
                                                            // TAB, so this row was a second door
                                                            // onto a screen the chat area already
                                                            // carries. Only the menu ENTRY is
                                                            // gone — ShellRoute.Notifications is
                                                            // still routed, and a NOTIFICATIONS
                                                            // push tap still opens it (see the
                                                            // PushTarget handling above).
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
                                                            // Billboards intentionally removed from
                                                            // the Social menu (Seb, 2026-07-31).
                                                            // Billboards are meant to be MAP PINS
                                                            // — "something that should be within
                                                            // the map, if it isn't activated it
                                                            // shouldn't be shown for our users" —
                                                            // and no billboard rendering exists in
                                                            // the map yet. A menu row leading to a
                                                            // flat list therefore advertised a
                                                            // feature that does not work as
                                                            // intended, so the row is gone and
                                                            // billboards are invisible to members
                                                            // until the map work lands. That is
                                                            // intended, not an oversight.
                                                            //
                                                            // The screen, the repository wiring and
                                                            // ShellRoute.Billboards all REMAIN —
                                                            // deliberately unreferenced, ready for
                                                            // the map integration (see
                                                            // BillboardsScreen / ShellRoute
                                                            // .Billboards). Do not delete them as
                                                            // dead code.
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
                                            // While the add/edit form is open a panel
                                            // dismiss must not silently drop the form:
                                            // route it into GarageRoute (confirm-if-dirty
                                            // + cleanup) via the tick. With the form
                                            // closed the gesture leaves to Map as before.
                                            onDismiss = {
                                                if (garageFormOpen) {
                                                    garageDismissTick++
                                                } else {
                                                    selectedTab = ShellTab.Map
                                                }
                                            },
                                            // Disarm the nested-scroll pull-dismiss while a
                                            // form is open so a fast scroll-to-top cannot
                                            // over-scroll into a dismiss (issue #796). Every
                                            // other panel keeps the default (enabled).
                                            dismissEnabled = !garageFormOpen,
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
                                                    onFormOpenChange = { garageFormOpen = it },
                                                    dismissRequestTick = garageDismissTick,
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
                                // live-session disc: tapping it raises the stop
                                // sheet, whose ONLY action is ending the session.
                                // Reads the optimistic UI state so the disc turns
                                // into the STOP sign the moment a start is tapped,
                                // not when the server's session echoes back.
                                isSharing = isSharingUi,
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

                // In-app update prompt, driven by GOOGLE PLAY ITSELF (the
                // In-App Updates API). Play is asked whether a newer build is
                // live on the track this install came from — so there is no
                // version number for anyone to maintain, no admin step at
                // release time, and no window in which the app can announce a
                // build Play would refuse to serve.
                //
                // Asked ONCE per app session: a cold start is the natural
                // moment to update, an update prompt that arrives mid-use is
                // just noise, and Play must not be re-queried per screen or per
                // recomposition. Entirely fail-safe — a non-Play install
                // (a debug/adb/sideloaded build, a device with no Play Store)
                // reports nothing to offer, so the prompt never appears, no
                // error is shown, and nothing else about the app changes.
                val appUpdateDismissals = remember(context) { AppUpdateDismissalStore(context) }
                // Reuses the single source created at the shell's front door for
                // the startup gate (see appUpdateSource above) rather than
                // constructing a second AppUpdateManager. The gate only acts on a
                // BLOCKING update — every flexible / awaiting-restart case still
                // flows through the wiring below.
                var appUpdateDecision by remember { mutableStateOf(AppUpdateDecision.NONE) }
                var appUpdateVersionCode by remember { mutableStateOf<Int?>(null) }
                val appUpdateStoreUnavailable =
                    stringResource(R.string.appUpdate_storeUnavailable)
                val appUpdateDownloaded = stringResource(R.string.appUpdate_downloaded)
                val appUpdateRestart = stringResource(R.string.appUpdate_restart)

                // THE ONE RECOVERY, shared by every way Play can let the member
                // down: a flow that never started, a flow that started and came
                // back failed, and a downloaded update Play could not install.
                // All three mean the same thing — the in-app route is out — so
                // all three hand off to the Play listing, which is the same
                // update by a longer road, and only say something if even that
                // has nowhere to go. Nothing is ever said while the listing is
                // still openable — which is exactly what the message says
                // happened, so it never sends anyone to a Play Store this
                // device has just proved it cannot open.
                //
                // A decline is not one of these and does not come through here:
                // backing out of Play's consent sheet is an answer, not a
                // failure.
                val appUpdateStoreFallback = {
                    PlayStoreLink.open(context, BuildConfig.APPLICATION_ID) {
                        scope.launch {
                            snackbarHostState.showSnackbar(appUpdateStoreUnavailable)
                        }
                    }
                }

                // Play owns the consent UI once the flow starts; this reads
                // what it hands back. A decline is silent — it is an answer,
                // not a failure, and the suppression window was already
                // recorded on the tap — and only a genuine failure recovers.
                val appUpdateFlowLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.StartIntentSenderForResult(),
                    ) { result ->
                        if (AppUpdateFlowResult.read(result.resultCode) ==
                            AppUpdateFlowOutcome.FAILED
                        ) {
                            appUpdateStoreFallback()
                        }
                    }

                LaunchedEffect(appUpdateSource, appUpdateDismissals) {
                    val dismissal = withContext(Dispatchers.IO) { appUpdateDismissals.read() }
                    val result =
                        AppUpdateCheck.run(
                            source = appUpdateSource,
                            dismissal = dismissal,
                            nowMillis = System.currentTimeMillis(),
                        )
                    appUpdateVersionCode = result.availability?.availableVersionCode
                    appUpdateDecision = result.decision
                }

                // A flexible download that finishes while the app is open:
                // Play reports it here, and the shell turns it into the
                // restart offer rather than leaving the bytes unused.
                DisposableEffect(appUpdateSource) {
                    val source = appUpdateSource
                    if (source == null) {
                        onDispose {}
                    } else {
                        val unregister = source.onDownloadComplete {
                            appUpdateDecision = AppUpdateDecision.AWAITING_RESTART
                        }
                        onDispose { unregister() }
                    }
                }

                // Never in front of someone who is driving: while a live
                // session is running or the navigation overlay is open the
                // prompt is held back (the same pair the shell already treats
                // as "user is on the road" for KeepScreenOn), and it never
                // stacks on top of the what's-new popup. Held, not cancelled —
                // it reappears once the drive ends. This is also the reason the
                // flow is FLEXIBLE: even once accepted, the download runs in
                // the background and the drive is never interrupted.
                val appUpdateOnTheRoad = isSharingUi || navSearchOpen
                val appUpdateShowable = !appUpdateOnTheRoad && whatsNewAnnouncement == null

                // The finished-download offer is a snackbar, not a dialog: a
                // restart is the member's call, and the app works fine until
                // they take it. Keyed on the decision so it is offered once.
                LaunchedEffect(appUpdateDecision, appUpdateShowable) {
                    if (appUpdateDecision != AppUpdateDecision.AWAITING_RESTART) {
                        return@LaunchedEffect
                    }
                    if (!appUpdateShowable) return@LaunchedEffect
                    val snackbarResult =
                        snackbarHostState.showSnackbar(
                            message = appUpdateDownloaded,
                            actionLabel = appUpdateRestart,
                            duration = SnackbarDuration.Long,
                        )
                    appUpdateDecision = AppUpdateDecision.NONE
                    if (snackbarResult == SnackbarResult.ActionPerformed &&
                        appUpdateSource?.completeUpdate() != true
                    ) {
                        // Same recovery again: Play could not install what it
                        // downloaded, so the listing is the remaining route.
                        appUpdateStoreFallback()
                    }
                }

                if (appUpdateShowable) {
                    val decision = appUpdateDecision
                    val recordDismissal = {
                        appUpdateVersionCode?.let { versionCode ->
                            appUpdateDismissals.record(versionCode, System.currentTimeMillis())
                        }
                        Unit
                    }
                    AppUpdateDialog(
                        decision = decision,
                        onUpdate = {
                            val immediate = decision == AppUpdateDecision.IMMEDIATE
                            val started =
                                appUpdateSource?.startFlow(appUpdateFlowLauncher, immediate) == true
                            // Play could not take over. Rather than a dead
                            // button, the shared fallback hands off to the
                            // store listing.
                            if (!started) appUpdateStoreFallback()
                            // Handing over closes the dismissible prompt and
                            // starts the suppression window, so the member is
                            // not asked again the moment they come back. The
                            // blocking prompt is deliberately left standing:
                            // backing out of Play's full-screen flow returns to
                            // it rather than past it.
                            if (!immediate) {
                                recordDismissal()
                                appUpdateDecision = AppUpdateDecision.NONE
                            }
                        },
                        onDismiss = {
                            recordDismissal()
                            appUpdateDecision = AppUpdateDecision.NONE
                        },
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
                            // Guard on the UI sharing state so confirming can never
                            // disturb an active — or just-requested — session; the
                            // fallback still runs when unwired. The picked car (or
                            // the default main car) rides along as the session's
                            // denormalized car.
                            val chosen = effectiveStartDrivingCarId
                            startDrivingCarId = null
                            if (!isSharingUi) requestStartSingleSession(chosen)
                        },
                        onConvoy = {
                            showCreateChooser = false
                            // Deep-link straight into #417's create-convoy flow;
                            // the owner can start the convoy from its detail. The
                            // car picked here is carried into the convoy-create call
                            // so the convoy honours the same choice as Single.
                            pendingConvoyVehicleId = effectiveStartDrivingCarId
                            startDrivingCarId = null
                            convoyOpenCreate = true
                            openRootRoute(ShellRoute.Convoys)
                        },
                        onDismiss = {
                            showCreateChooser = false
                            startDrivingCarId = null
                        },
                        vehicles = createChooserVehicles,
                        selectedVehicleId = effectiveStartDrivingCarId,
                        onSelectVehicle = { startDrivingCarId = it },
                    )
                }

                // Live-share STOP sheet: the transparent [LiveSharePopup] the centre
                // live control raises while a session runs. It exists to do ONE
                // thing — end the session — and is the confirm step in front of a
                // control that sits in the middle of the bottom bar, where a stray
                // tap would otherwise kill a running session outright.
                //
                // "Hide me now" and "More options" were REMOVED from it (Seb,
                // 2026-07): the stop control should stop, not present a menu. Both
                // remain wired everywhere else they were — the full
                // [LiveLocationScreen] and turn-by-turn navigation's copy of this
                // same sheet (which has no Stop) — so nothing was deleted from the
                // app, only from this one sheet. That is expressed once, in
                // [LiveManageSheet.actions], not restated here.
                //
                // Rendered at the shell (not in MapHome) so it is reachable from
                // every tab the bottom bar is on, and gated on the same UI sharing
                // state as the disc that raises it so the two cannot disagree.
                if (liveManageOpen && isSharingUi) {
                    LiveSharePopup(
                        isSharing = true,
                        canShareLive = canShareLive,
                        // Unused while sharing (no Start row), kept non-null for the
                        // shared signature.
                        onStart = {},
                        onStop = {
                            // Ending a live session while a convoy is active must
                            // not orphan the convoy (#726: an active convoy implies
                            // an ongoing session). Decide off the frozen snapshot:
                            // no active convoy → stop straight away; otherwise raise
                            // the convoy prompt and defer the stop to its choice.
                            val active = liveStopActiveConvoy
                            val plan =
                                LiveSessionConvoyStop.plan(
                                    inActiveConvoy = active != null,
                                    viewerIsOwner = active?.viewerIsOwner == true,
                                    acceptedMemberCount =
                                        active?.let { ConvoyBar.acceptedMembers(it).size } ?: 0,
                                )
                            when (plan) {
                                LiveSessionStopPlan.StopNow -> stopLiveShare()
                                is LiveSessionStopPlan.AskConvoy -> {
                                    liveManageOpen = false
                                    convoyStopPrompt =
                                        active?.let {
                                            ConvoyStopPromptState(it.convoyId, plan.exitChoice)
                                        }
                                }
                            }
                        },
                        // Not shown by this sheet (see above); the shared signature
                        // still requires handlers, and they stay CORRECT rather than
                        // becoming no-ops, so re-enabling a row can never wire it to
                        // nothing.
                        onHideMeNow = {
                            // Self-initiated end — keep the neutral save-prompt copy
                            // (see [savePromptReason]).
                            userEndedSession = true
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

                // "What about the convoy?" — raised when Stop is tapped while a
                // convoy is active, instead of silently ending the session and
                // orphaning it. The two convoy exits are the SAME the convoy bar
                // offers, so the semantics can't drift: EndConvoy -> convoy-end
                // (owner-only, ends it for everyone), LeaveConvoy -> convoy-leave
                // (the caller drops out, the others carry on; leadership transfers
                // when the owner leaves, and the server ends the convoy when too
                // few are left). Each choice performs its convoy action AND then
                // ends the live session; "Keep sharing" dismisses and does neither.
                val stopPrompt = convoyStopPrompt
                if (stopPrompt != null && isSharingUi) {
                    val exitChoice = stopPrompt.exitChoice
                    val bodyRes =
                        when (exitChoice) {
                            ConvoyExitChoice.LeaveOrEnd -> R.string.convoy_stopSessionOwnerBody
                            ConvoyExitChoice.EndOnly -> R.string.convoy_stopSessionOwnerEndOnlyBody
                            ConvoyExitChoice.LeaveOnly -> R.string.convoy_stopSessionMemberBody
                            ConvoyExitChoice.LeaveEndsConvoy ->
                                R.string.convoy_stopSessionLeaveEndsBody
                        }
                    // Perform the convoy action, then end the session — but ONLY
                    // if the convoy action succeeded. Ending the session on a
                    // FAILED end/leave would leave the convoy behind with no live
                    // session, the exact invariant this dialog exists to protect
                    // (#726). Closes the prompt first so it can't double-fire;
                    // re-raises it on failure (the coordinator has reconciled the
                    // convoy back) so the user can retry without orphaning it.
                    val actAndStop: (ConvoyStopAction) -> Unit = { action ->
                        val convoyId = stopPrompt.convoyId
                        convoyStopPrompt = null
                        scope.launch {
                            val coordinator = convoyBarCoordinator
                            when (action) {
                                ConvoyStopAction.EndConvoy -> coordinator?.end(convoyId)
                                ConvoyStopAction.LeaveConvoy -> coordinator?.leave(convoyId)
                            }
                            // runRowAction clears the row error before each attempt
                            // and sets it on failure, so this reads THIS call's
                            // outcome (the same pattern the convoy accept path uses).
                            val succeeded = coordinator == null || coordinator.actionError.value == null
                            if (succeeded) {
                                stopLiveShare()
                            } else {
                                convoyStopPrompt = stopPrompt
                            }
                        }
                    }
                    AlertDialog(
                        onDismissRequest = { convoyStopPrompt = null },
                        title = { Text(stringResource(R.string.convoy_stopSessionTitle)) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                                Text(
                                    text = stringResource(bodyRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                LiveSessionConvoyStop.actionsFor(exitChoice).forEach { action ->
                                    when (action) {
                                        ConvoyStopAction.LeaveConvoy -> {
                                            val label =
                                                when (exitChoice) {
                                                    ConvoyExitChoice.LeaveEndsConvoy ->
                                                        R.string.convoy_stopSessionLeaveEnds
                                                    ConvoyExitChoice.LeaveOrEnd ->
                                                        R.string.convoy_stopSessionLeaveRunning
                                                    else -> R.string.convoy_stopSessionLeave
                                                }
                                            Button(
                                                onClick = { actAndStop(ConvoyStopAction.LeaveConvoy) },
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Text(stringResource(label))
                                            }
                                        }
                                        ConvoyStopAction.EndConvoy ->
                                            // Destructive: a text action in the error
                                            // colour, never the accidental default.
                                            TextButton(
                                                onClick = { actAndStop(ConvoyStopAction.EndConvoy) },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors =
                                                    ButtonDefaults.textButtonColors(
                                                        contentColor = MaterialTheme.colorScheme.error,
                                                    ),
                                            ) {
                                                Text(stringResource(R.string.convoy_stopSessionEnd))
                                            }
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { convoyStopPrompt = null }) {
                                Text(stringResource(R.string.convoy_stopSessionKeep))
                            }
                        },
                    )
                }

                // "The convoy ended" — raised when someone ELSE ended a convoy the
                // member was in, which stopped their convoy-auto session under them.
                // Rather than ending the drive out from under them, ask: end the
                // session too (→ the existing stop + #771 save prompt), or keep going
                // as a standalone single session (transfer the STILL-RUNNING recording
                // to a fresh solo session — the convoy leg and solo continuation land
                // in one drive, no gap). Resolving the choice is the ONE place either
                // outcome is wired, via the pure [ConvoyEndSessionChoice.resolve].
                val convoyEndSessionId = convoyEndPromptSessionId
                if (convoyEndSessionId != null) {
                    val resolveConvoyEnd: (ConvoyEndChoice) -> Unit = { choice ->
                        // Close the dialog. The Stop branch marks the session decided
                        // synchronously (it stops+saves right here); the Transfer branch
                        // must NOT — see below.
                        convoyEndPromptSessionId = null
                        // Whether a REAL solo session can be started right now. This is
                        // exactly requestStartSingleSession's own gate: only when both
                        // hold does it actually start a session (otherwise it merely
                        // opens the live screen / shows the unavailable snackbar). It is
                        // handed to the pure resolve() so Continue with no way to start
                        // a session falls back to Stop rather than orphaning the drive.
                        val canStartSingle = liveLocationCoordinator != null && canShareLive
                        when (
                            val resolution = ConvoyEndSessionChoice.resolve(choice, canStartSingle)
                        ) {
                            is ConvoyEndResolution.Stop -> {
                                // End branch — and the Continue-but-can't-start fallback:
                                // stop the recording and raise the Keep/Delete summary
                                // with the convoy-ended copy (#771). Not a self-stop, so
                                // userEndedSession stays false and the reason stands.
                                // Decided now: the stop is synchronous, so the effect
                                // re-running on the still-stopped session must not
                                // re-open this dialog.
                                convoyEndDecidedSessionId = convoyEndSessionId
                                SingleSessionRecording.stop(resolution.reason)
                            }
                            ConvoyEndResolution.TransferToSingle -> {
                                // Continue branch: do NOT stop — the recording keeps
                                // running. Start a fresh standalone single session so
                                // sharing resumes at once; SingleSessionRecording.start
                                // (fired by the isSharing effect when the new session
                                // echoes) is a no-op while a recording is in flight, so
                                // the SAME recording — every point so far — carries into
                                // the solo session with no data loss and no visible gap.
                                //
                                // Marked PENDING, not decided: the start can still fail
                                // (refused / Busy / Failed / no echo). The resolution
                                // effect above turns this into "decided" once the new
                                // session is confirmed, or a clean stop+save if the
                                // start never lands — so a failed transfer can never
                                // leave the recording running with no session.
                                convoyTransferPendingSessionId = convoyEndSessionId
                                requestStartSingleSession()
                            }
                        }
                    }
                    AlertDialog(
                        // Back-press / outside-tap defaults to the conservative,
                        // data-preserving outcome — end the session and save the drive,
                        // exactly the pre-choice behaviour — so a dismiss never strands
                        // a stopped session with an unresolved recording.
                        onDismissRequest = { resolveConvoyEnd(ConvoyEndChoice.EndSession) },
                        title = { Text(stringResource(R.string.convoy_endedContinueTitle)) },
                        text = { Text(stringResource(R.string.convoy_endedContinueBody)) },
                        confirmButton = {
                            Button(onClick = { resolveConvoyEnd(ConvoyEndChoice.ContinueAsSingle) }) {
                                Text(stringResource(R.string.convoy_endedContinueAsSingle))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { resolveConvoyEnd(ConvoyEndChoice.EndSession) }) {
                                Text(stringResource(R.string.convoy_endedContinueEnd))
                            }
                        },
                    )
                }

                // End-of-session safety net: a finished Single/Convoy session's
                // drive is AUTO-SAVED and AUTO-KEPT (see the effects above), so the
                // normal stop path shows no prompt. This dialog now renders ONLY on
                // a save FAILURE — Retry re-runs the save after a transient fault;
                // Discard closes a permanent (member-gate) refusal, where nothing
                // was saved — so a drive is never lost silently (#853).
                if (showSessionSummary && activeRecording != null) {
                    SessionSummaryDialog(
                        state = recordingState,
                        pointsProvider = { activeRecording?.recordedPoints() ?: emptyList() },
                        onRetry = { scope.launch { activeRecording?.autoSave(null) } },
                        onDiscard = { activeRecording?.discard() },
                    )
                }

                // Informational "Drive saved" confirmation (#856): raised once the
                // finished single session's drive has been auto-kept (see the Kept
                // terminal above). The save already happened in the background, so
                // this only confirms it — OK dismisses; History jumps to the
                // Drives/History route (deep-linking to the just-saved drive when its
                // id is known). Removing an unwanted drive stays in History.
                if (driveSavedDialogVisible) {
                    DriveSavedDialog(
                        message = driveSavedText,
                        confirmLabel = driveSavedOkText,
                        historyLabel = driveSavedHistoryText,
                        onDismiss = { driveSavedDialogVisible = false },
                        onHistory = {
                            // Just navigate — DriveSavedDialog closes itself (calls
                            // onDismiss) after this, so no dismiss is needed here.
                            driveSavedDialogRideId?.let { pendingDriveDetailRideId = it }
                            selectedTab = ShellTab.History
                        },
                    )
                }

                // One-time battery-optimization exemption ask (#849): the first
                // time a drive is actually recording, offer to exempt the app so an
                // aggressive OS (Samsung / Doze) is less likely to kill the
                // backgrounded tracking session mid-drive. Shown once; declining is
                // fully supported (the disk journal still resumes a killed drive).
                DriveBatteryOptimizationPrompt(
                    isRecordingDrive = recordingState is RecordingState.Recording,
                )

                // Chat hub as a TRANSPARENT popup over the map (Issue 4): a focusable
                // Popup with no dimming scrim and a translucent surface, so the live
                // map stays visible behind it — matching the map-layers and
                // live-share popups.
                //
                // The gate: the popup floats over A MAP, so it may only show while
                // a map is the page in front — never over a full route, a non-map
                // tab, or the nav-search overlay.
                //
                // Turn-by-turn USED to be excluded here as well, and that exclusion
                // is now lifted: the navigation screen carries the map home's chat
                // control (the two right-side stacks are the same set of buttons,
                // by request), and a chat bubble that cannot open the hub is not
                // the same button. Navigation is a full-screen map of its own, so
                // the popup has exactly what it needs — a live map to float over.
                //
                // Everything else still reads the shell's single [mapCover] rather
                // than restating the condition (which is how the nav-search term
                // would have been missed when search stopped being its own
                // branch). Both the auto-close effect below and the render
                // condition read THIS value, so the two cannot drift apart.
                val chatHubGateOpen =
                    ShellNavigation.chatHubAllowed(
                        cover = mapCover,
                        navigating = navDestination != null,
                    )

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
                        // Tapping a shared location link in a message closes the
                        // hub and shows that point on the map IN-APP (the same
                        // move-to-point flow the map's own gestures use) — never an
                        // external maps app.
                        onShowLocationOnMap = { lat, lng ->
                            chatHubOpen = false
                            moveMapToPoint(lat, lng, sharedLocationName)
                        },
                        // Null for a plain chat-bubble tap (lands on Community);
                        // set by the convoy bar's chat icon so the hub opens on
                        // that convoy's channel. Cleared on close by the effect
                        // beside `chatHubOpen`.
                        pushDeepLink = chatHubLandingLink,
                        convoyLink = convoyNotificationLink,
                        onOpenEvent = openEventFromNotification,
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
 * while [isSharing] — the live-session disc that raises the STOP sheet
 * ([onManageLiveShare]), whose only action is ending the session.
 *
 * The disc keeps its error-red fill while sharing (an active session drawing
 * attention); tapping it opens the sheet rather than stopping outright, so a
 * stray tap on a control in the middle of the bottom bar cannot kill a running
 * session. The content description reflects that it opens the live controls.
 *
 * [isSharing] is the host's OPTIMISTIC sharing state, not the observed session:
 * the disc must become the STOP sign on the frame the user taps start, not after
 * the server round trip (see LiveShareStart / OptimisticLiveStart).
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
        // the live-session disc that raises the stop sheet: one control for the
        // session's whole life, so the way out is exactly where the way in was.
        // The sheet's single action is Stop (which auto-saves the drive and raises
        // the Keep/Delete summary via the isSharing effect, where the "keep it or
        // delete the saved drive" choice is made).
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
                        // square on the red disc, but tapping opens the stop sheet
                        // (one confirm, then the session ends), so the label says
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
    // The user's garage cars, the currently-selected one, and the tap handler —
    // the round-photo picker at the top of the chooser. The chosen car applies to
    // BOTH the Single and Convoy option below it (it is threaded into whichever
    // start the user then taps). Empty list → the picker shows its "no cars" hint.
    vehicles: List<com.kungsbackacarcommunity.app.garage.Vehicle> = emptyList(),
    selectedVehicleId: String? = null,
    onSelectVehicle: (String) -> Unit = {},
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
                com.kungsbackacarcommunity.app.live.StartDrivingCarPicker(
                    vehicles = vehicles,
                    selectedVehicleId = selectedVehicleId,
                    onSelectVehicle = onSelectVehicle,
                )
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
    // Marks that the member ended their own live session from the full-screen
    // live-location surface (Stop / Hide me now), so the shell's save prompt keeps
    // the neutral copy rather than mislabeling a deliberate stop as a convoy end
    // (see [savePromptReason]). A no-op path still stops the session; this only
    // records intent for the prompt.
    onUserEndLiveSession: () -> Unit,
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
    // Moves the app's OWN map to a shared location (from a chat geo-link tap),
    // in-app. Forwarded to the chat hub route; the hub closes first.
    onShowLocationOnMap: (latitude: Double, longitude: Double) -> Unit,
    // The app's OWN in-app navigate-to-point handoff (moveMapToPoint): raises the
    // "Navigate here" preview on the app's map. Forwarded to EventsRoute so the
    // event detail's Navigate button stays in-app instead of firing the device's
    // maps app — the same flow a tapped map place or a chat geo-link uses.
    onNavigateToPoint: (latitude: Double, longitude: Double, name: String?) -> Unit,
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
    badgeProgressRepository: BadgeProgressRepository?,
    blockingRepository: BlockingRepository?,
    friendsRepository: FriendsRepository?,
    memberProfileRepository: MemberProfileRepository?,
    memberProfileTargetUid: String?,
    onOpenMemberProfile: (String) -> Unit,
    dmRepository: DmRepository?,
    convoyRepository: ConvoyRepository?,
    convoyOpenCreate: Boolean,
    // The car the owner picked in the "Start driving" popup before choosing
    // Convoy, forwarded to the create-convoy call so the convoy honours the same
    // choice as a Single session; null falls back to the owner's main car.
    convoyCreateVehicleId: String?,
    // Invoked once a convoy is successfully created: the create flow shows no
    // confirmation page — it dismisses the convoy surface and lands on the Map
    // tab, where the convoy bar shows the new (active) convoy.
    onConvoyCreated: () -> Unit,
    // Invoked with the convoy id once an INVITE is successfully accepted — the
    // accept-side twin of [onConvoyCreated]. Same landing (close the surface,
    // Map tab), plus a one-shot request to frame that convoy's members.
    onConvoyJoined: (String) -> Unit,
    // Destination of the push tap that opened the chat hub, if that is why it is
    // open. Forwarded to ChatHubRoute, which owns tab/channel sub-navigation.
    chatHubPushLink: PushDeepLink?,
    // Event id from an event-reminder push tap (null otherwise). Forwarded to
    // EventsRoute to open that event on entry; [onEventDeepLinkConsumed] clears
    // the shell's pending id once EventsRoute has taken it.
    eventDeepLinkId: String?,
    onEventDeepLinkConsumed: () -> Unit,
    // Convoy id from a convoy-invite notification tap (null otherwise).
    // Forwarded to ConvoyRoute, which lands on its pending-invite list and — if
    // the invite is no longer there — says what became of it.
    convoyInviteDeepLinkId: String?,
    onConvoyInviteDeepLinkConsumed: () -> Unit,
    // Convoy state + "open this" for the notification inbox's convoy rows,
    // wherever the inbox is hosted (its own route, or the chat hub's tab).
    convoyNotificationLink: ConvoyNotificationLink?,
    // Opens an event's detail from an event notification tap in the inbox,
    // wherever it is hosted. Sets the event deep-link and routes to Events (and
    // closes the chat hub popup) — the same path a PushTarget.EVENT tap takes.
    openEventFromNotification: (String) -> Unit,
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
    onShareLocation: (name: String, point: LatLng) -> Unit,
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
            // already knows how to make, and scoped to THIS route: the listeners
            // below only exist while the Profile route is composed and tear down
            // on leaving it. No new query or index is added.
            //   • drives  → the same owner list the History tab folds, run through
            //     the shared DriveStatsCalculator (from the drive-stats page);
            //   • badges  → the owner users/{uid}/badges list (the badge wall);
            //   • points  → the single pointsLedger/{uid}.balance doc, plus the
            //     same bounded newest-first entries listener the Kronpoäng screen
            //     uses (credits only are shown — see Points.recentEarnings);
            //   • badge progress → the owner-only badges-getMyProgress callable,
            //     which hands this member their own seven ladder counters so
            //     every ladder can draw a bar (issue #799); the backend-only
            //     badgeProgress doc stays unreadable to clients.
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
            // The signed-in member's OWN seven ladder counters, fetched once per
            // route composition from the owner-only badges-getMyProgress callable.
            // Null until it resolves (or when unavailable in a config-less build),
            // in which case the wall renders every ladder's goal without a bar —
            // never a fabricated number. OWN-profile only: the callable derives
            // the uid from the auth context, so it can only ever be this member.
            val badgeCounters by
                produceState<BadgeCounters?>(
                    initialValue = null,
                    badgeProgressRepository,
                    uid,
                ) {
                    value = badgeProgressRepository?.fetchMyProgress()
                }
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
            // ONE fold over the drives list. The stats summary and the badge wall
            // both want the same lifetime figure, and the list is unbounded, so
            // computing it twice repeats an O(n) pass on every drives update.
            // Hoisted rather than read off statsSummary: that flattens a null
            // (drives not loaded / no stats) to 0.0, and the Vägfarare counter
            // distinguishes "unknown" from "zero".
            val driveStats =
                remember(drivesState, statsMonthStart) {
                    (drivesState as? DrivesState.Loaded)?.drives?.let {
                        DriveStatsCalculator.compute(it, statsMonthStart)
                    }
                }
            val statsSummary =
                remember(driveStats, badgesState, pointsBalance, profile?.createdAtMillis) {
                    val loadedDrives = (drivesState as? DrivesState.Loaded)?.drives
                    val loadedBadges = (badgesState as? BadgesState.Loaded)?.badges
                    // Hold the section back until the two activity signals have
                    // both resolved, so a member with drives never flashes the
                    // "start driving" empty state before the drives list loads.
                    if (loadedDrives == null || loadedBadges == null) {
                        null
                    } else {
                        ProfileStatsSummary.from(
                            driveStats = driveStats,
                            badgeCount = loadedBadges.size,
                            pointsBalance = pointsBalance,
                            memberSinceMillis = profile?.createdAtMillis,
                        )
                    }
                }

            // The badge wall. Null until the owner badge list resolves, so the
            // section stays absent rather than flashing an empty wall at a member
            // who actually holds badges. Counters come from the owner-only
            // getMyProgress callable ([badgeCounters]); once they resolve every
            // ladder draws an honest bar, and until then each ladder still shows
            // its goal without inventing a number (see BadgeCounters).
            val badgeShowcase =
                remember(badgesState, badgeCounters) {
                    (badgesState as? BadgesState.Loaded)?.badges?.let { badges ->
                        BadgeShowcase.from(
                            badges = badges,
                            counters = badgeCounters ?: BadgeCounters.NONE,
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
                    onSave = { name, bio, social ->
                        profileEditCoordinator?.let { c ->
                            scope.launch { c.save(uid, name, bio, social) }
                        }
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
                    // The points card is now the ONLY way into the full ledger
                    // (the profile menu's "Points" row was removed). Pushed, not
                    // opened as a root, so Back returns to the profile that sent
                    // them there — the same hub → child rule Settings uses.
                    onOpenPoints =
                        if (pointsRepository != null) {
                            { onOpenRoute(ShellRoute.Points) }
                        } else {
                            null
                        },
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
                    onUserEndLiveSession()
                    liveLocationCoordinator?.let { c -> scope.launch { c.stop() } }
                    BackgroundLocationController.stop(context)
                },
                onHideMeNow = {
                    onUserEndLiveSession()
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
                    // Backs the detail's in-app Share button: the friend picker sends
                    // a DM carrying a tappable "Open event" chip. Both null in a
                    // config-less build, which hides Share.
                    friendsRepository = friendsRepository,
                    dmRepository = dmRepository,
                    // Event detail's Navigate button routes through the app's own
                    // in-app navigate-to-point handoff (the same "Navigate here"
                    // preview a tapped map place uses), never the device's maps app.
                    onNavigateToPoint = onNavigateToPoint,
                    // Resolves the organiser's current display name (live users/{uid})
                    // for the detail page's "Organizer: …" line. The shared instance
                    // (or EMPTY in a config-less build) — already process-stable.
                    liveProfileRepository =
                        FirebaseLiveProfileRepository.sharedOrEmpty(LocalContext.current),
                )
            } else {
                LoadingScreen()
            }

        ShellRoute.CrownHunt -> {
            // The hub is now a read-only stats + season-leaderboard page (crowns
            // live on the map, not in a list here). Its reads come from the #710
            // aggregates via a rules-gated Firestore repository, built here like the
            // crown map layer's controller — null in a config-less/CI build, which
            // simply shows the loading affordance.
            val crownHuntStatsRepository =
                remember(context) { FirebaseCrownHuntStatsRepository.createIfAvailable(context) }
            CrownHuntRoute(
                statsRepository = crownHuntStatsRepository,
                passesMemberGate = MemberGating.allows(profileActiveMember),
                onBack = onClose,
                // Powers the member's own Kronjägare TIER standing — the same
                // owner-scoped users/{uid}/badges listener the profile badge wall
                // uses, so no new query shape or index.
                badgesRepository = badgesRepository,
                uid = uid,
            )
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
                    convoyLink = convoyNotificationLink,
                    onOpenEvent = openEventFromNotification,
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
                    // Opens a 1:1 DM with this member from the profile's Message
                    // action (shown only when already friends). Guarded on DM
                    // being wired, exactly like the Friends list's Message row.
                    onOpenChat =
                        if (dmRepository != null) {
                            { targetUid, displayName -> onOpenChat(targetUid, displayName) }
                        } else {
                            null
                        },
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
                    onConvoyJoined = onConvoyJoined,
                    // Lets the convoy taps that start a live session server-side
                    // (create / accept into an active convoy / start) flip the
                    // shell's live control immediately instead of waiting for the
                    // session to echo back — the same gate the manual start uses.
                    liveShareEnabled = canShareLive,
                    inviteDeepLinkConvoyId = convoyInviteDeepLinkId,
                    onInviteDeepLinkConsumed = onConvoyInviteDeepLinkConsumed,
                    createVehicleId = convoyCreateVehicleId,
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
                    // "Start a new dialogue": pick a friend to open a DM thread with,
                    // reusing the same open-chat path as a conversation-row tap.
                    friendsRepository = friendsRepository,
                    onOpenDm = { otherUid, otherName -> onOpenChat(otherUid, otherName) },
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
                    // A shared "Open event" chip in a DM lands the recipient on that
                    // event's detail page via the shell's existing deep-link path.
                    onOpenEvent = openEventFromNotification,
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
                // Tapping a shared location link leaves the hub and shows that
                // point on the map IN-APP (never an external maps app).
                onShowLocationOnMap = { lat, lng ->
                    onClose()
                    onShowLocationOnMap(lat, lng)
                },
                // Backs the block action on the hub's long-press message sheet.
                blockingRepository = blockingRepository,
                pushDeepLink = chatHubPushLink,
                convoyLink = convoyNotificationLink,
                onOpenEvent = openEventFromNotification,
            )

        // Migration-safe: `Badges` is the retired flat awards list. Its profile-menu
        // entry is gone (the profile's own badge wall is a strict superset), so
        // nothing in the UI navigates here; the constant is kept only so older
        // persisted state (route = Badges) still restores to a valid constant —
        // rememberSaveable persists ShellRoute BY NAME. Same treatment as `More`.
        ShellRoute.Badges -> {
            LaunchedEffect(Unit) { onClose() }
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
                onShare = onShareLocation,
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
    friendsRepository: FriendsRepository?,
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
        // "Messages" was removed from this menu: the map-home chat bubble is an
        // UNCONDITIONAL right-side control (MapControlSet.rightSideStack) and the
        // hub it opens renders the very same DM inbox (ConversationListRoute, on
        // its "Friends" tab) behind the very same `dmRepository != null` gate
        // this entry used — so the row was a second door onto a screen that is
        // one tap away on the map. Every other way in survives untouched:
        // ShellRoute.Conversations is still routed and is still where a
        // PushTarget.DM tap with no counterpart lands; a DM push WITH a
        // counterpart still opens the thread; and the Friends screen's "Message"
        // button and a member profile still open a thread via openChat.
        // Friends moved here from the Social menu: managing who you are
        // connected to is an account concern, not a browse-the-community one.
        // This is now the ONLY menu route to the friends list — the chat hub's
        // "Friends" TAB is the DM inbox, a different screen — and a
        // PushTarget.FRIENDS tap still opens it directly (see above).
        HubEntry(
            stringResource(R.string.shell_friendsTitle),
            Icons.Filled.Groups,
            if (friendsRepository != null) {
                { onOpenRoute(ShellRoute.Friends) }
            } else {
                null
            },
        ),
        // "Points" was removed from this menu (Seb, 2026-07-31): Kronpoäng is a
        // profile concern and belongs on the profile page, not in the account
        // menu beside it. The full ledger did NOT go with it — the profile's
        // points card only summarises (balance + 4 recent, undated CREDITS)
        // while PointsScreen is the whole statement (credits and debits, each
        // dated), so the card was made TAPPABLE and now opens
        // ShellRoute.Points itself (see the Profile branch of RouteHost:
        // `onOpenPoints`). That card is the app's ONLY door to the ledger; do
        // not add a second one here without removing that one.
        // "Awards" (the flat BadgesScreen list) was removed from this menu: the
        // profile screen's own ProfileBadgesSection is a strict superset of it —
        // every rung of every ladder plus the standalone milestones, earned lit
        // and unearned greyed, with the unlock date in each medallion's detail —
        // so the entry was a second, poorer door onto the same trophies.
        // ShellRoute.Badges is retired with it (see the route host).
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
