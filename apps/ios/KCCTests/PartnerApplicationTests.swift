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
