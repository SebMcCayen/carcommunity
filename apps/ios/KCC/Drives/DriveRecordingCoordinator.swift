import Foundation
import Observation

enum DriveRecordingState: Equatable, Sendable {
    case idle
    case recording(DriveRecordingSummary)
    /// The live drive is being auto-saved and auto-kept. This is intentionally
    /// not a presented state: normal session teardown never waits on a dialog.
    case saving(DriveRecordingSummary)
    /// The bounded background save failed. This is the only state that forces
    /// a prompt, preserving the exact route for an idempotent retry.
    case failed(DriveRecordingSummary, code: KccFunctionsErrorCode?)
    case kept(rideId: String)
    case discarded

    var presentsSummary: Bool {
        if case .failed = self { return true }
        return false
    }

    var summary: DriveRecordingSummary? {
        switch self {
        case .recording(let summary), .saving(let summary), .failed(let summary, _): summary
        case .idle, .kept, .discarded: nil
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
    @ObservationIgnored private let expiryTickWait: @Sendable () async throws -> Void
    @ObservationIgnored private let retryWait: @Sendable (Int) async throws -> Void

    private(set) var state: DriveRecordingState = .idle
    @ObservationIgnored private var desiredContext: DriveRecordingContext?
    @ObservationIgnored private var recorder: DriveRecorder?
    @ObservationIgnored private var stoppedAt: Date?
    @ObservationIgnored nonisolated(unsafe) private var fixesTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var authorizationTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var expiryTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var saveTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var uploadTask: Task<Void, Never>?

    init(
        repository: DriveRecordingRepository?,
        provider: LocationProvider,
        journal: DriveRecordingJournal? = nil,
        now: @escaping @Sendable () -> Date = { Date() },
        expiryTickWait: @escaping @Sendable () async throws -> Void = {
            try await Task.sleep(for: .seconds(1))
        },
        retryWait: @escaping @Sendable (Int) async throws -> Void = { attempt in
            try await Task.sleep(for: .milliseconds(attempt == 0 ? 1_000 : 4_000))
        }
    ) {
        self.repository = repository
        self.provider = provider
        self.journal = journal
        self.now = now
        self.expiryTickWait = expiryTickWait
        self.retryWait = retryWait

        guard repository != nil else { return }
        let stream = provider.authorizationUpdates()
        authorizationTask = Task { [weak self] in
            for await authorization in stream {
                guard !Task.isCancelled, let self else { return }
                if authorization.isAuthorized { self.beginDesiredRecordingIfPossible() }
            }
        }
    }

    deinit {
        fixesTask?.cancel()
        authorizationTask?.cancel()
        expiryTask?.cancel()
        saveTask?.cancel()
        uploadTask?.cancel()
    }

    var isAvailable: Bool { repository != nil }

    func updateContext(_ context: DriveRecordingContext) {
        guard desiredContext?.sourceSessionId == context.sourceSessionId
                || recorder?.context.sourceSessionId == context.sourceSessionId else { return }
        let previousExpiry = desiredContext?.expiresAt ?? recorder?.context.expiresAt
        desiredContext = context
        if previousExpiry != context.expiresAt { scheduleExpiry(for: context) }
        guard var recorder, recorder.context.sourceSessionId == context.sourceSessionId else {
            beginDesiredRecordingIfPossible()
            return
        }
        recorder.enrichContext(context)
        self.recorder = recorder
        journal?.updateContext(recorder.context)
    }

    /// Records the latest authoritative active session even while location is
    /// unavailable. A later grant from Settings/system permission immediately
    /// starts (or resumes) this same session without requiring an RTDB change.
    func start(context: DriveRecordingContext) {
        guard repository != nil else { return }
        switch state {
        case .kept, .discarded: state = .idle
        default: break
        }
        if let existing = desiredContext,
           existing.sourceSessionId != context.sourceSessionId,
           recorder != nil || state.presentsSummary || saveTask != nil { return }
        desiredContext = context
        scheduleExpiry(for: context)
        beginDesiredRecordingIfPossible()
    }

    /// Handles an authoritative inactive/nil live-session snapshot or local
    /// expiry. A cold-start journal is restored before stopping, then auto-saved
    /// and auto-kept just like Android's SingleSessionRecording flow.
    func endSession(context update: DriveRecordingContext? = nil) {
        desiredContext = nil
        expiryTask?.cancel()
        expiryTask = nil
        if recorder == nil, state == .idle,
           let restored = journal?.restore(sessionId: update?.sourceSessionId) {
            var restoredRecorder = DriveRecorder(
                startedAt: restored.startedAt,
                context: restored.context,
                restoring: restored.points
            )
            if let update { restoredRecorder.enrichContext(update) }
            recorder = restoredRecorder
            stoppedAt = restored.stoppedAt
        }
        guard let recorder, saveTask == nil else { return }
        fixesTask?.cancel()
        fixesTask = nil
        let endedAt = stoppedAt ?? now()
        stoppedAt = endedAt
        journal?.markStopped(at: endedAt)
        let summary = recorder.summary(endedAt: endedAt)
        state = .saving(summary)
        autoSave(summary: summary)
    }

    /// Manual retry for the only visible end-of-session prompt: a definitive
    /// background save failure. Uses the same sourceSessionId and frozen end time.
    func retry() {
        guard case .failed(let summary, _) = state, saveTask == nil else { return }
        state = .saving(summary)
        autoSave(summary: summary)
    }

    /// Closes a permanent refusal. No drive was stored, so clear the private
    /// route rather than leaving an impossible retry prompt forever.
    func discardFailed() {
        guard case .failed = state, saveTask == nil else { return }
        releaseRecording(clearJournal: true)
        state = .discarded
    }

    /// Drops all exact route data on sign-out/account switch.
    func reset() {
        fixesTask?.cancel()
        expiryTask?.cancel()
        saveTask?.cancel()
        uploadTask?.cancel()
        fixesTask = nil
        expiryTask = nil
        saveTask = nil
        uploadTask = nil
        desiredContext = nil
        releaseRecording(clearJournal: true)
        state = .idle
    }

    private func beginDesiredRecordingIfPossible() {
        guard repository != nil, provider.authorization.isAuthorized,
              let context = desiredContext,
              recorder == nil, saveTask == nil, state == .idle else { return }
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

    private func scheduleExpiry(for context: DriveRecordingContext) {
        expiryTask?.cancel()
        guard let expiresAt = context.expiresAt else { return }
        expiryTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                if self.now() >= expiresAt {
                    self.endSession(context: context)
                    return
                }
                do { try await self.expiryTickWait() } catch { return }
            }
        }
    }

    private func autoSave(summary: DriveRecordingSummary) {
        guard let repository, let recorder, let stoppedAt else { return }
        let request = recorder.request(endedAt: stoppedAt, title: nil)
        saveTask = Task { [weak self] in
            guard let self else { return }
            do {
                let result = try await self.saveWithRetry(request, repository: repository)
                try Task.checkCancellation()
                // The callable result is the durable boundary: keep immediately,
                // then let the route upload finish independently. A slow or failed
                // Storage write must never hold the saved drive in a pending state.
                self.saveTask = nil
                self.releaseRecording(clearJournal: true)
                self.state = .kept(rideId: result.rideId)
                if let path = result.routePath, !request.points.isEmpty {
                    self.uploadTask?.cancel()
                    self.uploadTask = Task { [weak self] in
                        try? await repository.uploadRoute(request.points, to: path)
                        guard !Task.isCancelled else { return }
                        self?.uploadTask = nil
                    }
                }
            } catch is CancellationError {
                // reset/sign-out owns cleanup and state.
            } catch let error as KccFunctionsError {
                guard !Task.isCancelled else { return }
                self.saveTask = nil
                self.state = .failed(summary, code: error.code)
            } catch {
                guard !Task.isCancelled else { return }
                self.saveTask = nil
                self.state = .failed(summary, code: nil)
            }
        }
    }

    private func saveWithRetry(
        _ request: DriveSaveRequest,
        repository: DriveRecordingRepository
    ) async throws -> DriveSaveResult {
        for attempt in 0..<3 {
            do { return try await repository.save(request) }
            catch is CancellationError { throw CancellationError() }
            catch {
                let code = (error as? KccFunctionsError)?.code
                let transient = code == .internalError || code == .unavailable
                guard transient, attempt < 2 else { throw error }
                try await retryWait(attempt)
            }
        }
        throw DriveRecordingRepositoryError.malformedResponse
    }

    private func releaseRecording(clearJournal: Bool) {
        recorder = nil
        stoppedAt = nil
        if clearJournal { journal?.clear() }
    }

    private func accept(_ fix: LocationFix) {
        guard var recorder, case .recording = state else { return }
        guard recorder.add(fix) else { return }
        if let point = recorder.points.last { journal?.append(point) }
        self.recorder = recorder
        state = .recording(recorder.summary(endedAt: now()))
    }
}
