import SwiftUI

struct OnboardingScreen: View {
    @Environment(\.openURL) private var openURL
    @Bindable var coordinator: OnboardingCoordinator
    @State private var licenceConfirmed = false
    @State private var termsAccepted = false
    @State private var privacyAccepted = false
    @State private var partnerStatsOptIn = true
    @State private var displayName = ""

    private var canSubmit: Bool {
        OnboardingForm.canSubmit(
            licenceConfirmed: licenceConfirmed,
            termsAccepted: termsAccepted,
            privacyAccepted: privacyAccepted,
            displayName: displayName
        ) && coordinator.status != .submitting
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: KccSpacing.s4) {
                Text("onboarding.title")
                    .font(.system(size: KccTypeScale.headingLg, weight: .semibold))
                Text("onboarding.subtitle").foregroundStyle(.secondary)

                Toggle("onboarding.licenceConfirm", isOn: $licenceConfirmed)
                    .accessibilityIdentifier("onboarding.licence")
                Toggle("onboarding.termsAccept", isOn: $termsAccepted)
                    .accessibilityIdentifier("onboarding.terms")
                documentLink("onboarding.termsLink", key: "url.terms")
                Toggle("onboarding.privacyAccept", isOn: $privacyAccepted)
                    .accessibilityIdentifier("onboarding.privacy")
                documentLink("onboarding.privacyLink", key: "url.privacy")

                TextField("onboarding.displayNamePlaceholder", text: $displayName)
                    .textFieldStyle(.roundedBorder)
                    .textContentType(.nickname)
                    .autocorrectionDisabled()
                    .accessibilityLabel(Text("onboarding.displayNameLabel"))
                Text("onboarding.displayNameDescription")
                    .font(.system(size: KccTypeScale.bodySm)).foregroundStyle(.secondary)
                if displayName.trimmingCharacters(in: .whitespacesAndNewlines).count
                    > OnboardingForm.displayNameMaxLength
                {
                    Text("profile.errorTooLong").foregroundStyle(.red)
                }

                Divider()
                Text("privacySettings.partnerStatsTitle")
                    .font(.system(size: KccTypeScale.titleMd, weight: .semibold))
                Text("privacySettings.partnerStatsExplainer").foregroundStyle(.secondary)
                Toggle("onboarding.partnerStatsOptIn", isOn: $partnerStatsOptIn)
                Text("onboarding.partnerStatsNote")
                    .font(.system(size: KccTypeScale.bodySm)).foregroundStyle(.secondary)

                if coordinator.status == .failed {
                    Text("onboarding.error").foregroundStyle(.red)
                }

                Button {
                    Task {
                        await coordinator.submit(
                            displayName: displayName,
                            anonymousPartnerStatsOptIn: partnerStatsOptIn
                        )
                    }
                } label: {
                    if coordinator.status == .submitting {
                        ProgressView().frame(maxWidth: .infinity, minHeight: 44)
                    } else {
                        Text("onboarding.continueButton")
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(!canSubmit)
            }
            .padding(KccSpacing.s6)
        }
        .background(.background)
        .onChange(of: displayName) { _, _ in coordinator.resetFailure() }
    }

    @ViewBuilder
    private func documentLink(_ title: LocalizedStringKey, key: String.LocalizationValue) -> some View {
        Button(title) {
            let value = String(localized: key)
            guard let url = URL(string: value), ["http", "https"].contains(url.scheme?.lowercased()) else { return }
            openURL(url)
        }
        .buttonStyle(.plain)
        .foregroundStyle(.tint)
        .padding(.leading, KccSpacing.s4)
    }
}
