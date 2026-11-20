import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import Foundation

final class FirebaseBillboardsRepository: BillboardsRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let functions: KccFunctionsClient

    private init(firestore: Firestore, functions: KccFunctionsClient) {
        self.firestore = firestore
        self.functions = functions
    }

    func activeBillboards() -> AsyncStream<BillboardsSnapshot> {
        let query = firestore.collection("billboards")
            .whereField("status", isEqualTo: "active")
            .whereField("mapVisible", isEqualTo: true)
            .order(by: "createdAt")
            .limit(to: Billboards.activeQueryLimit)
        return AsyncStream { continuation in
            let registration = query.addSnapshotListener { snapshot, error in
                if let error {
                    continuation.yield(.failed(
                        code: FirebaseEventsRepository.firestoreStatusName(error)
                    ))
                    return
                }
                continuation.yield(.loaded(
                    (snapshot?.documents ?? []).compactMap(Self.billboard(from:))
                ))
            }
            let box = BillboardListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func recordInteraction(billboardId: String, type: BillboardInteractionType) async throws {
        // Viewing an advert or following its action still works without
        // optional analytics. Never send the analytics callable using cached,
        // pending, unreadable, or legacy consent; the backend rechecks as well.
        guard let uid = Auth.auth().currentUser?.uid else { return }
        let snapshot: DocumentSnapshot
        do {
            snapshot = try await firestore.collection("userPrivate").document(uid).getDocument(source: .server)
        } catch { return }
        guard Auth.auth().currentUser?.uid == uid,
              let authoritative = PrivacySettingsSnapshot.authoritative(
                data: snapshot.data(),
                isFromCache: snapshot.metadata.isFromCache,
                hasPendingWrites: snapshot.metadata.hasPendingWrites,
                consentTimestamp: (snapshot.get("anonymousPartnerStatsConsentAt") as? Timestamp)?.dateValue()
              ),
              case .loaded(let choices) = authoritative,
              choices.partnerStatsOptIn else { return }
        _ = try await functions.call(
            "billboards-recordInteraction",
            payload: ["billboardId": billboardId, "interactionType": type.rawValue]
        )
    }

    static func billboard(from document: DocumentSnapshot) -> Billboard? {
        let headline = (document.get("headline") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard document.exists,
              let headline, !headline.isEmpty,
              let latitude = document.get("latitude") as? Double,
              let longitude = document.get("longitude") as? Double,
              latitude.isFinite, longitude.isFinite,
              (-90...90).contains(latitude), (-180...180).contains(longitude)
        else { return nil }
        return Billboard(
            id: document.documentID,
            headline: headline,
            message: nonempty(document.get("message") as? String),
            companyId: nonempty(document.get("partnerCompanyId") as? String),
            latitude: latitude,
            longitude: longitude,
            callToActionType: (document.get("callToActionType") as? String)
                .flatMap(BillboardCallToActionType.init(rawValue:)),
            callToActionValue: nonempty(document.get("callToActionValue") as? String),
            availableFrom: (document.get("availableFrom") as? Timestamp)?.dateValue(),
            availableUntil: (document.get("availableUntil") as? Timestamp)?.dateValue(),
            imagePath: nonempty(document.get("imagePath") as? String)
        )
    }

    private static func nonempty(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseBillboardsRepository?

    static func createIfAvailable() -> BillboardsRepository? {
        guard FirebaseApp.app() != nil,
              let functions = KccFunctionsClient.createIfAvailable() else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let firestore = Firestore.firestore()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
        ) {
            firestore.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebaseBillboardsRepository(
            firestore: firestore,
            functions: functions
        )
        cached = repository
        return repository
    }
}

private struct BillboardListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}
