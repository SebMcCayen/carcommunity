import Foundation
import Observation

enum DriveRecordingState: Equatable, Sendable {
    case idle
    case recording(DriveRecordingSummary)
    case prompt(DriveRecordingSummary)
    case saving(DriveRecordingSummary)
    case failed(DriveRecordingSummary, code: KccFunctionsErrorCode?)
    case saved(DriveRecordingSummary, rideId: String)
    case discarded

    var presentsSummary: Bool {
        switch self {
        case .prompt, .saving, .failed, .saved: true
        case .idle, .recording, .discarded: false
        }
    }

    var summary: DriveRecordingSummary? {
        switch self {
        case .recording(let summary), .prompt(let summary), .saving(let summary),
             .failed(let summary, _), .saved(let summary, _): summary
        case .idle, .discarded: nil
        }
    }
}

@MainActor
@Observable
final class DriveRecordingCoordinator {
    private let repository: DriveRecordingRepository?
    private let provider: LocationProvider
    @ObservationIgnored private let now: @Sendable () -> Date
    @ObservationIgnored private let journal: DriveRecordingJournal?

    private(set) var state: DriveRecordingState = .idle
    @ObservationIgnored private var recorder: DriveRecorder?
    @ObservationIgnored private var stoppedAt: Date?
    @ObservationIgnored nonisolated(unsafe) private var fixesTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var saveTask: Task<Void, Never>?

    init(
        repository: DriveRecordingRepository?,
        provider: LocationProvider,
        journal: DriveRecordingJournal? = nil,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.repository = repository
        self.provider = provider
        self.journal = journal
        self.now = now
    }

    deinit {
        fixesTask?.cancel()
        saveTask?.cancel()
    }

    var isAvailable: Bool { repository != nil }

    func updateContext(_ context: DriveRecordingContext) {
        guard var recorder, recorder.context.sourceSessionId == context.sourceSessionId else {
            return
        }
        recorder.enrichContext(context)
        self.recorder = recorder
        journal?.updateContext(recorder.context)
    }

    func start(context: DriveRecordingContext) {
        guard repository != nil, provider.authorization.isAuthorized else { return }
        if recorder?.context.sourceSessionId == context.sourceSessionId {
            updateContext(context)
            return
        }
        // An active recording survives a session echo/restart instead of
        // dropping its accumulated private route. The next recording starts
        // only after this one has been resolved by Save or Discard.
        if recorder != nil { return }
        guard !state.presentsSummary else { return }
        fixesTask?.cancel()
        let recorder: DriveRecorder
        if let restored = journal?.restore(sessionId: context.sourceSessionId) {
            recorder = DriveRecorder(
                startedAt: restored.startedAt,
                context: context,
                restoring: restored.points
            )
        } else {
            journal?.clear()
            recorder = DriveRecorder(startedAt: now(), context: context)
            journal?.begin(context: context, startedAt: recorder.startedAt)
        }
        self.recorder = recorder
        stoppedAt = nil
        state = .recording(recorder.summary(endedAt: now()))
        let stream = provider.fixes()
        fixesTask = Task { [weak self] in
            for await fix in stream {
                guard !Task.isCancelled, let self else { return }
                self.accept(fix)
            }
        }
    }

    /// Restores a stopped drive after process death and re-raises its forced
    /// Save/Discard summary. A supplied stopped session enriches the journal's
    /// context; nil uses the owner-scoped journal as recorded.
    func restorePending(context update: DriveRecordingContext?) {
        guard repository != nil, recorder == nil, state == .idle,
              let restored = journal?.restore(sessionId: update?.sourceSessionId) else { return }
        var recorder = DriveRecorder(
            startedAt: restored.startedAt,
            context: restored.context,
            restoring: restored.points
        )
        if let update { recorder.enrichContext(update) }
        let endedAt = restored.stoppedAt ?? now()
        self.recorder = recorder
        stoppedAt = endedAt
        state = .prompt(recorder.summary(endedAt: endedAt))
    }

    func stop() {
        guard let recorder, case .recording = state else { return }
        fixesTask?.cancel()
        fixesTask = nil
        let endedAt = now()
        stoppedAt = endedAt
        journal?.markStopped(at: endedAt)
        state = .prompt(recorder.summary(endedAt: endedAt))
    }

    func save(title: String?) async {
        let summary: DriveRecordingSummary
        switch state {
        case .prompt(let value), .failed(let value, _): summary = value
        default: return
        }
        guard saveTask == nil, let repository, let recorder, let stoppedAt else { return }
        state = .saving(summary)
        let request = recorder.request(endedAt: stoppedAt, title: title)
        let task = Task {
            do {
                let result = try await repository.save(request)
                try Task.checkCancellation()
                // A route upload is best-effort after the durable ride document.
                // A failed upload must not lie about the successful drive save;
                // History degrades to its documented route-unavailable state.
                if let path = result.routePath, !request.points.isEmpty {
                    try? await repository.uploadRoute(request.points, to: path)
                }
                try Task.checkCancellation()
                guard !Task.isCancelled else { return }
                self.state = .saved(summary, rideId: result.rideId)
            } catch let error as KccFunctionsError {
                guard !Task.isCancelled else { return }
                self.state = .failed(summary, code: error.code)
            } catch {
                guard !Task.isCancelled else { return }
                self.state = .failed(summary, code: nil)
            }
            self.saveTask = nil
        }
        saveTask = task
        await task.value
    }

    func discard() {
        switch state {
        case .prompt, .failed: break
        default: return
        }
        guard saveTask == nil else { return }
        recorder = nil
        stoppedAt = nil
        journal?.clear()
        state = .discarded
    }

    func finishSummary() {
        guard case .saved = state else { return }
        recorder = nil
        stoppedAt = nil
        journal?.clear()
        state = .idle
    }

    /// Drops all exact route data on sign-out/account switch.
    func reset() {
        fixesTask?.cancel()
        saveTask?.cancel()
        fixesTask = nil
        saveTask = nil
        recorder = nil
        stoppedAt = nil
        journal?.clear()
        state = .idle
    }

    private func accept(_ fix: LocationFix) {
        guard var recorder, case .recording = state else { return }
        guard recorder.add(fix) else { return }
        if let point = recorder.points.last { journal?.append(point) }
        self.recorder = recorder
        state = .recording(recorder.summary(endedAt: now()))
    }
}
