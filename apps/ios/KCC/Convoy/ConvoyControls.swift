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

struct ConvoyStatusBar: View {
    @Bindable var coordinator: ConvoyManagementCoordinator
    let convoy: ConvoyItem
    let friendsCoordinator: FriendsCoordinator?

    @State private var showMembers = false
    @State private var showInvite = false
    @State private var exitTarget: ConvoyExitTarget?

    private var working: Bool {
        coordinator.busyConvoyIds.contains(convoy.convoyId)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            HStack(spacing: KccSpacing.s2) {
                Button { showMembers = true } label: {
                    Label(memberCount, systemImage: "person.3.fill")
                        .lineLimit(1)
                }
                .buttonStyle(.plain)

                Spacer(minLength: KccSpacing.s2)

                Button { showInvite = true } label: {
                    Image(systemName: "person.badge.plus")
                }
                .disabled(working || friendsCoordinator == nil)
                .accessibilityLabel(Text("convoy.barInvite"))

                Button {
                    exitTarget = ConvoyExitTarget(convoyId: convoy.convoyId, choice: exitChoice)
                } label: {
                    if working {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: convoy.viewerIsOwner ? "stop.circle" : "rectangle.portrait.and.arrow.right")
                    }
                }
                .disabled(working)
                .accessibilityLabel(Text(exitLabel))
            }

            if let error = coordinator.actionError {
                Text(ConvoyManagementStrings.errorKey(error))
                    .font(.system(size: KccTypeScale.caption))
                    .foregroundStyle(KccPalette.errorRed)
                    .lineLimit(2)
            }
        }
        .font(.system(size: KccTypeScale.bodySm, weight: .semibold))
        .padding(.horizontal, KccSpacing.s4)
        .padding(.vertical, KccSpacing.s3)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: KccRadius.md))
        .shadow(color: .black.opacity(0.14), radius: 8, y: 3)
        .sheet(isPresented: $showMembers) {
            ConvoyMembersSheet(convoy: convoy)
        }
        .sheet(isPresented: $showInvite) {
            if let friendsCoordinator {
                ConvoyInviteSheet(
                    convoy: convoy,
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
        // The active driving surface owns the refresh lifetime. When the bar
        // disappears because the convoy ended or the user left, this task is
        // cancelled with it and the polling stops.
        .task(id: convoy.convoyId) {
            var refreshDelaySeconds = 60
            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: .seconds(refreshDelaySeconds))
                } catch {
                    return
                }
                let refreshed = await coordinator.refresh()
                refreshDelaySeconds = refreshed ? 60 : min(refreshDelaySeconds * 2, 300)
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

    var body: some View {
        NavigationStack {
            List {
                Section("convoy.membersTitle") {
                    ForEach(convoy.acceptedMembers) { member in
                        memberRow(member, waiting: false)
                    }
                    ForEach(convoy.pendingMembers) { member in
                        memberRow(member, waiting: true)
                    }
                }
            }
            .navigationTitle("convoy.barMemberListTitle")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("convoy.close") { dismiss() }
                }
            }
        }
    }

    private func memberRow(_ member: ConvoyMember, waiting: Bool) -> some View {
        HStack(spacing: KccSpacing.s3) {
            Image(systemName: member.role == .owner ? "crown.fill" : "person.crop.circle.fill")
                .foregroundStyle(member.role == .owner ? KccPalette.crownGold : .secondary)
            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                Text(member.displayName ?? String(localized: "convoy.barMemberUnnamed"))
                if waiting {
                    Text("convoy.barMemberWaiting")
                        .font(.system(size: KccTypeScale.caption))
                        .foregroundStyle(.secondary)
                } else if member.role == .owner {
                    Text("convoy.roleOwner")
                        .font(.system(size: KccTypeScale.caption))
                        .foregroundStyle(.secondary)
                }
            }
        }
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
            List {
                switch friendsCoordinator.status {
                case .loading:
                    ProgressView().frame(maxWidth: .infinity)
                case .error(let error):
                    VStack(spacing: KccSpacing.s3) {
                        Text(FriendsScreenStrings.statusErrorKey(error)).foregroundStyle(.secondary)
                        Button("convoy.friendsRetry") {
                            Task { await friendsCoordinator.load() }
                        }
                    }
                    .frame(maxWidth: .infinity)
                case .loaded(let friends, _, _, _):
                    let available = friends.filter { !memberIds.contains($0.uid) }
                    if friends.isEmpty {
                        Text("convoy.noFriends").foregroundStyle(.secondary)
                    } else if available.isEmpty {
                        Text("convoy.inviteNoInvitable").foregroundStyle(.secondary)
                    } else {
                        ForEach(available) { friend in
                            let isSelected = selected.contains(friend.uid)
                            Button {
                                if isSelected {
                                    selected.remove(friend.uid)
                                } else if selected.count < ConvoyBarLogic.maximumInviteBatchSize {
                                    selected.insert(friend.uid)
                                }
                            } label: {
                                HStack {
                                    Text(friend.displayName ?? String(localized: "convoy.unknownMember"))
                                    Spacer()
                                    if isSelected {
                                        Image(systemName: "checkmark.circle.fill")
                                    }
                                }
                            }
                            .buttonStyle(.plain)
                            .disabled(
                                working || (!isSelected
                                    && selected.count >= ConvoyBarLogic.maximumInviteBatchSize)
                            )
                            .accessibilityAddTraits(isSelected ? [.isSelected] : [])
                        }
                    }
                }

                if let error = coordinator.actionError {
                    Section {
                        Text(ConvoyManagementStrings.errorKey(error))
                            .foregroundStyle(KccPalette.errorRed)
                    }
                }
            }
            .navigationTitle("convoy.inviteTitle")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("convoy.inviteCancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("convoy.inviteSubmit") {
                        Task {
                            if await coordinator.invite(
                                convoyId: convoy.convoyId,
                                inviteeUids: Array(selected)
                            ) {
                                showInviteResult = true
                            }
                        }
                    }
                    .disabled(selected.isEmpty || working)
                }
            }
            .task {
                coordinator.clearActionError()
                coordinator.clearInviteResult()
                await friendsCoordinator.load()
            }
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

    @State private var showInvite = false
    @State private var confirmation: ConvoyActionTarget?

    private var working: Bool { coordinator.busyConvoyIds.contains(convoy.convoyId) }

    var body: some View {
        List {
            Section {
                HStack {
                    Text(convoy.title ?? String(localized: "convoy.untitled"))
                        .font(.headline)
                    Spacer()
                    Text(statusKey)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if let error = coordinator.actionError {
                Section {
                    Text(ConvoyManagementStrings.errorKey(error))
                        .foregroundStyle(KccPalette.errorRed)
                }
            }

            Section("convoy.membersTitle") {
                ForEach(convoy.members) { member in
                    HStack {
                        Image(systemName: member.role == .owner ? "crown.fill" : "person.crop.circle")
                        Text(member.displayName ?? String(localized: "convoy.unknownMember"))
                        Spacer()
                        Text(inviteKey(member.inviteStatus)).foregroundStyle(.secondary)
                    }
                }
            }

            if convoy.status != .ended, convoy.viewer?.inviteStatus == .accepted {
                Section {
                    Button("convoy.barInvite") { showInvite = true }
                        .disabled(working || friendsCoordinator == nil)
                    if convoy.viewerIsOwner && convoy.status == .forming {
                        Button("convoy.start") { confirm(.start) }
                            .disabled(working)
                    }
                    if canLeave {
                        Button(leaveLabel, role: .destructive) { confirm(.leave) }
                            .disabled(working)
                    }
                    if convoy.viewerIsOwner && convoy.status == .active {
                        Button("convoy.end", role: .destructive) { confirm(.end) }
                            .disabled(working)
                    }
                }
            }
        }
        .navigationTitle("convoy.title")
        .sheet(isPresented: $showInvite) {
            if let friendsCoordinator {
                ConvoyInviteSheet(
                    convoy: convoy,
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
    private var statusKey: LocalizedStringKey {
        switch convoy.status {
        case .forming: "convoy.statusForming"
        case .active: "convoy.statusActive"
        case .ended: "convoy.statusEnded"
        }
    }
    private func inviteKey(_ status: ConvoyInviteStatus) -> LocalizedStringKey {
        switch status {
        case .invited: "convoy.inviteInvited"
        case .accepted: "convoy.inviteAccepted"
        case .declined: "convoy.inviteDeclined"
        }
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
