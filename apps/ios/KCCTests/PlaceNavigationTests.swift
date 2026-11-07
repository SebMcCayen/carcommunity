import XCTest
@testable import KCC

final class PlaceNavigationTests: XCTestCase {
    func testDifferentDestinationsReplaceTheReadingPanelIdentity() {
        let first = PlaceSuggestion(id: "same-provider-id", name: "First", address: nil,
            point: MapPoint(longitude: 12, latitude: 57))
        let updated = PlaceSuggestion(id: first.id, name: "Changed address", address: "New street",
            point: MapPoint(longitude: 13, latitude: 58))
        XCTAssertNotEqual(MapReadingSelection.place(first), .place(updated))
    }
}
