import SwiftUI

private enum PartnersSection: String, CaseIterable, Identifiable {
    case directory, saved
    var id: Self { self }
    var titleKey: LocalizedStringKey {
        switch self {
        case .directory: "partners.directory"
        case .saved: "partners.saved"
        }
    }
}

private struct PartnerDestination: Hashable {
    let companyId: String
    let focusedOfferId: String?
}

struct PartnersScreen: View {
    @Bindable var coordinator: PartnersCoordinator
    let onBack: () -> Void

    @State private var section: PartnersSection = .directory
    @State private var path: [PartnerDestination] = []

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Picker("partners.section", selection: $section) {
                    ForEach(PartnersSection.allCases) { section in
                        Text(section.titleKey).tag(section)
                    }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)

                switch section {
                case .directory: directoryContent
                case .saved: savedContent
                }
            }
            .navigationTitle("partners.screenTitle")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: onBack) {
                        Label("shell.back", systemImage: "chevron.backward")
                    }
                }
            }
            .navigationDestination(for: PartnerDestination.self) { destination in
                PartnerDestinationScreen(coordinator: coordinator, destination: destination)
            }
        }
        .background(.background, ignoresSafeAreaEdges: .all)
        .onAppear { coordinator.start() }
        .onDisappear { coordinator.stop() }
    }

    @ViewBuilder private var directoryContent: some View {
        switch coordinator.state {
        case .unavailable:
            unavailableRow
        case .loading:
            HStack {
                Spacer()
                ProgressView("partners.loading")
                Spacer()
            }
        case .empty:
            ContentUnavailableView(
                "partners.screenTitle",
                systemImage: "building.2",
                description: Text("partners.noPartnersNearby")
            )
            .listRowBackground(Color.clear)
        case .failed:
            VStack(spacing: KccSpacing.s3) {
                Text("partners.error").foregroundStyle(.secondary)
                Button("partners.retry") { coordinator.reload() }
                    .buttonStyle(.borderedProminent)
            }
            .frame(maxWidth: .infinity)
        case .loaded(let companies):
            if coordinator.offersState == .failed {
                offersFailureRow
            }
            ForEach(companies) { company in
                NavigationLink(value: PartnerDestination(
                    companyId: company.id,
                    focusedOfferId: nil
                )) {
                    CompanyRow(
                        company: company,
                        offerCount: coordinator.offersState == .loaded && coordinator.offersAreExhaustive
                            ? coordinator.offers(for: company.id).count : nil
                    )
                }
            }
        }
    }

    @ViewBuilder private var savedContent: some View {
        if !coordinator.canAccessMemberOffers {
            ContentUnavailableView(
                "partnerOffers.upgradeForMemberOffers",
                systemImage: "lock",
                description: Text("partnerOffers.upgradeForMemberOffersHint")
            )
            .listRowBackground(Color.clear)
        } else if coordinator.savedState == .failed {
            VStack(spacing: KccSpacing.s3) {
                Text("partnerOffers.loadError").foregroundStyle(.secondary)
                Button("partners.retry") { coordinator.reloadSavedOffers() }
                    .buttonStyle(.borderedProminent)
            }
            .frame(maxWidth: .infinity)
        } else if coordinator.savedState == .loading {
            HStack {
                Spacer()
                ProgressView("partners.loading")
                Spacer()
            }
        } else if coordinator.savedOffers.isEmpty {
            ContentUnavailableView(
                "partnerOffers.savedEmptyTitle",
                systemImage: "bookmark",
                description: Text("partnerOffers.savedEmptyBody")
            )
            .listRowBackground(Color.clear)
        } else {
            ForEach(coordinator.savedOffers) { offer in
                NavigationLink(value: PartnerDestination(
                    companyId: offer.companyId,
                    focusedOfferId: offer.id
                )) {
                    VStack(alignment: .leading, spacing: KccSpacing.s1) {
                        Text(offer.title).font(.headline)
                        Text(offer.partnerCompanyName ?? companyName(for: offer.companyId))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Text(LocalizedStringKey(offer.offerType.localizationKey))
                            .font(.caption)
                            .foregroundStyle(.tint)
                    }
                    .padding(.vertical, KccSpacing.s1)
                }
            }
        }
    }

    private var companies: [PartnerCompany] {
        if case .loaded(let companies) = coordinator.state { return companies }
        return []
    }

    private func companyName(for companyId: String) -> String {
        companies.first(where: { $0.id == companyId })?.name
            ?? String(localized: "partners.detailTitle")
    }

    private var unavailableRow: some View {
        ContentUnavailableView(
            "shell.unavailable",
            systemImage: "building.2",
            description: Text("shell.unavailable")
        )
        .listRowBackground(Color.clear)
    }

    private var offersFailureRow: some View {
        VStack(spacing: KccSpacing.s3) {
            Text("partnerOffers.loadError").foregroundStyle(.secondary)
            Button("partners.retry") { coordinator.reload() }
                .buttonStyle(.borderedProminent)
        }
        .frame(maxWidth: .infinity)
    }
}

private struct PartnerDestinationScreen: View {
    @Bindable var coordinator: PartnersCoordinator
    let destination: PartnerDestination

    var body: some View {
        Group {
            if let company = coordinator.company(id: destination.companyId) {
                PartnerDetailScreen(
                    coordinator: coordinator,
                    company: company,
                    focusedOfferId: destination.focusedOfferId
                )
            } else {
                switch coordinator.companyLookupState {
                case .idle:
                    ProgressView("partners.loading")
                case .loading(let id) where id == destination.companyId:
                    ProgressView("partners.loading")
                case .failed(let id) where id == destination.companyId:
                    VStack(spacing: KccSpacing.s3) {
                        Text("partners.error")
                        Button("partners.retry") {
                            coordinator.loadCompany(id: destination.companyId)
                        }
                    }
                default:
                    ContentUnavailableView(
                        "shell.unavailable",
                        systemImage: "building.2",
                        description: Text("shell.unavailable")
                    )
                }
            }
        }
        .task(id: destination.companyId) {
            coordinator.loadCompany(id: destination.companyId)
        }
    }
}

private struct CompanyRow: View {
    let company: PartnerCompany
    let offerCount: Int?

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s1) {
            Text(company.name).font(.headline)
            Text(LocalizedStringKey(company.category.localizationKey))
                .font(.subheadline)
                .foregroundStyle(.tint)
            if let offerCount {
                let key = offerCount == 1
                    ? "partnerOffers.offerCountOne" : "partnerOffers.offerCountOther"
                Text(verbatim: String.localizedStringWithFormat(
                    NSLocalizedString(key, comment: "Number of active offers for a partner"),
                    Int64(offerCount)
                ))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, KccSpacing.s1)
    }
}

private struct PartnerDetailScreen: View {
    @Bindable var coordinator: PartnersCoordinator
    let company: PartnerCompany
    let focusedOfferId: String?

    var body: some View {
        List {
            Section {
                Text(LocalizedStringKey(company.category.localizationKey))
                    .foregroundStyle(.tint)
                if let description = company.description { Text(description) }
                if let address = company.address {
                    Label(address, systemImage: "mappin.and.ellipse")
                        .foregroundStyle(.secondary)
                }
                externalActions
            }

            Section("partnerOffers.sectionTitle") {
                let offers = coordinator.offers(for: company.id)
                if !offers.isEmpty {
                    ForEach(offers) { offer in
                        PartnerOfferCard(coordinator: coordinator, offer: offer)
                    }
                } else {
                    switch coordinator.offersState {
                    case .loading:
                        ProgressView("partners.loading")
                    case .failed:
                        Text("partnerOffers.loadError").foregroundStyle(.red)
                        Button("partners.retry") { coordinator.reload() }
                    case .loaded:
                        if coordinator.offersAreExhaustive {
                            Text("partnerOffers.noOffers").foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .navigationTitle(company.name)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: focusedOfferId) {
            if let focusedOfferId {
                coordinator.setExpandedOffer(focusedOfferId, expanded: true)
            }
        }
        .onDisappear { coordinator.clearSensitiveOfferState() }
        .alert("partnerOffers.saveError", isPresented: saveErrorPresented) {
            Button("partners.close") { coordinator.resetSavedError() }
        } message: {
            Text("partnerOffers.saveError")
        }
    }

    @ViewBuilder private var externalActions: some View {
        if let maps = PartnerExternalDestination.maps(
            latitude: company.latitude,
            longitude: company.longitude
        ) {
            Link(destination: maps) {
                Label("partners.navigateButton", systemImage: "arrow.triangle.turn.up.right.diamond")
            }
        }
        if let phone = PartnerExternalDestination.phone(company.phone) {
            Link(destination: phone) {
                Label("partners.callButton", systemImage: "phone")
            }
        }
        if let website = PartnerExternalDestination.website(company.website) {
            Link(destination: website) {
                Label("partners.websiteButton", systemImage: "safari")
            }
        }
    }

    private var saveErrorPresented: Binding<Bool> {
        Binding(
            get: {
                if case .failed = coordinator.savedActionStatus { return true }
                return false
            },
            set: { presented in if !presented { coordinator.resetSavedError() } }
        )
    }
}

private struct PartnerOfferCard: View {
    @Bindable var coordinator: PartnersCoordinator
    let offer: PartnerOffer

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text(offer.title).font(.headline)
            Text(LocalizedStringKey(offer.offerType.localizationKey))
                .font(.caption)
                .foregroundStyle(.tint)
            if !offer.teaserText.isEmpty { Text(offer.teaserText) }

            if coordinator.canAccessMemberOffers {
                Button {
                    Task { await coordinator.toggleSaved(offerId: offer.id) }
                } label: {
                    Label(
                        coordinator.savedOfferIds.contains(offer.id)
                            ? "partnerOffers.unsaveOffer" : "partnerOffers.saveOffer",
                        systemImage: coordinator.savedOfferIds.contains(offer.id)
                            ? "bookmark.fill" : "bookmark"
                    )
                }
                .disabled(saveIsWorking)

                DisclosureGroup(isExpanded: expansionBinding) {
                    detailContent.padding(.top, KccSpacing.s2)
                } label: {
                    Text("partnerOffers.howToRedeem")
                }
            } else {
                Label("partnerOffers.upgradeForMemberOffers", systemImage: "lock")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.tint)
                Text("partnerOffers.upgradeForMemberOffersHint")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, KccSpacing.s2)
    }

    @ViewBuilder private var detailContent: some View {
        switch coordinator.detailState {
        case .idle, .loading:
            ProgressView("partners.loading")
        case .missing:
            Text("partnerOffers.memberRequired").foregroundStyle(.secondary)
        case .failed:
            Text("partnerOffers.loadError").foregroundStyle(.red)
        case .loaded(let detail):
            VStack(alignment: .leading, spacing: KccSpacing.s3) {
                if let description = detail.description { Text(description) }
                if let instructions = detail.redemptionInstructions {
                    Text("partnerOffers.howToRedeem").font(.subheadline.weight(.semibold))
                    Text(instructions)
                }
                if let terms = detail.terms {
                    Text("partnerOffers.terms").font(.subheadline.weight(.semibold))
                    Text(terms).font(.caption).foregroundStyle(.secondary)
                }
                Label("partnerOffers.drivingSafetyWarning", systemImage: "car.side")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Button("partnerOffers.showCode") {
                    Task { await coordinator.revealCode(offerId: offer.id) }
                }
                .buttonStyle(.borderedProminent)
                .disabled(codeIsLoading)
                codeContent
            }
        }
    }

    @ViewBuilder private var codeContent: some View {
        switch coordinator.codeStatus {
        case .loading(let offerId) where offerId == offer.id:
            ProgressView("partners.loading")
        case .shown(let offerId, let code) where offerId == offer.id:
            if let code {
                VStack(alignment: .leading, spacing: KccSpacing.s1) {
                    Text("partnerOffers.codeVisible").font(.caption).foregroundStyle(.secondary)
                    Text(code).font(.title3.monospaced().weight(.semibold)).textSelection(.enabled)
                }
                .padding(KccSpacing.s3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
            } else {
                Text("partnerOffers.codeUnavailable").foregroundStyle(.secondary)
            }
        case .failed(let offerId) where offerId == offer.id:
            Text("partnerOffers.codeLoadError").foregroundStyle(.red)
        default:
            EmptyView()
        }
    }

    private var expansionBinding: Binding<Bool> {
        Binding(
            get: { coordinator.expandedOfferId == offer.id },
            set: { coordinator.setExpandedOffer(offer.id, expanded: $0) }
        )
    }

    private var saveIsWorking: Bool {
        if case .working(let offerId) = coordinator.savedActionStatus {
            return offerId == offer.id
        }
        return false
    }

    private var codeIsLoading: Bool {
        if case .loading(let offerId) = coordinator.codeStatus { return offerId == offer.id }
        return false
    }
}
