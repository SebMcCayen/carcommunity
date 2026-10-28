import SwiftUI
import UIKit

struct FeedbackScreen: View {
    @Environment(\.openURL) private var openURL
    @Bindable var coordinator: FeedbackCoordinator
    let openTicketsEnabled: Bool
    let onOpenTickets: () -> Void
    let onClose: () -> Void

    @State private var form = FeedbackReportForm()
    @State private var showValidationError = false

    private let context = FeedbackClientContext.current

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: KccSpacing.s4) {
                if case .submitted(let issueURL, let issueNumber, let summary) = coordinator.status {
                    confirmation(issueURL: issueURL, issueNumber: issueNumber, summary: summary)
                } else {
                    reportForm
                }
            }
            .padding(KccSpacing.s4)
        }
        .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
        .navigationTitle(Text("feedback.title"))
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: coordinator.status) { _, status in
            if case .submitted = status {
                form = FeedbackReportForm()
                showValidationError = false
            }
        }
    }

    private var reportForm: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s4) {
            VStack(alignment: .leading, spacing: KccSpacing.s2) {
                SocialFlow {
                    Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 24)).accessibilityHidden(true)
                    Text("feedback.publicNoticeTitle").font(.headline).fixedSize(horizontal: false, vertical: true)
                }
                Text("feedback.publicNotice")
                    .font(.subheadline)
            }
            .foregroundStyle(KccTheme.error)
            .padding(KccSpacing.s4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                KccTheme.error.opacity(0.1),
                in: RoundedRectangle(cornerRadius: KccRadius.md)
            )

            EditorTextField(title: "feedback.summaryLabel", text: $form.summary)
                .textFieldStyle(.roundedBorder)
                .onChange(of: form.summary) { _, value in
                    form.summary = FeedbackText.prefixByUTF16(
                        value,
                        limit: FeedbackReports.maximumSummaryLength
                    )
                }

            EditorTextField(title: "feedback.descriptionLabel", text: $form.description, multiline: true)
                .onChange(of: form.description) { _, value in
                    form.description = FeedbackText.prefixByUTF16(
                        value,
                        limit: FeedbackReports.maximumDescriptionLength
                    )
                    if FeedbackReports.validate(form) == nil { showValidationError = false }
                }

            if showValidationError, FeedbackReports.validate(form) != nil {
                Text("feedback.descriptionRequired")
                    .font(.footnote)
                    .foregroundStyle(.red)
            }

            if case .failed(let failure) = coordinator.status {
                Text(failure.messageKey)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
            if coordinator.status == .unavailable {
                Text("shell.unavailable")
                    .font(.footnote)
                    .foregroundStyle(KccTheme.secondary)
            }

            Button {
                guard FeedbackReports.validate(form) == nil else {
                    showValidationError = true
                    return
                }
                Task { await coordinator.submit(form: form, context: context) }
            } label: {
                EditorSubmitLabel(title: "feedback.submit", saving: coordinator.status == .submitting)
            }
            .kccPrimaryAction()
            .disabled(coordinator.status == .submitting || coordinator.status == .unavailable)

            if openTicketsEnabled {
                Button(action: onOpenTickets) { Text("feedback.openTicketsButton").frame(maxWidth: .infinity, minHeight: 48) }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    @ViewBuilder
    private func confirmation(issueURL: URL?, issueNumber: Int?, summary: String?) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Label("feedback.thankYouTitle", systemImage: "checkmark.circle.fill")
                .font(.title3.weight(.semibold))
                .foregroundStyle(.green)
            Text("feedback.thankYouBody")
            if let summary { Text(summary).italic() }
            if let issueNumber {
                Text(Self.localizedFormat("feedback.issueNumber", issueNumber))
                    .font(.headline)
            }
            if issueURL != nil { Text("feedback.thankYouIssue").font(.subheadline) }
        }
        .padding(KccSpacing.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.green.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))

        if let issueURL {
            Button { openURL(issueURL) } label: { Text("feedback.viewIssue").frame(maxWidth: .infinity, minHeight: 48) }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
        }
        Button {
            coordinator.reset()
            onClose()
        } label: { Text("feedback.close").frame(maxWidth: .infinity, minHeight: 48) }
            .kccPrimaryAction()
            .frame(maxWidth: .infinity)
    }

    private static func localizedFormat(_ key: String.LocalizationValue, _ value: Int) -> String {
        String.localizedStringWithFormat(String(localized: key), value)
    }
}

struct OpenTicketsScreen: View {
    @Environment(\.openURL) private var openURL
    @Bindable var coordinator: OpenTicketsCoordinator

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                Text("openTickets.intro").font(.subheadline).foregroundStyle(KccTheme.secondary)
                    .fixedSize(horizontal: false, vertical: true).kccContentCard()
                switch coordinator.listState {
                case .loading: ProgressView("openTickets.loading")
                case .failed: SettingsStateCard(title: "openTickets.title", message: "openTickets.error", symbol: "exclamationmark.circle")
                case .unavailable: SettingsStateCard(title: "openTickets.title", message: "shell.unavailable", symbol: "wifi.slash")
                case .loaded(let tickets):
                    if tickets.isEmpty { SettingsStateCard(title: "openTickets.title", message: "openTickets.empty", symbol: "checkmark.circle") }
                    ForEach(tickets) { ticket in
                        TicketCard(ticket: ticket,
                            state: coordinator.interactions[ticket.number] ?? TicketInteractionState(),
                            onPlusOne: { Task { await coordinator.plusOne(issueNumber: ticket.number) } },
                            onComment: { text in Task { await coordinator.comment(issueNumber: ticket.number, text: text) } },
                            onCommentEdited: { coordinator.clearError(issueNumber: ticket.number) },
                            onOpen: { openURL(ticket.htmlURL) })
                    }
                    if coordinator.canLoadMore || coordinator.isLoadingMore {
                        Button { coordinator.loadMore() } label: {
                            EditorSubmitLabel(title: "openTickets.loadMore", saving: coordinator.isLoadingMore)
                        }.buttonStyle(.bordered).disabled(coordinator.isLoadingMore)
                    }
                }
            }.padding(KccSpacing.s4)
        }
        .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
        .navigationTitle(Text("openTickets.title"))
        .navigationBarTitleDisplayMode(.inline)
        .task { coordinator.start() }
        .onDisappear { coordinator.stop() }
    }
}

struct TicketCard: View {
    let ticket: OpenTicket
    let state: TicketInteractionState
    let onPlusOne: () -> Void
    let onComment: (String) -> Void
    let onCommentEdited: () -> Void
    let onOpen: () -> Void

    @State private var comment = ""

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text(verbatim: ticket.title).font(.headline).fixedSize(horizontal: false, vertical: true)
            if !ticket.summary.isEmpty, ticket.summary != ticket.title {
                Text(verbatim: ticket.summary).font(.subheadline).foregroundStyle(KccTheme.secondary).fixedSize(horizontal: false, vertical: true)
            }
            Text("\(count("openTickets.plusOneCount", ticket.plusOneCount))  •  \(count("openTickets.commentCount", ticket.commentCount))")
                .font(.footnote)
                .foregroundStyle(KccTheme.secondary)

            SocialFlow {
                Button {
                    onPlusOne()
                } label: {
                    EditorSubmitLabel(title: state.plusOneDone ? "openTickets.plusOneDone" : "openTickets.plusOne", saving: state.submitting == .plusOne)
                }
                .buttonStyle(.bordered)
                .disabled(!state.canPlusOne)
                .accessibilityLabel(Text(state.plusOneDone
                    ? LocalizedStringKey("openTickets.plusOneDone")
                    : LocalizedStringKey("openTickets.plusOneDescription")))

                Button(action: onOpen) { Text("openTickets.openInGitHub").frame(minHeight: 48) }
                    .accessibilityHint(Text("openTickets.openInGitHubDescription"))
            }

            if state.plusOneDeliveryFailed {
                Text("openTickets.deliveryFailed")
                    .font(.footnote)
                    .foregroundStyle(KccTheme.error)
            }

            if state.commentDone {
                Text("openTickets.commentDone").font(.subheadline).foregroundStyle(.green)
            } else if state.commentDeliveryFailed {
                Text("openTickets.deliveryFailed")
                    .font(.subheadline)
                    .foregroundStyle(KccTheme.error)
            } else {
                Text("openTickets.publicNotice").font(.footnote).foregroundStyle(.red)
                EditorTextField(title: "openTickets.commentLabel", text: $comment, multiline: true)
                    .textFieldStyle(.roundedBorder)
                    .disabled(state.submitting != nil)
                    .onChange(of: comment) { _, value in
                        comment = FeedbackText.prefixByUTF16(
                            value,
                            limit: TicketComments.maximumLength
                        )
                        onCommentEdited()
                    }
                Button { onComment(comment) } label: { Text("openTickets.commentSubmit").frame(minHeight: 48) }
                    .buttonStyle(.bordered)
                    .disabled(!state.canComment || TicketComments.bound(comment).isEmpty)
            }

            if let error = state.error {
                Text(error.messageKey)
                    .font(.footnote)
                    .foregroundStyle(error == .alreadyDone ? Color.secondary : Color.red)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading).kccContentCard()
    }

    private func count(_ key: String.LocalizationValue, _ value: Int) -> String {
        String.localizedStringWithFormat(String(localized: key), value)
    }
}

private extension FeedbackFailure {
    var messageKey: LocalizedStringKey {
        switch self {
        case .rateLimited: "feedback.rateLimited"
        case .signedOut: "feedback.errorSignedOut"
        case .unavailable, .unknown: "feedback.error"
        }
    }
}

private extension TicketInteractionError {
    var messageKey: LocalizedStringKey {
        switch self {
        case .alreadyDone: "openTickets.alreadyDone"
        case .rateLimited: "openTickets.rateLimited"
        case .emptyComment: "openTickets.emptyComment"
        case .unknown: "openTickets.genericError"
        }
    }
}

private extension FeedbackClientContext {
    @MainActor
    static var current: FeedbackClientContext {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        return .sanitized(
            appVersion: version,
            osVersion: "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion)",
            deviceModel: UIDevice.current.model
        )
    }
}
