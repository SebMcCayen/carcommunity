import FirebaseAuth
import FirebaseFirestore
import FirebaseFunctions
import Foundation

/// Uses the existing shared-clubs backend. All messages/membership writes stay
/// in callables; a cached document never authorizes entry to the private room.
@MainActor
final class FirebaseClubChatRepository: ClubChatRepository {
    private let uid: String
    private let db: Firestore
    private let functions: Functions
    private var room: ClubChatSubscription?

    private init(uid: String, db: Firestore, functions: Functions) {
        self.uid = uid; self.db = db; self.functions = functions
    }
    static func createIfAvailable(uid: String) -> FirebaseClubChatRepository? {
        guard KccFunctionsClient.createIfAvailable() != nil, Auth.auth().currentUser?.uid == uid else { return nil }
        return Self(uid: uid, db: Firestore.firestore(), functions: Functions.functions(region: "europe-west1"))
    }
    func updates() -> AsyncStream<ClubChatSnapshot?> {
        room?.stop()
        let session = ClubChatSubscription(uid: uid, db: db)
        room = session
        return AsyncStream { continuation in
            session.start(continuation)
            continuation.onTermination = { _ in Task { @MainActor in session.stop() } }
        }
    }
    func loadOlder(membership: ClubChatMembership) async throws {
        guard let room, room.membership == membership else { throw CancellationError() }
        try await room.loadOlder()
    }
    private func require(_ membership: ClubChatMembership) throws {
        guard Auth.auth().currentUser?.uid == uid, room?.membership == membership else { throw CancellationError() }
    }
    func send(membership: ClubChatMembership, text: String, operationID: String) async throws {
        try require(membership)
        _ = try await functions.httpsCallable("driver-clubsSendMessage").call([
            "clubId": membership.clubID, "text": text, "operationId": operationID
        ])
        try require(membership)
    }
    func manage(membership: ClubChatMembership, action: String, messageID: String, reason: String?) async throws {
        try require(membership)
        var data = ["clubId": membership.clubID, "action": action, "messageId": messageID, "operationId": UUID().uuidString]
        data["reason"] = reason
        _ = try await functions.httpsCallable("driver-clubsManage").call(data)
        try require(membership)
    }
    func block(authorID: String) async throws {
        guard Auth.auth().currentUser?.uid == uid else { throw CancellationError() }
        _ = try await functions.httpsCallable("blocking-block").call(["targetUserId": authorID])
    }
}

@MainActor
private final class ClubChatSubscription {
    let uid: String
    let db: Firestore
    private var continuation: AsyncStream<ClubChatSnapshot?>.Continuation?
    private var roots: [ListenerRegistration] = []
    private var access: [ListenerRegistration] = []
    private var messages: [ListenerRegistration] = []
    private var enabled = false
    private var account: [String: Any]?
    private var club: [String: Any]?
    private var member: [String: Any]?
    private var clubID: String?
    private(set) var membership: ClubChatMembership?
    private var generation = 0
    private var clubEpoch = 0
    private var hidden: Set<String>?
    private var live: [QueryDocumentSnapshot]?
    private var history: [Int: [QueryDocumentSnapshot]] = [:]
    private var retainedBatches = ClubChatHistoryBatches()
    private var retainedListeners: [Int: ListenerRegistration] = [:]
    private var retainedRevisions: [Int: Int] = [:]
    private var cursor: (Timestamp, String)?
    private var paginated = false
    private var hasOlder = false

    init(uid: String, db: Firestore) { self.uid = uid; self.db = db }
    func start(_ continuation: AsyncStream<ClubChatSnapshot?>.Continuation) {
        self.continuation = continuation
        continuation.yield(nil)
        roots.append(observe("config/sharedClubs") { [weak self] value in
            guard let self else { return }
            self.enabled = value?["enabled"] as? Bool == true && value?["activatedAt"] is Timestamp
            self.refreshAccess()
        })
        roots.append(observe("driverClubAccounts/\(uid)") { [weak self] value in
            self?.account = value; self?.refreshAccess()
        })
    }
    func stop() {
        roots.forEach { $0.remove() }; roots = []
        account = nil; enabled = false
        resetClub()
        continuation?.finish(); continuation = nil
    }
    private func observe(_ path: String, receive: @escaping @MainActor ([String: Any]?) -> Void) -> ListenerRegistration {
        db.document(path).addSnapshotListener(includeMetadataChanges: true) { [weak self] snapshot, error in
            // Firestore snapshots are SDK Sendable; all private state is owned
            // on the main actor, including cancellation and generation checks.
            Task { @MainActor in
                guard let self, self.continuation != nil else { return }
                let fresh = error == nil && snapshot?.metadata.isFromCache == false
                    && snapshot?.metadata.hasPendingWrites == false && Auth.auth().currentUser?.uid == self.uid
                receive(fresh ? (snapshot?.data() ?? [:]) : nil)
            }
        }
    }
    private func resetRoom() {
        generation += 1
        messages.forEach { $0.remove() }; messages = []
        retainedListeners.values.forEach { $0.remove() }; retainedListeners = [:]
        retainedRevisions = [:]; retainedBatches = ClubChatHistoryBatches()
        membership = nil; live = nil; hidden = nil; history = [:]
        cursor = nil; paginated = false; hasOlder = false
        continuation?.yield(nil)
    }
    private func resetClub() {
        clubEpoch += 1
        resetRoom()
        access.forEach { $0.remove() }; access = []
        clubID = nil; club = nil; member = nil
    }
    private func refreshAccess() {
        let requested = enabled && account?["deletionRevoked"] as? Bool != true ? account?["activeClubId"] as? String : nil
        guard let id = requested, id.range(of: "\\A[A-Za-z0-9_-]{1,128}\\z", options: .regularExpression) != nil else {
            resetClub(); return
        }
        if clubID != id {
            resetClub(); clubID = id
            let accessEpoch = clubEpoch
            access.append(observe("driverClubs/\(id)") { [weak self] value in
                guard self?.clubID == id, self?.clubEpoch == accessEpoch else { return }; self?.club = value; self?.refreshAccess()
            })
            access.append(observe("driverClubs/\(id)/members/\(uid)") { [weak self] value in
                guard self?.clubID == id, self?.clubEpoch == accessEpoch else { return }; self?.member = value; self?.refreshAccess()
            })
        }
        guard club?["schemaVersion"] as? Int == 2, club?["status"] as? String == "active",
              let role = member?["role"] as? String, ["owner", "moderator", "member"].contains(role),
              role == account?["role"] as? String,
              let joined = member?["joinedAt"] as? Timestamp, joined == account?["joinedAt"] as? Timestamp,
              (role == "owner") == (club?["ownerUid"] as? String == uid) else { resetRoom(); return }
        let proof = ClubChatMembership(clubID: id, role: role, joinedAt: joined.dateValue())
        guard membership != proof else { return }
        resetRoom(); membership = proof
        let epoch = generation
        messages.append(observe("blockVisibility/\(uid)") { [weak self] value in
            guard let self, self.generation == epoch else { return }
            self.hidden = value.map { Set($0["hiddenUids"] as? [String] ?? []) }
            self.emit()
        })
        messages.append(query(id).limit(to: 25).addSnapshotListener(includeMetadataChanges: true) { [weak self] snapshot, error in
            Task { @MainActor in
                guard let self, self.generation == epoch else { return }
                self.live = self.fresh(snapshot, error: error) ? snapshot?.documents : nil
                if !self.paginated { self.cursor = self.boundary(self.live); self.hasOlder = self.live?.count == 25 }
                if self.paginated, let rows = self.live { self.retain(rows.map(\.documentID), epoch: epoch) }
                self.emit()
            }
        })
    }
    private func query(_ clubID: String) -> Query {
        db.collection("driverClubs/\(clubID)/messages").order(by: "createdAt", descending: true)
            .order(by: FieldPath.documentID(), descending: true)
    }
    private func fresh(_ snapshot: QuerySnapshot?, error: Error?) -> Bool {
        error == nil && snapshot?.metadata.isFromCache == false && snapshot?.metadata.hasPendingWrites == false
            && Auth.auth().currentUser?.uid == uid
    }
    private func boundary(_ rows: [QueryDocumentSnapshot]?) -> (Timestamp, String)? {
        guard let last = rows?.last, let time = last["createdAt"] as? Timestamp else { return nil }
        return (time, last.documentID)
    }
    func loadOlder() async throws {
        guard let proof = membership, let cursor, hasOlder, let live else { return }
        let epoch = generation
        paginated = true
        retain(live.map(\.documentID), epoch: epoch)
        let page = try await query(proof.clubID).start(after: [cursor.0, cursor.1]).limit(to: 25).getDocuments(source: .server)
        guard generation == epoch, membership == proof, Auth.auth().currentUser?.uid == uid else { throw CancellationError() }
        self.cursor = boundary(page.documents); hasOlder = page.documents.count == 25
        retain(page.documents.map(\.documentID), epoch: epoch)
        emit()
    }
    private func retain(_ ids: [String], epoch: Int) {
        guard let clubID else { return }
        for index in retainedBatches.append(ids) {
          retainedListeners[index]?.remove()
          let revision = (retainedRevisions[index] ?? 0) + 1
          retainedRevisions[index] = revision
          history[index] = []
          retainedListeners[index] = db.collection("driverClubs/\(clubID)/messages")
            .whereField(FieldPath.documentID(), in: retainedBatches.batches[index]).limit(to: 25)
            .addSnapshotListener(includeMetadataChanges: true) { [weak self] snapshot, error in
                Task { @MainActor in
                    guard let self, self.generation == epoch, self.retainedRevisions[index] == revision else { return }
                    self.history[index] = self.fresh(snapshot, error: error) ? snapshot?.documents ?? [] : []
                    self.emit()
                }
            }
        }
    }
    private func emit() {
        guard let membership, let hidden, let live, Auth.auth().currentUser?.uid == uid else {
            continuation?.yield(nil); return
        }
        var seen = Set<String>()
        let rows = (live + history.values.flatMap { $0 }).filter { seen.insert($0.documentID).inserted }
        let decoded = rows.compactMap { row -> ChannelMessage? in
            guard let author = row["authorUid"] as? String, !hidden.contains(author),
                  !["removed", "hidden"].contains(row["status"] as? String ?? ""),
                  let text = row["text"] as? String, !text.isEmpty else { return nil }
            return ChannelMessage(id: row.documentID, senderUid: author, text: text,
                senderDisplayName: row["authorName"] as? String, createdAt: (row["createdAt"] as? Timestamp)?.dateValue(),
                reactionCounts: MessageReactionParser.counts(row["reactionCounts"]))
        }
        continuation?.yield(ClubChatSnapshot(membership: membership,
            messages: ChannelThread.merge(older: [], live: decoded), hasOlder: hasOlder))
    }
}
