import Foundation

protocol ConvoyManagementRepository: AnyObject, Sendable {
    func list() async -> ConvoyManagementListResult
    func respond(convoyId: String, action: ConvoyAction) async -> ConvoyRespondResult
}
