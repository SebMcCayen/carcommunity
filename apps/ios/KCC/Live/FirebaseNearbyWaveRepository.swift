import FirebaseFirestore
import Foundation

final class FirebaseNearbyWaveRepository: NearbyWaveRepository, @unchecked Sendable {
    private let functions: KccFunctionsClient
    private let firestore: Firestore

    private init(functions: KccFunctionsClient, firestore: Firestore) {
        self.functions = functions
        self.firestore = firestore
    }

    static func createIfAvailable() -> FirebaseNearbyWaveRepository? {
        guard let functions = KccFunctionsClient.createIfAvailable() else { return nil }
        return FirebaseNearbyWaveRepository(functions: functions, firestore: Firestore.firestore())
    }

    func send(clientId: String) async -> NearbyWaveSendResult {
        do {
            _ = try await functions.call("live-sendWave", payload: ["clientId": clientId])
            return .sent
        } catch let error as KccFunctionsError where error.code == .resourceExhausted {
            return .rateLimited(retryAfterMilliseconds: error.retryAfterMilliseconds ?? 0)
        } catch {
            return .failed
        }
    }

    func incoming(uid: String, since: Date) -> AsyncStream<NearbyWaveEvent> {
        let query = firestore.collection("liveWaves").document(uid).collection("waves")
            .whereField("createdAt", isGreaterThan: Timestamp(date: since))
            .order(by: "createdAt")

        return AsyncStream(bufferingPolicy: .bufferingNewest(16)) { continuation in
            let registration = query.addSnapshotListener { snapshot, error in
                if error != nil {
                    continuation.finish()
                    return
                }
                guard let snapshot else { return }
                for change in snapshot.documentChanges where change.type == .added {
                    let document = change.document
                    guard !document.metadata.hasPendingWrites else { continue }
                    let data = document.data()
                    guard let sender = data["senderUid"] as? String, !sender.isEmpty, sender != uid,
                          let createdAt = data["createdAt"] as? Timestamp, createdAt.dateValue() > since,
                          let expireAt = data["expireAt"] as? Timestamp, expireAt.dateValue() > Date()
                    else { continue }

                    let name = (data["senderDisplayName"] as? String)?
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    continuation.yield(NearbyWaveEvent(
                        id: document.documentID, senderName: name?.isEmpty == false ? name : nil
                    ))
                }
            }
            let listener = NearbyWaveListener(registration: registration)
            continuation.onTermination = { _ in listener.registration.remove() }
        }
    }
}

private struct NearbyWaveListener: @unchecked Sendable {
    let registration: ListenerRegistration
}
