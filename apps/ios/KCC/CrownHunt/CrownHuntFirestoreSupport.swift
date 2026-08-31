import FirebaseFirestore
import FirebaseFunctions
import Foundation

/// Firebase instance factories for the Crown-Hunt repositories, with the same
/// emulator seams the rest of the app honors (``FirebaseEmulatorHost`` on the
/// `FIREBASE_*_EMULATOR_HOST` variables, matching firebase.json's ports). Every
/// Crown-Hunt callable is deployed to `europe-west1` (grouped exports like
/// `crownHunt-buyPerk`), so the region is pinned here once — the same region
/// ``KccFunctionsClient`` uses.
enum CrownHuntFirebase {
    static let region = "europe-west1"

    /// A Firestore instance, pointed at the emulator when
    /// `FIREBASE_FIRESTORE_EMULATOR_HOST` is set.
    static func firestore() -> Firestore {
        let firestore = Firestore.firestore()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
        ) {
            firestore.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        return firestore
    }

    /// A Functions client for `europe-west1`, pointed at the emulator when
    /// `FIREBASE_FUNCTIONS_EMULATOR_HOST` is set.
    static func functions() -> Functions {
        let functions = Functions.functions(region: region)
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FUNCTIONS_EMULATOR_HOST"]
        ) {
            functions.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        return functions
    }
}

/// Firestore glue shared by the Crown-Hunt Firebase repositories: the PII-safe
/// status-name mapper and the listener-teardown box.
///
/// The status-name mapper mirrors `FirebaseEventsRepository.firestoreStatusName`
/// (and Android's `firestoreCode()`): a bare status name is the whole
/// diagnosis, carried so callers can tell a STRUCTURAL fault (an undeployed
/// rule, a missing index) apart from "this phone has no signal" — never the
/// exception text, which can embed the failing query and the project id. Kept
/// local to this feature so the Crown-Hunt slice stays self-contained rather
/// than reaching into the events feature for the same helper.
enum CrownHuntFirestore {
    /// The bare Firestore status name (`FAILED_PRECONDITION`,
    /// `PERMISSION_DENIED`, `UNAVAILABLE`, …) for an error, or nil when the
    /// failure carries no Firestore code.
    static func statusName(_ error: Error) -> String? {
        let nsError = error as NSError
        guard nsError.domain == FirestoreErrorDomain,
            let code = FirestoreErrorCode.Code(rawValue: nsError.code)
        else { return nil }
        switch code {
        case .OK: return "OK"
        case .cancelled: return "CANCELLED"
        case .unknown: return "UNKNOWN"
        case .invalidArgument: return "INVALID_ARGUMENT"
        case .deadlineExceeded: return "DEADLINE_EXCEEDED"
        case .notFound: return "NOT_FOUND"
        case .alreadyExists: return "ALREADY_EXISTS"
        case .permissionDenied: return "PERMISSION_DENIED"
        case .resourceExhausted: return "RESOURCE_EXHAUSTED"
        case .failedPrecondition: return "FAILED_PRECONDITION"
        case .aborted: return "ABORTED"
        case .outOfRange: return "OUT_OF_RANGE"
        case .unimplemented: return "UNIMPLEMENTED"
        case .internal: return "INTERNAL"
        case .unavailable: return "UNAVAILABLE"
        case .dataLoss: return "DATA_LOSS"
        case .unauthenticated: return "UNAUTHENTICATED"
        @unknown default: return nil
        }
    }
}

/// `ListenerRegistration` is not Sendable, but a stream's `onTermination`
/// closure must be — all it does is remove the listener, which Firestore
/// documents as thread-safe, so the wrapper is sound. Mirrors the events
/// feature's `ListenerBox`.
struct CrownHuntListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}
