import Foundation
import Observation

struct NearbyWaveEvent: Equatable, Sendable {
    let id: String
    let senderName: String?
}

enum NearbyWaveSendResult: Equatable, Sendable {
    case sent
    case rateLimited(retryAfterMilliseconds: Int64)
    case failed
}

protocol NearbyWaveRepository: Sendable {
    func send(clientId: String) async -> NearbyWaveSendResult
    func incoming(uid: String, since: Date) -> AsyncStream<NearbyWaveEvent>
}

struct NearbyWaveRange: Equatable {
    // Mirrors live.sendWave. Discovery can use a different center and radius.
    static let radiusMeters = 15_000.0

    let markerUids: Set<String>
    let nearbyUids: Set<String>

    init(origin: MapPoint, markers: [LiveMarker], discovery: [NearbyLiveSession]) {
        func isInRange(latitude: Double, longitude: Double) -> Bool {
            LiveShareCadence.distanceMeters(
                lat1: origin.latitude, lon1: origin.longitude, lat2: latitude, lon2: longitude
            ) <= Self.radiusMeters
        }

        let observedUids = Set(markers.map(\.uid))
        markerUids = Set(markers.filter { isInRange(latitude: $0.latitude, longitude: $0.longitude) }.map(\.uid))
        let discoveryUids = discovery.filter {
            !observedUids.contains($0.uid) && isInRange(latitude: $0.latitude, longitude: $0.longitude)
        }.map(\.uid)
        nearbyUids = markerUids.union(discoveryUids)
    }
}

@MainActor
@Observable
final class NearbyWaveCoordinator {
    static let cooldown: TimeInterval = 45

    enum Pop: Equatable, Identifiable {
        case sent(String)
        case received(NearbyWaveEvent)

        var id: String {
            switch self {
            case .sent(let id): "self-\(id)"
            case .received(let event): "received-\(event.id)"
            }
        }
    }

    private let repository: any NearbyWaveRepository
    private(set) var pop: Pop?
    private(set) var cooldownUntil: Date = .distantPast
    private(set) var isSending = false
    private var ownMarker: LiveMarker?
    private var wavedThisVisit: Set<String> = []
    @ObservationIgnored private var pendingRecipients: Set<String> = []
    @ObservationIgnored private var dismissTask: Task<Void, Never>?

    init(repository: any NearbyWaveRepository) {
        self.repository = repository
    }

    func observeOrigin(uid: String?, repository: (any LiveLocationRepository)?) async {
        ownMarker = nil
        guard let uid, let repository else { return }
        while !Task.isCancelled {
            var shouldRetry = false
            for await update in repository.latestUpdateEvents(uid: uid) {
                guard !Task.isCancelled else { return }
                switch update {
                case .value(let marker): ownMarker = marker
                case .retry:
                    ownMarker = nil
                    shouldRetry = true
                }
            }
            guard shouldRetry else { return }
            do { try await Task.sleep(for: .seconds(1)) } catch { return }
        }
    }

    func ownPoint(uid: String, now: Date) -> MapPoint? {
        guard let marker = ownMarker, marker.uid == uid,
              marker.recordedAt.map({ now.timeIntervalSince($0) <= NearbyLivePresentation.staleAfter }) ?? true
        else { return nil }
        return MapPoint(longitude: marker.longitude, latitude: marker.latitude)
    }

    func observeInbox(uid: String) async {
        var receivedIds = Set<String>()
        for await event in repository.incoming(uid: uid, since: Date()) {
            guard !Task.isCancelled else { return }
            if receivedIds.insert(event.id).inserted { show(.received(event)) }
        }
    }

    func reconcile(_ range: NearbyWaveRange) {
        let remaining = wavedThisVisit.intersection(range.nearbyUids)
        if wavedThisVisit != remaining { wavedThisVisit = remaining }
        // If someone leaves while a send is in flight, don't mark a new visit
        // when that send eventually finishes.
        pendingRecipients.formIntersection(range.nearbyUids)
    }

    func canOffer(in range: NearbyWaveRange) -> Bool {
        !range.markerUids.isSubset(of: wavedThisVisit)
    }

    func send(in range: NearbyWaveRange, now: Date = Date()) async {
        reconcile(range)
        guard !isSending, now >= cooldownUntil, canOffer(in: range) else { return }

        let clientId = UUID().uuidString
        let outgoing = Pop.sent(clientId)
        pendingRecipients = range.nearbyUids
        isSending = true
        cooldownUntil = now.addingTimeInterval(Self.cooldown)
        show(outgoing)

        let result = await repository.send(clientId: clientId)
        isSending = false
        defer { pendingRecipients = [] }
        switch result {
        case .sent:
            wavedThisVisit.formUnion(pendingRecipients)
        case .rateLimited(let retryAfterMilliseconds):
            cooldownUntil = Date().addingTimeInterval(Double(retryAfterMilliseconds) / 1_000)
            if pop == outgoing { dismissPop() }
        case .failed:
            cooldownUntil = .distantPast
            if pop == outgoing { dismissPop() }
        }
    }

    func sharingEnded() {
        ownMarker = nil
        wavedThisVisit = []
        pendingRecipients = []
        dismissPop()
    }

    func dismissPop() {
        dismissTask?.cancel()
        dismissTask = nil
        pop = nil
    }

    private func show(_ newPop: Pop) {
        dismissTask?.cancel()
        pop = newPop
        dismissTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(2)) } catch { return }
            self?.dismissPop()
        }
    }
}
