import SwiftUI

/// Two-step destructive confirmation matching Android: opening this page does
/// nothing, and the first destructive button only opens a system alert. The
/// callable runs after the alert's second confirmation.
struct AccountDeletionScreen: View {
    @Bindable var coordinator: AccountDeletionCoordinator
    let onDeleted: () -> Bool
    let onReauthenticate: () -> Bool
    let onBack: () -> Void

    @State private var confirming = false
    @State private var sessionEndState: SessionEndState = .idle

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: KccSpacing.s4) {
                    SocialFlow {
                        Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 24)).accessibilityHidden(true)
                        Text("settings.accountDeletionWarning").font(.body).fixedSize(horizontal: false, vertical: true)
                    }
                        .foregroundStyle(KccTheme.error)
                        .padding(KccSpacing.s4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(KccTheme.error.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))

                    failureContent

                    Button(role: .destructive) { confirming = true } label: {
                        if coordinator.status.isDeleting {
                            ProgressView().frame(maxWidth: .infinity, minHeight: 48)
                        } else {
                            Text("settings.deleteAccount")
                                .frame(maxWidth: .infinity, minHeight: 48)
                        }
                    }
                    .buttonStyle(.borderedProminent).tint(KccTheme.error)
                    .disabled(
                        !coordinator.isAvailable || coordinator.status.isDeleting
                            || coordinator.status == .deleted
                    )
                    .accessibilityIdentifier("accountDeletion.delete")
                }
                .padding(KccSpacing.s4)
            }
            .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
            .navigationTitle("settings.accountDeletion")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: back) {
                        Label("shell.back", systemImage: "chevron.backward").frame(minHeight: 48)
                    }
                    .disabled(coordinator.status.isDeleting || coordinator.status == .deleted)
                }
            }
        }
        .kccReadingSurface(pageBackground: true)
        .alert("settings.accountDeletionConfirmTitle", isPresented: $confirming) {
            Button("profile.cancelButton", role: .cancel) {}
            Button("settings.deleteAccount", role: .destructive) { startDeletion() }
        } message: {
            Text("settings.accountDeletionConfirmBody")
        }
        .interactiveDismissDisabled(
            coordinator.status.isDeleting || coordinator.status == .deleted
        )
    }

    @ViewBuilder
    private var failureContent: some View {
        if case .failed(let reason) = sessionEndState {
            VStack(alignment: .leading, spacing: KccSpacing.s3) {
                errorText("auth.signOutError")
                Button { attemptSessionEnd(reason) } label: { Text(retryLabel(for: reason)).frame(minHeight: 48) }
                    .kccPrimaryAction()
            }
        } else if sessionEndState == .succeeded {
            ProgressView()
                .frame(maxWidth: .infinity)
                .accessibilityLabel(Text("auth.loading"))
        } else if !coordinator.isAvailable {
            errorText("settings.accountDeletionUnavailable")
        } else if case .failed(let failure) = coordinator.status {
            switch failure {
            case .authenticationRequired:
                VStack(alignment: .leading, spacing: KccSpacing.s3) {
                    errorText("settings.accountDeletionAuthenticationRequired")
                    Button("settings.signInAgain") {
                        attemptSessionEnd(.reauthenticate)
                    }
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
            .font(.subheadline)
            .foregroundStyle(KccTheme.error)
    }

    private func startDeletion() {
        coordinator.resetFailure()
        Task {
            do {
                if try await coordinator.delete() {
                    attemptSessionEnd(.deleted)
                }
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

    private func attemptSessionEnd(_ reason: SessionEndReason) {
        let succeeded: Bool
        switch reason {
        case .deleted: succeeded = onDeleted()
        case .reauthenticate: succeeded = onReauthenticate()
        }
        sessionEndState = succeeded ? .succeeded : .failed(reason)
    }

    private func retryLabel(for reason: SessionEndReason) -> LocalizedStringKey {
        switch reason {
        case .deleted: return "auth.signOut"
        case .reauthenticate: return "settings.signInAgain"
        }
    }
}

private enum SessionEndReason: Equatable {
    case deleted
    case reauthenticate
}

private enum SessionEndState: Equatable {
    case idle
    case succeeded
    case failed(SessionEndReason)
}
