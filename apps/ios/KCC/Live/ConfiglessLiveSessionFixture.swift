#if DEBUG
import FirebaseCore
import SwiftUI

/// Credential-free UI acceptance only. Nothing here exists in Release or can
/// replace a configured Firebase app, access real GPS, or persist a route.
enum ConfiglessLiveSessionFixture {
    static var isEnabled: Bool {
        FirebaseApp.app() == nil && ProcessInfo.processInfo.arguments.contains("-KCCUITestLiveSession")
    }
    static let selfUid = "fixture-live-self"
    static let otherUid = "fixture-live-other"
    static let repository = ConfiglessLiveRepository()

    static func marker(at now: Date) -> LiveMarker {
        LiveMarker(uid: otherUid, latitude: 57.48, longitude: 12.07,
            displayName: "Testförare", imagePath: nil, recordedAt: now, accuracyMeters: 5)
    }
}

actor ConfiglessLiveRepository: LiveLocationRepository {
    private var session: LiveSessionInfo? = LiveSessionInfo(sessionId: "fixture-live-session", status: .active,
        duration: .sixHours, expiresAt: Date().addingTimeInterval(6 * 3_600 - 120))
    private var continuations: [UUID: AsyncStream<LiveSessionInfo?>.Continuation] = [:]
    nonisolated func currentUserId() -> String? { ConfiglessLiveSessionFixture.selfUid }
    nonisolated func ownSessionUpdates(uid: String) -> AsyncStream<LiveSessionInfo?> {
        AsyncStream { continuation in
            let token = UUID()
            Task { await self.attach(continuation, token: token) }
            continuation.onTermination = { _ in Task { await self.detach(token) } }
        }
    }
    private func attach(_ continuation: AsyncStream<LiveSessionInfo?>.Continuation, token: UUID) {
        continuations[token] = continuation; continuation.yield(session)
    }
    private func detach(_ token: UUID) { continuations[token] = nil }
    func startSession(duration: LiveSessionDuration, vehicleId: String?) async throws {
        // Make the pending metrics observable before RTDB confirms the start.
        try await Task.sleep(for: .seconds(8))
        session = LiveSessionInfo(sessionId: UUID().uuidString, status: .active, duration: duration,
            expiresAt: Date().addingTimeInterval(Double(duration.hours) * 3_600))
        for continuation in continuations.values { continuation.yield(session) }
    }
    func stopSession() async throws {
        session = nil
        for continuation in continuations.values { continuation.yield(nil) }
    }
    func hideMeNow() async throws { try await stopSession() }
    func updatePosition(_ coordinate: LiveCoordinate) async throws {}
    nonisolated func latestUpdates(uid: String) -> AsyncStream<LiveMarker?> {
        AsyncStream { continuation in
            let task = Task {
                while !Task.isCancelled {
                    continuation.yield(ConfiglessLiveSessionFixture.marker(at: Date()))
                    do { try await Task.sleep(for: .seconds(1)) } catch { break }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}

@MainActor
final class ConfiglessLiveLocationProvider: LocationProvider {
    let authorization: LocationAuthorization = .whileInUse
    func requestWhenInUseAuthorization() {}
    func authorizationUpdates() -> AsyncStream<LocationAuthorization> {
        AsyncStream { $0.yield(authorization) }
    }
    func fixes() -> AsyncStream<LocationFix> {
        AsyncStream { continuation in
            let task = Task {
                var index = 0
                while !Task.isCancelled {
                    if let fix = LocationFix.of(latitude: 57.48 + Double(index) * 0.00002,
                        longitude: 12.07, timestamp: Date(), accuracyMeters: 5, speedMetersPerSecond: 10) {
                        continuation.yield(fix)
                    }
                    index += 1
                    do { try await Task.sleep(for: .seconds(1)) } catch { break }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}

actor ConfiglessDriveRecordingRepository: DriveRecordingRepository {
    func save(_ request: DriveSaveRequest) async throws -> DriveSaveResult {
        DriveSaveResult(rideId: "fixture-saved-drive", routePath: nil, alreadySaved: false)
    }
    func uploadRoute(_ points: [RecordedDrivePoint], to path: String) async throws {}
}

/// Lets shell acceptance wait for the real recording coordinator's durable-save
/// boundary, instead of treating the disappearance of live metrics as proof.
struct ConfiglessDriveSaveProbe: View {
    let state: DriveRecordingState?

    var body: some View {
        if case .kept(let rideId)? = state {
            Color.clear.frame(width: 1, height: 1)
                .accessibilityElement()
                .accessibilityLabel("Saved drive")
                .accessibilityValue(rideId)
                .accessibilityIdentifier("map.fixture.savedDrive")
        }
    }
}

struct ConfiglessNearbyLiveMarker: View {
    let coordinator: NearbyLiveCoordinator
    let onSelect: () -> Void
    var body: some View {
        if coordinator.visibleMarkers().contains(where: { $0.uid == ConfiglessLiveSessionFixture.otherUid }) {
            GeometryReader { geometry in
                Button(action: onSelect) {
                    Image(systemName: "car.side.fill").font(.system(size: 20))
                        .frame(width: 48, height: 48).background(KccTheme.driver, in: Circle())
                }
                .accessibilityLabel("Testförare")
                .accessibilityIdentifier("map.fixture.liveSharer")
                .position(x: geometry.size.width * 0.45, y: geometry.size.height * 0.35)
            }
        }
    }
}
#endif
