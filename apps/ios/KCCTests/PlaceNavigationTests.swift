import XCTest
@testable import KCC

final class PlaceNavigationTests: XCTestCase {
    func testDrivingHandoffUsesTheSelectedCoordinateAndNoInventedOrigin() throws {
        let point = MapPoint(longitude: 11.973, latitude: 57.708)
        let url = try XCTUnwrap(PlaceNavigationDestination.drivingURL(to: point))
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        XCTAssertEqual(components.scheme, "https")
        XCTAssertEqual(components.host, "maps.apple.com")
        XCTAssertEqual(components.queryItems, [
            URLQueryItem(name: "daddr", value: "57.708,11.973"),
            URLQueryItem(name: "dirflg", value: "d")
        ])
    }

    func testInvalidCoordinatesNeverCreateANavigationLink() {
        for point in [MapPoint(longitude: .nan, latitude: 57),
                      MapPoint(longitude: 12, latitude: .infinity),
                      MapPoint(longitude: 181, latitude: 57),
                      MapPoint(longitude: 12, latitude: -91)] {
            XCTAssertNil(PlaceNavigationDestination.drivingURL(to: point))
        }
        XCTAssertNotNil(PlaceNavigationDestination.drivingURL(to: MapPoint(longitude: -180, latitude: -90)))
    }

    func testDifferentDestinationsReplaceTheReadingPanelIdentity() {
        let first = PlaceSuggestion(id: "same-provider-id", name: "First", address: nil,
            point: MapPoint(longitude: 12, latitude: 57))
        let updated = PlaceSuggestion(id: first.id, name: "Changed address", address: "New street",
            point: MapPoint(longitude: 13, latitude: 58))
        XCTAssertNotEqual(MapReadingSelection.place(first), .place(updated))
    }
}
