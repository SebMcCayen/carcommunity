import Foundation
import Observation

enum IncidentFeedback: Equatable, Sendable {
    case success(String)
    case error(String)
}

@MainActor
@Observable
final class IncidentMapCoordinator {
    private let incidentRepository: IncidentRepository?
    private let policeRepository: PoliceRepository?
    private let currentUid: String?
    private let now: @Sendable () -> Date
    private let pollInterval: Duration

    private(set) var incidents: [RoadIncident] = []
    private(set) var policeReports: [PoliceReport] = []
    private(set) var latestFix: LocationFix?
    private(set) var busy = false
    private(set) var feedback: IncidentFeedback?
    private(set) var proximityAlert: PoliceReport?
    var selectedIncident: RoadIncident?
    var selectedPolice: PoliceReport?
    var reportSheetPresented = false
    var pendingMapReportType: IncidentType?

    var available: Bool { incidentRepository != nil }
    var selectedIncidentOwned: Bool { selectedIncident?.isOwned(by: currentUid) == true }
    var feedbackKey: String? {
        switch feedback {
        case .success(let key), .error(let key): key
        case nil: nil
        }
    }

    @ObservationIgnored private weak var surface: (any MapSurface)?
    @ObservationIgnored nonisolated(unsafe) private var fixTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var pollTask: Task<Void, Never>?
    @ObservationIgnored private var alertedPoliceIds = Set<String>()

    init(
        incidentRepository: IncidentRepository?,
        policeRepository: PoliceRepository?,
        currentUid: String?,
        now: @escaping @Sendable () -> Date = { Date() },
        pollInterval: Duration = .seconds(15)
    ) {
        self.incidentRepository = incidentRepository
        self.policeRepository = policeRepository
        self.currentUid = currentUid
        self.now = now
        self.pollInterval = pollInterval
    }

    deinit {
        fixTask?.cancel()
        pollTask?.cancel()
    }

    func start(surface: any MapSurface, provider: any LocationProvider) {
        stop(clearMarkers: false)
        self.surface = surface
        guard available || policeRepository != nil else {
            updateMarkers()
            return
        }
        let fixes = provider.fixes()
        fixTask = Task { [weak self] in
            for await fix in fixes {
                guard !Task.isCancelled, let self else { return }
                latestFix = fix
                evaluateProximity()
            }
        }
        pollTask = Task { [weak self, weak surface] in
            guard let self else { return }
            while !Task.isCancelled {
                await refresh(surface: surface)
                do { try await Task.sleep(for: pollInterval) } catch { return }
            }
        }
    }

    func stop(clearMarkers: Bool = true) {
        fixTask?.cancel()
        pollTask?.cancel()
        fixTask = nil
        pollTask = nil
        if clearMarkers { surface?.setIncidentMarkers([]) }
        surface = nil
    }

    func refresh(surface: (any MapSurface)? = nil) async {
        let targetSurface = surface ?? self.surface
        let center = targetSurface?.cameraSnapshot.map {
            MapPoint(longitude: $0.longitude, latitude: $0.latitude)
        } ?? latestFix.map { MapPoint(longitude: $0.longitude, latitude: $0.latitude) }
        guard let center else { return }
        let radius = IncidentViewport.radius(targetSurface?.visibleRadiusMeters())

        let newIncidents = await fetchIncidents(center: center, radius: radius)
        let newPolice = await fetchPolice(center: center, radius: radius)
        guard !Task.isCancelled else { return }
        if let newIncidents { incidents = newIncidents }
        if let newPolice { policeReports = newPolice.filter { $0.isLive(at: now()) } }
        reconcileSelections()
        updateMarkers()
        evaluateProximity()
    }

    func report(_ type: IncidentType, at point: MapPoint?) async {
        guard !busy, let incidentRepository else { return }
        let location = point ?? latestFix.map { MapPoint(longitude: $0.longitude, latitude: $0.latitude) }
        guard let location, Self.valid(location) else {
            feedback = .error("incidents.locationUnavailable")
            return
        }
        busy = true
        defer { busy = false }
        do {
            let reported = try await incidentRepository.report(type: type, at: location, note: nil)
            upsert(reported)
            updateMarkers()
            feedback = .success("incidents.reportSuccess")
            if type == .police {
                _ = await reportPolice(at: location, source: "manual", surfaceError: false)
            }
        } catch is CancellationError {
            return
        } catch {
            feedback = .error("incidents.reportError")
        }
    }

    /// Shared seam for standalone reports and the convoy-reaction feature.
    /// A future convoy call should pass `source: "convoy"` and its own fix.
    @discardableResult
    func reportPolice(
        at point: MapPoint,
        source: String = "convoy",
        surfaceError: Bool = true
    ) async -> Bool {
        guard Self.valid(point), let policeRepository else { return false }
        do {
            let pin = try await policeRepository.report(at: point, source: source)
            upsert(pin)
            updateMarkers()
            return true
        } catch is CancellationError {
            return false
        } catch {
            if surfaceError { feedback = .error("incidents.reportError") }
            return false
        }
    }

    func selectMarker(id: String) {
        if id.hasPrefix(PoliceMapMarker.prefix) {
            let pinId = String(id.dropFirst(PoliceMapMarker.prefix.count))
            selectedPolice = policeReports.first { $0.id == pinId }
            selectedIncident = nil
        } else {
            selectedIncident = incidents.first { $0.id == id }
            selectedPolice = nil
        }
    }

    func confirmSelectedIncident() async {
        guard !busy, let repository = incidentRepository, let selectedIncident else { return }
        busy = true
        defer { busy = false }
        do {
            let result = try await repository.confirm(incidentId: selectedIncident.id)
            patchIncident(id: selectedIncident.id) {
                $0.confirmationCount = result.confirmationCount
                $0.clearedCount = result.clearedCount
                $0.reportedCleared = result.reportedCleared
            }
            feedback = .success(result.alreadyConfirmed ? "incidents.verifyAlready" : "incidents.verifySuccess")
        } catch is CancellationError {
            return
        } catch {
            feedback = .error("incidents.verifyError")
        }
    }

    func clearSelectedIncident() async {
        guard !busy, let repository = incidentRepository, let incident = selectedIncident else { return }
        guard !incident.isImported else {
            feedback = .error("incidents.clearedImportedExplanation")
            return
        }
        guard let fix = latestFix else {
            feedback = .error("incidents.clearedNoLocation")
            return
        }
        busy = true
        defer { busy = false }
        do {
            let result = try await repository.reportCleared(incidentId: incident.id, fix: fix)
            if result.removed {
                incidents.removeAll { $0.id == incident.id }
                selectedIncident = nil
            } else {
                patchIncident(id: incident.id) {
                    $0.clearedCount = result.clearedCount
                    $0.confirmationCount = result.confirmationCount
                    $0.reportedCleared = result.reportedCleared
                }
            }
            updateMarkers()
            let key = result.removed ? "incidents.clearedRemoved"
                : result.alreadyVoted ? "incidents.clearedAlready" : "incidents.clearedSuccess"
            feedback = .success(key)
        } catch let rejection as IncidentClearRejection {
            feedback = .error(rejection == .outOfRange ? "incidents.clearedTooFar" : "incidents.clearedError")
        } catch is CancellationError {
            return
        } catch {
            feedback = .error("incidents.clearedError")
        }
    }

    func removeSelectedIncident() async {
        guard !busy, let repository = incidentRepository, let incident = selectedIncident else { return }
        busy = true
        defer { busy = false }
        do {
            try await repository.remove(incidentId: incident.id)
            incidents.removeAll { $0.id == incident.id }
            selectedIncident = nil
            updateMarkers()
            feedback = .success("incidents.removeSuccess")
        } catch is CancellationError {
            return
        } catch {
            feedback = .error("incidents.removeError")
        }
    }

    func verifySelectedPolice(confirm: Bool) async {
        guard !busy, let repository = policeRepository, let pin = selectedPolice else { return }
        busy = true
        defer { busy = false }
        do {
            let result = try await (confirm
                ? repository.confirm(policeReportId: pin.id)
                : repository.dispute(policeReportId: pin.id))
            patchPolice(id: pin.id) {
                $0.confirmationCount = result.confirmationCount
                $0.disputeCount = result.disputeCount
            }
            let key: String
            if confirm { key = result.alreadyVoted ? "police.confirmAlready" : "police.confirmSuccess" }
            else { key = result.alreadyVoted ? "police.disputeAlready" : "police.disputeSuccess" }
            feedback = .success(key)
        } catch is CancellationError {
            return
        } catch {
            feedback = .error("police.verifyError")
        }
    }

    func removeSelectedPolice() async {
        guard !busy, let repository = policeRepository, let pin = selectedPolice else { return }
        busy = true
        defer { busy = false }
        do {
            _ = try await repository.remove(policeReportId: pin.id)
            policeReports.removeAll { $0.id == pin.id }
            selectedPolice = nil
            updateMarkers()
            feedback = .success("police.removeSuccess")
        } catch is CancellationError {
            return
        } catch {
            feedback = .error("police.removeError")
        }
    }

    func clearFeedback() { feedback = nil }
    func dismissProximityAlert() { proximityAlert = nil }
    func beginMapSelection(for type: IncidentType) { pendingMapReportType = type }
    func cancelMapSelection() { pendingMapReportType = nil }

    private func fetchIncidents(center: MapPoint, radius: Double) async -> [RoadIncident]? {
        guard let incidentRepository else { return [] }
        do { return try await incidentRepository.listNearby(center: center, radiusMeters: radius) }
        catch { return nil }
    }

    private func fetchPolice(center: MapPoint, radius: Double) async -> [PoliceReport]? {
        guard let policeRepository else { return [] }
        do { return try await policeRepository.listNearby(center: center, radiusMeters: radius) }
        catch { return nil }
    }

    private func evaluateProximity() {
        let new = PoliceProximity.newAlerts(
            driver: latestFix, pins: policeReports,
            alreadyAlerted: alertedPoliceIds, now: now()
        )
        guard !new.isEmpty else { return }
        alertedPoliceIds.formUnion(new.map(\.id))
        if proximityAlert == nil { proximityAlert = new[0] }
    }

    private func updateMarkers() {
        surface?.setIncidentMarkers(
            incidents.map(\.mapMarker) + PoliceMapMarker.markers(pins: policeReports, incidents: incidents)
        )
    }

    private func reconcileSelections() {
        if let id = selectedIncident?.id { selectedIncident = incidents.first { $0.id == id } }
        if let id = selectedPolice?.id { selectedPolice = policeReports.first { $0.id == id } }
    }

    private func upsert(_ incident: RoadIncident) {
        incidents.removeAll { $0.id == incident.id }
        incidents.append(incident)
    }

    private func upsert(_ pin: PoliceReport) {
        policeReports.removeAll { $0.id == pin.id }
        policeReports.append(pin)
    }

    private func patchIncident(id: String, update: (inout RoadIncident) -> Void) {
        guard let index = incidents.firstIndex(where: { $0.id == id }) else { return }
        update(&incidents[index])
        selectedIncident = incidents[index]
        updateMarkers()
    }

    private func patchPolice(id: String, update: (inout PoliceReport) -> Void) {
        guard let index = policeReports.firstIndex(where: { $0.id == id }) else { return }
        update(&policeReports[index])
        selectedPolice = policeReports[index]
    }

    private static func valid(_ point: MapPoint) -> Bool {
        point.latitude.isFinite && point.longitude.isFinite
            && (-90...90).contains(point.latitude) && (-180...180).contains(point.longitude)
    }
}
