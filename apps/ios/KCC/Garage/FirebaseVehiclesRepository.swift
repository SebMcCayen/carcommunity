import FirebaseCore
import FirebaseFirestore
import FirebaseStorage
import Foundation

/// ``VehiclesRepository`` backed by Cloud Firestore + the garage-addVehicle
/// callable + Cloud Storage — the iOS port of Android's
/// `FirebaseGarageRepository.kt`, restricted to the list + add slice.
///
/// The list is an owner equality query (`userId == uid`) on the `vehicles`
/// collection, exactly Android's read path — no composite index needed, and
/// the sort (make, then model, case-insensitively) happens client-side like
/// Android's `sortedWith`. Listener failures surface as
/// ``GarageSnapshot/failed(code:)`` carrying the bare Firestore status name,
/// never as a silently empty garage.
///
/// The add write goes through the garage-addVehicle callable via
/// ``KccFunctionsClient`` (europe-west1) — the same direct-read /
/// callable-write split as Android, because the vehicle cap and the schema
/// validation live server-side and rules deny all client writes. The payload
/// carries catalogue IDS only; the backend derives the stored display text,
/// so this client can never label a `volvo` id "Ferrari".
///
/// Construction is guarded (``createIfAvailable()`` returns nil without
/// Firebase config), mirroring the other repositories and Android's
/// `createIfAvailable`.
final class FirebaseVehiclesRepository: VehiclesRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let functions: KccFunctionsClient
    private let storage: Storage

    private init(firestore: Firestore, functions: KccFunctionsClient, storage: Storage) {
        self.firestore = firestore
        self.functions = functions
        self.storage = storage
    }

    func vehicles(uid: String) -> AsyncStream<GarageSnapshot> {
        let query =
            firestore
            .collection(Self.vehiclesCollection)
            .whereField(Self.userIdField, isEqualTo: uid)
        return AsyncStream { continuation in
            let registration = query.addSnapshotListener { snapshot, error in
                if let error {
                    // Bare status name only (never the exception text, which
                    // embeds the failing query and the project id) — see
                    // GarageSnapshot.failed and FirebaseEventsRepository.
                    continuation.yield(
                        .failed(code: FirebaseEventsRepository.firestoreStatusName(error))
                    )
                    return
                }
                let vehicles = (snapshot?.documents ?? []).compactMap {
                    Vehicle.fromMap(id: $0.documentID, map: $0.data())
                }
                continuation.yield(.loaded(Garage.sortedForList(vehicles)))
            }
            let box = ListenerBox(registration: registration)
            continuation.onTermination = { _ in
                box.registration.remove()
            }
        }
    }

    func addVehicle(_ input: VehicleInput) async throws -> String {
        let result = try await functions.call(Self.addVehicle, payload: input.payload)
        // garage-addVehicle responds { vehicleId }. A response without a
        // usable id is a broken contract, not a soft failure: the photo slice
        // will have no path to key the vehicle's images under, so fail loudly
        // rather than report a success later steps cannot act on (Android's
        // posture in FirebaseGarageRepository.addVehicle).
        guard let map = result as? [String: Any],
            let vehicleId = map["vehicleId"] as? String,
            !vehicleId.isEmpty
        else { throw KccFunctionsError(code: .internalError) }
        return vehicleId
    }

    func imageDownloadURL(for imagePath: String) async -> URL? {
        try? await storage.reference(withPath: imagePath).downloadURL()
    }

    // MARK: - Factory

    private static let vehiclesCollection = "vehicles"
    private static let userIdField = "userId"
    /// Grouped-export spelling of the garage.addVehicle callable
    /// (contracts/functions/functions.json).
    private static let addVehicle = "garage-addVehicle"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseVehiclesRepository?

    /// Returns the process-wide repository when Firebase is configured for
    /// this build, or nil when GoogleService-Info.plist is absent (CI, local
    /// validation builds — see apps/ios/README.md).
    ///
    /// Emulator seams follow the shared `FIREBASE_*_EMULATOR_HOST`
    /// convention: `FIREBASE_FIRESTORE_EMULATOR_HOST` (8080) for the vehicles
    /// listener and `FIREBASE_STORAGE_EMULATOR_HOST` (9199) for photo URL
    /// resolution — ports per firebase.json; ``KccFunctionsClient`` applies
    /// `FIREBASE_FUNCTIONS_EMULATOR_HOST` (5001) itself. Firestore is a
    /// process-wide singleton shared with the other repositories, whose
    /// factories apply the same settings; the host check below makes the
    /// second application a no-op instead of mutating settings twice (same
    /// guard as `FirebaseUserProfileRepository`).
    static func createIfAvailable() -> VehiclesRepository? {
        guard FirebaseApp.app() != nil, let functions = KccFunctionsClient.createIfAvailable()
        else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let firestore = Firestore.firestore()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
        ), firestore.settings.host != "\(emulator.host):\(emulator.port)" {
            firestore.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let storage = Storage.storage()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_STORAGE_EMULATOR_HOST"]
        ) {
            storage.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebaseVehiclesRepository(
            firestore: firestore, functions: functions, storage: storage
        )
        cached = repository
        return repository
    }
}

extension VehicleInput {
    /// The garage-addVehicle wire payload
    /// (contracts/schemas/garage.schema.json `addVehicleRequest`), mirroring
    /// Android's `VehicleInput.toData()`.
    ///
    /// Catalogue IDS only — the backend derives the stored `make`/`model`
    /// display text and REJECTS a request carrying both forms (garage-core
    /// `refineVehicleIdentity`). The nullable free-text fields are always
    /// sent (possibly NSNull) so the payload shape matches Android's and a
    /// future edit path can clear them.
    var payload: [String: Any] {
        [
            "makeId": makeId,
            "modelId": modelId,
            "modelYear": modelYear,
            "powertrain": powertrain.wire,
            "engineDescription": engineDescription ?? NSNull(),
            // "modifications" is stored in the existing free-text
            // `description` field (garage-core), the same reuse as Android.
            "description": modifications ?? NSNull(),
            // Registration plate — DELIBERATELY PUBLIC field, already
            // normalised by VehicleValidation.
            "registrationPlate": registrationPlate ?? NSNull(),
        ]
    }
}

/// `ListenerRegistration` is not Sendable, but the stream's `onTermination`
/// closure must be — all it does is remove the listener, which Firestore
/// documents as thread-safe, so the wrapper is sound (same pattern as
/// `FirebaseEventsRepository`).
private struct ListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}
