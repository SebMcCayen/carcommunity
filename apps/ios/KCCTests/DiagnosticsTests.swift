import XCTest

@testable import KCC

final class DiagnosticsTests: XCTestCase {
    private final class RecordingCallable: DiagnosticsCallableClient, @unchecked Sendable {
        private let lock = NSLock()
        private var stored: [(String, [String: Any])] = []
        var calls: [(String, [String: Any])] { lock.withLock { stored } }
        func call(_ name: String, payload: DiagnosticsCallablePayload) async throws {
            lock.withLock { stored.append((name, payload.value)) }
        }
    }

    private struct SensitiveError: Error, CustomStringConvertible {
        var description: String {
            "user@example.com /Users/alice/private/secret.txt id 12345 token=abc.def"
        }
    }

    private final class RecordingDiagnosticsReporter: DiagnosticsReporter, @unchecked Sendable {
        private let lock = NSLock()
        private var stored: [DiagnosticsReport] = []
        var reports: [DiagnosticsReport] { lock.withLock { stored } }
        func report(_ report: DiagnosticsReport) { lock.withLock { stored.append(report) } }
    }

    func testMessageSanitizationRedactsPIISecretsPathsAndDigits() {
        let value = DiagnosticsSanitizer.message(
            " user@example.com /Users/alice/file.txt 57.1234 token=super-secret\nnext "
        )
        XCTAssertFalse(value.contains("user@example.com"))
        XCTAssertFalse(value.contains("alice"))
        XCTAssertFalse(value.contains("57"))
        XCTAssertFalse(value.contains("super-secret"))
        XCTAssertFalse(value.contains("\n"))
        XCTAssertTrue(value.contains("<email>"))
        XCTAssertTrue(value.contains("<path>"))
        XCTAssertTrue(value.contains("<token>"))
    }

    func testMetadataDropsSensitiveKeysBoundsAndRedactsStringValues() {
        var input: [String: DiagnosticsMetadataValue] = [
            "accessToken": .string("secret"),
            "latitude": .number(57.1),
            "lat": .number(57.1),
            "lng": .number(12.1),
            "lon": .number(12.1),
            "stackSummary": .string("trace"),
            "safe": .string("person@example.com attempt 99"),
            "online": .bool(true),
            "notFinite": .number(.infinity)
        ]
        for index in 0..<30 { input["z\(index)"] = .bool(true) }

        let sanitized = DiagnosticsSanitizer.metadata(input)
        XCTAssertNotNil(sanitized)
        XCTAssertNil(sanitized?["accessToken"])
        XCTAssertNil(sanitized?["latitude"])
        XCTAssertNil(sanitized?["lat"])
        XCTAssertNil(sanitized?["lng"])
        XCTAssertNil(sanitized?["lon"])
        XCTAssertNil(sanitized?["stackSummary"])
        XCTAssertNil(sanitized?["notFinite"])
        XCTAssertLessThanOrEqual(sanitized?.count ?? 0, 20)
        XCTAssertEqual(sanitized?["safe"], .string("<email> attempt <n>"))
    }

    func testBoundsMatchBackendUTF16LimitsWithoutSplittingScalars() {
        let report = DiagnosticsReport(
            severity: .error,
            featureArea: .unknown,
            safeMessage: String(repeating: "🚗", count: 1_100),
            appVersion: String(repeating: "🚗", count: 40),
            metadata: ["safe": .string(String(repeating: "🚗", count: 400))]
        )
        XCTAssertEqual(report.safeMessage.utf16.count, 2_000)
        XCTAssertEqual(report.appVersion?.utf16.count, 50)
        guard case .string(let metadataValue) = report.metadata?["safe"] else {
            return XCTFail("Expected string metadata")
        }
        XCTAssertEqual(metadataValue.utf16.count, 500)
    }

    func testDiagnosticsPayloadMatchesCallableContract() {
        let report = DiagnosticsReport(
            severity: .error,
            featureArea: .network,
            safeMessage: "Network request failed",
            errorCode: "UNAVAILABLE!",
            appVersion: "1.2.3",
            buildNumber: "42",
            osVersion: "iOS 18",
            metadata: ["online": .bool(false)]
        )
        let payload = report.payload
        XCTAssertEqual(payload["severity"] as? String, "error")
        XCTAssertEqual(payload["platform"] as? String, "ios")
        XCTAssertEqual(payload["featureArea"] as? String, "network")
        XCTAssertEqual(payload["errorCode"] as? String, "UNAVAILABLE")
        XCTAssertEqual((payload["metadata"] as? [String: Any])?["online"] as? Bool, false)
    }

    func testCrashReportNeverContainsRawDescriptionOrStackTrace() {
        let report = DiagnosticsReport.from(
            error: SensitiveError(),
            environment: DiagnosticsEnvironment(
                appVersion: "1.0", buildNumber: "1", osVersion: "iOS", deviceModel: "iPhone"
            )
        )
        XCTAssertEqual(report.severity, .critical)
        XCTAssertEqual(report.errorCode, "SensitiveError")
        XCTAssertEqual(report.safeMessage, "SensitiveError occurred")
        XCTAssertFalse(report.safeMessage.contains("user@example.com"))
        XCTAssertFalse(report.safeMessage.contains("super-secret"))
        XCTAssertNil(report.metadata)
    }

    func testSignInAdapterUsesOnlyStableSanitizedFields() {
        let sink = RecordingDiagnosticsReporter()
        let reporter = DiagnosticsSignInFailureReporter(
            reporter: sink,
            environment: DiagnosticsEnvironment(
                appVersion: "1.0", buildNumber: "2", osVersion: "iOS", deviceModel: "iPhone"
            )
        )
        reporter.reportSignInFailure(SignInFailureDetails(
            errorType: "FirebaseAuthError!",
            step: .firebaseExchange,
            statusCode: "invalid-credential"
        ))

        let report = sink.reports.first
        XCTAssertEqual(report?.featureArea, .signIn)
        XCTAssertEqual(report?.safeMessage, "Sign-in failed: FirebaseAuthError")
        XCTAssertEqual(report?.errorCode, "FirebaseAuthError")
        XCTAssertEqual(report?.metadata?["signInStep"], .string("firebase_exchange"))
        XCTAssertEqual(report?.metadata?["errorStatus"], .string("invalid-credential"))
    }

    func testAuthenticatedErrorReporterUsesContractAndRedactsBeforeTransport() async {
        let callable = RecordingCallable()
        let reporter = FirebaseClientErrorReporter(
            client: callable,
            environment: DiagnosticsEnvironment(
                appVersion: "1.0", buildNumber: "2", osVersion: "iOS 18", deviceModel: "iPhone"
            )
        )
        reporter.report(
            feature: "notifications.inboxListener",
            message: "Failed for user@example.com at 57.123 with token=secret",
            code: "UNAVAILABLE!"
        )

        let deadline = Date().addingTimeInterval(1)
        while callable.calls.isEmpty, Date() < deadline { await Task.yield() }
        let call = callable.calls.first
        XCTAssertEqual(call?.0, "errors-reportClientError")
        XCTAssertEqual(call?.1["feature"] as? String, "notifications.inboxListener")
        XCTAssertEqual(call?.1["platform"] as? String, "ios")
        XCTAssertEqual(call?.1["code"] as? String, "UNAVAILABLE")
        let message = call?.1["message"] as? String
        XCTAssertFalse(message?.contains("user@example.com") == true)
        XCTAssertFalse(message?.contains("57.123") == true)
        XCTAssertFalse(message?.contains("secret") == true)
    }

    func testNoopReportersAreSafe() {
        NoopDiagnosticsReporter().report(DiagnosticsReport(
            severity: .info, featureArea: .unknown, safeMessage: "Health check"
        ))
        NoopClientErrorReporter().report(feature: "test", message: "test", code: nil)
    }
}
