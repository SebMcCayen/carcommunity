import SwiftUI
import MapboxMaps
import UIKit
import FirebaseCore
import FirebaseAuth

private func trafficString(_ key: String) -> String { NSLocalizedString(key, comment: "") }
private struct TrafficText: View {
    let key: String
    init(_ key: String) { self.key = key }
    var body: some View { Text(LocalizedStringKey(key)).fixedSize(horizontal: false, vertical: true) }
}
@MainActor private let trafficColors: [UIColor] = [
    UIColor(red: 254/255, green: 229/255, blue: 217/255, alpha: 0.75),
    UIColor(red: 252/255, green: 174/255, blue: 145/255, alpha: 0.75),
    UIColor(red: 251/255, green: 106/255, blue: 74/255, alpha: 0.75),
    UIColor(red: 222/255, green: 45/255, blue: 38/255, alpha: 0.75),
    UIColor(red: 165/255, green: 15/255, blue: 21/255, alpha: 0.75),
]

struct PartnerTrafficHeatScreen: View {
    let companyId: String
    @State private var coordinator: PartnerTrafficHeatCoordinator
    @State private var account: String?
    @State private var listener: AuthStateDidChangeListenerHandle?
    @State private var refreshRequest = 0
    @Environment(\.scenePhase) private var phase
    init(companyId: String, client: PartnerPortalClient? = .createIfAvailable()) {
        self.companyId = companyId
        _coordinator = State(initialValue: PartnerTrafficHeatCoordinator(client: client))
        _account = State(initialValue: client?.trafficSessionKey)
    }
    var body: some View {
        List {
            TrafficText("partnerTraffic.purpose")
            TrafficText("partnerTraffic.privacy")
            if coordinator.busy { ProgressView() }
            PortalError(key: coordinator.errorKey)
            if account == nil { PortalText("portal.accessDenied") }
            if coordinator.expired { TrafficText("partnerTraffic.expired") }
            if coordinator.errorKey != nil || coordinator.expired {
                Button { refreshRequest += 1 } label: { PortalText("portal.retry") }
                    .frame(minHeight: 48).disabled(coordinator.busy)
            }
            if let snapshot = coordinator.snapshot {
                PartnerTrafficHeatContent(snapshot: snapshot)
            }
        }
        .navigationTitle(trafficString("partnerTraffic.title"))
        .kccReadingSurface(pageBackground: true)
        .accessibilityIdentifier("partnerTraffic.screen")
        .onAppear {
            guard listener == nil, let app = FirebaseApp.app() else { return }
            listener = Auth.auth(app: app).addStateDidChangeListener { _, user in
                Task { @MainActor in
                    if account != user?.uid { coordinator.clear(); account = user?.uid }
                }
            }
        }
        .onDisappear {
            if let listener, let app = FirebaseApp.app() { Auth.auth(app: app).removeStateDidChangeListener(listener) }
            listener = nil
            coordinator.clear()
        }
        .onChange(of: phase) { _, value in if value != .active { coordinator.clear() } }
        .task(id: "\(companyId)|\(account ?? "")|\(phase == .active)|\(refreshRequest)") {
            guard phase == .active, account != nil else { coordinator.clear(); return }
            while !Task.isCancelled {
                await coordinator.load(companyId: companyId)
                if coordinator.errorKey != nil { return }
                do { try await Task.sleep(for: .seconds(60)) } catch { return }
            }
        }
        .task(id: "expiry|\(companyId)|\(account ?? "")|\(phase == .active)") {
            guard phase == .active, account != nil else { return }
            // Expire the displayed snapshot even when a refresh RPC is stalled.
            while !Task.isCancelled {
                coordinator.expireIfNeeded()
                do { try await Task.sleep(for: .seconds(1)) } catch { return }
            }
        }
    }
}

struct PartnerTrafficHeatContent: View {
    let snapshot: PartnerTrafficHeatSnapshot
    var mapEnabled = true
    var body: some View {
        if snapshot.cells.isEmpty {
            TrafficText("partnerTraffic.empty").accessibilityIdentifier("partnerTraffic.empty")
        } else {
            if let iso = snapshot.generatedAt, let date = PortalDate.parse(iso) {
                Text(String(format: trafficString("partnerTraffic.updated"), date.formatted(date: .abbreviated, time: .shortened)))
            }
            if snapshot.truncated { TrafficText("partnerTraffic.limited") }
            if mapEnabled {
                PartnerTrafficMap(cells: snapshot.cells).frame(height: 340)
                    .accessibilityLabel(Text("partnerTraffic.map"))
            } else { TrafficText("partnerTraffic.mapUnavailable") }
            ForEach(1...5, id: \.self) { band in
                HStack {
                    Rectangle().fill(Color(uiColor: trafficColors[band - 1])).frame(width: 20, height: 20).accessibilityHidden(true)
                    TrafficText("partnerTraffic.band\(band)")
                }
            }
            Section {
                ForEach(Array(snapshot.cells.prefix(20))) { cell in
                    VStack(alignment: .leading) {
                        Text(String(format: "%.3f, %.3f", locale: Locale(identifier: "en_US_POSIX"), cell.latitude, cell.longitude))
                        TrafficText("partnerTraffic.band\(cell.band)")
                    }
                }
            } header: { TrafficText("partnerTraffic.areas") }
        }
    }
}

/// Uses the app's existing basemap provider. No location provider, puck, or GPS permission.
private struct PartnerTrafficMap: View {
    let cells: [PartnerHeatArea]
    private let accessToken: String?
    @State private var loaded = false
    @State private var failed = false

    init(cells: [PartnerHeatArea], accessToken: String? = MapboxConfiguration.accessToken()) {
        self.cells = cells
        self.accessToken = accessToken
        if let accessToken { MapboxOptions.accessToken = accessToken }
    }

    var body: some View {
        if accessToken != nil, !failed, let first = cells.first {
            MapboxMaps.Map(initialViewport: .camera(
                center: CLLocationCoordinate2D(latitude: max(-85, min(85, first.latitude)), longitude: first.longitude),
                zoom: 12, bearing: 0, pitch: 0
            )) {
                PolygonAnnotationGroup(cells) { cell in
                    let points = cell.boundary.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
                    let closed = points + points.prefix(1)
                    return PolygonAnnotation(polygon: Polygon(outerRing: Ring(coordinates: closed)))
                        .fillColor(StyleColor(trafficColors[cell.band - 1]))
                        .fillOutlineColor(StyleColor(.clear))
                }
                .slot(.middle)
            }
            .mapStyle(.standard)
            .onMapLoaded { _ in loaded = true }
            .onMapLoadingError { event in
                switch event.type {
                case .style: failed = true
                default: break
                }
            }
            .task {
                do { try await Task.sleep(for: .seconds(20)) } catch { return }
                if !loaded { failed = true }
            }
        } else {
            TrafficText("partnerTraffic.mapUnavailable")
        }
    }
}
