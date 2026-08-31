import FirebaseCore
import FirebaseFunctions
import Foundation

/// ``FriendsRepository`` backed by the member-gated friend callables
/// (europe-west1): `friend-list`, `friend-sendRequest`,
/// `friend-respondRequest`, `friend-cancelRequest`, `friend-remove` — the
/// iOS port of Android's `FirebaseFriendsRepository.kt`.
///
/// This slice does NOT go through ``KccFunctionsClient``: the friends
/// contract branches on the `details.reason` discriminator and the ambiguity
/// `details.candidates` list, which that seam (deliberately) does not carry.
/// The SDK error is translated HERE into the pure ``FriendCallableError``
/// (code + reason + candidates — never the message, which can embed
/// payloads/uids) and mapped by ``FriendsErrorMapper``, so the mapping and
/// parsing stay testable off-device and PII-safe.
///
/// Construction is guarded (``createIfAvailable()`` returns nil without
/// Firebase config), mirroring the other repositories.
final class FirebaseFriendsRepository: FriendsRepository, @unchecked Sendable {
    private let functions: Functions

    private init(functions: Functions) {
        self.functions = functions
    }

    func list() async -> FriendsResult {
        switch await callForData(Self.list, payload: [:]) {
        case .success(let data):
            return .loaded(FriendsResponseParser.parseList(data))
        case .failure(let error):
            return .failed(FriendsErrorMapper.mapList(error))
        }
    }

    func sendRequest(nickname: String) async -> SendRequestResult {
        await sendRequest(payload: ["nickname": nickname])
    }

    func sendRequest(toUid: String) async -> SendRequestResult {
        await sendRequest(payload: ["toUid": toUid])
    }

    private func sendRequest(payload: [String: Any]) async -> SendRequestResult {
        switch await callForData(Self.sendRequest, payload: payload) {
        case .success(let data):
            return FriendsResponseParser.parseSendSuccess(data)
        case .failure(let error):
            return FriendsErrorMapper.mapSend(error)
        }
    }

    func respond(requestId: String, accept: Bool) async -> RespondResult {
        let payload: [String: Any] = [
            "requestId": requestId,
            "action": accept ? "accept" : "decline",
        ]
        switch await callForData(Self.respondRequest, payload: payload) {
        case .success(let data):
            return FriendsResponseParser.parseRespondSuccess(data)
        case .failure(let error):
            return .failed(FriendsErrorMapper.mapRespond(error))
        }
    }

    func cancelRequest(toUid: String) async -> CancelResult {
        switch await callForData(Self.cancelRequest, payload: ["toUid": toUid]) {
        // The `cancelled` boolean is idempotent bookkeeping — either value
        // leaves the caller with no pending request to this member, which is
        // the whole post-condition. Only a thrown error is a failure.
        case .success:
            return .cancelled
        case .failure(let error):
            // mapGeneric, not mapRespond: this callable has no "that request
            // is gone" outcome to distinguish (it no-ops instead).
            return .failed(FriendsErrorMapper.mapGeneric(error))
        }
    }

    func remove(friendUid: String) async -> RemoveResult {
        switch await callForData(Self.remove, payload: ["friendUid": friendUid]) {
        // The `removed` boolean is idempotent bookkeeping — either value is a
        // success (the friend is gone).
        case .success:
            return .removed
        case .failure(let error):
            return .failed(FriendsErrorMapper.mapGeneric(error))
        }
    }

    /// Invokes the callable and normalizes the outcome: a success MUST carry
    /// a dictionary payload (a 2xx with no map is an unexpected response —
    /// surfaced as a failure rather than letting `friend-list` silently
    /// render empty, matching Android's empty-payload guard).
    private func callForData(
        _ name: String,
        payload: [String: Any]
    ) async -> Result<[String: Any], FriendCallableError> {
        do {
            let result = try await functions.httpsCallable(name).call(payload)
            guard let data = result.data as? [String: Any] else {
                return .failure(
                    FriendCallableError(code: .other, reason: nil, candidates: [])
                )
            }
            return .success(data)
        } catch {
            return .failure(Self.callableError(from: error))
        }
    }

    /// Translates a raw callable failure into the pure, testable error shape:
    /// the canonical code, the optional `details.reason`, and any ambiguity
    /// candidates in `details.candidates`. A throwable outside the Functions
    /// error domain (an App Check token failure, transport glitch) has no
    /// status code and maps to ``FriendErrorCode/other``.
    static func callableError(from error: Error) -> FriendCallableError {
        let nsError = error as NSError
        guard nsError.domain == FunctionsErrorDomain,
            let code = FunctionsErrorCode(rawValue: nsError.code)
        else {
            return FriendCallableError(code: .other, reason: nil, candidates: [])
        }
        let details = nsError.userInfo[FunctionsErrorDetailsKey]
        return FriendCallableError(
            code: friendErrorCode(from: code),
            reason: FriendsResponseParser.reason(of: details),
            candidates: FriendsResponseParser.parseCandidates(details)
        )
    }

    static func friendErrorCode(from code: FunctionsErrorCode) -> FriendErrorCode {
        switch code {
        case .unauthenticated: return .unauthenticated
        case .permissionDenied: return .permissionDenied
        case .invalidArgument: return .invalidArgument
        case .notFound: return .notFound
        case .alreadyExists: return .alreadyExists
        case .failedPrecondition: return .failedPrecondition
        // Transport-level failures the user can act on by retrying: the SDK
        // reports a lost/absent connection as UNAVAILABLE and a server-side
        // timeout as DEADLINE_EXCEEDED (Android folds both to Unavailable).
        case .unavailable, .deadlineExceeded: return .unavailable
        default: return .other
        }
    }

    // MARK: - Factory

    /// Grouped-export callable names (functions/src/friends).
    private static let list = "friend-list"
    private static let sendRequest = "friend-sendRequest"
    private static let respondRequest = "friend-respondRequest"
    private static let cancelRequest = "friend-cancelRequest"
    private static let remove = "friend-remove"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseFriendsRepository?

    /// Returns the process-wide repository when Firebase is configured for
    /// this build, or nil when GoogleService-Info.plist is absent (CI, local
    /// validation builds). Honors the `FIREBASE_FUNCTIONS_EMULATOR_HOST`
    /// seam, like ``KccFunctionsClient``.
    static func createIfAvailable() -> FriendsRepository? {
        guard FirebaseApp.app() != nil else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let functions = Functions.functions(region: KccFunctionsClient.region)
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FUNCTIONS_EMULATOR_HOST"]
        ) {
            functions.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebaseFriendsRepository(functions: functions)
        cached = repository
        return repository
    }
}
