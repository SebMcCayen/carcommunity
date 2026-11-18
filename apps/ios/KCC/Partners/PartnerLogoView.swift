import FirebaseCore
import FirebaseStorage
import SwiftUI

/// Only server-published Storage paths are resolved. No arbitrary URL from an application is rendered.
struct PartnerLogoView: View {
    let path: String?
    var size: CGFloat = 48
    @State private var resolvedURL: URL?
    var body: some View {
        Group {
            if let resolvedURL {
                AsyncImage(url: resolvedURL) { image in image.resizable().scaledToFit() } placeholder: { fallback }
            } else { fallback }
        }
        .frame(width: size, height: size)
        .padding(size > 40 ? 6 : 2)
        .background(KccTheme.subtle, in: RoundedRectangle(cornerRadius: KccRadius.sm))
        .accessibilityHidden(true)
        .task(id: path) {
            resolvedURL = nil
            guard let path, Self.allowed(path), FirebaseApp.app() != nil else { return }
            let value = await PartnerLogoStorage.downloadURL(for: path)
            guard !Task.isCancelled else { return }
            resolvedURL = value
        }
    }
    private var fallback: some View {
        Image(systemName: "building.2.fill").font(.system(size: max(16, size * 0.4)))
            .foregroundStyle(KccTheme.driver).frame(maxWidth: .infinity, maxHeight: .infinity)
    }
    static func allowed(_ path: String) -> Bool {
        path.range(of: #"^(companyImages|billboardImages)/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$"#, options: .regularExpression) != nil
            && !path.split(separator: "/").contains("..")
    }
}

@MainActor
private enum PartnerLogoStorage {
    /// Keep the SDK reference on this actor; only the Sendable URL crosses
    /// Firebase's completion boundary.
    static func downloadURL(for path: String) async -> URL? {
        await withCheckedContinuation { continuation in
            storage.reference(withPath: path).downloadURL { url, _ in
                continuation.resume(returning: url)
            }
        }
    }

    static let storage: Storage = {
        let value = Storage.storage()
        if let emulator = FirebaseEmulatorHost.parse(ProcessInfo.processInfo.environment["FIREBASE_STORAGE_EMULATOR_HOST"]) {
            value.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        return value
    }()
}
