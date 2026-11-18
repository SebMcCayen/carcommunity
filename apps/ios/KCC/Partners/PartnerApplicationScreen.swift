import SwiftUI
import PhotosUI
import UIKit

struct PartnerApplicationScreen: View {
    @Bindable var coordinator: PartnerApplicationCoordinator
    let onClose: () -> Void
    var accountUID: String? = nil

    @State private var form = PartnerApplicationForm()
    @State private var showValidationError = false
    @State private var presentationState = PartnerApplicationPresentationState()
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var logoError: String?
    @State private var applicationReviewReason: String?
    @State private var loadingApplication = false
    @State private var loadingLogo = false
    @State private var loadedDraft = false
    @State private var restoredDraft = false
    @State private var draftSaved = false
    @State private var draftError: String?

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
        .task {
            guard !loadedDraft else { return }
            loadedDraft = true
            if let accountUID, let saved = PortalApplicationDraftStore.load(uid: accountUID) {
                form = saved; restoredDraft = true
            }
            await loadExistingApplication()
        }
        .onChange(of: coordinator.submission) { _, status in
            if status == .done, let accountUID { PortalApplicationDraftStore.remove(uid: accountUID) }
        }
        .onChange(of: selectedPhoto) { _, item in
            Task {
                loadingLogo = true; logoError = nil; form.logoRightsConfirmed = false
                defer { if selectedPhoto == item { loadingLogo = false } }
                do {
                    let data = try await PortalLogoSelection.load(item)
                    guard !Task.isCancelled, selectedPhoto == item else { return }
                    form.logoBase64 = data?.base64EncodedString()
                } catch { form.logoBase64 = nil; logoError = "portal.logoInvalid" }
            }
        }
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
                PortalField(key: "portal.organizationNumber", text: $form.organizationNumber)
                PortalField(key: "portal.description", text: $form.proposedDescription, multiline: true)
                PortalField(key: "portal.address", text: $form.proposedAddress)
                PortalField(key: "portal.publicPhone", text: $form.publicPhone).keyboardType(.phonePad)
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

            Section {
                Toggle(portalString("portal.requestSelfService"), isOn: $form.selfServiceRequested)
                PortalText("portal.selfServiceExplanation").font(.footnote).foregroundStyle(.secondary)
                Toggle(portalString("portal.requestBillboards"), isOn: $form.billboardRequested)
                if form.billboardRequested {
                    PortalText("portal.billboardExplanation").font(.footnote)
                    PortalField(key: "portal.billboardPlacement", text: $form.billboardPlacementRequest, multiline: true)
                }
                PhotosPicker(selection: $selectedPhoto, matching: .images) {
                    Label(portalString("portal.selectLogo"), systemImage: "photo")
                }.frame(minHeight: 48)
                PortalText("portal.logoRequirements").font(.footnote).foregroundStyle(.secondary)
                if form.billboardRequested && form.logoBase64 == nil { PortalText("portal.billboardLogoRequired").font(.footnote) }
                if form.logoBase64 != nil {
                    Toggle(portalString("portal.logoRights"), isOn: $form.logoRightsConfirmed)
                }
                if loadingLogo { ProgressView() }
                PortalError(key: logoError)
                if let applicationReviewReason { Text(applicationReviewReason).foregroundStyle(.secondary) }
            }.disabled(coordinator.submission == .submitting || loadingApplication)

            Section {
                VStack(alignment: .leading, spacing: KccSpacing.s3) {
                    if let base64 = form.logoBase64, let data = Data(base64Encoded: base64), let image = UIImage(data: data) {
                        Image(uiImage: image).resizable().scaledToFit().frame(maxWidth: .infinity).frame(height: 110)
                            .accessibilityLabel(portalString("portal.logoPreview"))
                    }
                    if !form.companyName.isEmpty { Text(form.companyName).font(.title2.bold()) }
                    if let category = form.category { Text(LocalizedStringKey(category.localizationKey)).foregroundStyle(KccTheme.driver) }
                    if !form.proposedDescription.isEmpty { Text(form.proposedDescription) }
                    if !form.proposedAddress.isEmpty { Label(form.proposedAddress, systemImage: "mappin.and.ellipse") }
                    if !form.publicPhone.isEmpty { Label(form.publicPhone, systemImage: "phone") }
                    if !form.websiteURL.isEmpty { Label(form.websiteURL, systemImage: "safari") }
                }.frame(maxWidth: .infinity, alignment: .leading)
                PortalText("portal.publicPreviewPrivacy").font(.footnote).foregroundStyle(.secondary)
            } header: { PortalText("portal.publicPreview") }

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

            if let accountUID {
                Button {
                    do {
                        try PortalApplicationDraftStore.save(form: form, uid: accountUID)
                        draftSaved = true; draftError = nil
                    } catch { draftError = "portal.draftError" }
                } label: { PortalText("portal.saveDraft") }
                .frame(minHeight: 48).disabled(coordinator.submission == .submitting || loadingApplication)
                PortalText("portal.draftPrivacy").font(.footnote).foregroundStyle(.secondary)
                if draftSaved { PortalText("portal.draftSaved").font(.footnote) }
                PortalError(key: draftError)
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
            .disabled(coordinator.submission == .submitting || loadingLogo || loadingApplication || (form.logoBase64 != nil && !form.logoRightsConfirmed) || (form.billboardRequested && form.logoBase64 == nil))
            .accessibilityIdentifier("partnerApplication.submit")
            .listRowBackground(Color.clear)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    private func loadExistingApplication() async {
        guard let client = PartnerPortalClient.createIfAvailable() else { return }
        loadingApplication = true
        defer { loadingApplication = false }
        do {
            let workspace = try await client.call("workspace", as: PortalWorkspace.self)
            guard !Task.isCancelled, let value = workspace.application, value.status == "changes_requested" else { return }
            applicationReviewReason = value.reviewReason
            guard !restoredDraft else { return }
            form.companyName = value.companyName
            form.category = PartnerCategory(wireValue: value.category)
            form.organizationNumber = value.organizationNumber ?? ""
            form.contactName = value.contactName; form.contactEmail = value.contactEmail
            form.contactPhone = value.contactPhone ?? ""; form.websiteURL = value.website ?? ""
            form.proposedDescription = value.description ?? ""; form.proposedAddress = value.address ?? ""
            form.publicPhone = value.publicPhone ?? ""; form.message = value.message ?? ""
            form.selfServiceRequested = value.selfServiceRequested; form.billboardRequested = value.billboardRequested
            form.billboardPlacementRequest = value.billboardPlacementRequest ?? ""
            form.logoBase64 = value.logoPreviewBase64
            form.logoRightsConfirmed = false
            applicationReviewReason = value.reviewReason
        } catch { /* The form remains usable; submission is authorized by the server. */ }
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
