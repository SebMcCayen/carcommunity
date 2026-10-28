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
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Bindable var coordinator: ConvoyManagementCoordinator
    let convoy: ConvoyItem
    let friendsCoordinator: FriendsCoordinator?
    @Bindable var awareness: ConvoyAwarenessCoordinator
    let mapSurface: StubMapSurface
    let liveLocationEnabled: Bool
    let viewerUid: String?
    var onOpenMemberProfile: ((String, String?) -> Void)? = nil

    @State private var memberTarget: ConvoySheetTarget?
    @State private var inviteTarget: ConvoySheetTarget?
    @State private var exitTarget: ConvoyExitTarget?

    private var working: Bool {
        coordinator.busyConvoyIds.contains(convoy.convoyId)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            HStack(spacing: KccSpacing.s2) {
                Button { memberTarget = ConvoySheetTarget(convoy) } label: {
                    Group {
                        if dynamicTypeSize.isAccessibilitySize { Image(systemName: "person.3.fill") }
                        else { Label(memberCount, systemImage: "person.3.fill").lineLimit(1) }
                    }
                    .frame(minWidth: 48, minHeight: 48).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(memberCount)

                Spacer(minLength: KccSpacing.s2)

                if liveLocationEnabled {
                    Button {
                        awareness.focusMode = awareness.focusMode == .me ? .convoy : .me
                    } label: {
                        Image(systemName: awareness.focusMode == .convoy ? "person.3.fill" : "person.3")
                            .frame(width: 48, height: 48).contentShape(Rectangle())
                    }
                    .accessibilityLabel(Text(
                        awareness.focusMode == .convoy ? "convoy.barFocusConvoy" : "convoy.barFocusMe"
                    ))
                }

                Button { inviteTarget = ConvoySheetTarget(convoy) } label: {
                    Image(systemName: "person.badge.plus")
                        .frame(width: 48, height: 48).contentShape(Rectangle())
                }
                .disabled(working || friendsCoordinator == nil)
                .accessibilityLabel(Text("convoy.barInvite"))

                Button {
                    exitTarget = ConvoyExitTarget(convoyId: convoy.convoyId, choice: exitChoice)
                } label: {
                    Group {
                        if working { ProgressView().controlSize(.small) }
                        else { Image(systemName: convoy.viewerIsOwner ? "stop.circle" : "rectangle.portrait.and.arrow.right") }
                    }
                    .frame(width: 48, height: 48).contentShape(Rectangle())
                }
                .disabled(working)
                .accessibilityLabel(Text(exitLabel))
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
        .font(.system(size: KccTypeScale.bodySm, weight: .semibold))
        .padding(.horizontal, KccSpacing.s4)
        .padding(.vertical, KccSpacing.s3)
        .foregroundStyle(KccTheme.text)
        .background(KccTheme.overlay, in: RoundedRectangle(cornerRadius: KccRadius.lg))
        .overlay { RoundedRectangle(cornerRadius: KccRadius.lg).strokeBorder(KccTheme.border).allowsHitTesting(false) }
        .shadow(color: .black.opacity(0.14), radius: 8, y: 3)
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
        case .leaveOnly, .leaveEndsConvoy: "convoy.barLeave"
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
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button { dismiss() } label: { Text("convoy.close").frame(minHeight: 48) }
                }
            }
        }
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

    var body: some View {
        GeometryReader { geometry in
            TimelineView(.periodic(from: .now, by: 30)) { context in
                let placements = placements(size: geometry.size, now: context.date)
                ZStack {
                    ForEach(placements.onScreen) { placement in
                        memberChip(placement.member)
                            .position(x: placement.point.x, y: placement.point.y)
                    }
                    ForEach(placements.offScreen) { placement in
                        edgeChip(placement)
                            .position(x: placement.point.x, y: placement.point.y)
                    }
                }
            }
        }
        .allowsHitTesting(false)
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
