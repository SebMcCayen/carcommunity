import SwiftUI

/// On-screen-only public live sharers. Convoy members use the separate green
/// awareness layer (including edge arrows); nearby sharers use a cyan ring
/// and disappear outside the viewport to keep the map quiet and privacy-minded.
struct NearbyLiveOverlay: View {
    @Bindable var coordinator: NearbyLiveCoordinator
    let projection: any MapProjection
    let onSelect: (String) -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geometry in
            TimelineView(.periodic(from: .now, by: 30)) { context in
                ZStack {
                    ForEach(visible(size: geometry.size, now: context.date)) { item in
                        Button {
                            onSelect(item.marker.uid)
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
                .animation(reduceMotion ? nil : .linear(duration: 1.2), value: coordinator.positions)
            }
        }
        .accessibilityElement(children: .contain)
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
            Circle().fill(KccTheme.driver)
            if let url {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Image(systemName: "car.side.fill").foregroundStyle(KccTheme.onDriver)
                }
            } else {
                Image(systemName: "car.side.fill").foregroundStyle(KccTheme.onDriver)
            }
        }
        .font(.system(size: 18, weight: .semibold))
        .frame(width: 40, height: 40)
        .clipShape(Circle())
        .overlay(Circle().stroke(KccTheme.driver, lineWidth: 2))
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

/// Re-resolve from the authorized, freshness-filtered roster every render/tick.
/// Keeping only the uid in shell selection cannot retain a stopped/blocked user's
/// old snapshot while their marker has already disappeared from the map.
struct NearbySharerDetail: View {
    @Bindable var coordinator: NearbyLiveCoordinator
    let uid: String
    let onUnavailable: () -> Void

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let marker = coordinator.visibleMarkers(at: context.date).first { $0.uid == uid }
            Group {
                if let marker {
                    NearbySharerIdentity(
                        marker: marker,
                        imageURL: marker.imagePath.flatMap { coordinator.imageURLs[$0] }
                    )
                }
            }
            .onChange(of: marker == nil, initial: true) { _, unavailable in
                if unavailable { onUnavailable() }
            }
        }
    }
}

struct NearbySharerIdentity: View {
    let marker: LiveMarker
    let imageURL: URL?

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            if let imageURL {
                AsyncImage(url: imageURL) { phase in
                    if let image = phase.image { image.resizable().scaledToFill() }
                    else { Image(systemName: "car.side.fill").foregroundStyle(KccTheme.driver) }
                }
                .frame(width: 64, height: 64).clipShape(Circle())
                .overlay(Circle().strokeBorder(KccTheme.driver, lineWidth: 2).allowsHitTesting(false))
                .accessibilityHidden(true)
            } else {
                Image(systemName: "car.side.fill").font(.title2)
                    .foregroundStyle(KccTheme.driver)
                    .frame(width: 64, height: 64)
                    .background(KccTheme.subtle, in: Circle())
                    .accessibilityHidden(true)
            }
            Text(marker.displayName ?? String(localized: "nearby.unknownSharer"))
                .font(.title3.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
            if let recordedAt = marker.recordedAt {
                Text("\(String(localized: "liveLocation.lastUpdated")): \(recordedAt.formatted(.relative(presentation: .named)))")
                    .font(.subheadline).foregroundStyle(KccTheme.secondary)
            }
        }
        .accessibilityElement(children: .contain)
    }
}
