import SwiftUI

struct PartnerApplicationScreen: View {
    @Bindable var coordinator: PartnerApplicationCoordinator
    let onClose: () -> Void

    @State private var form = PartnerApplicationForm()
    @State private var showValidationError = false

    var body: some View {
        Group {
            if coordinator.submission == .done {
                successContent
            } else {
                applicationForm
            }
        }
        .navigationTitle("partners.applicationTitle")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { coordinator.prepareForPresentation() }
    }

    private var applicationForm: some View {
        Form {
            Section {
                Text("partners.applicationSubtitle")
                Text("partners.privacyNotice")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section {
                TextField("partners.companyNameLabel", text: $form.companyName)
                    .textContentType(.organizationName)
                Picker("partners.categoryLabel", selection: $form.category) {
                    Text("partners.categoryLabel").tag(PartnerCategory?.none)
                    ForEach(PartnerCategory.allCases, id: \.self) { category in
                        Text(LocalizedStringKey(category.localizationKey)).tag(Optional(category))
                    }
                }
                TextField("partners.contactNameLabel", text: $form.contactName)
                    .textContentType(.name)
                TextField("partners.contactEmailLabel", text: $form.contactEmail)
                    .textContentType(.emailAddress)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("partners.contactPhoneLabel", text: $form.contactPhone)
                    .textContentType(.telephoneNumber)
                    .keyboardType(.phonePad)
                TextField("partners.websiteLabel", text: $form.websiteURL)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("partners.messageLabel", text: $form.message, axis: .vertical)
                    .lineLimit(3...8)
            }

            if showValidationError, PartnerApplications.validate(form) != nil {
                Text(validationMessage)
                    .foregroundStyle(.red)
                    .font(.footnote)
                    .accessibilityIdentifier("partnerApplication.validationError")
            }
            if case .failed(let failure) = coordinator.submission {
                Text(failureMessage(failure))
                    .foregroundStyle(.red)
                    .font(.footnote)
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
                HStack {
                    Spacer()
                    if coordinator.submission == .submitting {
                        ProgressView().padding(.trailing, KccSpacing.s2)
                        Text("partners.submitting")
                    } else {
                        Text("partners.submitButton")
                    }
                    Spacer()
                }
            }
            .disabled(coordinator.submission == .submitting)
            .accessibilityIdentifier("partnerApplication.submit")
        }
        .scrollDismissesKeyboard(.interactively)
    }

    private var successContent: some View {
        ContentUnavailableView {
            Label("partners.submitSuccess", systemImage: "checkmark.circle.fill")
        } description: {
            Text("partners.privacyNotice")
        } actions: {
            Button("partners.close", action: onClose)
                .buttonStyle(.borderedProminent)
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
