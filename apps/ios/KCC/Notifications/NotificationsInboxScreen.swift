import SwiftUI

/// Notification rows use the shell's shared push/inbox destinations. Read-state
/// changes are best effort and never block opening an invitation.
struct NotificationsInboxScreen: View {
    /// Nil only where no coordinator is constructed; the unavailable STATE
    /// (config-less build) is modelled inside the coordinator.
    let coordinator: NotificationsInboxCoordinator?
    var onOpenSettings: (() -> Void)? = nil
    var onOpen: ((NotificationDeepLink) -> Void)? = nil
    /// Embedded chat keeps its actions inline without a navigation bar.
    var showsNavigationTitle = true

    var body: some View {
        titledContent
            .kccReadingSurface(pageBackground: true)
            .task { coordinator?.start() }
            .onDisappear { coordinator?.stopViewing() }
            .toolbar { if showsNavigationTitle { toolbarContent } }
            .alert(
                Text("notifications.markReadError"),
                isPresented: markReadFailurePresented
            ) {
                Button("notifications.errorDismiss") {
                    coordinator?.resetMarkReadStatus()
                }
            }
    }

    private var markReadFailurePresented: Binding<Bool> {
        Binding(
            get: {
                guard let coordinator else { return false }
                if case .failed = coordinator.markReadStatus { return true }
                return false
            },
            set: { isPresented in
                if !isPresented {
                    coordinator?.resetMarkReadStatus()
                }
            }
        )
    }

    @ViewBuilder
    private var titledContent: some View {
        if showsNavigationTitle {
            content
                .navigationTitle(Text("notifications.title"))
                .navigationBarTitleDisplayMode(.inline)
        } else {
            VStack(spacing: 0) {
                if (coordinator?.unreadCount ?? 0) > 0 || onOpenSettings != nil {
                    HStack(spacing: KccSpacing.s2) {
                        if let coordinator, coordinator.unreadCount > 0 {
                            markAllReadButton(coordinator).buttonStyle(.bordered)
                        }
                        Spacer(minLength: 0)
                        settingsButton
                    }
                    .frame(minHeight: 48)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .padding(.horizontal, KccSpacing.s4)
                    .padding(.top, KccSpacing.s2)
                }
                content
            }
        }
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        // "Mark all as read" is offered only when there is something unread —
        // the aggregate gate Android puts on the same affordance.
        if let coordinator, coordinator.unreadCount > 0 {
            ToolbarItem(placement: .primaryAction) {
                markAllReadButton(coordinator)
            }
        }
        if onOpenSettings != nil {
            ToolbarItem(placement: .secondaryAction) { settingsButton }
        }
    }

    private func markAllReadButton(_ coordinator: NotificationsInboxCoordinator) -> some View {
        Button {
            Task { await coordinator.markAllRead() }
        } label: {
            Text("notifications.markAllRead").frame(minHeight: 48)
        }
        .disabled(coordinator.markReadStatus == .working)
        .accessibilityIdentifier("notifications.markAllRead")
    }

    @ViewBuilder
    private var settingsButton: some View {
        if let onOpenSettings {
            Button(action: onOpenSettings) {
                Image(systemName: "gearshape").frame(width: 48, height: 48)
            }
            .accessibilityLabel(Text("notifications.settingsTitle"))
            .accessibilityIdentifier("notifications.settings")
        }
    }

    @ViewBuilder
    private var content: some View {
        if let coordinator {
            switch coordinator.state {
            case .loading:
                loadingState
            case .unavailable:
                // Config-less build / no session: notifications are not
                // wired in this build — the shared cross-feature unavailable
                // copy, not the "empty inbox" string (Copilot review on
                // PR #1055: those read very differently to a member).
                messageState(title: "notifications.title", body: "shell.unavailable")
            case .empty:
                messageState(title: "notifications.title", body: "notifications.empty")
            case .failed:
                errorState
            case .loaded(let items):
                list(items, coordinator: coordinator)
            }
        } else {
            // No coordinator constructed at all — same unavailable case as
            // above, not an empty inbox.
            messageState(title: "notifications.title", body: "shell.unavailable")
        }
    }

    private var loadingState: some View {
        VStack(spacing: KccSpacing.s3) {
            ProgressView()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var errorState: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: KccSpacing.s3) {
                Text("notifications.loadError").font(.body)
                Button { coordinator?.reload() } label: {
                    Text("notifications.retry").frame(minHeight: 48)
                }.kccPrimaryAction()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .kccContentCard().padding(KccSpacing.s4)
        }
    }

    private func messageState(title: LocalizedStringKey, body: LocalizedStringKey) -> some View {
        SocialReadingState(title: title, message: body, symbol: "bell")
    }

    private func list(_ items: [AppNotification], coordinator: NotificationsInboxCoordinator) -> some View {
        List {
            ForEach(items) { item in
                Button {
                    if let destination = coordinator.destination(for: item) { onOpen?(destination) }
                    if !item.isRead {
                        Task { await coordinator.markRead(notificationId: item.id) }
                    }
                } label: {
                    NotificationRow(item: item)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("notifications.item.\(item.id)")
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
    }
}

/// One inbox row: the unread marker, category label, title, preview/body, and
/// the received timestamp — the teaser fields Android's inbox row renders.
struct NotificationRow: View {
    let item: AppNotification

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            SocialFlow {
                if !item.isRead {
                    Text("notifications.unreadLabel")
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, KccSpacing.s2)
                        .padding(.vertical, KccSpacing.s1 / 2)
                        .background(KccPalette.crownGold.opacity(0.2))
                        .clipShape(Capsule())
                }
                Text(LocalizedStringKey(item.category.labelKey))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let received = receivedText {
                Text(received)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(verbatim: item.title)
                .font(.headline.weight(item.isRead ? .regular : .semibold))
            if let detail = item.previewText ?? item.body,
                !detail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(verbatim: detail)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
        .kccContentCard(border: item.isRead ? KccTheme.border : KccTheme.driver)
    }

    /// "Received %1$@" (notifications.timeReceivedAt) with an abbreviated
    /// date+time; nil when the item carries no parseable timestamp (Android's
    /// "a row with no timestamp shows none").
    private var receivedText: String? {
        guard let createdAt = item.createdAt else { return nil }
        let formatted = createdAt.formatted(date: .abbreviated, time: .shortened)
        return String.localizedStringWithFormat(
            NSLocalizedString("notifications.timeReceivedAt", comment: "Received-at timestamp on a notification row"),
            formatted
        )
    }
}

#Preview {
    NavigationStack {
        NotificationsInboxScreen(coordinator: nil)
    }
}


/// Selected compact identity design, shared with the Android invitation card.
struct ConvoyInvitationCard: View {
    let invitation: AppNotification
    let deadline: TimeInterval
    let busy: Bool
    let error: LocalizedStringKey?
    let onJoin: () -> Void
    let onDecline: () -> Void
    @State private var avatarURL: URL?

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            HStack(spacing: KccSpacing.s3) {
                AsyncImage(url: avatarURL) { phase in
                    if let image = phase.image { image.resizable().scaledToFill() }
                    else {
                        Image(systemName: "person.fill").foregroundStyle(KccTheme.secondary)
                            .frame(maxWidth: .infinity, maxHeight: .infinity).background(KccTheme.subtle)
                    }
                }.frame(width: 48, height: 48).clipShape(Circle()).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: KccSpacing.s1) {
                    Text(verbatim: invitation.inviterDisplayName?.trimmedNonBlank ?? String(localized: "notifications.categoryConvoyInvite"))
                        .font(.headline)
                    if invitation.inviterDisplayName?.trimmedNonBlank != nil {
                        Text("convoy.invitationFrom").font(.subheadline).foregroundStyle(KccTheme.secondary)
                    } else {
                        Text(verbatim: invitation.previewText ?? invitation.title)
                            .font(.subheadline).foregroundStyle(KccTheme.secondary)
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "car.2.fill").font(.title2).foregroundStyle(KccPalette.crownGold).accessibilityHidden(true)
            }.accessibilityElement(children: .combine)
            if let error {
                Text(error).font(.subheadline).foregroundStyle(KccTheme.error)
                    .accessibilityIdentifier("convoy.invitation.error")
            }
            HStack(spacing: KccSpacing.s2) {
                Button(action: onDecline) {
                    Text("convoy.invitationDecline").frame(maxWidth: .infinity, minHeight: 48)
                        .contentShape(Rectangle())
                }.buttonStyle(.plain).background(KccTheme.subtle, in: RoundedRectangle(cornerRadius: KccRadius.md))
                    .overlay { RoundedRectangle(cornerRadius: KccRadius.md).strokeBorder(KccTheme.border) }
                    .accessibilityIdentifier("convoy.invitation.decline")
                Button(action: onJoin) {
                    Group {
                        if busy { ProgressView().tint(KccPalette.inkBlack) }
                        else { Text("convoy.invitationJoin").fontWeight(.semibold) }
                    }.frame(maxWidth: .infinity, minHeight: 48).contentShape(Rectangle())
                }.buttonStyle(.plain).foregroundStyle(KccPalette.inkBlack)
                    .background(KccPalette.crownGold, in: RoundedRectangle(cornerRadius: KccRadius.md))
                    .accessibilityIdentifier("convoy.invitation.join")
            }.disabled(busy)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: KccSpacing.s3) { footer; Spacer(minLength: 0); countdown }
                VStack(alignment: .leading, spacing: KccSpacing.s1) { footer; countdown }
            }.font(.footnote).foregroundStyle(KccTheme.secondary)
        }
        .padding(KccSpacing.s4)
        .padding(.leading, KccSpacing.s1)
        .foregroundStyle(KccTheme.text)
        .background(KccTheme.surface)
        .overlay(alignment: .leading) { Rectangle().fill(KccPalette.crownGold).frame(width: KccSpacing.s1).accessibilityHidden(true) }
        .clipShape(RoundedRectangle(cornerRadius: KccRadius.lg))
        .shadow(color: .black.opacity(0.12), radius: 4, y: 2)
        .accessibilityIdentifier("convoy.invitation.banner")
        .task(id: invitation.inviterAvatarPath) {
            avatarURL = nil
            guard let path = invitation.inviterAvatarPath else { return }
            let url = await FirebaseUserProfileRepository.createIfAvailable()?.avatarDownloadURL(for: path)
            guard !Task.isCancelled else { return }
            avatarURL = url
        }
    }

    private var footer: some View { Text("convoy.invitationInboxHint") }
    private var countdown: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            Text(verbatim: String.localizedStringWithFormat(
                NSLocalizedString("convoy.invitationClosesIn", comment: "Invitation display time"),
                max(1, min(30, Int(ceil(deadline - ProcessInfo.processInfo.systemUptime))))
            ))
        }.accessibilityHidden(true)
    }
}
