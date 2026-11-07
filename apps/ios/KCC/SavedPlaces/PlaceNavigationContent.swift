import SwiftUI

/// Search selection previews a destination; only the explicit navigation
/// action starts in-app guidance. Preview alone makes no route/location request.
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
            Text("navigation.inAppHint")
                .font(.footnote)
                .foregroundStyle(KccTheme.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
