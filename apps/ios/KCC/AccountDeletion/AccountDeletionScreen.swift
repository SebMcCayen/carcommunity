import SwiftUI

/// Two-step destructive confirmation matching Android: opening this page does
/// nothing, and the first destructive button only opens a system alert. The
/// callable runs after the alert's second confirmation.
struct AccountDeletionScreen: View {
    @Bindable var coordinator: AccountDeletionCoordinator
    let onDeleted: () -> Void
    let onReauthenticate: () -> Void
    let onBack: () -> Void

    @State private var confirming = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: KccSpacing.s4) {
                    Label("settings.accountDeletionWarning", systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(KccPalette.errorRed)
                        .padding(KccSpacing.s4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(KccPalette.errorRed.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))

                    failureContent

                    Button(role: .destructive) { confirming = true } label: {
                        if coordinator.status.isDeleting {
                            ProgressView().frame(maxWidth: .infinity, minHeight: 44)
                        } else {
                            Text("settings.deleteAccount")
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!coordinator.isAvailable || coordinator.status.isDeleting)
                    .accessibilityIdentifier("accountDeletion.delete")
                }
                .padding(KccSpacing.s6)
            }
            .navigationTitle("settings.accountDeletion")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: back) {
                        Label("shell.back", systemImage: "chevron.backward")
                    }
                    .disabled(coordinator.status.isDeleting)
                }
            }
        }
        .background(.background, ignoresSafeAreaEdges: .all)
        .alert("settings.accountDeletionConfirmTitle", isPresented: $confirming) {
            Button("profile.cancelButton", role: .cancel) {}
            Button("settings.deleteAccount", role: .destructive) { startDeletion() }
        } message: {
            Text("settings.accountDeletionConfirmBody")
        }
    }

    @ViewBuilder
    private var failureContent: some View {
        if !coordinator.isAvailable {
            errorText("settings.accountDeletionUnavailable")
        } else if case .failed(let failure) = coordinator.status {
            switch failure {
            case .authenticationRequired:
                VStack(alignment: .leading, spacing: KccSpacing.s3) {
                    errorText("settings.accountDeletionAuthenticationRequired")
                    Button("settings.signInAgain", action: onReauthenticate)
                        .buttonStyle(.bordered)
                }
            case .temporarilyUnavailable:
                errorText("settings.accountDeletionTemporaryError")
            case .invalidRequest, .notPermitted, .generic:
                errorText("settings.accountDeletionError")
            }
        }
    }

    private func errorText(_ key: LocalizedStringKey) -> some View {
        Text(key)
            .font(.system(size: KccTypeScale.bodySm))
            .foregroundStyle(KccPalette.errorRed)
    }

    private func startDeletion() {
        coordinator.resetFailure()
        Task {
            do {
                if try await coordinator.delete() { onDeleted() }
            } catch is CancellationError {
                // The owner disappeared; cancellation is not a user-facing failure.
            } catch {
                // The coordinator consumes non-cancellation failures.
            }
        }
    }

    private func back() {
        coordinator.resetFailure()
        onBack()
    }
}
