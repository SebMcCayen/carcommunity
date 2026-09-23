import SwiftUI
import UIKit

/// The five-tab, map-first shell. One persistent map sits behind the tab host;
/// History / Social / Garage render as translucent panels over the map per
/// `translucentPanelTabs`; the tab set, default tab, and the map-cover rules
/// all come from the pure ``ShellNavigation`` logic so behaviour stays unit-tested
/// outside SwiftUI.
struct ShellView: View {
    @Environment(\.openURL) private var openURL

    /// The signed-in session, threaded from ``RootView``. The config-less /
    /// unavailable state renders the bare shell with no profile entry —
    /// Android's "unavailable entries are omitted" hub rule.
    @Bindable var session: AuthSession

    @State private var selectedTab: ShellTab = .defaultTab
    /// The full-screen sub-route back-stack, held as the ONE pure value from
    /// ``ShellRouteStack``; every open/Back goes through its ``ShellRouteStack/opening(_:)``
    /// / ``ShellRouteStack/poppingOne()`` reducers rather than ad-hoc state.
    @State private var routes = ShellRouteStack.empty

    /// The shell's SINGLE map surface, composed once for the whole signed-in
    /// shell and never disposed — Android composes the surface once in
    /// `AuthenticatedApp` and covers/uncovers it; `@State` mirrors that by
    /// keeping this one instance for the shell's lifetime. Covered pages only
    /// stand it down via ``MapSurface/setActive(_:)`` (see the map-cover
    /// effect below), never recreate it.
    @State private var mapSurface = StubMapSurface()

    /// Feature coordinators are composed once for the signed-in shell. Every
    /// factory is config-safe, so a build without GoogleService-Info.plist
    /// still renders each route's unavailable state rather than crashing.
    @State private var eventsCoordinator: EventsCoordinator?
    @State private var leaderboardCoordinator: LeaderboardCoordinator?
    @State private var notificationsCoordinator: NotificationsInboxCoordinator?
    @State private var notificationSettingsCoordinator: NotificationSettingsCoordinator?
    @State private var friendsCoordinator: FriendsCoordinator?
    @State private var conversationsCoordinator: ConversationsCoordinator?
    @State private var chatHubCoordinator: ChatHubCoordinator?
    @State private var crownHuntComposition: CrownHuntComposition?
    @State private var liveLocationCoordinator: LiveLocationCoordinator?
    @State private var locationPermissionCoordinator: LocationPermissionCoordinator?
    @State private var startDrivingGarage: GarageCoordinator?
    @State private var convoyManagementCoordinator: ConvoyManagementCoordinator?
    @State private var convoyAwareness = ConvoyAwarenessCoordinator()
    @State private var liveLocationRepository: LiveLocationRepository?
    @State private var convoyCreateCoordinator: ConvoyCreateCoordinator?
    @State private var convoyCreateVehicleId: String?
    @State private var convoyCreateReturnsToList = false
    @State private var selectedConvoyId: String?
    @State private var friendsRepository: FriendsRepository?
    @State private var conversationsRepository: ConversationsRepository?
    @State private var dmTarget: DmRouteTarget?
    @State private var dmCoordinator: ChatCoordinator?
    @State private var locationProvider = CoreLocationProvider()
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
        .alert(convoyLeaveResultMessage, isPresented: convoyLeaveResultIsPresented) {
            Button("convoy.close", role: .cancel) {
                convoyManagementCoordinator?.clearLeaveResult()
            }
        }
        .task(id: signedInUid) { await wireFeatures() }
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
                .overlay {
                    if liveLocationFeatureEnabled {
                        ConvoyMapAwarenessOverlay(
                            members: convoyAwareness.visibleMembers,
                            imageURLs: convoyAwareness.imageURLs,
                            projection: mapSurface
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
                            liveLocationEnabled: liveLocationFeatureEnabled
                        )
                        .padding(.horizontal, KccSpacing.s4)
                        .padding(.top, KccSpacing.s12 + KccSpacing.s3)
                    }
                }
                .task(id: convoyAwarenessSubscriptionKey) {
                    convoyAwareness.sync(
                        convoy: convoyAwarenessTargetConvoy,
                        repository: liveLocationRepository,
                        currentUid: signedInUid
                    )
                    applyConvoyFocus()
                }
                .onChange(of: convoyAwareness.focusMode) { _, _ in applyConvoyFocus() }
                .onChange(of: convoyAwareness.positions) { _, _ in applyConvoyFocus() }
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
            panelTab { DrivesPanel() }
        case .social:
            panelTab {
                SocialHubPanel(
                    crownHuntEnabled: crownHuntComposition?.flags.crownHuntEnabled == true,
                    onOpenEvents: { routes = routes.opening(.events) },
                    onOpenConvoys: openConvoyManagement,
                    onOpenCrownHunt: { routes = routes.opening(.crownHunt) },
                    onOpenLeaderboard: { routes = routes.opening(.leaderboard) }
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
        } label: {
            Label("shell.moreProfile", systemImage: "person.circle")
                .labelStyle(.iconOnly)
                .font(.system(size: 28))
        }
        .padding(KccSpacing.s4)
    }

    private var mapCommunicationControls: some View {
        VStack(spacing: KccSpacing.s3) {
            Button {
                guard ChatHubCoordinator.canPresentHub(cover: mapCover, navigating: false) else { return }
                routes = routes.opening(.chatHub)
            } label: {
                Image(systemName: "bubble.left.and.bubble.right.fill")
                    .frame(width: 48, height: 48)
                    .background(.regularMaterial, in: Circle())
            }
            .accessibilityLabel(Text("chatHub.title"))

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

    @ViewBuilder
    private func routeHost(for route: ShellRoute) -> some View {
        switch route {
        case .profile:
            ProfileScreen(
                displayName: signedInDisplayName,
                onSignOut: { session.signOut() },
                onBack: { routes = routes.poppingOne() }
            )
        case .events:
            // The read-only events list, opened from the Social hub. The
            // NavigationStack hosts the screen's `navigationTitle`; Back pops
            // one level via the pure stack, like every route.
            NavigationStack {
                EventsScreen(coordinator: eventsCoordinator)
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
        case .leaderboard:
            routeNavigation {
                LeaderboardScreen(coordinator: leaderboardCoordinator)
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
        case .friends:
            routeNavigation {
                FriendsScreen(
                    coordinator: friendsCoordinator,
                    onMessageFriend: { friend in
                        openDm(uid: friend.uid, displayName: friend.displayName)
                    },
                    onViewProfile: nil
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
        case .chat:
            if let target = dmTarget {
                routeNavigation {
                    ChatScreen(
                        coordinator: dmCoordinator,
                        otherName: target.displayName,
                        currentUid: signedInUid ?? ""
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
        if routes.current == .chat {
            routes = routes.poppingOne()
        }
        dmTarget = nil
        dmCoordinator = nil
        if routes.current == .convoys { routes = routes.poppingOne() }
        convoyManagementCoordinator = nil
        convoyAwareness.sync(convoy: nil, repository: nil, currentUid: nil)
        liveLocationRepository = nil
        convoyCreateCoordinator = nil
        convoyCreateVehicleId = nil
        convoyCreateReturnsToList = false
        selectedConvoyId = nil
        liveLocationCoordinator = nil
        startDrivingGarage = nil
        crownHuntComposition = nil

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
        eventsCoordinator = FirebaseEventsRepository.createIfAvailable().map(EventsCoordinator.init(repository:))
        leaderboardCoordinator = LeaderboardCoordinator(
            repository: FirebaseLeaderboardRepository.createIfAvailable()
        )
        notificationsCoordinator = NotificationsInboxCoordinator(repository: notifications, uid: uid)
        notificationSettingsCoordinator = NotificationSettingsCoordinator(
            repository: FirebaseNotificationSettingsRepository.createIfAvailable(),
            uid: uid
        )
        friendsCoordinator = friends.map {
            FriendsCoordinator(
                repository: $0,
                pointsRepository: FirebaseFriendPointsRepository.createIfAvailable()
            )
        }
        conversationsCoordinator = nil
        if let conversations, let uid {
            conversationsCoordinator = ConversationsCoordinator(
                repository: conversations,
                blockVisibility: FirebaseBlockVisibilityRepository.createOrEmpty(),
                uid: uid
            )
        }
        chatHubCoordinator = ChatHubCoordinator(
            communityRepository: FirebaseCommunityChatRepository.createIfAvailable(),
            convoyRepository: FirebaseConvoyChatRepository.createIfAvailable()
        )
        let convoyManagement = ConvoyManagementCoordinator(
            repository: FirebaseConvoyManagementRepository.createIfAvailable()
        )
        convoyManagementCoordinator = convoyManagement

        let crownHunt = await CrownHuntComposition.live(
            uid: uid,
            passesMemberGate: uid != nil
        )

        // `.task(id: signedInUid)` cancels and restarts this work when the
        // identity changes. Do not let a slower composition for the old user
        // overwrite the new session's coordinators after its await returns.
        guard !Task.isCancelled, uid == signedInUid else { return }

        let liveRepository = FirebaseLiveLocationRepository.createIfAvailable()
        liveLocationRepository = liveRepository
        let liveLocation = LiveLocationCoordinator(
            repository: liveRepository,
            provider: locationProvider,
            canShare: crownHunt.flags.liveLocationEnabled
        )
        // Observe the own session from the shell so a flag-disabled feature
        // can still reveal its control when an existing session needs Stop or
        // Hide access. Starting this listener does not request GPS permission.
        liveLocation.start()
        liveLocationCoordinator = liveLocation
        crownHuntComposition = crownHunt

        if pendingCreateIntent?.belongs(to: uid) == true {
            pendingCreateIntent = nil
            presentSingleSessionAction(using: liveLocation)
        }

        // Convoy discovery is independent of the map's live-session wiring.
        // Load it last so a slow callable cannot delay location controls.
        await convoyManagement.load()
    }

    private var signedInDisplayName: String? {
        if case .signedIn(_, let displayName) = session.state {
            return displayName
        }
        return nil
    }

    private var signedInUid: String? {
        if case .signedIn(let uid, _) = session.state { return uid }
        return nil
    }

    private var liveLocationFeatureEnabled: Bool {
        crownHuntComposition?.flags.liveLocationEnabled == true
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
    ShellView(session: AuthSession(repository: nil))
}
