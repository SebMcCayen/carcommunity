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
import androidx.compose.ui.platform.LocalContext
import com.kungsbackacarcommunity.app.config.FeatureFlag
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
import com.kungsbackacarcommunity.app.garage.GarageCoordinator
import com.kungsbackacarcommunity.app.garage.GarageRepository
import com.kungsbackacarcommunity.app.garage.GarageRoute
import com.kungsbackacarcommunity.app.groupdrive.GroupDriveCoordinator
import com.kungsbackacarcommunity.app.groupdrive.GroupDriveRepository
import com.kungsbackacarcommunity.app.home.HomeScreen
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
import com.kungsbackacarcommunity.app.live.LiveLocationCoordinator
import com.kungsbackacarcommunity.app.live.LiveLocationRepository
import com.kungsbackacarcommunity.app.live.LiveLocationScreen
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
import com.kungsbackacarcommunity.app.subscription.BillingRepository
import com.kungsbackacarcommunity.app.subscription.SubscriptionRoute
import com.kungsbackacarcommunity.app.subscription.SubscriptionVerifier
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
    billingRepository: BillingRepository?,
    subscriptionVerifier: SubscriptionVerifier?,
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

                    // Avatar upload: an ImageUploadCoordinator (5 MB cap) uploads
                    // to profileImages/{uid}/{imageId}, then persists avatarPath
                    // through the rules-validated profile write. Wired only when
                    // BOTH the uploader and profile repo are available (config-less
                    // builds hide the change-picture button).
                    val avatarContext = LocalContext.current
                    val avatarCoordinator =
                        remember(mediaUploader) {
                            mediaUploader?.let {
                                ImageUploadCoordinator(it, MediaUpload.PROFILE_IMAGE_MAX_BYTES)
                            }
                        }
                    val avatarStatus by
                        (avatarCoordinator?.status ?: flowOf(ImageUploadStatus.Idle))
                            .collectAsState(initial = ImageUploadStatus.Idle)
                    val avatarUrl = rememberStorageImageUrl(avatarContext, profile?.avatarPath)
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
                            destination = MainDestination.Home
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

                MainDestination.LiveLocation -> {
                    // Foreground background-location service is started/stopped
                    // alongside the session so GPS streams via live.updatePosition
                    // (Phase 12 slice 6). The service self-stops when Firebase is
                    // unavailable or the location permission is absent.
                    val liveLocationContext = LocalContext.current
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
                            // Only start the foreground service when live-location
                            // sharing is actually wired (coordinator present). In a
                            // config-less / Firebase-unavailable build the coordinator
                            // is null and the service would only start to immediately
                            // self-stop, churning a foreground notification.
                            liveLocationCoordinator?.let { c ->
                                scope.launch { c.start(d) }
                                BackgroundLocationController.start(liveLocationContext)
                            }
                        },
                        onStop = {
                            liveLocationCoordinator?.let { c -> scope.launch { c.stop() } }
                            BackgroundLocationController.stop(liveLocationContext)
                        },
                        onHideMeNow = {
                            liveLocationCoordinator?.let { c -> scope.launch { c.hideMeNow() } }
                            BackgroundLocationController.stop(liveLocationContext)
                        },
                        onBack = {
                            destination = MainDestination.Home
                            liveLocationCoordinator?.reset()
                            BackgroundLocationController.stop(liveLocationContext)
                        },
                    )
                }

                MainDestination.Map -> {
                    // Flag-gated (not member-gated) like the live-location
                    // entry; the map itself needs no repository. The Mapbox
                    // token guard lives in MapRoute.
                    MapRoute(onBack = { destination = MainDestination.Home })
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
                            groupDriveRepository = groupDriveRepository,
                            groupDriveCoordinator = groupDriveCoordinator,
                            onBack = { destination = MainDestination.Home },
                        )
                    } else {
                        // Unreachable: the Home entry is gated on eventsRepository
                        // != null. Render the shell rather than mutate state here.
                        LoadingScreen()
                    }
                }

                MainDestination.CrownHunt -> {
                    if (crownHuntRepository != null) {
                        CrownHuntRoute(
                            repository = crownHuntRepository,
                            coordinator = crownHuntCoordinator,
                            isActiveMember = profile?.activeMember == true,
                            onBack = { destination = MainDestination.Home },
                        )
                    } else {
                        LoadingScreen()
                    }
                }

                MainDestination.Partners -> {
                    if (partnersRepository != null) {
                        PartnersRoute(
                            repository = partnersRepository,
                            offerCodeCoordinator = offerCodeCoordinator,
                            uid = uid,
                            isActiveMember = profile?.activeMember == true,
                            onBack = { destination = MainDestination.Home },
                        )
                    } else {
                        LoadingScreen()
                    }
                }

                MainDestination.Notifications -> {
                    if (notificationsRepository != null) {
                        NotificationsRoute(
                            repository = notificationsRepository,
                            coordinator = notificationsCoordinator,
                            uid = uid,
                            onBack = { destination = MainDestination.Home },
                        )
                    } else {
                        LoadingScreen()
                    }
                }

                MainDestination.NotificationSettings -> {
                    if (notificationSettingsRepository != null) {
                        val context = LocalContext.current
                        NotificationSettingsRoute(
                            repository = notificationSettingsRepository,
                            coordinator = notificationSettingsCoordinator,
                            uid = uid,
                            pushPermission = currentPushPermissionStatus(context),
                            onOpenSystemSettings = { openAppNotificationSettings(context) },
                            onBack = { destination = MainDestination.Home },
                        )
                    } else {
                        LoadingScreen()
                    }
                }

                MainDestination.Garage -> {
                    if (garageRepository != null) {
                        GarageRoute(
                            repository = garageRepository,
                            coordinator = garageCoordinator,
                            uid = uid,
                            isActiveMember = profile?.activeMember == true,
                            onBack = { destination = MainDestination.Home },
                            mediaUploader = mediaUploader,
                        )
                    } else {
                        LoadingScreen()
                    }
                }

                MainDestination.Badges -> {
                    if (badgesRepository != null) {
                        BadgesRoute(
                            repository = badgesRepository,
                            uid = uid,
                            onBack = { destination = MainDestination.Home },
                        )
                    } else {
                        LoadingScreen()
                    }
                }

                MainDestination.Blocked -> {
                    if (blockingRepository != null) {
                        BlockingRoute(
                            repository = blockingRepository,
                            uid = uid,
                            onBack = { destination = MainDestination.Home },
                        )
                    } else {
                        LoadingScreen()
                    }
                }

                MainDestination.SavedDrives -> {
                    if (drivesRepository != null) {
                        DrivesRoute(
                            repository = drivesRepository,
                            uid = uid,
                            onBack = { destination = MainDestination.Home },
                        )
                    } else {
                        LoadingScreen()
                    }
                }

                MainDestination.Points -> {
                    if (pointsRepository != null) {
                        PointsRoute(
                            repository = pointsRepository,
                            uid = uid,
                            onBack = { destination = MainDestination.Home },
                        )
                    } else {
                        LoadingScreen()
                    }
                }

                MainDestination.PartnerApplication -> {
                    if (partnerApplicationCoordinator != null) {
                        PartnerApplicationRoute(
                            coordinator = partnerApplicationCoordinator,
                            onBack = { destination = MainDestination.Home },
                        )
                    } else {
                        LoadingScreen()
                    }
                }

                MainDestination.Billboards -> {
                    if (billboardsRepository != null) {
                        BillboardsRoute(
                            repository = billboardsRepository,
                            onBack = { destination = MainDestination.Home },
                        )
                    } else {
                        LoadingScreen()
                    }
                }

                MainDestination.AccountDeletion -> {
                    if (accountDeletionCoordinator != null) {
                        AccountDeletionRoute(
                            coordinator = accountDeletionCoordinator,
                            onDeleted = onSignOut,
                            onBack = { destination = MainDestination.Home },
                        )
                    } else {
                        LoadingScreen()
                    }
                }

                MainDestination.PartnerStats -> {
                    if (partnerStatsRepository != null) {
                        PartnerStatsRoute(
                            repository = partnerStatsRepository,
                            coordinator = partnerStatsCoordinator,
                            uid = uid,
                            onBack = { destination = MainDestination.Home },
                        )
                    } else {
                        LoadingScreen()
                    }
                }

                MainDestination.Subscription -> {
                    if (billingRepository != null && subscriptionVerifier != null) {
                        SubscriptionRoute(
                            billing = billingRepository,
                            verifier = subscriptionVerifier,
                            isActiveMember = profile?.activeMember == true,
                            onBack = { destination = MainDestination.Home },
                        )
                    } else {
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
                        // Map: behind the same LIVE_LOCATION flag (flag-gated,
                        // not member-gated). Needs no Firebase repository — the
                        // map renders the caller's own view; the Mapbox token
                        // guard is in MapRoute.
                        onOpenMap =
                            if (liveLocationEnabled) {
                                { destination = MainDestination.Map }
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
                        // Kronjakt: behind the crownHunt flag; membership is
                        // enforced inside the screen and by the rules.
                        onOpenCrownHunt =
                            if (crownHuntRepository != null &&
                                FeatureGate.isAvailable(
                                    flags = flags,
                                    flag = FeatureFlag.CROWN_HUNT,
                                    memberGated = false,
                                    isActiveMember = profile?.activeMember == true,
                                )
                            ) {
                                { destination = MainDestination.CrownHunt }
                            } else {
                                null
                            },
                        // Partners: behind the partners flag; offers are member-
                        // gated inside the screen.
                        onOpenPartners =
                            if (partnersRepository != null &&
                                FeatureGate.isAvailable(
                                    flags = flags,
                                    flag = FeatureFlag.PARTNERS,
                                    memberGated = false,
                                    isActiveMember = profile?.activeMember == true,
                                )
                            ) {
                                { destination = MainDestination.Partners }
                            } else {
                                null
                            },
                        // Notification inbox is core (no flag); reachable when
                        // Firebase is configured.
                        onOpenNotifications =
                            if (notificationsRepository != null) {
                                { destination = MainDestination.Notifications }
                            } else {
                                null
                            },
                        // Notification preferences: owner-write; reachable when configured.
                        onOpenNotificationSettings =
                            if (notificationSettingsRepository != null) {
                                { destination = MainDestination.NotificationSettings }
                            } else {
                                null
                            },
                        // Garage is a member feature (add/edit/delete are
                        // member-gated callables); entry requires membership.
                        onOpenGarage =
                            if (garageRepository != null && profile?.activeMember == true) {
                                { destination = MainDestination.Garage }
                            } else {
                                null
                            },
                        // Badges: owner-read; reachable when Firebase is configured.
                        onOpenBadges =
                            if (badgesRepository != null) {
                                { destination = MainDestination.Badges }
                            } else {
                                null
                            },
                        // Blocked users: owner-read management; reachable when configured.
                        onOpenBlocked =
                            if (blockingRepository != null) {
                                { destination = MainDestination.Blocked }
                            } else {
                                null
                            },
                        // Saved drives: owner-read history; reachable when configured.
                        onOpenSavedDrives =
                            if (drivesRepository != null) {
                                { destination = MainDestination.SavedDrives }
                            } else {
                                null
                            },
                        // Points wallet: owner-read; reachable when configured.
                        onOpenPoints =
                            if (pointsRepository != null) {
                                { destination = MainDestination.Points }
                            } else {
                                null
                            },
                        // Subscriptions: the member purchase path; reachable only
                        // when BOTH billing (Play) and the verifier (Firebase) are
                        // available — a config-less build has billing but no
                        // verifier, so the entry would otherwise be a dead end.
                        onOpenSubscription =
                            if (billingRepository != null && subscriptionVerifier != null) {
                                { destination = MainDestination.Subscription }
                            } else {
                                null
                            },
                        // Partner application: any authenticated user may apply.
                        onOpenPartnerApplication =
                            if (partnerApplicationCoordinator != null) {
                                { destination = MainDestination.PartnerApplication }
                            } else {
                                null
                            },
                        // Digital billboards: behind the digitalBillboards flag.
                        onOpenBillboards =
                            if (billboardsRepository != null &&
                                FeatureGate.isAvailable(
                                    flags = flags,
                                    flag = FeatureFlag.DIGITAL_BILLBOARDS,
                                    memberGated = false,
                                    isActiveMember = profile?.activeMember == true,
                                )
                            ) {
                                { destination = MainDestination.Billboards }
                            } else {
                                null
                            },
                        // Account deletion: signedIn (works while suspended).
                        onOpenAccountDeletion =
                            if (accountDeletionCoordinator != null) {
                                { destination = MainDestination.AccountDeletion }
                            } else {
                                null
                            },
                        // Partner-stats opt-in: behind the partnerStats flag.
                        onOpenPartnerStats =
                            if (partnerStatsRepository != null &&
                                FeatureGate.isAvailable(
                                    flags = flags,
                                    flag = FeatureFlag.PARTNER_STATS,
                                    memberGated = false,
                                    isActiveMember = profile?.activeMember == true,
                                )
                            ) {
                                { destination = MainDestination.PartnerStats }
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
private enum class MainDestination {
    Home,
    Profile,
    LiveLocation,
    Map,
    Events,
    CrownHunt,
    Partners,
    Notifications,
    NotificationSettings,
    Garage,
    Badges,
    Blocked,
    SavedDrives,
    Points,
    PartnerApplication,
    Billboards,
    AccountDeletion,
    PartnerStats,
    Subscription,
}

@Composable
private fun LoadingScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator()
        }
    }
}
