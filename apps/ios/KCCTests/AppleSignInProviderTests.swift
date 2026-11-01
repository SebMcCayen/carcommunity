import XCTest
@testable import KCC

final class AppleSignInProviderTests: XCTestCase {
    func testAuthorizationDoesNotRequestNameOrEmailAndStillHashesNonce() {
        let request = AppleSignInProvider.makeAuthorizationRequest(nonce: "abc")

        XCTAssertEqual(request.requestedScopes, [])
        // SHA-256's known "abc" vector, independent of the implementation.
        XCTAssertEqual(request.nonce, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
}
