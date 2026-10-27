import SwiftUI

struct PartnerApplicationScreen: View {
    @Bindable var coordinator: PartnerApplicationCoordinator
    let onClose: () -> Void

    @State private var form = PartnerApplicationForm()
    @State private var showValidationError = false
    @State private var presentationState = PartnerApplicationPresentationState()

    var body: some View {
        Group {
            if coordinator.submission == .done {
                successContent
            } else {
                applicationForm
            }
        }
        .kccReadingSurface(pageBackground: true)
        .tint(KccTheme.driver)
        .navigationTitle("partners.applicationTitle")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            guard presentationState.consumePreparation() else { return }
            coordinator.prepareForPresentation()
        }
    }

    private var applicationForm: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: KccSpacing.s3) {
                    Label("partners.applicationSubtitle", systemImage: "briefcase").font(.headline)
                        .fixedSize(horizontal: false, vertical: true)
                    Text("partners.privacyNotice").font(.body).foregroundStyle(KccTheme.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }.kccContentCard()
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }

            Section {
                PartnerApplicationTextField(title: "partners.companyNameLabel", text: $form.companyName)
                    .textContentType(.organizationName)
                Picker("partners.categoryLabel", selection: $form.category) {
                    Text("partners.categoryLabel").tag(PartnerCategory?.none)
                    ForEach(PartnerCategory.allCases, id: \.self) { category in
                        Text(LocalizedStringKey(category.localizationKey)).tag(Optional(category))
                    }
                }
                PartnerApplicationTextField(title: "partners.contactNameLabel", text: $form.contactName)
                    .textContentType(.name)
                PartnerApplicationTextField(title: "partners.contactEmailLabel", text: $form.contactEmail)
                    .textContentType(.emailAddress)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                PartnerApplicationTextField(title: "partners.contactPhoneLabel", text: $form.contactPhone)
                    .textContentType(.telephoneNumber)
                    .keyboardType(.phonePad)
                PartnerApplicationTextField(title: "partners.websiteLabel", text: $form.websiteURL)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                PartnerApplicationTextField(title: "partners.messageLabel", text: $form.message, multiline: true)
            }
            .disabled(coordinator.submission == .submitting)
            .listRowBackground(KccTheme.surface)

            if showValidationError, PartnerApplications.validate(form) != nil {
                Text(validationMessage)
                    .foregroundStyle(KccPalette.errorRed)
                    .font(.footnote).fixedSize(horizontal: false, vertical: true)
                    .listRowBackground(KccTheme.surface)
                    .accessibilityIdentifier("partnerApplication.validationError")
            }
            if case .failed(let failure) = coordinator.submission {
                Text(failureMessage(failure))
                    .foregroundStyle(KccPalette.errorRed)
                    .font(.footnote).fixedSize(horizontal: false, vertical: true)
                    .listRowBackground(KccTheme.surface)
                    .accessibilityIdentifier("partnerApplication.submitError")
            }

            Button {
                guard let input = PartnerApplications.input(from: form) else {
                    showValidationError = true
                    return
                }
                showValidationError = false
                Task { await coordinator.submit(input) }
            } label: {
                PartnerApplicationSubmitLabel(submitting: coordinator.submission == .submitting)
            }
            .kccPrimaryAction()
            .disabled(coordinator.submission == .submitting)
            .accessibilityIdentifier("partnerApplication.submit")
            .listRowBackground(Color.clear)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    private var successContent: some View {
        ScrollView {
            SettingsStateCard(title: "partners.submitSuccess", message: "partners.privacyNotice", symbol: "checkmark.circle.fill",
                retryTitle: "partners.close", onRetry: onClose).padding(KccSpacing.s4)
        }
    }

    private var validationMessage: LocalizedStringKey {
        guard let error = PartnerApplications.validate(form) else {
            return "partners.fieldRequired"
        }
        return LocalizedStringKey(error.localizationKey)
    }

    private func failureMessage(_ failure: PartnerApplicationFailure) -> LocalizedStringKey {
        switch failure {
        case .invalidInput: "partners.submitErrorInvalid"
        case .duplicate: "partners.submitErrorDuplicate"
        case .unknown: "partners.submitError"
        }
    }
}

/// Keeps SwiftUI child replacement from preparing the same screen twice.
/// `Group` is transparent, so switching from the form to success content may
/// deliver another appearance callback without a new route presentation.
struct PartnerApplicationPresentationState: Equatable, Sendable {
    private(set) var hasPrepared = false

    mutating func consumePreparation() -> Bool {
        guard !hasPrepared else { return false }
        hasPrepared = true
        return true
    }
}
