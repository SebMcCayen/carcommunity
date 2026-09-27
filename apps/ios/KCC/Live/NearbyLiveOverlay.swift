import SwiftUI

/// On-screen-only public live sharers. Convoy members use the separate green
/// awareness layer (including edge arrows); nearby strangers use a purple ring
/// and disappear outside the viewport to keep the map quiet and privacy-minded.
struct NearbyLiveOverlay: View {
    @Bindable var coordinator: NearbyLiveCoordinator
    let projection: any MapProjection
    @State private var selection: SelectedNearbySharer?

    var body: some View {
        GeometryReader { geometry in
            TimelineView(.periodic(from: .now, by: 30)) { context in
                ZStack {
                    ForEach(visible(size: geometry.size, now: context.date)) { item in
                        Button {
                            selection = SelectedNearbySharer(marker: item.marker)
                        } label: {
                            markerChip(item.marker)
                        }
                        .buttonStyle(.plain)
                        .frame(minWidth: 48, minHeight: 48)
                        .contentShape(Rectangle())
                        .accessibilityLabel(String.localizedStringWithFormat(
                            NSLocalizedString("nearby.liveSharerOnMap", comment: "Nearby live sharer"),
                            spokenName(item.marker)
                        ))
                        .position(x: item.point.x, y: item.point.y)
                    }
                }
                .animation(.linear(duration: 1.2), value: coordinator.positions)
            }
        }
        .accessibilityElement(children: .contain)
        .sheet(item: $selection) { selected in
            NearbySharerCallout(
                marker: selected.marker,
                imageURL: selected.marker.imagePath.flatMap { coordinator.imageURLs[$0] }
            )
            .presentationDetents([.height(220)])
        }
    }

    private func visible(size: CGSize, now: Date) -> [NearbyLivePlacement] {
        guard projection.cameraSnapshot != nil, size.width > 0, size.height > 0 else { return [] }
        let margin = 24.0
        return coordinator.visibleMarkers(at: now).compactMap { marker in
            guard let point = projection.screenPositionFor(
                latitude: marker.latitude,
                longitude: marker.longitude
            ), point.trustworthy,
            point.x >= margin, point.y >= margin,
            point.x <= Double(size.width) - margin,
            point.y <= Double(size.height) - margin
            else { return nil }
            return NearbyLivePlacement(
                marker: marker,
                point: CGPoint(x: CGFloat(point.x), y: CGFloat(point.y))
            )
        }
    }

    private func markerChip(_ marker: LiveMarker) -> some View {
        let url = marker.imagePath.flatMap { coordinator.imageURLs[$0] }
        return ZStack {
            Circle().fill(Color.purple)
            if let url {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Image(systemName: "car.side.fill").foregroundStyle(.white)
                }
            } else {
                Image(systemName: "car.side.fill").foregroundStyle(.white)
            }
        }
        .font(.system(size: 18, weight: .semibold))
        .frame(width: 40, height: 40)
        .clipShape(Circle())
        .overlay(Circle().stroke(.white, lineWidth: 2))
        .shadow(radius: 2, y: 1)
    }

    private func spokenName(_ marker: LiveMarker) -> String {
        marker.displayName ?? String(localized: "nearby.unknownSharer")
    }
}

private struct NearbyLivePlacement: Identifiable {
    let marker: LiveMarker
    let point: CGPoint
    var id: String { marker.uid }
}

private struct SelectedNearbySharer: Identifiable {
    let marker: LiveMarker
    var id: String { marker.uid }
}

private struct NearbySharerCallout: View {
    @Environment(\.dismiss) private var dismiss
    let marker: LiveMarker
    let imageURL: URL?

    var body: some View {
        VStack(spacing: KccSpacing.s3) {
            HStack(spacing: KccSpacing.s3) {
                ZStack {
                    Circle().fill(Color.purple)
                    if let imageURL {
                        AsyncImage(url: imageURL) { image in
                            image.resizable().scaledToFill()
                        } placeholder: {
                            Image(systemName: "car.side.fill").foregroundStyle(.white)
                        }
                    } else {
                        Image(systemName: "car.side.fill").foregroundStyle(.white)
                    }
                }
                .frame(width: 52, height: 52)
                .clipShape(Circle())
                VStack(alignment: .leading, spacing: KccSpacing.s1) {
                    Text(marker.displayName ?? String(localized: "nearby.unknownSharer"))
                        .font(.headline)
                    if let recordedAt = marker.recordedAt {
                        Text("\(String(localized: "liveLocation.lastUpdated")): \(recordedAt.formatted(.relative(presentation: .named)))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
            }
            Button("nearby.sharerPopupClose") { dismiss() }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(KccSpacing.s5)
        .accessibilityElement(children: .contain)
    }
}
