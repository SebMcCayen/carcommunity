import XCTest
import AuthenticationServices
@testable import KCC

final class AppleSignInProviderTests: XCTestCase {
    func testAuthorizationDoesNotRequestNameOrEmailAndStillHashesNonce() {
        let request = AppleSignInProvider.makeAuthorizationRequest(nonce: "abc")

        XCTAssertEqual(request.requestedScopes, [])
        // SHA-256's known "abc" vector, independent of the implementation.
        XCTAssertEqual(request.nonce, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    func testAppleNSErrorCodesHaveStableDiagnosticNames() throws {
        let cases: [(ASAuthorizationError.Code, String)] = [
            (.unknown, "appleUnknown"),
            (.invalidResponse, "appleInvalidResponse"),
            (.notHandled, "appleNotHandled"),
            (.failed, "appleFailed"),
            (.notInteractive, "appleNotInteractive")
        ]
        for (code, expected) in cases {
            let error = NSError(domain: ASAuthorizationErrorDomain, code: code.rawValue)
            let mapped = try XCTUnwrap(AppleSignInProvider.signInError(from: error) as? SignInFailedError)
            let details = SignInCoordinator.describeFailure(mapped, step: .credentialFetch)
            XCTAssertEqual(details.errorType, "SignInFailedError", "Uses an existing backend allowlist entry")
            XCTAssertEqual(details.statusCode, expected)
            XCTAssertEqual((mapped.underlying as NSError).code, code.rawValue)
        }
    }

    func testAppleUserCancellationIsDroppedForNSErrorAndTypedError() {
        let source = NSError(domain: ASAuthorizationErrorDomain, code: ASAuthorizationError.Code.canceled.rawValue)
        XCTAssertTrue(AppleSignInProvider.signInError(from: source) is SignInCancelledError)
        // Exercise the typed bridge as well as the delegate's NSError form.
        let typed = source as Error as? ASAuthorizationError
        XCTAssertNotNil(typed)
        if let typed { XCTAssertTrue(AppleSignInProvider.signInError(from: typed) is SignInCancelledError) }
    }

    func testOtherDomainsCannotMasqueradeAsAppleCancellation() throws {
        let source = NSError(domain: "synthetic.private-domain", code: ASAuthorizationError.Code.canceled.rawValue)
        let mapped = try XCTUnwrap(AppleSignInProvider.signInError(from: source) as? SignInFailedError)
        XCTAssertEqual(mapped.diagnosticCode, "appleProviderFailure")
    }

    func testFutureAppleCodesUseOneBoundedFallback() throws {
        for code in [Int.min, -1, 123456, Int.max] {
            let source = NSError(domain: ASAuthorizationErrorDomain, code: code)
            let mapped = try XCTUnwrap(AppleSignInProvider.signInError(from: source) as? SignInFailedError)
            XCTAssertEqual(mapped.diagnosticCode, "appleOther")
        }
    }
}
