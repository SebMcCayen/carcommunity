import CryptoKit
import Foundation
import Observation

struct ClubChatMembership: Equatable, Sendable {
    let clubID: String
    let role: String
    let joinedAt: Date
    var canModerate: Bool { role == "owner" || role == "moderator" }
}

struct ClubChatSnapshot: Equatable, Sendable {
    let membership: ClubChatMembership
    let messages: [ChannelMessage]
    let hasOlder: Bool
}

/// Full 25-ID batches remain attached. Only a partially filled tail changes
/// as messages arrive, so retained history never creates a listener per row.
struct ClubChatHistoryBatches {
    private(set) var batches: [[String]] = []
    private var seen: Set<String> = []
    mutating func append(_ ids: [String]) -> [Int] {
        var changed: Set<Int> = []
        for id in ids where seen.insert(id).inserted {
            if batches.last?.count == 25 || batches.isEmpty { batches.append([]) }
            let index = batches.count - 1
            batches[index].append(id); changed.insert(index)
        }
        return changed.sorted()
    }
}

@MainActor
protocol ClubChatRepository: AnyObject {
    func updates() -> AsyncStream<ClubChatSnapshot?>
    func loadOlder(membership: ClubChatMembership) async throws
    func send(membership: ClubChatMembership, text: String, operationID: String) async throws
    func manage(membership: ClubChatMembership, action: String, messageID: String, reason: String?) async throws
    func block(authorID: String) async throws
}

/// Private state lives only while the visible, foreground room has fresh
/// server proof. An access loss also invalidates in-flight operations and drafts.
@MainActor @Observable
final class ClubChatCoordinator {
    private let repository: (any ClubChatRepository)?
    let uid: String
    let cooldown: ChatSendCooldown
    private(set) var snapshot: ClubChatSnapshot?
    private(set) var pending: [ChannelMessage] = []
    private(set) var paging = false
    private(set) var failed = false
    private(set) var reported = false
    var draft = ""
    private var generation = 0
    @ObservationIgnored nonisolated(unsafe) private var subscription: Task<Void, Never>?

    init(repository: (any ClubChatRepository)?, uid: String) {
        self.repository = repository; self.uid = uid
        self.cooldown = ChatSendCooldowns.forUser(uid)
    }
    deinit { subscription?.cancel() }
    var messages: [ChannelMessage] {
        ChannelThread.mergeWithPending(older: [], live: snapshot?.messages ?? [], pending: pending)
    }
    func start() {
        guard subscription == nil, let repository else { return }
        let stream = repository.updates()
        subscription = Task { [weak self] in
            for await state in stream {
                guard !Task.isCancelled, let self else { return }
                self.apply(state)
            }
        }
    }
    func stop() { subscription?.cancel(); subscription = nil; apply(nil) }
    func apply(_ state: ClubChatSnapshot?) {
        if snapshot?.membership != state?.membership {
            generation += 1; pending = []; draft = ""; paging = false; failed = false; reported = false
        }
        snapshot = state
        let delivered = Set(state?.messages.filter { $0.senderUid == uid }.map(\.id) ?? [])
        pending.removeAll { delivered.contains($0.id) }
    }
    static func messageID(uid: String, operationID: String) -> String {
        func hash(_ text: String) -> String { SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined() }
        return hash(uid + "_" + hash(operationID))
    }
    func sendDraft() {
        let text = draft
        guard send(text) != nil else { return }
        draft = ""
        // The failed bubble always retains the original text and operation ID.
        // Draft restoration is deliberately unnecessary: retry remains visible.
    }
    @discardableResult func send(_ text: String, retry: ChannelMessage? = nil) -> String? {
        guard let membership = snapshot?.membership, let repository, !cooldown.isBlocked,
              (1...2000).contains(text.trimmingCharacters(in: .whitespacesAndNewlines).unicodeScalars.count)
        else { return nil }
        if let retry, !pending.contains(where: { $0.id == retry.id && $0.deliveryState == .failed }) { return nil }
        let operation = retry?.clientId ?? UUID().uuidString
        let id = Self.messageID(uid: uid, operationID: operation)
        let bubble = ChannelMessage(id: id, senderUid: uid, text: text.trimmingCharacters(in: .whitespacesAndNewlines),
            createdAt: retry?.createdAt ?? Date(), clientId: operation, deliveryState: .sending)
        pending.removeAll { $0.id == id }; pending.append(bubble)
        let epoch = generation
        Task { [weak self] in
            do {
                try await repository.send(membership: membership, text: bubble.text, operationID: operation)
                guard let self, self.generation == epoch else { return }
                self.failed = false
            } catch {
                guard let self, self.generation == epoch else { return }
                if case .rateLimited(let limit) = ChatFirestore.sendError(from: error) { self.cooldown.apply(limit) }
                if let index = self.pending.firstIndex(where: { $0.id == id }) {
                    self.pending[index] = ChannelMessage(id: id, senderUid: self.uid, text: bubble.text,
                        createdAt: bubble.createdAt, clientId: operation, deliveryState: .failed,
                        sendError: ChatFirestore.sendError(from: error))
                }
                self.failed = true
            }
        }
        return id
    }
    func loadOlder() async {
        guard let membership = snapshot?.membership, snapshot?.hasOlder == true, !paging, let repository else { return }
        let epoch = generation; paging = true
        defer { if generation == epoch { paging = false } }
        do { try await repository.loadOlder(membership: membership) }
        catch { if generation == epoch { failed = true } }
    }
    func act(_ action: String, message: ChannelMessage, reason: ChatReportReason? = nil) async {
        guard let membership = snapshot?.membership, let repository,
              snapshot?.messages.contains(where: { $0.id == message.id && $0.senderUid == message.senderUid }) == true,
              action != "removeMessage" || membership.canModerate else { return }
        let epoch = generation
        do {
            if action == "block" { try await repository.block(authorID: message.senderUid) }
            else {
                let wire = reason.map { $0 == .hateOrAbuse ? "hate" : $0 == .unsafeDriving ? "dangerousAdvice" : $0.wire }
                try await repository.manage(membership: membership, action: action, messageID: message.id, reason: wire)
            }
            guard generation == epoch else { return }
            reported = action == "reportMessage"; failed = false
        } catch { if generation == epoch { failed = true } }
    }
}
