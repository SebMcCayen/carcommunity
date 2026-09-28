import Foundation

final class FirebaseOnboardingRepository: OnboardingRepository, @unchecked Sendable {
    private let functions: KccFunctionsClient
    private init(functions: KccFunctionsClient) { self.functions = functions }

    func completeOnboarding(
        displayName: String,
        anonymousPartnerStatsOptIn: Bool?
    ) async throws {
        var payload: [String: Any] = [
            "licenceConfirmed": true,
            "termsAccepted": true,
            "privacyPolicyAccepted": true,
            "displayName": displayName,
        ]
        if let anonymousPartnerStatsOptIn {
            payload["anonymousPartnerStatsOptIn"] = anonymousPartnerStatsOptIn
        }
        _ = try await functions.call("auth-completeOnboarding", payload: payload)
    }

    static func createIfAvailable() -> OnboardingRepository? {
        guard let functions = KccFunctionsClient.createIfAvailable() else { return nil }
        return FirebaseOnboardingRepository(functions: functions)
    }
}
