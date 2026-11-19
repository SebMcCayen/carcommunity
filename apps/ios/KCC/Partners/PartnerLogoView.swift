import FirebaseCore
import FirebaseStorage
import SwiftUI
import UIKit

/// Only server-published Storage paths are resolved. No arbitrary URL from an application is rendered.
struct PartnerLogoView: View {
    let path: String?
    var size: CGFloat = 48
    @State private var resolvedImage: (path: String, image: UIImage)?
    var body: some View {
        Group {
            if let resolvedImage, resolvedImage.path == path {
                Image(uiImage: resolvedImage.image).resizable().scaledToFit()
            } else { fallback }
        }
        .frame(width: size, height: size)
        .padding(size > 40 ? 6 : 2)
        .background(KccTheme.subtle, in: RoundedRectangle(cornerRadius: KccRadius.sm))
        .accessibilityHidden(true)
        .task(id: path) {
            resolvedImage = nil
            guard let path, Self.allowed(path), FirebaseApp.app() != nil else { return }
            let data = await PartnerLogoStorage.imageData(for: path)
            guard !Task.isCancelled, let data, let image = UIImage(data: data) else { return }
            resolvedImage = (path, image)
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
    /// Fetch through authenticated Storage rules. Admin-published logos do not
    /// carry public download tokens. Only bounded Sendable bytes leave the SDK.
    static func imageData(for path: String) async -> Data? {
        let limit: Int64 = 1024 * 1024
        return await withCheckedContinuation { continuation in
            _ = storage.reference(withPath: path).getData(maxSize: limit) { @Sendable data, error in
                guard error == nil, let data, data.count <= Int(limit) else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: data)
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
