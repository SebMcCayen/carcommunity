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
        XCTAssertEqual(preferences.incidentMaxAge, .all)
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

    func testConfirmedNewAccountGetsOneWeekPersistedAcrossRestart() {
        let preferences = MapLayerPreferences(defaults: defaults)
        preferences.initializeIncidentAgeForSignIn(isNewUser: true)
        XCTAssertEqual(preferences.incidentMaxAge, .week1)
        XCTAssertEqual(defaults.string(forKey: "map.layers.incidentMaxAge"), "WEEK_1")
        XCTAssertEqual(MapLayerPreferences(defaults: defaults).incidentMaxAge, .week1)
    }

    func testExistingAccountWithoutPreferenceKeepsAllEvenOnFreshInstall() {
        let preferences = MapLayerPreferences(defaults: defaults)
        preferences.initializeIncidentAgeForSignIn(isNewUser: false)
        XCTAssertEqual(preferences.incidentMaxAge, .all)
        XCTAssertNil(defaults.object(forKey: "map.layers.incidentMaxAge"))
    }

    func testInitializationPreservesEverySavedChoiceIncludingAll() {
        for option in IncidentAgeOption.allCases {
            let preferences = MapLayerPreferences(defaults: defaults)
            preferences.setIncidentMaxAge(option)
            preferences.initializeIncidentAgeForSignIn(isNewUser: true)
            preferences.initializeIncidentAgeForSignIn(isNewUser: false)
            XCTAssertEqual(preferences.incidentMaxAge, option)
            XCTAssertEqual(defaults.string(forKey: "map.layers.incidentMaxAge"), option.rawValue)
            XCTAssertEqual(MapLayerPreferences(defaults: defaults).incidentMaxAge, option)
        }
    }

    func testUserCanChangeNewDefaultAndRepeatedInitializationDoesNotResetIt() {
        let preferences = MapLayerPreferences(defaults: defaults)
        preferences.initializeIncidentAgeForSignIn(isNewUser: true)
        preferences.setIncidentMaxAge(.all)
        let restored = MapLayerPreferences(defaults: defaults)
        restored.initializeIncidentAgeForSignIn(isNewUser: true)
        XCTAssertEqual(restored.incidentMaxAge, .all)
    }

    func testClearingModeOverrideReturnsToSystemAppearance() {
        let preferences = MapLayerPreferences(defaults: defaults)
        preferences.setMapModeOverride(.day)
        preferences.setMapModeOverride(nil)

        let restored = MapLayerPreferences(defaults: defaults)
        XCTAssertNil(restored.mapModeOverride)
        XCTAssertEqual(restored.effectiveMapMode(systemIsDark: true), .night)
    }

    func testAppearanceSelectionAppliesExplicitChoiceImmediately() {
        let preferences = MapLayerPreferences(defaults: defaults)
        XCTAssertEqual(preferences.selectMapAppearance(.night, systemIsDark: false), .night)
        XCTAssertEqual(preferences.selectMapAppearance(.day, systemIsDark: true), .day)
        XCTAssertEqual(MapLayerPreferences(defaults: defaults).mapModeOverride, .day)
    }

    func testSystemAppearanceSelectionClearsManualModeAndFollowsChanges() {
        let preferences = MapLayerPreferences(defaults: defaults)
        preferences.setMapModeOverride(.night)
        XCTAssertEqual(preferences.selectMapAppearance(nil, systemIsDark: false), .day)
        XCTAssertNil(MapLayerPreferences(defaults: defaults).mapModeOverride)
        XCTAssertEqual(preferences.effectiveMapMode(systemIsDark: true), .night)
        // Selecting System again still supplies the current mode to the renderer.
        XCTAssertEqual(preferences.selectMapAppearance(nil, systemIsDark: true), .night)
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
