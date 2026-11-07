import SwiftUI
import UIKit

struct InAppNavigationScreen: View {
    let destination: PlaceSuggestion
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var coordinator: InAppNavigationCoordinator

    init(destination: PlaceSuggestion, location: any LocationProvider,
         service: any InAppNavigationService = MapboxInAppNavigationService()) {
        self.destination = destination
        _coordinator = State(initialValue: InAppNavigationCoordinator(location: location, service: service))
    }

    var body: some View {
        Group {
            if let session = coordinator.session {
                GuidanceController(session: session)
                    .ignoresSafeArea()
            } else {
                NavigationStack {
                    VStack(spacing: KccSpacing.s4) {
                        Text(destination.name).font(.title2.weight(.semibold))
                            .fixedSize(horizontal: false, vertical: true)
                        if coordinator.phase == .locating || coordinator.phase == .routing {
                            ProgressView()
                        }
                        Text(message).multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                        if coordinator.phase == .locationDenied {
                            Button("map.locationOpenSettings") {
                                if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                            }.kccPrimaryAction()
                        } else if coordinator.phase == .routeFailed || coordinator.phase == .locationUnavailable {
                            Button("dm.retry") { start() }.kccPrimaryAction()
                        }
                    }
                    .padding(KccSpacing.s6)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .kccReadingSurface(pageBackground: true)
                    .navigationTitle(Text("navigation.title"))
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("addressSearch.savedCancel") { coordinator.stop(); dismiss() }
                                .accessibilityIdentifier("navigation.cancel")
                        }
                    }
                }
            }
        }
        .accessibilityIdentifier("navigation.inApp")
        .task { start() }
        .onDisappear { coordinator.stop() }
    }
    private var message: LocalizedStringKey {
        switch coordinator.phase {
        case .idle, .locating: "navigation.findingLocation"
        case .routing: "navigation.calculatingRoute"
        case .locationDenied: "map.locationPermissionBody"
        case .locationUnavailable: "navigation.locationUnavailable"
        case .unavailable: "navigation.unavailable"
        case .routeFailed: "navigation.routeFailed"
        case .guiding: "navigation.title"
        }
    }
    private func start() { coordinator.start(destination) { dismiss() } }
}

private struct GuidanceController: UIViewControllerRepresentable {
    let session: any InAppGuidanceSession
    func makeUIViewController(context: Context) -> UIViewController { session.viewController }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
    func makeCoordinator() -> SessionLifetime { SessionLifetime(session) }
    static func dismantleUIViewController(_ controller: UIViewController, coordinator: SessionLifetime) { coordinator.session.stop() }

    final class SessionLifetime {
        let session: any InAppGuidanceSession
        init(_ session: any InAppGuidanceSession) { self.session = session }
    }
}
