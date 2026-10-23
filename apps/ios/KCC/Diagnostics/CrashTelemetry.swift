import FirebaseCore
import FirebaseCrashlytics
import Foundation

/// PII-free app context layered on Crashlytics' automatic fatal capture.
/// Callers must use stable app-authored keys and events, never user content.
@MainActor
protocol CrashTelemetry: AnyObject {
    func setKey(_ key: String, value: String)
    func log(_ event: String, detail: String?)
    func recordNonFatal(feature: String, error: Error)
}

@MainActor
final class NoopCrashTelemetry: CrashTelemetry {
    func setKey(_ key: String, value: String) {}
    func log(_ event: String, detail: String?) {}
    func recordNonFatal(feature: String, error: Error) {}
}

enum CrashTelemetryPolicy {
    static func collectionEnabled(isDebugBuild: Bool) -> Bool {
        !isDebugBuild
    }
}

enum CrashTelemetryText {
    static let maximumLength = 1_024

    static func value(_ raw: String) -> String {
        String(
            raw.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(maximumLength)
        )
    }

    static func breadcrumb(event: String, detail: String?) -> String {
        let event = value(event)
        guard let detail = detail.map(value), !detail.isEmpty else { return event }
        return value("\(event): \(detail)")
    }
}

enum CrashTelemetryKey {
    static let buildType = "build_type"
    static let versionName = "version_name"
    static let versionCode = "version_code"
    static let lastNonFatal = "last_non_fatal"
}

enum CrashTelemetryEvent {
    static let appStart = "app.start"
    static let nonFatal = "nonFatal"
}

/// Config-less builds receive a no-op sink. Configured releases get fatal
/// capture, symbolication, bounded breadcrumbs, and explicit non-fatal errors.
@MainActor
final class FirebaseCrashTelemetry: CrashTelemetry {
    private let crashlytics: Crashlytics

    private init(crashlytics: Crashlytics) {
        self.crashlytics = crashlytics
    }

    func setKey(_ key: String, value: String) {
        crashlytics.setCustomValue(
            CrashTelemetryText.value(value),
            forKey: CrashTelemetryText.value(key)
        )
    }

    func log(_ event: String, detail: String? = nil) {
        crashlytics.log(CrashTelemetryText.breadcrumb(event: event, detail: detail))
    }

    func recordNonFatal(feature: String, error: Error) {
        let feature = CrashTelemetryText.value(feature)
        log(CrashTelemetryEvent.nonFatal, detail: feature)
        setKey(CrashTelemetryKey.lastNonFatal, value: feature)
        crashlytics.record(error: error)
    }

    static func install(
        environment: DiagnosticsEnvironment,
        isDebugBuild: Bool = FirebaseCrashTelemetry.isDebugBuild
    ) -> any CrashTelemetry {
        guard FirebaseApp.app() != nil else { return NoopCrashTelemetry() }

        let crashlytics = Crashlytics.crashlytics()
        crashlytics.setCrashlyticsCollectionEnabled(
            CrashTelemetryPolicy.collectionEnabled(isDebugBuild: isDebugBuild)
        )
        let telemetry = FirebaseCrashTelemetry(crashlytics: crashlytics)
        telemetry.setKey(
            CrashTelemetryKey.buildType,
            value: isDebugBuild ? "debug" : "release"
        )
        telemetry.setKey(CrashTelemetryKey.versionName, value: environment.appVersion)
        telemetry.setKey(CrashTelemetryKey.versionCode, value: environment.buildNumber)
        telemetry.log(CrashTelemetryEvent.appStart)
        return telemetry
    }

    private static var isDebugBuild: Bool {
        #if DEBUG
        true
        #else
        false
        #endif
    }
}
