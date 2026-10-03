import XCTest

@testable import KCC

@MainActor
final class SavedPlacesTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "SavedPlacesTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        defaults.removePersistentDomain(forName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    func testHomeAndWorkAreSingletonsSortedBeforeFavourites() throws {
        let firstHome = try XCTUnwrap(makeSaved(.home, id: "one", latitude: 57))
        let favourite = try XCTUnwrap(makeSaved(.favourite, id: "cafe", latitude: 58))
        let secondHome = try XCTUnwrap(makeSaved(.home, id: "two", latitude: 59))

        let places = SavedPlacesPolicy.upsert(
            secondHome,
            into: SavedPlacesPolicy.upsert(favourite, into: [firstHome])
        )

        XCTAssertEqual(places.map(\.id), ["home", "fav:cafe"])
        XCTAssertEqual(places.first?.place.point.latitude, 59)
    }

    func testCapEvictsOldestFavouriteWithoutDroppingHomeOrWork() throws {
        var places = [
            try XCTUnwrap(makeSaved(.home, id: "h", latitude: 50)),
            try XCTUnwrap(makeSaved(.work, id: "w", latitude: 51))
        ]
        for index in 0..<5 {
            let saved = try XCTUnwrap(makeSaved(.favourite, id: "f\(index)", latitude: 52 + Double(index)))
            places = SavedPlacesPolicy.upsert(saved, into: places)
        }

        XCTAssertEqual(places.count, 6)
        XCTAssertTrue(places.contains { $0.id == "home" })
        XCTAssertTrue(places.contains { $0.id == "work" })
        XCTAssertFalse(places.contains { $0.id == "fav:f0" })
        XCTAssertTrue(places.contains { $0.id == "fav:f4" })
    }

    func testRejectsInvalidCoordinatesAndBoundsLabels() {
        let invalid = PlaceSuggestion(
            id: "bad",
            name: "Bad",
            address: nil,
            point: MapPoint(longitude: 12, latitude: .nan)
        )
        XCTAssertNil(SavedPlacesPolicy.create(kind: .favourite, place: invalid, label: "Bad"))

        let saved = SavedPlacesPolicy.create(
            kind: .favourite,
            place: suggestion(id: "ok", latitude: 57),
            label: String(repeating: "a", count: 100)
        )
        XCTAssertEqual(saved?.label.count, SavedPlacesPolicy.maximumLabelLength)
    }

    func testQueryAndPersistedIdentityUseScalarBounds() throws {
        let combiningQuery = "a" + String(repeating: "\u{0301}", count: 500)
        XCTAssertEqual(
            SavedPlacesPolicy.normalizedQuery(combiningQuery).unicodeScalars.count,
            SavedPlacesPolicy.maximumQueryLength
        )

        let longID = String(repeating: "x", count: 500)
        let saved = try XCTUnwrap(makeSaved(.favourite, id: longID, latitude: 57))
        XCTAssertEqual(saved.place.id.unicodeScalars.count, 256)
        XCTAssertEqual(saved.id, "fav:\(saved.place.id)")
        XCTAssertEqual(SavedPlacesPolicy.normalize([saved]), [saved])
    }

    func testStoreIsIsolatedByAccountAndToleratesCorruptPayload() throws {
        let first = UserDefaultsSavedPlacesStore(uid: "member-a", defaults: defaults)
        let second = UserDefaultsSavedPlacesStore(uid: "member-b", defaults: defaults)
        let saved = try XCTUnwrap(makeSaved(.home, id: "home-address", latitude: 57))
        first.save([saved])

        XCTAssertEqual(first.load(), [saved])
        XCTAssertTrue(second.load().isEmpty)

        for (key, _) in defaults.dictionaryRepresentation() where key.hasPrefix("ios.savedPlaces.v1.") {
            defaults.set(Data("not-json".utf8), forKey: key)
        }
        XCTAssertTrue(first.load().isEmpty)
    }

    func testShareURLIsHTTPSAndKeepsCoordinatesInQuery() throws {
        let saved = try XCTUnwrap(makeSaved(.favourite, id: "lake", latitude: 57.49))
        let url = try XCTUnwrap(SavedPlaceShare.url(for: saved))
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))

        XCTAssertEqual(components.scheme, "https")
        XCTAssertEqual(components.host, "maps.apple.com")
        XCTAssertEqual(components.queryItems?.first(where: { $0.name == "ll" })?.value, "57.49,12.0")
    }

    func testShareURLBoundsDisplayNameByUnicodeScalars() throws {
        let scalarHeavyName = "a" + String(repeating: "\u{0301}", count: 400)
        let url = try XCTUnwrap(SavedPlaceShare.url(
            name: scalarHeavyName,
            point: MapPoint(longitude: 12, latitude: 57)
        ))
        let name = try XCTUnwrap(
            URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?
                .first(where: { $0.name == "q" })?
                .value
        )

        XCTAssertEqual(name.unicodeScalars.count, 160)
        XCTAssertEqual(name, SavedPlacesPolicy.bounded(scalarHeavyName, to: 160))
    }

    func testSearchBoxRequestIsBoundedAndUsesValidProximity() throws {
        let client = MapboxAddressSearchClient(token: "pk.test", language: "sv")
        let url = try XCTUnwrap(client.requestURL(
            query: String(repeating: "a", count: 500),
            proximity: MapPoint(longitude: 12.08, latitude: 57.49)
        ))
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        let items = try XCTUnwrap(components.queryItems)

        XCTAssertEqual(components.path, "/search/searchbox/v1/forward")
        XCTAssertEqual(
            items.first(where: { $0.name == "q" })?.value?.unicodeScalars.count,
            SavedPlacesPolicy.maximumQueryLength
        )
        XCTAssertEqual(items.first(where: { $0.name == "limit" })?.value, "6")
        XCTAssertEqual(items.first(where: { $0.name == "proximity" })?.value, "12.08,57.49")
        XCTAssertEqual(items.first(where: { $0.name == "language" })?.value, "sv")
        XCTAssertNil(items.first(where: { $0.name == "country" }))
        XCTAssertNil(items.first(where: { $0.name == "types" }))
        XCTAssertNil(items.first(where: { $0.name == "permanent" }))
        XCTAssertNil(items.first(where: { $0.name == "autocomplete" }))
    }

    func testSearchBoxRequestUsesHomeBiasWithoutLiveProximity() throws {
        let client = MapboxAddressSearchClient(token: "pk.test", language: "sv")
        let url = try XCTUnwrap(client.requestURL(query: "Kungsmässan", proximity: nil))
        let items = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)

        XCTAssertEqual(items.first(where: { $0.name == "proximity" })?.value, "12.073,57.4874")
        XCTAssertEqual(items.first(where: { $0.name == "country" })?.value, "SE")
    }

    func testSearchBoxDoesNotBuildTokenlessOrBlankRequests() {
        let unavailable = MapboxAddressSearchClient(token: "", language: "sv")
        let malformed = MapboxAddressSearchClient(token: "sk.secret", language: "sv")
        let available = MapboxAddressSearchClient(token: "pk.test", language: "sv")

        XCTAssertNil(unavailable.requestURL(query: "Kungsmässan", proximity: nil))
        XCTAssertNil(malformed.requestURL(query: "Kungsmässan", proximity: nil))
        XCTAssertNil(available.requestURL(query: "   ", proximity: nil))
    }

    func testSearchBoxDecodesPOIAndBusinessResult() throws {
        let data = Data("""
        {"type":"FeatureCollection","features":[{
          "type":"Feature",
          "geometry":{"type":"Point","coordinates":[12.0757,57.4874]},
          "properties":{
            "mapbox_id":"poi.kungsmassan",
            "feature_type":"poi",
            "name":"Kungsmässan",
            "full_address":"Borgmästaregatan 5, 434 32 Kungsbacka",
            "poi_category":["shopping_mall"]
          }
        }]}
        """.utf8)

        let decoded = try MapboxAddressSearchClient.decode(data: data)

        XCTAssertEqual(decoded, [PlaceSuggestion(
            id: "poi.kungsmassan",
            name: "Kungsmässan",
            address: "Borgmästaregatan 5, 434 32 Kungsbacka",
            point: MapPoint(longitude: 12.0757, latitude: 57.4874)
        )])
    }

    func testGeocoderDropsInvalidFeaturesAndCapsResults() throws {
        let features = (0..<8).map { index in
            """
            {"id":"feature.\(index)","geometry":{"coordinates":[12.0,57.\(index)]},"properties":{"name":"Place \(index)","mapbox_id":"id.\(index)"}}
            """
        } + [
            """
            {"id":"bad","geometry":{"coordinates":[999,999]},"properties":{"name":"Bad"}}
            """
        ]
        let data = Data("{\"features\":[\(features.joined(separator: ","))]}".utf8)

        let decoded = try MapboxAddressSearchClient.decode(data: data)

        XCTAssertEqual(decoded.count, SavedPlacesPolicy.maximumSearchResults)
        XCTAssertEqual(decoded.first?.name, "Place 0")
    }

    func testGeocoderDeduplicatesStableFeatureIdentity() throws {
        let data = Data("""
        {"features":[
          {"id":"feature.1","geometry":{"coordinates":[12,57]},"properties":{"name":"First","mapbox_id":"same"}},
          {"id":"feature.2","geometry":{"coordinates":[13,58]},"properties":{"name":"Duplicate","mapbox_id":"same"}}
        ]}
        """.utf8)

        let decoded = try MapboxAddressSearchClient.decode(data: data)

        XCTAssertEqual(decoded.map(\.name), ["First"])
    }

    func testGeocoderBoundsDecodedTextByUnicodeScalars() throws {
        let scalarHeavyText = "a" + String(repeating: "\u{0301}", count: 400)
        let payload: [String: Any] = [
            "features": [[
                "id": "fallback",
                "geometry": ["coordinates": [12, 57]],
                "properties": [
                    "mapbox_id": scalarHeavyText,
                    "name": scalarHeavyText,
                    "full_address": scalarHeavyText
                ]
            ]]
        ]
        let data = try JSONSerialization.data(withJSONObject: payload)

        let suggestion = try XCTUnwrap(MapboxAddressSearchClient.decode(data: data).first)

        XCTAssertEqual(suggestion.id.unicodeScalars.count, 256)
        XCTAssertEqual(suggestion.name.unicodeScalars.count, 160)
        XCTAssertEqual(suggestion.address?.unicodeScalars.count, 240)
        XCTAssertEqual(suggestion.id, SavedPlacesPolicy.bounded(scalarHeavyText, to: 256))
    }

    func testGeocoderRejectsOversizedResponseBeforeDecoding() {
        let data = Data(repeating: 0x20, count: MapboxAddressSearchClient.maximumResponseBytes + 1)

        XCTAssertThrowsError(try MapboxAddressSearchClient.decode(data: data)) { error in
            XCTAssertEqual(error as? AddressSearchError, .invalidResponse)
        }
    }

    func testRepointingFavouriteRemovesOldCoordinateIdentity() throws {
        let old = try XCTUnwrap(makeSaved(.favourite, id: "old", latitude: 57))
        let store = UserDefaultsSavedPlacesStore(uid: "member", defaults: defaults)
        store.save([old])
        let coordinator = SavedPlacesCoordinator(
            store: store,
            searchClient: UnavailableAddressSearchClient()
        )

        coordinator.save(
            kind: .favourite,
            place: suggestion(id: "new", latitude: 58),
            label: "New",
            replacingID: old.id
        )

        XCTAssertEqual(coordinator.places.map(\.id), ["fav:new"])
    }

    func testRepointingFavouriteAtCapacityOnlyReplacesThatFavourite() throws {
        let store = UserDefaultsSavedPlacesStore(uid: "member", defaults: defaults)
        let existing = try (0..<SavedPlacesPolicy.maximumCount).map { index in
            try XCTUnwrap(makeSaved(.favourite, id: "f\(index)", latitude: 50 + Double(index)))
        }
        store.save(existing)
        let coordinator = SavedPlacesCoordinator(
            store: store,
            searchClient: UnavailableAddressSearchClient()
        )

        coordinator.save(
            kind: .favourite,
            place: suggestion(id: "replacement", latitude: 60),
            label: "Replacement",
            replacingID: "fav:f5"
        )

        XCTAssertEqual(coordinator.places.count, SavedPlacesPolicy.maximumCount)
        XCTAssertTrue(coordinator.places.contains { $0.id == "fav:f0" })
        XCTAssertFalse(coordinator.places.contains { $0.id == "fav:f5" })
        XCTAssertTrue(coordinator.places.contains { $0.id == "fav:replacement" })
    }

    private func suggestion(id: String, latitude: Double) -> PlaceSuggestion {
        PlaceSuggestion(
            id: id,
            name: "Place \(id)",
            address: "Address \(id)",
            point: MapPoint(longitude: 12, latitude: latitude)
        )
    }

    private func makeSaved(
        _ kind: SavedPlaceKind,
        id: String,
        latitude: Double
    ) -> SavedPlace? {
        SavedPlacesPolicy.create(kind: kind, place: suggestion(id: id, latitude: latitude), label: id)
    }
}
