import SwiftUI
#if DEBUG
import FirebaseCore

/// Synthetic search and URL handling only for credential-free Debug UI tests.
/// A configured Firebase app can never opt into this fixture.
enum ConfiglessPlaceSearchFixture {
    static var isEnabled: Bool {
        FirebaseApp.app() == nil && ProcessInfo.processInfo.arguments.contains("-KCCUITestPlaceSearch")
    }

    static let places = [
        PlaceSuggestion(id: "fixture-gothenburg", name: "Göteborg Centralstation",
            address: "Drottningtorget, Göteborg", point: MapPoint(longitude: 11.973, latitude: 57.708)),
        PlaceSuggestion(id: "fixture-varberg", name: "Varberg station",
            address: "Stationsgatan, Varberg", point: MapPoint(longitude: 12.249, latitude: 57.109))
    ]

    static var savedPlaces: [SavedPlace] {
        SavedPlacesPolicy.create(kind: .home, place: places[0], label: "Home").map { [$0] } ?? []
    }
}

struct ConfiglessPlaceSearchClient: AddressSearchClient {
    func search(query: String, proximity: MapPoint?) async throws -> [PlaceSuggestion] {
        ConfiglessPlaceSearchFixture.places
    }
}
#endif

struct ConfiglessPlaceNavigationHandoff: ViewModifier {
    #if DEBUG
    @State private var openedURL: URL?
    #endif

    @ViewBuilder func body(content: Content) -> some View {
        #if DEBUG
        if ConfiglessPlaceSearchFixture.isEnabled {
            content
                .environment(\.openURL, OpenURLAction { url in
                    if ProcessInfo.processInfo.arguments.contains("-KCCUITestRejectMaps") { return .discarded }
                    openedURL = url
                    return .handled
                })
                .overlay(alignment: .top) {
                    if let openedURL {
                        Text(openedURL.absoluteString).font(.caption)
                            .accessibilityIdentifier("place.fixture.openedURL")
                    }
                }
        } else { content }
        #else
        content
        #endif
    }
}
