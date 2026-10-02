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
                .order(by: FieldPath.documentID(), descending: true)
                .limit(to: Self.activeCompaniesPageSize + 1)
                .addSnapshotListener { snapshot, error in
                    if let error {
                        continuation.yield(.failed(code: Self.errorCode(error)))
                        return
                    }
                    let page = Self.companyPage(from: snapshot?.documents ?? [])
                    continuation.yield(.loaded(
                        companies: page.companies,
                        nextCursor: page.nextCursor
                    ))
                }
            let box = PartnerListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func fetchActiveCompanies(after cursor: PartnerPageCursor) async throws -> PartnerCompaniesPage {
        let snapshot = try await firestore.collection("companies")
            .whereField("status", isEqualTo: "active")
            .order(by: "createdAt", descending: true)
            .order(by: FieldPath.documentID(), descending: true)
            .start(after: [Timestamp(date: cursor.createdAt), cursor.documentId])
            .limit(to: Self.activeCompaniesPageSize + 1)
            .getDocuments()
        return Self.companyPage(from: snapshot.documents)
    }

    func observeCompany(id: String) -> AsyncStream<PartnerCompanySnapshot> {
        AsyncStream { continuation in
            let registration = firestore.collection("companies")
                .whereField(FieldPath.documentID(), isEqualTo: id)
                .whereField("status", isEqualTo: "active")
                .limit(to: 1)
                .addSnapshotListener { snapshot, error in
                    if let error {
                        continuation.yield(.failed(code: Self.errorCode(error)))
                        return
                    }
                    guard let document = snapshot?.documents.first else {
                        continuation.yield(.loaded(nil))
                        return
                    }
                    continuation.yield(.loaded(PartnerCompany.fromMap(
                        id: document.documentID,
                        map: document.data()
                    )))
                }
            let box = PartnerListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func observeActiveOffers() -> AsyncStream<PartnerActiveOffersSnapshot> {
        AsyncStream { continuation in
            let registration = firestore.collection("offers")
                .whereField("status", isEqualTo: "active")
                .order(by: "createdAt", descending: true)
                .order(by: FieldPath.documentID(), descending: true)
                .limit(to: Self.activeOffersPageSize + 1)
                .addSnapshotListener { snapshot, error in
                    if let error {
                        continuation.yield(.failed(code: Self.errorCode(error)))
                        return
                    }
                    let page = Self.offerPage(from: snapshot?.documents ?? [])
                    continuation.yield(.loaded(
                        offers: page.offers,
                        nextCursor: page.nextCursor
                    ))
                }
            let box = PartnerListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func fetchActiveOffers(after cursor: PartnerPageCursor) async throws -> PartnerOffersPage {
        let snapshot = try await firestore.collection("offers")
            .whereField("status", isEqualTo: "active")
            .order(by: "createdAt", descending: true)
            .order(by: FieldPath.documentID(), descending: true)
            .start(after: [Timestamp(date: cursor.createdAt), cursor.documentId])
            .limit(to: Self.activeOffersPageSize + 1)
            .getDocuments()
        return Self.offerPage(from: snapshot.documents)
    }

    func observeOffers(ids: Set<String>) -> AsyncStream<PartnerOffersSnapshot> {
        AsyncStream { continuation in
            guard !ids.isEmpty else {
                continuation.yield(.loaded(offers: []))
                continuation.finish()
                return
            }

            let values = Array(ids.prefix(Self.savedOffersLimit))
            let chunks = stride(from: 0, to: values.count, by: Self.firestoreInLimit).map {
                Array(values[$0..<min($0 + Self.firestoreInLimit, values.count)])
            }
            let aggregate = PartnerOffersAggregate(count: chunks.count)
            let registrations = chunks.enumerated().map { index, chunk in
                firestore.collection("offers")
                    .whereField("status", isEqualTo: "active")
                    .whereField(FieldPath.documentID(), in: chunk)
                    .addSnapshotListener { snapshot, error in
                        if let error {
                            if aggregate.shouldReportFailure() {
                                continuation.yield(.failed(code: Self.errorCode(error)))
                            }
                            return
                        }
                        let offers: [PartnerOffer] = snapshot?.documents.compactMap { document -> PartnerOffer? in
                            guard document.data()["status"] as? String == "active" else { return nil }
                            return PartnerOffer.fromMap(id: document.documentID, map: document.data())
                        } ?? []
                        if let offers = aggregate.receive(index: index, offers: offers) {
                            continuation.yield(.loaded(offers: offers))
                        }
                    }
            }
            let box = PartnerListenerCollectionBox(registrations: registrations)
            continuation.onTermination = { _ in box.registrations.forEach { $0.remove() } }
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
                .order(by: "savedAt", descending: true)
                .limit(to: Self.savedOffersLimit + 1)
                .addSnapshotListener { snapshot, error in
                    if let error {
                        continuation.yield(.failed(code: Self.errorCode(error)))
                        return
                    }
                    let documents = snapshot?.documents ?? []
                    continuation.yield(.loaded(
                        ids: Set(documents.prefix(Self.savedOffersLimit).map(\.documentID)),
                        isExhaustive: documents.count <= Self.savedOffersLimit
                    ))
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

    private static func companyPage(
        from documents: [QueryDocumentSnapshot]
    ) -> PartnerCompaniesPage {
        let visible = Array(documents.prefix(activeCompaniesPageSize))
        let companies = visible.compactMap {
            PartnerCompany.fromMap(id: $0.documentID, map: $0.data())
        }.sorted {
            $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
        return PartnerCompaniesPage(
            companies: companies,
            nextCursor: documents.count > activeCompaniesPageSize
                ? visible.last.flatMap(pageCursor) : nil
        )
    }

    private static func offerPage(from documents: [QueryDocumentSnapshot]) -> PartnerOffersPage {
        let visible = Array(documents.prefix(activeOffersPageSize))
        return PartnerOffersPage(
            offers: visible.compactMap {
                PartnerOffer.fromMap(id: $0.documentID, map: $0.data())
            },
            nextCursor: documents.count > activeOffersPageSize
                ? visible.last.flatMap(pageCursor) : nil
        )
    }

    private static func pageCursor(_ document: QueryDocumentSnapshot) -> PartnerPageCursor? {
        guard let timestamp = document.data()["createdAt"] as? Timestamp else { return nil }
        return PartnerPageCursor(createdAt: timestamp.dateValue(), documentId: document.documentID)
    }

    private static let firestoreInLimit = 30
    private static let savedOffersLimit = 30
    private static let activeCompaniesPageSize = 150
    private static let activeOffersPageSize = 200

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

private struct PartnerListenerCollectionBox: @unchecked Sendable {
    let registrations: [ListenerRegistration]
}

final class PartnerOffersAggregate: @unchecked Sendable {
    private let lock = NSLock()
    private let count: Int
    private var snapshots: [Int: [PartnerOffer]] = [:]
    private var reportedFailure = false

    init(count: Int) { self.count = count }

    func shouldReportFailure() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !reportedFailure else { return false }
        reportedFailure = true
        return true
    }

    func receive(index: Int, offers: [PartnerOffer]) -> [PartnerOffer]? {
        lock.lock()
        defer { lock.unlock() }
        snapshots[index] = offers
        guard snapshots.count == count else { return nil }
        reportedFailure = false
        return snapshots.keys.sorted().flatMap { snapshots[$0] ?? [] }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
