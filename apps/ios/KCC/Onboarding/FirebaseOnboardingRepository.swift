import Foundation

final class FirebaseOnboardingRepository: OnboardingRepository, @unchecked Sendable {
    private let functions: KccFunctionsClient
    private init(functions: KccFunctionsClient) { self.functions = functions }

    func completeOnboarding(
        displayName: String,
        anonymousPartnerStatsOptIn: Bool?
    ) async throws {
        _ = try await functions.call(
            "auth-completeOnboarding",
            payload: Self.payload(displayName: displayName, anonymousPartnerStatsOptIn: anonymousPartnerStatsOptIn)
        )
    }

    /// Consent time is stamped by the callable, never supplied by the device.
    static func payload(displayName: String, anonymousPartnerStatsOptIn: Bool?) -> [String: Any] {
        var payload: [String: Any] = [
            "licenceConfirmed": true,
            "termsAccepted": true,
            "privacyPolicyAccepted": true,
            "displayName": displayName,
        ]
        if let anonymousPartnerStatsOptIn {
            payload["anonymousPartnerStatsOptIn"] = anonymousPartnerStatsOptIn
            payload["anonymousPartnerStatsConsentVersion"] = PrivacySettingsChoices.partnerStatsConsentVersion
        }
        return payload
    }

    static func createIfAvailable() -> OnboardingRepository? {
        guard let functions = KccFunctionsClient.createIfAvailable() else { return nil }
        return FirebaseOnboardingRepository(functions: functions)
    }
}
