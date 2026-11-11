import Foundation
import Observation

/// Reconciles the independent RTDB session and Firestore convoy listeners.
/// Ending a convoy remotely must not save the route before the member can
/// choose to continue. All other ends (Stop, Hide, leave and expiry) still win.
@MainActor
@Observable
final class ConvoyEndSessionCoordinator {
    private(set) var presentsChoice = false
    private(set) var isContinuing = false
    var commandFailed = false

    @ObservationIgnored private let repository: ConvoyManagementRepository?
    @ObservationIgnored private let live: LiveLocationCoordinator
    @ObservationIgnored private let recording: DriveRecordingCoordinator
    @ObservationIgnored private let localExit: (String) -> Bool
    @ObservationIgnored private let now: @Sendable () -> Date
    @ObservationIgnored private var session: LiveSessionInfo?
    @ObservationIgnored private var context: DriveRecordingContext?
    @ObservationIgnored private var candidate: LiveSessionInfo?
    @ObservationIgnored private var candidateContext: DriveRecordingContext?
    @ObservationIgnored private var ended = false
    @ObservationIgnored private var decided: Set<String> = []
    @ObservationIgnored private var deadline: Date?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored nonisolated(unsafe) private var observation: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var timer: Task<Void, Never>?

    init(repository: ConvoyManagementRepository?, live: LiveLocationCoordinator,
         recording: DriveRecordingCoordinator, localExit: @escaping (String) -> Bool,
         now: @escaping @Sendable () -> Date = Date.init) {
        self.repository = repository
        self.live = live
        self.recording = recording
        self.localExit = localExit
        self.now = now
    }

    deinit { observation?.cancel(); timer?.cancel() }

    func update(session: LiveSessionInfo?, context: DriveRecordingContext?) {
        self.session = session
        self.context = context
        if let previous = candidate, let session, session.sessionId != previous.sessionId {
            if isContinuing, !session.convoyAutoStarted, session.convoyId == nil,
               LiveLocation.isSharing(session, at: now()), let context {
                recording.continueAsSingle(from: previous.sessionId, context: context)
            }
            decided.insert(previous.sessionId)
            clearCandidate()
        }
        if candidate == nil, let session, session.convoyAutoStarted,
           session.convoyId != nil, !decided.contains(session.sessionId), repository != nil {
            candidate = session
            candidateContext = context
            observeCandidate()
        }
        reconcile()
    }

    func tick() { reconcile() }

    func chooseContinue() async {
        guard presentsChoice, !isContinuing, let candidate else { return }
        guard live.wired, live.canShare else { chooseEnd(); return }
        let expected = generation
        presentsChoice = false
        isContinuing = true
        deadline = now().addingTimeInterval(20)
        let result = await live.startSharing(vehicleId: candidate.vehicleId)
        guard generation == expected, self.candidate?.sessionId == candidate.sessionId else { return }
        if result != .success { failContinuation() }
        // A successful callable is not the handoff: wait for the authoritative
        // manual-session echo before transferring the still-running recorder.
    }

    func chooseEnd() {
        guard let candidate else { return }
        let shouldStop = session?.sessionId == candidate.sessionId && live.isSharing
        finishCandidate()
        if shouldStop {
            Task { [weak self, live] in
                // Never stop an unrelated session which arrived before dispatch.
                guard live.session?.sessionId == candidate.sessionId else { return }
                let result = await live.stopSharing()
                if result != .success { self?.commandFailed = true }
            }
        }
    }

    func stop() { clearCandidate() }

    private func reconcile() {
        guard let candidate else {
            if let session, let context, LiveLocation.isSharing(session, at: now()),
               !decided.contains(session.sessionId), !live.wasLocallyStopped(sessionId: session.sessionId) {
                recording.updateContext(context)
                recording.start(context: context)
            } else {
                recording.endSession(context: context)
            }
            return
        }
        let expired = candidate.expiresAt.map { $0 <= now() } == true
            || (session?.sessionId == candidate.sessionId && session?.status == .expired)
        if expired || live.wasLocallyStopped(sessionId: candidate.sessionId)
            || candidate.convoyId.map(localExit) == true {
            finishCandidate()
            return
        }
        let sharing = session?.sessionId == candidate.sessionId && LiveLocation.isSharing(session, at: now())
        if isContinuing {
            if let deadline, now() >= deadline { failContinuation() }
            return
        }
        if ended {
            // A result from one listener must not close the recorder while the
            // other listener is catching up. The choice owns this short gap.
            deadline = nil
            presentsChoice = true
            return
        }
        if sharing, let context {
            candidateContext = context
            recording.updateContext(context)
            recording.start(context: context)
            deadline = nil
        } else {
            // RTDB can stop first. Give Firestore the same bounded reconciliation
            // window Android uses; offline/deleted documents cannot orphan GPS.
            if deadline == nil { deadline = now().addingTimeInterval(20) }
            if let deadline, now() >= deadline { finishCandidate() }
        }
    }

    private func failContinuation() {
        isContinuing = false
        deadline = nil
        commandFailed = true
        if session?.sessionId == candidate?.sessionId, live.isSharing {
            presentsChoice = true
        } else {
            finishCandidate()
        }
    }

    private func finishCandidate() {
        if let id = candidate?.sessionId { decided.insert(id) }
        let context = candidateContext
        clearCandidate()
        recording.endSession(context: context)
    }

    private func clearCandidate() {
        generation += 1
        observation?.cancel()
        timer?.cancel()
        observation = nil
        timer = nil
        candidate = nil
        candidateContext = nil
        deadline = nil
        ended = false
        presentsChoice = false
        isContinuing = false
    }

    private func observeCandidate() {
        guard let convoyId = candidate?.convoyId, let repository else { return }
        let expected = generation
        observation = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    for try await convoy in repository.observeConvoy(convoyId: convoyId) {
                        guard !Task.isCancelled, let self, self.generation == expected else { return }
                        if let convoy, convoy.viewer?.inviteStatus == .accepted {
                            self.ended = convoy.status == .ended
                        }
                        self.reconcile()
                    }
                    return
                } catch {
                    do { try await Task.sleep(for: .seconds(3)) } catch { return }
                }
            }
        }
        timer = Task { [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(1)) } catch { return }
                guard let self, self.generation == expected else { return }
                self.tick()
            }
        }
    }
}
