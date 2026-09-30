import FirebaseCore
import FirebaseFirestore
import Foundation

final class FirebasePartnersRepository: PartnersRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let functions: KccFunctionsClient

    private init(firestore: Firestore, functions: KccFunctionsClient) {
        self.firestore = firestore
        self.functions = functions
    }

    func observeActiveCompanies() -> AsyncStream<PartnersCollectionSnapshot> {
        AsyncStream { continuation in
            let registration = firestore.collection("companies")
                .whereField("status", isEqualTo: "active")
                .order(by: "createdAt", descending: true)
                .limit(to: 150)
                .addSnapshotListener { snapshot, error in
                    if let error {
                        continuation.yield(.failed(code: Self.errorCode(error)))
                        return
                    }
                    let companies = snapshot?.documents.compactMap {
                        PartnerCompany.fromMap(id: $0.documentID, map: $0.data())
                    }.sorted {
                        $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
                    } ?? []
                    continuation.yield(.loaded(companies: companies))
                }
            let box = PartnerListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func observeActiveOffers() -> AsyncStream<PartnerOffersSnapshot> {
        AsyncStream { continuation in
            let registration = firestore.collection("offers")
                .whereField("status", isEqualTo: "active")
                .order(by: "createdAt", descending: true)
                .limit(to: 200)
                .addSnapshotListener { snapshot, error in
                    if let error {
                        continuation.yield(.failed(code: Self.errorCode(error)))
                        return
                    }
                    continuation.yield(.loaded(offers: snapshot?.documents.compactMap {
                        PartnerOffer.fromMap(id: $0.documentID, map: $0.data())
                    } ?? []))
                }
            let box = PartnerListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func observeOfferDetail(offerId: String) -> AsyncStream<PartnerOfferDetailSnapshot> {
        AsyncStream { continuation in
            let registration = firestore.collection("offers").document(offerId)
                .collection("details").document("member")
                .addSnapshotListener { snapshot, error in
                    if let error {
                        continuation.yield(.failed(code: Self.errorCode(error)))
                        return
                    }
                    guard let snapshot, snapshot.exists else {
                        continuation.yield(.loaded(nil))
                        return
                    }
                    continuation.yield(.loaded(PartnerOfferDetail.fromMap(snapshot.data() ?? [:])))
                }
            let box = PartnerListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func observeSavedOfferIds(uid: String) -> AsyncStream<SavedOffersSnapshot> {
        AsyncStream { continuation in
            let registration = firestore.collection("users").document(uid)
                .collection("savedOffers")
                .addSnapshotListener { snapshot, error in
                    if let error {
                        continuation.yield(.failed(code: Self.errorCode(error)))
                        return
                    }
                    continuation.yield(.loaded(ids: Set(snapshot?.documents.map(\.documentID) ?? [])))
                }
            let box = PartnerListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func showOfferCode(offerId: String) async throws -> String? {
        let result = try await functions.call(
            "partners-showOfferCode",
            payload: ["offerId": offerId]
        )
        guard let map = result as? [String: Any] else { return nil }
        return (map["code"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
    }

    func setSaved(uid: String, offerId: String, saved: Bool) async throws {
        let reference = firestore.collection("users").document(uid)
            .collection("savedOffers").document(offerId)
        if saved {
            try await reference.setData([
                "offerId": offerId,
                "savedAt": FieldValue.serverTimestamp(),
            ])
        } else {
            try await reference.delete()
        }
    }

    private static func errorCode(_ error: Error) -> String? {
        (error as NSError).userInfo["FIRFirestoreErrorDomain"] as? String
            ?? String((error as NSError).code)
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var cached: FirebasePartnersRepository?

    static func createIfAvailable() -> PartnersRepository? {
        guard FirebaseApp.app() != nil,
              let functions = KccFunctionsClient.createIfAvailable()
        else { return nil }
        lock.lock()
        defer { lock.unlock() }
        if let cached { return cached }
        let firestore = Firestore.firestore()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
        ), firestore.settings.host != "\(emulator.host):\(emulator.port)" {
            firestore.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebasePartnersRepository(firestore: firestore, functions: functions)
        cached = repository
        return repository
    }
}

private struct PartnerListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
