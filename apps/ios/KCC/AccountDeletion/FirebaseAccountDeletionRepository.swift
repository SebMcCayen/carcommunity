import FirebaseCore
import Foundation

/// Narrow callable seam used to verify request/response behavior without a
/// configured Firebase app in unit tests.
protocol AccountDeletionFunctionsCalling: Sendable {
    func call(_ name: String, payload: [String: Any]) async throws -> Any?
}

extension KccFunctionsClient: AccountDeletionFunctionsCalling {}

/// Calls `account-deleteAccount` in europe-west1 through the shared Functions
/// client. The backend disables Auth and revokes refresh tokens before it
/// atomically records the pending deletion, then hard-purges after 30 days.
final class FirebaseAccountDeletionRepository: AccountDeletionRepository, @unchecked Sendable {
    static let callable = "account-deleteAccount"
    static let maximumReasonLength = 500

    private let functions: any AccountDeletionFunctionsCalling

    init(functions: any AccountDeletionFunctionsCalling) {
        self.functions = functions
    }

    func deleteAccount(reason: String?) async throws {
        var payload: [String: Any] = [:]
        if let reason = Self.normalizedReason(reason) {
            guard reason.count <= Self.maximumReasonLength else {
                throw KccFunctionsError(code: .invalidArgument)
            }
            payload["reason"] = reason
        }

        let raw = try await functions.call(Self.callable, payload: payload)
        guard let response = raw as? [String: Any],
              response["status"] as? String == "pending",
              let requestId = response["requestId"] as? String,
              !requestId.isEmpty
        else {
            throw KccFunctionsError(code: .unknown)
        }
    }

    static func normalizedReason(_ reason: String?) -> String? {
        guard let value = reason?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty
        else { return nil }
        return value
    }

    static func createIfAvailable() -> AccountDeletionRepository? {
        guard FirebaseApp.app() != nil,
              let functions = KccFunctionsClient.createIfAvailable()
        else { return nil }
        return FirebaseAccountDeletionRepository(functions: functions)
    }
}
