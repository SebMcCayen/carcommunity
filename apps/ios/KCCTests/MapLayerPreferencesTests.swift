import XCTest

@testable import KCC

@MainActor
final class MapLayerPreferencesTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "MapLayerPreferencesTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        defaults.removePersistentDomain(forName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    func testDefaultsMatchExistingMapExperience() {
        let preferences = MapLayerPreferences(defaults: defaults)
        XCTAssertFalse(preferences.trafficEnabled)
        XCTAssertTrue(preferences.trafficAlertsEnabled)
        XCTAssertNil(preferences.mapModeOverride)
        XCTAssertTrue(preferences.is3D)
        XCTAssertEqual(preferences.browsingZoom, 16, accuracy: 1e-9)
        XCTAssertEqual(preferences.effectiveMapMode(systemIsDark: false), .day)
        XCTAssertEqual(preferences.effectiveMapMode(systemIsDark: true), .night)
    }

    func testEveryChoiceSurvivesStoreRecreation() {
        let preferences = MapLayerPreferences(defaults: defaults)
        preferences.setTrafficEnabled(true)
        preferences.setTrafficAlertsEnabled(false)
        preferences.setMapModeOverride(.night)
        preferences.set3DEnabled(false)
        preferences.setBrowsingZoom(13.7)

        let restored = MapLayerPreferences(defaults: defaults)
        XCTAssertTrue(restored.trafficEnabled)
        XCTAssertFalse(restored.trafficAlertsEnabled)
        XCTAssertEqual(restored.mapModeOverride, .night)
        XCTAssertFalse(restored.is3D)
        XCTAssertEqual(restored.browsingZoom, 13.5, accuracy: 1e-9)
    }

    func testClearingModeOverrideReturnsToSystemAppearance() {
        let preferences = MapLayerPreferences(defaults: defaults)
        preferences.setMapModeOverride(.day)
        preferences.setMapModeOverride(nil)

        let restored = MapLayerPreferences(defaults: defaults)
        XCTAssertNil(restored.mapModeOverride)
        XCTAssertEqual(restored.effectiveMapMode(systemIsDark: true), .night)
    }

    func testUnknownStoredModeSafelyFallsBackToSystemAppearance() {
        XCTAssertNil(MapLayerPreferences.decodeMapMode(nil))
        XCTAssertNil(MapLayerPreferences.decodeMapMode("Dusk"))
        XCTAssertEqual(MapLayerPreferences.decodeMapMode("Day"), .day)
        XCTAssertEqual(MapLayerPreferences.decodeMapMode("Night"), .night)
    }

    func testBrowsingZoomClampsAndSnapsToHalfSteps() {
        XCTAssertEqual(MapBrowsingZoom.snap(11), 12, accuracy: 1e-9)
        XCTAssertEqual(MapBrowsingZoom.snap(19), 18, accuracy: 1e-9)
        XCTAssertEqual(MapBrowsingZoom.snap(15.24), 15, accuracy: 1e-9)
        XCTAssertEqual(MapBrowsingZoom.snap(15.26), 15.5, accuracy: 1e-9)
        XCTAssertEqual(MapBrowsingZoom.fromStored(.infinity), 16, accuracy: 1e-9)
        XCTAssertEqual(MapBrowsingZoom.fromStored(nil), 16, accuracy: 1e-9)
    }

    func testTrafficPaletteMatchesAndroidDayAndNightRamps() {
        XCTAssertEqual(MapTrafficPalette.day.low, 0xFF4CAF50)
        XCTAssertEqual(MapTrafficPalette.day.severe, 0xFFD32F2F)
        XCTAssertEqual(MapTrafficPalette.night.moderate, 0xFFC9A227)
        XCTAssertEqual(MapTrafficPalette.night.heavy, 0xFFC20017)
        XCTAssertEqual(MapTrafficPalette.lineWidth(for: .day), 2.5)
        XCTAssertEqual(MapTrafficPalette.lineWidth(for: .night), 3.5)
    }
}
