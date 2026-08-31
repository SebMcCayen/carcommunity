import FirebaseCore
import FirebaseFirestore
import FirebaseFunctions
import Foundation

/// ``PerkShopRepository`` backed by Firestore listeners on the shop's display
/// catalog + the member's owned inventory, plus the `crownHunt-buyPerk`
/// callable — the iOS port of Android's `FirebasePerkShopRepository` (buy +
/// view-inventory only).
///
///  - a member-readable document listener on `config/perkCatalog` (rules gate
///    `isActiveMember()`),
///  - an owner-only document listener on `perkInventory/{uid}` (rules gate `get`
///    if owner — a single-doc listener, never a collection query), and
///  - the `crownHunt-buyPerk` callable (europe-west1).
///
/// The buy path talks to the Functions SDK directly (rather than through the
/// shared ``KccFunctionsClient``) because it must read the server's structured
/// `details.reason` discriminator to tell the insufficient-funds / hold-cap /
/// shop-unavailable rejection families apart — the same discrimination Android's
/// repository does off `FirebaseFunctionsException.details`, which the shared
/// client deliberately does not surface. The reason is mapped to a
/// ``PerkBuyFailureReason`` at this seam so the coordinator stays Firebase-free
/// and PII-safe (it branches on a family, never on the SDK message).
///
/// Construction is guarded (``createIfAvailable()`` returns nil without Firebase).
final class FirebasePerkShopRepository: PerkShopRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let functions: Functions

    private init(firestore: Firestore, functions: Functions) {
        self.firestore = firestore
        self.functions = functions
    }

    func catalog() -> AsyncStream<PerkCatalogSnapshot> {
        let document = firestore.collection(Self.config).document(Self.perkCatalog)
        return AsyncStream { continuation in
            // Track the last good load so a transient listener error keeps the
            // shop visible instead of flickering to an error state — only a
            // failure BEFORE the first successful read surfaces as failed.
            let loadedOnce = LoadedFlag()
            let registration = document.addSnapshotListener { snapshot, error in
                if let error {
                    if !loadedOnce.value {
                        continuation.yield(.failed(code: CrownHuntFirestore.statusName(error)))
                    }
                    return
                }
                loadedOnce.value = true
                continuation.yield(.loaded(Self.catalogEntries(from: snapshot)))
            }
            let box = CrownHuntListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func inventory(uid: String) -> AsyncStream<[String: Int]> {
        let document = firestore.collection(Self.inventory).document(uid)
        return AsyncStream { continuation in
            let loadedOnce = LoadedFlag()
            let registration = document.addSnapshotListener { snapshot, error in
                if error != nil {
                    // First-snapshot error: emit empty so the combined shop state
                    // can still render instead of hanging in loading. After a
                    // successful load, keep the last-known counts on a transient
                    // error rather than misrendering "You own: N" as 0.
                    if !loadedOnce.value { continuation.yield([:]) }
                    return
                }
                loadedOnce.value = true
                continuation.yield(Self.inventoryCounts(from: snapshot))
            }
            let box = CrownHuntListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func buyPerk(perkId: String, idempotencyKey: String) async throws -> PerkPurchaseResult {
        let payload: [String: Any] = ["perkId": perkId, "idempotencyKey": idempotencyKey]
        do {
            let response = try await functions.httpsCallable(Self.buyPerkCallable).call(payload)
            guard let result = Self.purchaseResult(from: response.data) else {
                throw PerkPurchaseError(reason: .unknown)
            }
            return result
        } catch let error as PerkPurchaseError {
            throw error
        } catch {
            throw PerkPurchaseError(reason: Self.buyFailure(from: error))
        }
    }

    // MARK: - Error mapping

    /// Maps a `buyPerk` callable failure to its rejection family. A
    /// `failed-precondition` is told apart by the server's structured
    /// `details.reason` (`insufficient_funds` / `hold_cap_reached` / anything
    /// else → unavailable); every other code folds to
    /// ``PerkBuyFailureReason/unknown`` (network, internal, …) — mirroring
    /// Android's `toPerkPurchaseException`.
    static func buyFailure(from error: Error) -> PerkBuyFailureReason {
        let nsError = error as NSError
        guard nsError.domain == FunctionsErrorDomain,
            let code = FunctionsErrorCode(rawValue: nsError.code)
        else { return .unknown }
        switch code {
        case .failedPrecondition:
            let reason = (nsError.userInfo[FunctionsErrorDetailsKey] as? [String: Any])?["reason"]
                as? String
            return PerkPurchaseReason.failure(for: reason)
        default:
            return .unknown
        }
    }

    // MARK: - Mapping

    /// Parses the `config/perkCatalog` mirror's `perks` array into display
    /// entries. A row missing an id/name/cost, or with an unrecognised kind or a
    /// negative cost, is SKIPPED rather than defaulted — schema drift stays
    /// visible instead of showing a perk at a wrong price/family.
    static func catalogEntries(from snapshot: DocumentSnapshot?) -> [PerkCatalogEntry] {
        guard let snapshot, snapshot.exists,
            let rawPerks = snapshot.get("perks") as? [[String: Any]]
        else { return [] }
        return rawPerks.compactMap(catalogEntry(from:))
    }

    static func catalogEntry(from map: [String: Any]) -> PerkCatalogEntry? {
        guard let perkId = (map["perkId"] as? String), !perkId.isEmpty,
            let kind = PerkKind.fromWire(map["kind"] as? String),
            let name = (map["name"] as? String), !name.isEmpty,
            let costKp = (map["costKp"] as? NSNumber)?.intValue, costKp >= 0
        else { return nil }
        return PerkCatalogEntry(
            perkId: perkId,
            kind: kind,
            name: name,
            iconKey: map["iconKey"] as? String ?? "",
            costKp: costKp,
            blurb: map["blurb"] as? String ?? "",
            // nameEn arrives with catalog doc version >= 2; empty on an older mirror.
            nameEn: map["nameEn"] as? String ?? ""
        )
    }

    /// Reads the `{ perkId: count }` counts out of a `perkInventory/{uid}`
    /// document, dropping the housekeeping `updatedAt` field and any
    /// non-numeric/negative value. An absent document yields an empty map.
    static func inventoryCounts(from snapshot: DocumentSnapshot?) -> [String: Int] {
        guard let snapshot, snapshot.exists, let data = snapshot.data() else { return [:] }
        var counts: [String: Int] = [:]
        for (key, value) in data {
            if key == "updatedAt" { continue }
            guard let count = (value as? NSNumber)?.intValue, count >= 0 else { continue }
            counts[key] = count
        }
        return counts
    }

    /// Parses the `crownHunt-buyPerk` callable result. A response missing the
    /// authoritative post-purchase totals is treated as unrecognized.
    static func purchaseResult(from data: Any?) -> PerkPurchaseResult? {
        guard let map = data as? [String: Any],
            let perkId = map["perkId"] as? String,
            let newBalance = (map["newBalance"] as? NSNumber)?.intValue,
            let inventoryCount = (map["inventoryCount"] as? NSNumber)?.intValue
        else { return nil }
        return PerkPurchaseResult(
            perkId: perkId,
            newBalance: newBalance,
            inventoryCount: inventoryCount,
            alreadyPurchased: (map["alreadyPurchased"] as? Bool) ?? false
        )
    }

    // MARK: - Factory

    private static let config = "config"
    private static let perkCatalog = "perkCatalog"
    private static let inventory = "perkInventory"
    private static let buyPerkCallable = "crownHunt-buyPerk"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebasePerkShopRepository?

    /// A shop repository, or nil in a config-less/CI build (no Firebase) — the
    /// shop then shows its unavailable affordance. Honors the firestore +
    /// functions emulator seams.
    static func createIfAvailable() -> PerkShopRepository? {
        guard FirebaseApp.app() != nil else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let repository = FirebasePerkShopRepository(
            firestore: CrownHuntFirebase.firestore(),
            functions: CrownHuntFirebase.functions()
        )
        cached = repository
        return repository
    }
}

/// A tiny reference box so a listener closure can flip "have we loaded once"
/// without capturing a `var` (the closure is `@escaping` and re-entrant).
/// Confined to the listener callback thread, so no extra synchronization is
/// needed.
private final class LoadedFlag: @unchecked Sendable {
    var value = false
}
