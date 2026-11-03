import SwiftUI

/// Search selection previews a destination; only the explicit navigation
/// action hands it to Maps. No route request or location subscription is made.
struct PlaceNavigationContent: View {
    let place: PlaceSuggestion
    let onNavigate: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text(place.name)
                .font(.headline)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("place.navigation.name")
            if let address = place.secondaryText {
                Text(address)
                    .foregroundStyle(KccTheme.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button(action: onNavigate) {
                Label("shell.placeMenuNavigate", systemImage: "arrow.triangle.turn.up.right.diamond.fill")
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .kccPrimaryAction()
            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            .accessibilityLabel(Text("shell.placeMenuNavigate"))
            .accessibilityIdentifier("place.navigation.start")
            Text("addressSearch.opensInAppleMaps")
                .font(.footnote)
                .foregroundStyle(KccTheme.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

enum PlaceNavigationDestination {
    static func drivingURL(to point: MapPoint) -> URL? {
        guard SavedPlacesPolicy.isValid(point: point) else { return nil }
        var components = URLComponents()
        components.scheme = "https"
        components.host = "maps.apple.com"
        components.path = "/"
        components.queryItems = [
            URLQueryItem(name: "daddr", value: "\(point.latitude),\(point.longitude)"),
            URLQueryItem(name: "dirflg", value: "d")
        ]
        return components.url
    }
}
