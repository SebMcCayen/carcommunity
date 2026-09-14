import Foundation

protocol ConvoyCreateRepository: AnyObject, Sendable {
    func list() async -> ConvoyCreateListResult
    func create(inviteeUids: [String], vehicleId: String?) async -> ConvoyCreateResult
}
