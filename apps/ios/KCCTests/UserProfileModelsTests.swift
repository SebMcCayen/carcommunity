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
