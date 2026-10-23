import XCTest
@testable import KCC

final class CrashTelemetryTests: XCTestCase {
    func testCollectionIsEnabledOnlyForReleaseBuilds() {
        XCTAssertFalse(CrashTelemetryPolicy.collectionEnabled(isDebugBuild: true))
        XCTAssertTrue(CrashTelemetryPolicy.collectionEnabled(isDebugBuild: false))
    }

    func testValueCollapsesWhitespaceAndBoundsCrashlyticsFields() {
        let value = CrashTelemetryText.value("  map\n\t" + String(repeating: "x", count: 2_000))

        XCTAssertFalse(value.contains("\n"))
        XCTAssertFalse(value.contains("\t"))
        XCTAssertEqual(value.count, CrashTelemetryText.maximumLength)
        XCTAssertTrue(value.hasPrefix("map "))
    }

    func testBreadcrumbDropsBlankDetail() {
        XCTAssertEqual(
            CrashTelemetryText.breadcrumb(event: "app.start", detail: " \n "),
            "app.start"
        )
        XCTAssertEqual(
            CrashTelemetryText.breadcrumb(event: "nonFatal", detail: "map.render"),
            "nonFatal: map.render"
        )
    }
}
