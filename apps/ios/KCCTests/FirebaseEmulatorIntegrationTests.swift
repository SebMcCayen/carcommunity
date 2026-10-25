import FirebaseAppCheck
import FirebaseAuth
import FirebaseCore
import XCTest

@testable import KCC

#if DEBUG
/// Opt-in SDK integration test. No production app configuration or credentials.
final class FirebaseEmulatorIntegrationTests: XCTestCase {
    @MainActor
    func testAppleExchangeRefreshAndSignOutAgainstLocalAuthEmulator() async throws {
        guard ProcessInfo.processInfo.environment["KCC_RUN_AUTH_EMULATOR_TESTS"] == "1" else {
            throw XCTSkip("Run the local Auth-emulator acceptance command to enable this test")
        }
        // The test runner supplies only an opt-in, never a configurable cloud endpoint.
        let options = FirebaseOptions(googleAppID: "1:123456789012:ios:000000000000000000000000", gcmSenderID: "123456789012")
        options.projectID = "demo-kcc-ios-ci"
        options.apiKey = "AIza" + String(repeating: "0", count: 35)
        AppCheck.setAppCheckProviderFactory(EmulatorOnlyAppCheckFactory())
        let name = "KCC-CI-Emulator-" + UUID().uuidString
        FirebaseApp.configure(name: name, options: options)
        let app = try XCTUnwrap(FirebaseApp.app(name: name))
        let auth = Auth.auth(app: app)
        // ATS restricts literal IP HTTP on iOS 17+. The unqualified loopback
        // hostname avoids adding network exceptions to the shipping Info.plist.
        auth.useEmulator(withHost: "localhost", port: 19098)
        defer { app.delete { _ in } }
        do {
            let payload = try await EmulatorAppleIDTokenProvider(subject: "native-test-" + UUID().uuidString).fetchAppleIDToken()
            let result = try await auth.signIn(with: OAuthProvider.appleCredential(
                withIDToken: payload.identityToken, rawNonce: payload.rawNonce, fullName: nil
            ))
            XCTAssertFalse(result.user.uid.isEmpty)
            XCTAssertTrue(auth.currentUser?.uid == result.user.uid)
            let token = try await result.user.getIDToken(forcingRefresh: true)
            XCTAssertFalse(token.isEmpty) // Never include credentials in assertion messages.
            try await result.user.reload()
            try auth.signOut()
            XCTAssertNil(auth.currentUser)
        } catch {
            try? auth.signOut()
            // SDK errors can carry request credentials; report only the numeric code.
            XCTFail("Native local Auth acceptance failed (code \((error as NSError).code))")
        }
    }
}

/// Avoids external App Check/Installations requests. Applies only to named test apps.
private final class EmulatorOnlyAppCheckFactory: NSObject, AppCheckProviderFactory {
    func createProvider(with app: FirebaseApp) -> (any AppCheckProvider)? {
        guard app.name.hasPrefix("KCC-CI-Emulator-") else { return nil }
        return EmulatorOnlyAppCheckProvider()
    }
}

private final class EmulatorOnlyAppCheckProvider: NSObject, AppCheckProvider {
    func getToken(completion: @escaping (AppCheckToken?, (any Error)?) -> Void) {
        completion(AppCheckToken(token: "local-emulator-test-only", expirationDate: Date().addingTimeInterval(3600)), nil)
    }
}
#endif
