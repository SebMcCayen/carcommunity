import SwiftUI
import UIKit
import Observation

@MainActor
@Observable
final class RoadCameraMapCoordinator {
    @ObservationIgnored private let repository: (any RoadCameraRepository)?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var selectionGeneration = 0
    private(set) var cameras: [RoadCamera] = []
    private var catalogById: [String: RoadCamera] = [:]
    private(set) var locations: [RoadCameraLocation] = []
    private(set) var enabledTypes = Set<RoadCameraType>()
    private(set) var selectedCamera: RoadCamera?
    private(set) var selectedGroup: RoadCameraMapGroup?
    private(set) var photo: RoadCameraPhoto?
    private(set) var photoLoading = false
    private(set) var photoErrorKey: String?
    private(set) var catalogErrorKey: String?

    init(repository: (any RoadCameraRepository)?) { self.repository = repository }

    func refresh(enabledTypes: Set<RoadCameraType>) async {
        generation += 1
        let request = generation
        self.enabledTypes = enabledTypes
        if let selectedCamera, !enabledTypes.contains(selectedCamera.type) { dismiss() }
        guard !enabledTypes.isEmpty, let repository else {
            cameras = []
            catalogById = [:]
            locations = []
            return
        }
        do {
            let catalog = try await repository.catalog()
            guard !Task.isCancelled, request == generation else { return }
            cameras = catalog.cameras
            catalogById = Dictionary(uniqueKeysWithValues: catalog.cameras.map { ($0.id, $0) })
            locations = RoadCameraLocation.group(catalog.cameras)
            if let group = selectedGroup {
                let retained = group.views.compactMap { catalogById[$0.id] }
                selectedGroup = retained.isEmpty ? nil : RoadCameraMapGroup(sites: RoadCameraLocation.group(retained))
            }
            if let selectedCamera {
                if let current = catalogById[selectedCamera.id] { self.selectedCamera = current }
                else { dismiss() }
            }
            catalogErrorKey = nil
        } catch {
            guard !Task.isCancelled, request == generation else { return }
            catalogErrorKey = "roadCameras.catalogUnavailable"
        }
    }

    func stop() {
        generation += 1
        enabledTypes = []
        cameras = []
        catalogById = [:]
        locations = []
        dismiss()
    }

    func visibleCameras(center: MapCameraSnapshot?, radius: Double?) -> [RoadCamera] {
        visibleGroups(center: center, radius: radius).map(\.camera)
    }

    func visibleGroups(center: MapCameraSnapshot?, radius: Double?) -> [RoadCameraMapGroup] {
        guard let center else { return [] }
        // Same bounded local area as Android. Group before the 250-marker cap.
        let longitudeScale = cos(center.latitude * .pi / 180)
        let nearby: [RoadCameraLocation] = locations.filter { site in
            let camera = site.camera
            let north = (camera.latitude - center.latitude) * 111_000
            let east = (camera.longitude - center.longitude) * 111_000 * longitudeScale
            return enabledTypes.contains(camera.type) && hypot(north, east) <= 50_000
        }
        let ordered = nearby.map { RoadCameraMapGroup(sites: [$0]) }.sorted { lhs, rhs in
            let lhsDistance = hypot(lhs.camera.latitude - center.latitude, (lhs.camera.longitude - center.longitude) * longitudeScale)
            let rhsDistance = hypot(rhs.camera.latitude - center.latitude, (rhs.camera.longitude - center.longitude) * longitudeScale)
            return lhsDistance == rhsDistance ? lhs.id < rhs.id : lhsDistance < rhsDistance
        }
        return ordered
    }

    var selectedViews: [RoadCamera] {
        guard let selectedCamera else { return [] }
        if let selectedGroup {
            return selectedGroup.views.compactMap { catalogById[$0.id] }.filter { enabledTypes.contains($0.type) }
        }
        return locations.first { $0.views.contains { $0.id == selectedCamera.id } }?.views ?? [selectedCamera]
    }

    var selectedOpposingDirections: Bool {
        let views = selectedViews
        return views.count == 2 && views[0].type == .speed && RoadCameraLocation.group(views).count == 1
    }

    func selectDirection(_ camera: RoadCamera) {
        guard camera.id != selectedCamera?.id, selectedViews.contains(where: { $0.id == camera.id }) else { return }
        select(camera)
    }

    func select(_ camera: RoadCamera) {
        guard enabledTypes.contains(camera.type) else { return }
        if selectedGroup?.views.contains(where: { $0.id == camera.id }) != true { selectedGroup = nil }
        selectionGeneration += 1
        selectedCamera = camera
        photo = nil
        photoErrorKey = nil
        photoLoading = false
    }

    func selectGroup(_ group: RoadCameraMapGroup) {
        guard enabledTypes.contains(group.camera.type) else { return }
        selectedGroup = group
        select(group.camera)
    }

    func dismiss() {
        selectionGeneration += 1
        selectedCamera = nil
        selectedGroup = nil
        photo = nil
        photoErrorKey = nil
        photoLoading = false
    }

    /// This is called exclusively by the selected camera's reading content.
    func loadPhoto(cameraId: String) async {
        guard let camera = selectedCamera, camera.id == cameraId, camera.type != .speed,
              !photoLoading, let repository else { return }
        let selection = selectionGeneration
        photo = nil
        photoErrorKey = nil
        photoLoading = true
        defer {
            if selectedCamera?.id == cameraId, selectionGeneration == selection { photoLoading = false }
        }
        do {
            let result = try await repository.photo(cameraId: cameraId)
            // An already-started fetch may complete after SwiftUI cancels its
            // task. The selection token, not task liveness, owns this UI state.
            guard selectedCamera?.id == cameraId, selectionGeneration == selection else { return }
            guard RoadCameraCachePolicy.isSafePhoto(result), UIImage(data: result.data) != nil else {
                throw KccFunctionsError(code: .internalError)
            }
            photo = result
        } catch {
            guard selectedCamera?.id == cameraId, selectionGeneration == selection else { return }
            photoErrorKey = (error as? KccFunctionsError)?.code == .resourceExhausted
                ? "roadCameras.rateLimited" : "roadCameras.unavailable"
        }
    }
}

struct RoadCameraMapOverlay: View {
    @Bindable var coordinator: RoadCameraMapCoordinator
    let surface: any MapSurface
    let onSelect: (RoadCamera) -> Void

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                ForEach(coordinator.visibleGroups(center: surface.cameraSnapshot, radius: surface.visibleRadiusMeters())) { group in
                    let camera = group.camera
                    if let point = surface.screenPositionFor(latitude: camera.latitude, longitude: camera.longitude),
                       point.trustworthy, point.x >= -30, point.y >= -30,
                       point.x <= Double(geometry.size.width + 30), point.y <= Double(geometry.size.height + 30) {
                        Button { coordinator.selectGroup(group); onSelect(camera) } label: {
                            RoadCameraGroupBadge(group: group)
                        }
                        .buttonStyle(.plain)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                        .accessibilityLabel(cameraAccessibilityLabel(group))
                        .accessibilityIdentifier("map.roadCamera." + camera.id)
                        .position(x: CGFloat(point.x), y: CGFloat(point.y))
                    }
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
        }
    }

    private func cameraAccessibilityLabel(_ group: RoadCameraMapGroup) -> Text {
        var label = NSLocalizedString(group.camera.type.titleKey, comment: "") + ", " + group.camera.name
        if group.views.count > 1 {
            label += ", " + String(format: NSLocalizedString("roadCameras.groupCount", comment: ""), group.views.count)
        }
        if group.opposingDirections { label += ", " + NSLocalizedString("roadCameras.bothDirections", comment: "") }
        return Text(label)
    }
}

struct RoadCameraGroupBadge: View {
    let group: RoadCameraMapGroup

    var body: some View {
        if group.views.count == 1 {
            IncidentMarkerBadge(marker: group.camera.marker)
        } else {
            HStack(spacing: 4) {
                if group.opposingDirections { Image(systemName: "arrow.down") }
                Group {
                    if let asset = group.camera.type.assetName {
                        Image(asset).renderingMode(.template).resizable().scaledToFit()
                    } else {
                        Image(systemName: group.camera.type.symbol).resizable().scaledToFit()
                    }
                }.frame(width: 22, height: 22)
                if group.opposingDirections { Image(systemName: "arrow.up") }
                Text(group.views.count.formatted()).monospacedDigit()
            }
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(roadCameraGroupColor(group.camera.type == .speed ? 0xFF040211 : group.camera.type.color))
            .padding(.horizontal, 8).frame(height: 36)
            .background(.white, in: Capsule())
            .overlay { Capsule().strokeBorder(roadCameraGroupColor(0xFF040211), lineWidth: 1) }
            .accessibilityHidden(true)
        }
    }
}

private func roadCameraGroupColor(_ argb: UInt32) -> Color {
    Color(red: Double((argb >> 16) & 0xFF) / 255,
          green: Double((argb >> 8) & 0xFF) / 255,
          blue: Double(argb & 0xFF) / 255,
          opacity: Double((argb >> 24) & 0xFF) / 255)
}

struct RoadCameraDetailsContent: View {
    @Bindable var coordinator: RoadCameraMapCoordinator
    let camera: RoadCamera
    var onZoomIn: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text(camera.name).font(.headline)
            if coordinator.selectedViews.count > 1 {
                Text(String(format: NSLocalizedString("roadCameras.groupCount", comment: ""), coordinator.selectedViews.count))
                    .font(.subheadline.weight(.semibold))
                if coordinator.selectedOpposingDirections {
                    Text("roadCameras.bothDirections")
                }
                Text("roadCameras.zoomHint").font(.footnote).foregroundStyle(KccTheme.secondary)
                if let onZoomIn, (coordinator.selectedGroup?.sites.count ?? 1) > 1 {
                    Button("roadCameras.showCameras", action: onZoomIn).frame(minHeight: 44)
                        .accessibilityIdentifier("roadCamera.zoomGroup")
                }
                Text("roadCameras.chooseDirection").font(.subheadline)
                ViewThatFits(in: .horizontal) {
                    directionButtons(horizontal: true)
                    directionButtons(horizontal: false)
                }
            }
            Text("incidents.sourceTrafikverket").font(.footnote).foregroundStyle(KccTheme.secondary)
            if camera.type != .speed {
                if let photo = coordinator.photo, let image = UIImage(data: photo.data) {
                    Image(uiImage: image).resizable().scaledToFit()
                        .clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
                        .accessibilityLabel(Text(LocalizedStringKey(camera.type.titleKey)))
                    if let timestamp = photo.photoTime {
                        Text(photoTimeLabel(timestamp)).font(.footnote).foregroundStyle(KccTheme.secondary)
                    }
                } else if coordinator.photoLoading {
                    ProgressView("roadCameras.loading").frame(maxWidth: .infinity, minHeight: 120)
                } else if let key = coordinator.photoErrorKey {
                    Text(LocalizedStringKey(key)).foregroundStyle(KccTheme.secondary)
                    Button("roadCameras.retry") {
                        Task { await coordinator.loadPhoto(cameraId: camera.id) }
                    }
                    .frame(minHeight: 44)
                }
            }
        }
        .task(id: camera.id) { await coordinator.loadPhoto(cameraId: camera.id) }
    }

    @ViewBuilder
    private func directionButtons(horizontal: Bool) -> some View {
        let layout = horizontal ? AnyLayout(HStackLayout(spacing: KccSpacing.s2))
            : AnyLayout(VStackLayout(alignment: .leading, spacing: KccSpacing.s2))
        layout {
            ForEach(coordinator.selectedViews) { view in
                Button { coordinator.selectDirection(view) } label: {
                    HStack {
                        if view.id == camera.id { Image(systemName: "checkmark") }
                        Text(directionLabel(view))
                    }
                    .padding(.horizontal, KccSpacing.s2).frame(minHeight: 44)
                }
                .buttonStyle(.bordered)
                .tint(view.id == camera.id ? KccTheme.driver : KccTheme.secondary)
                .accessibilityAddTraits(view.id == camera.id ? .isSelected : [])
                .accessibilityIdentifier("roadCamera.direction." + view.id)
            }
        }
    }

    private func directionLabel(_ camera: RoadCamera) -> String {
        let label = camera.viewLabel(in: coordinator.selectedViews)
        return coordinator.selectedViews.contains { $0.name != camera.name } ? camera.name + " · " + label : label
    }

    private func photoTimeLabel(_ timestamp: String) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        var date = formatter.date(from: timestamp)
        if date == nil {
            formatter.formatOptions = [.withInternetDateTime]
            date = formatter.date(from: timestamp)
        }
        let display = date?.formatted(date: .abbreviated, time: .shortened) ?? timestamp
        return String(format: NSLocalizedString("roadCameras.photoTime", comment: ""), display)
    }
}
