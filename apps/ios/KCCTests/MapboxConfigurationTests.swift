import XCTest

@testable import KCC

final class MapboxConfigurationTests: XCTestCase {
    func testReturnsPublicRuntimeToken() {
        XCTAssertEqual(
            MapboxConfiguration.accessToken(infoDictionary: ["MBXAccessToken": "pk.public-token"]),
            "pk.public-token"
        )
    }

    func testTrimsInjectedToken() {
        XCTAssertEqual(
            MapboxConfiguration.accessToken(infoDictionary: ["MBXAccessToken": "  pk.public-token\n"]),
            "pk.public-token"
        )
    }

    func testRejectsMissingSecretAndUnresolvedValues() {
        XCTAssertNil(MapboxConfiguration.accessToken(infoDictionary: [:]))
        XCTAssertNil(MapboxConfiguration.accessToken(infoDictionary: ["MBXAccessToken": "sk.secret"]))
        XCTAssertNil(MapboxConfiguration.accessToken(infoDictionary: ["MBXAccessToken": "$(MAPBOX_ACCESS_TOKEN)"]))
    }
}
