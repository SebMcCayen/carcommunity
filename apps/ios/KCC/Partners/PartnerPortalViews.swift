import SwiftUI
import PhotosUI
import UIKit
import ImageIO

func portalString(_ key: String) -> String {
    NSLocalizedString(key, tableName: "PartnerPortal", bundle: .main, comment: "Partner portal")
}
struct PortalText: View {
    let key: String
    init(_ key: String) { self.key = key }
    var body: some View { Text(LocalizedStringKey(key), tableName: "PartnerPortal") }
}
struct PortalField: View {
    let key: String
    @Binding var text: String
    var multiline = false
    var body: some View {
        TextField(portalString(key), text: $text, axis: multiline ? .vertical : .horizontal)
            .frame(minHeight: 48).lineLimit(multiline ? 3...8 : 1...1)
    }
}
struct PortalError: View {
    let key: String?
    var body: some View {
        if let key {
            PortalText(key).foregroundStyle(KccPalette.errorRed)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("partnerPortal.error")
        }
    }
}

struct PartnerWorkspaceScreen: View {
    @State private var coordinator = PartnerPortalCoordinator()
    @Environment(\.scenePhase) private var scenePhase
    let onBack: () -> Void
    let onApply: () -> Void

    var body: some View {
        NavigationStack {
            List {
                if coordinator.client == nil { PortalText("portal.unavailable") }
                if coordinator.busy { ProgressView() }
                PortalError(key: coordinator.errorKey)
                if let workspace = coordinator.workspace {
                    if workspace.activeCompanies.isEmpty && workspace.application == nil {
                        Section {
                            PortalText("portal.noCompany")
                            Button(action: onApply) { PortalText("portal.apply") }.frame(minHeight: 48)
                        }
                    }
                    ForEach(workspace.activeCompanies) { company in
                        NavigationLink {
                            PartnerCompanyWorkspace(company: company, coordinator: coordinator)
                        } label: {
                            HStack(spacing: KccSpacing.s3) {
                                PartnerLogoView(path: company.logoPath, size: 48)
                                VStack(alignment: .leading, spacing: KccSpacing.s1) {
                                    Text(company.name).font(.headline)
                                    PortalText(company.canManage ? "portal.manager" : "portal.redeemer").font(.footnote)
                                }
                            }.frame(minHeight: 64)
                        }
                    }
                    if let application = workspace.application {
                        Section {
                            Text(application.companyName).font(.headline)
                            PortalText("portal.application.\(application.status)")
                            if let reason = application.reviewReason { Text(reason) }
                            if application.status == "rejected" {
                                Button(action: onApply) { PortalText("portal.apply") }.frame(minHeight: 48)
                            } else if application.status == "changes_requested" {
                                Button(action: onApply) { PortalText("portal.completeApplication") }.frame(minHeight: 48)
                            }
                        } header: { PortalText("portal.application") }
                    }
                }
            }
            .refreshable { await coordinator.load() }
            .navigationTitle(coordinator.workspace?.menuEntry == .application
                ? String(localized: "partnerPortal.application") : portalString("portal.workspace"))
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: onBack) { Label("shell.back", systemImage: "chevron.backward") }.frame(minHeight: 48)
                }
            }
            .task { await coordinator.load() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { await coordinator.load() } }
            }
        }
        .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
        .accessibilityIdentifier("partnerPortal.workspace")
    }
}

private struct PartnerCompanyWorkspace: View {
    let company: PortalCompany
    @Bindable var coordinator: PartnerPortalCoordinator
    @State private var editorPresented = false
    @State private var scannerPresented = false
    @State private var profilePresented = false
    @State private var selectedOffer: PortalOffer?

    var body: some View {
        List {
            Section {
                HStack {
                    PartnerLogoView(path: company.logoPath, size: 64)
                    VStack(alignment: .leading) {
                        Text(company.name).font(.title3.bold())
                        PortalText("portal.companyStatus.\(company.status)").foregroundStyle(.secondary)
                    }
                }
                Button { scannerPresented = true } label: {
                    Label(portalString("portal.scan"), systemImage: "qrcode.viewfinder")
                }.frame(minHeight: 48)
            }
            if company.canManage {
                Section {
                    Button { profilePresented = true } label: {
                        Label(portalString("portal.editProfile"), systemImage: "building.2")
                    }.frame(minHeight: 48)
                    NavigationLink {
                        PartnerBusinessStatisticsScreen(company: company)
                    } label: { Label(portalString("portal.statistics"), systemImage: "chart.bar") }
                    NavigationLink {
                        PartnerTrafficHeatScreen(companyId: company.companyId)
                    } label: {
                        Label(NSLocalizedString("partnerTraffic.title", comment: ""), systemImage: "map")
                    }.frame(minHeight: 48).accessibilityIdentifier("partnerTraffic.open")
                    Button { selectedOffer = nil; editorPresented = true } label: {
                        Label(portalString("portal.createOffer"), systemImage: "plus.circle")
                    }.frame(minHeight: 48)
                }
                Section {
                    if coordinator.busy { ProgressView() }
                    PortalError(key: coordinator.errorKey)
                    if coordinator.offers.isEmpty && !coordinator.busy { PortalText("portal.noOffers") }
                    ForEach(coordinator.offers) { offer in
                        VStack(alignment: .leading, spacing: KccSpacing.s2) {
                            Text(offer.title).font(.headline)
                            PortalText("portal.offerStatus.\(offer.status)").foregroundStyle(.secondary)
                            if offer.canEdit {
                                Button { selectedOffer = offer; editorPresented = true } label: { PortalText("portal.editDraft") }
                                    .frame(minHeight: 48)
                            }
                            if offer.status == "active" {
                                Button {
                                    Task {
                                        let ok = await coordinator.run { client in
                                            try await client.command("pauseReservations", [
                                                "companyId": company.companyId, "offerId": offer.offerId,
                                                "paused": !(offer.reservationsPaused ?? false),
                                            ])
                                        }
                                        if ok { await coordinator.loadOffers(companyId: company.companyId) }
                                    }
                                } label: {
                                    PortalText(offer.reservationsPaused == true ? "portal.resumeReservations" : "portal.pauseReservations")
                                }.frame(minHeight: 48).disabled(coordinator.busy)
                            }
                        }.padding(.vertical, KccSpacing.s2)
                    }
                } header: { PortalText("portal.offers") } footer: { PortalText("portal.reviewRequired") }
            }
        }
        .navigationTitle(company.name)
        .task(id: company.companyId) { if company.canManage { await coordinator.loadOffers(companyId: company.companyId) } }
        .refreshable { if company.canManage { await coordinator.loadOffers(companyId: company.companyId) } }
        .sheet(isPresented: $editorPresented, onDismiss: {
            Task { await coordinator.loadOffers(companyId: company.companyId) }
        }) {
            PartnerOfferEditor(companyId: company.companyId, offer: selectedOffer)
        }
        .sheet(isPresented: $scannerPresented) { PartnerRedemptionScanner(companyId: company.companyId) }
        .sheet(isPresented: $profilePresented) { PartnerBusinessProfileEditor(company: company) }
        .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
    }
}

private struct PartnerOfferEditor: View {
    let companyId: String
    let offer: PortalOffer?
    @State private var form: PortalOfferForm
    @State private var coordinator = PartnerPortalCoordinator()
    @State private var validationFailed = false
    @Environment(\.dismiss) private var dismiss
    init(companyId: String, offer: PortalOffer?) {
        self.companyId = companyId; self.offer = offer
        _form = State(initialValue: PortalOfferForm(offer: offer))
    }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    PortalField(key: "portal.offerTitle", text: $form.title)
                    PortalField(key: "portal.teaser", text: $form.teaserText, multiline: true)
                    PortalField(key: "portal.description", text: $form.description, multiline: true)
                    PortalField(key: "portal.terms", text: $form.terms, multiline: true)
                    PortalField(key: "portal.percentage", text: $form.percentage).keyboardType(.decimalPad)
                }
                Section {
                    Toggle(portalString("portal.limited"), isOn: $form.limited)
                    if form.limited { Stepper("\(portalString("portal.capacity")): \(form.capacity)", value: $form.capacity, in: 1...10_000) }
                    Stepper("\(portalString("portal.reservationDays")): \(form.reservationDays)", value: $form.reservationDays, in: 1...30)
                    Toggle(portalString("portal.hasStart"), isOn: $form.hasStart)
                    if form.hasStart { DatePicker(portalString("portal.validFrom"), selection: $form.availableFrom, displayedComponents: [.date, .hourAndMinute]) }
                    Toggle(portalString("portal.hasEnd"), isOn: $form.hasEnd)
                    if form.hasEnd { DatePicker(portalString("portal.validUntil"), selection: $form.availableUntil, in: Date()..., displayedComponents: [.date, .hourAndMinute]) }
                } footer: { PortalText("portal.noAppointments") }
                PortalText("portal.reviewRequired")
                if validationFailed { PortalError(key: "portal.invalid") }
                PortalError(key: coordinator.errorKey)
                Button {
                    guard form.isValid else { validationFailed = true; return }
                    Task {
                        let ok = await coordinator.run { client in
                            try await client.command("submitOffer", form.payload(companyId: companyId, offerId: offer?.offerId))
                        }
                        if ok { dismiss() }
                    }
                } label: {
                    if coordinator.busy { ProgressView() } else { PortalText("portal.submitForReview") }
                }.kccPrimaryAction().disabled(coordinator.busy)
            }
            .disabled(coordinator.busy)
            .navigationTitle(portalString("portal.offerEditor"))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("partners.close") { dismiss() } } }
        }.tint(KccTheme.driver)
    }
}

private struct PartnerBusinessProfileEditor: View {
    let company: PortalCompany
    @State private var description: String
    @State private var phone: String
    @State private var website: String
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var logo: Data?
    @State private var logoError: String?
    @State private var photoLoading = false
    @State private var logoRightsConfirmed = false
    @State private var coordinator = PartnerPortalCoordinator()
    @Environment(\.dismiss) private var dismiss
    init(company: PortalCompany) {
        self.company = company
        _description = State(initialValue: company.description ?? "")
        _phone = State(initialValue: company.phone ?? "")
        _website = State(initialValue: company.website ?? "")
    }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    PortalField(key: "portal.description", text: $description, multiline: true)
                    PortalField(key: "portal.publicPhone", text: $phone).keyboardType(.phonePad)
                    PortalField(key: "portal.website", text: $website).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                }
                Section {
                    PhotosPicker(selection: $selectedPhoto, matching: .images) {
                        Label(portalString("portal.selectLogo"), systemImage: "photo")
                    }.frame(minHeight: 48)
                    PortalText("portal.logoRequirements").font(.footnote)
                    if let logo, let image = UIImage(data: logo) {
                        Image(uiImage: image).resizable().scaledToFit().frame(height: 100).accessibilityLabel(portalString("portal.logoPreview"))
                    }
                    if photoLoading { ProgressView() }
                    if logo != nil { Toggle(portalString("portal.logoRights"), isOn: $logoRightsConfirmed) }
                    PortalError(key: logoError)
                }
                PortalError(key: coordinator.errorKey)
                Button {
                    Task {
                        let ok = await coordinator.run { client in
                            if let logo { try await client.command("uploadLogo", ["companyId": company.companyId, "logoBase64": logo.base64EncodedString(), "logoRightsConfirmed": logoRightsConfirmed]) }
                            try await client.command("updateProfile", ["companyId": company.companyId, "description": description,
                                "phone": phone, "website": website])
                        }
                        if ok { dismiss() }
                    }
                } label: { if coordinator.busy { ProgressView() } else { PortalText("portal.save") } }
                .kccPrimaryAction().disabled(coordinator.busy || photoLoading || (logo != nil && !logoRightsConfirmed) || description.trimmingCharacters(in: .whitespacesAndNewlines).count < 2)
            }
            .disabled(coordinator.busy)
            .navigationTitle(portalString("portal.editProfile"))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("partners.close") { dismiss() } } }
            .onChange(of: selectedPhoto) { _, item in
                Task {
                    logoError = nil; photoLoading = true; logoRightsConfirmed = false
                    defer { if selectedPhoto == item { photoLoading = false } }
                    do {
                        let data = try await PortalLogoSelection.load(item)
                        guard !Task.isCancelled, selectedPhoto == item else { return }
                        logo = data
                    } catch {
                        guard selectedPhoto == item else { return }
                        logo = nil; logoError = "portal.logoInvalid"
                    }
                }
            }
        }.tint(KccTheme.driver)
    }
}

struct PartnerBusinessStatisticsScreen: View {
    let company: PortalCompany
    @State private var coordinator = PartnerPortalCoordinator()
    @State private var period = "all"
    var body: some View {
        List {
            Picker(portalString("portal.period"), selection: $period) {
                PortalText("portal.allTime").tag("all")
                ForEach(PortalDate.closedMonths(), id: \.self) { Text($0).tag($0) }
            }
            PortalText("portal.statisticsPrivacy").font(.footnote).foregroundStyle(.secondary)
            if coordinator.busy { ProgressView() }
            PortalError(key: coordinator.errorKey)
            if let statistics = coordinator.statistics {
                if let date = PortalDate.parse(statistics.updatedAt), date.timeIntervalSince1970 > 0 {
                    LabeledContent(portalString("portal.used"), value: statistics.totalRedeemed.formatted())
                    ForEach(statistics.partners.filter { $0.companyId == company.companyId }) { partner in
                        ForEach(partner.offers) { offer in
                            LabeledContent(offer.title, value: offer.totalRedeemed.formatted())
                        }
                    }
                    Text("\(portalString("portal.updated")): \(date.formatted(date: .abbreviated, time: .shortened))").font(.footnote).foregroundStyle(.secondary)
                } else { PortalText("portal.statisticsPending") }
            }
        }
        .navigationTitle(portalString("portal.statistics"))
        .task(id: period) { await coordinator.loadStatistics(companyId: company.companyId, period: period) }
        .refreshable { await coordinator.loadStatistics(companyId: company.companyId, period: period) }
        .tint(KccTheme.driver)
    }
}

enum PortalLogoSelection {
    @MainActor static func load(_ item: PhotosPickerItem?) async throws -> Data? {
        guard let item else { return nil }
        guard let data = try await item.loadTransferable(type: Data.self), data.count <= 5 * 1024 * 1024,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width <= 16_000_000 / height,
              let image = UIImage(data: data), let png = image.pngData(), png.count <= 5 * 1024 * 1024
        else { throw KccFunctionsError(code: .invalidArgument) }
        return png
    }
}
