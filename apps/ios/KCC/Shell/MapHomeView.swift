import SwiftUI
import MapboxMaps
import CoreLocation
import UIKit
import Observation

/// Camera padding changes framing, never the native map's size. Apply it at
/// the renderer binding so every center/follow command (including callbacks
/// installed before the dock was measured) sees the current dock clearance.
enum MapHomeReadingViewportPolicy {
    static func applying(clearance: CGFloat, to viewport: Viewport) -> Viewport {
        viewport.padding(.bottom, clearance.isFinite ? max(0, clearance) : 0)
    }
    static func unpadded(_ viewport: Viewport) -> Viewport { viewport.padding(.bottom, 0) }
}

/// A puck is foreground UI demand, never permission to keep GPS alive for
/// a hidden map or a background scene. Explicit drive/live sessions own their
/// separate providers and are unaffected by this gate.
enum MapHomePuckPolicy {
    static func isVisible(authorization: LocationAuthorization, surfaceActive: Bool, sceneIsActive: Bool) -> Bool {
        authorization.isAuthorized && surfaceActive && sceneIsActive
    }
}

/// Mirrors the convoy-camera ownership rules Android already applies in
/// `MapboxMapSurface.setConvoyFit/applyConvoyFit`: turning focus OFF restores
/// normal Me framing, a temporary lack of fittable points while focus stays ON
/// leaves the current camera alone, and the fit itself is computed on a flat
/// camera before re-applying the live pitch.
enum MapHomeConvoyViewportPolicy {
    enum Plan: Equatable {
        case restoreBrowsing
        case keepCurrentViewport
        case fitConvoy
    }

    static let fitComputationPitch: CGFloat = 0
    static let minimumFitZoom: CGFloat = 8
    static let maximumFitZoom: CGFloat = 16

    static func plan(points: [MapPoint]?, focusEnabled: Bool) -> Plan {
        guard focusEnabled else { return .restoreBrowsing }
        guard let points, points.count >= 2 else { return .keepCurrentViewport }
        return .fitConvoy
    }

    static func fitZoom(_ rawZoom: CGFloat?, fallback: CGFloat) -> CGFloat {
        let zoom = rawZoom.map { $0.isFinite ? $0 : fallback } ?? fallback
        return min(max(zoom, minimumFitZoom), maximumFitZoom)
    }
}

/// The complete camera destination currently owned by the renderer.
///
/// Mapbox reports the camera's in-flight value while an animation is running.
/// Starting a second full-camera animation from that value can therefore
/// resurrect an old pitch or zoom. Keeping the latest destination lets every
/// subsequent command merge into the destination that is actually being
/// approached instead of an intermediate frame.
struct MapHomeCameraTarget: Equatable {
    let latitude: Double
    let longitude: Double
    let zoom: Double
    let bearing: Double
    let pitch: Double

    func applyingPreferences(
        is3D: Bool,
        browsingZoom: Double,
        browsingOwnsZoom: Bool
    ) -> MapHomeCameraTarget {
        MapHomeCameraTarget(
            latitude: latitude,
            longitude: longitude,
            zoom: browsingOwnsZoom ? browsingZoom : zoom,
            bearing: bearing,
            pitch: is3D ? 45 : 0
        )
    }
}

/// Retained renderer bookkeeping, deliberately not observable. Camera events
/// arrive at display frequency; publishing their targets or timer handles via
/// SwiftUI State makes the entire native-map body run during every gesture.
/// Pixel projection remains observable on MapSurface for accurate marker motion.
@MainActor
final class MapHomeCameraRuntime {
    private(set) var cameraReturnToken: UUID?
    private var cameraReturnTask: Task<Void, Never>?
    var cameraReturnActive: Bool { cameraReturnToken != nil }

    func beginCameraReturn() -> UUID {
        cancelCameraReturn()
        let token = UUID()
        cameraReturnToken = token
        return token
    }

    func retainCameraReturn(_ task: Task<Void, Never>) { cameraReturnTask = task }

    func finishCameraReturn(_ token: UUID) -> Bool {
        guard cameraReturnToken == token else { return false }
        cameraReturnToken = nil
        cameraReturnTask = nil
        return true
    }

    func cancelCameraReturn() {
        cameraReturnTask?.cancel()
        cameraReturnTask = nil
        cameraReturnToken = nil
    }

    private(set) var canonicalTarget: MapHomeCameraTarget?
    private var programmaticTokens: Set<UUID> = []
    private var programmaticTasks: [UUID: Task<Void, Never>] = [:]
    private(set) var idleReturnTask: Task<Void, Never>?
    private(set) var idleReturnToken: UUID?
    private(set) var idleReturnDeadline: ContinuousClock.Instant?

    var programmaticChangeActive: Bool { !programmaticTokens.isEmpty }

    func recordCameraFrame(_ target: MapHomeCameraTarget) {
        guard !programmaticChangeActive else { return }
        canonicalTarget = target
    }

    func setTarget(_ target: MapHomeCameraTarget?) { canonicalTarget = target }

    func beginProgrammaticChange() -> UUID {
        let token = UUID()
        programmaticTokens.insert(token)
        return token
    }

    func retainProgrammaticTask(_ task: Task<Void, Never>, for token: UUID) {
        programmaticTasks[token] = task
    }

    /// A late completion cannot release a newer animation's destination.
    @discardableResult
    func finishProgrammaticChange(_ token: UUID) -> Bool {
        guard programmaticTokens.remove(token) != nil else { return false }
        programmaticTasks[token] = nil
        guard !programmaticChangeActive else { return false }
        canonicalTarget = nil
        return true
    }

    /// Extend one deadline instead of cancelling/allocating a Task per frame.
    /// The retained closure reads current bindings when it fires, not a camera
    /// snapshot from the gesture that first armed the timer.
    func scheduleIdleReturn(
        after delay: Duration,
        onIdle: @escaping @MainActor () -> Void
    ) {
        idleReturnDeadline = ContinuousClock.now.advanced(by: delay)
        guard idleReturnTask == nil else { return }
        let token = UUID()
        idleReturnToken = token
        idleReturnTask = Task { @MainActor [weak self] in
            guard let self else { return }
            let clock = ContinuousClock()
            while let deadline = idleReturnDeadline {
                do { try await clock.sleep(until: deadline) } catch { return }
                guard !Task.isCancelled, idleReturnToken == token,
                      let latestDeadline = idleReturnDeadline else { return }
                if clock.now < latestDeadline { continue }
                idleReturnTask = nil
                idleReturnToken = nil
                idleReturnDeadline = nil
                onIdle()
                return
            }
        }
    }

    func cancelIdleReturn() {
        idleReturnTask?.cancel()
        idleReturnTask = nil
        idleReturnToken = nil
        idleReturnDeadline = nil
    }

    func cancelAll() {
        cancelCameraReturn()
        cancelIdleReturn()
        for task in programmaticTasks.values { task.cancel() }
        programmaticTasks.removeAll()
        programmaticTokens.removeAll()
        canonicalTarget = nil
    }
}

enum MapHomeMeFollowPolicy {
    static let idleReturnDelay: Duration = .seconds(10)

    struct SubscriptionKey: Equatable {
        let surfaceActive: Bool
        let enabled: Bool
        let authorization: LocationAuthorization
        let providerId: ObjectIdentifier
    }

    struct RestoreState: Equatable {
        let latestOwnPoint: MapPoint?
        let suspended: Bool
    }

    struct Camera: Equatable {
        let center: CLLocationCoordinate2D
        let zoom: CGFloat
        let bearing: CGFloat
        let pitch: CGFloat
    }

    struct FixUpdate: Equatable {
        let latestOwnPoint: MapPoint
        let shouldApplyCamera: Bool
    }

    static func subscriptionKey(
        surfaceActive: Bool,
        enabled: Bool,
        authorization: LocationAuthorization,
        locationProvider: any LocationProvider
    ) -> SubscriptionKey {
        SubscriptionKey(
            surfaceActive: surfaceActive,
            enabled: enabled,
            authorization: authorization,
            providerId: ObjectIdentifier(locationProvider as AnyObject)
        )
    }

    static func restoreState(
        latestOwnPoint: MapPoint?,
        userPoint: MapPoint?,
        resumeSelfFollow: Bool,
        suspended: Bool
    ) -> RestoreState {
        RestoreState(
            // Resuming after convoy focus must not reuse a fix cached before
            // the convoy owned the camera. Prefer the caller's fresh RTDB
            // point; if it is unavailable, keep the browsing camera until
            // the shared provider supplies its next device fix.
            latestOwnPoint: userPoint ?? (resumeSelfFollow ? nil : latestOwnPoint),
            suspended: resumeSelfFollow ? false : suspended
        )
    }

    static func ingestFix(
        _ fix: LocationFix,
        followEnabled: Bool,
        suspended: Bool
    ) -> FixUpdate {
        let point = MapPoint(longitude: fix.longitude, latitude: fix.latitude)
        return FixUpdate(
            latestOwnPoint: point,
            shouldApplyCamera: followEnabled && !suspended
        )
    }

    static func shouldConsumeFixes(
        surfaceActive: Bool,
        followEnabled: Bool,
        authorization: LocationAuthorization
    ) -> Bool {
        surfaceActive && followEnabled && authorization.isAuthorized
    }

    static func shouldRestoreBrowsingOnAuthorizationChange(
        previous: LocationAuthorization,
        current: LocationAuthorization,
        followEnabled: Bool
    ) -> Bool {
        followEnabled && previous.isAuthorized && !current.isAuthorized
    }

    @MainActor
    static func camera(
        point: MapPoint?,
        snapshot: MapCameraSnapshot?,
        fallback: MapCameraSnapshot?,
        restoringBrowsing: Bool,
        browsingZoom: Double = StubMapSurface.defaultBrowsingZoom
    ) -> Camera? {
        let preferred = restoringBrowsing ? (fallback ?? snapshot) : (snapshot ?? fallback)
        let center = point.map {
            CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
        } ?? preferred.map {
            CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
        }
        guard let center else { return nil }
        // A convoy/route camera may have owned zoom while the user changed the
        // resting browsing preference. Once browsing ownership resumes, use
        // that latest preference even if no fresh own-location point exists;
        // the saved pre-owner camera still supplies center, bearing and pitch.
        let zoom = restoringBrowsing
            ? CGFloat(browsingZoom)
            : CGFloat(preferred?.zoom ?? Double(StubMapSurface.defaultBrowsingZoom))
        return Camera(
            center: center,
            zoom: zoom,
            bearing: CGFloat(preferred?.bearing ?? 0),
            pitch: CGFloat(preferred?.pitch ?? 45)
        )
    }

    @MainActor
    static func authorizationStream(
        for locationProvider: any LocationProvider
    ) -> AsyncStream<LocationAuthorization> {
        // LocationProvider guarantees that this stream atomically seeds its
        // current value before delivering changes. Adding a second seed here
        // can race that subscription and replay a stale authorization after a
        // newer value has already been set.
        locationProvider.authorizationUpdates()
    }

}

enum MapHomeProjectionTrustPolicy {
    private static let maximumMercatorLatitude = 85.05112878
    static let roundTripTolerancePixels = 4.0
    static let minimumRoundTripToleranceMeters = 2.0
    static let maximumFlatPitchDegrees = 1.0

    static func hasFiniteScreenPosition(x: Double, y: Double) -> Bool {
        x.isFinite && y.isFinite
    }

    static func requiresRoundTrip(pitch: Double) -> Bool {
        !pitch.isFinite || pitch > maximumFlatPitchDegrees
    }

    static func isTrustworthy(
        latitude: Double,
        longitude: Double,
        unprojectedLatitude: Double,
        unprojectedLongitude: Double,
        zoom: Double
    ) -> Bool {
        guard latitude.isFinite, longitude.isFinite,
              unprojectedLatitude.isFinite, unprojectedLongitude.isFinite else {
            return false
        }
        let tolerance = max(
            metersPerPixel(latitude: latitude, zoom: zoom) * roundTripTolerancePixels,
            minimumRoundTripToleranceMeters
        )
        let mismatch = LiveShareCadence.distanceMeters(
            lat1: latitude, lon1: longitude,
            lat2: unprojectedLatitude, lon2: unprojectedLongitude
        )
        return mismatch <= tolerance
    }

    static func metersPerPixel(latitude: Double, zoom: Double) -> Double {
        guard latitude.isFinite, zoom.isFinite else { return 0 }
        let clampedLatitude = min(max(latitude, -maximumMercatorLatitude), maximumMercatorLatitude)
        let clampedZoom = max(zoom, 0)
        return 156_543.03392 * cos(clampedLatitude * .pi / 180) / pow(2, clampedZoom)
    }
}

private enum MapHomeStyleLayers {
    static let standardImportId = "basemap"
    static let lightPresetConfig = "lightPreset"
    static let show3DObjectsConfig = "show3dObjects"
    static let trafficSourceId = "kcc-traffic-source"
    static let trafficLayerId = "kcc-traffic-layer"

    static func applyMapMode(_ mode: MapMode, to map: MapboxMap) {
        try? map.setStyleImportConfigProperty(
            for: standardImportId,
            config: lightPresetConfig,
            value: mode == .night ? "night" : "day"
        )
    }

    static func apply3D(_ enabled: Bool, to map: MapboxMap) {
        guard map.styleURI == .standard else { return }
        try? map.setStyleImportConfigProperty(
            for: standardImportId,
            config: show3DObjectsConfig,
            value: enabled
        )
    }

    static func applyTraffic(_ visible: Bool, mode: MapMode, to map: MapboxMap) {
        if !map.sourceExists(withId: trafficSourceId) {
            var source = VectorSource(id: trafficSourceId)
            source.url = "mapbox://mapbox.mapbox-traffic-v1"
            try? map.addSource(source)
        }
        if !map.layerExists(withId: trafficLayerId) {
            var layer = LineLayer(id: trafficLayerId, source: trafficSourceId)
            layer.sourceLayer = "traffic"
            layer.slot = "middle"
            layer.lineCap = .constant(.round)
            layer.lineJoin = .constant(.round)
            layer.visibility = .constant(visible ? .visible : .none)
            layer.lineColor = .expression(colorExpression(for: mode))
            layer.lineWidth = .constant(MapTrafficPalette.lineWidth(for: mode))
            try? map.addLayer(layer)
            return
        }
        try? map.updateLayer(withId: trafficLayerId, type: LineLayer.self) { layer in
            layer.visibility = .constant(visible ? .visible : .none)
            layer.lineColor = .expression(colorExpression(for: mode))
            layer.lineWidth = .constant(MapTrafficPalette.lineWidth(for: mode))
        }
    }

    private static func colorExpression(for mode: MapMode) -> Exp {
        let colors = MapTrafficPalette.colors(for: mode)
        return Exp(.match) {
            Exp(.get) { "congestion" }
            "low"
            color(colors.low)
            "moderate"
            color(colors.moderate)
            "heavy"
            color(colors.heavy)
            "severe"
            color(colors.severe)
            color(colors.unknown)
        }
    }

    private static func color(_ argb: UInt32) -> UIColor {
        UIColor(
            red: CGFloat((argb >> 16) & 0xFF) / 255,
            green: CGFloat((argb >> 8) & 0xFF) / 255,
            blue: CGFloat(argb & 0xFF) / 255,
            alpha: CGFloat((argb >> 24) & 0xFF) / 255
        )
    }
}

@MainActor
private final class ConvoyViewportInteractionObserver: @preconcurrency ViewportStatusObserver {
    weak var surface: StubMapSurface?
    var onUserInteraction: @MainActor () -> Void = {}

    init(surface: StubMapSurface) {
        self.surface = surface
    }

    func viewportStatusDidChange(
        from fromStatus: ViewportStatus,
        to toStatus: ViewportStatus,
        reason: ViewportStatusChangeReason
    ) {
        guard reason == .userInteraction else { return }
        surface?.suspendConvoyFitForInteraction()
        onUserInteraction()
    }
}

/// Initial camera ownership lasts until a location-follow viewport is armed
/// or the member deliberately browses/selects another target. Late permission
/// grants must not pull a manually positioned camera back to the member.
struct MapHomeStartupCameraPolicy {
    private(set) var pending = true
    private(set) var following = false

    mutating func cancel() {
        pending = false
        following = false
    }

    mutating func takeFollow(authorized: Bool, active: Bool, rendererReady: Bool) -> Bool {
        guard pending, authorized, active, rendererReady else { return false }
        pending = false
        following = true
        return true
    }
}

/// Hosts the shell's single Mapbox Standard map. Config-less builds keep the
/// deterministic placeholder, while a valid public token swaps in the native
/// renderer without changing the shell-facing ``MapSurface`` seam.
@MainActor
struct MapHomeView: View {
    @Environment(\.colorScheme) private var colorScheme
    /// Config-less accessibility diagnostics let UI tests detect accidental
    /// map-host recreation. No account or location data is included.
    @State private var hostLifetimeID = UUID()
    @Environment(\.scenePhase) private var scenePhase
    /// The shell's one surface instance, owned by ``ShellView`` — composed
    /// once for the whole signed-in shell and never disposed.
    let surface: StubMapSurface
    let locationProvider: any LocationProvider
    private let basemap: MapBasemap
    private let accessToken: String?
    let bottomClearance: CGFloat
    private let featureHealthReporter: FeatureHealthReporter?
    private let markers: AnyView
    @State private var renderWatchdog: MapRenderWatchdog

    init(
        surface: StubMapSurface,
        locationProvider: any LocationProvider,
        basemap: MapBasemap = .standard,
        accessToken: String? = MapboxConfiguration.accessToken(),
        bottomClearance: CGFloat = 0,
        featureHealthReporter: FeatureHealthReporter? = nil,
        markers: AnyView = AnyView(EmptyView())
    ) {
        self.surface = surface
        self.locationProvider = locationProvider
        self.basemap = basemap
        self.accessToken = accessToken
        self.bottomClearance = bottomClearance
        self.featureHealthReporter = featureHealthReporter
        self.markers = markers
        _renderWatchdog = State(initialValue: MapRenderWatchdog())
    }

    var body: some View {
        ZStack {
            if let accessToken {
                MapboxStandardMap(
                    accessToken: accessToken,
                    surface: surface,
                    locationProvider: locationProvider,
                    basemap: basemap,
                    bottomClearance: bottomClearance,
                    markers: markers,
                    onLoadingError: { kind in
                        featureHealthReporter?.report(
                            kind,
                            foreground: scenePhase == .active,
                            surfaceShown: surface.isActive
                        )
                    },
                    onFullFrameRendered: {
                        _ = renderWatchdog.tick(milliseconds: 0, eligible: false, rendered: true)
                    }
                ) {
                    surface.markLoaded()
                }
            } else {
                mapPlaceholder
                    .overlay { markers }
                    .ignoresSafeArea(edges: .top)
            }
        }
        .onAppear(perform: updateFeatureHealthConditions)
        .onChange(of: scenePhase) { _, _ in
            updateFeatureHealthConditions()
        }
        .onChange(of: surface.isActive) { _, _ in
            updateFeatureHealthConditions()
        }
        .onDisappear {
            featureHealthReporter?.updateConditions(foreground: false, surfaceShown: false)
        }
        // Keep load tracking for renderer health without covering the map with status copy.
        .task {
            guard accessToken == nil else { return }
            await surface.simulateInitialLoadIfNeeded()
        }
        // Detect the silent failure class where Mapbox never completes a full
        // frame. Only foreground, uncovered, online time consumes the 12s
        // budget; config-less placeholder builds never start the watchdog.
        .task(id: accessToken != nil) {
            guard accessToken != nil, let featureHealthReporter else { return }
            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: .seconds(1))
                } catch {
                    return
                }
                let surfaceShown = surface.isActive
                let foreground = scenePhase == .active
                let eligible = surfaceShown && foreground && featureHealthReporter.isOnline()
                if renderWatchdog.tick(
                    milliseconds: 1_000,
                    eligible: eligible,
                    rendered: false
                ) {
                    featureHealthReporter.report(
                        .mapRenderTimeout,
                        foreground: foreground,
                        surfaceShown: surfaceShown
                    )
                }
                if renderWatchdog.isDisarmed { return }
            }
        }
    }

    private var mapPlaceholder: some View {
        ZStack {
            KccTheme.subtle
                .ignoresSafeArea()
            Text("shell.mapPlaceholder")
                .accessibilityIdentifier("shell.mapPlaceholder")
                .accessibilityValue(Text(verbatim:
                    "\(hostLifetimeID.uuidString)|\(colorScheme == .dark ? "Dark" : "Light")"))
                .font(.headline)
                .foregroundStyle(.secondary)
        }
    }

    private func updateFeatureHealthConditions() {
        featureHealthReporter?.updateConditions(
            foreground: scenePhase == .active,
            surfaceShown: surface.isActive
        )
    }


}

/// Mapbox's stock iOS puck adds a fixed 1.1-second interpolator. Reuse its
/// exact image assets in native annotations, driven by the same short timeline
/// as other drivers, rather than layering that second animation on top.
@Observable @MainActor
private final class DriverPuckPresentation {
    private(set) var point: DriverMotionPoint?
    private(set) var trail: [MapPoint] = []
    private(set) var accuracyMeters: Double?
    private(set) var sourceFix: LocationFix?
    @ObservationIgnored private var motion = DriverMotionTimeline()
    @ObservationIgnored private var animation: Task<Void, Never>?
    @ObservationIgnored private var accepted: ConvoyMemberPosition?
    @ObservationIgnored private var pending: ConvoyMemberPosition?
    @ObservationIgnored private var history: [MapPoint] = []
    @ObservationIgnored private var stableBearing = 0.0
    @ObservationIgnored private var ingestedAt: TimeInterval = 0

    @discardableResult
    func ingest(_ fix: LocationFix, sharing: Bool, seed: [MapPoint],
                onFrame: @escaping @MainActor (DriverMotionPoint) -> Void) -> Bool {
        let wallNow = Date()
        guard MapCameraResumePolicy.isFresh(fixAt: fix.timestamp, now: wallNow) else { return false }
        let candidate = ConvoyMemberPosition(uid: "self", latitude: fix.latitude, longitude: fix.longitude,
            updatedAt: fix.timestamp, accuracyMeters: fix.accuracyMeters,
            headingDegrees: fix.headingDegrees, speedMetersPerSecond: fix.speedMetersPerSecond)
        switch ConvoyPositionQuality.judge(candidate, previous: accepted, pending: pending) {
        case .reject: pending = nil; return false
        case .hold: pending = candidate; return false
        case .accept: pending = nil
        }
        stableBearing = MapCameraResumePolicy.course(heading: fix.headingDegrees,
            speed: fix.speedMetersPerSecond, speedAccuracy: fix.speedAccuracyMetersPerSecond, holding: stableBearing)
        let now = ProcessInfo.processInfo.systemUptime
        guard motion.update(DriverMotionPoint(latitude: fix.latitude, longitude: fix.longitude, bearing: stableBearing),
            recordedAt: fix.timestamp, sampleAge: wallNow.timeIntervalSince(fix.timestamp), now: now,
            speed: fix.speedMetersPerSecond, course: stableBearing) else { return false }
        accepted = candidate
        sourceFix = fix
        accuracyMeters = fix.accuracyMeters
        ingestedAt = now
        if !sharing { history = [] }
        else {
            if history.isEmpty { history = Array(seed.suffix(1_000)) }
            let next = MapPoint(longitude: fix.longitude, latitude: fix.latitude)
            if let previous = history.last {
                let distance = LiveShareCadence.distanceMeters(lat1: previous.latitude, lon1: previous.longitude,
                    lat2: next.latitude, lon2: next.longitude)
                if distance > 100 { history = [] }
            }
            if history.last != next { history.append(next) }
            var meters = 0.0
            var start = max(0, history.count - 1)
            while start > 0, meters < 500 {
                meters += LiveShareCadence.distanceMeters(lat1: history[start].latitude, lon1: history[start].longitude,
                    lat2: history[start - 1].latitude, lon2: history[start - 1].longitude)
                start -= 1
            }
            history = Array(history[start...].suffix(1_000))
        }
        animation?.cancel()
        publish(at: now, onFrame: onFrame)
        animation = Task { @MainActor [weak self] in
            while let self, self.motion.isAnimating(at: ProcessInfo.processInfo.systemUptime) {
                do { try await Task.sleep(for: .seconds(DriverMotionPolicy.frameInterval)) } catch { return }
                guard !Task.isCancelled else { return }
                self.publish(at: ProcessInfo.processInfo.systemUptime, onFrame: onFrame)
            }
        }
        return true
    }

    func stop() {
        animation?.cancel(); animation = nil
        point = nil; sourceFix = nil; accuracyMeters = nil; trail = []
        motion = DriverMotionTimeline(); accepted = nil; pending = nil
    }

    private func publish(at now: TimeInterval, onFrame: @MainActor (DriverMotionPoint) -> Void) {
        guard let frame = motion.frame(at: now) else { return }
        point = frame
        let head = MapPoint(longitude: frame.longitude, latitude: frame.latitude)
        let prefix = now - ingestedAt < DriverMotionPolicy.correctionDuration ? Array(history.dropLast()) : history
        trail = prefix.isEmpty ? [] : (prefix.last == head ? prefix : prefix + [head])
        onFrame(frame)
    }
}

/// Mapbox-specific rendering stays confined to this file. The initial camera
/// waits for location consent, then follows the member until they browse.
@MainActor
private struct MapboxStandardMap: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let onLoaded: @MainActor () -> Void
    let onFullFrameRendered: @MainActor () -> Void
    let onLoadingError: @MainActor (FeatureHealthKind) -> Void
    let surface: StubMapSurface
    let locationProvider: any LocationProvider
    let basemap: MapBasemap
    let bottomClearance: CGFloat
    let markers: AnyView
    @State private var ownMotion = DriverPuckPresentation()
    private let puckImages = Puck2DConfiguration.makeDefault(showBearing: true)
    @State private var viewport: Viewport
    @State private var cameraBeforeConvoy: MapCameraSnapshot?
    @State private var interactionObserver: ConvoyViewportInteractionObserver
    @State private var latestOwnPoint: MapPoint?
    @State private var startupCamera = MapHomeStartupCameraPolicy()
    @State private var mapReady = false
    @State private var didFinishInitialMapLoad = false
    @State private var compassFollowRequested = false
    @State private var meFollowEnabled = false
    @State private var meFollowSuspended = false
    @State private var locationAuthorization: LocationAuthorization
    @State private var surfaceActive: Bool
    @State private var cameraRuntime = MapHomeCameraRuntime()
    @State private var rendererAttached = false
    @State private var isVisible = false
    @State private var cameraResume = MapCameraResumePolicy()
    @State private var stableCourse: Double?

    init(
        accessToken: String,
        surface: StubMapSurface,
        locationProvider: any LocationProvider,
        basemap: MapBasemap,
        bottomClearance: CGFloat,
        markers: AnyView,
        onLoadingError: @escaping @MainActor (FeatureHealthKind) -> Void,
        onFullFrameRendered: @escaping @MainActor () -> Void,
        onLoaded: @escaping @MainActor () -> Void
    ) {
        MapboxOptions.accessToken = accessToken
        self.surface = surface
        self.locationProvider = locationProvider
        self.basemap = basemap
        self.onLoaded = onLoaded
        self.bottomClearance = bottomClearance
        self.markers = markers
        self.onFullFrameRendered = onFullFrameRendered
        self.onLoadingError = onLoadingError
        _viewport = State(initialValue: .camera(
            // A neutral overview while consent/the first fix is pending,
            // never a city presented as the member's position.
            center: .init(latitude: 0, longitude: 0),
            zoom: 1,
            bearing: 0,
            pitch: 0
        ))
        _interactionObserver = State(initialValue: ConvoyViewportInteractionObserver(surface: surface))
        _locationAuthorization = State(initialValue: locationProvider.authorization)
        _surfaceActive = State(initialValue: surface.isActive)
    }

    var body: some View {
        MapReader { proxy in
            MapboxMaps.Map(viewport: Binding(
                get: { MapHomeReadingViewportPolicy.applying(clearance: bottomClearance, to: viewport) },
                set: { viewport = MapHomeReadingViewportPolicy.unpadded($0) }
            )) {
                if MapHomePuckPolicy.isVisible(
                    authorization: locationAuthorization,
                    surfaceActive: surfaceActive,
                    sceneIsActive: scenePhase == .active
                ), let point = ownMotion.point {
                    let coordinate = CLLocationCoordinate2D(latitude: point.latitude, longitude: point.longitude)
                    CircleAnnotation(centerCoordinate: coordinate)
                        .circleRadius(accuracyRadius(at: point))
                        .circleColor(StyleColor(puckImages.accuracyRingColor))
                        .circleStrokeWidth(1)
                        .circleStrokeColor(StyleColor(puckImages.accuracyRingBorderColor))
                    PointAnnotationGroup {
                        if let image = puckImages.shadowImage {
                            PointAnnotation(id: "own-puck-shadow", coordinate: coordinate)
                                .image(.init(image: image, name: "own-puck-shadow"))
                                .symbolSortKey(0)
                        }
                        if let image = puckImages.bearingImage {
                            PointAnnotation(id: "own-puck-bearing", coordinate: coordinate)
                                .image(.init(image: image, name: "own-puck-bearing"))
                                .iconRotate(point.bearing ?? 0)
                                .symbolSortKey(1)
                        }
                        if let image = puckImages.topImage {
                            PointAnnotation(id: "own-puck-top", coordinate: coordinate)
                                .image(.init(image: image, name: "own-puck-top"))
                                .symbolSortKey(2)
                        }
                    }
                    .iconAllowOverlap(true)
                    .iconIgnorePlacement(true)
                    .iconRotationAlignment(.map)
                    .iconPitchAlignment(.viewport)
                    .slot(.top)
                    if ownMotion.trail.count >= 2 {
                        PolylineAnnotation(id: "own-driver-trail", lineCoordinates: ownMotion.trail.map {
                            CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
                        })
                        .lineColor(UIColor(KccPalette.crownGold))
                        .lineBorderColor(UIColor(KccPalette.inkBlack))
                        .lineBorderWidth(2).lineWidth(6).lineOpacity(0.92).lineJoin(.round)
                    }
                }
                if let trail = surface.followMeTrail, trail.count >= 2 {
                    PolylineAnnotation(
                        id: "convoy-follow-me-trail",
                        lineCoordinates: trail.map {
                            CLLocationCoordinate2D(
                                latitude: $0.latitude,
                                longitude: $0.longitude
                            )
                        }
                    )
                    .lineColor(UIColor.systemYellow)
                    .lineBorderColor(UIColor.black.withAlphaComponent(0.65))
                    .lineBorderWidth(2)
                    .lineWidth(6)
                    .lineOpacity(0.92)
                    .lineJoin(.round)
                }
            }
            .mapStyle(basemap == .satellite ? .standardSatellite : .standard)
            // Match Android's native pitch gesture alongside pan, pinch and rotation.
            .gestureOptions(GestureOptions(pitchEnabled: true))
            // Keep Mapbox attribution/logo; hide only the requested scale
            // and the redundant SDK compass in favour of our accessible rail.
            .ornamentOptions(OrnamentOptions(
                scaleBar: ScaleBarViewOptions(visibility: .hidden),
                compass: CompassViewOptions(visibility: .hidden),
                logo: LogoViewOptions(position: .bottomLeft,
                    margins: CGPoint(x: 8, y: bottomClearance + 8)),
                attributionButton: AttributionButtonOptions(position: .bottomLeft,
                    margins: CGPoint(x: 112, y: bottomClearance + 8))
            ))
            .onStyleLoaded { _ in
                guard let map = proxy.map else { return }
                // The marker overlay uses point(for:), which Mapbox does not
                // support on Standard's globe at low zoom levels.
                try? map.setProjection(StyleProjection(name: .mercator))
                surface.invalidateProjection()
                MapHomeStyleLayers.applyMapMode(surface.mapMode, to: map)
                MapHomeStyleLayers.apply3D(surface.is3D, to: map)
                MapHomeStyleLayers.applyTraffic(surface.trafficEnabled, mode: surface.mapMode, to: map)
            }
            .onMapLoaded { _ in
                onLoaded()
                guard !didFinishInitialMapLoad else { return }
                if !rendererAttached, let viewportController = proxy.viewport {
                    viewportController.addStatusObserver(interactionObserver)
                    rendererAttached = true
                }
                installRenderer(
                    proxy.map,
                    viewport: $viewport,
                    cameraBeforeConvoy: $cameraBeforeConvoy,
                    latestOwnPoint: $latestOwnPoint,
                    meFollowEnabled: $meFollowEnabled,
                    meFollowSuspended: $meFollowSuspended
                )
                mapReady = proxy.map != nil
                didFinishInitialMapLoad = mapReady
                updateOwnLocationForVisibility()
            }
            .onMapLoadingError { event in
                switch event.type {
                case .style: onLoadingError(.mapStyleLoadFailed)
                case .sprite, .glyphs: onLoadingError(.mapResourceLoadError)
                default: break
                }
            }
            .onRenderFrameFinished { event in
                if event.renderMode == .full {
                    onFullFrameRendered()
                }
            }
            .onCameraChanged { context in
                let state = context.cameraState
                cameraRuntime.recordCameraFrame(cameraTarget(from: state))
                surface.updateCameraSnapshot(.of(
                    latitude: state.center.latitude,
                    longitude: state.center.longitude,
                    zoom: state.zoom,
                    bearing: state.bearing,
                    pitch: state.pitch
                ))
                if meFollowEnabled, meFollowSuspended,
                   !cameraRuntime.programmaticChangeActive {
                    armMeFollowIdleReturn(viewport: $viewport)
                }
            }
            .onGeometryChange(for: CGSize.self) { $0.size } action: { _ in
                surface.invalidateProjection()
            }
            .onDisappear {
                isVisible = false
                mapReady = false
                didFinishInitialMapLoad = false
                if compassFollowRequested {
                    performProgrammaticViewportChange { viewport = .idle }
                }
                cameraRuntime.cancelAll()
                if rendererAttached {
                    proxy.viewport?.removeStatusObserver(interactionObserver)
                    rendererAttached = false
                }
                surface.removeRenderer()
            }
            .onAppear {
                isVisible = true
                interactionObserver.onUserInteraction = {
                    // A gesture can interrupt an in-flight programmatic animation.
                    // Always suspend now; idle return waits for its token to clear.
                    startupCamera.cancel()
                    cameraResume.cancel()
                    cameraRuntime.cancelCameraReturn()
                    if !meFollowEnabled { compassFollowRequested = false }
                    cameraRuntime.setTarget(nil)
                    guard meFollowEnabled else { return }
                    meFollowSuspended = true
                    surface.suspendSelfFollowForInteraction()
                    armMeFollowIdleReturn(viewport: $viewport)
                }
                // onMapLoaded handles first readiness. A retained Mapbox view
                // does not necessarily emit it again after disappearing, so
                // reattach immediately when its map is already available.
                if let map = proxy.map {
                    if !rendererAttached, let viewportController = proxy.viewport {
                        viewportController.addStatusObserver(interactionObserver)
                        rendererAttached = true
                    }
                    installRenderer(
                        map,
                        viewport: $viewport,
                        cameraBeforeConvoy: $cameraBeforeConvoy,
                        latestOwnPoint: $latestOwnPoint,
                        meFollowEnabled: $meFollowEnabled,
                        meFollowSuspended: $meFollowSuspended
                    )
                }
                mapReady = proxy.map != nil
                updateOwnLocationForVisibility()
            }
            .onChange(of: surface.isActive, initial: true) { _, active in
                surfaceActive = active
                updateOwnLocationForVisibility()
            }
            .onChange(of: scenePhase) { _, _ in
                updateOwnLocationForVisibility()
            }
            .onChange(of: surface.compassMode) { _, _ in
                guard surfaceActive else { return }
                startupCamera.cancel()
                cameraResume.cancel()
                cameraRuntime.cancelCameraReturn()
                compassFollowRequested = true
                cameraRuntime.setTarget(nil)
                surface.suspendConvoyFitForInteraction()
                meFollowSuspended = false
                cameraRuntime.cancelIdleReturn()
                applyCompassViewport(viewport: $viewport, snapshot: surface.cameraSnapshot)
            }
            // Mapbox projects from the native view's top-left. Expand the
            // map and its marker layer together so their origins stay aligned.
            .overlay { markers }
            .ignoresSafeArea(edges: .top)
        }
        .task(id: MapHomeMeFollowPolicy.subscriptionKey(
            surfaceActive: surfaceActive && scenePhase == .active && isVisible && mapReady,
            enabled: true,
            authorization: locationAuthorization,
            locationProvider: locationProvider
        )) {
            guard surfaceActive, scenePhase == .active, isVisible, mapReady,
                  locationAuthorization.isAuthorized else { ownMotion.stop(); surface.updateRenderedOwnPoint(nil); return }
            defer { ownMotion.stop(); surface.updateRenderedOwnPoint(nil) }
            for await fix in locationProvider.fixes() {
                if Task.isCancelled { return }
                guard MapCameraResumePolicy.isFresh(fixAt: fix.timestamp, now: Date()) else { continue }
                guard ownMotion.ingest(fix, sharing: surface.userMarker?.isLiveSharing == true,
                    seed: surface.seededBreadcrumb, onFrame: { frame in
                        applyPresentedOwnFrame(frame, viewport: $viewport)
                    }) else { continue }
                // Resume is evaluated ONCE from an accepted physical fix,
                // never from a predicted display frame or cached SDK callback.
                _ = consumeCameraReturnFix(fix)
                cameraResume.recordFix(at: fix.timestamp)
            }
        }
        .task(id: ObjectIdentifier(locationProvider as AnyObject)) {
            for await authorization in MapHomeMeFollowPolicy.authorizationStream(
                for: locationProvider
            ) {
                if Task.isCancelled { return }
                locationAuthorization = authorization
                startOwnLocationIfNeeded()
                if !authorization.isAuthorized, compassFollowRequested {
                    startupCamera.cancel()
                    cameraResume.cancel()
                    cameraRuntime.cancelCameraReturn()
                    compassFollowRequested = false
                    // Stop the explicit follow viewport when consent is lost.
                    performProgrammaticViewportChange { viewport = .idle }
                }
            }
        }
        .onChange(of: locationAuthorization) { oldValue, newValue in
            guard MapHomeMeFollowPolicy.shouldRestoreBrowsingOnAuthorizationChange(
                previous: oldValue,
                current: newValue,
                followEnabled: meFollowEnabled
            ) else { return }
            meFollowEnabled = false
            meFollowSuspended = false
            cameraRuntime.cancelIdleReturn()
            latestOwnPoint = nil
            let fallback = cameraBeforeConvoy
            cameraBeforeConvoy = nil
            surface.setConvoyFit(
                points: nil,
                focusEnabled: false,
                userPoint: nil,
                followSelfEnabled: false
            )
            applyMeFollow(
                nil,
                viewport: $viewport,
                fallback: fallback,
                snapshot: surface.cameraSnapshot,
                restoringBrowsing: true
            )
        }
    }

    private func installRenderer(
        _ map: MapboxMap?,
        viewport: Binding<Viewport>,
        cameraBeforeConvoy: Binding<MapCameraSnapshot?>,
        latestOwnPoint: Binding<MapPoint?>,
        meFollowEnabled: Binding<Bool>,
        meFollowSuspended: Binding<Bool>
    ) {
        guard let map else { return }
        surface.installRenderer(
            projection: { latitude, longitude in
                let coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
                let point = map.point(for: coordinate)
                guard MapHomeProjectionTrustPolicy.hasFiniteScreenPosition(
                    x: point.x,
                    y: point.y
                ) else {
                    return nil
                }
                let cameraState = map.cameraState
                let trustworthy: Bool
                if MapHomeProjectionTrustPolicy.requiresRoundTrip(pitch: cameraState.pitch) {
                    let roundTrip = map.coordinate(for: point)
                    trustworthy = MapHomeProjectionTrustPolicy.isTrustworthy(
                        latitude: latitude,
                        longitude: longitude,
                        unprojectedLatitude: roundTrip.latitude,
                        unprojectedLongitude: roundTrip.longitude,
                        zoom: cameraState.zoom
                    )
                } else {
                    // A top-down camera cannot fold a point behind itself. Avoid
                    // a native unprojection for every marker in the common 2D
                    // browsing state, matching the Android surface.
                    trustworthy = true
                }
                return MapScreenPoint(x: point.x, y: point.y, trustworthy: trustworthy)
            },
            convoyFit: {
                points,
                enabled,
                userPoint,
                followSelfEnabled,
                resumeSelfFollow,
                restoreViewport in
                let state = map.cameraState
                let baseTarget = cameraRuntime.canonicalTarget ?? cameraTarget(from: state)
                meFollowEnabled.wrappedValue = !enabled && followSelfEnabled
                if enabled || !followSelfEnabled {
                    cameraRuntime.cancelIdleReturn()
                }
                if enabled {
                    startupCamera.cancel()
                    cameraResume.cancel()
                    cameraRuntime.cancelCameraReturn()
                    compassFollowRequested = false
                }
                switch MapHomeConvoyViewportPolicy.plan(points: points, focusEnabled: enabled) {
                case .keepCurrentViewport:
                    return
                case .restoreBrowsing:
                    guard restoreViewport else { return }
                    let fallback = cameraBeforeConvoy.wrappedValue
                    cameraBeforeConvoy.wrappedValue = nil
                    let restoreState = MapHomeMeFollowPolicy.restoreState(
                        latestOwnPoint: latestOwnPoint.wrappedValue,
                        userPoint: userPoint,
                        resumeSelfFollow: resumeSelfFollow,
                        suspended: meFollowSuspended.wrappedValue
                    )
                    latestOwnPoint.wrappedValue = restoreState.latestOwnPoint
                    meFollowSuspended.wrappedValue = restoreState.suspended
                    applyMeFollow(
                        restoreState.latestOwnPoint,
                        viewport: viewport,
                        fallback: fallback,
                        snapshot: .of(
                            latitude: state.center.latitude,
                            longitude: state.center.longitude,
                            zoom: state.zoom,
                            bearing: state.bearing,
                            pitch: state.pitch
                        ),
                        restoringBrowsing: true
                    )
                    return
                case .fitConvoy:
                    break
                }
                guard let points, points.count >= 2 else { return }
                if cameraBeforeConvoy.wrappedValue == nil {
                    cameraBeforeConvoy.wrappedValue = .of(
                        latitude: baseTarget.latitude,
                        longitude: baseTarget.longitude,
                        zoom: baseTarget.zoom,
                        bearing: baseTarget.bearing,
                        pitch: baseTarget.pitch
                    )
                }
                let coordinates = points.map {
                    CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
                }
                guard let camera = try? map.camera(
                    for: coordinates,
                    camera: CameraOptions(
                        bearing: baseTarget.bearing,
                        pitch: MapHomeConvoyViewportPolicy.fitComputationPitch
                    ),
                    coordinatesPadding: UIEdgeInsets(top: 110, left: 60, bottom: 150, right: 60),
                    maxZoom: MapHomeConvoyViewportPolicy.maximumFitZoom,
                    offset: nil
                ) else { return }
                applyCameraTarget(
                    MapHomeCameraTarget(
                        latitude: (camera.center ?? state.center).latitude,
                        longitude: (camera.center ?? state.center).longitude,
                        zoom: Double(MapHomeConvoyViewportPolicy.fitZoom(
                            camera.zoom, fallback: CGFloat(baseTarget.zoom)
                        )),
                        bearing: baseTarget.bearing,
                        pitch: surface.is3D ? 45 : 0
                    ),
                    duration: 0.9,
                    viewport: viewport
                )
            },
            center: { point in
                let wasWaitingForInitialLocation = startupCamera.pending
                startupCamera.cancel()
                cameraResume.cancel()
                cameraRuntime.cancelCameraReturn()
                compassFollowRequested = false
                if meFollowEnabled.wrappedValue {
                    meFollowSuspended.wrappedValue = true
                }
                let state = map.cameraState
                let baseTarget = cameraRuntime.canonicalTarget ?? cameraTarget(from: state)
                applyCameraTarget(
                    MapHomeCameraTarget(
                        latitude: point.latitude,
                        longitude: point.longitude,
                        zoom: wasWaitingForInitialLocation ? surface.browsingZoom : baseTarget.zoom,
                        bearing: baseTarget.bearing,
                        pitch: wasWaitingForInitialLocation ? (surface.is3D ? 45 : 0) : baseTarget.pitch
                    ),
                    duration: 0.9,
                    viewport: viewport
                )
            },
            traffic: { enabled, mode in
                MapHomeStyleLayers.applyTraffic(enabled, mode: mode, to: map)
            },
            mapMode: { mode in
                MapHomeStyleLayers.applyMapMode(mode, to: map)
            },
            cameraPreferences: { enabled, zoom in
                MapHomeStyleLayers.apply3D(enabled, to: map)
                // Renderer attachment replays preferences before the first fix.
                // Keep the neutral overview pending consent, and keep native
                // puck ownership after style reloads instead of replacing it
                // with a fixed camera at the renderer's preceding coordinate.
                if startupCamera.pending || cameraResume.pending || cameraRuntime.cameraReturnActive { return }
                if startupCamera.following {
                    if isVisible, surfaceActive, scenePhase == .active,
                       locationAuthorization.isAuthorized, !surface.convoyFocusEnabled {
                        applyCompassViewport(viewport: viewport, snapshot: surface.cameraSnapshot)
                    }
                    return
                }
                let state = map.cameraState
                let target = (cameraRuntime.canonicalTarget ?? cameraTarget(from: state))
                    .applyingPreferences(
                        is3D: enabled,
                        browsingZoom: zoom,
                        browsingOwnsZoom: !surface.convoyFocusEnabled
                            && surface.routeOverlay == nil
                    )
                guard cameraRuntime.canonicalTarget != target else { return }
                guard abs(state.pitch - target.pitch) > 0.01
                        || abs(state.zoom - target.zoom) > 0.01
                else { return }
                applyCameraTarget(target, duration: 0.5, viewport: viewport)
            }
        )
    }

    private func cameraTarget(from state: CameraState) -> MapHomeCameraTarget {
        MapHomeCameraTarget(
            latitude: state.center.latitude,
            longitude: state.center.longitude,
            zoom: state.zoom,
            bearing: state.bearing,
            pitch: state.pitch
        )
    }

    private func updateOwnLocationForVisibility() {
        let visible = surfaceActive && scenePhase == .active && isVisible && mapReady
        let following = (compassFollowRequested || meFollowEnabled) && !meFollowSuspended &&
            !surface.convoyFocusEnabled && surface.routeOverlay == nil
        cameraResume.visibilityChanged(visible: visible, following: following)
        guard visible else {
            surface.updateRenderedOwnPoint(nil)
            stableCourse = surface.cameraSnapshot?.bearing
            if compassFollowRequested { performProgrammaticViewportChange { viewport = .idle } }
            cameraRuntime.cancelAll()
            return
        }
        if !cameraResume.pending {
            if compassFollowRequested, locationAuthorization.isAuthorized, !surface.convoyFocusEnabled {
                applyCompassViewport(viewport: $viewport, snapshot: surface.cameraSnapshot)
            } else {
                startOwnLocationIfNeeded()
            }
        }
        // A covered/inactive scene cancels its timer. A selected destination
        // has already released startup/compass ownership, so it needs a new
        // idle return here even when no camera frame or GPS fix arrives.
        armMeFollowIdleReturn(viewport: $viewport)
    }

    private func startOwnLocationIfNeeded() {
        guard !surface.convoyFocusEnabled,
              startupCamera.takeFollow(
                authorized: locationAuthorization.isAuthorized,
                active: isVisible && surfaceActive && scenePhase == .active,
                rendererReady: mapReady
              ) else { return }
        // Wait for one current fix before arming follow. No cached native course
        // is allowed to spin the camera while location is being reacquired.
        compassFollowRequested = true
        cameraResume.requestReturn()
    }

    /// The return is a one-shot camera owner. Subsequent fixes update its latest
    /// target/heading, but never restart its duration or delay ordinary driving.
    private func consumeCameraReturnFix(_ fix: LocationFix) -> Bool {
        guard compassFollowRequested, !meFollowSuspended, !surface.convoyFocusEnabled,
              surface.routeOverlay == nil, surfaceActive, scenePhase == .active else { return false }
        let previousCourse = stableCourse ?? surface.cameraSnapshot?.bearing ?? 0
        stableCourse = MapCameraResumePolicy.course(
            heading: fix.headingDegrees, speed: fix.speedMetersPerSecond,
            speedAccuracy: fix.speedAccuracyMetersPerSecond, holding: previousCourse)
        latestOwnPoint = MapPoint(longitude: fix.longitude, latitude: fix.latitude)
        let shouldReturn = cameraResume.takeReturn(fixAt: fix.timestamp, now: Date())
        cameraResume.recordFix(at: fix.timestamp)
        if shouldReturn {
            let token = cameraRuntime.beginCameraReturn()
            let snapshot = surface.cameraSnapshot
            let duration = reduceMotion ? 0 : MapCameraResumePolicy.duration
            let targetBearing = surface.compassMode == .northUp ? 0 : (stableCourse ?? previousCourse)
            // A neutral world overview is not a place to fly from on first launch.
            if snapshot == nil || (snapshot?.zoom ?? 0) < 3 {
                viewport = .camera(center: .init(latitude: fix.latitude, longitude: fix.longitude),
                    zoom: max(1, surface.browsingZoom - 1), bearing: targetBearing, pitch: 0)
            }
            applyCameraTarget(MapHomeCameraTarget(latitude: fix.latitude, longitude: fix.longitude,
                zoom: snapshot.map { $0.zoom >= 3 ? $0.zoom : surface.browsingZoom } ?? surface.browsingZoom,
                bearing: MapCameraResumePolicy.shortestBearing(from: snapshot?.bearing ?? targetBearing, to: targetBearing),
                pitch: surface.is3D ? 45 : 0), duration: duration, viewport: $viewport)
            let task = Task { @MainActor in
                if duration > 0 { do { try await Task.sleep(for: .seconds(duration)) } catch { return } }
                guard !Task.isCancelled, cameraRuntime.finishCameraReturn(token),
                      isVisible, surfaceActive, scenePhase == .active, compassFollowRequested,
                      !meFollowSuspended, !surface.convoyFocusEnabled, surface.routeOverlay == nil else { return }
                // Handoff without starting another animated return.
                setCompassFollow(viewport: $viewport, snapshot: surface.cameraSnapshot)
            }
            cameraRuntime.retainCameraReturn(task)
            return true
        }
        if cameraResume.pending || cameraRuntime.cameraReturnActive { return true }
        if stableCourse != previousCourse {
            setCompassFollow(viewport: $viewport, snapshot: surface.cameraSnapshot)
        }
        return true
    }

    private func applyCameraTarget(
        _ target: MapHomeCameraTarget,
        duration: TimeInterval,
        viewport: Binding<Viewport>
    ) {
        cameraRuntime.setTarget(target)
        performProgrammaticViewportChange {
            withViewportAnimation(.easeInOut(duration: reduceMotion ? 0 : duration)) {
                viewport.wrappedValue = .camera(
                    center: CLLocationCoordinate2D(
                        latitude: target.latitude,
                        longitude: target.longitude
                    ),
                    zoom: target.zoom,
                    bearing: target.bearing,
                    pitch: target.pitch
                )
            }
        }
    }

    private func performProgrammaticViewportChange(_ change: () -> Void) {
        let token = cameraRuntime.beginProgrammaticChange()
        let task = Task { @MainActor in
            do { try await Task.sleep(for: .seconds(2)) } catch { return }
            guard !Task.isCancelled,
                  cameraRuntime.finishProgrammaticChange(token) else { return }
            if isVisible, meFollowEnabled, meFollowSuspended {
                armMeFollowIdleReturn(viewport: $viewport)
            }
        }
        cameraRuntime.retainProgrammaticTask(task, for: token)
        change()
    }

    private func armMeFollowIdleReturn(viewport: Binding<Viewport>) {
        guard isVisible,
              surfaceActive,
              scenePhase == .active,
              mapReady,
              meFollowEnabled,
              meFollowSuspended,
              !surface.convoyFocusEnabled,
              !cameraRuntime.programmaticChangeActive
        else {
            cameraRuntime.cancelIdleReturn()
            return
        }
        cameraRuntime.scheduleIdleReturn(after: MapHomeMeFollowPolicy.idleReturnDelay) {
            guard isVisible,
                  surfaceActive,
                  scenePhase == .active,
                  mapReady,
                  meFollowEnabled,
                  meFollowSuspended,
                  !surface.convoyFocusEnabled,
                  !cameraRuntime.programmaticChangeActive else { return }
            meFollowSuspended = false
            surface.resumeSelfFollowAfterIdle()
            applyMeFollow(latestOwnPoint, viewport: viewport)
        }
    }

    private func applyMeFollow(
        _ point: MapPoint?,
        viewport: Binding<Viewport>,
        fallback: MapCameraSnapshot? = nil,
        snapshot: MapCameraSnapshot? = nil,
        restoringBrowsing: Bool = false
    ) {
        if compassFollowRequested, locationAuthorization.isAuthorized {
            applyCompassViewport(viewport: viewport, snapshot: snapshot ?? surface.cameraSnapshot)
            return
        }
        guard let camera = meFollowCamera(
            point,
            fallback: fallback,
            snapshot: snapshot ?? surface.cameraSnapshot,
            restoringBrowsing: restoringBrowsing
        ) else { return }
        applyCameraTarget(
            MapHomeCameraTarget(
                latitude: camera.center.latitude,
                longitude: camera.center.longitude,
                zoom: Double(camera.zoom),
                bearing: Double(camera.bearing),
                pitch: surface.is3D ? 45 : 0
            ),
            duration: 0.9,
            viewport: viewport
        )
    }

    /// The same retained Mapbox viewport owns this user-requested transition.
    /// Uses the visible map's shared fix demand; never requests permission or
    /// starts live sharing. Without consent only north is reset.
    private func applyCompassViewport(
        viewport: Binding<Viewport>,
        snapshot: MapCameraSnapshot?
    ) {
        if locationAuthorization.isAuthorized {
            performProgrammaticViewportChange {
                withViewportAnimation(.easeInOut(duration: reduceMotion ? 0 : 0.5)) {
                    setCompassFollow(viewport: viewport, snapshot: snapshot)
                }
            }
        } else if let snapshot {
            applyCameraTarget(
                MapHomeCameraTarget(
                    latitude: snapshot.latitude,
                    longitude: snapshot.longitude,
                    zoom: snapshot.zoom,
                    bearing: surface.compassMode == .northUp ? 0 : snapshot.bearing,
                    pitch: snapshot.pitch
                ),
                duration: 0.5,
                viewport: viewport
            )
        }
    }

    private func setCompassFollow(viewport: Binding<Viewport>, snapshot: MapCameraSnapshot?) {
        if let point = ownMotion.point { applyPresentedOwnFrame(point, viewport: viewport) }
    }

    private func applyPresentedOwnFrame(_ point: DriverMotionPoint, viewport: Binding<Viewport>) {
        latestOwnPoint = MapPoint(longitude: point.longitude, latitude: point.latitude)
        surface.updateRenderedOwnPoint(isVisible && surfaceActive && scenePhase == .active && locationAuthorization.isAuthorized ? latestOwnPoint : nil)
        guard isVisible, surfaceActive, scenePhase == .active, locationAuthorization.isAuthorized,
              !cameraResume.pending, !cameraRuntime.cameraReturnActive,
              !meFollowSuspended, !surface.convoyFocusEnabled, surface.routeOverlay == nil,
              compassFollowRequested || meFollowEnabled else { return }
        let snapshot = surface.cameraSnapshot
        let target = MapHomeCameraTarget(latitude: point.latitude, longitude: point.longitude,
            zoom: cameraRuntime.canonicalTarget?.zoom ?? snapshot?.zoom ?? surface.browsingZoom,
            bearing: surface.compassMode == .northUp ? 0 : (point.bearing ?? stableCourse ?? snapshot?.bearing ?? 0),
            pitch: surface.is3D ? 45 : 0)
        cameraRuntime.setTarget(target)
        // Already interpolated. A second viewport animation would add latency
        // and make the camera disagree with the puck and breadcrumb tip.
        viewport.wrappedValue = .camera(center: .init(latitude: point.latitude, longitude: point.longitude),
            zoom: target.zoom, bearing: target.bearing, pitch: target.pitch)
    }

    private func accuracyRadius(at point: DriverMotionPoint) -> Double {
        let metersPerPoint = 78_271.51696 * cos(point.latitude * .pi / 180) / pow(2, surface.cameraSnapshot?.zoom ?? surface.browsingZoom)
        guard metersPerPoint > 0, let accuracy = ownMotion.accuracyMeters else { return 0 }
        return min(500, max(0, accuracy / metersPerPoint))
    }

    private func meFollowCamera(
        _ point: MapPoint?,
        fallback: MapCameraSnapshot?,
        snapshot: MapCameraSnapshot?,
        restoringBrowsing: Bool
    ) -> (center: CLLocationCoordinate2D, zoom: CGFloat, bearing: CGFloat, pitch: CGFloat)? {
        MapHomeMeFollowPolicy.camera(
            point: point,
            snapshot: snapshot,
            fallback: fallback,
            restoringBrowsing: restoringBrowsing,
            browsingZoom: surface.browsingZoom
        ).map { camera in
            (
                center: camera.center,
                zoom: camera.zoom,
                bearing: camera.bearing,
                pitch: camera.pitch
            )
        }
    }
}

#Preview("Loading") {
    MapHomeView(
        surface: StubMapSurface(initialState: .loading, autoLoad: false),
        locationProvider: StubLocationProvider()
    )
}

#Preview("Loaded") {
    MapHomeView(
        surface: StubMapSurface(initialState: .loaded, autoLoad: false),
        locationProvider: StubLocationProvider()
    )
}
