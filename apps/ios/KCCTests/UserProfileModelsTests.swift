import XCTest

@testable import KCC

/// Decoding tests for the own-profile model: `UserProfile.fromMap` must be
/// TOLERANT — a missing, partial, or wrong-typed field degrades to nil
/// (Android's `getString` semantics), never a decode failure, because a
/// partially-provisioned or legacy `users/{uid}` document must still render.
final class UserProfileModelsTests: XCTestCase {

    func testFullDocumentDecodesEveryModelledField() {
        let profile = UserProfile.fromMap([
            "displayName": "Sebbe",
            "bio": "E46:an är aldrig färdig.",
            "avatarPath": "profileImages/uid-1/avatar-1.jpg",
            // Contract fields this slice does not model must be ignored, not
            // rejected.
            "role": "member",
            "activeMember": true,
            "suspended": false,
            "deleted": false,
        ])
        XCTAssertEqual(profile.displayName, "Sebbe")
        XCTAssertEqual(profile.bio, "E46:an är aldrig färdig.")
        XCTAssertEqual(profile.avatarPath, "profileImages/uid-1/avatar-1.jpg")
    }

    private let clubLogo = "https://firebasestorage.googleapis.com/v0/b/example.firebasestorage.app/o/"
        + "clubLogos%2Fclub_12345678%2Fowner_12345678%2Flogo.webp?alt=media"

    private func badgeFields(id: String = "club_12345678", name: String = "Östra Klubben",
                             logo: Any = NSNull()) -> [String: Any] {
        ["clubId": id, "clubName": name, "logoUrl": logo]
    }

    func testPublicClubProjectionDecodesWithoutMembershipLookup() throws {
        let profile = UserProfile.fromMap(["clubBadge": badgeFields(logo: clubLogo)])
        let badge = try XCTUnwrap(profile.clubBadge)
        XCTAssertEqual(badge.clubId, "club_12345678")
        XCTAssertEqual(badge.clubName, "Östra Klubben")
        XCTAssertEqual(badge.initials, "ÖK")
        XCTAssertEqual(badge.logoURL?.absoluteString, clubLogo)
        XCTAssertNil(UserProfile.fromMap([:]).clubBadge)
        XCTAssertNil(UserProfile.fromMap(["clubBadge": NSNull()]).clubBadge)
    }

    func testMalformedClubAffiliationsAreAbsent() {
        let malformed: [Any] = [NSNull(), "club", [:], badgeFields(logo: 1),
                               badgeFields().merging(["role": "owner"]) { _, new in new }]
        for value in malformed {
            XCTAssertNil(ClubBadge.fromField(value))
        }
        for id in ["", String(repeating: "a", count: 129), "club/a123", "club%2Fa1", "club id1"] {
            XCTAssertNil(ClubBadge.fromField(badgeFields(id: id)))
        }
        for name in ["", "ab", " ", " club", "club ", String(repeating: "a", count: 61), "club\nname",
                     String(repeating: "😀", count: 31)] {
            XCTAssertNil(ClubBadge.fromField(badgeFields(name: name)))
        }
        for id in ["a", "club", String(repeating: "a", count: 128)] {
            for name in ["abc", String(repeating: "a", count: 60), String(repeating: "😀", count: 30), "🚗x"] {
                XCTAssertNotNil(ClubBadge.fromField(badgeFields(id: id, name: name)))
            }
        }
        XCTAssertEqual(ClubBadge.fromField(badgeFields(name: "Östra   Klubben"))?.initials, "ÖK")
    }

    func testUnsafeClubImagesKeepInitialsAsOnAndroid() throws {
        let unsafe: [Any] = [NSNull(), "", "http://example.test/logo.webp", "https://example.test/logo.webp",
            "file:///logo.webp", "content://logo", "javascript:alert(1)",
            clubLogo.replacingOccurrences(of: "https://", with: "https://user:pass@"), clubLogo + "#fragment",
            clubLogo.replacingOccurrences(of: ".com/", with: ".com:443/"),
            clubLogo.replacingOccurrences(of: "club_12345678", with: "club_87654321"),
            clubLogo.replacingOccurrences(of: "logo.webp", with: ".."),
            clubLogo.replacingOccurrences(of: "logo.webp", with: "%252F"),
            clubLogo.replacingOccurrences(of: "logo.webp", with: "extra%2Flogo.webp"),
            "https://" + String(repeating: "x", count: 2048)]
        for url in unsafe {
            let badge = try XCTUnwrap(ClubBadge.fromField(badgeFields(logo: url)))
            XCTAssertNil(badge.logoURL)
            XCTAssertEqual(badge.initials, "ÖK")
        }
    }

    func testRealOnboardingCompletionValueMarksProfileComplete() {
        let profile = UserProfile.fromMap(["onboardingCompletedAt": Date()])
        XCTAssertTrue(profile.onboardingComplete)
    }

    func testExplicitNullOnboardingCompletionDoesNotMarkProfileComplete() {
        let profile = UserProfile.fromMap(["onboardingCompletedAt": NSNull()])
        XCTAssertFalse(profile.onboardingComplete)
    }

    func testMissingOnboardingCompletionDoesNotMarkProfileComplete() {
        let profile = UserProfile.fromMap([:])
        XCTAssertFalse(profile.onboardingComplete)
    }

    func testExperienceRouteFailsClosedUntilCompletionWasVerified() {
        XCTAssertEqual(
            AuthenticatedExperienceRoute.resolve(
                profileState: .failed(code: "UNAVAILABLE"),
                hasVerifiedOnboarding: false
            ),
            .recovery(canRetry: true)
        )
        XCTAssertEqual(
            AuthenticatedExperienceRoute.resolve(
                profileState: .unavailable,
                hasVerifiedOnboarding: false
            ),
            .recovery(canRetry: false)
        )
    }

    func testExperienceRoutePreservesShellAfterCompletionWasVerified() {
        XCTAssertEqual(
            AuthenticatedExperienceRoute.resolve(
                profileState: .failed(code: "UNAVAILABLE"),
                hasVerifiedOnboarding: true
            ),
            .shell
        )
    }

    func testEmptyMapDecodesToAllNil() {
        let profile = UserProfile.fromMap([:])
        XCTAssertNil(profile.displayName)
        XCTAssertNil(profile.bio)
        XCTAssertNil(profile.avatarPath)
    }

    func testPartialDocumentKeepsWhatItHas() {
        let profile = UserProfile.fromMap(["displayName": "Sebbe"])
        XCTAssertEqual(profile.displayName, "Sebbe")
        XCTAssertNil(profile.bio)
        XCTAssertNil(profile.avatarPath)
    }

    func testWrongTypedFieldsDegradeToNil() {
        let profile = UserProfile.fromMap([
            "displayName": 42,
            "bio": ["not": "a string"],
            "avatarPath": true,
        ])
        XCTAssertNil(profile.displayName)
        XCTAssertNil(profile.bio)
        XCTAssertNil(profile.avatarPath)
    }
}
