import SwiftUI

/// The in-app notification inbox — the iOS slice of Android's
/// `NotificationsScreen` (list + mark-read + mark-all-read). Deep-link routing
/// on tap, swipe-to-delete, and the in-place friend/convoy actions are
/// DELIBERATELY out of this slice: the tap destination is modelled
/// (``NotificationDeepLink``) and exported for the future wiring PR, but no
/// ``ShellView`` routing is wired here.
///
/// A dumb switch over ``NotificationsInboxUiState``: all decisions live in the
/// pure ``NotificationsInboxCoordinator``. The `coordinator` is nil only in a
/// context that never constructs one; a config-less build constructs the
/// coordinator with a nil repository, which resolves to
/// ``NotificationsInboxUiState/unavailable`` and renders the placeholder — the
/// same seam every Firebase-backed surface honors.
///
/// Reached via ``ShellRoute/notifications`` — the shell wraps this screen in a
/// `NavigationStack` and supplies the Back affordance (future wiring PR).
struct NotificationsInboxScreen: View {
    /// Nil only where no coordinator is constructed; the unavailable STATE
    /// (config-less build) is modelled inside the coordinator.
    let coordinator: NotificationsInboxCoordinator?

    var body: some View {
        content
            .navigationTitle(Text("notifications.title"))
            .task { coordinator?.start() }
            .toolbar { toolbarContent }
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        // "Mark all as read" is offered only when there is something unread —
        // the aggregate gate Android puts on the same affordance.
        if let coordinator, coordinator.unreadCount > 0 {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    Task { await coordinator.markAllRead() }
                } label: {
                    Text("notifications.markAllRead")
                }
                .disabled(coordinator.markReadStatus == .working)
            }
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
        VStack(spacing: KccSpacing.s3) {
            Text("notifications.loadError")
                .multilineTextAlignment(.center)
            Button {
                coordinator?.reload()
            } label: {
                Text("notifications.retry")
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(KccSpacing.s4)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func messageState(title: LocalizedStringKey, body: LocalizedStringKey) -> some View {
        VStack(spacing: KccSpacing.s2) {
            Text(title)
                .font(.system(size: KccTypeScale.titleMd, weight: KccTypeScale.semibold))
            Text(body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(KccSpacing.s4)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func list(_ items: [AppNotification], coordinator: NotificationsInboxCoordinator) -> some View {
        List {
            // A tap on an UNREAD row marks it read; a read row is inert. The
            // list itself updates from the next listener snapshot, so the row's
            // read styling follows the server, never an optimistic flip.
            ForEach(items) { item in
                Button {
                    guard !item.isRead else { return }
                    Task { await coordinator.markRead(notificationId: item.id) }
                } label: {
                    NotificationRow(item: item)
                }
                .buttonStyle(.plain)
                .disabled(item.isRead)
            }
        }
        .listStyle(.insetGrouped)
    }
}

/// One inbox row: the unread marker, category label, title, preview/body, and
/// the received timestamp — the teaser fields Android's inbox row renders.
private struct NotificationRow: View {
    let item: AppNotification

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s1) {
            HStack(spacing: KccSpacing.s2) {
                if !item.isRead {
                    Text("notifications.unreadLabel")
                        .font(.system(size: KccTypeScale.caption, weight: KccTypeScale.semibold))
                        .padding(.horizontal, KccSpacing.s2)
                        .padding(.vertical, KccSpacing.s1 / 2)
                        .background(KccPalette.crownGold.opacity(0.2))
                        .clipShape(Capsule())
                }
                Text(LocalizedStringKey(item.category.labelKey))
                    .font(.system(size: KccTypeScale.caption))
                    .foregroundStyle(.secondary)
                Spacer(minLength: KccSpacing.s2)
                if let received = receivedText {
                    Text(received)
                        .font(.system(size: KccTypeScale.caption))
                        .foregroundStyle(.secondary)
                }
            }
            Text(item.title)
                .font(.system(size: KccTypeScale.bodyMd, weight: item.isRead ? .regular : KccTypeScale.semibold))
            if let detail = item.previewText ?? item.body,
                !detail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(detail)
                    .font(.system(size: KccTypeScale.bodySm))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, KccSpacing.s1)
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
