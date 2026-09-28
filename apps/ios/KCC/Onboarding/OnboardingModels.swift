import Foundation

enum OnboardingForm {
    static let displayNameMaxLength = 120

    static func normalizedDisplayName(_ raw: String) -> String? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty || value.count > displayNameMaxLength ? nil : value
    }

    static func canSubmit(
        licenceConfirmed: Bool,
        termsAccepted: Bool,
        privacyAccepted: Bool,
        displayName: String
    ) -> Bool {
        licenceConfirmed && termsAccepted && privacyAccepted
            && normalizedDisplayName(displayName) != nil
    }
}

enum OnboardingStatus: Equatable, Sendable {
    case idle
    case submitting
    case done
    case failed
}

protocol OnboardingRepository: AnyObject, Sendable {
    func completeOnboarding(
        displayName: String,
        anonymousPartnerStatsOptIn: Bool?
    ) async throws
}
