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
    private(set) var locations: [RoadCameraLocation] = []
    private(set) var enabledTypes = Set<RoadCameraType>()
    private(set) var selectedCamera: RoadCamera?
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
            locations = []
            return
        }
        do {
            let catalog = try await repository.catalog()
            guard !Task.isCancelled, request == generation else { return }
            cameras = catalog.cameras
            locations = RoadCameraLocation.group(catalog.cameras)
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
        locations = []
        dismiss()
    }

    func visibleCameras(center: MapCameraSnapshot?, radius: Double?) -> [RoadCamera] {
        guard let center else { return [] }
        let distance = max(500, min(radius ?? 15_000, 50_000))
        let latitudeSpan = distance / 111_000
        let longitudeScale = cos(center.latitude * .pi / 180)
        let longitudeSpan = latitudeSpan / max(0.1, longitudeScale)
        let representatives: [RoadCamera] = locations.map(\.camera)
        let nearby: [RoadCamera] = representatives.filter { camera in
            enabledTypes.contains(camera.type)
                && abs(camera.latitude - center.latitude) <= latitudeSpan
                && abs(camera.longitude - center.longitude) <= longitudeSpan
        }
        let ordered: [RoadCamera] = nearby.sorted { lhs, rhs in
            let lhsDistance = hypot(lhs.latitude - center.latitude, (lhs.longitude - center.longitude) * longitudeScale)
            let rhsDistance = hypot(rhs.latitude - center.latitude, (rhs.longitude - center.longitude) * longitudeScale)
            return lhsDistance < rhsDistance
        }
        return Array(ordered.prefix(250))
    }

    var selectedViews: [RoadCamera] {
        guard let selectedCamera else { return [] }
        return locations.first { $0.views.contains { $0.id == selectedCamera.id } }?.views ?? [selectedCamera]
    }

    func selectDirection(_ camera: RoadCamera) {
        guard camera.id != selectedCamera?.id, selectedViews.contains(where: { $0.id == camera.id }) else { return }
        select(camera)
    }

    func select(_ camera: RoadCamera) {
        guard enabledTypes.contains(camera.type) else { return }
        selectionGeneration += 1
        selectedCamera = camera
        photo = nil
        photoErrorKey = nil
        photoLoading = false
    }

    func dismiss() {
        selectionGeneration += 1
        selectedCamera = nil
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
                ForEach(coordinator.visibleCameras(center: surface.cameraSnapshot, radius: surface.visibleRadiusMeters())) { camera in
                    if let point = surface.screenPositionFor(latitude: camera.latitude, longitude: camera.longitude),
                       point.trustworthy, point.x >= -30, point.y >= -30,
                       point.x <= Double(geometry.size.width + 30), point.y <= Double(geometry.size.height + 30) {
                        Button { onSelect(camera) } label: {
                            IncidentMarkerBadge(marker: camera.marker)
                        }
                        .buttonStyle(.plain)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                        .accessibilityLabel(Text(LocalizedStringKey(camera.type.titleKey)) + Text(", " + camera.name))
                        .accessibilityIdentifier("map.roadCamera." + camera.id)
                        .position(x: CGFloat(point.x), y: CGFloat(point.y))
                    }
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
        }
    }
}

struct RoadCameraDetailsContent: View {
    @Bindable var coordinator: RoadCameraMapCoordinator
    let camera: RoadCamera

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text(camera.name).font(.headline)
            if coordinator.selectedViews.count > 1 {
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
                        Text(view.viewLabel(in: coordinator.selectedViews))
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
