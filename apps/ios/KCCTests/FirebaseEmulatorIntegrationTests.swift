import FirebaseAppCheck
import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import FirebaseFunctions
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
            if ProcessInfo.processInfo.environment["KCC_RUN_MEMBER_EMULATOR_TESTS"] == "1" {
                try await verifyMemberTools(app: app, options: options)
            }
            try auth.signOut()
            XCTAssertNil(auth.currentUser)
        } catch {
            try? auth.signOut()
            // SDK errors can carry request credentials; report only the numeric code.
            XCTFail("Native local Auth acceptance failed (code \((error as NSError).code))")
        }
    }
    @MainActor private func verifyMemberTools(app: FirebaseApp, options: FirebaseOptions) async throws {
        XCTAssertEqual(ProcessInfo.processInfo.environment["KCC_RUN_MEMBER_EMULATOR_TESTS"], "1")
        // Independent named SDK sessions, all hardwired to local demo endpoints.
        let memberAuth = Auth.auth(app: app)
        try memberAuth.signOut()
        _ = try await memberAuth.signIn(withEmail: "native-member@example.com", password: "Native-local-only-12345")
        let actorName = "KCC-CI-Emulator-Actor-" + UUID().uuidString
        FirebaseApp.configure(name: actorName, options: options)
        let actorApp = try XCTUnwrap(FirebaseApp.app(name: actorName))
        defer { actorApp.delete { _ in } }
        let actorAuth = Auth.auth(app: actorApp)
        actorAuth.useEmulator(withHost: "localhost", port: 19098)
        _ = try await actorAuth.signIn(withEmail: "native-admin@example.com", password: "Native-local-only-12345")
        let adminFunctions = Functions.functions(app: actorApp, region: "europe-west1")
        let ownFunctions = Functions.functions(app: app, region: "europe-west1")
        adminFunctions.useEmulator(withHost: "localhost", port: 15001)
        ownFunctions.useEmulator(withHost: "localhost", port: 15001)
        let firestore = Firestore.firestore(app: app)
        firestore.useEmulator(withHost: "localhost", port: 18088)
        let key = UUID().uuidString
        let warningResult = try await adminFunctions.httpsCallable("admin-warnUser").call([
            "targetUid": "native-member", "reason": "Native acceptance warning", "internalNote": "PRIVATE-NOTE", "durationDays": 7, "idempotencyKey": key
        ])
        let warningData = try XCTUnwrap(warningResult.data as? [String: Any])
        let warningID = try XCTUnwrap(warningData["actionId"] as? String)
        let started = ProcessInfo.processInfo.systemUptime
        let ownResult = try await ownFunctions.httpsCallable("account-listWarnings").call([:])
        let own = try XCTUnwrap(ownResult.data as? [String: Any])
        XCTAssertEqual(own["userId"] as? String, "native-member")
        let rows = try XCTUnwrap(own["warnings"] as? [[String: Any]])
        XCTAssertEqual(rows.count, 1)
        let row = try XCTUnwrap(rows.first)
        XCTAssertNil(row["internalNote"])
        let warning = try XCTUnwrap(AccountWarning.decode(row, requestStarted: started))
        XCTAssertEqual(warning.reason, "Native acceptance warning")
        XCTAssertTrue(warning.active(at: ProcessInfo.processInfo.systemUptime))
        let signal = try await firestore.collection("accountWarningSignals").document("native-member").getDocument(source: .server)
        XCTAssertTrue(signal.exists)
        do {
            _ = try await firestore.collection("userWarnings").document("native-member").collection("warnings").document(warningID).getDocument(source: .server)
            XCTFail("Private warning lifecycle must be denied to the member")
        } catch { XCTAssertEqual((error as NSError).code, FirestoreErrorCode.Code.permissionDenied.rawValue) }
        let adjustment: [String: Any] = ["targetUid": "native-member", "amount": 17, "type": "adjustment_credit", "reason": "Native acceptance", "idempotencyKey": UUID().uuidString]
        _ = try await adminFunctions.httpsCallable("points-adminAdjust").call(adjustment)
        _ = try await adminFunctions.httpsCallable("points-adminAdjust").call(adjustment)
        let wallet = try await firestore.collection("pointsLedger").document("native-member").getDocument(source: .server)
        XCTAssertEqual((wallet.get("balance") as? NSNumber)?.intValue, 117)
        _ = try await adminFunctions.httpsCallable("admin-updateDisplayName").call([
            "targetUid": "native-member", "displayName": "Native renamed", "previousDisplayName": "native-member", "expectedActorUid": "native-admin", "reason": "Native acceptance", "idempotencyKey": UUID().uuidString
        ])
        let profile = try await firestore.collection("users").document("native-member").getDocument(source: .server)
        XCTAssertEqual(profile.get("displayName") as? String, "Native renamed")
        _ = try await adminFunctions.httpsCallable("admin-revokeWarning").call([
            "targetUid": "native-member", "warningId": warningID, "expectedVersion": 1, "reason": "Native acceptance complete", "idempotencyKey": UUID().uuidString
        ])
        let afterResult = try await ownFunctions.httpsCallable("account-listWarnings").call([:])
        let after = try XCTUnwrap(afterResult.data as? [String: Any])
        XCTAssertEqual((after["warnings"] as? [[String: Any]])?.count, 0)
        try actorAuth.signOut()
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
