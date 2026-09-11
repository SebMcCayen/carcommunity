import SwiftUI

/// The five-tab, map-first shell. One persistent map sits behind the tab host;
/// History / Social / Garage render as translucent panels over the map per
/// `translucentPanelTabs`; the tab set, default tab, and the map-cover rules
/// all come from the pure ``ShellNavigation`` logic so behaviour stays unit-tested
/// outside SwiftUI.
struct ShellView: View {
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
    @State private var friendsRepository: FriendsRepository?
    @State private var conversationsRepository: ConversationsRepository?
    @State private var dmTarget: DmRouteTarget?
    @State private var dmCoordinator: ChatCoordinator?
    @State private var locationProvider = CoreLocationProvider()

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
            MapHomeView(surface: mapSurface)

            TabView(selection: $selectedTab) {
                ForEach(ShellTab.allCases, id: \.self) { tab in
                    content(for: tab)
                        .tabItem { Label(tab.title, systemImage: tab.systemImage) }
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
                    onOpenCrownHunt: { routes = routes.opening(.crownHunt) },
                    onOpenLeaderboard: { routes = routes.opening(.leaderboard) }
                )
            }
        case .garage:
            panelTab { GaragePanel() }
        case .create:
            // Not a panel tab: an opaque page (the map-cover rule stands the
            // surface down here). The Create chooser fills in with its slice.
            placeholder(for: tab)
                .background(.background, ignoresSafeAreaEdges: .all)
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

    @ViewBuilder
    private func placeholder(for tab: ShellTab) -> some View {
        VStack(spacing: 12) {
            Image(systemName: tab.systemImage)
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text(tab.title)
                .font(.title2)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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

    @MainActor
    private func wireFeatures() async {
        let uid = signedInUid

        // Remove a conversation built for the previous identity before doing
        // any asynchronous flag work. If Chat was opened from a parent hub,
        // return to that hub; otherwise close the route entirely.
        if routes.current == .chat {
            routes = routes.poppingOne()
        }
        dmTarget = nil
        dmCoordinator = nil
        liveLocationCoordinator = nil
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

        let crownHunt = await CrownHuntComposition.live(
            uid: uid,
            passesMemberGate: uid != nil
        )

        // `.task(id: signedInUid)` cancels and restarts this work when the
        // identity changes. Do not let a slower composition for the old user
        // overwrite the new session's coordinators after its await returns.
        guard !Task.isCancelled, uid == signedInUid else { return }

        let liveLocation = LiveLocationCoordinator.live(
            provider: locationProvider,
            canShare: crownHunt.flags.liveLocationEnabled
        )
        // Observe the own session from the shell so a flag-disabled feature
        // can still reveal its control when an existing session needs Stop or
        // Hide access. Starting this listener does not request GPS permission.
        liveLocation.start()
        liveLocationCoordinator = liveLocation
        crownHuntComposition = crownHunt
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
