import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import Foundation

final class FirebaseGroupDriveRepository: GroupDriveRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let functions: KccFunctionsClient

    private init(firestore: Firestore, functions: KccFunctionsClient) {
        self.firestore = firestore
        self.functions = functions
    }

    func participants(eventId: String) -> AsyncStream<GroupDriveRosterSnapshot> {
        let query = participantCollection(eventId: eventId).limit(to: GroupDrive.rosterLimit)
        return AsyncStream { continuation in
            let registration = query.addSnapshotListener { snapshot, error in
                if let error {
                    continuation.yield(.failed(code: FirebaseEventsRepository.firestoreStatusName(error)))
                    return
                }
                let participants = (snapshot?.documents ?? []).compactMap(Self.participant(from:))
                continuation.yield(.loaded(GroupDrive.activeParticipants(participants)))
            }
            let box = GroupDriveListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func myStatus(eventId: String, uid: String) -> AsyncStream<GroupDriveOwnStatusSnapshot> {
        let reference = participantCollection(eventId: eventId).document(uid)
        return AsyncStream { continuation in
            let registration = reference.addSnapshotListener { snapshot, error in
                if let error {
                    continuation.yield(.failed(code: FirebaseEventsRepository.firestoreStatusName(error)))
                    return
                }
                guard let snapshot else {
                    continuation.yield(.failed(code: nil))
                    return
                }
                let status = snapshot.exists
                    ? GroupDriveStatus(rawValue: snapshot.get(Self.statusField) as? String ?? "")
                    : nil
                continuation.yield(.loaded(status))
            }
            let box = GroupDriveListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func join(eventId: String) async throws {
        _ = try await functions.call(Self.joinCallable, payload: ["eventId": eventId])
    }

    func updateStatus(eventId: String, status: GroupDriveStatus) async throws {
        guard GroupDriveStatus.updatable.contains(status) else {
            throw KccFunctionsError(code: .invalidArgument)
        }
        _ = try await functions.call(
            Self.updateStatusCallable,
            payload: ["eventId": eventId, "status": status.rawValue]
        )
    }

    func leave(eventId: String) async throws {
        _ = try await functions.call(Self.leaveCallable, payload: ["eventId": eventId])
    }

    private func participantCollection(eventId: String) -> CollectionReference {
        firestore.collection(Self.eventsCollection).document(eventId)
            .collection(Self.participantsCollection)
    }

    private static func participant(from document: QueryDocumentSnapshot) -> GroupDriveParticipant? {
        guard let status = GroupDriveStatus(rawValue: document.get(statusField) as? String ?? "")
        else { return nil }
        return GroupDriveParticipant(
            uid: document.documentID,
            displayName: document.get(displayNameField) as? String,
            status: status
        )
    }

    @MainActor
    static func createIfAvailable() -> GroupDriveRepository? {
        guard FirebaseApp.app() != nil,
              Auth.auth().currentUser != nil,
              let functions = KccFunctionsClient.createIfAvailable() else { return nil }
        // The events repository is the feature composition root and configures
        // the shared Firestore instance (including emulator settings) before
        // constructing this companion repository.
        return FirebaseGroupDriveRepository(firestore: .firestore(), functions: functions)
    }

    private static let eventsCollection = "events"
    private static let participantsCollection = "groupDriveParticipants"
    private static let statusField = "status"
    private static let displayNameField = "displayName"
    private static let joinCallable = "groupDrive-join"
    private static let updateStatusCallable = "groupDrive-updateStatus"
    private static let leaveCallable = "groupDrive-leave"
}

private final class GroupDriveListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
    init(registration: ListenerRegistration) { self.registration = registration }
}
