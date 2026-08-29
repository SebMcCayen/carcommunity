import SwiftUI

@main
struct KCCApp: App {
    init() {
        // Null-safe: a checkout without the gitignored GoogleService-Info.plist
        // (CI, a fresh clone) still builds and renders, with Firebase-backed
        // features standing down — the iOS mirror of Android's
        // `createIfAvailable` pattern.
        FirebaseBootstrap.configureIfAvailable()
    }

    var body: some Scene {
        WindowGroup {
            ShellView()
        }
    }
}
