import Foundation

protocol ConvoyReactionRepository: AnyObject, Sendable {
    func send(
        convoyId: String,
        kind: ConvoyReactionKind,
        clientId: String
    ) async -> ConvoyReactionSendResult

    /// Emits only server-committed reactions created after the subscription starts.
    func reactions(
        convoyId: String,
        since: Date
    ) -> AsyncStream<ConvoyReactionEvent>
}
