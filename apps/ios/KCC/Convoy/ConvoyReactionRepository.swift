import Foundation

protocol ConvoyReactionRepository: AnyObject, Sendable {
    func send(
        convoyId: String,
        kind: ConvoyReactionKind,
        clientId: String
    ) async -> ConvoyReactionSendResult

    /// Emits only server-committed reactions observed after the subscription's
    /// initial server snapshot. Repository implementations must not compare a
    /// client clock with a server timestamp to decide freshness.
    func reactions(convoyId: String) -> AsyncStream<ConvoyReactionEvent>
}
