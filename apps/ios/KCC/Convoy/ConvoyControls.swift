import SwiftUI

struct ConvoyStatusBar: View {
    @Bindable var coordinator: ConvoyManagementCoordinator
    let convoy: ConvoyItem
    let friendsCoordinator: FriendsCoordinator?

    @State private var showMembers = false
    @State private var showInvite = false
    @State private var showExit = false

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

                Button { showExit = true } label: {
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
            isPresented: $showExit,
            titleVisibility: .visible
        ) {
            switch exitChoice {
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
            Button("convoy.barConfirmCancel", role: .cancel) {}
        } message: {
            Text(exitBody)
        }
        // The active driving surface owns the refresh lifetime. When the bar
        // disappears because the convoy ended or the user left, this task is
        // cancelled with it and the polling stops.
        .task(id: convoy.convoyId) {
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(15)) } catch { return }
                await coordinator.refresh()
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
        switch exitChoice {
        case .leaveOrEnd: "convoy.barExitChoiceTitle"
        case .endOnly: "convoy.barEndConfirmTitle"
        case .leaveOnly: "convoy.barLeaveConfirmTitle"
        case .leaveEndsConvoy: "convoy.barLeaveEndsConfirmTitle"
        }
    }

    private var exitBody: LocalizedStringKey {
        switch exitChoice {
        case .leaveOrEnd: "convoy.barExitChoiceBody"
        case .endOnly: "convoy.barEndConfirmBody"
        case .leaveOnly: "convoy.barLeaveConfirmBody"
        case .leaveEndsConvoy: "convoy.barLeaveEndsConfirmBody"
        }
    }

    private func run(_ action: ConvoyLifecycleAction) {
        Task { await coordinator.runLifecycle(convoyId: convoy.convoyId, action: action) }
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

    var body: some View {
        NavigationStack {
            List {
                switch friendsCoordinator.status {
                case .loading:
                    ProgressView().frame(maxWidth: .infinity)
                case .error:
                    VStack(spacing: KccSpacing.s3) {
                        Text("convoy.errorGeneric").foregroundStyle(.secondary)
                        Button("convoy.friendsRetry") {
                            Task { await friendsCoordinator.load() }
                        }
                    }
                    .frame(maxWidth: .infinity)
                case .loaded(let friends, _, _, _):
                    let available = friends.filter { !memberIds.contains($0.uid) }
                    if available.isEmpty {
                        Text("convoy.inviteNoInvitable").foregroundStyle(.secondary)
                    } else {
                        ForEach(available) { friend in
                            Button {
                                if selected.contains(friend.uid) {
                                    selected.remove(friend.uid)
                                } else {
                                    selected.insert(friend.uid)
                                }
                            } label: {
                                HStack {
                                    Text(friend.displayName ?? String(localized: "convoy.unknownMember"))
                                    Spacer()
                                    if selected.contains(friend.uid) {
                                        Image(systemName: "checkmark.circle.fill")
                                    }
                                }
                            }
                            .buttonStyle(.plain)
                        }
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
                                dismiss()
                            }
                        }
                    }
                    .disabled(selected.isEmpty || working)
                }
            }
            .task { await friendsCoordinator.load() }
        }
        .interactiveDismissDisabled(working)
    }

    private var memberIds: Set<String> { Set(convoy.members.map(\.uid)) }
    private var working: Bool { coordinator.busyConvoyIds.contains(convoy.convoyId) }
}

struct ConvoyDetailScreen: View {
    @Bindable var coordinator: ConvoyManagementCoordinator
    let convoy: ConvoyItem
    let friendsCoordinator: FriendsCoordinator?

    @State private var showInvite = false
    @State private var confirmation: ConvoyLifecycleAction?

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

            if convoy.status != .ended {
                Section {
                    Button("convoy.barInvite") { showInvite = true }
                        .disabled(working || friendsCoordinator == nil)
                    if convoy.viewerIsOwner && convoy.status == .forming {
                        Button("convoy.start") { confirmation = .start }
                            .disabled(working)
                    }
                    if canLeave {
                        Button(leaveLabel, role: .destructive) { confirmation = .leave }
                            .disabled(working)
                    }
                    if convoy.viewerIsOwner && convoy.status == .active {
                        Button("convoy.end", role: .destructive) { confirmation = .end }
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
                Button(confirmationActionLabel, role: confirmation == .start ? nil : .destructive) {
                    let action = confirmation
                    self.confirmation = nil
                    Task { await coordinator.runLifecycle(convoyId: convoy.convoyId, action: action) }
                }
            }
            Button("convoy.barConfirmCancel", role: .cancel) { confirmation = nil }
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
        switch confirmation {
        case .start: "convoy.start"
        case .end: "convoy.barEndConfirmTitle"
        case .leave:
            exitChoice == .leaveEndsConvoy
                ? "convoy.barLeaveEndsConfirmTitle"
                : "convoy.barLeaveConfirmTitle"
        case nil: "convoy.title"
        }
    }
    private var confirmationActionLabel: LocalizedStringKey {
        switch confirmation {
        case .start: "convoy.start"
        case .end: "convoy.barEndConfirmAction"
        case .leave:
            exitChoice == .leaveEndsConvoy
                ? "convoy.barLeaveEndsConfirmAction"
                : "convoy.barLeaveConfirmAction"
        case nil: "convoy.close"
        }
    }
}
