import Foundation
import MapboxMaps
import Network

/// Stable silent-failure categories. Values are app-authored constants and can
/// safely appear in the public deduplicated issue created by the backend.
enum FeatureHealthKind: CaseIterable, Sendable {
    case mapStyleLoadFailed
    case mapResourceLoadError
    case mapRenderTimeout

    var feature: String {
        switch self {
        case .mapStyleLoadFailed: "mapHealth.styleLoad"
        case .mapResourceLoadError: "mapHealth.mapLoad"
        case .mapRenderTimeout: "mapHealth.renderTimeout"
        }
    }

    var codePrefix: String {
        switch self {
        case .mapStyleLoadFailed: "MAP_STYLE_LOAD_FAILED"
        case .mapResourceLoadError: "MAP_RESOURCE_LOAD_ERROR"
        case .mapRenderTimeout: "MAP_RENDER_TIMEOUT"
        }
    }

    var summary: String {
        switch self {
        case .mapStyleLoadFailed: "Mapbox style failed to load"
        case .mapResourceLoadError: "Mapbox sprite or glyph resource failed to load"
        case .mapRenderTimeout: "Map surface never rendered a full frame"
        }
    }
}

struct FeatureHealthEnvironment: Equatable, Sendable {
    let appVersion: String
    let buildNumber: String
    let osVersion: String
    let mapboxSDKVersion: String
    /// Presence only. The Mapbox token itself must never enter diagnostics.
    let accessTokenPresent: Bool
}

struct FeatureHealthConditions: Equatable, Sendable {
    let online: Bool
    let foreground: Bool
    let surfaceShown: Bool
}

enum FeatureHealthSuppression: Equatable, Sendable {
    case offline
    case backgrounded
    case surfaceNeverShown
    case alreadyReportedThisSession
}

enum FeatureHealthDecision: Equatable, Sendable {
    case report(feature: String, message: String, code: String, context: ClientErrorContext)
    case suppress(FeatureHealthSuppression)
}

/// Process-scoped decision gate: reports each health failure at most once per
/// app session, and only while the feature had a fair chance to work.
final class FeatureHealthGate: @unchecked Sendable {
    private let environment: FeatureHealthEnvironment
    private let lock = NSLock()
    private var reported: Set<FeatureHealthKind> = []

    init(environment: FeatureHealthEnvironment) {
        self.environment = environment
    }

    func decide(
        _ kind: FeatureHealthKind,
        conditions: FeatureHealthConditions
    ) -> FeatureHealthDecision {
        lock.withLock {
            guard conditions.surfaceShown else { return .suppress(.surfaceNeverShown) }
            guard conditions.foreground else { return .suppress(.backgrounded) }
            guard conditions.online else { return .suppress(.offline) }
            guard reported.insert(kind).inserted else {
                return .suppress(.alreadyReportedThisSession)
            }
            let version = Self.sanitizedVersion(environment.appVersion)
            let message = [
                kind.summary,
                "os=\(DiagnosticsSanitizer.message(environment.osVersion))",
                "tokenPresent=\(environment.accessTokenPresent)"
            ].joined(separator: " | ")
            return .report(
                feature: kind.feature,
                message: message,
                code: "\(kind.codePrefix)@\(version)",
                context: .init(
                    buildNumber: environment.buildNumber,
                    sdkVersion: environment.mapboxSDKVersion
                )
            )
        }
    }

    private static func sanitizedVersion(_ raw: String) -> String {
        let kept = raw.filter { $0.isASCII && ($0.isLetter || $0.isNumber || ".-_".contains($0)) }
        return kept.isEmpty ? "unknown" : String(kept.prefix(24))
    }
}

/// Pure accumulating watchdog. Time only accrues while the map is visible,
/// foregrounded, and online; rendering permanently disarms it.
final class MapRenderWatchdog: @unchecked Sendable {
    static let defaultTimeoutMilliseconds: Int64 = 12_000
    private let timeoutMilliseconds: Int64
    private let lock = NSLock()
    private var elapsed: Int64 = 0
    private var disarmed = false

    init(timeoutMilliseconds: Int64 = defaultTimeoutMilliseconds) {
        self.timeoutMilliseconds = timeoutMilliseconds
    }

    func tick(milliseconds: Int64, eligible: Bool, rendered: Bool) -> Bool {
        lock.withLock {
            guard !disarmed else { return false }
            if rendered {
                disarmed = true
                return false
            }
            guard eligible, milliseconds > 0 else { return false }
            elapsed += milliseconds
            guard elapsed >= timeoutMilliseconds else { return false }
            disarmed = true
            return true
        }
    }

    var elapsedMilliseconds: Int64 { lock.withLock { elapsed } }
    var isDisarmed: Bool { lock.withLock { disarmed } }
}

protocol NetworkStatus: Sendable {
    func isOnline() -> Bool
}

/// Process-safe connectivity snapshot used only as a false-positive suppression
/// gate. No interface, address, or network name is collected.
final class SystemNetworkStatus: NetworkStatus, @unchecked Sendable {
    // This unauthenticated probe sends neither the Mapbox access token nor user data.
    private static let connectivityProbeURL = URL(string: "https://api.mapbox.com/")!
    private static let validationInterval: TimeInterval = 5

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "com.kungsbackacarcommunity.diagnostics.network")
    private let session: URLSession
    private let lock = NSLock()
    private var pathAvailable = false
    private var online = false
    private var validationInFlight = false
    private var lastValidation = Date.distantPast
    private var validationGeneration = 0
    private var validationTask: URLSessionDataTask?

    init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 4
        configuration.timeoutIntervalForResource = 4
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        session = URLSession(configuration: configuration)
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let (becameAvailable, taskToCancel) = self.lock.withLock {
                let available = path.status == .satisfied
                let changed = self.pathAvailable != available
                self.pathAvailable = available
                if !available {
                    self.online = false
                    self.validationInFlight = false
                    self.validationGeneration += 1
                    let task = self.validationTask
                    self.validationTask = nil
                    return (false, task)
                }
                if changed {
                    self.lastValidation = .distantPast
                }
                return (changed, nil)
            }
            taskToCancel?.cancel()
            if becameAvailable {
                self.validateConnectivityIfNeeded()
            }
        }
        monitor.start(queue: queue)
    }

    deinit {
        monitor.cancel()
        session.invalidateAndCancel()
    }

    func isOnline() -> Bool {
        validateConnectivityIfNeeded()
        return lock.withLock { pathAvailable && online }
    }

    static func acceptsMapboxConnectivityResponse(
        _ response: URLResponse?,
        error: Error?
    ) -> Bool {
        guard error == nil,
              let response = response as? HTTPURLResponse,
              response.url?.host == "api.mapbox.com"
        else {
            return false
        }
        return (200..<500).contains(response.statusCode)
    }

    private func validateConnectivityIfNeeded() {
        let generation = lock.withLock { () -> Int? in
            guard pathAvailable,
                  !validationInFlight,
                  Date().timeIntervalSince(lastValidation) >= Self.validationInterval
            else {
                return nil
            }
            validationInFlight = true
            online = false
            lastValidation = Date()
            validationGeneration += 1
            return validationGeneration
        }
        guard let generation else { return }

        var request = URLRequest(url: Self.connectivityProbeURL)
        request.httpMethod = "HEAD"
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = 4
        let task = session.dataTask(with: request) { [weak self] _, response, error in
            guard let self else { return }
            let validated = Self.acceptsMapboxConnectivityResponse(response, error: error)
            self.lock.withLock {
                guard self.validationGeneration == generation else { return }
                self.online = self.pathAvailable && validated
                self.validationInFlight = false
                self.validationTask = nil
            }
        }
        let shouldStart = lock.withLock {
            guard pathAvailable,
                  validationInFlight,
                  validationGeneration == generation
            else {
                return false
            }
            validationTask = task
            return true
        }
        if shouldStart {
            task.resume()
        } else {
            task.cancel()
        }
    }
}

/// One process-lifetime diagnostics composition for the authenticated shell.
/// Keeping the gate here ensures a SwiftUI view reconstruction cannot reset the
/// once-per-session health-report cap.
@MainActor
final class IOSDiagnosticsComposition {
    let clientErrorReporter: any ClientErrorReporter
    let featureHealthReporter: FeatureHealthReporter

    init(
        environment: DiagnosticsEnvironment = .current(),
        accessTokenPresent: Bool = MapboxConfiguration.accessToken() != nil
    ) {
        let clientReporter = FirebaseClientErrorReporter.createIfAvailable(environment: environment)
            ?? NoopClientErrorReporter()
        clientErrorReporter = clientReporter
        featureHealthReporter = FeatureHealthReporter(
            gate: FeatureHealthGate(environment: FeatureHealthEnvironment(
                appVersion: environment.appVersion,
                buildNumber: environment.buildNumber,
                osVersion: environment.osVersion,
                mapboxSDKVersion: Self.mapboxSDKVersion,
                accessTokenPresent: accessTokenPresent
            )),
            errorReporter: clientReporter,
            networkStatus: SystemNetworkStatus()
        )
    }
}

private extension IOSDiagnosticsComposition {
    static var mapboxSDKVersion: String {
        Bundle(for: MapView.self).infoDictionary?["CFBundleShortVersionString"] as? String
            ?? "unknown"
    }
}

final class FeatureHealthReporter: @unchecked Sendable {
    private let gate: FeatureHealthGate
    private let errorReporter: any ClientErrorReporter
    private let networkStatus: any NetworkStatus

    init(
        gate: FeatureHealthGate,
        errorReporter: any ClientErrorReporter,
        networkStatus: any NetworkStatus
    ) {
        self.gate = gate
        self.errorReporter = errorReporter
        self.networkStatus = networkStatus
    }

    func isOnline() -> Bool { networkStatus.isOnline() }

    @discardableResult
    func report(
        _ kind: FeatureHealthKind,
        foreground: Bool,
        surfaceShown: Bool
    ) -> FeatureHealthDecision {
        let decision = gate.decide(kind, conditions: FeatureHealthConditions(
            online: networkStatus.isOnline(),
            foreground: foreground,
            surfaceShown: surfaceShown
        ))
        if case .report(let feature, let message, let code, let context) = decision {
            errorReporter.report(feature: feature, message: message, code: code, context: context)
        }
        return decision
    }
}
