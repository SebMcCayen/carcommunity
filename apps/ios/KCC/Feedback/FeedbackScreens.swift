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
            .padding(KccSpacing.s5)
        }
        .navigationTitle(Text("feedback.title"))
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
                Label("feedback.publicNoticeTitle", systemImage: "exclamationmark.triangle.fill")
                    .font(.headline)
                Text("feedback.publicNotice")
                    .font(.subheadline)
            }
            .foregroundStyle(KccPalette.errorRed)
            .padding(KccSpacing.s4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                KccPalette.errorRed.opacity(0.1),
                in: RoundedRectangle(cornerRadius: KccRadius.md)
            )

            TextField("feedback.summaryLabel", text: $form.summary)
                .textFieldStyle(.roundedBorder)
                .onChange(of: form.summary) { _, value in
                    form.summary = FeedbackText.prefixByUTF16(
                        value,
                        limit: FeedbackReports.maximumSummaryLength
                    )
                }

            Text("feedback.descriptionLabel")
                .font(.subheadline.weight(.semibold))
            TextEditor(text: $form.description)
                .accessibilityLabel(Text("feedback.descriptionLabel"))
                .frame(minHeight: 150)
                .padding(KccSpacing.s2)
                .overlay {
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(.secondary.opacity(0.35))
                }
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
                    .foregroundStyle(.secondary)
            }

            Button {
                guard FeedbackReports.validate(form) == nil else {
                    showValidationError = true
                    return
                }
                Task { await coordinator.submit(form: form, context: context) }
            } label: {
                if coordinator.status == .submitting {
                    ProgressView().frame(maxWidth: .infinity)
                } else {
                    Text("feedback.submit").frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(coordinator.status == .submitting || coordinator.status == .unavailable)

            if openTicketsEnabled {
                Button("feedback.openTicketsButton", action: onOpenTickets)
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
            Button("feedback.viewIssue") { openURL(issueURL) }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
        }
        Button("feedback.close") {
            coordinator.reset()
            onClose()
        }
            .buttonStyle(.borderedProminent)
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
        Group {
            switch coordinator.listState {
            case .loading:
                ProgressView("openTickets.loading")
            case .failed:
                ContentUnavailableView(
                    "openTickets.error",
                    systemImage: "exclamationmark.triangle"
                )
            case .unavailable:
                ContentUnavailableView("shell.unavailable", systemImage: "wifi.slash")
            case .loaded(let tickets):
                if tickets.isEmpty {
                    ContentUnavailableView("openTickets.empty", systemImage: "checkmark.circle")
                } else {
                    List {
                        ForEach(tickets) { ticket in
                            TicketCard(
                                ticket: ticket,
                                state: coordinator.interactions[ticket.number] ?? TicketInteractionState(),
                                onPlusOne: {
                                    Task { await coordinator.plusOne(issueNumber: ticket.number) }
                                },
                                onComment: { text in
                                    Task { await coordinator.comment(issueNumber: ticket.number, text: text) }
                                },
                                onCommentEdited: { coordinator.clearError(issueNumber: ticket.number) },
                                onOpen: { openURL(ticket.htmlURL) }
                            )
                        }
                        if coordinator.canLoadMore || coordinator.isLoadingMore {
                            Button("openTickets.loadMore") { coordinator.loadMore() }
                                .disabled(coordinator.isLoadingMore)
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
        }
        .navigationTitle(Text("openTickets.title"))
        .safeAreaInset(edge: .top) {
            Text("openTickets.intro")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .padding(.horizontal, KccSpacing.s4)
                .padding(.vertical, KccSpacing.s2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.regularMaterial)
        }
        .task { coordinator.start() }
        .onDisappear { coordinator.stop() }
    }
}

private struct TicketCard: View {
    let ticket: OpenTicket
    let state: TicketInteractionState
    let onPlusOne: () -> Void
    let onComment: (String) -> Void
    let onCommentEdited: () -> Void
    let onOpen: () -> Void

    @State private var comment = ""

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text(ticket.title).font(.headline).lineLimit(2)
            if !ticket.summary.isEmpty, ticket.summary != ticket.title {
                Text(ticket.summary).font(.subheadline).foregroundStyle(.secondary).lineLimit(3)
            }
            Text("\(count("openTickets.plusOneCount", ticket.plusOneCount))  •  \(count("openTickets.commentCount", ticket.commentCount))")
                .font(.caption)
                .foregroundStyle(.secondary)

            HStack {
                Button {
                    onPlusOne()
                } label: {
                    if state.submitting == .plusOne {
                        ProgressView()
                    } else {
                        Text(state.plusOneDone
                            ? LocalizedStringKey("openTickets.plusOneDone")
                            : LocalizedStringKey("openTickets.plusOne"))
                    }
                }
                .buttonStyle(.bordered)
                .disabled(!state.canPlusOne)
                .accessibilityLabel(Text(state.plusOneDone
                    ? LocalizedStringKey("openTickets.plusOneDone")
                    : LocalizedStringKey("openTickets.plusOneDescription")))

                Button("openTickets.openInGitHub", action: onOpen)
                    .accessibilityHint(Text("openTickets.openInGitHubDescription"))
            }

            if state.plusOneDeliveryFailed {
                Text("openTickets.deliveryFailed")
                    .font(.caption)
                    .foregroundStyle(KccPalette.errorRed)
            }

            if state.commentDone {
                Text("openTickets.commentDone").font(.subheadline).foregroundStyle(.green)
            } else if state.commentDeliveryFailed {
                Text("openTickets.deliveryFailed")
                    .font(.subheadline)
                    .foregroundStyle(KccPalette.errorRed)
            } else {
                Text("openTickets.publicNotice").font(.caption).foregroundStyle(.red)
                TextField("openTickets.commentLabel", text: $comment, axis: .vertical)
                    .lineLimit(2...6)
                    .textFieldStyle(.roundedBorder)
                    .disabled(state.submitting != nil)
                    .onChange(of: comment) { _, value in
                        comment = FeedbackText.prefixByUTF16(
                            value,
                            limit: TicketComments.maximumLength
                        )
                        onCommentEdited()
                    }
                Button("openTickets.commentSubmit") { onComment(comment) }
                    .buttonStyle(.bordered)
                    .disabled(!state.canComment || TicketComments.bound(comment).isEmpty)
            }

            if let error = state.error {
                Text(error.messageKey)
                    .font(.caption)
                    .foregroundStyle(error == .alreadyDone ? Color.secondary : Color.red)
            }
        }
        .padding(.vertical, KccSpacing.s2)
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
    static var current: FeedbackClientContext {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        return .sanitized(
            appVersion: version,
            osVersion: "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion)",
            deviceModel: UIDevice.current.model
        )
    }
}
