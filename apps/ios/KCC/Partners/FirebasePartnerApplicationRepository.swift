import Foundation

final class FirebasePartnerApplicationRepository: PartnerApplicationRepository, @unchecked Sendable {
    private let functions: KccFunctionsClient

    private init(functions: KccFunctionsClient) {
        self.functions = functions
    }

    func submit(_ input: PartnerApplicationInput) async throws {
        _ = try await functions.call("partners-submitApplication", payload: input.payload)
    }

    /// Config-less builds intentionally omit this feature entry instead of
    /// touching Firebase before it has been configured.
    static func createIfAvailable() -> PartnerApplicationRepository? {
        KccFunctionsClient.createIfAvailable().map(FirebasePartnerApplicationRepository.init)
    }
}

