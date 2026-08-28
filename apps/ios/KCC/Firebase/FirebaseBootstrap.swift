import FirebaseCore
import Foundation

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
        FirebaseApp.configure(options: options)
        isConfigured = true
    }
}
