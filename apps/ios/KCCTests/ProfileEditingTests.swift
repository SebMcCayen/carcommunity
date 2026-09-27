import XCTest
@testable import KCC

final class ProfileEditingTests: XCTestCase {
    func testValidationTrimsFieldsAndCanonicalizesSocialHandles() throws {
        let result = ProfileValidation.validate(ProfileDraft(
            displayName: " Ada ", bio: " Driver ", facebook: "https://www.facebook.com/Ada.Driver",
            instagram: "@Ada_Driver", youtube: "https://youtube.com/@Ada-Driver"
        ))
        let profile = try result.get()
        XCTAssertEqual(profile.displayName, "Ada")
        XCTAssertEqual(profile.bio, "Driver")
        XCTAssertEqual(profile.facebook, "ada.driver")
        XCTAssertEqual(profile.instagram, "ada_driver")
        XCTAssertEqual(profile.youtube, "Ada-Driver")
    }

    func testValidationRejectsBlankNameForeignHostsAndTooLongBio() {
        assertFailure(ProfileValidation.validate(ProfileDraft(
            displayName: " ", bio: "", facebook: "", instagram: "", youtube: ""
        )), equals: .nameRequired)
        assertFailure(ProfileValidation.validate(ProfileDraft(
            displayName: "Ada", bio: "", facebook: "https://evil.test/ada", instagram: "", youtube: ""
        )), equals: .invalidSocial)
        assertFailure(ProfileValidation.validate(ProfileDraft(
            displayName: "Ada", bio: String(repeating: "x", count: 501), facebook: "", instagram: "", youtube: ""
        )), equals: .tooLong)
    }

    private func assertFailure(
        _ result: Result<ValidatedProfile, ProfileValidationError>,
        equals expected: ProfileValidationError
    ) {
        guard case .failure(let error) = result else { return XCTFail("Expected failure") }
        XCTAssertEqual(error, expected)
    }
}

private extension ProfileDraft {
    init(displayName: String, bio: String, facebook: String, instagram: String, youtube: String) {
        self.init(profile: nil)
        self.displayName = displayName; self.bio = bio; self.facebook = facebook
        self.instagram = instagram; self.youtube = youtube
    }
}
