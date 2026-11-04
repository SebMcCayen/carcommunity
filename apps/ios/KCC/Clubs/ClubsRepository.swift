import FirebaseAuth
import FirebaseFirestore
import Foundation

/// Server callables own public presentation, membership and private manager invitations.
@MainActor
protocol ClubsRepository: AnyObject {
    func enabledUpdates() -> AsyncStream<Bool>
    func state() async throws -> CommunityClubState
    func directory(after cursor: String?) async throws -> ClubDirectoryPage
    func get(id: String) async throws -> CommunityClub?
    func sentInvitations(clubID: String, after cursor: String?) async throws -> SentClubInvitationsPage
    func manage(action: String, fields: [String: String], operationID: String) async throws
}

@MainActor
final class FirebaseClubsRepository: ClubsRepository {
    let uid: String
    private let client: KccFunctionsClient
    private let firestore: Firestore
    init(uid: String, client: KccFunctionsClient, firestore: Firestore) {
        self.uid = uid; self.client = client; self.firestore = firestore
    }
    static func createIfAvailable(uid: String) -> FirebaseClubsRepository? {
        guard let client = KccFunctionsClient.createIfAvailable(), Auth.auth().currentUser?.uid == uid else { return nil }
        return Self(uid: uid, client: client, firestore: Firestore.firestore())
    }
    func enabledUpdates() -> AsyncStream<Bool> {
        let uid = uid
        return AsyncStream { continuation in
            let listener = firestore.document("config/sharedClubs")
                .addSnapshotListener(includeMetadataChanges: true) { snapshot, error in
                    let data = snapshot?.data()
                    continuation.yield(error == nil && snapshot?.metadata.isFromCache == false
                        && Auth.auth().currentUser?.uid == uid && data?["enabled"] as? Bool == true
                        && data?["activatedAt"] is Timestamp)
                }
            let box = ClubListenerBox(listener)
            continuation.onTermination = { _ in box.listener.remove() }
        }
    }
    private func call(_ suffix: String, fields: [String: String] = [:]) async throws -> [String: Any] {
        guard Auth.auth().currentUser?.uid == uid else { throw CancellationError() }
        let result = try await client.call("driver-\(suffix)", payload: fields)
        try Task.checkCancellation()
        guard Auth.auth().currentUser?.uid == uid, let result = result as? [String: Any] else { throw CancellationError() }
        return result
    }
    func state() async throws -> CommunityClubState { try await CommunityClubState.decode(call("clubsState")) }
    func directory(after cursor: String?) async throws -> ClubDirectoryPage {
        let raw = try await call("clubsList", fields: cursor.map { ["cursor": $0] } ?? [:])
        return ClubDirectoryPage(clubs: (raw["items"] as? [[String: Any]] ?? []).prefix(25).compactMap(CommunityClub.decode),
                                 nextCursor: raw["nextCursor"] as? String)
    }
    func get(id: String) async throws -> CommunityClub? {
        let raw = try await call("clubsGet", fields: ["clubId": id])
        return (raw["club"] as? [String: Any]).flatMap(CommunityClub.decode)
    }
    func sentInvitations(clubID: String, after cursor: String?) async throws -> SentClubInvitationsPage {
        var fields = ["clubId": clubID]
        fields["cursor"] = cursor
        let raw = try await call("clubsInvitations", fields: fields)
        return SentClubInvitationsPage(items: (raw["items"] as? [[String: Any]] ?? []).prefix(25).compactMap(SentClubInvitation.decode),
                                       nextCursor: raw["nextCursor"] as? String)
    }
    func manage(action: String, fields: [String: String], operationID: String) async throws {
        var request = fields
        request["action"] = action; request["operationId"] = operationID
        _ = try await call("clubsManage", fields: request)
    }
}
private final class ClubListenerBox: @unchecked Sendable {
    let listener: ListenerRegistration
    init(_ listener: ListenerRegistration) { self.listener = listener }
}
