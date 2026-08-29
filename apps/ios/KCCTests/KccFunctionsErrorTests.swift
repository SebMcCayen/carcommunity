import XCTest

@testable import KCC

/// Pins the contract error-code mapping (contracts/errors/errors.json) the
/// callable seam translates SDK failures into. Pure string mapping — no
/// Firebase — mirroring Android's `createFailureFromCode` /
/// `manageFailureFromCode` tests: both the wire spelling and the SDK
/// enum-name spelling must resolve, and anything unrecognized folds to
/// `.unknown` rather than a fabricated contract code.
final class KccFunctionsErrorTests: XCTestCase {

    func testWireSpellingsMapToTheirContractCodes() {
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("unauthenticated"), .unauthenticated)
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("permission-denied"), .permissionDenied)
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("invalid-argument"), .invalidArgument)
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("not-found"), .notFound)
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("resource-exhausted"), .resourceExhausted)
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("failed-precondition"), .failedPrecondition)
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("internal"), .internalError)
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("unavailable"), .unavailable)
    }

    func testSdkEnumNameSpellingsMapToTheSameCodes() {
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("PERMISSION_DENIED"), .permissionDenied)
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("FAILED_PRECONDITION"), .failedPrecondition)
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("RESOURCE_EXHAUSTED"), .resourceExhausted)
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("UNAUTHENTICATED"), .unauthenticated)
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("NOT_FOUND"), .notFound)
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("INTERNAL"), .internalError)
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("UNAVAILABLE"), .unavailable)
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("INVALID_ARGUMENT"), .invalidArgument)
    }

    func testWhitespaceAndCaseAreTolerated() {
        XCTAssertEqual(KccFunctionsErrorCode.fromWire(" Permission-Denied \n"), .permissionDenied)
    }

    func testUnrecognizedAbsentOrNonContractCodesFoldToUnknown() {
        XCTAssertEqual(KccFunctionsErrorCode.fromWire(nil), .unknown)
        XCTAssertEqual(KccFunctionsErrorCode.fromWire(""), .unknown)
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("something-else"), .unknown)
        // SDK-local codes with no contract counterpart must not masquerade
        // as a contract code.
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("deadline-exceeded"), .unknown)
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("cancelled"), .unknown)
        XCTAssertEqual(KccFunctionsErrorCode.fromWire("unknown"), .unknown)
    }
}
