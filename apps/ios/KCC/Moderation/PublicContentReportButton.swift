import FirebaseAuth
import SwiftUI

struct PublicContentTarget: Sendable {
    let surface: String
    let itemID: String
    var threadID: String? = nil
}

protocol PublicContentReporting: Sendable {
    func report(_ target: PublicContentTarget, reason: ChatReportReason) async throws
}

final class FirebasePublicContentReporter: PublicContentReporting, @unchecked Sendable {
    private let client: KccFunctionsClient
    private let uid: String
    private init(client: KccFunctionsClient, uid: String) { self.client = client; self.uid = uid }
    func report(_ target: PublicContentTarget, reason: ChatReportReason) async throws {
        try Task.checkCancellation()
        guard Auth.auth().currentUser?.uid == uid else { throw CancellationError() }
        var payload: [String: Any] = ["surface": target.surface, "expectedUid": uid, "reason": reason.rawValue]
        if target.surface == "billboard" { payload["billboardId"] = target.itemID }
        else {
            payload["threadId"] = target.threadID ?? target.itemID
            if target.surface == "driver_comment" { payload["commentId"] = target.itemID }
        }
        guard let result = try await client.call("moderation-reportPublicContent", payload: payload) as? [String: Any],
              result["reported"] as? Bool == true else { throw KccFunctionsError(code: .unknown) }
        try Task.checkCancellation()
        guard Auth.auth().currentUser?.uid == uid else { throw CancellationError() }
    }
    static func createIfAvailable() -> (any PublicContentReporting)? {
        guard let client = KccFunctionsClient.createIfAvailable(), let uid = Auth.auth().currentUser?.uid else { return nil }
        return FirebasePublicContentReporter(client: client, uid: uid)
    }
}

/// Chosen content goes only to the private moderator queue. No client-authored
/// snapshot, message history, public GitHub issue or advertisement impression.
struct PublicContentReportButton: View {
    let target: PublicContentTarget
    var reporter: (any PublicContentReporting)? = FirebasePublicContentReporter.createIfAvailable()
    @State private var choosing = false
    @State private var busy = false
    @State private var status: LocalizedStringKey?
    @State private var operation: Task<Void, Never>?

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Button { choosing = true } label: {
                Label(LocalizedStringKey(target.surface == "billboard" ? "contentSafety.reportAd" : "contentSafety.report"), systemImage: "flag")
                    .frame(minHeight: 48)
            }.buttonStyle(.bordered).disabled(busy || reporter == nil)
            if let status { Text(status).font(.footnote).fixedSize(horizontal: false, vertical: true) }
        }
        .confirmationDialog("chat.reportReasonPrompt", isPresented: $choosing, titleVisibility: .visible) {
            ForEach(ChatReportReason.allCases, id: \.self) { reason in
                Button(LocalizedStringKey("chat.reportReason." + reason.rawValue)) {
                    guard !busy, let reporter else { return }
                    busy = true; status = nil
                    operation = Task { @MainActor in
                        defer { busy = false }
                        do {
                            try await reporter.report(target, reason: reason)
                            try Task.checkCancellation()
                            status = "contentSafety.success"
                        } catch is CancellationError { }
                        catch { if !Task.isCancelled { status = "chat.reportError" } }
                    }
                }
            }
            Button("chat.replyCancel", role: .cancel) { }
        }
        .onDisappear { operation?.cancel() }
    }
}

struct DriverContentSafetyActions: View {
    let viewerUID: String?
    let authorUID: String
    let target: PublicContentTarget
    var body: some View {
        if let viewerUID, authorUID != viewerUID {
            SocialFlow {
                PublicContentReportButton(target: target)
                NavigationLink {
                    MemberProfileScreen(targetUid: authorUID, viewerUid: viewerUID,
                        friends: FirebaseFriendsRepository.createIfAvailable())
                } label: {
                    Label("contentSafety.profileAndBlock", systemImage: "person.crop.circle.badge.xmark")
                        .frame(minHeight: 48)
                }.buttonStyle(.bordered)
            }
        }
    }
}
