import DeviceCheck
import FirebaseAppCheck
import FirebaseCore
import Foundation

enum FirebaseAppCheckProviderKind: Equatable {
    case debug
    case appAttest
    case deviceCheck

    static func select(isDebugBuild: Bool, appAttestSupported: Bool) -> Self {
        if isDebugBuild { return .debug }
        return appAttestSupported ? .appAttest : .deviceCheck
    }
}

private final class KCCAppCheckProviderFactory: NSObject, AppCheckProviderFactory {
    func createProvider(with app: FirebaseApp) -> (any AppCheckProvider)? {
        #if DEBUG
        let kind = FirebaseAppCheckProviderKind.select(
            isDebugBuild: true,
            appAttestSupported: false
        )
        #else
        let kind = FirebaseAppCheckProviderKind.select(
            isDebugBuild: false,
            appAttestSupported: DCAppAttestService.shared.isSupported
        )
        #endif

        switch kind {
        case .debug:
            return AppCheckDebugProviderFactory().createProvider(with: app)
        case .appAttest:
            return AppAttestProviderFactory().createProvider(with: app)
        case .deviceCheck:
            return DeviceCheckProviderFactory().createProvider(with: app)
        }
    }
}

/// Configures Firebase only when a `GoogleService-Info.plist` is present in
/// the app bundle.
///
/// The plist is gitignored and injected at build/release time (see
/// `apps/ios/README.md`), exactly like Android's `google-services.json`: a
/// checkout without it must still compile, launch, and render, with every
/// Firebase-backed repository factory returning nil instead of crashing.
/// Repository factories consult ``isConfigured`` before touching any Firebase
/// API — the iOS mirror of Android's `createIfAvailable` pattern.
@MainActor
enum FirebaseBootstrap {
    /// True once `FirebaseApp.configure` has run with a real config. Every
    /// Firebase-backed factory gates on this.
    private(set) static var isConfigured = false

    static func configureIfAvailable() {
        guard FirebaseApp.app() == nil else {
            isConfigured = true
            return
        }
        guard
            let path = Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist"),
            let options = FirebaseOptions(contentsOfFile: path)
        else {
            // No config bundled — a config-less build. Not an error.
            return
        }
        // App Check must be installed before Firebase configures its component
        // graph. Debug builds emit a registrable development token; release
        // builds prefer App Attest and fall back to DeviceCheck where needed.
        AppCheck.setAppCheckProviderFactory(KCCAppCheckProviderFactory())
        FirebaseApp.configure(options: options)
        isConfigured = true
    }
}
