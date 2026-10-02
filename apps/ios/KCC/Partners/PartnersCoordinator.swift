import Foundation
import Observation

@MainActor
@Observable
final class PartnersCoordinator {
    private let repository: PartnersRepository?
    private let subscriptionRepository: SubscriptionStateRepository?
    private let uid: String?
    private var access: AccountAccess

    @ObservationIgnored nonisolated(unsafe) private var companiesTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var offersTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var savedTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var savedOffersTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var entitlementTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var detailTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var companyTask: Task<Void, Never>?

    private(set) var state: PartnersUiState
    private(set) var offers: [PartnerOffer] = []
    private(set) var offersState: PartnerOffersUiState = .loading
    private(set) var offersAreExhaustive = false
    private(set) var companiesAreExhaustive = false
    private(set) var isLoadingMoreCompanies = false
    private(set) var didFailLoadingMoreCompanies = false
    private(set) var isLoadingMoreOffers = false
    private(set) var didFailLoadingMoreOffers = false
    private(set) var savedOfferIds: Set<String> = []
    private(set) var savedOffers: [PartnerOffer] = []
    private(set) var savedOffersAreExhaustive = true
    private(set) var savedState: SavedOffersUiState = .loading
    private(set) var companyLookupState: PartnerCompanyLookupState = .idle
    private(set) var canAccessMemberOffers: Bool
    private(set) var expandedOfferId: String?
    private(set) var detailState: OfferDetailUiState = .idle
    private(set) var codeStatus: OfferCodeStatus = .idle
    private(set) var savedActionStatus: SavedOfferActionStatus = .idle

    private var revealGeneration = 0
    private var savedGeneration = 0
    private var isRunning = false
    private var hasLoadedOffersSnapshot = false
    private var hasLoadedSavedIdsSnapshot = false
    private var hasLoadedSavedSnapshot = false
    private var resolvedCompanies: [String: PartnerCompany] = [:]
    private var companiesCursor: PartnerPageCursor?
    private var offersCursor: PartnerPageCursor?
    private var liveCompanies: [PartnerCompany] = []
    private var pagedCompanies: [PartnerCompany] = []
    private var liveOffers: [PartnerOffer] = []
    private var pagedOffers: [PartnerOffer] = []

    init(
        repository: PartnersRepository?,
        subscriptionRepository: SubscriptionStateRepository?,
        uid: String?,
        access: AccountAccess
    ) {
        self.repository = repository
        self.subscriptionRepository = subscriptionRepository
        self.uid = uid
        self.access = access
        self.state = repository == nil || uid == nil ? .unavailable : .loading
        self.canAccessMemberOffers = PartnerOfferAccess.allows(
            access: access,
            subscription: nil
        )
    }

    deinit {
        companiesTask?.cancel()
        offersTask?.cancel()
        savedTask?.cancel()
        savedOffersTask?.cancel()
        entitlementTask?.cancel()
        detailTask?.cancel()
        companyTask?.cancel()
    }

    func start() {
        guard !isRunning, let repository, uid != nil
        else { return }
        isRunning = true
        subscribeCompanies(repository)
        subscribeOffers(repository)
        subscribeEntitlement()
        if canAccessMemberOffers { subscribeSavedOffers() }
    }

    func stop() {
        isRunning = false
        companiesTask?.cancel()
        offersTask?.cancel()
        savedTask?.cancel()
        savedOffersTask?.cancel()
        entitlementTask?.cancel()
        companyTask?.cancel()
        companiesTask = nil
        offersTask = nil
        savedTask = nil
        savedOffersTask = nil
        entitlementTask = nil
        companyTask = nil

        state = repository == nil || uid == nil ? .unavailable : .loading
        offers = []
        offersState = .loading
        offersAreExhaustive = false
        companiesAreExhaustive = false
        companiesCursor = nil
        offersCursor = nil
        liveCompanies = []
        pagedCompanies = []
        liveOffers = []
        pagedOffers = []
        isLoadingMoreCompanies = false
        didFailLoadingMoreCompanies = false
        isLoadingMoreOffers = false
        didFailLoadingMoreOffers = false
        savedOfferIds = []
        savedOffers = []
        savedOffersAreExhaustive = true
        hasLoadedOffersSnapshot = false
        hasLoadedSavedIdsSnapshot = false
        hasLoadedSavedSnapshot = false
        resolvedCompanies = [:]
        companyLookupState = .idle
        canAccessMemberOffers = PartnerOfferAccess.allows(access: access, subscription: nil)
        savedState = canAccessMemberOffers ? .loading : .unavailable
        savedGeneration += 1
        savedActionStatus = .idle
        clearSensitiveOfferState()
    }

    func reload() {
        guard isRunning, let repository, uid != nil else { return }
        companiesTask?.cancel()
        offersTask?.cancel()
        companiesTask = nil
        offersTask = nil
        state = .loading
        offersState = hasLoadedOffersSnapshot ? .loaded : .loading
        companiesCursor = nil
        offersCursor = nil
        liveCompanies = []
        pagedCompanies = []
        liveOffers = []
        pagedOffers = []
        companiesAreExhaustive = false
        offersAreExhaustive = false
        isLoadingMoreCompanies = false
        didFailLoadingMoreCompanies = false
        isLoadingMoreOffers = false
        didFailLoadingMoreOffers = false
        subscribeCompanies(repository)
        subscribeOffers(repository)
    }

    func loadMoreCompanies() async {
        guard isRunning,
              let repository,
              let cursor = companiesCursor,
              !isLoadingMoreCompanies
        else { return }
        isLoadingMoreCompanies = true
        defer { isLoadingMoreCompanies = false }
        didFailLoadingMoreCompanies = false
        do {
            let page = try await repository.fetchActiveCompanies(after: cursor)
            guard !Task.isCancelled, isRunning else { return }
            pagedCompanies = Self.mergeCompanies(pagedCompanies + page.companies)
            state = .loaded(Self.mergeCompanies(liveCompanies + pagedCompanies))
            companiesCursor = page.nextCursor
            companiesAreExhaustive = page.nextCursor == nil
        } catch is CancellationError {
            return
        } catch {
            guard isRunning else { return }
            didFailLoadingMoreCompanies = true
        }
    }

    func loadMoreOffers() async {
        guard isRunning,
              let repository,
              let cursor = offersCursor,
              !isLoadingMoreOffers
        else { return }
        isLoadingMoreOffers = true
        defer { isLoadingMoreOffers = false }
        didFailLoadingMoreOffers = false
        do {
            let page = try await repository.fetchActiveOffers(after: cursor)
            guard !Task.isCancelled, isRunning else { return }
            pagedOffers = Self.mergeOffers(pagedOffers + page.offers)
            offers = Self.mergeOffers(liveOffers + pagedOffers)
            offersCursor = page.nextCursor
            offersAreExhaustive = page.nextCursor == nil
        } catch is CancellationError {
            return
        } catch {
            guard isRunning else { return }
            didFailLoadingMoreOffers = true
        }
    }

    func updateAccess(_ access: AccountAccess) {
        guard access != self.access else { return }
        clearSensitiveOfferState()
        entitlementTask?.cancel()
        entitlementTask = nil
        self.access = access

        if access.canAccessAdminFeatures {
            setMemberOfferAccess(true)
        } else {
            // Fail closed while a fresh backend subscription snapshot is pending.
            setMemberOfferAccess(false)
            if isRunning { subscribeEntitlement() }
        }
    }

    func offers(for companyId: String) -> [PartnerOffer] {
        let merged = Dictionary(
            (offers + savedOffers).map { ($0.id, $0) },
            uniquingKeysWith: { current, _ in current }
        ).map(\.value)
        return PartnersPresentation.offers(merged, forCompany: companyId)
    }

    func company(id: String) -> PartnerCompany? {
        if case .loaded(let companies) = state,
           let company = companies.first(where: { $0.id == id }) {
            return company
        }
        return resolvedCompanies[id]
    }

    func loadCompany(id: String) {
        if companyLookupState == .loading(id: id) { return }
        guard isRunning else { return }
        guard let repository else {
            companyLookupState = .failed(id: id)
            return
        }
        companyTask?.cancel()
        companyLookupState = .loading(id: id)
        let stream = repository.observeCompany(id: id)
        companyTask = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self else { return }
                switch snapshot {
                case .loaded(let company):
                    if let company {
                        self.resolvedCompanies[id] = company
                        self.companyLookupState = .loaded(id: id)
                    } else {
                        self.resolvedCompanies[id] = nil
                        self.companyLookupState = .missing(id: id)
                    }
                case .failed:
                    self.companyLookupState = .failed(id: id)
                }
            }
        }
    }

    func reloadSavedOffers() {
        guard isRunning, canAccessMemberOffers else { return }
        savedTask?.cancel()
        savedOffersTask?.cancel()
        savedTask = nil
        savedOffersTask = nil
        savedState = hasLoadedSavedSnapshot ? .loaded : .loading
        subscribeSavedOffers()
    }

    func setExpandedOffer(_ offerId: String, expanded: Bool) {
        guard isRunning, canAccessMemberOffers else {
            clearSensitiveOfferState()
            return
        }
        if expanded {
            guard expandedOfferId != offerId else { return }
            expandedOfferId = offerId
            codeStatus = .idle
            subscribeDetail(offerId: offerId)
        } else if expandedOfferId == offerId {
            clearSensitiveOfferState()
        }
    }

    func revealCode(offerId: String) async {
        guard isRunning,
              canAccessMemberOffers,
              expandedOfferId == offerId,
              let repository,
              codeStatus != .loading(offerId: offerId)
        else { return }
        revealGeneration += 1
        let generation = revealGeneration
        codeStatus = .loading(offerId: offerId)
        do {
            let code = try await repository.showOfferCode(offerId: offerId)
            guard !Task.isCancelled,
                  generation == revealGeneration,
                  canAccessMemberOffers,
                  expandedOfferId == offerId
            else { return }
            codeStatus = .shown(offerId: offerId, code: code)
        } catch is CancellationError {
            guard generation == revealGeneration else { return }
            codeStatus = .idle
        } catch {
            guard generation == revealGeneration,
                  canAccessMemberOffers,
                  expandedOfferId == offerId
            else { return }
            codeStatus = .failed(offerId: offerId)
        }
    }

    func toggleSaved(offerId: String) async {
        guard isRunning,
              canAccessMemberOffers,
              let repository,
              let uid,
              !savedActionStatus.isWorking
        else { return }
        let shouldSave = !savedOfferIds.contains(offerId)
        savedGeneration += 1
        let generation = savedGeneration
        savedActionStatus = .working(offerId: offerId)
        do {
            try await repository.setSaved(uid: uid, offerId: offerId, saved: shouldSave)
            guard !Task.isCancelled, generation == savedGeneration else { return }
            // The live owner snapshot remains authoritative; do not mutate
            // savedOfferIds optimistically after a successful write.
            savedActionStatus = .idle
        } catch is CancellationError {
            guard generation == savedGeneration else { return }
            savedActionStatus = .idle
        } catch {
            guard generation == savedGeneration else { return }
            savedActionStatus = .failed(offerId: offerId)
        }
    }

    func resetSavedError() {
        if case .failed = savedActionStatus { savedActionStatus = .idle }
    }

    func clearSensitiveOfferState() {
        detailTask?.cancel()
        detailTask = nil
        expandedOfferId = nil
        detailState = .idle
        revealGeneration += 1
        codeStatus = .idle
    }

    private func subscribeCompanies(_ repository: PartnersRepository) {
        let stream = repository.observeActiveCompanies()
        companiesTask = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self else { return }
                switch snapshot {
                case .loaded(let companies, let nextCursor):
                    self.liveCompanies = companies
                    if self.pagedCompanies.isEmpty {
                        self.companiesCursor = nextCursor
                        self.companiesAreExhaustive = nextCursor == nil
                    }
                    self.didFailLoadingMoreCompanies = false
                    let merged = Self.mergeCompanies(companies + self.pagedCompanies)
                    self.state = merged.isEmpty ? .empty : .loaded(merged)
                case .failed:
                    self.state = .failed
                }
            }
        }
    }

    private func subscribeOffers(_ repository: PartnersRepository) {
        let stream = repository.observeActiveOffers()
        offersTask = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self else { return }
                switch snapshot {
                case .loaded(let offers, let nextCursor):
                    self.liveOffers = offers
                    if self.pagedOffers.isEmpty {
                        self.offersCursor = nextCursor
                        self.offersAreExhaustive = nextCursor == nil
                    }
                    self.offers = Self.mergeOffers(offers + self.pagedOffers)
                    self.didFailLoadingMoreOffers = false
                    self.hasLoadedOffersSnapshot = true
                    self.offersState = .loaded
                    if let expandedOfferId = self.expandedOfferId,
                       !offers.contains(where: { $0.id == expandedOfferId }),
                       !self.savedOffers.contains(where: { $0.id == expandedOfferId }) {
                        self.clearSensitiveOfferState()
                    }
                case .failed:
                    if !self.hasLoadedOffersSnapshot {
                        self.offers = []
                        self.offersAreExhaustive = false
                        self.offersState = .failed
                    }
                }
            }
        }
    }

    private func subscribeEntitlement() {
        guard isRunning,
              !access.canAccessAdminFeatures,
              let subscriptionRepository,
              let uid
        else { return }
        let stream = subscriptionRepository.subscription(uid: uid)
        entitlementTask = Task { [weak self] in
            for await subscription in stream {
                guard !Task.isCancelled, let self else { return }
                self.applyEntitlement(subscription)
            }
        }
    }

    private func applyEntitlement(_ subscription: StoredSubscription?) {
        let allowed = PartnerOfferAccess.allows(access: access, subscription: subscription)
        setMemberOfferAccess(allowed)
    }

    private func setMemberOfferAccess(_ allowed: Bool) {
        guard allowed != canAccessMemberOffers else { return }
        canAccessMemberOffers = allowed
        if allowed {
            savedState = hasLoadedSavedSnapshot ? .loaded : .loading
            if isRunning { subscribeSavedOffers() }
        } else {
            savedTask?.cancel()
            savedOffersTask?.cancel()
            savedTask = nil
            savedOffersTask = nil
            savedOfferIds = []
            savedOffers = []
            savedOffersAreExhaustive = true
            savedState = .unavailable
            hasLoadedSavedIdsSnapshot = false
            hasLoadedSavedSnapshot = false
            savedGeneration += 1
            savedActionStatus = .idle
            clearSensitiveOfferState()
        }
    }

    private func subscribeSavedOffers() {
        guard isRunning, savedTask == nil, let repository, let uid else { return }
        let stream = repository.observeSavedOfferIds(uid: uid)
        savedTask = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self, self.canAccessMemberOffers else { return }
                switch snapshot {
                case .loaded(let ids, let isExhaustive):
                    self.hasLoadedSavedIdsSnapshot = true
                    self.savedOffersAreExhaustive = isExhaustive
                    let changed = ids != self.savedOfferIds
                    self.savedOfferIds = ids
                    if changed {
                        self.savedOffers.removeAll { !ids.contains($0.id) }
                        self.hasLoadedSavedSnapshot = false
                        if let expandedOfferId = self.expandedOfferId,
                           !ids.contains(expandedOfferId),
                           !self.offers.contains(where: { $0.id == expandedOfferId }) {
                            self.clearSensitiveOfferState()
                        }
                    }
                    if changed || self.savedOffersTask == nil {
                        self.subscribeSavedOfferDocuments(ids: ids)
                    }
                case .failed:
                    if !self.hasLoadedSavedIdsSnapshot {
                        self.savedOfferIds = []
                        self.savedState = .failed
                    }
                }
            }
        }
    }

    private func subscribeSavedOfferDocuments(ids: Set<String>) {
        savedOffersTask?.cancel()
        savedOffersTask = nil
        guard let repository else { return }
        if ids.isEmpty {
            savedOffers = []
            hasLoadedSavedSnapshot = true
            savedState = .loaded
            return
        }
        if !hasLoadedSavedSnapshot { savedState = .loading }
        let stream = repository.observeOffers(ids: ids)
        savedOffersTask = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self, self.canAccessMemberOffers else { return }
                switch snapshot {
                case .loaded(let offers, _):
                    self.savedOffers = offers.sorted {
                        $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending
                    }
                    if let expandedOfferId = self.expandedOfferId,
                       !self.offers.contains(where: { $0.id == expandedOfferId }),
                       !self.savedOffers.contains(where: { $0.id == expandedOfferId }) {
                        self.clearSensitiveOfferState()
                    }
                    self.hasLoadedSavedSnapshot = true
                    self.savedState = .loaded
                case .failed:
                    if !self.hasLoadedSavedSnapshot {
                        self.savedOffers = []
                        self.savedState = .failed
                    }
                }
            }
        }
    }

    private func subscribeDetail(offerId: String) {
        guard isRunning, let repository else { return }
        detailTask?.cancel()
        detailState = .loading
        let stream = repository.observeOfferDetail(offerId: offerId)
        detailTask = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self,
                      self.canAccessMemberOffers,
                      self.expandedOfferId == offerId
                else { return }
                switch snapshot {
                case .loaded(let detail):
                    self.detailState = detail.map(OfferDetailUiState.loaded) ?? .missing
                case .failed:
                    self.detailState = .failed
                }
            }
        }
    }

    private static func mergeCompanies(_ companies: [PartnerCompany]) -> [PartnerCompany] {
        Dictionary(companies.map { ($0.id, $0) }, uniquingKeysWith: { current, _ in current })
            .values
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    private static func mergeOffers(_ offers: [PartnerOffer]) -> [PartnerOffer] {
        Array(Dictionary(
            offers.map { ($0.id, $0) },
            uniquingKeysWith: { current, _ in current }
        ).values)
    }
}

private extension SavedOfferActionStatus {
    var isWorking: Bool {
        if case .working = self { return true }
        return false
    }
}
