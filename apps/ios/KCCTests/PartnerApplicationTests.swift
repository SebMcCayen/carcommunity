import XCTest
import CoreImage

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

    func testExplicitSchemesArePreservedForValidationAndOnlyHTTPSIsAccepted() {
        XCTAssertEqual(
            PartnerApplications.normalizedWebsiteURL("HTTPS://example.com"),
            "HTTPS://example.com"
        )
        var form = validForm()
        form.websiteURL = "HTTP://example.com"
        XCTAssertEqual(PartnerApplications.validate(form), .websiteURL)
        form.websiteURL = "ftp://example.com"
        XCTAssertEqual(PartnerApplications.validate(form), .websiteURL)
        form.websiteURL = "https://user:password@example.com"
        XCTAssertEqual(PartnerApplications.validate(form), .websiteURL)
        form.websiteURL = "https://user@example.com"
        XCTAssertEqual(PartnerApplications.validate(form), .websiteURL)
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

    func testPortalApplicationPayloadSeparatesPublicProfileAndPrivateContact() throws {
        var form = validForm()
        form.proposedDescription = "Bilvård med personlig service"
        form.proposedAddress = "Industrivägen 1"
        form.publicPhone = "030012345"
        form.selfServiceRequested = true
        form.billboardRequested = true
        form.billboardPlacementRequest = "Vid infarten"
        let payload = try XCTUnwrap(PartnerApplications.input(from: form)).portalPayload
        XCTAssertEqual(payload["action"] as? String, "submitApplication")
        XCTAssertEqual(payload["website"] as? String, "https://example.com")
        XCTAssertEqual(payload["description"] as? String, form.proposedDescription)
        XCTAssertEqual(payload["address"] as? String, form.proposedAddress)
        XCTAssertEqual(payload["publicPhone"] as? String, "030012345")
        XCTAssertEqual(payload["contactPhone"] as? String, "0701234567")
        XCTAssertEqual(payload["selfServiceRequested"] as? Bool, true)
        XCTAssertEqual(payload["billboardRequested"] as? Bool, true)
        XCTAssertNil(payload["websiteUrl"])
        XCTAssertNil(payload["proposedDescription"])
        XCTAssertNil(payload["submittedByUserId"])
    }

    func testCustomerOfferProjectionDecodesWithoutPrivateCounters() throws {
        let json = #"{"offer":{"offerId":"offer-test","companyId":"company-test","title":"Test offer","teaserText":"Short text","description":"Description","terms":"Terms","percentageDiscount":null,"availableFrom":null,"availableUntil":null,"capacity":3,"remainingCount":2,"reservationDays":7,"status":"active","version":4},"reservation":null}"#
        let response = try JSONDecoder().decode(PortalOfferResponse.self, from: Data(json.utf8))
        XCTAssertEqual(response.offer.remainingCount, 2)
        XCTAssertNil(response.offer.reservedCount)
        XCTAssertNil(response.offer.redeemedCount)
        XCTAssertEqual(response.offer.version, 4)
    }

    func testStatisticsPeriodsExcludeTheOpenUTCMonthAcrossYearBoundary() throws {
        let now = try XCTUnwrap(PortalDate.parse("2026-01-01T00:00:00.000Z"))
        let months = PortalDate.closedMonths(now: now)
        XCTAssertEqual(months.count, 12)
        XCTAssertEqual(months.first, "2025-12")
        XCTAssertEqual(months.last, "2025-01")
        XCTAssertFalse(months.contains("2026-01"))
    }

    func testManagedOfferPreservesUnlimitedCapacityAndRejectsFractionalPercentage() {
        var form = PortalOfferForm()
        form.title = "Testförmån"; form.teaserText = "En kort sammanfattning"
        form.description = "En beskrivning"; form.terms = "En gång per konto"
        XCTAssertTrue(form.isValid)
        XCTAssertTrue(form.payload(companyId: "company-test", offerId: nil)["capacity"] is NSNull)
        form.percentage = "12,5"
        XCTAssertFalse(form.isValid)
        form.percentage = "20"
        XCTAssertTrue(form.isValid)
    }

    @MainActor
    func testPersonalQRContainsOnlyTheOpaqueServerToken() throws {
        let token = "kcc1.synthetic-test-token-with-no-customer-details"
        let image = try XCTUnwrap(PortalQR.image(token)?.cgImage)
        let detector = try XCTUnwrap(CIDetector(ofType: CIDetectorTypeQRCode, context: CIContext(),
            options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]))
        let input = CIImage(cgImage: image).transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        let features = detector.features(in: input).compactMap { $0 as? CIQRCodeFeature }
        XCTAssertEqual(features.first?.messageString, token)
        XCTAssertNil(PortalQR.image(""))
    }


    func testApplicationDeviceDraftOmitsLogoAndKeepsPublicPrivateFieldsSeparate() throws {
        var form = validForm()
        form.publicPhone = "03009999"
        form.logoBase64 = "synthetic-image-data-that-must-not-be-persisted"
        form.logoRightsConfirmed = true
        XCTAssertEqual(PartnerApplications.input(from: form)?.portalPayload["logoRightsConfirmed"] as? Bool, true)
        let draft = PortalApplicationDraft(form: form)
        let data = try JSONEncoder().encode(draft)
        let encoded = try XCTUnwrap(String(data: data, encoding: .utf8))
        XCTAssertFalse(encoded.contains("synthetic-image-data"))
        let restored = try JSONDecoder().decode(PortalApplicationDraft.self, from: data).form
        XCTAssertNil(restored.logoBase64)
        XCTAssertFalse(restored.logoRightsConfirmed)
        XCTAssertEqual(restored.contactPhone, form.contactPhone)
        XCTAssertEqual(restored.publicPhone, "03009999")
        XCTAssertEqual(restored.companyName, form.companyName)
    }

}
