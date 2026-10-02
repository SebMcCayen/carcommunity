import Foundation

/// Garage operations — the iOS port of Android's `GarageRepository.kt`,
/// including owner management and photo-gallery operations. Firebase-free protocol so the
/// coordinator and screens are unit-testable with fakes.
///
/// The list is an owner Firestore read (the `vehicles` collection is
/// readable by any authenticated user — firebase/firestore.rules
/// `vehicles/{vehicleId}`), mirroring Android's read path; ALL writes go
/// through the garage.* callables — direct client writes are denied by rules
/// because the per-user cap, the no-VIN schema validation, and the plate
/// normalisation can only be enforced server-side.
protocol VehiclesRepository: AnyObject, Sendable {
    /// The user's vehicles, list-sorted (``Garage/sortedForList(_:)``). Each
    /// call returns a fresh stream backed by its own snapshot listener;
    /// terminating the stream (dropping the iteration) detaches the listener.
    func vehicles(uid: String) -> AsyncStream<GarageSnapshot>

    /// Creates a vehicle via the garage-addVehicle callable and returns its
    /// NEW id, as minted by the backend (which responds with `{ vehicleId }`).
    /// The id is what a later photo slice keys
    /// `vehicleImages/{uid}/{vehicleId}/` under, so it is surfaced from day
    /// one rather than re-plumbed later.
    ///
    /// - Throws: ``KccFunctionsError`` with the contract error code.
    func addVehicle(_ input: VehicleInput) async throws -> String

    func updateVehicle(vehicleId: String, input: VehicleInput) async throws

    func deleteVehicle(vehicleId: String) async throws

    func setMainVehicle(vehicleId: String, isMain: Bool) async throws

    /// Uploads sanitised JPEG bytes beneath the caller's own vehicle prefix,
    /// then records the path through garage-addVehiclePhoto. Implementations
    /// remove the uploaded object after a definitive callable rejection. An
    /// ambiguous transport failure must retain it because the backend may
    /// already have committed the path.
    func addVehiclePhoto(uid: String, vehicleId: String, jpegData: Data) async throws

    func removeVehiclePhoto(vehicleId: String, photoPath: String) async throws

    func reorderVehiclePhotos(vehicleId: String, orderedPaths: [String]) async throws

    /// Resolves a Cloud Storage vehicle-photo path
    /// (vehicleImages/{uid}/{vehicleId}/{imageId}) to a download URL for
    /// rendering — the same lazy path→URL split as the profile avatar.
    /// Returns nil on any failure (offline, object deleted, rules): the card
    /// then keeps its placeholder, because a missing picture is cosmetic,
    /// never an error state.
    func imageDownloadURL(for imagePath: String) async -> URL?
}

/// Decides whether a freshly uploaded image is safe to remove after the
/// callable fails. Transport/unknown/internal failures are ambiguous: the
/// transaction may have committed before the response was lost, so deleting
/// then could leave the vehicle document pointing at a missing object.
enum VehiclePhotoUploadCleanup {
    static func shouldDelete(after code: KccFunctionsErrorCode) -> Bool {
        switch code {
        case .unauthenticated, .permissionDenied, .invalidArgument, .notFound, .alreadyExists,
             .resourceExhausted, .failedPrecondition:
            true
        case .internalError, .unavailable, .unknown:
            false
        }
    }
}

/// Owner-readable subscription state used by Garage presentation. Firebase-
/// free so the coordinator and tier projection stay unit-testable.
protocol SubscriptionStateRepository: AnyObject, Sendable {
    /// Emits nil for a missing, unreadable, or malformed document. Nil is the
    /// fail-closed Community state, never an implicit paid entitlement.
    func subscription(uid: String) -> AsyncStream<StoredSubscription?>
}
