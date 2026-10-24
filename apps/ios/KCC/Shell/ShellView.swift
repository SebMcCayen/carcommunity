import SwiftUI
import UIKit

/// The five-tab, map-first shell. One persistent map sits behind the tab host;
/// History / Social / Garage render as translucent panels over the map per
/// `translucentPanelTabs`; the tab set, default tab, and the map-cover rules
/// all come from the pure ``ShellNavigation`` logic so behaviour stays unit-tested
/// outside SwiftUI.
struct ShellView: View {
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.colorScheme) private var colorScheme

    /// The signed-in session, threaded from ``RootView``. The config-less /
    /// unavailable state renders the bare shell with no profile entry —
    /// Android's "unavailable entries are omitted" hub rule.
    @Bindable var session: AuthSession
    /// Canonical identity supplied by the auth-state router so direct account
    /// replacement cannot reuse feature/profile state for the previous user.
    let authenticatedUid: String?
    let access: AccountAccess
    let featureFlags: FeatureFlags
    let diagnostics: IOSDiagnosticsComposition

    @State private var selectedTab: ShellTab = .defaultTab
    @State private var chatHasOpened = false
    /// The full-screen sub-route back-stack, held as the ONE pure value from
    /// ``ShellRouteStack``; every open/Back goes through its ``ShellRouteStack/opening(_:)``
    /// / ``ShellRouteStack/poppingOne()`` reducers rather than ad-hoc state.
    @State private var routes = ShellRouteStack.empty
    @State private var eventPath: [EventDetailRoute] = []
    @State private var pushNotifications = PushNotificationCoordinator.shared

    init(
        session: AuthSession,
        authenticatedUid: String?,
        access: AccountAccess,
        featureFlags: FeatureFlags,
        diagnostics: IOSDiagnosticsComposition,
        initialRoute: ShellRoute? = nil,
        initialTab: ShellTab? = nil
    ) {
        self.session = session
        self.authenticatedUid = authenticatedUid
        self.access = access
        self.featureFlags = featureFlags
        self.diagnostics = diagnostics
        let restoredTab = initialTab ?? .defaultTab
        _selectedTab = State(initialValue: ShellNavigation.restoredTab(restoredTab))
        _routes = State(initialValue: ShellNavigation.restoredRoutes(tab: restoredTab, route: initialRoute))
        _accountDeletionCoordinator = State(initialValue: AccountDeletionCoordinator(
            repository: FirebaseAccountDeletionRepository.createIfAvailable()
        ))
        _subscriptionCoordinator = State(initialValue: SubscriptionCoordinator(
            uid: authenticatedUid,
            store: StoreKitSubscriptionStore(),
            verifier: FirebaseAppleSubscriptionVerifier.createIfAvailable()
        ))
    }

    /// The shell's SINGLE map surface, composed once for the whole signed-in
    /// shell and never disposed — Android composes the surface once in
    /// `AuthenticatedApp` and covers/uncovers it; `@State` mirrors that by
    /// keeping this one instance for the shell's lifetime. Covered pages only
    /// stand it down via ``MapSurface/setActive(_:)`` (see the map-cover
    /// effect below), never recreate it.
    @State private var mapSurface = StubMapSurface()
    @State private var mapLayerPreferences = MapLayerPreferences()
    @State private var showMapLayers = false
    @State private var navSearchOpen = false
    @AccessibilityFocusState private var searchLauncherFocused: Bool
    @State private var showSavedPlacesPicker = false
    @State private var savedPlacesCoordinator: SavedPlacesCoordinator?
    @State private var savedPlacesProximity: MapPoint?

    /// Feature coordinators are composed once for the signed-in shell. Every
    /// factory is config-safe, so a build without GoogleService-Info.plist
    /// still renders each route's unavailable state rather than crashing.
    @State private var eventsCoordinator: EventsCoordinator?
    @State private var billboardsCoordinator: BillboardsCoordinator?
    @State private var leaderboardCoordinator: LeaderboardCoordinator?
    @State private var notificationsCoordinator: NotificationsInboxCoordinator?
    @State private var notificationSettingsCoordinator: NotificationSettingsCoordinator?
    @State private var notificationSettingsAvailable = false
    @State private var blockedUsersAvailable = false
    @State private var privacySettingsCoordinator: PrivacySettingsCoordinator?
    @State private var friendsCoordinator: FriendsCoordinator?
    @State private var conversationsCoordinator: ConversationsCoordinator?
    @State private var chatHubCoordinator: ChatHubCoordinator?
    @State private var crownHuntComposition: CrownHuntComposition?
    @State private var partnersCoordinator: PartnersCoordinator?
    @State private var feedbackCoordinator: FeedbackCoordinator?
    @State private var openTicketsCoordinator: OpenTicketsCoordinator?
    @State private var partnerApplicationCoordinator: PartnerApplicationCoordinator?
    @State private var accountDeletionCoordinator: AccountDeletionCoordinator
    @State private var subscriptionCoordinator: SubscriptionCoordinator
    @State private var liveLocationCoordinator: LiveLocationCoordinator?
    @State private var driveRecordingCoordinator: DriveRecordingCoordinator?
    @State private var locationPermissionCoordinator: LocationPermissionCoordinator?
    @State private var startDrivingGarage: GarageCoordinator?
    @State private var convoyManagementCoordinator: ConvoyManagementCoordinator?
    @State private var convoyAwareness = ConvoyAwarenessCoordinator()
    @State private var nearbyLive = NearbyLiveCoordinator()
    @State private var groupDriveMapUids: [String] = []
    @State private var convoyReactionCoordinator: ConvoyReactionCoordinator?
    @State private var convoyFollowMeCoordinator: ConvoyFollowMeCoordinator?
    @State private var liveLocationRepository: LiveLocationRepository?
    @State private var convoyCreateCoordinator: ConvoyCreateCoordinator?
    @State private var incidentMapCoordinator: IncidentMapCoordinator?
    @State private var convoyCreateVehicleId: String?
    @State private var convoyCreateReturnsToList = false
    @State private var selectedConvoyId: String?
    @State private var friendsRepository: FriendsRepository?
    @State private var conversationsRepository: ConversationsRepository?
    @State private var dmTarget: DmRouteTarget?
    @State private var dmCoordinator: ChatCoordinator?
    @State private var memberProfileTarget: MemberProfileRouteTarget?
    @State private var locationProvider = CoreLocationProvider()
    /// Live sharing is explicitly user-started and time-bounded, so its own
    /// provider may continue while the app is backgrounded. Keeping it
    /// separate prevents ordinary map-puck demand from ever gaining that
    /// behavior and lets Stop/Hide release live GPS independently of drives.
    @State private var liveLocationProvider = CoreLocationProvider(backgroundEnabled: true)
    /// Separate demand channel: only an explicit drive recording may keep
    /// positioning alive after screen lock. It is independent of both the
    /// foreground map provider and the time-bounded live-sharing provider.
    @State private var driveLocationProvider = CoreLocationProvider(backgroundEnabled: true)
    @State private var showStartDriving = false
    @State private var showStopConfirmation = false
    @State private var pendingSingleSessionStart = false
    @State private var pendingStartVehicleId: String?
    @State private var pendingConvoyCreate = false
    @State private var pendingConvoyVehicleId: String?
    @State private var pendingCreateIntent: SingleSessionCreateIntent?
    @State private var pendingSessionCommand: SingleSessionCommand?
    @State private var sessionCommandTask: Task<Void, Never>?
    @State private var sessionActionError = false
    @State private var whatsNewCoordinator = WhatsNewCoordinator()
    @State private var appUpdateCoordinator = AppUpdateCoordinator()
    @State private var appStoreUnavailable = false

    /// What is drawn over the shell's map right now — the ONE pure value
    /// every cover-derived decision reads. Turn-by-turn remains deliberately
    /// false per ADR-002; address search keeps the retained map live beneath
    /// its translucent chrome.
    private var mapCover: MapCover {
        ShellNavigation.mapCover(
            tab: selectedTab,
            route: routes.current,
            navigating: false,
            navSearchOpen: addressSearchPresented
        )
    }

    private var addressSearchPresented: Bool {
        ShellNavigation.addressSearchPresented(
            tab: selectedTab,
            route: routes.current,
            requested: navSearchOpen
        )
    }

    private var mapHomeChromeVisible: Bool {
        ShellNavigation.mapHomeChromeVisible(
            tab: selectedTab,
            route: routes.current,
            addressSearchRequested: navSearchOpen
        )
    }

    var body: some View {
        shellPresentation
            .task(id: signedInUid) { await wireFeatures() }
            .task(id: signedInUid) {
                openPendingPushDeepLink()
            }
            .onChange(of: pushNotifications.pendingDeepLink) { _, _ in
                openPendingPushDeepLink()
            }
            .task(id: crownFeatureWiringKey) { await wireCrownHunt() }
            .task(id: signedInUid) {
                whatsNewCoordinator.start()
                guard signedInUid != nil else { return }
                await appUpdateCoordinator.checkOnce()
            }
            .task(id: savedPlacesLocationSubscriptionKey) {
                await observeSavedPlacesLocation()
            }
            .onChange(of: scenePhase) { _, phase in
                guard phase == .active, signedInUid != nil else { return }
                appUpdateCoordinator.scheduleRequiredUpdateRecheck()
                driveRecordingCoordinator?.retryPendingRouteUploads()
            }
            .onChange(of: featureFlags.isEnabled(.liveLocation)) { _, enabled in
                liveLocationCoordinator?.canShare = enabled && !access.isRestricted
            }
            .onChange(of: chatFeatureWiringKey) { _, _ in
                applyChatFeatureGate()
            }
            .onChange(of: billboardFeatureWiringKey) { _, _ in
                wireBillboards()
            }
            .onChange(of: access) { _, access in
                partnersCoordinator?.updateAccess(access)
                if !partnerStatsEntryAvailable, routes.current == .partnerStats {
                    routes = routes.poppingOne()
                }
            }
            .onChange(of: routes.current) { _, route in
                if route != nil { closeAddressSearch() }
                if route != .events { eventPath = [] }
            }
            .onChange(of: featureFlags.isEnabled(.partners)) { _, enabled in
                if !enabled, routes.current == .partners {
                    partnersCoordinator?.clearSensitiveOfferState()
                    routes = routes.poppingOne()
                }
            }
            .onChange(of: featureFlags.isEnabled(.reportTicketsBrowser)) { _, enabled in
                if !enabled, routes.current == .openTickets {
                    routes = routes.poppingOne()
                }
            }
            .onChange(of: featureFlags.isEnabled(.partnerStats)) { _, enabled in
                if !enabled, routes.current == .partnerStats {
                    routes = routes.poppingOne()
                }
            }
            .task(id: convoyReactionSubscriptionKey) {
                convoyReactionCoordinator?.sync(convoyId: convoyReactionTargetId)
            }
            .task(id: convoyFollowMeSubscriptionKey) {
                syncFollowMe()
            }
            .onDisappear {
                // RootView removes the entire shell on sign-out or when a live
                // account update becomes restricted. Stop exact-location and
                // background drive collection synchronously during that swap.
                liveLocationCoordinator?.standDownForRestrictedAccess()
                driveRecordingCoordinator?.reset()
                billboardsCoordinator?.stop()
                mapSurface.setBillboardMarkers([])
            }
    }

    private var shellBase: some View {
        ZStack {
            // Exactly one native Mapbox view for the signed-in shell. Tabs and
            // routes cover it instead of recreating its Metal render surface.
            MapHomeView(
                surface: mapSurface,
                locationProvider: locationProvider,
                featureHealthReporter: FeatureHealthReporter.whenAuthenticated(
                    diagnostics.featureHealthReporter,
                    uid: signedInUid
                )
            )

            // PR #1165 (codex/ios-map-visibility): remove the opaque TabView
            // content host. Keep map feature tasks mounted across tab changes.
            content(for: .map)
                .allowsHitTesting(selectedTab == .map && routes.current == nil)
                .accessibilityHidden(selectedTab != .map || routes.current != nil)
            // Retain the Chat stack/drafts after first entry. Hiding it does
            // not stop the explicit live/drive session underneath.
            if chatHasOpened || selectedTab == .chat {
                chatTab
                    .opacity(selectedTab == .chat ? 1 : 0)
                    .allowsHitTesting(selectedTab == .chat && routes.current == nil)
                    .accessibilityHidden(selectedTab != .chat || routes.current != nil)
            }
            if selectedTab != .map && selectedTab != .chat {
                content(for: selectedTab)
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            primaryNavigation
        }
        // Route host: sub-routes render full-screen OVER the tab shell (the
        // Android shell's route-host pattern), each carrying its own back
        // affordance that pops one level via the pure stack.
        .overlay {
            if let route = routes.current {
                routeHost(for: route)
            }
        }
        .overlay {
            if addressSearchPresented, let savedPlacesCoordinator {
                AddressSearchOverlay(
                    coordinator: savedPlacesCoordinator,
                    proximity: savedPlacesProximity,
                    onSelect: showPlaceOnMap,
                    onManage: {
                        navSearchOpen = false
                        routes = routes.opening(.savedPlaces)
                    },
                    onClose: { navSearchOpen = false }
                )
            }
        }
    }

    private var shellInteractions: some View {
        shellBase
        // Drive the surface's liveness from the SAME pure cover value the
        // pages derive from — the iOS counterpart of Android's
        // `LaunchedEffect(mapCover) { mapSurface.setActive(...) }`:
        // Transparent/None keep it live, Opaque stands it down.
        .onChange(of: mapCover, initial: true) { _, cover in
            mapSurface.setActive(ShellMapHost.surfaceActive(cover: cover))
        }
        .onChange(of: colorScheme, initial: true) { _, scheme in
            mapSurface.setMapMode(
                mapLayerPreferences.effectiveMapMode(systemIsDark: scheme == .dark)
            )
        }
        .onAppear {
            applyMapLayerPreferences()
        }
        .onChange(of: selectedTab) { _, tab in
            if tab == .chat { chatHasOpened = true }
            if tab != .map { closeAddressSearch() }
            if tab != .map, routes.current == .chatHub {
                routes = routes.poppingOne()
            }
        }
        .onChange(of: navSearchOpen) { _, open in
            if !open { searchLauncherFocused = true }
        }
        .onChange(of: locationPermissionCoordinator?.state) { _, state in
            if state == .granted {
                startSingleSessionAfterPermissionGrant()
                openConvoyCreateAfterPermissionGrant()
            }
        }
        .onChange(of: liveLocationCoordinator?.isSharing) { _, sharing in
            guard let sharing,
                  let command = pendingSessionCommand,
                  command.isReconciled(isSharing: sharing)
            else { return }
            clearSessionCommand()
        }
        .onChange(of: liveLocationCoordinator?.sessionSnapshotRevision, initial: true) { _, _ in
            reconcileDriveRecording()
        }
    }

    private var shellSheets: some View {
        shellInteractions
        .sheet(isPresented: $showStartDriving, onDismiss: releaseStartDrivingGarage) {
            if let startDrivingGarage {
                StartDrivingSheet(
                    garage: startDrivingGarage,
                    isStarting: liveLocationCoordinator?.actionStatus == .working,
                    canStartSingleSession: liveLocationCoordinator?.canShare == true,
                    onStart: requestSingleSessionStart,
                    onConvoy: requestConvoyCreation
                )
            }
        }
        .sheet(isPresented: driveSummaryIsPresented) {
            if let driveRecordingCoordinator {
                DriveRecordingSummarySheet(coordinator: driveRecordingCoordinator)
            }
        }
        .sheet(isPresented: incidentReportIsPresented) {
            if let incidentMapCoordinator {
                IncidentReportSheet(
                    coordinator: incidentMapCoordinator
                )
            }
        }
        .sheet(isPresented: incidentDetailsIsPresented) {
            if let incidentMapCoordinator {
                IncidentDetailsSheet(coordinator: incidentMapCoordinator)
            }
        }
        .sheet(isPresented: policeDetailsIsPresented) {
            if let incidentMapCoordinator {
                PoliceDetailsSheet(coordinator: incidentMapCoordinator)
            }
        }
        .sheet(isPresented: crownSelectionIsPresented) {
            if let coordinator = crownHuntComposition?.mapCoordinator {
                CrownHuntCollectSheet(coordinator: coordinator)
            }
        }
        .sheet(isPresented: crownPerkMenuIsPresented) {
            if let coordinator = crownHuntComposition?.perkMapCoordinator {
                CrownPerkDeploySheet(coordinator: coordinator)
            }
        }
        .sheet(isPresented: $showMapLayers) {
            MapLayersSheet(
                preferences: mapLayerPreferences,
                systemIsDark: colorScheme == .dark,
                trafikverketDataShown: incidentMapCoordinator?.incidents.contains(where: \.isImported) == true,
                onTrafficAlertsChanged: { enabled in
                    incidentMapCoordinator?.setTrafficAlertsEnabled(enabled)
                },
                onTrafficChanged: mapSurface.setTrafficEnabled,
                onMapModeChanged: mapSurface.setMapMode,
                on3DChanged: mapSurface.set3DEnabled,
                onBrowsingZoomChanged: mapSurface.setBrowsingZoom
            )
        }
        .sheet(isPresented: $showSavedPlacesPicker) {
            if let savedPlacesCoordinator {
                SavedPlacesPickerSheet(
                    places: savedPlacesCoordinator.places,
                    onSelect: { saved in
                        showSavedPlacesPicker = false
                        showPlaceOnMap(saved.place)
                    },
                    onManage: {
                        showSavedPlacesPicker = false
                        routes = routes.opening(.savedPlaces)
                    }
                )
            }
        }
        .sheet(isPresented: whatsNewAnnouncementIsPresented) {
            if let announcement = whatsNewCoordinator.announcement {
                WhatsNewAnnouncementSheet(
                    announcement: announcement,
                    onShowAll: {
                        whatsNewCoordinator.acknowledge()
                        routes = routes.opening(.whatsNew)
                    },
                    onClose: whatsNewCoordinator.acknowledge
                )
            }
        }
    }

    private var shellPresentation: some View {
        shellSheets
        .foregroundStyle(KccTheme.text)
        .tint(KccTheme.driver)
        .background(KccTheme.page, ignoresSafeAreaEdges: .all)
        .confirmationDialog(
            "liveLocation.stop",
            isPresented: $showStopConfirmation,
            titleVisibility: .visible
        ) {
            Button("liveLocation.stop", role: .destructive) {
                stopSingleSession()
            }
            Button("shell.liveSharePromptCancel", role: .cancel) {}
        }
        .alert(Text("map.locationNeededTitle"), isPresented: permissionPromptIsPresented) {
            if locationPermissionCoordinator?.state == .rationale {
                Button("map.locationDismiss", role: .cancel) {
                    cancelPendingCreateAction()
                    locationPermissionCoordinator?.dismissRationale()
                }
                Button("map.locationAllow") {
                    locationPermissionCoordinator?.proceedFromRationale()
                }
            } else {
                Button("map.locationDismiss", role: .cancel) {
                    cancelPendingCreateAction()
                    locationPermissionCoordinator?.dismissSettingsHint()
                }
                Button("map.locationOpenSettings") {
                    locationPermissionCoordinator?.dismissSettingsHint()
                    guard let url = URL(string: UIApplication.openSettingsURLString) else {
                        cancelPendingCreateAction()
                        return
                    }
                    openURL(url)
                }
            }
        } message: {
            Text("map.locationPermissionBody")
        }
        .alert("liveLocation.statusError", isPresented: $sessionActionError) {
            Button("notifications.errorDismiss", role: .cancel) {}
        } message: {
            Text("liveLocation.error")
        }
        .alert(incidentFeedbackTitle, isPresented: incidentFeedbackIsPresented) {
            Button("notifications.errorDismiss", role: .cancel) {
                incidentMapCoordinator?.clearFeedback()
            }
        }
        .alert(convoyLeaveResultMessage, isPresented: convoyLeaveResultIsPresented) {
            Button("convoy.close", role: .cancel) {
                convoyManagementCoordinator?.clearLeaveResult()
            }
        }
        .alert(appUpdateTitle, isPresented: appUpdateIsPresented) {
            Button("appUpdate.update") { openAvailableUpdate() }
            if appUpdateCoordinator.availability?.isRequired != true {
                Button("appUpdate.dismiss", role: .cancel) {
                    appUpdateCoordinator.dismiss()
                }
            }
        } message: {
            Text(appUpdateMessage)
        }
        .alert("appUpdate.iosStoreUnavailable", isPresented: $appStoreUnavailable) {
            Button("notifications.errorDismiss", role: .cancel) {}
        }
    }

    /// Per-tab foreground content. The persistent map is owned by `body`, so
    /// map and panel tabs stay transparent wherever it should remain visible.
    @ViewBuilder
    private func content(for tab: ShellTab) -> some View {
        switch tab {
        case .map:
            Color.clear
                .ignoresSafeArea()
                // Account entries require a real session. Public Settings
                // (including legal links) also remain usable without config.
                .overlay(alignment: .topTrailing) {
                    if selectedTab == .map, routes.current == nil { shellMenuButton }
                }
                .overlay(alignment: .topLeading) {
                    if mapHomeChromeVisible {
                        mapSearchButton.padding(KccSpacing.s4)
                    }
                }
                .overlay(alignment: .bottomLeading) {
                    if mapHomeChromeVisible,
                       case .kept = driveRecordingCoordinator?.state {
                        Button {
                            routes = routes.opening(.myDrives)
                        } label: {
                            Label("shell.moreDrives", systemImage: "chart.bar.xaxis")
                                .padding(KccSpacing.s3)
                                .background(KccTheme.overlay, in: Capsule())
                        }
                        .accessibilityIdentifier("shell.postDriveHistory")
                        .padding(KccSpacing.s4)
                    }
                }
                .overlay(alignment: .top) {
                    if !groupDriveMapUids.isEmpty, mapHomeChromeVisible {
                        HStack(spacing: KccSpacing.s2) {
                            Label("groupDrive.mapTitle", systemImage: "car.2")
                            Button {
                                closeGroupDriveMap()
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                            }
                            .accessibilityLabel(Text("events.attendeesClose"))
                        }
                        .font(.system(size: KccTypeScale.bodySm, weight: KccTypeScale.semibold))
                        .padding(.horizontal, KccSpacing.s3)
                        .padding(.vertical, KccSpacing.s2)
                        .background(.regularMaterial, in: Capsule())
                        .padding(.top, KccSpacing.s4)
                    }
                }
                .overlay(alignment: .trailing) {
                    if mapHomeChromeVisible { mapCommunicationControls }
                }
                .overlay {
                    if let crownMapCoordinator = crownHuntComposition?.mapCoordinator {
                        CrownHuntMapOverlay(
                            coordinator: crownMapCoordinator,
                            projection: mapSurface
                        )
                    }
                    if let perkMapCoordinator = crownHuntComposition?.perkMapCoordinator {
                        CrownPerkMapOverlay(
                            coordinator: perkMapCoordinator,
                            projection: mapSurface,
                            ownFix: crownHuntComposition?.mapCoordinator?.latestFix
                        )
                    }
                    if let incidentMapCoordinator {
                        IncidentMapOverlay(
                            coordinator: incidentMapCoordinator,
                            projection: mapSurface
                        )
                    }
                    if let billboardsCoordinator {
                        BillboardMapOverlay(
                            coordinator: billboardsCoordinator,
                            projection: mapSurface
                        )
                    }
                    if liveLocationFeatureEnabled {
                        ConvoyMapAwarenessOverlay(
                            members: convoyAwareness.visibleMembers,
                            imageURLs: convoyAwareness.imageURLs,
                            projection: mapSurface
                        )
                        NearbyLiveOverlay(
                            coordinator: nearbyLive,
                            projection: mapSurface
                        )
                    }
                }
                .overlay {
                    if mapHomeChromeVisible,
                       convoyReactionTargetId != nil,
                       let convoyReactionCoordinator {
                        ConvoyReactionControls(
                            coordinator: convoyReactionCoordinator,
                            followMeCoordinator: convoyFollowMeCoordinator
                        )
                    }
                }
                .overlay(alignment: .top) {
                    if mapHomeChromeVisible,
                       let coordinator = convoyManagementCoordinator,
                       let convoy = coordinator.activeConvoy {
                        ConvoyStatusBar(
                            coordinator: coordinator,
                            convoy: convoy,
                            friendsCoordinator: friendsCoordinator,
                            awareness: convoyAwareness,
                            mapSurface: mapSurface,
                            liveLocationEnabled: liveLocationFeatureEnabled,
                            viewerUid: signedInUid,
                            onOpenMemberProfile: openMemberProfile
                        )
                        .padding(.horizontal, KccSpacing.s4)
                        .padding(.top, KccSpacing.s12 + KccSpacing.s3)
                    }
                }
                .overlay {
                    if mapHomeChromeVisible,
                       incidentMapCoordinator?.pendingMapReportType != nil {
                        IncidentLocationPickerControls(
                            canConfirm: currentMapCenter != nil,
                            confirm: submitMapCenterIncident,
                            cancel: { incidentMapCoordinator?.cancelMapSelection() }
                        )
                    }
                }
                .overlay(alignment: .top) {
                    if mapHomeChromeVisible,
                       incidentMapCoordinator?.proximityAlert != nil {
                        PoliceProximityBanner {
                            incidentMapCoordinator?.dismissProximityAlert()
                        }
                        .padding(.horizontal, KccSpacing.s4)
                        .padding(.top, KccSpacing.s12 + KccSpacing.s12)
                    }
                }
                .task(id: incidentMapLifecycleKey) {
                    guard foregroundMapWorkActive, let incidentMapCoordinator else { return }
                    incidentMapCoordinator.start(
                        surface: mapSurface,
                        provider: locationProvider
                    )
                    do { try await Task.sleep(for: .seconds(86_400)) } catch {}
                    incidentMapCoordinator.stop()
                }
                .task(id: crownMapLifecycleKey) {
                    guard foregroundMapWorkActive,
                          let coordinator = crownHuntComposition?.mapCoordinator else { return }
                    let perkCoordinator = crownHuntComposition?.perkMapCoordinator
                    coordinator.start()
                    perkCoordinator?.start()
                    await coordinator.refresh(
                        camera: mapSurface.cameraSnapshot,
                        visibleRadiusMeters: mapSurface.visibleRadiusMeters()
                    )
                    do { try await Task.sleep(for: .seconds(86_400)) } catch {}
                    coordinator.stop()
                    perkCoordinator?.stop()
                }
                .task(id: incidentMapRefreshKey) {
                    guard foregroundMapWorkActive, let incidentMapCoordinator else { return }
                    // Camera snapshots update while panning. Cancellation turns
                    // this into a trailing-edge debounce, avoiding callable spam.
                    do { try await Task.sleep(for: .milliseconds(700)) } catch { return }
                    await incidentMapCoordinator.refresh(surface: mapSurface)
                }
                .task(id: crownMapRefreshKey) {
                    guard foregroundMapWorkActive,
                          let coordinator = crownHuntComposition?.mapCoordinator else { return }
                    do { try await Task.sleep(for: .milliseconds(700)) } catch { return }
                    await coordinator.refresh(
                        camera: mapSurface.cameraSnapshot,
                        visibleRadiusMeters: mapSurface.visibleRadiusMeters()
                    )
                }
                .task(id: convoyAwarenessSubscriptionKey) {
                    convoyAwareness.sync(
                        convoy: convoyAwarenessTargetConvoy,
                        repository: liveLocationRepository,
                        currentUid: signedInUid
                    )
                    applyConvoyFocus()
                }
                .task(id: nearbyLiveTaskKey) {
                    guard nearbyLiveShouldRun,
                          let repository = liveLocationRepository,
                          let uid = signedInUid
                    else {
                        nearbyLive.deactivate()
                        return
                    }
                    nearbyLive.activate(
                        repository: repository, currentUid: uid,
                        excludedUids: nearbyLiveExcludedUids)
                    if !groupDriveMapUids.isEmpty {
                        nearbyLive.activateRoster(
                            repository: repository,
                            currentUid: uid,
                            participantUids: groupDriveMapUids,
                            excludedUids: nearbyLiveExcludedUids
                        )
                        do { try await Task.sleep(for: .seconds(86_400)) } catch {}
                        return
                    }
                    // This lifecycle task deliberately does not depend on the
                    // camera. Each cadence tick reads the latest settled view,
                    // so rapid pans cannot restart discovery or compress its
                    // monotonic 20-second request interval.
                    do { try await Task.sleep(for: .milliseconds(700)) } catch { return }
                    while !Task.isCancelled {
                        if let camera = mapSurface.cameraSnapshot {
                            await nearbyLive.poll(
                                center: MapPoint(
                                    longitude: camera.longitude,
                                    latitude: camera.latitude
                                ),
                                radiusMeters: mapSurface.visibleRadiusMeters()
                                    ?? defaultNearbyLiveRadiusMeters
                            )
                            do {
                                try await Task.sleep(for: NearbyLiveCoordinator.discoveryInterval)
                            } catch { return }
                        } else {
                            do { try await Task.sleep(for: .seconds(1)) } catch { return }
                        }
                    }
                }
                .onChange(of: nearbyLiveExcludedUids) { _, excludedUids in
                    guard nearbyLiveShouldRun else { return }
                    if groupDriveMapUids.isEmpty {
                        nearbyLive.activate(
                            repository: liveLocationRepository,
                            currentUid: signedInUid,
                            excludedUids: excludedUids
                        )
                    } else {
                        nearbyLive.activateRoster(
                            repository: liveLocationRepository,
                            currentUid: signedInUid,
                            participantUids: groupDriveMapUids,
                            excludedUids: excludedUids
                        )
                    }
                }
                .onChange(of: billboardsCoordinator?.markers, initial: true) { _, markers in
                    mapSurface.setBillboardMarkers(markers ?? [])
                }
                .onChange(of: convoyAwareness.focusMode) { _, _ in applyConvoyFocus() }
                .onChange(of: convoyAwareness.positions) { _, _ in
                    applyConvoyFocus()
                    syncFollowMe()
                }
                .task(id: convoyAwareness.focusMode) {
                    guard convoyAwareness.focusMode == .convoy else { return }
                    while !Task.isCancelled {
                        do {
                            try await Task.sleep(
                                for: .seconds(ConvoyArrowPlanner.staleAfter / 4)
                            )
                        } catch {
                            return
                        }
                        applyConvoyFocus()
                    }
                }
        case .chat:
            chatTab
        case .history:
            myDrives
        case .social:
            panelTab {
                SocialHubPanel(
                    crownHuntEnabled: featureFlags.isEnabled(.crownHunt),
                    partnersEnabled: featureFlags.isEnabled(.partners)
                        && partnersCoordinator != nil,
                    onOpenEvents: { openEvents() },
                    onOpenConvoys: openConvoyManagement,
                    onOpenCrownHunt: { routes = routes.opening(.crownHunt) },
                    onOpenLeaderboard: { routes = routes.opening(.leaderboard) },
                    onOpenPartners: { routes = routes.opening(.partners) }
                )
            }
        case .garage:
            panelTab { GaragePanel() }
        case .create:
            // Create is an action, never a destination. `tabSelection` keeps
            // Map selected and presents Start driving (or Stop) instead.
            Color.clear.ignoresSafeArea()
        }
    }

    /// A translucent panel tab: the persistent map remains visible above the
    /// bottom-anchored card. Dismiss returns to the Map tab.
    private func panelTab(@ViewBuilder content: @escaping () -> some View) -> some View {
        TranslucentShellPanel(
            onDismiss: { selectedTab = .map },
            content: content
        )
    }

    private var shellMenuButton: some View {
        Menu {
            if case .signedIn = session.state {
                Button {
                    routes = routes.opening(.profile)
                } label: {
                    Label("shell.moreProfile", systemImage: "person")
                }
            }
            Button {
                routes = routes.opening(.profile).opening(.myDrives)
            } label: {
                Label("shell.moreDrives", systemImage: "chart.bar.xaxis")
            }
            .accessibilityIdentifier("shell.myDrives")
            if friendsCoordinator != nil {
                Button {
                    routes = routes.opening(.friends)
                } label: {
                    Label("shell.friendsTitle", systemImage: "person.2")
                }
            }
            Button {
                routes = routes.opening(.settings)
            } label: {
                Label("settingsMenu.title", systemImage: "gearshape")
            }
            .accessibilityIdentifier("shell.settings")
            if savedPlacesCoordinator != nil {
                Button {
                    routes = routes.opening(.savedPlaces)
                } label: {
                    Label("settingsMenu.savedPlaces", systemImage: "bookmark")
                }
            }
            if feedbackCoordinator != nil {
                Button {
                    routes = routes.opening(.feedback)
                } label: {
                    Label("shell.moreFeedback", systemImage: "exclamationmark.bubble")
                }
            }
            if partnerApplicationCoordinator != nil {
                Button {
                    routes = routes.opening(.partnerApplication)
                } label: {
                    Label("shell.morePartnerApplication", systemImage: "briefcase")
                }
            }
            if partnerStatsEntryAvailable {
                Button {
                    routes = routes.opening(.partnerStats)
                } label: {
                    Label("shell.morePartnerStats", systemImage: "hand.raised")
                }
            }
        } label: {
            Label(
                signedInUid == nil
                    ? LocalizedStringKey("settingsMenu.title")
                    : LocalizedStringKey("shell.moreProfile"),
                systemImage: signedInUid == nil ? "gearshape" : "person.circle"
            )
            .labelStyle(.iconOnly)
            .font(.title2)
            .frame(width: 48, height: 48)
            .kccMapControl()
        }
        .accessibilityIdentifier("shell.menu")
        .padding(KccSpacing.s4)
    }

    private var mapCommunicationControls: some View {
        VStack(spacing: KccSpacing.s3) {
            if incidentMapCoordinator?.available == true {
                Button {
                    incidentMapCoordinator?.reportSheetPresented = true
                } label: {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .frame(width: 48, height: 48)
                        .kccMapControl()
                }
                .accessibilityLabel(Text("incidents.reportButton"))
            }

            MapLayersButton(isPresented: $showMapLayers)
            Button {
                let next = mapSurface.compassMode.toggled()
                mapSurface.setCompassMode(next)
                if next == .northUp { mapSurface.recenterNorthUp() }
                else { mapSurface.recenter() }
            } label: {
                Image(systemName: "location.north.line")
                    .frame(width: 48, height: 48)
                    .kccMapControl()
            }
            .accessibilityLabel(Text(mapSurface.compassMode == .northUp
                ? "shell.compassCourseUp" : "shell.compassNorthUp"))
            .accessibilityIdentifier("shell.compass")

            if savedPlacesCoordinator != nil {
                Button {
                    showSavedPlacesPicker = true
                } label: {
                    Image(systemName: "bookmark.fill")
                        .frame(width: 48, height: 48)
                        .kccMapControl()
                }
                .accessibilityLabel(Text("shell.savedPlacesButton"))
            }

            if let coordinator = crownHuntComposition?.perkMapCoordinator, coordinator.isAvailable {
                CrownPerkMapControl(coordinator: coordinator)
            }

            if let liveLocationCoordinator, liveLocationCoordinator.canPresentScreen {
                Button {
                    routes = routes.opening(.liveLocation)
                } label: {
                    Image(systemName: "location.fill")
                        .frame(width: 48, height: 48)
                        .kccMapControl()
                }
                .accessibilityLabel(Text("liveLocation.screenTitle"))
            }
        }
        .font(.system(size: 20, weight: .semibold))
        .padding(KccSpacing.s4)
    }

    private var mapSearchButton: some View {
        Button {
            navSearchOpen = true
        } label: {
            Image(systemName: "magnifyingglass")
                .font(.title3.weight(.semibold))
                .frame(width: 48, height: 48)
                .kccMapControl()
        }
        .accessibilityLabel(Text("shell.searchExpand"))
        .accessibilityIdentifier("shell.search")
        .accessibilityFocused($searchLauncherFocused)
    }

    private var primaryNavigation: some View {
        HStack(spacing: 0) {
            ForEach(ShellTab.allCases, id: \.self) { tab in
                Button {
                    tabSelection.wrappedValue = tab
                } label: {
                    VStack(spacing: KccSpacing.s1) {
                        Image(systemName: tabSystemImage(tab))
                            .font(.title3.weight(.semibold))
                            .frame(height: 28)
                            .accessibilityHidden(true)
                        Text(LocalizedStringKey(tabTitle(tab))).font(.caption2)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .foregroundStyle(tab == .create ? KccTheme.onGold : KccTheme.text)
                    .background {
                        if tab == .create {
                            RoundedRectangle(cornerRadius: KccRadius.xl)
                                .fill(liveLocationCoordinator?.isSharing == true ? KccPalette.errorRed : KccPalette.crownGold)
                        }
                    }
                    .overlay(alignment: .bottom) {
                        if selectedTab == tab {
                            Capsule().fill(KccTheme.driver).frame(width: 28, height: 3)
                        }
                    }
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("shell.tab.\(tab.accessibilityID)")
                .accessibilityAddTraits(selectedTab == tab ? .isSelected : [])
            }
        }
        .padding(.horizontal, KccSpacing.s2)
        .padding(.vertical, KccSpacing.s2)
        .background(KccTheme.overlay, ignoresSafeAreaEdges: .bottom)
        .overlay(alignment: .top) { Rectangle().fill(KccTheme.border).frame(height: 1) }
    }

    private var myDrives: some View {
        DrivesPanel(sharingEnabled: FeatureGate.isAvailable(
            flags: featureFlags, flag: .socialSharing, memberGated: false, access: access
        ))
        .kccReadingSurface()
        .accessibilityIdentifier("shell.myDrivesScreen")
    }

    private var chatTab: some View {
        NavigationStack { chatHubContent }
            .kccReadingSurface()
    }

    private func applyMapLayerPreferences() {
        incidentMapCoordinator?.setTrafficAlertsEnabled(mapLayerPreferences.trafficAlertsEnabled)
        mapSurface.setTrafficEnabled(mapLayerPreferences.trafficEnabled)
        mapSurface.setMapMode(
            mapLayerPreferences.effectiveMapMode(systemIsDark: colorScheme == .dark)
        )
        mapSurface.set3DEnabled(mapLayerPreferences.is3D)
        mapSurface.setBrowsingZoom(mapLayerPreferences.browsingZoom)
    }

    @ViewBuilder
    private func routeHost(for route: ShellRoute) -> some View {
        switch route {
        case .profile:
            ProfileScreen(
                uid: signedInUid,
                displayName: signedInDisplayName,
                onSignOut: { endDeletionSession() },
                onBack: { routes = routes.poppingOne() },
                onOpenPoints: { routes = routes.opening(.points) },
                onOpenDrives: { routes = routes.opening(.myDrives) },
                onOpenWhatsNew: { routes = routes.opening(.whatsNew) },
                onOpenAccountDeletion: { routes = routes.opening(.accountDeletion) }
            )
        case .myDrives:
            routeNavigation { myDrives }
        case .points:
            routeNavigation {
                PointsScreen(uid: signedInUid)
            }
        case .events:
            // The read-only events list, opened from the Social hub. The
            // NavigationStack hosts the screen's `navigationTitle`; Back pops
            // one level via the pure stack, like every route.
            NavigationStack(path: $eventPath) {
                EventsScreen(
                    coordinator: eventsCoordinator,
                    locationProvider: locationProvider,
                    onShowGroupDriveOnMap: showGroupDriveOnMap
                )
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button {
                                routes = routes.poppingOne()
                            } label: {
                                Label("shell.back", systemImage: "chevron.backward")
                            }
                        }
                    }
            }
            // A live chat-flag change must invalidate NavigationStack's
            // current detail/chat destination, not only replace the list's
            // repository behind an already-open child view.
            .id(eventChatFeatureWiringKey)
        case .leaderboard:
            routeNavigation {
                LeaderboardScreen(coordinator: leaderboardCoordinator)
            }
        case .partners:
            if featureFlags.isEnabled(.partners), let partnersCoordinator {
                PartnersScreen(
                    coordinator: partnersCoordinator,
                    onBack: { routes = routes.poppingOne() }
                )
            } else {
                unavailableRoute
            }
        case .partnerApplication:
            if let partnerApplicationCoordinator {
                routeNavigation {
                    PartnerApplicationScreen(
                        coordinator: partnerApplicationCoordinator,
                        onClose: { routes = routes.poppingOne() }
                    )
                }
            } else {
                unavailableRoute
            }
        case .crownHunt:
            if let composition = crownHuntComposition {
                CrownHuntHubView(
                    statsCoordinator: composition.statsCoordinator,
                    claimsCoordinator: composition.claimsCoordinator,
                    shopCoordinator: composition.shopCoordinator,
                    crownHuntEnabled: composition.flags.crownHuntEnabled,
                    onBack: { routes = routes.poppingOne() }
                )
                .background(.background, ignoresSafeAreaEdges: .all)
            } else {
                unavailableRoute
            }
        case .liveLocation:
            if let liveLocationCoordinator, let locationPermissionCoordinator {
                LiveLocationScreen(
                    coordinator: liveLocationCoordinator,
                    permissionCoordinator: locationPermissionCoordinator,
                    onBack: { routes = routes.poppingOne() }
                )
            } else {
                unavailableRoute
            }
        case .chatHub:
            chatHubOverlay
        case .notifications:
            routeNavigation {
                NotificationsInboxScreen(
                    coordinator: notificationsCoordinator,
                    onOpenSettings: { routes = routes.opening(.notificationSettings) }
                )
            }
        case .notificationSettings:
            routeNavigation {
                NotificationSettingsScreen(
                    coordinator: notificationSettingsCoordinator,
                    pushNotifications: pushNotifications
                )
            }
        case .blocked:
            BlockedUsersScreen(uid: signedInUid) {
                routes = routes.poppingOne()
            }
        case .settings:
            routeNavigation {
                SettingsScreen(actions: settingsActions)
            }
        case .whatsNew:
            routeNavigation {
                WhatsNewScreen(entries: whatsNewCoordinator.entries)
            }
        case .subscription:
            routeNavigation {
                MembershipScreen(access: access, coordinator: subscriptionCoordinator)
            }
        case .savedPlaces:
            if let savedPlacesCoordinator {
                routeNavigation {
                    SavedPlacesScreen(
                        coordinator: savedPlacesCoordinator,
                        proximity: savedPlacesProximity
                    )
                }
            } else {
                unavailableRoute
            }
        case .accountDeletion:
            AccountDeletionScreen(
                coordinator: accountDeletionCoordinator,
                onDeleted: { endDeletionSession(purgeRoutes: true) },
                onReauthenticate: { endDeletionSession(purgeRoutes: false) },
                onBack: { routes = routes.poppingOne() }
            )
        case .partnerStats:
            if partnerStatsEntryAvailable, let privacySettingsCoordinator {
                routeNavigation {
                    PrivacySettingsScreen(coordinator: privacySettingsCoordinator)
                }
            } else {
                unavailableRoute
            }
        case .friends:
            routeNavigation {
                FriendsScreen(
                    coordinator: friendsCoordinator,
                    onMessageFriend: { friend in
                        openDm(uid: friend.uid, displayName: friend.displayName)
                    },
                    onViewProfile: { friend in
                        openMemberProfile(uid: friend.uid, displayName: friend.displayName)
                    }
                )
            }
        case .convoys:
            if let convoyManagementCoordinator {
                NavigationStack {
                    Group {
                        if let convoyCreateCoordinator {
                            ConvoyCreateScreen(
                                coordinator: convoyCreateCoordinator,
                                friendsCoordinator: friendsCoordinator,
                                vehicleId: convoyCreateVehicleId,
                                onCreated: completeConvoyCreation
                            )
                        } else if let selectedConvoyId,
                                  let convoy = convoyManagementCoordinator.snapshot?.convoys
                                      .first(where: { $0.convoyId == selectedConvoyId }) {
                            ConvoyDetailScreen(
                                coordinator: convoyManagementCoordinator,
                                convoy: convoy,
                                friendsCoordinator: friendsCoordinator
                            )
                        } else {
                            ConvoyManagementScreen(
                                coordinator: convoyManagementCoordinator,
                                onCreate: openConvoyCreateFromList,
                                onJoined: completeConvoyJoin,
                                onOpen: { selectedConvoyId = $0.convoyId }
                            )
                        }
                    }
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button {
                                closeConvoyScreen()
                            } label: {
                                Label("shell.back", systemImage: "chevron.backward")
                            }
                            .disabled(convoyActionIsWorking)
                        }
                    }
                }
                .background(.background, ignoresSafeAreaEdges: .all)
            } else {
                unavailableRoute
            }
        case .conversations:
            routeNavigation {
                ConversationsScreen(
                    coordinator: conversationsCoordinator,
                    makeNewDialogueCoordinator: makeNewDialogueCoordinator,
                    onOpenConversation: openDm
                )
            }
        case .memberProfile:
            if let target = memberProfileTarget, let viewerUid = signedInUid {
                routeNavigation {
                    MemberProfileScreen(
                        targetUid: target.uid,
                        viewerUid: viewerUid,
                        friends: friendsRepository,
                        onMessage: openDm
                    )
                }
            } else {
                unavailableRoute
            }
        case .chat:
            if let target = dmTarget {
                routeNavigation {
                    ChatScreen(
                        coordinator: dmCoordinator,
                        otherName: target.displayName,
                        currentUid: signedInUid ?? "",
                        onViewProfile: {
                            openMemberProfile(uid: target.uid, displayName: target.displayName)
                        }
                    )
                }
            } else {
                unavailableRoute
            }
        case .feedback:
            if let feedbackCoordinator {
                routeNavigation(onBack: feedbackCoordinator.reset) {
                    FeedbackScreen(
                        coordinator: feedbackCoordinator,
                        openTicketsEnabled: featureFlags.isEnabled(.reportTicketsBrowser),
                        onOpenTickets: { routes = routes.opening(.openTickets) },
                        onClose: { routes = routes.poppingOne() }
                    )
                }
            } else {
                unavailableRoute
            }
        case .openTickets:
            if featureFlags.isEnabled(.reportTicketsBrowser), let openTicketsCoordinator {
                routeNavigation {
                    OpenTicketsScreen(coordinator: openTicketsCoordinator)
                }
            } else {
                unavailableRoute
            }
        default:
            // No other route is reachable yet — each renders here as its
            // feature is ported. Falling back to nothing (rather than
            // trapping) keeps an unexpected value harmless.
            EmptyView()
        }
    }

    private var settingsActions: SettingsActions {
        SettingsActions(
            onManageSubscription: { routes = routes.opening(.subscription) },
            onSavedPlaces: savedPlacesCoordinator != nil
                ? { routes = routes.opening(.savedPlaces) } : nil,
            onNotificationSettings: notificationSettingsAvailable
                ? { routes = routes.opening(.notificationSettings) } : nil,
            onBlockedUsers: blockedUsersAvailable
                ? { routes = routes.opening(.blocked) } : nil,
            onPartnerStats: partnerStatsEntryAvailable
                ? { routes = routes.opening(.partnerStats) } : nil,
            onFeedback: feedbackCoordinator != nil
                ? { routes = routes.opening(.feedback) } : nil,
            onDeleteAccount: authenticatedUid != nil
                ? { routes = routes.opening(.accountDeletion) } : nil,
            onWhatsNew: { routes = routes.opening(.whatsNew) }
        )
    }

    private func endDeletionSession(purgeRoutes: Bool = false) -> Bool {
        guard let authenticatedUid else { return true }
        if purgeRoutes {
            driveRecordingCoordinator?.purgeForAccountDeletion()
            // Also cover deletion before feature wiring creates the coordinator.
            FileDriveRecordingJournal.purgeOwnerData(ownerId: authenticatedUid)
        }
        return session.signOut(ifSignedInAs: authenticatedUid)
    }

    private var whatsNewAnnouncementIsPresented: Binding<Bool> {
        Binding(
            get: { whatsNewCoordinator.announcement != nil },
            set: { presented in
                if !presented { whatsNewCoordinator.acknowledge() }
            }
        )
    }

    private var updatePromptSuppressedForDriving: Bool {
        if liveLocationCoordinator?.isSharing == true { return true }
        return driveRecordingCoordinator?.state.summary != nil
    }

    private var appUpdateIsPresented: Binding<Bool> {
        Binding(
            get: {
                !appStoreUnavailable && appUpdateCoordinator.shouldPresent(
                    isDriving: updatePromptSuppressedForDriving,
                    announcementIsPresented: whatsNewCoordinator.announcement != nil
                )
            },
            set: { _ in }
        )
    }

    private var appUpdateTitle: LocalizedStringKey {
        appUpdateCoordinator.availability?.isRequired == true
            ? "appUpdate.requiredTitle" : "appUpdate.title"
    }

    private var appUpdateMessage: LocalizedStringKey {
        appUpdateCoordinator.availability?.isRequired == true
            ? "appUpdate.iosRequiredMessage" : "appUpdate.iosMessage"
    }

    private func openAvailableUpdate() {
        guard let url = appUpdateCoordinator.availability?.storeURL else {
            appStoreUnavailable = true
            return
        }
        openURL(url) { accepted in
            if accepted {
                appUpdateCoordinator.accepted()
            } else {
                appStoreUnavailable = true
            }
        }
    }

    private var routeBackButton: some View {
        Button {
            routes = routes.poppingOne()
        } label: {
            Label("shell.back", systemImage: "chevron.backward")
        }
        .padding(KccSpacing.s4)
    }

    private var chatHubContent: some View {
        ChatHubScreen(
                coordinator: chatHubCoordinator,
                conversationsCoordinator: conversationsCoordinator,
                makeNewDialogueCoordinator: makeNewDialogueCoordinator,
                onOpenConversation: openDm,
                notificationsCoordinator: notificationsCoordinator,
                onOpenNotificationSettings: {
                    routes = routes.opening(.notificationSettings)
                },
                onOpenEvents: {
                    openEvents()
                }
            )
            .kccReadingSurface()
    }

    private var chatHubOverlay: some View {
        NavigationStack {
            chatHubContent
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        routes = routes.poppingOne()
                    } label: {
                        Label("shell.back", systemImage: "chevron.backward")
                    }
                }
            }
        }
        .kccReadingSurface()
    }

    private var unavailableRoute: some View {
        VStack(spacing: KccSpacing.s3) {
            Text("shell.unavailable")
                .foregroundStyle(.secondary)
            routeBackButton
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .kccReadingSurface()
    }

    private func routeNavigation<Content: View>(
        onBack: (() -> Void)? = nil,
        @ViewBuilder content: () -> Content
    ) -> some View {
        NavigationStack {
            content()
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        if let onBack {
                            Button {
                                onBack()
                                routes = routes.poppingOne()
                            } label: {
                                Label("shell.back", systemImage: "chevron.backward")
                            }
                            .padding(KccSpacing.s4)
                        } else {
                            routeBackButton
                        }
                    }
                }
        }
        .kccReadingSurface()
    }

    private var makeNewDialogueCoordinator: (() -> NewDialogueCoordinator)? {
        friendsRepository.map { repository in
            { NewDialogueCoordinator(friends: repository) }
        }
    }

    private func openDm(uid: String, displayName: String?) {
        guard !uid.isEmpty, let conversationsRepository, let selfUid = signedInUid else { return }
        dmTarget = DmRouteTarget(uid: uid, displayName: displayName)
        dmCoordinator = ChatCoordinator(
            repository: conversationsRepository,
            selfUid: selfUid,
            otherUid: uid
        )
        routes = routes.opening(.chat)
    }

    private func openPendingPushDeepLink() {
        guard let link = pushNotifications.pendingDeepLink, signedInUid != nil else { return }
        if link.target == .dm, link.entityId != nil, conversationsRepository == nil {
            return
        }
        _ = pushNotifications.consumePendingDeepLink()
        switch link.target {
        case .dm:
            if let uid = link.entityId { openDm(uid: uid, displayName: nil) }
            else { routes = routes.opening(.conversations) }
        case .communityChat:
            chatHubCoordinator?.select(.community)
            selectedTab = .chat
            routes = .empty
        case .convoyChat:
            chatHubCoordinator?.select(.convoys)
            selectedTab = .chat
            routes = .empty
        case .convoys:
            selectedConvoyId = link.entityId
            routes = routes.opening(.convoys)
        case .friends:
            routes = routes.opening(.friends)
        case .event:
            openEvents(eventId: link.entityId)
        case .subscription:
            routes = routes.opening(.subscription)
        case .notifications:
            routes = routes.opening(.notifications)
        }
    }

    private func openMemberProfile(uid: String, displayName: String?) {
        guard !uid.isEmpty, uid != signedInUid else { return }
        memberProfileTarget = MemberProfileRouteTarget(uid: uid, displayName: displayName)
        routes = routes.opening(.memberProfile)
    }

    private func openEvents(eventId: String? = nil) {
        eventPath = eventId.map { [EventDetailRoute(eventId: $0)] } ?? []
        routes = routes.opening(.events)
    }

    private var tabSelection: Binding<ShellTab> {
        Binding(
            get: { selectedTab },
            set: { tab in
                if routes.current == .chatHub { routes = routes.poppingOne() }
                guard tab == .create else {
                    selectedTab = tab
                    return
                }
                selectedTab = .map
                sessionActionError = false
                guard pendingSessionCommand == nil else { return }
                guard let liveLocationCoordinator else {
                    pendingCreateIntent = SingleSessionCreateIntent(identity: signedInUid)
                    return
                }
                pendingCreateIntent = nil
                presentSingleSessionAction(using: liveLocationCoordinator)
            }
        )
    }

    private func presentSingleSessionAction(using coordinator: LiveLocationCoordinator) {
        guard coordinator.actionStatus != .working else { return }
        let actions = StartDrivingSelection.actions(
            wired: coordinator.wired,
            canShareLive: coordinator.canShare
        )
        if coordinator.isSharing {
            showStopConfirmation = true
        } else if actions.showChooser {
            startDrivingGarage = GarageCoordinator(
                repository: FirebaseVehiclesRepository.createIfAvailable(),
                uid: signedInUid
            )
            showStartDriving = true
        } else {
            routes = routes.opening(.liveLocation)
        }
    }

    private func requestConvoyCreation(vehicleId: String?) {
        sessionActionError = false
        guard pendingSessionCommand == nil,
              let liveLocationCoordinator,
              liveLocationCoordinator.wired
        else {
            routes = routes.opening(.liveLocation)
            return
        }
        let actions = StartDrivingSelection.actions(
            wired: liveLocationCoordinator.wired,
            canShareLive: liveLocationCoordinator.canShare
        )
        guard actions.convoyRequiresLocation else {
            openConvoyCreate(vehicleId: vehicleId)
            return
        }
        guard let locationPermissionCoordinator else {
            routes = routes.opening(.liveLocation)
            return
        }
        pendingConvoyCreate = true
        pendingConvoyVehicleId = vehicleId
        locationPermissionCoordinator.requestAccess()
        if locationPermissionCoordinator.state == .granted {
            openConvoyCreateAfterPermissionGrant()
        }
    }

    private func openConvoyCreateAfterPermissionGrant() {
        guard pendingConvoyCreate else { return }
        let vehicleId = pendingConvoyVehicleId
        pendingConvoyCreate = false
        pendingConvoyVehicleId = nil
        guard pendingSessionCommand == nil else { return }
        openConvoyCreate(vehicleId: vehicleId)
    }

    private func openConvoyCreate(vehicleId: String?) {
        convoyCreateReturnsToList = false
        convoyCreateVehicleId = vehicleId
        convoyCreateCoordinator = ConvoyCreateCoordinator(
            repository: FirebaseConvoyCreateRepository.createIfAvailable()
        )
        routes = routes.opening(.convoys)
    }

    private func closeConvoyCreate() {
        guard !convoyCreationIsWorking else { return }
        convoyCreateCoordinator = nil
        convoyCreateVehicleId = nil
        convoyCreateReturnsToList = false
    }

    private func closeConvoyScreen() {
        guard !convoyCreationIsWorking else { return }
        if convoyCreateCoordinator != nil, convoyCreateReturnsToList {
            closeConvoyCreate()
        } else if let selectedId = selectedConvoyId,
                  convoyManagementCoordinator?.snapshot?.convoys.contains(
                      where: { $0.convoyId == selectedId }
                  ) == true {
            selectedConvoyId = nil
        } else {
            selectedConvoyId = nil
            closeConvoyCreate()
            if routes.current == .convoys { routes = routes.poppingOne() }
        }
    }

    private func openConvoyManagement() {
        closeConvoyCreate()
        selectedConvoyId = nil
        routes = routes.opening(.convoys)
    }

    private func openConvoyCreateFromList() {
        convoyCreateReturnsToList = true
        convoyCreateVehicleId = nil
        convoyCreateCoordinator = ConvoyCreateCoordinator(
            repository: FirebaseConvoyCreateRepository.createIfAvailable()
        )
    }

    private var convoyCreationIsWorking: Bool {
        guard let convoyCreateCoordinator else { return false }
        if case .working = convoyCreateCoordinator.createState { return true }
        return false
    }

    private var convoyActionIsWorking: Bool {
        convoyCreationIsWorking
            || !(convoyManagementCoordinator?.busyConvoyIds.isEmpty ?? true)
    }

    private var convoyLeaveResultIsPresented: Binding<Bool> {
        Binding(
            get: { convoyManagementCoordinator?.lastLeaveResult != nil },
            set: { if !$0 { convoyManagementCoordinator?.clearLeaveResult() } }
        )
    }

    private var convoyLeaveResultMessage: LocalizedStringKey {
        guard let result = convoyManagementCoordinator?.lastLeaveResult else {
            return "convoy.leftConvoyToast"
        }
        if result.outcome == .leftAndEnded {
            return "convoy.leftAndEndedToast"
        }
        return result.newLeaderUid?.isEmpty == false
            ? "convoy.leftAsLeaderToast"
            : "convoy.leftConvoyToast"
    }

    private func completeConvoyCreation(_ created: ConvoyCreated) {
        guard !created.convoyId.isEmpty else { return }
        if let convoy = created.convoy {
            convoyManagementCoordinator?.recordCreatedConvoy(convoy)
        }
        closeConvoyCreate()
        if routes.current == .convoys { routes = routes.poppingOne() }
        selectedTab = .map
        Task { _ = await convoyManagementCoordinator?.refresh() }
        retainConvoySessionStartUntilObserved()
    }

    private func completeConvoyJoin(_ convoy: ConvoyItem) {
        guard !convoy.convoyId.isEmpty else { return }
        closeConvoyCreate()
        if routes.current == .convoys { routes = routes.poppingOne() }
        selectedTab = .map
        if convoy.status == .active { retainConvoySessionStartUntilObserved() }
    }

    private func retainConvoySessionStartUntilObserved() {
        guard pendingSessionCommand == nil,
              let liveLocationCoordinator,
              liveLocationCoordinator.canShare
        else { return }
        let identity = signedInUid
        if SingleSessionCommand.starting.isReconciled(isSharing: liveLocationCoordinator.isSharing) {
            return
        }
        pendingSessionCommand = .starting
        sessionCommandTask = Task {
            do {
                try await Task.sleep(for: .seconds(15))
            } catch {
                return
            }
            guard !Task.isCancelled,
                  identity == signedInUid,
                  liveLocationCoordinator === self.liveLocationCoordinator,
                  pendingSessionCommand == .starting
            else { return }
            pendingSessionCommand = nil
            sessionCommandTask = nil
            sessionActionError = true
        }
    }

    private func tabTitle(_ tab: ShellTab) -> String {
        if tab == .create,
           (liveLocationCoordinator?.isSharing == true || pendingSessionCommand == .starting) {
            return "liveLocation.stop"
        }
        return tab.titleKey
    }

    private func tabSystemImage(_ tab: ShellTab) -> String {
        if tab == .create,
           (liveLocationCoordinator?.isSharing == true || pendingSessionCommand == .starting) {
            return "stop.circle.fill"
        }
        return tab.systemImage
    }

    private func requestSingleSessionStart(vehicleId: String?) {
        sessionActionError = false
        guard pendingSessionCommand == nil,
              let liveLocationCoordinator,
              liveLocationCoordinator.canShare,
              liveLocationCoordinator.wired
        else {
            routes = routes.opening(.liveLocation)
            return
        }
        guard let locationPermissionCoordinator else {
            routes = routes.opening(.liveLocation)
            return
        }
        pendingSingleSessionStart = true
        pendingStartVehicleId = vehicleId
        locationPermissionCoordinator.requestAccess()
        if locationPermissionCoordinator.state == .granted {
            startSingleSessionAfterPermissionGrant()
        }
    }

    private func startSingleSessionAfterPermissionGrant() {
        guard pendingSingleSessionStart else { return }
        guard pendingSessionCommand == nil,
              let liveLocationCoordinator,
              liveLocationCoordinator.canShare,
              liveLocationCoordinator.wired
        else {
            cancelPendingSingleSessionStart()
            routes = routes.opening(.liveLocation)
            return
        }
        let vehicleId = pendingStartVehicleId
        let identity = signedInUid
        cancelPendingSingleSessionStart()
        pendingSessionCommand = .starting
        sessionCommandTask = Task {
            let result = await liveLocationCoordinator.startSharing(vehicleId: vehicleId)
            await finishSessionCommand(
                result,
                command: .starting,
                identity: identity,
                coordinator: liveLocationCoordinator
            )
        }
    }

    private func stopSingleSession() {
        guard pendingSessionCommand == nil, let liveLocationCoordinator else { return }
        sessionActionError = false
        let identity = signedInUid
        pendingSessionCommand = .stopping
        sessionCommandTask = Task {
            let result = await liveLocationCoordinator.stopSharing()
            await finishSessionCommand(
                result,
                command: .stopping,
                identity: identity,
                coordinator: liveLocationCoordinator
            )
        }
    }

    private func finishSessionCommand(
        _ result: LiveCommandResult,
        command: SingleSessionCommand,
        identity: String?,
        coordinator: LiveLocationCoordinator
    ) async {
        guard !Task.isCancelled,
              identity == signedInUid,
              coordinator === liveLocationCoordinator,
              pendingSessionCommand == command
        else { return }

        switch result {
        case .failed:
            pendingSessionCommand = nil
            sessionCommandTask = nil
            sessionActionError = true
        case .busy:
            pendingSessionCommand = nil
            sessionCommandTask = nil
        case .success:
            if command.isReconciled(isSharing: coordinator.isSharing) {
                clearSessionCommand()
                return
            }
            // A successful callable normally echoes through RTDB immediately.
            // Bound the optimistic lock so a lost listener event cannot leave
            // Create/Stop disabled for the rest of the signed-in session.
            do {
                try await Task.sleep(for: .seconds(15))
            } catch {
                return
            }
            guard !Task.isCancelled,
                  identity == signedInUid,
                  coordinator === liveLocationCoordinator,
                  pendingSessionCommand == command
            else { return }
            pendingSessionCommand = nil
            sessionCommandTask = nil
            sessionActionError = true
        }
    }

    private func cancelPendingSingleSessionStart() {
        pendingSingleSessionStart = false
        pendingStartVehicleId = nil
    }

    private func cancelPendingCreateAction() {
        cancelPendingSingleSessionStart()
        pendingConvoyCreate = false
        pendingConvoyVehicleId = nil
    }

    private func clearSessionCommand() {
        sessionCommandTask?.cancel()
        sessionCommandTask = nil
        pendingSessionCommand = nil
    }

    private func releaseStartDrivingGarage() {
        startDrivingGarage = nil
    }

    private var permissionPromptIsPresented: Binding<Bool> {
        Binding(
            get: {
                (pendingSingleSessionStart || pendingConvoyCreate)
                    && (locationPermissionCoordinator?.state == .rationale
                        || locationPermissionCoordinator?.state == .deniedNeedsSettings)
            },
            set: { _ in }
        )
    }

    @MainActor
    private func wireFeatures() async {
        let uid = signedInUid
        let eventChatEnabled = ChatFeatureGate.eventChatEnabled(
            flags: featureFlags,
            access: access
        )
        let channelAndDirectChatEnabled = ChatFeatureGate.channelAndDirectChatEnabled(
            access: access
        )

        if pendingCreateIntent?.belongs(to: uid) == false {
            pendingCreateIntent = nil
        }

        showStartDriving = false
        showStopConfirmation = false
        cancelPendingCreateAction()
        clearSessionCommand()
        sessionActionError = false

        // Remove a conversation built for the previous identity before doing
        // any asynchronous flag work. If Chat was opened from a parent hub,
        // return to that hub; otherwise close the route entirely.
        while routes.current == .chat || routes.current == .memberProfile {
            routes = routes.poppingOne()
        }
        dmTarget = nil
        dmCoordinator = nil
        memberProfileTarget = nil
        if routes.current == .convoys { routes = routes.poppingOne() }
        convoyManagementCoordinator = nil
        convoyAwareness.sync(convoy: nil, repository: nil, currentUid: nil)
        nearbyLive.deactivate()
        groupDriveMapUids = []
        convoyReactionCoordinator?.sync(convoyId: nil)
        convoyReactionCoordinator = nil
        convoyFollowMeCoordinator?.stop()
        convoyFollowMeCoordinator = nil
        liveLocationRepository = nil
        convoyCreateCoordinator = nil
        incidentMapCoordinator?.stop()
        incidentMapCoordinator = nil
        billboardsCoordinator?.stop()
        billboardsCoordinator = nil
        mapSurface.setBillboardMarkers([])
        wireBillboards()
        convoyCreateVehicleId = nil
        convoyCreateReturnsToList = false
        selectedConvoyId = nil
        liveLocationCoordinator?.shutdown()
        liveLocationCoordinator = nil
        driveRecordingCoordinator?.reset()
        driveRecordingCoordinator = nil
        startDrivingGarage = nil
        crownHuntComposition?.mapCoordinator?.stop()
        crownHuntComposition?.perkMapCoordinator?.stop()
        crownHuntComposition = nil
        partnersCoordinator?.clearSensitiveOfferState()
        partnersCoordinator = nil
        savedPlacesCoordinator = nil
        navSearchOpen = false
        showSavedPlacesPicker = false
        openTicketsCoordinator?.stop()
        feedbackCoordinator = nil
        openTicketsCoordinator = nil
        partnerApplicationCoordinator = nil

        let friends = FirebaseFriendsRepository.createIfAvailable()
        let conversations = FirebaseConversationsRepository.createIfAvailable()
        let notifications = FirebaseNotificationsRepository.createIfAvailable()
        if locationPermissionCoordinator == nil {
            let permissionCoordinator = LocationPermissionCoordinator(provider: locationProvider)
            permissionCoordinator.start()
            locationPermissionCoordinator = permissionCoordinator
        }
        friendsRepository = friends
        conversationsRepository = conversations
        let incidentMap = IncidentMapCoordinator(
            incidentRepository: FirebaseIncidentRepository.createIfAvailable(),
            policeRepository: FirebasePoliceRepository.createIfAvailable(),
            currentUid: uid
        )
        incidentMap.setTrafficAlertsEnabled(mapLayerPreferences.trafficAlertsEnabled)
        incidentMapCoordinator = incidentMap
        let eventChat = eventChatEnabled
            ? FirebaseEventChatRepository.createIfAvailable() : nil
        eventsCoordinator = FirebaseEventsRepository.createIfAvailable().map {
            EventsCoordinator(
                repository: $0,
                eventChatRepository: eventChat,
                groupDriveRepository: FirebaseGroupDriveRepository.createIfAvailable(),
                locationProvider: locationProvider,
                subscriptionRepository: FirebaseSubscriptionStateRepository.createIfAvailable()
            )
        }
        leaderboardCoordinator = LeaderboardCoordinator(
            repository: FirebaseLeaderboardRepository.createIfAvailable()
        )
        partnersCoordinator = FirebasePartnersRepository.createIfAvailable().map {
            PartnersCoordinator(
                repository: $0,
                subscriptionRepository: FirebaseSubscriptionStateRepository.createIfAvailable(),
                uid: uid,
                access: access
            )
        }
        let notificationSettingsRepository =
            FirebaseNotificationSettingsRepository.createIfAvailable()
        notificationSettingsAvailable = notificationSettingsRepository != nil && uid != nil
        blockedUsersAvailable = FirebaseBlockingRepository.createIfAvailable() != nil && uid != nil
        feedbackCoordinator = FeedbackCoordinator(
            repository: FirebaseFeedbackRepository.createIfAvailable()
        )
        openTicketsCoordinator = OpenTicketsCoordinator(
            repository: FirebaseOpenTicketsRepository.createIfAvailable()
        )
        partnerApplicationCoordinator = FirebasePartnerApplicationRepository.createIfAvailable().map {
            PartnerApplicationCoordinator(repository: $0)
        }
        notificationsCoordinator = NotificationsInboxCoordinator(
            repository: notifications,
            uid: uid,
            errorReporter: diagnostics.clientErrorReporter
        )
        notificationSettingsCoordinator = NotificationSettingsCoordinator(
            repository: notificationSettingsRepository,
            uid: uid
        )
        if let uid, !uid.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let searchClient: AddressSearchClient
            if let token = MapboxConfiguration.accessToken() {
                searchClient = MapboxAddressSearchClient(
                    token: token,
                    language: Locale.current.language.languageCode?.identifier ?? "sv"
                )
            } else {
                searchClient = UnavailableAddressSearchClient()
            }
            savedPlacesCoordinator = SavedPlacesCoordinator(
                store: ProtectedFileSavedPlacesStore(uid: uid),
                searchClient: searchClient
            )
        } else {
            savedPlacesCoordinator = SavedPlacesCoordinator(
                store: TransientSavedPlacesStore(),
                searchClient: UnavailableAddressSearchClient()
            )
        }
        privacySettingsCoordinator = PrivacySettingsCoordinator(
            repository: FirebasePrivacySettingsRepository.createIfAvailable(),
            uid: uid
        )
        friendsCoordinator = friends.map {
            FriendsCoordinator(
                repository: $0,
                pointsRepository: FirebaseFriendPointsRepository.createIfAvailable()
            )
        }
        conversationsCoordinator = nil
        if channelAndDirectChatEnabled, let conversations, let uid {
            conversationsCoordinator = ConversationsCoordinator(
                repository: conversations,
                blockVisibility: FirebaseBlockVisibilityRepository.createOrEmpty(),
                uid: uid
            )
        }
        chatHubCoordinator = ChatHubCoordinator(
            communityRepository: channelAndDirectChatEnabled
                ? FirebaseCommunityChatRepository.createIfAvailable() : nil,
            convoyRepository: channelAndDirectChatEnabled
                ? FirebaseConvoyChatRepository.createIfAvailable() : nil,
            chatRepliesEnabled: featureFlags.isEnabled(.chatReplies)
        )
        // A cold-start push can arrive before asynchronous feature composition
        // installs the DM repository. Retry now that every destination has its
        // required coordinator instead of losing the one-shot handoff.
        openPendingPushDeepLink()
        let convoyManagement = ConvoyManagementCoordinator(
            repository: FirebaseConvoyManagementRepository.createIfAvailable()
        )
        convoyManagementCoordinator = convoyManagement
        convoyReactionCoordinator = FirebaseConvoyReactionRepository.createIfAvailable().map { repository in
            ConvoyReactionCoordinator(
                repository: repository,
                onPoliceSent: { [incidentMap] in
                    _ = await incidentMap.reportPoliceAtCurrentLocation(
                        source: "convoy",
                        surfaceError: false
                    )
                }
            )
        }
        convoyFollowMeCoordinator = FirebaseConvoyFollowMeRepository.createIfAvailable().map {
            ConvoyFollowMeCoordinator(repository: $0)
        }

        let liveRepository = FirebaseLiveLocationRepository.createIfAvailable()
        liveLocationRepository = liveRepository
        let liveLocation = LiveLocationCoordinator(
            repository: liveRepository,
            provider: liveLocationProvider,
            canShare: FeatureGate.isAvailable(
                flags: featureFlags,
                flag: .liveLocation,
                memberGated: false,
                access: access
            )
        )
        // Observe the own session from the shell so a flag-disabled feature
        // can still reveal its control when an existing session needs Stop or
        // Hide access. Starting this listener does not request GPS permission.
        liveLocation.start()
        liveLocationCoordinator = liveLocation
        let driveJournal = uid.flatMap { FileDriveRecordingJournal(ownerId: $0) }
        driveRecordingCoordinator = DriveRecordingCoordinator(
            repository: FirebaseDriveRecordingRepository.createIfAvailable(),
            provider: driveLocationProvider,
            journal: driveJournal,
            uploadSpool: driveJournal?.routeUploadSpool
        )
        if pendingCreateIntent?.belongs(to: uid) == true {
            pendingCreateIntent = nil
            presentSingleSessionAction(using: liveLocation)
        }

        // Convoy discovery is independent of the map's live-session wiring.
        // Load it last so a slow callable cannot delay location controls.
        await convoyManagement.load()
        // The shell may have disappeared (restriction/sign-out) or switched
        // identities while the callable was suspended. Its onDisappear path
        // has already stopped exact-location/background recording; never let
        // this stale continuation reconcile and start it again.
        guard !Task.isCancelled, uid == signedInUid else { return }
        reconcileDriveRecording()
        await wireCrownHunt()
    }

    @MainActor
    private func wireCrownHunt() async {
        let uid = signedInUid
        let composition = await CrownHuntComposition.live(
            uid: uid,
            passesMemberGate: MemberGating.allows(access: access),
            featureFlags: featureFlags,
            locationProvider: locationProvider
        )
        guard !Task.isCancelled, uid == signedInUid else { return }
        crownHuntComposition = composition
    }

    @MainActor
    private func wireBillboards() {
        billboardsCoordinator?.stop()
        billboardsCoordinator = nil
        mapSurface.setBillboardMarkers([])
        guard signedInUid != nil,
              FeatureGate.isAvailable(
                flags: featureFlags,
                flag: .digitalBillboards,
                memberGated: false,
                access: access
              ) else { return }
        let coordinator = BillboardsCoordinator(
            repository: FirebaseBillboardsRepository.createIfAvailable()
        )
        billboardsCoordinator = coordinator
        coordinator.start()
    }

    @MainActor
    private func applyChatFeatureGate() {
        let eventChatEnabled = ChatFeatureGate.eventChatEnabled(
            flags: featureFlags,
            access: access
        )
        let channelChatEnabled = ChatFeatureGate.channelAndDirectChatEnabled(access: access)
        chatHubCoordinator = ChatHubCoordinator(
            communityRepository: channelChatEnabled
                ? FirebaseCommunityChatRepository.createIfAvailable() : nil,
            convoyRepository: channelChatEnabled
                ? FirebaseConvoyChatRepository.createIfAvailable() : nil,
            chatRepliesEnabled: featureFlags.isEnabled(.chatReplies)
        )
        // Keep the loaded events list and its listener, but replace the
        // repository captured by future detail routes. The view identity key
        // above resets an already-open detail/chat destination.
        eventsCoordinator?.updateEventChatRepository(
            eventChatEnabled ? FirebaseEventChatRepository.createIfAvailable() : nil
        )
    }

    private var driveSummaryIsPresented: Binding<Bool> {
        Binding(
            get: { driveRecordingCoordinator?.state.presentsSummary == true },
            set: { _ in }
        )
    }

    private func reconcileDriveRecording() {
        guard let driveRecordingCoordinator, let liveLocationCoordinator,
              liveLocationCoordinator.hasReceivedSessionSnapshot else { return }
        let session = liveLocationCoordinator.session
        if let session {
            driveRecordingCoordinator.updateContext(driveRecordingContext(session))
        }
        guard LiveLocation.isSharing(session, at: Date()), let session else {
            driveRecordingCoordinator.endSession(
                context: session.map(driveRecordingContext)
            )
            return
        }
        driveRecordingCoordinator.start(context: driveRecordingContext(session))
    }

    private func driveRecordingContext(_ session: LiveSessionInfo) -> DriveRecordingContext {
        let convoy = session.convoyId.flatMap { id in
            convoyManagementCoordinator?.snapshot?.convoys.first { $0.convoyId == id }
        }
        let members = convoy?.acceptedMembers.compactMap { member -> ConvoyDriveMember? in
            guard member.uid != signedInUid else { return nil }
            return ConvoyDriveMember(
                uid: member.uid,
                displayName: member.displayName,
                avatarPath: nil
            )
        } ?? []
        return DriveRecordingContext(
            sourceSessionId: session.sessionId,
            vehicleId: session.vehicleId,
            carImagePath: session.carImagePath,
            convoyMembers: members,
            expiresAt: session.expiresAt
        )
    }

    private var signedInDisplayName: String? {
        if case .signedIn(_, let displayName) = session.state {
            return displayName
        }
        return nil
    }

    private var currentMapCenter: MapPoint? {
        mapSurface.cameraSnapshot.map {
            MapPoint(longitude: $0.longitude, latitude: $0.latitude)
        }
    }

    private var savedPlacesLocationSubscriptionKey: String {
        let needed = addressSearchPresented || routes.current == .savedPlaces
        return "\(needed)|\(locationProvider.authorization)"
    }

    private func observeSavedPlacesLocation() async {
        savedPlacesProximity = nil
        guard (addressSearchPresented || routes.current == .savedPlaces),
              locationProvider.authorization.isAuthorized
        else { return }
        let stream = locationProvider.fixes()
        let point = await withTaskGroup(of: MapPoint?.self) { group in
            group.addTask {
                for await fix in stream {
                    let point = MapPoint(longitude: fix.longitude, latitude: fix.latitude)
                    if SavedPlacesPolicy.isValid(point: point) { return point }
                }
                return nil
            }
            group.addTask {
                try? await Task.sleep(for: .seconds(3))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
        guard !Task.isCancelled, let point else { return }
        savedPlacesProximity = point
        if let savedPlacesCoordinator, !savedPlacesCoordinator.query.isEmpty {
            savedPlacesCoordinator.updateQuery(savedPlacesCoordinator.query, proximity: point)
        }
    }

    private func showPlaceOnMap(_ place: PlaceSuggestion) {
        guard SavedPlacesPolicy.isValid(point: place.point) else { return }
        savedPlacesCoordinator?.clearSearch()
        navSearchOpen = false
        selectedTab = .map
        mapSurface.centerOn(place.point)
    }

    private func closeAddressSearch() {
        savedPlacesCoordinator?.clearSearch()
        navSearchOpen = false
    }

    private func submitMapCenterIncident() {
        guard let incidentMapCoordinator,
              let type = incidentMapCoordinator.pendingMapReportType,
              let point = currentMapCenter else { return }
        incidentMapCoordinator.cancelMapSelection()
        Task { await incidentMapCoordinator.report(type, at: point) }
    }

    private var incidentMapLifecycleKey: String {
        "\(signedInUid ?? "unavailable")|\(incidentMapCoordinator == nil ? "off" : "on")|\(foregroundMapWorkActive)"
    }

    private var crownMapLifecycleKey: String {
        [
            signedInUid ?? "unavailable",
            crownHuntComposition?.mapCoordinator?.isAvailable == true ? "on" : "off",
            foregroundMapWorkActive ? "active" : "inactive"
        ].joined(separator: "|")
    }

    private var incidentMapRefreshKey: String {
        let camera = mapSurface.cameraSnapshot
        return [
            incidentMapLifecycleKey,
            camera.map { "\($0.latitude)|\($0.longitude)|\($0.zoom)" } ?? "no-camera"
        ].joined(separator: "|")
    }

    private var crownMapRefreshKey: String {
        let camera = mapSurface.cameraSnapshot
        return [
            crownMapLifecycleKey,
            camera.map { "\($0.latitude)|\($0.longitude)|\($0.zoom)" } ?? "no-camera"
        ].joined(separator: "|")
    }

    private var crownSelectionIsPresented: Binding<Bool> {
        Binding(
            get: { crownHuntComposition?.mapCoordinator?.selectedTarget != nil },
            set: { if !$0 { crownHuntComposition?.mapCoordinator?.dismissSelection() } }
        )
    }

    private var crownPerkMenuIsPresented: Binding<Bool> {
        Binding(
            get: { crownHuntComposition?.perkMapCoordinator?.menuPresented == true },
            set: { crownHuntComposition?.perkMapCoordinator?.menuPresented = $0 }
        )
    }

    private var incidentReportIsPresented: Binding<Bool> {
        Binding(
            get: { incidentMapCoordinator?.reportSheetPresented == true },
            set: { incidentMapCoordinator?.reportSheetPresented = $0 }
        )
    }

    private var incidentDetailsIsPresented: Binding<Bool> {
        Binding(
            get: { incidentMapCoordinator?.selectedIncident != nil },
            set: { if !$0 { incidentMapCoordinator?.selectedIncident = nil } }
        )
    }

    private var policeDetailsIsPresented: Binding<Bool> {
        Binding(
            get: { incidentMapCoordinator?.selectedPolice != nil },
            set: { if !$0 { incidentMapCoordinator?.selectedPolice = nil } }
        )
    }

    private var incidentFeedbackIsPresented: Binding<Bool> {
        Binding(
            get: {
                incidentMapCoordinator?.feedback != nil
                    && incidentMapCoordinator?.selectedIncident == nil
                    && incidentMapCoordinator?.selectedPolice == nil
            },
            set: { if !$0 { incidentMapCoordinator?.clearFeedback() } }
        )
    }

    private var incidentFeedbackTitle: LocalizedStringKey {
        guard let feedback = incidentMapCoordinator?.feedback else {
            return "incidents.reportError"
        }
        switch feedback {
        case .success(let key), .error(let key): return LocalizedStringKey(key)
        }
    }

    private var signedInUid: String? {
        authenticatedUid
    }

    private var crownFeatureWiringKey: String {
        [
            signedInUid ?? "unavailable",
            String(featureFlags.isEnabled(.liveLocation)),
            String(featureFlags.isEnabled(.crownHunt)),
            String(featureFlags.isEnabled(.crownHuntSpawn)),
            String(featureFlags.isEnabled(.crownHuntPerks)),
            String(featureFlags.isEnabled(.crownHuntLiveShareScoring)),
            String(MemberGating.allows(access: access))
        ].joined(separator: "|")
    }

    private var chatFeatureWiringKey: String {
        "\(featureFlags.isEnabled(.chat))|\(featureFlags.isEnabled(.chatReplies))|\(access.isRestricted)"
    }

    private var billboardFeatureWiringKey: String {
        "\(signedInUid ?? "unavailable")|\(featureFlags.isEnabled(.digitalBillboards))|\(access.isRestricted)"
    }

    private var foregroundMapWorkActive: Bool {
        ShellMapHost.foregroundMapWorkActive(
            cover: mapCover,
            sceneIsActive: scenePhase == .active
        )
    }

    private var eventChatFeatureWiringKey: String {
        String(ChatFeatureGate.eventChatEnabled(flags: featureFlags, access: access))
    }

    private var liveLocationFeatureEnabled: Bool {
        crownHuntComposition?.flags.liveLocationEnabled == true
    }

    private var partnerStatsEntryAvailable: Bool {
        ShellNavigation.partnerStatsEntryAvailable(
            flags: featureFlags,
            access: access,
            repositoryAvailable: privacySettingsCoordinator?.isAvailable == true
        )
    }

    private var convoyAwarenessTargetConvoy: ConvoyItem? {
        guard liveLocationFeatureEnabled else { return nil }
        return convoyManagementCoordinator?.activeConvoy
    }

    private var convoyAwarenessSubscriptionKey: String {
        guard let convoy = convoyAwarenessTargetConvoy else {
            return "disabled|\(signedInUid ?? "")"
        }
        return "enabled|\(convoy.convoyId)|\(convoy.livePositionUids.sorted().joined(separator: ","))|\(signedInUid ?? "")"
    }

    private var nearbyLiveShouldRun: Bool {
        liveLocationFeatureEnabled
            && selectedTab == .map
            && mapCover == .none
            && scenePhase == .active
            && liveLocationRepository != nil
            && signedInUid != nil
    }

    private var nearbyLiveExcludedUids: Set<String> {
        Set(convoyAwarenessTargetConvoy?.livePositionUids ?? [])
    }

    private var nearbyLiveTaskKey: String {
        "\(nearbyLiveShouldRun)|\(signedInUid ?? "")|\(groupDriveMapUids.joined(separator: ","))"
    }

    private func showGroupDriveOnMap(_ participantUids: [String]) {
        nearbyLive.deactivate()
        var seen = Set<String>()
        groupDriveMapUids = participantUids.filter {
            !$0.isEmpty && seen.insert($0).inserted
        }
        routes = .empty
        selectedTab = .map
    }

    private func closeGroupDriveMap() {
        nearbyLive.deactivate()
        groupDriveMapUids = []
    }

    /// Reactions follow the same map-chrome gate as Android: listen only while
    /// the map home is unobscured and an accepted, non-ended convoy owns the bar.
    private var convoyReactionTargetId: String? {
        ConvoyReactionSubscription.targetConvoyId(
            mapIsUncovered: mapCover == .none,
            activeConvoyId: convoyManagementCoordinator?.activeConvoy?.convoyId,
            appIsActive: scenePhase == .active
        )
    }

    private var convoyReactionSubscriptionKey: String {
        "\(convoyReactionCoordinator != nil)|\(convoyReactionTargetId ?? "")"
    }

    private var convoyFollowMeSubscriptionKey: String {
        let convoy = convoyManagementCoordinator?.activeConvoy
        let members = convoy?.acceptedMembers.map(\.uid).sorted().joined(separator: ",") ?? ""
        return "\(convoyFollowMeCoordinator != nil)|\(convoy?.convoyId ?? "")|\(members)|\(signedInUid ?? "")"
    }

    private func syncFollowMe() {
        convoyFollowMeCoordinator?.sync(
            convoy: convoyManagementCoordinator?.activeConvoy,
            currentUid: signedInUid,
            positions: convoyAwareness.positions,
            surface: mapSurface
        )
    }

    private func applyConvoyFocus() {
        let hasConvoyContext = convoyAwarenessTargetConvoy != nil
        mapSurface.setConvoyFit(
            points: convoyAwareness.fitPoints(),
            focusEnabled: convoyAwareness.focusMode == .convoy,
            userPoint: convoyAwareness.ownPoint(),
            followSelfEnabled: hasConvoyContext
        )
    }
}

private struct DmRouteTarget: Equatable, Sendable {
    let uid: String
    let displayName: String?
}

private struct MemberProfileRouteTarget: Equatable, Sendable {
    let uid: String
    let displayName: String?
}

extension ShellTab {
    /// Localized tab title. Keys live in the generated `Localizable.xcstrings`
    /// and are the same semantic names as `contracts/localization`
    /// (`shell.tabMap` …) — see `apps/ios/scripts/generate-strings.mjs`.
    var title: LocalizedStringKey { LocalizedStringKey(titleKey) }

    var titleKey: String {
        switch self {
        case .map: "shell.tabMap"
        case .history: "shell.moreDrives"
        case .chat: "shell.tabChat"
        case .create: "shell.tabCreate"
        case .social: "shell.tabSocial"
        case .garage: "shell.tabGarage"
        }
    }

    var systemImage: String {
        switch self {
        case .map: "map"
        case .history: "clock"
        case .chat: "bubble.left.and.bubble.right"
        case .create: "plus.circle"
        case .social: "person.2"
        case .garage: "car"
        }
    }

    var accessibilityID: String {
        switch self {
        case .map: "map"
        case .history: "history"
        case .chat: "chat"
        case .create: "create"
        case .social: "social"
        case .garage: "garage"
        }
    }
}

#Preview {
    // Config-less session: the bare shell, no profile entry.
    ShellView(
        session: AuthSession(repository: nil),
        authenticatedUid: nil,
        access: .unrestrictedCommunity,
        featureFlags: .contractDefaults,
        diagnostics: IOSDiagnosticsComposition()
    )
}
