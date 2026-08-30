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

/// An RSVP write failure carrying ONLY the bare Firestore status name
/// (`PERMISSION_DENIED`, `UNAVAILABLE`, …) when one was available — the same
/// PII-safe diagnosis rule as ``EventsListSnapshot/failed(code:)``: a status
/// name is the whole diagnosis; exception text (which can embed the failing
/// document path and the project id) is never carried. Pure Swift so the
/// coordinator branches on the CODE with no Firebase dependency.
struct RsvpWriteError: Error, Equatable, Sendable {
    /// The bare Firestore status name, or nil when the failure carried none.
    let code: String?
}

/// Events read + RSVP operations — the iOS port of Android's
/// `EventsRepository.kt`, restricted to the list and detail + RSVP slices.
/// Firebase-free protocol so the coordinators and screens are unit-testable
/// with fakes.
///
/// Reads are direct Firestore snapshot listeners, mirroring Android:
/// published teasers are readable by any authenticated user
/// (firebase/firestore.rules events/{id}); the member-gated
/// `details/private` document is enforced by the Security Rules. Writing an
/// RSVP is a direct owner write of exactly `{ status, updatedAt }`
/// (rules-validated, `validRsvpDocument()`); the backend `events-onRsvpWrite`
/// trigger maintains the public `rsvpCounts` tally. Create/check-in writes
/// are deliberately absent — they arrive with later slices, through the
/// ``KccFunctionsClient`` seam.
protocol EventsRepository: AnyObject, Sendable {
    /// Published events, soonest first, bounded to
    /// ``Events/publishedEventsQueryLimit``. Each call returns a fresh stream
    /// backed by its own listener; terminating the stream (dropping the
    /// iteration) detaches the listener.
    func publishedEvents() -> AsyncStream<EventsListSnapshot>

    /// A single event's teaser doc; nil when missing/unreadable (Android's
    /// `observeEvent`). The stream emits SETTLED reads only — the caller
    /// supplies the loading state before the first emission.
    func event(withId eventId: String) -> AsyncStream<EventSummary?>

    /// Member-gated detail; emits nil when denied (non-member) or missing
    /// (Android's `observeEventDetail`).
    func eventDetail(eventId: String) -> AsyncStream<EventDetail?>

    /// The caller's own RSVP answer; nil when they have not responded
    /// (Android's `observeMyRsvp`).
    func myRsvp(eventId: String, uid: String) -> AsyncStream<RsvpStatus?>

    /// Writes/updates the caller's RSVP answer — a direct owner write of
    /// exactly `{ status, updatedAt: serverTimestamp }` (Android's `setRsvp`).
    /// - Throws: ``RsvpWriteError`` with the PII-safe status name.
    func submitRsvp(eventId: String, uid: String, status: RsvpStatus) async throws

    /// The signed-in user's uid, or nil with no session. Android threads the
    /// uid from the auth state into `EventsRoute`; the iOS events feature is
    /// self-contained (the shell passes no identity), so the repository —
    /// which already owns the Firebase seam — answers it instead.
    func currentUserId() -> String?
}
