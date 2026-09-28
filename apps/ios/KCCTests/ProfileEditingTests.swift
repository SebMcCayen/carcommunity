import XCTest
@testable import KCC

final class ProfileEditingTests: XCTestCase {
    private final class MutationRepository: UserProfileRepository, @unchecked Sendable {
        private(set) var updatedUids: [String] = []
        private(set) var uploadedUids: [String] = []

        func profileUpdates(uid: String) -> AsyncStream<UserProfileSnapshot> {
            AsyncStream { $0.finish() }
        }

        func avatarDownloadURL(for avatarPath: String) async -> URL? { nil }

        func updateProfile(uid: String, profile: ValidatedProfile) async throws {
            updatedUids.append(uid)
        }

        func uploadAvatar(uid: String, jpegData: Data) async throws {
            uploadedUids.append(uid)
        }
    }

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

    @MainActor
    func testExistentialRepositoryDispatchesProfileAndAvatarMutationsToFake() async {
        let fake = MutationRepository()
        let repository: UserProfileRepository = fake
        let coordinator = ProfileEditCoordinator(repository: repository, uid: "account-a")
        let draft = ProfileDraft(
            displayName: "Ada", bio: "", facebook: "", instagram: "", youtube: ""
        )

        let saveError = await coordinator.save(draft)
        XCTAssertNil(saveError)
        coordinator.reset()
        await coordinator.uploadAvatar(jpegData: Data([0xff, 0xd8]))

        XCTAssertEqual(fake.updatedUids, ["account-a"])
        XCTAssertEqual(fake.uploadedUids, ["account-a"])
    }

    @MainActor
    func testDirectAccountSwitchKeepsProfileMutationsBoundToExplicitUid() async {
        let fake = MutationRepository()
        let repository: UserProfileRepository = fake
        let draft = ProfileDraft(
            displayName: "Ada", bio: "", facebook: "", instagram: "", youtube: ""
        )

        let accountA = ProfileEditCoordinator(repository: repository, uid: "account-a")
        let accountAError = await accountA.save(draft)
        XCTAssertNil(accountAError)
        let accountB = ProfileEditCoordinator(repository: repository, uid: "account-b")
        let accountBError = await accountB.save(draft)
        XCTAssertNil(accountBError)

        XCTAssertEqual(fake.updatedUids, ["account-a", "account-b"])
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
