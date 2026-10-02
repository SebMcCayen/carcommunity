import Foundation
import UIKit

/// Severities accepted by `diagnostics-submitReport`.
enum DiagnosticsSeverity: String, Sendable {
    case info
    case warning
    case error
    case critical
}

/// Feature areas accepted by the shared diagnostics callable.
enum DiagnosticsFeatureArea: String, Sendable {
    case auth
    case signIn = "sign_in"
    case liveLocation = "live_location"
    case events
    case subscription
    case admin
    case map
    case network
    case unknown
}

/// A scalar metadata value. Nested containers are deliberately impossible, so
/// sensitive data cannot be hidden inside an object or array.
enum DiagnosticsMetadataValue: Equatable, Sendable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case null

    fileprivate var payloadValue: Any {
        switch self {
        case .string(let value): value
        case .number(let value): value
        case .bool(let value): value
        case .null: NSNull()
        }
    }
}

/// Non-identifying build/device facts shared by diagnostics and client errors.
struct DiagnosticsEnvironment: Equatable, Sendable {
    let appVersion: String
    let buildNumber: String
    let osVersion: String
    let deviceModel: String

    @MainActor
    static func current(
        bundle: Bundle = .main,
        processInfo: ProcessInfo = .processInfo,
        device: UIDevice = .current
    ) -> DiagnosticsEnvironment {
        DiagnosticsEnvironment(
            appVersion: Self.bounded(
                bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
                maximum: 50
            ),
            buildNumber: Self.bounded(
                bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String,
                maximum: 50
            ),
            osVersion: Self.bounded(processInfo.operatingSystemVersionString, maximum: 100),
            deviceModel: Self.bounded(device.model, maximum: 100)
        )
    }

    private static func bounded(_ value: String?, maximum: Int) -> String {
        String((value ?? "unknown").prefix(maximum))
    }
}

/// Deterministic, client-side privacy filtering. The backend repeats these
/// checks; filtering here prevents sensitive values from leaving the device.
enum DiagnosticsSanitizer {
    static let maximumMessageLength = 2_000
    static let maximumMetadataCount = 20
    static let maximumMetadataKeyLength = 100
    static let maximumMetadataValueLength = 500

    private static let blockedKeyFragments = [
        "token", "secret", "password", "credential", "auth", "cookie",
        "stack", "trace", "latitude", "longitude", "coords", "coordinates",
        "location", "position"
    ]

    /// Produces a bounded single-line message with common PII/secret carriers
    /// redacted. Digit runs are masked, which also prevents exact coordinates,
    /// phone numbers, identifiers, and retry counters escaping.
    static func message(_ raw: String, fallback: String = "Error") -> String {
        var value = raw
        let replacements: [(String, String)] = [
            (#"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b"#, "<email>"),
            (#"(?i)\b[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\b"#, "<uuid>"),
            (#"(?i)\bhttps?://\S+"#, "<url>"),
            (#"(?i)\b(?:bearer\s+|(?:access[_-]?|refresh[_-]?|id[_-]?)?token\s*[:=]\s*)\S+"#, "<token>"),
            (#"(?:/[A-Za-z0-9._-]+){2,}"#, "<path>"),
            (#"\d+"#, "<n>"),
            (#"\s+"#, " ")
        ]
        for (pattern, replacement) in replacements {
            value = value.replacingOccurrences(
                of: pattern,
                with: replacement,
                options: .regularExpression
            )
        }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return String((trimmed.isEmpty ? fallback : trimmed).prefix(maximumMessageLength))
    }

    static func errorCode(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let kept = raw.filter { $0.isASCII && ($0.isLetter || $0.isNumber || "._-".contains($0)) }
        return kept.isEmpty ? nil : String(kept.prefix(100))
    }

    static func feature(_ raw: String) -> String? {
        let kept = raw.filter { $0.isASCII && ($0.isLetter || $0.isNumber || "._-".contains($0)) }
        return kept.isEmpty ? nil : String(kept.prefix(120))
    }

    static func metadata(
        _ input: [String: DiagnosticsMetadataValue]?
    ) -> [String: DiagnosticsMetadataValue]? {
        guard let input else { return nil }
        var result: [String: DiagnosticsMetadataValue] = [:]
        // Sorting makes the 20-key truncation deterministic across processes.
        for key in input.keys.sorted() {
            guard result.count < maximumMetadataCount,
                !key.isEmpty,
                key.count <= maximumMetadataKeyLength
            else { continue }
            let lower = key.lowercased()
            guard !blockedKeyFragments.contains(where: lower.contains) else { continue }
            guard let value = input[key] else { continue }
            switch value {
            case .string(let raw):
                result[key] = .string(String(message(raw).prefix(maximumMetadataValueLength)))
            case .number(let number):
                guard number.isFinite else { continue }
                result[key] = .number(number)
            case .bool, .null:
                result[key] = value
            }
        }
        return result.isEmpty ? nil : result
    }
}

/// Privacy-reviewed payload for the public, pre-auth diagnostics callable.
struct DiagnosticsReport: Equatable, Sendable {
    let severity: DiagnosticsSeverity
    let featureArea: DiagnosticsFeatureArea
    let safeMessage: String
    let errorCode: String?
    let appVersion: String?
    let buildNumber: String?
    let osVersion: String?
    let metadata: [String: DiagnosticsMetadataValue]?

    init(
        severity: DiagnosticsSeverity,
        featureArea: DiagnosticsFeatureArea,
        safeMessage: String,
        errorCode: String? = nil,
        appVersion: String? = nil,
        buildNumber: String? = nil,
        osVersion: String? = nil,
        metadata: [String: DiagnosticsMetadataValue]? = nil
    ) {
        self.severity = severity
        self.featureArea = featureArea
        self.safeMessage = DiagnosticsSanitizer.message(safeMessage)
        self.errorCode = DiagnosticsSanitizer.errorCode(errorCode)
        self.appVersion = appVersion.map { String($0.prefix(50)) }
        self.buildNumber = buildNumber.map { String($0.prefix(50)) }
        self.osVersion = osVersion.map { String($0.prefix(100)) }
        self.metadata = DiagnosticsSanitizer.metadata(metadata)
    }

    var payload: [String: Any] {
        var result: [String: Any] = [
            "severity": severity.rawValue,
            "platform": "ios",
            "featureArea": featureArea.rawValue,
            "safeMessage": safeMessage
        ]
        if let errorCode { result["errorCode"] = errorCode }
        if let appVersion { result["appVersion"] = appVersion }
        if let buildNumber { result["buildNumber"] = buildNumber }
        if let osVersion { result["osVersion"] = osVersion }
        if let metadata {
            result["metadata"] = metadata.mapValues(\.payloadValue)
        }
        return result
    }

    /// Builds a critical report without a stack trace or error description.
    /// Error descriptions can carry arbitrary user content, so the Swift type
    /// name is the only failure detail allowed off-device.
    static func from(
        error: Error,
        featureArea: DiagnosticsFeatureArea = .unknown,
        environment: DiagnosticsEnvironment
    ) -> DiagnosticsReport {
        let typeName = String(describing: type(of: error))
        return DiagnosticsReport(
            severity: .critical,
            featureArea: featureArea,
            safeMessage: "\(typeName) occurred",
            errorCode: typeName,
            appVersion: environment.appVersion,
            buildNumber: environment.buildNumber,
            osVersion: environment.osVersion
        )
    }
}
