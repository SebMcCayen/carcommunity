import Foundation

/// The SDK payload is a tree of immutable scalar Foundation values. Swift's
/// `Any` cannot express that sendability, so this reviewed wrapper carries it
/// across the reporter's fire-and-forget task boundary.
struct DiagnosticsCallablePayload: @unchecked Sendable {
    let value: [String: Any]
}

/// Minimal callable transport so reporters remain Firebase-free and testable.
protocol DiagnosticsCallableClient: Sendable {
    func call(_ name: String, payload: DiagnosticsCallablePayload) async throws
}

private struct FirebaseDiagnosticsCallableClient: DiagnosticsCallableClient {
    let client: KccFunctionsClient

    func call(_ name: String, payload: DiagnosticsCallablePayload) async throws {
        _ = try await client.call(name, payload: payload.value)
    }
}

/// Fire-and-forget diagnostics sink. Implementations must never surface their
/// own transport failures to the feature that is already failing.
protocol DiagnosticsReporter: Sendable {
    func report(_ report: DiagnosticsReport)
}

struct NoopDiagnosticsReporter: DiagnosticsReporter {
    func report(_ report: DiagnosticsReport) {}
}

final class FirebaseDiagnosticsReporter: DiagnosticsReporter, @unchecked Sendable {
    private static let callable = "diagnostics-submitReport"
    private let client: any DiagnosticsCallableClient

    init(client: any DiagnosticsCallableClient) {
        self.client = client
    }

    func report(_ report: DiagnosticsReport) {
        let payload = DiagnosticsCallablePayload(value: report.payload)
        Task { [client] in
            try? await client.call(Self.callable, payload: payload)
        }
    }

    static func createIfAvailable() -> DiagnosticsReporter? {
        guard let client = KccFunctionsClient.createIfAvailable() else { return nil }
        return FirebaseDiagnosticsReporter(client: FirebaseDiagnosticsCallableClient(client: client))
    }
}

/// Adapts sign-in's existing PII-free failure details to the public diagnostics
/// path. Error messages, Apple credentials, tokens, email, and account ids are
/// never present in this type.
struct DiagnosticsSignInFailureReporter: SignInFailureReporter {
    let reporter: any DiagnosticsReporter
    let environment: DiagnosticsEnvironment

    func reportSignInFailure(_ details: SignInFailureDetails) {
        let errorType = DiagnosticsSanitizer.errorCode(details.errorType) ?? "UnknownError"
        var metadata: [String: DiagnosticsMetadataValue] = [
            "signInStep": .string(details.step.rawValue),
            "deviceModel": .string(environment.deviceModel)
        ]
        if let statusCode = DiagnosticsSanitizer.errorCode(details.statusCode) {
            metadata["errorStatus"] = .string(statusCode)
        }
        reporter.report(DiagnosticsReport(
            severity: .error,
            featureArea: .signIn,
            safeMessage: "Sign-in failed: \(errorType)",
            errorCode: errorType,
            appVersion: environment.appVersion,
            buildNumber: environment.buildNumber,
            osVersion: environment.osVersion,
            metadata: metadata
        ))
    }
}

/// Authenticated surfaced-error sink (`errors-reportClientError`). Messages are
/// app-authored summaries only; the implementation additionally redacts and
/// bounds every field before it can leave the device.
protocol ClientErrorReporter: Sendable {
    func report(feature: String, message: String, code: String?)
}

struct NoopClientErrorReporter: ClientErrorReporter {
    func report(feature: String, message: String, code: String?) {}
}

final class FirebaseClientErrorReporter: ClientErrorReporter, @unchecked Sendable {
    private static let callable = "errors-reportClientError"
    private let client: any DiagnosticsCallableClient
    private let environment: DiagnosticsEnvironment

    init(client: any DiagnosticsCallableClient, environment: DiagnosticsEnvironment) {
        self.client = client
        self.environment = environment
    }

    func report(feature: String, message: String, code: String?) {
        guard let feature = DiagnosticsSanitizer.feature(feature) else { return }
        var payload: [String: Any] = [
            "feature": feature,
            "message": DiagnosticsSanitizer.message(message),
            "appVersion": String(environment.appVersion.prefix(50)),
            "osVersion": String(environment.osVersion.prefix(100)),
            "deviceModel": String(environment.deviceModel.prefix(100)),
            "platform": "ios"
        ]
        if let code = DiagnosticsSanitizer.errorCode(code) { payload["code"] = code }
        let callablePayload = DiagnosticsCallablePayload(value: payload)
        Task { [client] in
            try? await client.call(Self.callable, payload: callablePayload)
        }
    }

    @MainActor
    static func createIfAvailable(
        environment: DiagnosticsEnvironment = .current()
    ) -> ClientErrorReporter? {
        guard let client = KccFunctionsClient.createIfAvailable() else { return nil }
        return FirebaseClientErrorReporter(
            client: FirebaseDiagnosticsCallableClient(client: client),
            environment: environment
        )
    }
}
