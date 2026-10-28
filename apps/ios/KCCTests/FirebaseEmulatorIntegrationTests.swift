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
        // gRPC uses an explicit plaintext loopback transport. Auth/Functions
        // use localhost for ATS; Firestore's native gRPC channel uses IPv4.
        let firestoreSettings = firestore.settings
        firestoreSettings.host = "127.0.0.1:18088"
        firestoreSettings.isSSLEnabled = false
        firestoreSettings.cacheSettings = MemoryCacheSettings()
        firestore.settings = firestoreSettings
        XCTAssertEqual(firestore.settings.host, "127.0.0.1:18088")
        XCTAssertFalse(firestore.settings.isSSLEnabled)
        let key = UUID().uuidString
        let warningResult = try await adminFunctions.httpsCallable("admin-warnUser").call([
            "targetUid": "native-member", "reason": "Native acceptance warning", "internalNote": "PRIVATE-NOTE", "durationDays": 7, "idempotencyKey": key
        ])
        let warningData = try XCTUnwrap(warningResult.data as? [String: Any])
        let warningID = try XCTUnwrap(warningData["actionId"] as? String)
        let started = ContinuousClock().now
        let ownResult = try await ownFunctions.httpsCallable("account-listWarnings").call([:])
        let own = try XCTUnwrap(ownResult.data as? [String: Any])
        XCTAssertEqual(own["userId"] as? String, "native-member")
        let rows = try XCTUnwrap(own["warnings"] as? [[String: Any]])
        XCTAssertEqual(rows.count, 1)
        let row = try XCTUnwrap(rows.first)
        XCTAssertNil(row["internalNote"])
        let warning = try XCTUnwrap(AccountWarning.decode(row, requestStarted: started))
        XCTAssertEqual(warning.reason, "Native acceptance warning")
        XCTAssertTrue(warning.active(at: ContinuousClock().now))
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
        let accessRepository = FirebaseAccountAccessRepository(firestore: firestore, auth: memberAuth,
            functions: KccFunctionsClient(functions: ownFunctions))
        _ = try await waitForAccess(accessRepository, restricted: false)
        for action in ["suspend", "restore"] {
            let reviewedResult = try await adminFunctions.httpsCallable("admin-getAccessTarget").call(["targetUid": "native-member"])
            let reviewed = try XCTUnwrap(reviewedResult.data as? [String: Any])
            let decisionPayload: [String: Any] = ["targetUid": "native-member", "action": action,
                "expectedActorUid": "native-admin", "expectedVersion": try XCTUnwrap(reviewed["version"]),
                "expectedBlacklistIds": try XCTUnwrap(reviewed["blacklistIds"]),
                "reason": "Native product-only access", "idempotencyKey": UUID().uuidString]
            _ = try await adminFunctions.httpsCallable("admin-updateAccess").call(decisionPayload)
            _ = try await adminFunctions.httpsCallable("admin-updateAccess").call(decisionPayload)
            let access = try await waitForAccess(accessRepository, restricted: action == "suspend")
            XCTAssertFalse(access.suspended)
            XCTAssertFalse(access.deleted)
            XCTAssertFalse(access.blacklisted)
            XCTAssertEqual(access.carCommunitySuspended, action == "suspend")
            _ = try await memberAuth.currentUser?.getIDToken(forcingRefresh: true)
            let canonical = try await firestore.collection("users").document("native-member").getDocument(source: .server)
            XCTAssertEqual(canonical.get("suspended") as? Bool, false)
            XCTAssertEqual(canonical.get("deleted") as? Bool, false)
        }
        let eventUpdates = documentUpdates(firestore.document("events/native-moderation/messages/native-event-message"))
        let publicUpdates = documentUpdates(firestore.document("communityChat/global/messages/native-public-message"))
        let quoteUpdates = documentUpdates(firestore.document("communityChat/global/messages/native-public-reply"))
        for source in ["event", "community"] {
            let target: [String: Any] = source == "event" ? ["source": source, "eventId": "native-moderation", "messageId": "native-event-message"] : ["source": source, "messageId": "native-public-message"]
            let evidenceResult = try await adminFunctions.httpsCallable("admin-getPublicModerationCase").call(target)
            let evidence = try XCTUnwrap(evidenceResult.data as? [String: Any])
            let removal: [String: Any] = ["target": target, "expectedActorUid": "native-admin", "expectedVersion": try XCTUnwrap(evidence["version"]), "action": "remove", "reason": "Native public moderation", "idempotencyKey": UUID().uuidString]
            _ = try await adminFunctions.httpsCallable("admin-decidePublicModeration").call(removal)
            _ = try await adminFunctions.httpsCallable("admin-decidePublicModeration").call(removal)
        }
        try await waitForDocument(eventUpdates) { $0.get("moderationState") as? String == "removed" && $0.get("message") as? String == "" }
        try await waitForDocument(publicUpdates) { !$0.exists }
        try await waitForDocument(quoteUpdates) { $0.exists && $0.get("replyTo") == nil }
        let removedEvent = try await firestore.document("events/native-moderation/messages/native-event-message").getDocument(source: .server)
        XCTAssertEqual(removedEvent.get("moderationState") as? String, "removed")
        XCTAssertEqual(removedEvent.get("message") as? String, "")
        let removedPublic = try await firestore.document("communityChat/global/messages/native-public-message").getDocument(source: .server)
        XCTAssertFalse(removedPublic.exists)
        let preservedReply = try await firestore.document("communityChat/global/messages/native-public-reply").getDocument(source: .server)
        XCTAssertEqual(preservedReply.get("text") as? String, "Reply preserved")
        XCTAssertNil(preservedReply.get("replyTo"))
        let crownMap = FirebaseCrownHuntMapRepository(firestore: firestore, functions: KccFunctionsClient(functions: ownFunctions))
        let crownKeys = CrownSpawnQuery.cellKeys(latitude: 57.4874, longitude: 12.0761, visibleRadiusMeters: 100)
        let initialHandplaced = try await crownMap.activePoints()
        let initialAutomatic = try await crownMap.nearbySpawns(cellKeys: crownKeys, now: Date())
        XCTAssertTrue(initialHandplaced.contains { $0.id == "native-handplaced" })
        XCTAssertTrue(initialAutomatic.contains { $0.id == "native-automatic" })
        _ = try await adminFunctions.httpsCallable("crownHunt-pausePoint").call(["pointId": "native-handplaced"])
        _ = try await adminFunctions.httpsCallable("crownHunt-updateSpawnArea").call(["areaId": "native-area", "active": false])
        let afterHandplaced = try await crownMap.activePoints()
        let afterAutomatic = try await crownMap.nearbySpawns(cellKeys: crownKeys, now: Date())
        XCTAssertFalse(afterHandplaced.contains { $0.id == "native-handplaced" })
        XCTAssertFalse(afterAutomatic.contains { $0.id == "native-automatic" })
        let unchangedWallet = try await firestore.collection("pointsLedger").document("native-member").getDocument(source: .server)
        XCTAssertEqual((unchangedWallet.get("balance") as? NSNumber)?.intValue, 117)
        try actorAuth.signOut()
    }

    private func documentUpdates(_ reference: DocumentReference) -> AsyncThrowingStream<DocumentSnapshot, Error> {
        AsyncThrowingStream { continuation in
            let listener = reference.addSnapshotListener { snapshot, error in
                if let error { continuation.finish(throwing: error) }
                else if let snapshot { continuation.yield(snapshot) }
            }
            let registration = EmulatorListenerRegistrationBox(registration: listener)
            continuation.onTermination = { _ in registration.remove() }
        }
    }

    private func waitForDocument(_ updates: AsyncThrowingStream<DocumentSnapshot, Error>, predicate: @escaping @Sendable (DocumentSnapshot) -> Bool) async throws {
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                for try await snapshot in updates {
                    if !snapshot.metadata.isFromCache && predicate(snapshot) { return }
                }
                throw CancellationError()
            }
            group.addTask {
                try await Task.sleep(for: .seconds(30))
                throw KccFunctionsError(code: .unavailable)
            }
            defer { group.cancelAll() }
            _ = try await group.next()
        }
    }

    @MainActor
    private func waitForAccess(_ repository: FirebaseAccountAccessRepository, restricted: Bool) async throws -> AccountAccess {
        try await withThrowingTaskGroup(of: AccountAccess.self) { group in
            group.addTask {
                for await snapshot in repository.updates(uid: "native-member") {
                    if case .loaded(let access) = snapshot, let access, access.isRestricted == restricted { return access }
                }
                throw CancellationError()
            }
            group.addTask {
                try await Task.sleep(for: .seconds(30))
                throw KccFunctionsError(code: .unavailable)
            }
            defer { group.cancelAll() }
            return try await group.next()!
        }
    }
}

/// Immutable SDK cancellation handle; remove() is the sole cross-task operation.
/// Matches the registration-box pattern used by the production chat repository.
private struct EmulatorListenerRegistrationBox: @unchecked Sendable {
    let registration: any ListenerRegistration
    func remove() { registration.remove() }
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
