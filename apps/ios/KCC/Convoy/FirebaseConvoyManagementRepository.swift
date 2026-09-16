import Foundation
import FirebaseAuth
import FirebaseFirestore

final class FirebaseConvoyManagementRepository: ConvoyManagementRepository, @unchecked Sendable {
    private let client: KccFunctionsClient
    private let firestore: Firestore

    private init(client: KccFunctionsClient, firestore: Firestore) {
        self.client = client
        self.firestore = firestore
    }

    func observeConvoy(convoyId: String) -> AsyncThrowingStream<ConvoyItem?, Error> {
        guard let viewerUid = Auth.auth().currentUser?.uid else {
            return AsyncThrowingStream { $0.finish() }
        }
        let document = firestore.collection("convoys").document(convoyId)
        return AsyncThrowingStream { continuation in
            let registration = document.addSnapshotListener { snapshot, error in
                guard error == nil else {
                    continuation.finish(throwing: ConvoyListenerFailure.stopped)
                    return
                }
                guard let snapshot, snapshot.exists, let data = snapshot.data() else {
                    continuation.yield(nil)
                    return
                }
                continuation.yield(Self.parseDocument(data, id: snapshot.documentID, viewerUid: viewerUid))
            }
            let box = ConvoyListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func list() async -> ConvoyManagementListResult {
        switch await call("convoy-list", payload: [:]) {
        case .success(let data): return .loaded(ConvoyManagementParser.parseList(data))
        case .failure(let error):
            return .failed(ConvoyManagementErrorMapper.mapList(error.code))
        }
    }

    func respond(convoyId: String, action: ConvoyAction) async -> ConvoyRespondResult {
        switch await call(
            "convoy-respond",
            payload: ["convoyId": convoyId, "action": action.rawValue]
        ) {
        case .success(let data): return ConvoyManagementParser.parseRespond(data)
        case .failure(let error):
            return .failed(ConvoyManagementErrorMapper.mapRespond(error.code))
        }
    }

    func lifecycle(
        convoyId: String,
        action: ConvoyLifecycleAction
    ) async -> ConvoyLifecycleResult {
        switch await call(
            "convoy-\(action.rawValue)",
            payload: ["convoyId": convoyId]
        ) {
        case .success(let data): return ConvoyManagementParser.parseLifecycle(data, action: action)
        case .failure(let error):
            return .failed(
                ConvoyManagementErrorMapper.mapLifecycle(error.code, action: action)
            )
        }
    }

    func invite(
        convoyId: String,
        inviteeUids: [String]
    ) async -> ConvoyInviteMutationResult {
        switch await call(
            "convoy-invite",
            payload: ["convoyId": convoyId, "inviteeUids": inviteeUids]
        ) {
        case .success(let data): return ConvoyManagementParser.parseInvite(data)
        case .failure(let error):
            return .failed(ConvoyManagementErrorMapper.mapInvite(error.code))
        }
    }

    private func call(
        _ name: String,
        payload: [String: Any]
    ) async -> Result<[String: Any], KccFunctionsError> {
        do {
            guard let data = try await client.call(name, payload: payload) as? [String: Any] else {
                return .failure(KccFunctionsError(code: .unknown))
            }
            return .success(data)
        } catch let error as KccFunctionsError {
            return .failure(error)
        } catch {
            return .failure(KccFunctionsError(code: .unknown))
        }
    }

    static func createIfAvailable() -> ConvoyManagementRepository? {
        KccFunctionsClient.createIfAvailable().map {
            let firestore = Firestore.firestore()
            if let emulator = FirebaseEmulatorHost.parse(
                ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
            ), firestore.settings.host != "\(emulator.host):\(emulator.port)" {
                firestore.useEmulator(withHost: emulator.host, port: emulator.port)
            }
            return Self(client: $0, firestore: firestore)
        }
    }

    private static func parseDocument(
        _ document: [String: Any],
        id: String,
        viewerUid: String
    ) -> ConvoyItem? {
        let storedMembers = document["members"] as? [String: Any] ?? [:]
        let profiles = document["memberProfiles"] as? [String: Any] ?? [:]
        let members: [[String: Any]] = storedMembers.compactMap { uid, raw in
            guard var member = raw as? [String: Any] else { return nil }
            member["uid"] = uid
            if let profile = profiles[uid] as? [String: Any] {
                member["displayName"] = profile["displayName"]
            }
            return member
        }.sorted { left, right in
            let leftUid = left["uid"] as? String ?? ""
            let rightUid = right["uid"] as? String ?? ""
            let ownerUid = document["ownerUid"] as? String
            if leftUid == ownerUid { return true }
            if rightUid == ownerUid { return false }
            return leftUid < rightUid
        }
        var wire = document
        wire["convoyId"] = id
        wire["members"] = members
        if let viewer = storedMembers[viewerUid] as? [String: Any],
           let role = viewer["role"] as? String,
           let inviteStatus = viewer["inviteStatus"] as? String
        {
            wire["viewer"] = [
                "role": role,
                "inviteStatus": inviteStatus
            ]
        } else {
            wire["viewer"] = NSNull()
        }
        if let createdAt = document["createdAt"] as? Timestamp {
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            wire["createdAt"] = formatter.string(from: createdAt.dateValue())
        }
        return ConvoyManagementParser.parseItem(wire)
    }
}

private struct ConvoyListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}

private enum ConvoyListenerFailure: Error {
    case stopped
}
