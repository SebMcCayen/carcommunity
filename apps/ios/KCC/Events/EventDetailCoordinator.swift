import Foundation
import Observation

/// Loading vs settled for a single event's teaser — the iOS port of Android's
/// `EventLoad` in `EventsRoute.kt`: a nil event on the very first composition
/// must read as "loading", not "error", so the distinction is modelled rather
/// than inferred from nil.
enum EventDetailUiState: Equatable, Sendable {
    /// Waiting for the first teaser snapshot.
    case loading
    /// The teaser settled nil — missing or unreadable (Android renders
    /// `events_errorDetail` with a retry).
    case failed
    /// The teaser doc, updated live by the listener.
    case loaded(EventSummary)
}

/// UI-facing status of an in-flight RSVP write — Android's `RsvpStatusUi`,
/// with the failure carrying the PII-safe Firestore status name (the same
/// diagnostics posture as ``EventsListUiState/failed(code:)``); the screen
/// renders a generic message either way and branches only on the case.
enum RsvpSubmitState: Equatable, Sendable {
    case idle
    case saving
    case failed(code: String?)
}

/// Orchestrates one event's detail page: the teaser listener, the
/// member-gated detail listener, the caller's own RSVP listener, and the RSVP
/// write — the iOS counterpart of Android's detail wiring in `EventsRoute`
/// plus its `RsvpCoordinator`. Pure Swift (no Firebase/SwiftUI types) so it
/// is unit-testable with a fake repository.
///
/// The OBSERVED RSVP document drives the selected answer — the write is not
/// applied optimistically; ``rsvpState`` only tracks the in-flight write
/// (Android: "The observed RSVP document drives the selected answer; this
/// only tracks the write"). Firestore's local latency compensation makes a
/// successful write reflect immediately anyway.
@MainActor
@Observable
final class EventDetailCoordinator {
    private let repository: EventsRepository
    let eventId: String
    /// The signed-in user's uid, resolved once at construction. Nil (no
    /// session) hides the RSVP affordance — there is no owner document to
    /// write.
    private let uid: String?
    /// Whether the viewer passes the member gate — mirrors Android's
    /// `passesMemberGate` parameter. While ``MemberGating/enabled`` is false
    /// every signed-in user passes; re-enabling the gate requires threading
    /// the real `activeMember` entitlement into this flag (a deliberate,
    /// documented seam — same as Android).
    let passesMemberGate: Bool

    private(set) var state: EventDetailUiState = .loading
    /// The member-gated detail; nil while unsettled, denied, or absent.
    private(set) var detail: EventDetail?
    /// The caller's own answer from the rsvps/{uid} listener; nil = not
    /// answered.
    private(set) var myRsvp: RsvpStatus?
    private(set) var rsvpState: RsvpSubmitState = .idle

    /// The live stream-consuming tasks. `nonisolated(unsafe)` so the
    /// nonisolated deinit can cancel them — every mutation happens on the
    /// main actor, and by the time deinit runs no other reference exists, so
    /// the unguarded access cannot race (same pattern as
    /// ``EventsCoordinator``).
    @ObservationIgnored
    nonisolated(unsafe) private var subscriptions: [Task<Void, Never>] = []
    /// The in-flight RSVP write, if any — kept so deinit can cancel it (the
    /// same `nonisolated(unsafe)` reasoning as `subscriptions`).
    @ObservationIgnored
    nonisolated(unsafe) private var submission: Task<Void, Never>?
    /// Whether the member-gated detail listener has been attached for the
    /// current subscription generation (see ``subscribeDetailIfNeeded(for:)``).
    @ObservationIgnored
    private var detailSubscribed = false

    init(
        repository: EventsRepository,
        eventId: String,
        passesMemberGate: Bool = MemberGating.allows(isActiveMember: false)
    ) {
        self.repository = repository
        self.eventId = eventId
        self.uid = repository.currentUserId()
        self.passesMemberGate = passesMemberGate
    }

    deinit {
        for task in subscriptions {
            task.cancel()
        }
        submission?.cancel()
    }

    // MARK: - Derived gates

    /// Whether the RSVP row renders: gate-passer + published event + a
    /// session to write as (Android's `Events.canRsvp`, plus the uid the
    /// route guarantees there).
    var canRsvp: Bool {
        guard case .loaded(let event) = state else { return false }
        return uid != nil && Events.canRsvp(passesMemberGate: passesMemberGate, status: event.status)
    }

    /// Whether the member-gated detail card renders (Android's
    /// `Events.canSeeDetails`).
    var canSeeDetails: Bool {
        guard case .loaded(let event) = state else { return false }
        return Events.canSeeDetails(passesMemberGate: passesMemberGate, status: event.status)
    }

    // MARK: - Lifecycle

    /// Begins observing on first appearance. Idempotent: a second call (e.g.
    /// SwiftUI re-running `.task`) keeps the live listeners and their state.
    func start() {
        guard subscriptions.isEmpty else { return }
        subscribe()
    }

    /// The "try again" affordance from the failed state — tears the listeners
    /// down, returns to ``EventDetailUiState/loading``, and re-subscribes
    /// (Android's retry re-keys the observation).
    func reload() {
        subscribe()
    }

    private func subscribe() {
        for task in subscriptions {
            task.cancel()
        }
        subscriptions = []
        state = .loading
        detail = nil
        detailSubscribed = false
        myRsvp = nil
        // A (re)subscribe is a fresh page: cancel the in-flight RSVP write's
        // state updates and unlock the buttons, so a retry from a failed
        // teaser can never stay stuck on `saving` behind a stale task. The
        // write itself may still land server-side — the rsvps/{uid} listener
        // reports whatever it produced, exactly as for any concurrent write.
        submission?.cancel()
        submission = nil
        rsvpState = .idle

        // The streams are created HERE, not inside the tasks, so the
        // listeners attach synchronously — reload() has re-subscribed by the
        // time it returns (same reasoning as EventsCoordinator.subscribe).
        let teaser = repository.event(withId: eventId)
        subscriptions.append(consume(teaser) { coordinator, event in
            // Settled nil = missing/unreadable (Android's error text);
            // a live update keeps the loaded teaser fresh.
            coordinator.state = event.map(EventDetailUiState.loaded) ?? .failed
            if let event {
                coordinator.subscribeDetailIfNeeded(for: event.status)
            }
        })

        if let uid {
            let rsvpStream = repository.myRsvp(eventId: eventId, uid: uid)
            subscriptions.append(consume(rsvpStream) { coordinator, answer in
                coordinator.myRsvp = answer
            })
        }
    }

    /// Attaches the member-gated `details/private` listener the first time
    /// the teaser shows a state the rules would actually serve: member gate
    /// passed AND published (firestore.rules gates the detail read on the
    /// parent's `published` status, so subscribing for a completed/cancelled
    /// event would only produce denied reads). A non-gate-passer never
    /// subscribes at all — the detail simply stays nil (Android's
    /// settled-empty flow). Once attached the listener is kept: if the event
    /// later leaves `published` the denied read emits nil, which is the
    /// correct value then.
    private func subscribeDetailIfNeeded(for status: EventStatus) {
        guard passesMemberGate, !detailSubscribed,
            Events.canSeeDetails(passesMemberGate: passesMemberGate, status: status)
        else { return }
        detailSubscribed = true
        let detailStream = repository.eventDetail(eventId: eventId)
        subscriptions.append(consume(detailStream) { coordinator, detail in
            coordinator.detail = detail
        })
    }

    /// Folds one stream's emissions into coordinator state. `self` is
    /// captured weakly so the long-lived stream tasks never retain the
    /// coordinator (see ``EventsCoordinator/subscribe()``).
    private func consume<Value: Sendable>(
        _ stream: AsyncStream<Value>,
        into apply: @escaping @MainActor (EventDetailCoordinator, Value) -> Void
    ) -> Task<Void, Never> {
        Task { [weak self] in
            for await value in stream {
                guard !Task.isCancelled, let self else { return }
                apply(self, value)
            }
        }
    }

    // MARK: - RSVP write

    /// Submits an RSVP answer — Android's `RsvpCoordinator.submit`: at most
    /// one write in flight (a tap while saving is ignored), success returns
    /// to idle (the rsvps/{uid} listener confirms the new answer), a failure
    /// lands in ``RsvpSubmitState/failed(code:)`` for the screen's transient
    /// error line.
    func submitRsvp(_ answer: RsvpStatus) {
        guard rsvpState != .saving, let uid else { return }
        rsvpState = .saving
        submission = Task { [weak self, repository, eventId] in
            do {
                try await repository.submitRsvp(eventId: eventId, uid: uid, status: answer)
                guard !Task.isCancelled, let self else { return }
                self.rsvpState = .idle
            } catch {
                guard !Task.isCancelled, let self else { return }
                // Branch on the CODE, never the message: the repository
                // throws RsvpWriteError carrying only the PII-safe status
                // name; anything else degrades to a code-less failure.
                self.rsvpState = .failed(code: (error as? RsvpWriteError)?.code)
            }
        }
    }

    /// Clears a failure so the transient error line goes away — Android's
    /// `RsvpCoordinator.reset` (a no-op unless failed).
    func resetRsvpFailure() {
        if case .failed = rsvpState {
            rsvpState = .idle
        }
    }
}
