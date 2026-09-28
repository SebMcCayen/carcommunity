import Foundation
import Observation

enum AccountAccessState: Equatable, Sendable {
    case unavailable
    case loading
    case loaded(AccountAccess)
    case failed(code: String?)
}

@MainActor
@Observable
final class AppAccessSession {
    private let accessRepository: AccountAccessRepository?
    private let flagsRepository: FeatureFlagsRepository?
    private(set) var accountState: AccountAccessState
    private(set) var flags = FeatureFlags.contractDefaults
    private var uid: String?
    @ObservationIgnored nonisolated(unsafe) private var accessTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var flagsTask: Task<Void, Never>?

    init(
        accessRepository: AccountAccessRepository?,
        flagsRepository: FeatureFlagsRepository?
    ) {
        self.accessRepository = accessRepository
        self.flagsRepository = flagsRepository
        accountState = accessRepository == nil ? .unavailable : .loading
    }

    deinit {
        accessTask?.cancel()
        flagsTask?.cancel()
    }

    func bind(uid: String?) {
        guard uid != self.uid else { return }
        self.uid = uid
        accessTask?.cancel()
        flagsTask?.cancel()
        accessTask = nil
        flagsTask = nil
        flags = .contractDefaults
        guard let uid else {
            accountState = accessRepository == nil ? .unavailable : .loading
            return
        }
        guard let accessRepository else {
            accountState = .unavailable
            return
        }
        accountState = .loading
        let accessUpdates = accessRepository.updates(uid: uid)
        accessTask = Task { [weak self] in
            for await update in accessUpdates {
                guard !Task.isCancelled, let self, self.uid == uid else { return }
                switch update {
                case .loaded(let access):
                    // A missing document can be the provisioning trigger race;
                    // do not invent unrestricted access for it.
                    self.accountState = access.map(AccountAccessState.loaded) ?? .loading
                case .failed(let code):
                    self.accountState = .failed(code: code)
                }
            }
        }
        guard let flagsRepository else { return }
        let flagUpdates = flagsRepository.updates()
        flagsTask = Task { [weak self] in
            for await flags in flagUpdates {
                guard !Task.isCancelled, let self, self.uid == uid else { return }
                self.flags = flags
            }
        }
    }

    func refreshFlags() async {
        guard uid != nil, let flagsRepository else { return }
        do { flags = try await flagsRepository.fetch() } catch {
            // Keep the last good values (or contract defaults), never fail off.
        }
    }
}
