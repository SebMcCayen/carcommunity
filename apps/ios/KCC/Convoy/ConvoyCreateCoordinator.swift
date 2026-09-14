import Foundation
import Observation

enum ConvoyCreateAvailability: Equatable, Sendable {
    case loading
    case ready(hasActiveConvoy: Bool)
    case unavailable
    case failed(ConvoyCreateError)
}

enum ConvoyCreateState: Equatable, Sendable {
    case idle
    case working
    case created(ConvoyCreated)
    case failed(ConvoyCreateError)
}

@MainActor
@Observable
final class ConvoyCreateCoordinator {
    private let repository: ConvoyCreateRepository?

    private(set) var availability: ConvoyCreateAvailability = .loading
    private(set) var createState: ConvoyCreateState = .idle

    init(repository: ConvoyCreateRepository?) {
        self.repository = repository
        if repository == nil { availability = .unavailable }
    }

    func load() async {
        guard let repository else {
            availability = .unavailable
            return
        }
        availability = .loading
        switch await repository.list() {
        case .loaded(let snapshot):
            availability = .ready(hasActiveConvoy: snapshot.hasActiveConvoy)
        case .failed(let error):
            availability = .failed(error)
        }
    }

    func create(inviteeUids: [String], vehicleId: String?) async {
        switch createState {
        case .working, .created: return
        case .idle, .failed: break
        }
        guard case .ready(let hasActiveConvoy) = availability else {
            createState = .failed(.generic)
            return
        }
        guard !hasActiveConvoy else {
            createState = .failed(.alreadyInConvoy)
            return
        }

        var seen = Set<String>()
        let invitees = inviteeUids.compactMap { raw -> String? in
            let uid = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !uid.isEmpty, seen.insert(uid).inserted else { return nil }
            return uid
        }
        guard !invitees.isEmpty else {
            createState = .failed(.noInvitees)
            return
        }
        guard let repository else {
            createState = .failed(.generic)
            return
        }
        let normalizedVehicleId = vehicleId?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty

        createState = .working
        switch await repository.create(inviteeUids: invitees, vehicleId: normalizedVehicleId) {
        case .created(let created): createState = .created(created)
        case .failed(let error): createState = .failed(error)
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
