import Foundation
import Observation

@MainActor
@Observable
final class ConvoyReactionCoordinator {
    typealias PoliceSuccessHandler = @MainActor @Sendable () async -> Void

    private(set) var incomingReaction: ConvoyReactionEvent?
    private(set) var cooldown = ConvoyReactionCooldownState()
    private(set) var sendingKinds: Set<ConvoyReactionKind> = []

    @ObservationIgnored private let repository: ConvoyReactionRepository
    @ObservationIgnored private let nowMilliseconds: @Sendable () -> Int64
    @ObservationIgnored private let makeClientId: @Sendable () -> String
    @ObservationIgnored private let onPoliceSent: PoliceSuccessHandler?
    @ObservationIgnored private var activeConvoyId: String?
    @ObservationIgnored private var sessionGeneration: UInt64 = 0
    // These tasks are only mutated on the main actor. Marking their storage
    // nonisolated permits the nonisolated deinitializer to cancel them, matching
    // the lifecycle pattern used by the other stream coordinators in the app.
    @ObservationIgnored
    nonisolated(unsafe) private var observationTask: Task<Void, Never>?
    @ObservationIgnored
    nonisolated(unsafe) private var dismissalTask: Task<Void, Never>?

    init(
        repository: ConvoyReactionRepository,
        nowMilliseconds: @escaping @Sendable () -> Int64 = {
            Int64(Date().timeIntervalSince1970 * 1_000)
        },
        makeClientId: @escaping @Sendable () -> String = {
            UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(32).lowercased()
        },
        onPoliceSent: PoliceSuccessHandler? = nil
    ) {
        self.repository = repository
        self.nowMilliseconds = nowMilliseconds
        self.makeClientId = makeClientId
        self.onPoliceSent = onPoliceSent
    }

    deinit {
        observationTask?.cancel()
        dismissalTask?.cancel()
    }

    /// Switches the one live listener to the convoy currently visible on the map.
    /// Passing nil tears down the listener and clears transient UI state.
    func sync(convoyId: String?) {
        guard convoyId != activeConvoyId else { return }
        sessionGeneration &+= 1
        observationTask?.cancel()
        dismissalTask?.cancel()
        observationTask = nil
        dismissalTask = nil
        activeConvoyId = convoyId
        incomingReaction = nil
        cooldown = ConvoyReactionCooldownState()
        sendingKinds = []
        guard let convoyId else { return }

        let stream = repository.reactions(convoyId: convoyId)
        observationTask = Task { [weak self] in
            for await event in stream {
                guard !Task.isCancelled, let self, self.activeConvoyId == convoyId else { break }
                self.show(event)
            }
        }
    }

    func remainingMilliseconds(
        for kind: ConvoyReactionKind,
        nowMilliseconds: Int64
    ) -> Int64 {
        cooldown.remainingMilliseconds(for: kind, nowMilliseconds: nowMilliseconds)
    }

    func send(_ kind: ConvoyReactionKind) async {
        guard let convoyId = activeConvoyId else { return }
        let expectedGeneration = sessionGeneration
        let sentAt = nowMilliseconds()
        guard cooldown.isReady(kind, nowMilliseconds: sentAt),
              !sendingKinds.contains(kind)
        else { return }

        // Stop a double tap before the network round-trip. The server still owns
        // the real cooldown and can replace this estimate below.
        cooldown = cooldown.recordingSend(kind, atMilliseconds: sentAt)
        sendingKinds.insert(kind)
        let result = await repository.send(
            convoyId: convoyId,
            kind: kind,
            clientId: makeClientId()
        )
        // A server-confirmed police reaction has a durable, non-UI side
        // effect: publish the associated police pin. It must run even if the
        // visible convoy changed while the send was in flight. Session guards
        // below still prevent that old completion from touching cooldown or
        // sending state in the replacement session.
        if result == .sent, kind == .police {
            await onPoliceSent?()
        }
        // The same convoy can become active again while this request is in
        // flight (A -> nil/B -> A). Its id alone does not identify the session;
        // never let an earlier completion mutate the replacement session.
        guard activeConvoyId == convoyId,
              sessionGeneration == expectedGeneration
        else { return }
        sendingKinds.remove(kind)
        switch result {
        case .sent:
            break
        case .rateLimited(let retryAfterMilliseconds):
            cooldown = cooldown.applyingServerCooldown(
                kind,
                retryAfterMilliseconds: retryAfterMilliseconds,
                nowMilliseconds: nowMilliseconds()
            )
        case .failed:
            // Nothing reached the server, so permit an immediate retry.
            cooldown = cooldown.clearing(kind)
        }
    }

    func dismissIncoming(id: String) {
        guard incomingReaction?.id == id else { return }
        incomingReaction = nil
        dismissalTask?.cancel()
        dismissalTask = nil
    }

    private func show(_ event: ConvoyReactionEvent) {
        incomingReaction = event
        dismissalTask?.cancel()
        dismissalTask = Task { [weak self] in
            do {
                try await Task.sleep(for: .milliseconds(1_720))
            } catch {
                return
            }
            guard !Task.isCancelled else { return }
            self?.dismissIncoming(id: event.id)
        }
    }
}
