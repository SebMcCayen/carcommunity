import SwiftUI

struct ClubChatScreen: View {
    let coordinator: ClubChatCoordinator?
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.chatReadingActive) private var readingActive
    @State private var cooldownSeconds = 0

    var body: some View {
        Group {
            if let coordinator, coordinator.snapshot != nil {
                room(coordinator)
            } else {
                ScrollView {
                    Text("clubs.noClubChat")
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity).padding(KccSpacing.s4)
                }
            }
        }
        .kccReadingSurface(pageBackground: true)
        .accessibilityIdentifier("clubChat.screen")
        .onChange(of: readingActive && scenePhase == .active, initial: true) { _, active in
            if active { coordinator?.start() } else { coordinator?.stop() }
        }
        .onChange(of: coordinator.map(ObjectIdentifier.init)) { _, _ in
            if readingActive && scenePhase == .active { coordinator?.start() }
        }
        .onDisappear { coordinator?.stop() }
    }

    private func room(_ coordinator: ClubChatCoordinator) -> some View {
        let messages = coordinator.messages
        return ChatThreadScrollView(latestMessageID: messages.last?.id,
                                    latestIsOwn: messages.last?.senderUid == coordinator.uid) {
            if coordinator.snapshot?.hasOlder == true {
                Button("clubs.older") { Task { await coordinator.loadOlder() } }
                    .disabled(coordinator.paging)
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            if messages.isEmpty { Text("clubs.emptyChat").foregroundStyle(KccTheme.secondary) }
            ForEach(messages) { message in
                row(message, coordinator: coordinator).id(message.id)
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            composer(coordinator)
        }
        .task(id: coordinator.cooldown.deadlineToken) {
            cooldownSeconds = coordinator.cooldown.remainingSeconds
            while cooldownSeconds > 0 {
                do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
                cooldownSeconds = coordinator.cooldown.remainingSeconds
            }
        }
    }

    private func row(_ message: ChannelMessage, coordinator: ClubChatCoordinator) -> some View {
        let own = message.senderUid == coordinator.uid
        return HStack {
            if own { Spacer(minLength: 32) }
            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                Text(message.senderDisplayName ?? String(localized: "clubs.member"))
                    .font(.caption.weight(.semibold))
                Text(message.text).textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                if let date = message.createdAt {
                    Text(date, style: .time).font(.caption2)
                }
                if message.deliveryState == .failed, message.sendError?.isRetryable == true {
                    Button("dm.retry") { _ = coordinator.send(message.text, retry: message) }
                        .disabled(cooldownSeconds > 0)
                        .frame(minHeight: 44)
                } else if message.deliveryState == .sending {
                    ProgressView().accessibilityLabel(Text("channel.send"))
                }
            }
            .padding(KccSpacing.s3)
            .foregroundStyle(own ? KccTheme.onGold : KccTheme.text)
            .background(own ? KccPalette.crownGold : KccTheme.surface,
                        in: RoundedRectangle(cornerRadius: KccRadius.md))
            .contextMenu {
                if message.deliveryState == .sent {
                    if !own {
                        Menu("clubs.report") {
                            ForEach(ChatReportReason.allCases, id: \.self) { reason in
                                Button(LocalizedStringKey("chat.reportReason." + reason.wire)) {
                                    Task { await coordinator.act("reportMessage", message: message, reason: reason) }
                                }
                            }
                        }
                        Button("clubs.block", role: .destructive) {
                            Task { await coordinator.act("block", message: message) }
                        }
                    }
                    if coordinator.snapshot?.membership.canModerate == true {
                        Button("clubs.hideMessage", role: .destructive) {
                            Task { await coordinator.act("removeMessage", message: message) }
                        }
                    }
                }
            }
            if !own { Spacer(minLength: 32) }
        }
    }

    private func composer(_ coordinator: ClubChatCoordinator) -> some View {
        @Bindable var coordinator = coordinator
        let enabled = cooldownSeconds == 0
        return VStack(spacing: KccSpacing.s1) {
            Divider()
            if coordinator.failed { Text("clubs.error").font(.footnote).foregroundStyle(KccTheme.error) }
            if coordinator.reported { Text("clubs.reported").font(.footnote) }
            if !enabled { Text(String.localizedStringWithFormat(NSLocalizedString("chat.flood.wait", comment: ""), Int64(cooldownSeconds))).font(.footnote) }
            ChatQuickEmojiRow(enabled: enabled) { _ = coordinator.send($0) }
            HStack(spacing: KccSpacing.s2) {
                TextField("clubs.message", text: $coordinator.draft, axis: .vertical)
                    .lineLimit(1...3).textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier("clubChat.input")
                ChatKeyboardDismissButton()
                Button("clubs.send") { coordinator.sendDraft() }
                    .kccPrimaryAction()
                    .disabled(!enabled || coordinator.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                              || coordinator.draft.unicodeScalars.count > 2000)
                    .accessibilityIdentifier("clubChat.send")
            }.padding(KccSpacing.s2)
        }
        .background(KccTheme.surface)
    }
}
