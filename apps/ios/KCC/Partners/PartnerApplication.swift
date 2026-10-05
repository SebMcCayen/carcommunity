import Foundation
import Observation

/// The member-entered partner application. Field limits mirror
/// `functions/src/partners/partners-core.ts`; optional blank values are
/// removed before they cross the callable boundary.
struct PartnerApplicationForm: Equatable, Sendable {
    var companyName = ""
    var category: PartnerCategory?
    var contactName = ""
    var contactEmail = ""
    var contactPhone = ""
    var websiteURL = ""
    var message = ""
}

struct PartnerApplicationInput: Equatable, Sendable {
    let companyName: String
    let category: PartnerCategory
    let contactName: String
    let contactEmail: String
    let contactPhone: String?
    let websiteURL: String?
    let message: String?

    var payload: [String: Any] {
        var payload: [String: Any] = [
            "companyName": companyName,
            "category": category.rawValue,
            "contactName": contactName,
            "contactEmail": contactEmail,
        ]
        if let contactPhone { payload["contactPhone"] = contactPhone }
        if let websiteURL { payload["websiteUrl"] = websiteURL }
        if let message { payload["message"] = message }
        return payload
    }
}

enum PartnerApplicationValidationError: Equatable, Sendable {
    case companyName, category, contactName, contactEmail, websiteURL, fieldTooLong

    var localizationKey: String {
        switch self {
        case .contactEmail, .websiteURL, .fieldTooLong:
            "partners.submitErrorInvalid"
        case .companyName, .category, .contactName:
            "partners.fieldRequired"
        }
    }
}

enum PartnerApplications {
    static let companyNameLimit = 150
    static let contactNameLimit = 120
    static let emailLimit = 254
    static let phoneLimit = 30
    static let websiteLimit = 500
    static let messageLimit = 2_000

    static func validate(_ form: PartnerApplicationForm) -> PartnerApplicationValidationError? {
        let company = form.companyName.partnerTrimmed
        let name = form.contactName.partnerTrimmed
        let email = form.contactEmail.partnerTrimmed
        guard !company.isEmpty else { return .companyName }
        guard form.category != nil else { return .category }
        guard !name.isEmpty else { return .contactName }
        guard looksLikeEmail(email) else { return .contactEmail }
        let website = normalizedWebsiteURL(form.websiteURL)
        guard website.map(isValidWebsiteURL) ?? true else { return .websiteURL }
        // Zod/JavaScript counts UTF-16 code units. Using the same measure
        // prevents emoji and composed characters from passing locally only
        // to be rejected by the callable's max-length validation.
        guard company.utf16.count <= companyNameLimit,
              name.utf16.count <= contactNameLimit,
              email.utf16.count <= emailLimit,
              form.contactPhone.partnerTrimmed.utf16.count <= phoneLimit,
              (website?.utf16.count ?? 0) <= websiteLimit,
              form.message.partnerTrimmed.utf16.count <= messageLimit
        else { return .fieldTooLong }
        return nil
    }

    static func input(from form: PartnerApplicationForm) -> PartnerApplicationInput? {
        guard validate(form) == nil, let category = form.category else { return nil }
        return PartnerApplicationInput(
            companyName: form.companyName.partnerTrimmed,
            category: category,
            contactName: form.contactName.partnerTrimmed,
            contactEmail: form.contactEmail.partnerTrimmed,
            contactPhone: form.contactPhone.partnerTrimmed.partnerNilIfEmpty,
            websiteURL: normalizedWebsiteURL(form.websiteURL),
            message: form.message.partnerTrimmed.partnerNilIfEmpty
        )
    }

    static func normalizedWebsiteURL(_ raw: String) -> String? {
        let value = raw.partnerTrimmed
        guard !value.isEmpty else { return nil }
        if value.lowercased().hasPrefix("http://") || value.lowercased().hasPrefix("https://") {
            return value
        }
        return "https://\(value)"
    }

    private static func looksLikeEmail(_ value: String) -> Bool {
        // Mirrors Zod 4's default `email` pattern used by the callable.
        let pattern = #"^(?!\.)(?!.*\.\.)([A-Za-z0-9_'+\-.]*)[A-Za-z0-9_+-]@([A-Za-z0-9][A-Za-z0-9-]*\.)+[A-Za-z]{2,}$"#
        return value.range(of: pattern, options: .regularExpression) != nil
    }

    private static func isValidWebsiteURL(_ value: String) -> Bool {
        guard let components = URLComponents(string: value),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = components.host,
              !host.isEmpty
        else { return false }
        return true
    }
}

private extension String {
    var partnerTrimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
    var partnerNilIfEmpty: String? { isEmpty ? nil : self }
}

protocol PartnerApplicationRepository: Sendable {
    func submit(_ input: PartnerApplicationInput) async throws
}

enum PartnerApplicationFailure: Equatable, Sendable {
    case invalidInput, duplicate, unknown
}

enum PartnerApplicationSubmission: Equatable, Sendable {
    case idle, submitting, done, failed(PartnerApplicationFailure)
}

@MainActor
@Observable
final class PartnerApplicationCoordinator {
    private let repository: PartnerApplicationRepository
    private(set) var submission: PartnerApplicationSubmission = .idle

    init(repository: PartnerApplicationRepository) {
        self.repository = repository
    }

    func submit(_ input: PartnerApplicationInput) async {
        guard submission != .submitting else { return }
        submission = .submitting
        do {
            try await repository.submit(input)
            guard !Task.isCancelled else {
                submission = .idle
                return
            }
            submission = .done
        } catch is CancellationError {
            submission = .idle
        } catch let error as KccFunctionsError {
            switch error.code {
            case .invalidArgument: submission = .failed(.invalidInput)
            case .alreadyExists: submission = .failed(.duplicate)
            default: submission = .failed(.unknown)
            }
        } catch {
            submission = .failed(.unknown)
        }
    }

    /// Clears a settled result when the member opens the form again. An
    /// in-flight request is deliberately not reset, preserving single-flight.
    func prepareForPresentation() {
        guard submission != .submitting else { return }
        submission = .idle
    }
}
