import Foundation

/// One emission of the published-events listener.
///
/// The iOS port of Android's `EventsListState` minus `Loading`: a repository
/// stream only ever emits SETTLED results (a snapshot or a failure), and the
/// coordinator supplies the loading state before the first emission — the
/// same split Android gets from `collectAsState(initial = Loading)`.
enum EventsListSnapshot: Equatable, Sendable {
    /// The listener failed. `code` is the bare Firestore status name when one
    /// was available (e.g. `FAILED_PRECONDITION` for a missing composite
    /// index, `PERMISSION_DENIED` for a rules denial, `UNAVAILABLE` when
    /// offline) — a stable, PII-safe diagnosis carried so callers can tell a
    /// STRUCTURAL fault apart from "this phone has no signal". A bare status
    /// name, never exception text (which can embed the failing query and the
    /// project id) — the same rule as Android's `firestoreCode()`.
    case failed(code: String?)
    /// A fresh snapshot of the published events, already list-sorted
    /// (``Events/sortedForList(_:)``).
    case loaded([EventSummary])
}

/// Events read operations — the iOS port of Android's `EventsRepository.kt`,
/// restricted to the read-only list slice. Firebase-free protocol so the
/// coordinator and screens are unit-testable with fakes.
///
/// Reads are direct Firestore snapshot listeners, mirroring Android:
/// published teasers are readable by any authenticated user
/// (firebase/firestore.rules events/{id}); member-gated details stay behind
/// the Security Rules and are not read here. RSVP/create/check-in writes are
/// deliberately absent — they arrive with later slices, through the
/// ``KccFunctionsClient`` seam.
protocol EventsRepository: AnyObject, Sendable {
    /// Published events, soonest first, bounded to
    /// ``Events/publishedEventsQueryLimit``. Each call returns a fresh stream
    /// backed by its own listener; terminating the stream (dropping the
    /// iteration) detaches the listener.
    func publishedEvents() -> AsyncStream<EventsListSnapshot>
}
