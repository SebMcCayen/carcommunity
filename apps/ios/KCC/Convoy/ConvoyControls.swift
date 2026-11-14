import SwiftUI

private struct ConvoyExitTarget {
    let convoyId: String
    let choice: ConvoyExitChoice
}

private struct ConvoyActionTarget {
    let convoyId: String
    let action: ConvoyLifecycleAction
    let exitChoice: ConvoyExitChoice
}

private struct ConvoySheetTarget: Identifiable {
    let id: String
    let initial: ConvoyItem

    init(_ convoy: ConvoyItem) {
        id = convoy.convoyId
        initial = convoy
    }
}

struct ConvoyStatusBar: View {
    @Environment(\.scenePhase) private var scenePhase
    @Bindable var coordinator: ConvoyManagementCoordinator
    let convoy: ConvoyItem
    let friendsCoordinator: FriendsCoordinator?
    @Bindable var awareness: ConvoyAwarenessCoordinator
    let mapSurface: StubMapSurface
    let liveLocationEnabled: Bool
    let viewerUid: String?
    var chat: ConvoyListCoordinator? = nil
    var chatRepliesEnabled = ChatFeatureFlags.chatRepliesDefault
    var onOpenMemberProfile: ((String, String?) -> Void)? = nil

    @State private var memberTarget: ConvoySheetTarget?
    @State private var inviteTarget: ConvoySheetTarget?
    @State private var exitTarget: ConvoyExitTarget?
    @State private var chatTarget: ConvoySheetTarget?
    @State private var unreadChatCount = 0

    private var working: Bool {
        coordinator.busyConvoyIds.contains(convoy.convoyId)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            ConvoyControlLayout {
                Button { memberTarget = ConvoySheetTarget(convoy) } label: {
                    HStack(spacing: KccSpacing.s1) {
                        Image(systemName: "person.2.fill").font(.system(size: 20)).foregroundStyle(KccTheme.driver)
                        Text(convoy.acceptedMemberCount.formatted()).font(.subheadline.weight(.semibold))
                            .foregroundStyle(KccTheme.text)
                    }
                    .fixedSize()
                    .frame(minWidth: 48, minHeight: 48).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(memberCount)
                .accessibilityIdentifier("convoy.bar.members")

                if liveLocationEnabled {
                    Button {
                        awareness.focusMode = awareness.focusMode == .me ? .convoy : .me
                    } label: {
                        Image(systemName: awareness.focusMode == .convoy ? "person.3.fill" : "viewfinder")
                            .foregroundStyle(awareness.focusMode == .convoy ? KccTheme.driver : KccTheme.secondary)
                            .frame(width: 48, height: 48).contentShape(Rectangle())
                    }
                    .accessibilityLabel(Text(
                        awareness.focusMode == .convoy ? "convoy.barFocusConvoy" : "convoy.barFocusMe"
                    ))
                    .accessibilityIdentifier("convoy.bar.focus")
                }

                if chat != nil {
                    Button { chatTarget = ConvoySheetTarget(convoy) } label: {
                        Image(systemName: "bubble.left.fill")
                            .frame(width: 48, height: 48).contentShape(Rectangle())
                            .overlay(alignment: .topTrailing) {
                                if let badge = ConvoyBarLogic.unreadBadgeLabel(unreadChatCount) {
                                    Text(badge).font(.system(size: 11, weight: .semibold))
                                        .padding(.horizontal, 4).padding(.vertical, 1)
                                        .foregroundStyle(.white)
                                        .background(KccPalette.errorRed, in: Capsule())
                                        .padding(KccSpacing.s1)
                                        .accessibilityHidden(true)
                                }
                            }
                    }
                    .accessibilityLabel(ConvoyBarLogic.chatAccessibilityLabel(unreadChatCount))
                    .accessibilityIdentifier("convoy.bar.chat")
                }

                Button { inviteTarget = ConvoySheetTarget(convoy) } label: {
                    Image(systemName: "person.badge.plus")
                        .frame(width: 48, height: 48).contentShape(Rectangle())
                }
                .disabled(working || friendsCoordinator == nil)
                .foregroundStyle(KccTheme.secondary.opacity(working || friendsCoordinator == nil ? 0.38 : 1))
                .accessibilityLabel(Text(friendsCoordinator == nil ? "convoy.barInviteUnavailable" : "convoy.barInvite"))
                .accessibilityIdentifier("convoy.bar.invite")

                Button {
                    exitTarget = ConvoyExitTarget(convoyId: convoy.convoyId, choice: exitChoice)
                } label: {
                    Group {
                        if working { ProgressView().controlSize(.small) }
                        else { Image(systemName: exitChoice == .leaveOnly ? "rectangle.portrait.and.arrow.right" : "stop.circle.fill") }
                    }
                    .frame(width: 48, height: 48).contentShape(Rectangle())
                }
                .disabled(working)
                .foregroundStyle(working ? KccTheme.secondary.opacity(0.38) : KccPalette.errorRed)
                .accessibilityLabel(Text(exitLabel))
                .accessibilityIdentifier("convoy.bar.exit")
            }

            if let error = coordinator.actionError(for: convoy.convoyId) {
                HStack {
                    Text(ConvoyManagementStrings.errorKey(error))
                        .font(.subheadline)
                        .foregroundStyle(KccPalette.errorRed)
                    Spacer(minLength: KccSpacing.s2)
                    Button {
                        coordinator.clearActionError()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("convoy.close"))
                }
            }
        }
        .font(.system(size: 24))
        .buttonStyle(.plain)
        .padding(.leading, KccSpacing.s4)
        .padding(.trailing, KccSpacing.s2)
        .foregroundStyle(KccTheme.secondary)
        .background(KccTheme.overlay, in: RoundedRectangle(cornerRadius: KccRadius.full))
        .overlay { RoundedRectangle(cornerRadius: KccRadius.full).strokeBorder(KccTheme.border).allowsHitTesting(false) }
        .shadow(color: .black.opacity(0.14), radius: 3, y: 3)
        .sheet(item: $chatTarget) { target in
            if let chat {
                ConvoyDockChatSheet(list: chat, target: target, chatRepliesEnabled: chatRepliesEnabled,
                    onOpenMemberProfile: onOpenMemberProfile)
            }
        }
        .sheet(item: $memberTarget) { target in
            LiveConvoyMembersSheet(
                coordinator: coordinator,
                awareness: awareness,
                target: target,
                viewerUid: viewerUid,
                onCenter: mapSurface.centerOn,
                onOpenMemberProfile: onOpenMemberProfile
            )
        }
        .sheet(item: $inviteTarget) { target in
            if let friendsCoordinator {
                LiveConvoyInviteSheet(
                    target: target,
                    friendsCoordinator: friendsCoordinator,
                    coordinator: coordinator
                )
            }
        }
        .confirmationDialog(
            Text(exitTitle),
            isPresented: Binding(
                get: { exitTarget != nil },
                set: { if !$0 { exitTarget = nil } }
            ),
            titleVisibility: .visible
        ) {
            switch exitTarget?.choice ?? exitChoice {
            case .leaveOrEnd:
                Button("convoy.barExitChoiceLeave") { run(.leave) }
                Button("convoy.barExitChoiceEnd", role: .destructive) { run(.end) }
            case .endOnly:
                Button("convoy.barEndConfirmAction", role: .destructive) { run(.end) }
            case .leaveOnly:
                Button("convoy.barLeaveConfirmAction", role: .destructive) { run(.leave) }
            case .leaveEndsConvoy:
                Button("convoy.barLeaveEndsConfirmAction", role: .destructive) { run(.leave) }
            }
            Button("convoy.barConfirmCancel", role: .cancel) { exitTarget = nil }
        } message: {
            Text(exitBody)
        }
        // Observe only this convoy document while the app is foregrounded.
        // Changing convoy or scene phase cancels the stream and removes its
        // Firestore listener through AsyncStream termination.
        .task(id: "\(convoy.convoyId)-\(String(describing: scenePhase))") {
            guard scenePhase == .active else { return }
            await coordinator.observeConvoy(id: convoy.convoyId)
        }
        .task(id: "\(convoy.convoyId)-\(viewerUid ?? "")-\(chat != nil)-\(String(describing: scenePhase))") {
            unreadChatCount = 0
            guard scenePhase == .active, let chat, let viewerUid else { return }
            for await count in chat.observeUnread(convoyId: convoy.convoyId, uid: viewerUid) {
                guard !Task.isCancelled else { return }
                unreadChatCount = count
            }
        }
    }

    private var exitChoice: ConvoyExitChoice {
        ConvoyBarLogic.exitChoice(
            viewerIsOwner: convoy.viewerIsOwner,
            acceptedMemberCount: convoy.acceptedMemberCount
        )
    }

    private var memberCount: String {
        String.localizedStringWithFormat(
            NSLocalizedString("convoy.barMembers", comment: "Convoy member count"),
            convoy.acceptedMemberCount
        )
    }

    private var exitLabel: LocalizedStringKey {
        switch exitChoice {
        case .leaveOrEnd: "convoy.barExit"
        case .endOnly: "convoy.barEnd"
        case .leaveOnly: "convoy.barLeave"
        case .leaveEndsConvoy: "convoy.barLeaveEnds"
        }
    }

    private var exitTitle: LocalizedStringKey {
        switch exitTarget?.choice ?? exitChoice {
        case .leaveOrEnd: "convoy.barExitChoiceTitle"
        case .endOnly: "convoy.barEndConfirmTitle"
        case .leaveOnly: "convoy.barLeaveConfirmTitle"
        case .leaveEndsConvoy: "convoy.barLeaveEndsConfirmTitle"
        }
    }

    private var exitBody: LocalizedStringKey {
        switch exitTarget?.choice ?? exitChoice {
        case .leaveOrEnd: "convoy.barExitChoiceBody"
        case .endOnly: "convoy.barEndConfirmBody"
        case .leaveOnly: "convoy.barLeaveConfirmBody"
        case .leaveEndsConvoy: "convoy.barLeaveEndsConfirmBody"
        }
    }

    private func run(_ action: ConvoyLifecycleAction) {
        guard let target = exitTarget else { return }
        exitTarget = nil
        Task { await coordinator.runLifecycle(convoyId: target.convoyId, action: action) }
    }
}

/// Android's SpaceBetween flow row: preserve button order and 48pt targets,
/// wrapping only when the numeric member count and controls cannot fit.
private struct ConvoyControlLayout: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let proposed = proposal.width.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
        let width = proposed ?? subviews.reduce(0) { $0 + $1.sizeThatFits(.unspecified).width }
        return CGSize(width: width, height: frames(width: width, subviews: subviews).map(\.maxY).max() ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for (index, frame) in frames(width: bounds.width, subviews: subviews).enumerated() {
            subviews[index].place(at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY),
                proposal: ProposedViewSize(frame.size))
        }
    }

    private func frames(width: CGFloat, subviews: Subviews) -> [CGRect] {
        let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        var rows: [[Int]] = [], used: CGFloat = 0
        for index in sizes.indices {
            if rows.isEmpty || used + sizes[index].width > width {
                rows.append([]); used = 0
            }
            rows[rows.count - 1].append(index)
            used += sizes[index].width
        }
        var result = Array(repeating: CGRect.zero, count: sizes.count)
        var y: CGFloat = 0
        for row in rows {
            let height = row.map { sizes[$0].height }.max() ?? 0
            let gap = row.count > 1
                ? max(0, width - row.reduce(0) { $0 + sizes[$1].width }) / CGFloat(row.count - 1) : 0
            var x: CGFloat = 0
            for index in row {
                result[index] = CGRect(origin: CGPoint(x: x, y: y + (height - sizes[index].height) / 2), size: sizes[index])
                x += sizes[index].width + gap
            }
            y += height + KccSpacing.s1
        }
        return result
    }
}

private struct ConvoyDockChatSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var coordinator: ChannelChatCoordinator
    let target: ConvoySheetTarget
    let onOpenMemberProfile: ((String, String?) -> Void)?

    init(list: ConvoyListCoordinator, target: ConvoySheetTarget, chatRepliesEnabled: Bool,
         onOpenMemberProfile: ((String, String?) -> Void)?) {
        self.target = target
        self.onOpenMemberProfile = onOpenMemberProfile
        _coordinator = State(initialValue: list.makeChatCoordinator(convoyId: target.id,
            chatRepliesEnabled: chatRepliesEnabled))
    }

    var body: some View {
        NavigationStack {
            ConvoyChatScreen(coordinator: coordinator, convoyTitle: target.initial.title,
                onViewProfile: onOpenMemberProfile.map { open in
                    { uid, name in dismiss(); open(uid, name) }
                })
        }
        .environment(\.chatReadingActive, scenePhase == .active)
        .tint(KccChatColors.readingAccent)
        .kccDismissibleSheet()
    }
}

struct ConvoyMembersSheet: View {
    @Environment(\.dismiss) private var dismiss
    let convoy: ConvoyItem
    let viewerUid: String?
    var positions: [String: ConvoyMemberPosition] = [:]
    var onCenter: ((MapPoint) -> Void)?
    var onOpenMemberProfile: ((String, String?) -> Void)? = nil

    var body: some View {
        NavigationStack {
            TimelineView(.periodic(
                from: .now,
                by: ConvoyArrowPlanner.staleAfter / 4
            )) { context in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: KccSpacing.s3) {
                        Text("convoy.membersTitle").font(.title3.bold()).accessibilityAddTraits(.isHeader)
                        ForEach(convoy.acceptedMembers) { member in
                            memberRow(member, waiting: false, now: context.date)
                        }
                        ForEach(convoy.pendingMembers) { member in
                            memberRow(member, waiting: true, now: context.date)
                        }
                    }.padding(KccSpacing.s4)
                }
            }
            .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
            .navigationTitle("convoy.barMemberListTitle")

        }
        .kccDismissibleSheet()
    }

    private func memberRow(_ member: ConvoyMember, waiting: Bool, now: Date) -> some View {
        let availablePosition = positions[member.uid].flatMap {
            $0.isFresh(at: now) ? $0 : nil
        }
        let canOpenProfile = ConvoyBarLogic.canOpenMemberProfile(
            memberUid: member.uid, viewerUid: viewerUid, waiting: waiting
        )
        let profileAction: (() -> Void)? = canOpenProfile ? onOpenMemberProfile.map { openProfile in
            { dismiss(); openProfile(member.uid, member.displayName) }
        } : nil
        let centerAction: (() -> Void)? = waiting ? nil : onCenter.map { center in
            {
                guard let availablePosition else { return }
                center(MapPoint(longitude: availablePosition.longitude, latitude: availablePosition.latitude))
                dismiss()
            }
        }
        return ConvoyMemberActionCard(member: member, waiting: waiting, locationAvailable: availablePosition != nil,
            onProfile: profileAction, onCenter: centerAction)
    }
}

private struct LiveConvoyMembersSheet: View {
    @Bindable var coordinator: ConvoyManagementCoordinator
    @Bindable var awareness: ConvoyAwarenessCoordinator
    let target: ConvoySheetTarget
    let viewerUid: String?
    let onCenter: (MapPoint) -> Void
    let onOpenMemberProfile: ((String, String?) -> Void)?

    var body: some View {
        ConvoyMembersSheet(
            convoy: coordinator.convoy(id: target.id) ?? target.initial,
            viewerUid: viewerUid,
            positions: awareness.positions,
            onCenter: onCenter,
            onOpenMemberProfile: onOpenMemberProfile
        )
    }
}

/// SwiftUI awareness layer over the native map. Projection remains owned by
/// Mapbox through ``MapProjection``; this view only decides marker vs edge
/// arrow and draws lightweight, accessible chips.
struct ConvoyMapAwarenessOverlay: View {
    let members: [ConvoyMemberPosition]
    let imageURLs: [String: URL]
    let projection: MapProjection
    var onSelect: ((String) -> Void)? = nil

    var body: some View {
        GeometryReader { geometry in
            TimelineView(.periodic(from: .now, by: 30)) { context in
                let placements = placements(size: geometry.size, now: context.date)
                ZStack {
                    ForEach(placements.onScreen) { placement in
                        Button { onSelect?(placement.member.uid) } label: {
                            memberChip(placement.member)
                                .frame(minWidth: 48, minHeight: 48).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .position(x: placement.point.x, y: placement.point.y)
                    }
                    ForEach(placements.offScreen) { placement in
                        edgeChip(placement)
                            .allowsHitTesting(false)
                            .position(x: placement.point.x, y: placement.point.y)
                    }
                }
            }
        }
        .allowsHitTesting(onSelect != nil)
        .accessibilityElement(children: .contain)
    }

    private func placements(size: CGSize, now: Date) -> ConvoyPlacements {
        guard let camera = projection.cameraSnapshot else {
            return ConvoyPlacements(onScreen: [], offScreen: [])
        }
        return ConvoyArrowPlanner.plan(
            members: members,
            camera: camera,
            viewportWidth: size.width,
            viewportHeight: size.height,
            edgeInset: 38,
            viewportMargin: 26,
            now: now,
            project: { member in
                projection.screenPositionFor(
                    latitude: member.latitude,
                    longitude: member.longitude
                )
            }
        )
    }

    private func memberChip(_ member: ConvoyMemberPosition) -> some View {
        VStack(spacing: 2) {
            memberPhoto(member)
            Text(member.displayName ?? String(localized: "convoy.barMemberUnnamed"))
                .font(.caption2.weight(.semibold))
                .lineLimit(1)
                .padding(.horizontal, 5)
                .padding(.vertical, 2)
                .background(KccTheme.overlay, in: Capsule())
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String.localizedStringWithFormat(
            NSLocalizedString("convoy.awarenessMemberOnMap", comment: "Convoy member on map"),
            spokenName(member)
        ))
    }

    private func edgeChip(_ placement: ConvoyOffScreenPlacement) -> some View {
        ZStack(alignment: .topTrailing) {
            Image(systemName: "location.north.fill")
                .font(.system(size: 27, weight: .bold))
                .foregroundStyle(KccTheme.driver)
                .rotationEffect(.degrees(placement.angleDegrees))
                .frame(width: 48, height: 48)
                .kccMapControl()
            if placement.extraCount > 0 {
                Text("+\(placement.extraCount)")
                    .font(.caption2.bold())
                    .foregroundStyle(KccTheme.onDriver)
                    .padding(4)
                    .background(KccTheme.driver, in: Capsule())
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(directionDescription(placement))
    }

    @ViewBuilder
    private func memberPhoto(_ member: ConvoyMemberPosition) -> some View {
        let url = member.imagePath.flatMap { imageURLs[$0] }
        ZStack {
            Circle().fill(KccTheme.driver)
            if let url {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Image(systemName: "car.side.fill").foregroundStyle(KccTheme.onDriver)
                }
            } else {
                Image(systemName: "car.side.fill").foregroundStyle(KccTheme.onDriver)
            }
        }
        .font(.system(size: 18, weight: .semibold))
        .frame(width: 38, height: 38)
        .clipShape(Circle())
        .overlay(Circle().stroke(KccTheme.driver, lineWidth: 2))
    }

    private func spokenName(_ member: ConvoyMemberPosition) -> String {
        member.displayName ?? String(localized: "convoy.barMemberUnnamed")
    }

    private func directionDescription(_ placement: ConvoyOffScreenPlacement) -> String {
        let name = spokenName(placement.member)
        let normalizedHour = (Int((placement.angleDegrees / 30).rounded()) % 12 + 12) % 12
        let clock = Int64(normalizedHour == 0 ? 12 : normalizedHour)
        let distance = distanceLabel(placement.distanceMeters)
        if placement.extraCount > 0 {
            return String.localizedStringWithFormat(
                NSLocalizedString("convoy.awarenessArrowGroup", comment: "Grouped off-map convoy members"),
                name, Int64(placement.extraCount), clock, distance
            )
        }
        return String.localizedStringWithFormat(
            NSLocalizedString("convoy.awarenessArrowSingle", comment: "Off-map convoy member"),
            name, clock, distance
        )
    }

    private func distanceLabel(_ meters: Double) -> String {
        if meters >= 1_000 {
            let value = String(format: "%.1f", locale: .current, meters / 1_000)
            return String.localizedStringWithFormat(
                NSLocalizedString("convoy.awarenessDistanceKm", comment: "Convoy distance in kilometres"),
                value
            )
        }
        return String.localizedStringWithFormat(
            NSLocalizedString("convoy.awarenessDistanceMeters", comment: "Convoy distance in metres"),
            Int64(meters.rounded())
        )
    }
}

private struct LiveConvoyInviteSheet: View {
    let target: ConvoySheetTarget
    let friendsCoordinator: FriendsCoordinator
    @Bindable var coordinator: ConvoyManagementCoordinator

    var body: some View {
        ConvoyInviteSheet(
            convoy: coordinator.convoy(id: target.id) ?? target.initial,
            friendsCoordinator: friendsCoordinator,
            coordinator: coordinator
        )
    }
}

struct ConvoyInviteSheet: View {
    @Environment(\.dismiss) private var dismiss
    let convoy: ConvoyItem
    @Bindable var friendsCoordinator: FriendsCoordinator
    @Bindable var coordinator: ConvoyManagementCoordinator

    @State private var selected = Set<String>()
    @State private var showInviteResult = false

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: KccSpacing.s3) {
                    switch friendsCoordinator.status {
                    case .loading:
                        ProgressView().frame(maxWidth: .infinity)
                    case .error(let error):
                        VStack(spacing: KccSpacing.s3) {
                            Text(FriendsScreenStrings.statusErrorKey(error)).foregroundStyle(KccTheme.secondary)
                            Button { Task { await friendsCoordinator.load() } } label: {
                                Text("convoy.friendsRetry").frame(minHeight: 48)
                            }.buttonStyle(.bordered)
                        }
                        .frame(maxWidth: .infinity)
                    case .loaded(let friends, _, _, _):
                        let available = friends.filter { !memberIds.contains($0.uid) }
                        if friends.isEmpty {
                            Text("convoy.noFriends").foregroundStyle(KccTheme.secondary)
                        } else if selectionLimit == 0 {
                            Text("convoy.inviteFull").foregroundStyle(KccTheme.secondary)
                        } else if available.isEmpty {
                            Text("convoy.inviteNoInvitable").foregroundStyle(KccTheme.secondary)
                        } else {
                            ForEach(available) { friend in
                                let isSelected = selected.contains(friend.uid)
                                Button {
                                    if isSelected {
                                        selected.remove(friend.uid)
                                    } else if selected.count < selectionLimit {
                                        selected.insert(friend.uid)
                                    }
                                } label: {
                                    ConvoyFriendChoiceLabel(friend: friend, selected: isSelected)
                                }
                                .buttonStyle(.plain)
                                .disabled(
                                    working || (!isSelected
                                        && selected.count >= selectionLimit)
                                )
                                .accessibilityAddTraits(isSelected ? [.isSelected] : [])
                            }
                        }
                    }

                    if let error = coordinator.actionError(for: convoy.convoyId) {
                        Text(ConvoyManagementStrings.errorKey(error))
                            .foregroundStyle(KccPalette.errorRed).kccContentCard()
                    }
                }
                .padding(KccSpacing.s4)
            }
            .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
            .navigationTitle("convoy.inviteTitle")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Text("convoy.inviteCancel").frame(minHeight: 48) }
                        .disabled(working)
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                Button {
                    guard !validSelection.isEmpty,
                          validSelection.count == selected.count,
                          selected.count <= selectionLimit
                    else { return }
                    let inviteeUids = Array(validSelection)
                    Task {
                        if await coordinator.invite(
                            convoyId: convoy.convoyId,
                            inviteeUids: inviteeUids
                        ) {
                            showInviteResult = true
                        }
                    }
                } label: { ConvoyCommandLabel(title: "convoy.inviteSubmit", working: working) }
                .kccPrimaryAction()
                .disabled(validSelection.isEmpty || validSelection.count != selected.count
                          || selected.count > selectionLimit || working)
                .padding(KccSpacing.s4).background(KccTheme.surface)
            }
            .task {
                coordinator.clearActionError()
                coordinator.clearInviteResult()
                await friendsCoordinator.load()
            }
            .onChange(of: memberIds) { _, _ in trimSelection() }
            .onChange(of: selectionLimit) { _, _ in trimSelection() }
        }
        .interactiveDismissDisabled(working)
        .alert(inviteResultMessage, isPresented: $showInviteResult) {
            Button("convoy.close") {
                coordinator.clearInviteResult()
                dismiss()
            }
        }
    }

    private var memberIds: Set<String> { Set(convoy.members.map(\.uid)) }
    private var working: Bool { coordinator.busyConvoyIds.contains(convoy.convoyId) }
    private var selectionLimit: Int { ConvoyBarLogic.maximumInviteSelection(for: convoy) }
    private var validSelection: Set<String> { selected.subtracting(memberIds) }

    private func trimSelection() {
        selected = Set(validSelection.sorted().prefix(selectionLimit))
    }

    private var inviteResultMessage: String {
        guard let result = coordinator.lastInviteResult else { return "" }
        if result.invitedCount == 0 {
            return String(localized: "convoy.inviteConfirmNoneAdded")
        }
        if result.skippedCount == 0 {
            return String.localizedStringWithFormat(
                NSLocalizedString("convoy.inviteConfirmInvited", comment: "Convoy invitation result"),
                result.invitedCount
            )
        }
        return String.localizedStringWithFormat(
            NSLocalizedString("convoy.inviteConfirmMixed", comment: "Convoy invitation result"),
            result.invitedCount,
            result.skippedCount
        )
    }
}

struct ConvoyDetailScreen: View {
    @Bindable var coordinator: ConvoyManagementCoordinator
    let convoy: ConvoyItem
    let friendsCoordinator: FriendsCoordinator?

    @State private var inviteTarget: ConvoySheetTarget?
    @State private var confirmation: ConvoyActionTarget?

    private var working: Bool { coordinator.busyConvoyIds.contains(convoy.convoyId) }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                ConvoySummaryContent(convoy: convoy).kccContentCard()
                if let error = coordinator.actionError(for: convoy.convoyId) {
                    ConvoyActionFeedback(error: error, onDismiss: coordinator.clearActionError)
                }
                if let recap = convoy.recap { ConvoyRecapCard(recap: recap) }
                if convoy.status != .ended, convoy.viewer?.inviteStatus == .accepted {
                    VStack(spacing: KccSpacing.s3) {
                        Button { inviteTarget = ConvoySheetTarget(convoy) } label: { ConvoyCommandLabel(title: "convoy.barInvite") }
                            .buttonStyle(.bordered).disabled(working || friendsCoordinator == nil)
                        if convoy.viewerIsOwner && convoy.status == .forming {
                            Button { confirm(.start) } label: { ConvoyCommandLabel(title: "convoy.start") }
                                .kccPrimaryAction().disabled(working)
                        }
                        if canLeave {
                            Button(role: .destructive) { confirm(.leave) } label: { ConvoyCommandLabel(title: leaveLabel) }
                                .buttonStyle(.bordered).tint(KccPalette.errorRed).disabled(working)
                        }
                        if convoy.viewerIsOwner {
                            Button(role: .destructive) { confirm(.end) } label: { ConvoyCommandLabel(title: "convoy.end") }
                                .buttonStyle(.bordered).tint(KccPalette.errorRed).disabled(working)
                        }
                        if working { ProgressView() }
                    }.kccContentCard()
                }
                Text("convoy.membersTitle").font(.title3.bold()).accessibilityAddTraits(.isHeader)
                ForEach(convoy.members) { member in
                    ConvoyMemberSummary(member: member).kccContentCard()
                }
            }.padding(KccSpacing.s4)
        }
        .navigationTitle("convoy.title")
        .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
        .sheet(item: $inviteTarget) { target in
            if let friendsCoordinator {
                LiveConvoyInviteSheet(
                    target: target,
                    friendsCoordinator: friendsCoordinator,
                    coordinator: coordinator
                )
            }
        }
        .confirmationDialog(
            confirmationTitle,
            isPresented: Binding(
                get: { confirmation != nil },
                set: { if !$0 { confirmation = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let confirmation {
                Button(confirmationActionLabel, role: confirmation.action == .start ? nil : .destructive) {
                    let target = confirmation
                    self.confirmation = nil
                    Task {
                        await coordinator.runLifecycle(
                            convoyId: target.convoyId,
                            action: target.action
                        )
                    }
                }
            }
            Button("convoy.barConfirmCancel", role: .cancel) { confirmation = nil }
        } message: {
            if let confirmationBody {
                Text(confirmationBody)
            }
        }
    }

    private var exitChoice: ConvoyExitChoice {
        ConvoyBarLogic.exitChoice(
            viewerIsOwner: convoy.viewerIsOwner,
            acceptedMemberCount: convoy.acceptedMemberCount
        )
    }
    private var canLeave: Bool { exitChoice != .endOnly }
    private var leaveLabel: LocalizedStringKey {
        exitChoice == .leaveEndsConvoy ? "convoy.barLeaveEnds" : "convoy.leave"
    }
    private var confirmationTitle: LocalizedStringKey {
        switch confirmation?.action {
        case .start: "convoy.start"
        case .end: "convoy.barEndConfirmTitle"
        case .leave:
            confirmation?.exitChoice == .leaveEndsConvoy
                ? "convoy.barLeaveEndsConfirmTitle"
                : "convoy.barLeaveConfirmTitle"
        case nil: "convoy.title"
        }
    }
    private var confirmationActionLabel: LocalizedStringKey {
        switch confirmation?.action {
        case .start: "convoy.start"
        case .end: "convoy.barEndConfirmAction"
        case .leave:
            confirmation?.exitChoice == .leaveEndsConvoy
                ? "convoy.barLeaveEndsConfirmAction"
                : "convoy.barLeaveConfirmAction"
        case nil: "convoy.close"
        }
    }

    private var confirmationBody: LocalizedStringKey? {
        switch confirmation?.action {
        case .start, nil: nil
        case .end: "convoy.barEndConfirmBody"
        case .leave:
            confirmation?.exitChoice == .leaveEndsConvoy
                ? "convoy.barLeaveEndsConfirmBody"
                : "convoy.barLeaveConfirmBody"
        }
    }

    private func confirm(_ action: ConvoyLifecycleAction) {
        confirmation = ConvoyActionTarget(
            convoyId: convoy.convoyId,
            action: action,
            exitChoice: exitChoice
        )
    }
}

/// Convoy members are deliberately excluded from the public nearby roster.
/// Resolve this card from its own authorized subscription, including on tap.
struct ConvoySharerDetail: View {
    let coordinator: ConvoyAwarenessCoordinator
    let uid: String
    let onUnavailable: () -> Void
    let onViewProfile: (String, String?) -> Void
    var clubsCoordinator: ClubsCoordinator? = nil
    var onViewClub: ((String) -> Void)? = nil

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let member = coordinator.visibleMembers.first { $0.uid == uid && $0.isFresh(at: context.date) }
            Group {
                if let member {
                    NearbySharerIdentity(
                        marker: LiveMarker(uid: member.uid, latitude: member.latitude, longitude: member.longitude,
                            displayName: member.displayName, imagePath: member.imagePath,
                            recordedAt: member.updatedAt, accuracyMeters: member.accuracyMeters),
                        imageURL: member.imagePath.flatMap { coordinator.imageURLs[$0] },
                        clubsCoordinator: clubsCoordinator,
                        onVisitClub: onViewClub.map { action in { clubID in
                            guard coordinator.profileTarget(uid: uid) != nil else { onUnavailable(); return }
                            action(clubID)
                        } },
                        onVisitProfile: {
                            guard let current = coordinator.profileTarget(uid: uid) else {
                                onUnavailable(); return
                            }
                            onViewProfile(current.uid, current.displayName)
                        }
                    )
                }
            }
            .onChange(of: member == nil, initial: true) { _, unavailable in
                if unavailable { onUnavailable() }
            }
        }
    }
}
