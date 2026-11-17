import XCTest
@testable import KCC

final class MapDensityPolicyTests: XCTestCase {
    private func item(_ id: String, offset: Double = 0, count: Int = 1, critical: Bool = false, selected: Bool = false) -> MapDensityItem {
        MapDensityItem(id: id, latitude: 57, longitude: 12 + offset, count: count, critical: critical, selected: selected)
    }
    func testMixedTypesKeepAllMembersAndCriticalStatus() {
        let input = [item("camera", count: 2), item("weather"), item("crown"), item("accident", critical: true)]
        let groups = MapDensityPolicy.groups(input, zoomBucket: 15)
        XCTAssertEqual(groups.count, 1)
        XCTAssertEqual(Set(groups[0].members.map(\.id)), Set(input.map(\.id)))
        XCTAssertEqual(groups[0].count, 5)
        XCTAssertTrue(groups[0].critical)
        XCTAssertEqual(groups[0].anchor.id, "accident")
    }
    func testSelectionDoesNotReplaceGroupIdentityOrMembership() {
        let source = [item("camera"), item("crown"), item("hazard", critical: true)]
        let original = MapDensityPolicy.groups(source, zoomBucket: 15)[0]
        var selectedSource = source
        selectedSource[1].selected = true
        let selected = MapDensityPolicy.groups(selectedSource, zoomBucket: 15)[0]
        XCTAssertEqual(original.id, selected.id)
        XCTAssertEqual(original.members.map(\.id), selected.members.map(\.id))
        XCTAssertEqual(selected.anchor.id, "crown")
        XCTAssertTrue(selected.selected && selected.critical)
    }
    func testDenseCrownsAndCatalogReorderingKeepEveryID() {
        let source = (1...1_000).map { item("crown-\($0)") }
        let group = MapDensityPolicy.groups(source, zoomBucket: 18)[0]
        XCTAssertEqual(group.members.count, 1_000)
        XCTAssertEqual(group.compactCount, "99+")
        XCTAssertEqual(MapDensityPolicy.groups(source, zoomBucket: 18), MapDensityPolicy.groups(Array(source.reversed()), zoomBucket: 18))
    }
    func testZoomBoundaryHysteresisAndDetailReturn() {
        var zoom = MapDensityZoom()
        XCTAssertEqual(zoom.update(15.99), 15)
        XCTAssertEqual(zoom.update(16.01), 15)
        XCTAssertEqual(zoom.update(16.2), 16)
        XCTAssertEqual(zoom.update(15.99), 16)
        XCTAssertEqual(zoom.update(15.8), 15)
        let source = [item("a"), item("b", offset: 0.001)]
        XCTAssertGreaterThan(MapDensityPolicy.groups(source, zoomBucket: 18).count, MapDensityPolicy.groups(source, zoomBucket: 13).count)
    }
    func testNoProximityChainAndNoInvalidCoordinateCrash() {
        let degreePerPixel = 360 / (512 * pow(2, 15.0))
        let source = [item("a"), item("b", offset: degreePerPixel * 50), item("c", offset: degreePerPixel * 100)]
        XCTAssertEqual(MapDensityPolicy.groups(source, zoomBucket: 15).count, 2)
        let bad = MapDensityItem(id: "bad", latitude: .nan, longitude: 12)
        XCTAssertEqual(MapDensityPolicy.groups([item("valid"), bad], zoomBucket: 15).flatMap(\.members).map(\.id), ["valid"])
    }
    func testBadgeUsesActualOwnPointAndLeavesPannedCameraAlone() {
        let own = MapDensityScreenPoint(x: 120, y: 300)
        let moved = MapDensityPolicy.badgePosition(anchor: own, own: own, width: 390, height: 844)
        XCTAssertGreaterThanOrEqual(hypot(moved.x - own.x, moved.y - own.y), 43.99)
        XCTAssertLessThanOrEqual(hypot(moved.x - own.x, moved.y - own.y), 88)
        let panned = MapDensityScreenPoint(x: 260, y: 300)
        XCTAssertEqual(MapDensityPolicy.badgePosition(anchor: panned, own: own, width: 390, height: 844), panned)
    }
}
