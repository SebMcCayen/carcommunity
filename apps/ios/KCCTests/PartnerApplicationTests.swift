import XCTest

@testable import KCC

final class PartnerApplicationTests: XCTestCase {
    private final class Repository: PartnerApplicationRepository, @unchecked Sendable {
        var inputs: [PartnerApplicationInput] = []
        var error: Error?

        func submit(_ input: PartnerApplicationInput) async throws {
            inputs.append(input)
            if let error { throw error }
        }
    }

    private func validForm() -> PartnerApplicationForm {
        PartnerApplicationForm(
            companyName: "  KCC Garage  ", category: .workshop,
            contactName: " Ada ", contactEmail: "ada@example.com",
            contactPhone: " 0701234567 ", websiteURL: " example.com ", message: " Hello "
        )
    }

    func testValidationRequiresAuthorityFieldsAndValidEmail() {
        var form = validForm()
        XCTAssertNil(PartnerApplications.validate(form))
        form.companyName = " "
        XCTAssertEqual(PartnerApplications.validate(form), .companyName)
        form = validForm(); form.category = nil
        XCTAssertEqual(PartnerApplications.validate(form), .category)
        form = validForm(); form.contactName = ""
        XCTAssertEqual(PartnerApplications.validate(form), .contactName)
        form = validForm(); form.contactEmail = "a@b"
        XCTAssertEqual(PartnerApplications.validate(form), .contactEmail)
    }

    func testValidationMessagesDistinguishInvalidValuesFromMissingFields() {
        XCTAssertEqual(
            PartnerApplicationValidationError.contactEmail.localizationKey,
            "partners.submitErrorInvalid"
        )
        XCTAssertEqual(
            PartnerApplicationValidationError.websiteURL.localizationKey,
            "partners.submitErrorInvalid"
        )
        XCTAssertEqual(
            PartnerApplicationValidationError.fieldTooLong.localizationKey,
            "partners.submitErrorInvalid"
        )
        for error in [
            PartnerApplicationValidationError.companyName, .category, .contactName,
        ] {
            XCTAssertEqual(error.localizationKey, "partners.fieldRequired")
        }
    }

    func testInputTrimsFieldsNormalizesWebsiteAndOmitsBlanks() {
        var form = validForm()
        form.contactPhone = " "
        form.message = ""
        let input = PartnerApplications.input(from: form)
        XCTAssertEqual(input?.companyName, "KCC Garage")
        XCTAssertEqual(input?.contactName, "Ada")
        XCTAssertEqual(input?.websiteURL, "https://example.com")
        XCTAssertNil(input?.contactPhone)
        XCTAssertNil(input?.message)
        XCTAssertNil(input?.payload["contactPhone"])
        XCTAssertEqual(input?.payload["category"] as? String, "workshop")
    }

    func testExistingHttpSchemeIsPreservedCaseInsensitively() {
        XCTAssertEqual(
            PartnerApplications.normalizedWebsiteURL("HTTPS://example.com"),
            "HTTPS://example.com"
        )
        XCTAssertNil(PartnerApplications.normalizedWebsiteURL("  "))
    }

    func testValidationRejectsBackendInvalidEmailAndWebsiteVectors() {
        for invalidEmail in [
            "a@.com",
            "a@example.c",
            ".a@example.com",
            "a..b@example.com",
            "a@b..com",
            "a@localhost",
        ] {
            var form = validForm()
            form.contactEmail = invalidEmail
            XCTAssertEqual(
                PartnerApplications.validate(form),
                .contactEmail,
                "Expected backend-invalid email to be rejected: \(invalidEmail)"
            )
        }

        var form = validForm()
        form.contactEmail = "ada+garage@example.co"
        XCTAssertNil(PartnerApplications.validate(form))

        form.websiteURL = "https://"
        XCTAssertEqual(PartnerApplications.validate(form), .websiteURL)
        form.websiteURL = "https://exa mple.com"
        XCTAssertEqual(PartnerApplications.validate(form), .websiteURL)
    }

    func testValidationUsesBackendUTF16BoundariesAfterWebsiteNormalization() {
        var form = validForm()

        form.companyName = String(repeating: "😀", count: 75)
        XCTAssertNil(PartnerApplications.validate(form))
        form.companyName += "a"
        XCTAssertEqual(PartnerApplications.validate(form), .fieldTooLong)

        form = validForm()
        let websiteAtLimit = "example.com/" + String(repeating: "😀", count: 240)
        XCTAssertEqual(
            PartnerApplications.normalizedWebsiteURL(websiteAtLimit)?.utf16.count,
            PartnerApplications.websiteLimit
        )
        form.websiteURL = websiteAtLimit
        XCTAssertNil(PartnerApplications.validate(form))
        form.websiteURL += "a"
        XCTAssertEqual(PartnerApplications.validate(form), .fieldTooLong)
    }

    func testPresentationPreparationIsConsumedOnlyOnce() {
        var state = PartnerApplicationPresentationState()
        XCTAssertTrue(state.consumePreparation())
        XCTAssertFalse(state.consumePreparation())
        XCTAssertTrue(state.hasPrepared)
    }

    @MainActor
    func testCoordinatorCompletesAndMapsContractFailures() async throws {
        let repository = Repository()
        let coordinator = PartnerApplicationCoordinator(repository: repository)
        let input = try XCTUnwrap(PartnerApplications.input(from: validForm()))

        await coordinator.submit(input)
        XCTAssertEqual(coordinator.submission, .done)
        XCTAssertEqual(repository.inputs, [input])

        coordinator.prepareForPresentation()
        repository.error = KccFunctionsError(code: .alreadyExists)
        await coordinator.submit(input)
        XCTAssertEqual(coordinator.submission, .failed(.duplicate))

        coordinator.prepareForPresentation()
        repository.error = KccFunctionsError(code: .invalidArgument)
        await coordinator.submit(input)
        XCTAssertEqual(coordinator.submission, .failed(.invalidInput))
    }
}
