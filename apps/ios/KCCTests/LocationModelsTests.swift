import CoreLocation
import XCTest

@testable import KCC

/// Unit tests for the pure location models: the fix normalizer's
/// sentinel-to-nil rules (mirroring what Android gets for free from the fused
/// provider's optionals), and the `CLAuthorizationStatus` matrix — both pure
/// functions, no device.
final class LocationModelsTests: XCTestCase {

    private static let when = Date(timeIntervalSince1970: 1_700_000_000)

    // MARK: - LocationFix.of normalization

    func testValidFieldsPassThrough() {
        let fix = LocationFix.of(
            latitude: 57.487,
            longitude: 12.076,
            timestamp: Self.when,
            accuracyMeters: 5.0,
            headingDegrees: 271.5,
            speedMetersPerSecond: 13.9
        )

        XCTAssertEqual(
            fix,
            LocationFix(
                latitude: 57.487,
                longitude: 12.076,
                timestamp: Self.when,
                accuracyMeters: 5.0,
                headingDegrees: 271.5,
                speedMetersPerSecond: 13.9
            )
        )
    }

    func testCoreLocationNegativeSentinelsBecomeNil() {
        // CLLocation reports "unknown" as negative accuracy/course/speed —
        // the seam must swallow the sentinels so no consumer branches on -1.
        let fix = LocationFix.of(
            latitude: 57.487,
            longitude: 12.076,
            timestamp: Self.when,
            accuracyMeters: -1,
            headingDegrees: -1,
            speedMetersPerSecond: -1
        )

        XCTAssertNotNil(fix)
        XCTAssertNil(fix?.accuracyMeters)
        XCTAssertNil(fix?.headingDegrees)
        XCTAssertNil(fix?.speedMetersPerSecond)
    }

    func testNilOptionalsStayNil() {
        let fix = LocationFix.of(latitude: 0, longitude: 0, timestamp: Self.when)

        XCTAssertNotNil(fix)
        XCTAssertNil(fix?.accuracyMeters)
        XCTAssertNil(fix?.headingDegrees)
        XCTAssertNil(fix?.speedMetersPerSecond)
    }

    func testNonFiniteOptionalFieldsBecomeNil() {
        let fix = LocationFix.of(
            latitude: 57.487,
            longitude: 12.076,
            timestamp: Self.when,
            accuracyMeters: .nan,
            headingDegrees: .infinity,
            speedMetersPerSecond: .nan
        )

        XCTAssertNotNil(fix)
        XCTAssertNil(fix?.accuracyMeters)
        XCTAssertNil(fix?.headingDegrees)
        XCTAssertNil(fix?.speedMetersPerSecond)
    }

    func testHeadingWrapsIntoZeroTo360() {
        let fix = LocationFix.of(
            latitude: 57.487,
            longitude: 12.076,
            timestamp: Self.when,
            headingDegrees: 360.0
        )

        XCTAssertEqual(fix?.headingDegrees, 0.0)
    }

    func testStationaryZeroesAreKeptNotNilled() {
        // 0 speed and 0 heading are real measurements (parked, facing north)
        // — only NEGATIVE values are the unknown sentinel.
        let fix = LocationFix.of(
            latitude: 57.487,
            longitude: 12.076,
            timestamp: Self.when,
            accuracyMeters: 0,
            headingDegrees: 0,
            speedMetersPerSecond: 0
        )

        XCTAssertEqual(fix?.accuracyMeters, 0)
        XCTAssertEqual(fix?.headingDegrees, 0)
        XCTAssertEqual(fix?.speedMetersPerSecond, 0)
    }

    func testNonFiniteCoordinateYieldsNoFix() {
        XCTAssertNil(
            LocationFix.of(latitude: .nan, longitude: 12.076, timestamp: Self.when)
        )
        XCTAssertNil(
            LocationFix.of(latitude: 57.487, longitude: .infinity, timestamp: Self.when)
        )
    }

    // MARK: - LocationAuthorization

    func testIsAuthorized() {
        XCTAssertFalse(LocationAuthorization.notDetermined.isAuthorized)
        XCTAssertFalse(LocationAuthorization.denied.isAuthorized)
        XCTAssertTrue(LocationAuthorization.whileInUse.isAuthorized)
        XCTAssertTrue(LocationAuthorization.always.isAuthorized)
    }

    // MARK: - CLAuthorizationStatus matrix

    func testStatusMapping() {
        XCTAssertEqual(
            CoreLocationProvider.authorization(from: .notDetermined), .notDetermined
        )
        // Restricted folds into denied: it cannot be fixed by asking, which
        // is the property the coordinator branches on.
        XCTAssertEqual(CoreLocationProvider.authorization(from: .restricted), .denied)
        XCTAssertEqual(CoreLocationProvider.authorization(from: .denied), .denied)
        XCTAssertEqual(
            CoreLocationProvider.authorization(from: .authorizedWhenInUse), .whileInUse
        )
        XCTAssertEqual(
            CoreLocationProvider.authorization(from: .authorizedAlways), .always
        )
    }

    // MARK: - CLLocation → LocationFix

    func testFixFromCLLocationMapsAllFields() {
        let location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 57.487, longitude: 12.076),
            altitude: 12,
            horizontalAccuracy: 4.2,
            verticalAccuracy: 3.0,
            course: 88.0,
            speed: 22.5,
            timestamp: Self.when
        )

        let fix = CoreLocationProvider.fix(from: location)

        XCTAssertEqual(fix?.latitude, 57.487)
        XCTAssertEqual(fix?.longitude, 12.076)
        XCTAssertEqual(fix?.timestamp, Self.when)
        XCTAssertEqual(fix?.accuracyMeters, 4.2)
        XCTAssertEqual(fix?.headingDegrees, 88.0)
        XCTAssertEqual(fix?.speedMetersPerSecond, 22.5)
    }

    func testFixFromCLLocationNormalizesUnknowns() {
        let location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 57.487, longitude: 12.076),
            altitude: 0,
            horizontalAccuracy: -1,
            verticalAccuracy: -1,
            course: -1,
            speed: -1,
            timestamp: Self.when
        )

        let fix = CoreLocationProvider.fix(from: location)

        XCTAssertNotNil(fix)
        XCTAssertNil(fix?.accuracyMeters)
        XCTAssertNil(fix?.headingDegrees)
        XCTAssertNil(fix?.speedMetersPerSecond)
    }
}
