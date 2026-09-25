import Foundation

protocol ConvoyFollowMeRepository: AnyObject, Sendable {
    func setFollowMe(convoyId: String, active: Bool) async -> Bool?
    func writeTrail(convoyId: String, polyline: String) async -> Bool
    func states(convoyId: String) -> AsyncStream<ConvoyFollowMeState?>
}
