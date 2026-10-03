import XCTest

@testable import KCC

final class FeatureHealthTests: XCTestCase {
    private final class RecordingErrorReporter: ClientErrorReporter, @unchecked Sendable {
        struct Entry: Equatable { let feature: String; let message: String; let code: String? }
        private let lock = NSLock()
        private var stored: [Entry] = []
        var entries: [Entry] { lock.withLock { stored } }
        func report(feature: String, message: String, code: String?) {
            lock.withLock { stored.append(Entry(feature: feature, message: message, code: code)) }
        }
    }

    private struct FixedNetwork: NetworkStatus { let online: Bool; func isOnline() -> Bool { online } }

    private func gate() -> FeatureHealthGate {
        FeatureHealthGate(environment: FeatureHealthEnvironment(
            appVersion: "1.2.3 beta!",
            buildNumber: "42",
            osVersion: "iOS 18.0",
            mapboxSDKVersion: "11.26.0",
            accessTokenPresent: true
        ))
    }

    func testGateSuppressesIneligibleConditionsWithoutConsumingSessionCap() {
        let gate = gate()
        XCTAssertEqual(
            gate.decide(.mapRenderTimeout, conditions: .init(
                online: false, foreground: true, surfaceShown: true
            )),
            .suppress(.offline)
        )
        XCTAssertEqual(
            gate.decide(.mapRenderTimeout, conditions: .init(
                online: true, foreground: false, surfaceShown: true
            )),
            .suppress(.backgrounded)
        )
        guard case .report(let feature, let message, let code, let context) = gate.decide(
            .mapRenderTimeout,
            conditions: .init(online: true, foreground: true, surfaceShown: true)
        ) else { return XCTFail("Expected a report") }
        XCTAssertEqual(feature, "mapHealth.renderTimeout")
        XCTAssertEqual(code, "MAP_RENDER_TIMEOUT@1.2.3beta")
        XCTAssertTrue(message.contains("tokenPresent=true"))
        XCTAssertFalse(message.contains("maps="))
        XCTAssertFalse(message.contains("build="))
        XCTAssertEqual(context.buildNumber, "42")
        XCTAssertEqual(context.sdkVersion, "11.26.0")
        XCTAssertFalse(message.contains("pk."))
    }

    func testGateReportsEachKindOnlyOncePerSession() {
        let gate = gate()
        let conditions = FeatureHealthConditions(online: true, foreground: true, surfaceShown: true)
        guard case .report = gate.decide(.mapStyleLoadFailed, conditions: conditions) else {
            return XCTFail("Expected first report")
        }
        XCTAssertEqual(
            gate.decide(.mapStyleLoadFailed, conditions: conditions),
            .suppress(.alreadyReportedThisSession)
        )
    }

    func testWatchdogOnlyAccumulatesEligibleTimeAndFiresOnce() {
        let watchdog = MapRenderWatchdog(timeoutMilliseconds: 3_000)
        XCTAssertFalse(watchdog.tick(milliseconds: 1_000, eligible: false, rendered: false))
        XCTAssertFalse(watchdog.tick(milliseconds: 2_000, eligible: true, rendered: false))
        XCTAssertEqual(watchdog.elapsedMilliseconds, 2_000)
        XCTAssertTrue(watchdog.tick(milliseconds: 1_000, eligible: true, rendered: false))
        XCTAssertFalse(watchdog.tick(milliseconds: 1_000, eligible: true, rendered: false))
        XCTAssertTrue(watchdog.isDisarmed)
    }

    func testRenderedMapDisarmsWatchdogWithoutReporting() {
        let watchdog = MapRenderWatchdog(timeoutMilliseconds: 1)
        XCTAssertFalse(watchdog.tick(milliseconds: 1, eligible: true, rendered: true))
        XCTAssertTrue(watchdog.isDisarmed)
    }

    func testReporterForwardsAllowedDecisionAndNoopsOffline() {
        let onlineSink = RecordingErrorReporter()
        let online = FeatureHealthReporter(
            gate: gate(), errorReporter: onlineSink, networkStatus: FixedNetwork(online: true)
        )
        _ = online.report(.mapResourceLoadError, foreground: true, surfaceShown: true)
        XCTAssertEqual(onlineSink.entries.count, 1)
        XCTAssertEqual(onlineSink.entries.first?.feature, "mapHealth.mapLoad")

        let offlineSink = RecordingErrorReporter()
        let offline = FeatureHealthReporter(
            gate: gate(), errorReporter: offlineSink, networkStatus: FixedNetwork(online: false)
        )
        XCTAssertEqual(
            offline.report(.mapResourceLoadError, foreground: true, surfaceShown: true),
            .suppress(.offline)
        )
        XCTAssertTrue(offlineSink.entries.isEmpty)
    }
}
