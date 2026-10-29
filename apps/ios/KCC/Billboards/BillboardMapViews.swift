import SwiftUI

struct BillboardMapOverlay: View {
    @Environment(\.openURL) private var openURL
    @Bindable var coordinator: BillboardsCoordinator
    let projection: any MapProjection

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .bottom) {
                ForEach(coordinator.markers, id: \.id) { marker in
                    if let point = projection.screenPositionFor(
                        latitude: marker.latitude,
                        longitude: marker.longitude
                    ), point.trustworthy,
                       point.x >= -32, point.y >= -32,
                       point.x <= Double(geometry.size.width + 32),
                       point.y <= Double(geometry.size.height + 32) {
                        Button { coordinator.open(billboardId: marker.id) } label: {
                            BillboardMarker()
                        }
                        .buttonStyle(.plain)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                        .accessibilityLabel(Text("billboard.sponsoredLabel"))
                        .position(x: CGFloat(point.x), y: CGFloat(point.y))
                    }
                }

                if let billboard = coordinator.selectedBillboard {
                    BillboardMapPopup(
                        billboard: billboard,
                        action: BillboardCallToAction.resolve(billboard),
                        onAction: { action in
                            coordinator.recordAction(action, for: billboard)
                            openURL(action.url)
                            coordinator.dismiss()
                        },
                        onDismiss: coordinator.dismiss
                    )
                    .frame(maxHeight: min(420, geometry.size.height * 0.65))
                    .padding(KccSpacing.s4)
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height, alignment: .bottom)
        }
    }
}

private struct BillboardMarker: View {
    var body: some View {
        VStack(spacing: 0) {
            RoundedRectangle(cornerRadius: 5)
                .fill(Color(red: 0.68, green: 0.08, blue: 0.34))
                .frame(width: 34, height: 23)
                .overlay {
                    VStack(alignment: .leading, spacing: 4) {
                        Capsule().fill(.white).frame(width: 18, height: 3)
                        Capsule().fill(.white).frame(width: 12, height: 3)
                    }
                }
                .overlay(RoundedRectangle(cornerRadius: 5).stroke(.white.opacity(0.9), lineWidth: 1.5))
                .shadow(radius: 2, y: 1)
            Capsule().fill(.white).frame(width: 4, height: 9)
        }
    }
}

private struct BillboardMapPopup: View {
    let billboard: Billboard
    let action: BillboardAction?
    let onAction: (BillboardAction) -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            HStack {
                Text("billboard.sponsoredLabel")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer()
                Button(action: onDismiss) {
                    Image(systemName: "xmark").frame(width: 48, height: 48)
                }
                .accessibilityLabel(Text("billboard.mapPopupClose"))
            }
            ScrollView {
                VStack(alignment: .leading, spacing: KccSpacing.s2) {
                    PublicContentReportButton(target: PublicContentTarget(surface: "billboard", itemID: billboard.id))
                    Text(billboard.headline).font(.headline)
                    if let message = billboard.message { Text(message).foregroundStyle(.secondary) }
                    if let action {
                        Button { onAction(action) } label: {
                            Text(LocalizedStringKey(action.labelKey))
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
            }
        }
        .padding(KccSpacing.s4)
        .frame(maxWidth: 360, alignment: .leading)
        .background(KccTheme.surface, in: RoundedRectangle(cornerRadius: 16))
        .shadow(radius: 6, y: 2)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("billboard-map-popup")
    }
}
