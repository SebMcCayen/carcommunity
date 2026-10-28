import SwiftUI

enum PartnersSection: String, CaseIterable, Identifiable {
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
            VStack(spacing: 0) {
                PartnersTabs(selection: $section)
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                        switch section {
                        case .directory: directoryContent
                        case .saved: savedContent
                        }
                    }.padding(KccSpacing.s4)
                }
            }
            .kccReadingSurface(pageBackground: true)
            .tint(KccTheme.driver)
            .navigationTitle("partners.screenTitle")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: onBack) {
                        Label("shell.back", systemImage: "chevron.backward").frame(minHeight: 48)
                    }
                }
            }
            .navigationDestination(for: PartnerDestination.self) { destination in
                PartnerDestinationScreen(coordinator: coordinator, destination: destination)
            }
        }
        .kccReadingSurface(pageBackground: true)
        .tint(KccTheme.driver)
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
            SettingsStateCard(title: "partners.screenTitle", message: "partners.noPartnersNearby", symbol: "building.2")
        case .failed:
            SettingsStateCard(title: "partners.screenTitle", message: "partners.error", symbol: "building.2",
                retryTitle: "partners.retry", onRetry: coordinator.reload)
        case .loaded(let companies):
            if coordinator.offersState == .failed {
                offersFailureRow
            }
            ForEach(companies) { company in
                NavigationLink(value: PartnerDestination(
                    companyId: company.id,
                    focusedOfferId: nil
                )) {
                    PartnerCompanySummary(
                        company: company,
                        offerCount: coordinator.offersState == .loaded && coordinator.offersAreExhaustive
                            ? coordinator.offers(for: company.id).count : nil
                    ).kccContentCard()
                }.buttonStyle(.plain)
            }
            if !coordinator.companiesAreExhaustive {
                loadMoreCompaniesRow
            }
        }
    }

    @ViewBuilder private var savedContent: some View {
        if !coordinator.canAccessMemberOffers {
            SettingsStateCard(title: "partnerOffers.upgradeForMemberOffers", message: "partnerOffers.upgradeForMemberOffersHint", symbol: "lock")
        } else if coordinator.savedState == .failed {
            SettingsStateCard(title: "partners.saved", message: "partnerOffers.loadError", symbol: "bookmark",
                retryTitle: "partners.retry", onRetry: coordinator.reloadSavedOffers)
        } else if coordinator.savedState == .loading {
            HStack {
                Spacer()
                ProgressView("partners.loading")
                Spacer()
            }
        } else if coordinator.savedOffers.isEmpty {
            if !coordinator.savedOffersAreExhaustive {
                Text("partnerOffers.savedLimitedBody")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            SettingsStateCard(title: "partnerOffers.savedEmptyTitle", message: "partnerOffers.savedEmptyBody", symbol: "bookmark")
        } else {
            if !coordinator.savedOffersAreExhaustive {
                Text("partnerOffers.savedLimitedBody")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            ForEach(coordinator.savedOffers) { offer in
                NavigationLink(value: PartnerDestination(
                    companyId: offer.companyId,
                    focusedOfferId: offer.id
                )) {
                    PartnerSavedOfferSummary(offer: offer, companyName: companyName(for: offer.companyId)
                        ?? offer.partnerCompanyName ?? String(localized: "partners.detailTitle"))
                        .kccContentCard()
                }.buttonStyle(.plain)
            }
        }
    }

    private var companies: [PartnerCompany] {
        if case .loaded(let companies) = coordinator.state { return companies }
        return []
    }

    private func companyName(for companyId: String) -> String? {
        companies.first(where: { $0.id == companyId })?.name
    }

    private var unavailableRow: some View {
        SettingsStateCard(title: "partners.screenTitle", message: "shell.unavailable", symbol: "building.2")
    }

    private var offersFailureRow: some View {
        SettingsStateCard(title: "partnerOffers.sectionTitle", message: "partnerOffers.loadError", symbol: "tag",
            retryTitle: "partners.retry", onRetry: coordinator.reload)
    }

    @ViewBuilder private var loadMoreCompaniesRow: some View {
        if coordinator.isLoadingMoreCompanies {
            HStack {
                Spacer()
                ProgressView("partners.loading")
                Spacer()
            }
        } else {
            if coordinator.didFailLoadingMoreCompanies {
                Text("partners.error").foregroundStyle(.secondary)
            }
            Button { Task { await coordinator.loadMoreCompanies() } } label: {
                Text("partners.loadMore").frame(maxWidth: .infinity, minHeight: 48)
            }.buttonStyle(.bordered)
        }
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
                    ScrollView {
                        SettingsStateCard(title: "partners.detailTitle", message: "partners.error", symbol: "building.2",
                            retryTitle: "partners.retry", onRetry: { coordinator.loadCompany(id: destination.companyId) })
                            .padding(KccSpacing.s4)
                    }
                default:
                    ScrollView {
                        SettingsStateCard(title: "partners.detailTitle", message: "shell.unavailable", symbol: "building.2")
                            .padding(KccSpacing.s4)
                    }
                }
            }
        }
        .kccReadingSurface(pageBackground: true)
        .tint(KccTheme.driver)
        .task(id: destination.companyId) {
            coordinator.loadCompany(id: destination.companyId)
        }
        .onDisappear { coordinator.closeCompanyDetail() }
    }
}

private struct PartnerDetailScreen: View {
    @Bindable var coordinator: PartnersCoordinator
    let company: PartnerCompany
    let focusedOfferId: String?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                VStack(alignment: .leading, spacing: KccSpacing.s3) {
                    PartnerCompanySummary(company: company, offerCount: nil, showsChevron: false)
                    if let description = company.description { Text(verbatim: description).font(.body).fixedSize(horizontal: false, vertical: true) }
                    if let address = company.address {
                        Label(address, systemImage: "mappin.and.ellipse").font(.body).foregroundStyle(KccTheme.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    externalActions
                }.kccContentCard()
                Text("partnerOffers.sectionTitle").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                let offers = coordinator.detailOffers(for: company.id)
                if coordinator.companyOffersState == .failed {
                    SettingsStateCard(title: "partnerOffers.sectionTitle", message: "partnerOffers.loadError", symbol: "tag",
                        retryTitle: "partners.retry", onRetry: coordinator.reloadCompanyOffers)
                }
                if !offers.isEmpty {
                    ForEach(offers) { offer in
                        PartnerOfferCard(coordinator: coordinator, offer: offer)
                    }
                } else {
                    switch coordinator.companyOffersState {
                    case .loading: ProgressView("partners.loading")
                    case .failed: EmptyView()
                    case .loaded:
                        if coordinator.companyOffersAreExhaustive { Text("partnerOffers.noOffers").foregroundStyle(KccTheme.secondary) }
                    }
                }
                if coordinator.companyOffersState == .loaded, coordinator.canLoadMoreCompanyOffers {
                    if coordinator.isLoadingMoreCompanyOffers { ProgressView("partners.loading") }
                    else {
                        if coordinator.didFailLoadingMoreCompanyOffers { Text("partnerOffers.loadError").foregroundStyle(KccTheme.secondary) }
                        Button { Task { await coordinator.loadMoreCompanyOffers() } } label: {
                            Text("partners.loadMore").frame(maxWidth: .infinity, minHeight: 48)
                        }.buttonStyle(.bordered)
                    }
                }
            }.padding(KccSpacing.s4)
        }
        .kccReadingSurface(pageBackground: true)
        .tint(KccTheme.driver)
        .navigationTitle(company.name)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: focusedOfferId) {
            if let focusedOfferId {
                coordinator.setExpandedOffer(focusedOfferId, expanded: true)
            }
        }
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
                    .frame(minHeight: 48).fixedSize(horizontal: false, vertical: true)
            }.buttonStyle(.bordered)
        }
        if let phone = PartnerExternalDestination.phone(company.phone) {
            Link(destination: phone) {
                Label("partners.callButton", systemImage: "phone")
                    .frame(minHeight: 48).fixedSize(horizontal: false, vertical: true)
            }.buttonStyle(.bordered)
        }
        if let website = PartnerExternalDestination.website(company.website) {
            Link(destination: website) {
                Label("partners.websiteButton", systemImage: "safari")
                    .frame(minHeight: 48).fixedSize(horizontal: false, vertical: true)
            }.buttonStyle(.bordered)
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

struct PartnerOfferCard: View {
    @Bindable var coordinator: PartnersCoordinator
    let offer: PartnerOffer

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text(verbatim: offer.title).font(.headline).fixedSize(horizontal: false, vertical: true)
            PartnerOfferTypeLabel(type: offer.offerType)
            if !offer.teaserText.isEmpty { Text(verbatim: offer.teaserText).font(.body).fixedSize(horizontal: false, vertical: true) }

            if coordinator.canAccessMemberOffers {
                Button {
                    Task { await coordinator.toggleSaved(offerId: offer.id) }
                } label: {
                    Label(
                        coordinator.savedOfferIds.contains(offer.id)
                            ? "partnerOffers.unsaveOffer" : "partnerOffers.saveOffer",
                        systemImage: coordinator.savedOfferIds.contains(offer.id)
                            ? "bookmark.fill" : "bookmark"
                    ).frame(minHeight: 48).fixedSize(horizontal: false, vertical: true)
                }
                .buttonStyle(.bordered)
                .disabled(saveIsWorking)

                DisclosureGroup(isExpanded: expansionBinding) {
                    detailContent.padding(.top, KccSpacing.s2)
                } label: {
                    Text("partnerOffers.howToRedeem").font(.body).frame(minHeight: 48)
                }
            } else {
                Label("partnerOffers.upgradeForMemberOffers", systemImage: "lock")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.tint)
                Text("partnerOffers.upgradeForMemberOffersHint")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .kccContentCard()
    }

    @ViewBuilder private var detailContent: some View {
        switch coordinator.detailState {
        case .idle, .loading:
            ProgressView("partners.loading")
        case .missing:
            Text("partnerOffers.detailUnavailable").foregroundStyle(.secondary)
        case .failed:
            Text("partnerOffers.detailLoadError").foregroundStyle(KccPalette.errorRed)
        case .loaded(let detail):
            VStack(alignment: .leading, spacing: KccSpacing.s3) {
                if let description = detail.description { Text(verbatim: description).font(.body).fixedSize(horizontal: false, vertical: true) }
                if let instructions = detail.redemptionInstructions {
                    Text("partnerOffers.howToRedeem").font(.subheadline.weight(.semibold))
                    Text(verbatim: instructions).font(.body).fixedSize(horizontal: false, vertical: true)
                }
                if let terms = detail.terms {
                    Text("partnerOffers.terms").font(.subheadline.weight(.semibold))
                    Text(verbatim: terms).font(.footnote).fixedSize(horizontal: false, vertical: true).foregroundStyle(.secondary)
                }
                Label("partnerOffers.drivingSafetyWarning", systemImage: "car.side")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button { Task { await coordinator.revealCode(offerId: offer.id) } } label: {
                    Text("partnerOffers.showCode").frame(minHeight: 48).fixedSize(horizontal: false, vertical: true)
                }
                .kccPrimaryAction()
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
                PartnerOfferCodeValue(code: code)
            } else {
                Text("partnerOffers.codeUnavailable").foregroundStyle(.secondary)
            }
        case .failed(let offerId) where offerId == offer.id:
            Text("partnerOffers.codeLoadError").foregroundStyle(KccPalette.errorRed)
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
        if case .working = coordinator.savedActionStatus { return true }
        return false
    }

    private var codeIsLoading: Bool {
        if case .loading(let offerId) = coordinator.codeStatus { return offerId == offer.id }
        return false
    }
}
