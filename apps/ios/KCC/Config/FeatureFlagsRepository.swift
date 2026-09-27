import Foundation

protocol FeatureFlagsRepository: AnyObject, Sendable {
    func fetch() async throws -> FeatureFlags
    func updates() -> AsyncStream<FeatureFlags>
}
