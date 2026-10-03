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

    @State private var selectedTab: ShellTab = .defaultTab
    /// The full-screen sub-route back-stack, held as the ONE pure value from
    /// ``ShellRouteStack``; every open/Back goes through its ``ShellRouteStack/opening(_:)``
    /// / ``ShellRouteStack/poppingOne()`` reducers rather than ad-hoc state.
    @State private var routes = ShellRouteStack.empty

    init(
        session: AuthSession,
        authenticatedUid: String?,
        access: AccountAccess,
        featureFlags: FeatureFlags,
        initialRoute: ShellRoute? = nil,
        initialTab: ShellTab? = nil
    ) {
        self.session = session
        self.authenticatedUid = authenticatedUid
        self.access = access
        self.featureFlags = featureFlags
        _selectedTab = State(initialValue: initialTab ?? .defaultTab)
        _routes = State(initialValue: initialRoute.map { .empty.opening($0) } ?? .empty)
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

    /// Feature coordinators are composed once for the signed-in shell. Every
    /// factory is config-safe, so a build without GoogleService-Info.plist
    /// still renders each route's unavailable state rather than crashing.
    @State private var eventsCoordinator: EventsCoordinator?
    @State private var leaderboardCoordinator: LeaderboardCoordinator?
    @State private var notificationsCoordinator: NotificationsInboxCoordinator?
    @State private var notificationSettingsCoordinator: NotificationSettingsCoordinator?
    @State private var privacySettingsCoordinator: PrivacySettingsCoordinator?
    @State private var friendsCoordinator: FriendsCoordinator?
    @State private var conversationsCoordinator: ConversationsCoordinator?
    @State private var chatHubCoordinator: ChatHubCoordinator?
    @State private var crownHuntComposition: CrownHuntComposition?
    @State private var partnersCoordinator: PartnersCoordinator?
    @State private var liveLocationCoordinator: LiveLocationCoordinator?
    @State private var driveRecordingCoordinator: DriveRecordingCoordinator?
    @State private var locationPermissionCoordinator: LocationPermissionCoordinator?
    @State private var startDrivingGarage: GarageCoordinator?
    @State private var convoyManagementCoordinator: ConvoyManagementCoordinator?
    @State private var convoyAwareness = ConvoyAwarenessCoordinator()
    @State private var nearbyLive = NearbyLiveCoordinator()
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
    /// every cover-derived decision reads. `navigating` / `navSearchOpen` are
    /// hard false until turn-by-turn and the address search are ported.
    private var mapCover: MapCover {
        ShellNavigation.mapCover(
            tab: selectedTab,
            route: routes.current,
            navigating: false,
            navSearchOpen: false
        )
    }

    var body: some View {
        shellPresentation
            .task(id: signedInUid) { await wireFeatures() }
            .task(id: crownFeatureWiringKey) { await wireCrownHunt() }
            .task {
                whatsNewCoordinator.start()
                await appUpdateCoordinator.checkOnce()
            }
            .onChange(of: scenePhase) { _, phase in
                guard phase == .active else { return }
                appUpdateCoordinator.scheduleRequiredUpdateRecheck()
            }
            .onChange(of: featureFlags.isEnabled(.liveLocation)) { _, enabled in
                liveLocationCoordinator?.canShare = enabled && !access.isRestricted
            }
            .onChange(of: chatFeatureWiringKey) { _, _ in
                applyChatFeatureGate()
            }
            .onChange(of: access) { _, access in
                partnersCoordinator?.updateAccess(access)
                if !partnerStatsEntryAvailable, routes.current == .partnerStats {
                    routes = routes.poppingOne()
                }
            }
            .onChange(of: featureFlags.isEnabled(.partners)) { _, enabled in
                if !enabled, routes.current == .partners {
                    partnersCoordinator?.clearSensitiveOfferState()
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
            }
    }

    private var shellPresentation: some View {
        ZStack {
            // Exactly one native Mapbox view for the signed-in shell. Tabs and
            // routes cover it instead of recreating its Metal render surface.
            MapHomeView(surface: mapSurface, locationProvider: locationProvider)

            TabView(selection: tabSelection) {
                ForEach(ShellTab.allCases, id: \.self) { tab in
                    content(for: tab)
                        .tabItem {
                            Label(
                                tabTitle(tab),
                                systemImage: tabSystemImage(tab)
                            )
                        }
                        .tag(tab)
                }
            }
        }
        // Route host: sub-routes render full-screen OVER the tab shell (the
        // Android shell's route-host pattern), each carrying its own back
        // affordance that pops one level via the pure stack.
        .overlay {
            if let route = routes.current, route != .chatHub {
                routeHost(for: route)
            }
        }
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
            if tab != .map, routes.current == .chatHub {
                routes = routes.poppingOne()
            }
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
                // The map-home profile entry (Android: the map-home top-right
                // profile menu button). Only when a session actually exists —
                // the unavailable shell has no one to show or sign out.
                .overlay(alignment: .topTrailing) {
                    if case .signedIn = session.state {
                        profileButton
                    }
                }
                .overlay(alignment: .bottomTrailing) {
                    if case .signedIn = session.state {
                        mapCommunicationControls
                    }
                }
                .overlay(alignment: .bottomLeading) {
                    // Viewing preferences are device-local and do not require
                    // an account. Keep the control in the config-less shell so
                    // clone-and-run builds exercise the same stub seam as CI.
                    MapLayersButton(isPresented: $showMapLayers)
                        .padding(KccSpacing.s4)
                }
                .overlay(alignment: .bottom) {
                    if let coordinator = crownHuntComposition?.perkMapCoordinator,
                       coordinator.isAvailable {
                        CrownPerkMapControl(coordinator: coordinator)
                            .padding(.bottom, KccSpacing.s4)
                    }
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
                    if convoyReactionTargetId != nil, let convoyReactionCoordinator {
                        ConvoyReactionControls(
                            coordinator: convoyReactionCoordinator,
                            followMeCoordinator: convoyFollowMeCoordinator
                        )
                    }
                }
                .overlay(alignment: .top) {
                    if let coordinator = convoyManagementCoordinator,
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
                    if incidentMapCoordinator?.pendingMapReportType != nil {
                        IncidentLocationPickerControls(
                            canConfirm: currentMapCenter != nil,
                            confirm: submitMapCenterIncident,
                            cancel: { incidentMapCoordinator?.cancelMapSelection() }
                        )
                    }
                }
                .overlay(alignment: .top) {
                    if incidentMapCoordinator?.proximityAlert != nil {
                        PoliceProximityBanner {
                            incidentMapCoordinator?.dismissProximityAlert()
                        }
                        .padding(.horizontal, KccSpacing.s4)
                        .padding(.top, KccSpacing.s12 + KccSpacing.s12)
                    }
                }
                .task(id: incidentMapLifecycleKey) {
                    guard let incidentMapCoordinator else { return }
                    incidentMapCoordinator.start(
                        surface: mapSurface,
                        provider: locationProvider
                    )
                    do { try await Task.sleep(for: .seconds(86_400)) } catch {}
                    incidentMapCoordinator.stop()
                }
                .task(id: crownMapLifecycleKey) {
                    guard let coordinator = crownHuntComposition?.mapCoordinator else { return }
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
                .task(id: mapSurface.cameraSnapshot) {
                    guard let incidentMapCoordinator else { return }
                    // Camera snapshots update while panning. Cancellation turns
                    // this into a trailing-edge debounce, avoiding callable spam.
                    do { try await Task.sleep(for: .milliseconds(700)) } catch { return }
                    await incidentMapCoordinator.refresh(surface: mapSurface)
                }
                .task(id: crownMapRefreshKey) {
                    guard let coordinator = crownHuntComposition?.mapCoordinator else { return }
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
                        repository: repository,
                        currentUid: uid,
                        excludedUids: nearbyLiveExcludedUids
                    )
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
                    nearbyLive.activate(
                        repository: liveLocationRepository,
                        currentUid: signedInUid,
                        excludedUids: excludedUids
                    )
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
                .overlay {
                    if routes.current == .chatHub {
                        chatHubOverlay
                    }
                }
        case .history:
            // The read-only drives history (Android's DrivesListScreen). The
            // panel wires itself (repository + uid) at the feature level, so
            // the shell stays argument-free here.
            panelTab {
                DrivesPanel(sharingEnabled: FeatureGate.isAvailable(
                    flags: featureFlags,
                    flag: .socialSharing,
                    memberGated: false,
                    access: access
                ))
            }
        case .social:
            panelTab {
                SocialHubPanel(
                    crownHuntEnabled: featureFlags.isEnabled(.crownHunt),
                    partnersEnabled: featureFlags.isEnabled(.partners)
                        && partnersCoordinator != nil,
                    onOpenEvents: { routes = routes.opening(.events) },
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

    private var profileButton: some View {
        Menu {
            Button {
                routes = routes.opening(.profile)
            } label: {
                Label("shell.moreProfile", systemImage: "person")
            }
            if friendsCoordinator != nil {
                Button {
                    routes = routes.opening(.friends)
                } label: {
                    Label("shell.friendsTitle", systemImage: "person.2")
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
            Label("shell.moreProfile", systemImage: "person.circle")
                .labelStyle(.iconOnly)
                .font(.system(size: 28))
        }
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
                        .background(.regularMaterial, in: Circle())
                }
                .accessibilityLabel(Text("incidents.reportButton"))
            }

            if ShellNavigation.chatHubEntryAvailable(flags: featureFlags, access: access) {
                Button {
                    guard ChatHubCoordinator.canPresentHub(cover: mapCover, navigating: false) else { return }
                    routes = routes.opening(.chatHub)
                } label: {
                    Image(systemName: "bubble.left.and.bubble.right.fill")
                        .frame(width: 48, height: 48)
                        .background(.regularMaterial, in: Circle())
                }
                .accessibilityLabel(Text("chatHub.title"))
            }

            if let liveLocationCoordinator, liveLocationCoordinator.canPresentScreen {
                Button {
                    routes = routes.opening(.liveLocation)
                } label: {
                    Image(systemName: "location.fill")
                        .frame(width: 48, height: 48)
                        .background(.regularMaterial, in: Circle())
                }
                .accessibilityLabel(Text("liveLocation.screenTitle"))
            }
        }
        .font(.system(size: 20, weight: .semibold))
        .padding(KccSpacing.s4)
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
                onSignOut: { session.signOut() },
                onBack: { routes = routes.poppingOne() },
                onOpenPoints: { routes = routes.opening(.points) },
                onOpenWhatsNew: { routes = routes.opening(.whatsNew) }
            )
        case .points:
            routeNavigation {
                PointsScreen(uid: signedInUid)
            }
        case .events:
            // The read-only events list, opened from the Social hub. The
            // NavigationStack hosts the screen's `navigationTitle`; Back pops
            // one level via the pure stack, like every route.
            NavigationStack {
                EventsScreen(coordinator: eventsCoordinator, locationProvider: locationProvider)
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
            EmptyView()
        case .notifications:
            routeNavigation {
                NotificationsInboxScreen(
                    coordinator: notificationsCoordinator,
                    onOpenSettings: { routes = routes.opening(.notificationSettings) }
                )
            }
        case .notificationSettings:
            routeNavigation {
                NotificationSettingsScreen(coordinator: notificationSettingsCoordinator)
            }
        case .whatsNew:
            routeNavigation {
                WhatsNewScreen(entries: whatsNewCoordinator.entries)
            }
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
        default:
            // No other route is reachable yet — each renders here as its
            // feature is ported. Falling back to nothing (rather than
            // trapping) keeps an unexpected value harmless.
            EmptyView()
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

    private var chatHubOverlay: some View {
        NavigationStack {
            ChatHubScreen(
                coordinator: chatHubCoordinator,
                conversationsCoordinator: conversationsCoordinator,
                makeNewDialogueCoordinator: makeNewDialogueCoordinator,
                onOpenConversation: openDm,
                notificationsCoordinator: notificationsCoordinator,
                onOpenNotificationSettings: {
                    routes = routes.opening(.notificationSettings)
                }
            )
            .background(.regularMaterial, ignoresSafeAreaEdges: .all)
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
    }

    private var unavailableRoute: some View {
        VStack(spacing: KccSpacing.s3) {
            Text("shell.unavailable")
                .foregroundStyle(.secondary)
            routeBackButton
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.background, ignoresSafeAreaEdges: .all)
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

    private func routeNavigation<Content: View>(
        @ViewBuilder content: () -> Content
    ) -> some View {
        NavigationStack {
            content()
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        routeBackButton
                    }
                }
        }
        .background(.background, ignoresSafeAreaEdges: .all)
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

    private func openMemberProfile(uid: String, displayName: String?) {
        guard !uid.isEmpty, uid != signedInUid else { return }
        memberProfileTarget = MemberProfileRouteTarget(uid: uid, displayName: displayName)
        routes = routes.opening(.memberProfile)
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

    private func tabTitle(_ tab: ShellTab) -> LocalizedStringKey {
        if tab == .create,
           (liveLocationCoordinator?.isSharing == true || pendingSessionCommand == .starting) {
            return "liveLocation.stop"
        }
        return tab.title
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
        convoyReactionCoordinator?.sync(convoyId: nil)
        convoyReactionCoordinator = nil
        convoyFollowMeCoordinator?.stop()
        convoyFollowMeCoordinator = nil
        liveLocationRepository = nil
        convoyCreateCoordinator = nil
        incidentMapCoordinator?.stop()
        incidentMapCoordinator = nil
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
        notificationsCoordinator = NotificationsInboxCoordinator(repository: notifications, uid: uid)
        notificationSettingsCoordinator = NotificationSettingsCoordinator(
            repository: FirebaseNotificationSettingsRepository.createIfAvailable(),
            uid: uid
        )
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
        driveRecordingCoordinator = DriveRecordingCoordinator(
            repository: FirebaseDriveRecordingRepository.createIfAvailable(),
            provider: driveLocationProvider,
            journal: uid.flatMap { FileDriveRecordingJournal(ownerId: $0) }
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
        // Event detail/chat lives under its own NavigationStack. Rebuild the
        // list composition as well; the view identity key above pops any
        // already-open detail/chat destination before the old repository can
        // be used again.
        eventsCoordinator = FirebaseEventsRepository.createIfAvailable().map {
            EventsCoordinator(
                repository: $0,
                eventChatRepository: eventChatEnabled
                    ? FirebaseEventChatRepository.createIfAvailable() : nil,
                locationProvider: locationProvider,
                subscriptionRepository: FirebaseSubscriptionStateRepository.createIfAvailable()
            )
        }
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

    private func submitMapCenterIncident() {
        guard let incidentMapCoordinator,
              let type = incidentMapCoordinator.pendingMapReportType,
              let point = currentMapCenter else { return }
        incidentMapCoordinator.cancelMapSelection()
        Task { await incidentMapCoordinator.report(type, at: point) }
    }

    private var incidentMapLifecycleKey: String {
        "\(signedInUid ?? "unavailable")|\(incidentMapCoordinator == nil ? "off" : "on")"
    }

    private var crownMapLifecycleKey: String {
        [
            signedInUid ?? "unavailable",
            crownHuntComposition?.mapCoordinator?.isAvailable == true ? "on" : "off",
            selectedTab == .map ? "visible" : "covered"
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
        "\(featureFlags.isEnabled(.chat))|\(featureFlags.isEnabled(.chatReplies))"
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
        "\(nearbyLiveShouldRun)|\(signedInUid ?? "")"
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
    var title: LocalizedStringKey {
        switch self {
        case .map: "shell.tabMap"
        case .history: "shell.tabHistory"
        case .create: "shell.tabCreate"
        case .social: "shell.tabSocial"
        case .garage: "shell.tabGarage"
        }
    }

    var systemImage: String {
        switch self {
        case .map: "map"
        case .history: "clock"
        case .create: "plus.circle"
        case .social: "person.2"
        case .garage: "car"
        }
    }
}

#Preview {
    // Config-less session: the bare shell, no profile entry.
    ShellView(
        session: AuthSession(repository: nil),
        authenticatedUid: nil,
        access: .unrestrictedCommunity,
        featureFlags: .contractDefaults
    )
}
