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
    private let maximumFixAge: TimeInterval
    private let fixWaitTimeout: Duration

    private(set) var incidents: [RoadIncident] = []
    private(set) var policeReports: [PoliceReport] = []
    private(set) var latestFix: LocationFix?
    private(set) var busy = false
    private(set) var feedback: IncidentFeedback?
    private(set) var proximityAlert: PoliceReport?
    private(set) var trafficAlertsEnabled = true
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
    @ObservationIgnored private weak var locationProvider: (any LocationProvider)?
    @ObservationIgnored nonisolated(unsafe) private var fixTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var authorizationTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var pollTask: Task<Void, Never>?
    @ObservationIgnored private var alertedPoliceIds = Set<String>()
    @ObservationIgnored private var mutationGeneration = 0
    @ObservationIgnored private var refreshGeneration = 0
    @ObservationIgnored private var contextGeneration = 0

    init(
        incidentRepository: IncidentRepository?,
        policeRepository: PoliceRepository?,
        currentUid: String?,
        now: @escaping @Sendable () -> Date = { Date() },
        pollInterval: Duration = .seconds(15),
        maximumFixAge: TimeInterval = 30,
        fixWaitTimeout: Duration = .seconds(3)
    ) {
        self.incidentRepository = incidentRepository
        self.policeRepository = policeRepository
        self.currentUid = currentUid
        self.now = now
        self.pollInterval = pollInterval
        self.maximumFixAge = maximumFixAge
        self.fixWaitTimeout = fixWaitTimeout
    }

    deinit {
        fixTask?.cancel()
        authorizationTask?.cancel()
        pollTask?.cancel()
    }

    func start(surface: any MapSurface, provider: any LocationProvider) {
        stop(clearMarkers: false)
        self.surface = surface
        locationProvider = provider
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
        let authorizations = provider.authorizationUpdates()
        authorizationTask = Task { [weak self] in
            for await authorization in authorizations {
                guard !Task.isCancelled, let self else { return }
                if !authorization.isAuthorized { latestFix = nil }
            }
        }
        startPollingIfNeeded()
    }

    func setTrafficAlertsEnabled(_ enabled: Bool) {
        guard enabled != trafficAlertsEnabled else { return }
        invalidateContext()
        trafficAlertsEnabled = enabled
        if enabled {
            startPollingIfNeeded()
        } else {
            pollTask?.cancel()
            pollTask = nil
            incidents = []
            policeReports = []
            selectedIncident = nil
            selectedPolice = nil
            proximityAlert = nil
            alertedPoliceIds.removeAll()
            surface?.setIncidentMarkers([])
        }
    }

    private func startPollingIfNeeded() {
        guard trafficAlertsEnabled, pollTask == nil else { return }
        pollTask = Task { [weak self, weak surface] in
            guard let self else { return }
            while !Task.isCancelled {
                await refresh(surface: surface)
                do { try await Task.sleep(for: pollInterval) } catch { return }
            }
        }
    }

    func stop(clearMarkers: Bool = true) {
        invalidateContext()
        fixTask?.cancel()
        authorizationTask?.cancel()
        pollTask?.cancel()
        fixTask = nil
        authorizationTask = nil
        pollTask = nil
        if clearMarkers { surface?.setIncidentMarkers([]) }
        surface = nil
        locationProvider = nil
    }

    func refresh(surface: (any MapSurface)? = nil) async {
        guard trafficAlertsEnabled, !busy else { return }
        refreshGeneration += 1
        let refresh = refreshGeneration
        let mutations = mutationGeneration
        let targetSurface = surface ?? self.surface
        let center = targetSurface?.cameraSnapshot.map {
            MapPoint(longitude: $0.longitude, latitude: $0.latitude)
        } ?? latestFix.map { MapPoint(longitude: $0.longitude, latitude: $0.latitude) }
        guard let center else { return }
        let radius = IncidentViewport.radius(targetSurface?.visibleRadiusMeters())

        let newIncidents = await fetchIncidents(center: center, radius: radius)
        let newPolice = await fetchPolice(center: center, radius: radius)
        guard !Task.isCancelled, !busy, refresh == refreshGeneration,
              mutations == mutationGeneration, trafficAlertsEnabled else { return }
        if let newIncidents { incidents = newIncidents }
        if let newPolice { policeReports = newPolice.filter { $0.isLive(at: now()) } }
        reconcileSelections()
        updateMarkers()
        evaluateProximity()
    }

    func report(_ type: IncidentType, at point: MapPoint?) async {
        guard !busy, let incidentRepository else { return }
        let context = contextGeneration
        busy = true
        defer { busy = false }
        let location: MapPoint?
        if let point {
            location = point
        } else if let fix = await authorizedFreshFix(waitForFirst: true) {
            location = MapPoint(longitude: fix.longitude, latitude: fix.latitude)
        } else {
            location = nil
        }
        guard let location, Self.valid(location) else {
            if contextIsCurrent(context) {
                feedback = .error("incidents.locationUnavailable")
            }
            return
        }
        beginMutation()
        do {
            let reported = try await incidentRepository.report(type: type, at: location, note: nil)
            if contextIsCurrent(context) {
                upsert(reported)
                updateMarkers()
            }
            if type == .police {
                _ = await reportPolice(
                    at: location,
                    source: "manual",
                    surfaceError: false,
                    presentationContext: context
                )
            }
            if contextIsCurrent(context) {
                feedback = .success("incidents.reportSuccess")
            }
        } catch is CancellationError {
            return
        } catch {
            if contextIsCurrent(context) { feedback = .error("incidents.reportError") }
        }
    }

    /// Uploads an already-authorized location selected by another coordinator
    /// path. Current-location callers must go through
    /// ``reportPoliceAtCurrentLocation(source:surfaceError:)`` so permission
    /// and freshness are checked immediately before the upload.
    @discardableResult
    private func reportPolice(
        at point: MapPoint,
        source: String = "convoy",
        surfaceError: Bool = true,
        presentationContext: Int? = nil
    ) async -> Bool {
        guard Self.valid(point), let policeRepository else { return false }
        let context = presentationContext ?? contextGeneration
        beginMutation()
        do {
            let pin = try await policeRepository.report(at: point, source: source)
            guard contextIsCurrent(context) else { return true }
            // Police reports deliberately do not claim the coordinator-wide
            // busy state: convoy reactions must not block unrelated incident
            // actions while their upload is in flight. Advance the generation
            // again at commit time so a refresh that started after the upload
            // began cannot replace this returned pin with its older snapshot.
            beginMutation()
            upsert(pin)
            updateMarkers()
            return true
        } catch is CancellationError {
            return false
        } catch {
            if surfaceError, contextIsCurrent(context) {
                feedback = .error("incidents.reportError")
            }
            return false
        }
    }

    @discardableResult
    func reportPoliceAtCurrentLocation(
        source: String = "convoy",
        surfaceError: Bool = true
    ) async -> Bool {
        let context = contextGeneration
        guard let fix = await authorizedFreshFix(waitForFirst: true) else {
            if surfaceError, contextIsCurrent(context) {
                feedback = .error("incidents.locationUnavailable")
            }
            return false
        }
        return await reportPolice(
            at: MapPoint(longitude: fix.longitude, latitude: fix.latitude),
            source: source,
            surfaceError: surfaceError,
            presentationContext: context
        )
    }

    func selectMarker(id: String) {
        guard trafficAlertsEnabled else { return }
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
        guard trafficAlertsEnabled, !busy,
              let repository = incidentRepository, let selectedIncident else { return }
        let context = contextGeneration
        busy = true
        beginMutation()
        defer { busy = false }
        do {
            let result = try await repository.confirm(incidentId: selectedIncident.id)
            guard contextIsCurrent(context) else { return }
            patchIncident(id: selectedIncident.id) {
                $0.confirmationCount = result.confirmationCount
                $0.clearedCount = result.clearedCount
                $0.reportedCleared = result.reportedCleared
            }
            feedback = .success(result.alreadyConfirmed ? "incidents.verifyAlready" : "incidents.verifySuccess")
        } catch is CancellationError {
            return
        } catch {
            if contextIsCurrent(context) { feedback = .error("incidents.verifyError") }
        }
    }

    func clearSelectedIncident() async {
        guard trafficAlertsEnabled, !busy,
              let repository = incidentRepository, let incident = selectedIncident else { return }
        let context = contextGeneration
        guard !incident.isImported else {
            feedback = .error("incidents.clearedImportedExplanation")
            return
        }
        busy = true
        defer { busy = false }
        guard let fix = await authorizedFreshFix(waitForFirst: true) else {
            if contextIsCurrent(context) { feedback = .error("incidents.clearedNoLocation") }
            return
        }
        guard contextIsCurrent(context) else { return }
        beginMutation()
        do {
            let result = try await repository.reportCleared(incidentId: incident.id, fix: fix)
            guard contextIsCurrent(context) else { return }
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
            if contextIsCurrent(context) {
                feedback = .error(
                    rejection == .outOfRange ? "incidents.clearedTooFar" : "incidents.clearedError"
                )
            }
        } catch is CancellationError {
            return
        } catch {
            if contextIsCurrent(context) { feedback = .error("incidents.clearedError") }
        }
    }

    func removeSelectedIncident() async {
        guard trafficAlertsEnabled, !busy,
              let repository = incidentRepository, let incident = selectedIncident else { return }
        let context = contextGeneration
        busy = true
        beginMutation()
        defer { busy = false }
        do {
            try await repository.remove(incidentId: incident.id)
            guard contextIsCurrent(context) else { return }
            incidents.removeAll { $0.id == incident.id }
            selectedIncident = nil
            updateMarkers()
            feedback = .success("incidents.removeSuccess")
        } catch is CancellationError {
            return
        } catch {
            if contextIsCurrent(context) { feedback = .error("incidents.removeError") }
        }
    }

    func verifySelectedPolice(confirm: Bool) async {
        guard trafficAlertsEnabled, !busy,
              let repository = policeRepository, let pin = selectedPolice else { return }
        let context = contextGeneration
        busy = true
        beginMutation()
        defer { busy = false }
        do {
            let result = try await (confirm
                ? repository.confirm(policeReportId: pin.id)
                : repository.dispute(policeReportId: pin.id))
            guard contextIsCurrent(context) else { return }
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
            if contextIsCurrent(context) { feedback = .error("police.verifyError") }
        }
    }

    func removeSelectedPolice() async {
        guard trafficAlertsEnabled, !busy,
              let repository = policeRepository, let pin = selectedPolice else { return }
        let context = contextGeneration
        busy = true
        beginMutation()
        defer { busy = false }
        do {
            _ = try await repository.remove(policeReportId: pin.id)
            guard contextIsCurrent(context) else { return }
            policeReports.removeAll { $0.id == pin.id }
            selectedPolice = nil
            updateMarkers()
            feedback = .success("police.removeSuccess")
        } catch is CancellationError {
            return
        } catch {
            if contextIsCurrent(context) { feedback = .error("police.removeError") }
        }
    }

    func clearFeedback() { feedback = nil }
    func dismissProximityAlert() {
        proximityAlert = nil
        evaluateProximity()
    }
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
        guard trafficAlertsEnabled, proximityAlert == nil else { return }
        let new = PoliceProximity.newAlerts(
            driver: latestFix, pins: policeReports,
            alreadyAlerted: alertedPoliceIds, now: now()
        )
        guard !new.isEmpty else { return }
        let next = new[0]
        alertedPoliceIds.insert(next.id)
        proximityAlert = next
    }

    private func updateMarkers() {
        guard trafficAlertsEnabled else {
            surface?.setIncidentMarkers([])
            return
        }
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

    private func beginMutation() {
        mutationGeneration &+= 1
    }

    private func invalidateContext() {
        contextGeneration &+= 1
        mutationGeneration &+= 1
        refreshGeneration &+= 1
    }

    private func contextIsCurrent(_ context: Int) -> Bool {
        trafficAlertsEnabled && context == contextGeneration && !Task.isCancelled
    }

    private func authorizedFreshFix(waitForFirst: Bool) async -> LocationFix? {
        guard let provider = locationProvider, provider.authorization.isAuthorized else {
            latestFix = nil
            return nil
        }
        if let latestFix, isFresh(latestFix) { return latestFix }
        guard waitForFirst else { return nil }

        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: fixWaitTimeout)
        while clock.now < deadline {
            guard provider.authorization.isAuthorized else {
                latestFix = nil
                return nil
            }
            if let latestFix, isFresh(latestFix) { return latestFix }
            do { try await Task.sleep(for: .milliseconds(50)) } catch { return nil }
        }
        return nil
    }

    private func isFresh(_ fix: LocationFix) -> Bool {
        let age = now().timeIntervalSince(fix.timestamp)
        return age >= -5 && age <= maximumFixAge
    }

    private static func valid(_ point: MapPoint) -> Bool {
        point.latitude.isFinite && point.longitude.isFinite
            && (-90...90).contains(point.latitude) && (-180...180).contains(point.longitude)
    }
}
