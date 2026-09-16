import Foundation

protocol ConvoyManagementRepository: AnyObject, Sendable {
    func list() async -> ConvoyManagementListResult
    func observeConvoy(convoyId: String) -> AsyncThrowingStream<ConvoyItem?, Error>
    func respond(convoyId: String, action: ConvoyAction) async -> ConvoyRespondResult
    func lifecycle(convoyId: String, action: ConvoyLifecycleAction) async -> ConvoyLifecycleResult
    func invite(convoyId: String, inviteeUids: [String]) async -> ConvoyInviteMutationResult
}

extension ConvoyManagementRepository {
    func observeConvoy(convoyId: String) -> AsyncThrowingStream<ConvoyItem?, Error> {
        AsyncThrowingStream { $0.finish() }
    }
}
