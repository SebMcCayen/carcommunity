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
    private(set) var savedOfferIds: Set<String> = []
    private(set) var savedOffers: [PartnerOffer] = []
    private(set) var savedState: SavedOffersUiState = .loading
    private(set) var companyLookupState: PartnerCompanyLookupState = .idle
    private(set) var canAccessMemberOffers: Bool
    private(set) var expandedOfferId: String?
    private(set) var detailState: OfferDetailUiState = .idle
    private(set) var codeStatus: OfferCodeStatus = .idle
    private(set) var savedActionStatus: SavedOfferActionStatus = .idle

    private var revealGeneration = 0
    private var savedGeneration = 0
    private var hasLoadedOffersSnapshot = false
    private var hasLoadedSavedSnapshot = false
    private var resolvedCompanies: [String: PartnerCompany] = [:]

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
        guard companiesTask == nil, offersTask == nil,
              let repository, uid != nil
        else { return }
        subscribeCompanies(repository)
        subscribeOffers(repository)
        subscribeEntitlement()
        if canAccessMemberOffers { subscribeSavedOffers() }
    }

    func reload() {
        guard let repository, uid != nil else { return }
        companiesTask?.cancel()
        offersTask?.cancel()
        companiesTask = nil
        offersTask = nil
        state = .loading
        offersState = hasLoadedOffersSnapshot ? .loaded : .loading
        subscribeCompanies(repository)
        subscribeOffers(repository)
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
            subscribeEntitlement()
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
        if company(id: id) != nil {
            companyLookupState = .loaded(id: id)
            return
        }
        if companyLookupState == .loading(id: id) { return }
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
        guard canAccessMemberOffers else { return }
        savedTask?.cancel()
        savedOffersTask?.cancel()
        savedTask = nil
        savedOffersTask = nil
        savedState = hasLoadedSavedSnapshot ? .loaded : .loading
        subscribeSavedOffers()
    }

    func setExpandedOffer(_ offerId: String, expanded: Bool) {
        guard canAccessMemberOffers else {
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
        guard canAccessMemberOffers,
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
        guard canAccessMemberOffers,
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
                case .loaded(let companies):
                    self.state = companies.isEmpty ? .empty : .loaded(companies)
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
                case .loaded(let offers, let isExhaustive):
                    self.offers = offers
                    self.offersAreExhaustive = isExhaustive
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
        guard !access.canAccessAdminFeatures,
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
            subscribeSavedOffers()
        } else {
            savedTask?.cancel()
            savedOffersTask?.cancel()
            savedTask = nil
            savedOffersTask = nil
            savedOfferIds = []
            savedOffers = []
            savedState = .unavailable
            hasLoadedSavedSnapshot = false
            savedGeneration += 1
            savedActionStatus = .idle
            clearSensitiveOfferState()
        }
    }

    private func subscribeSavedOffers() {
        guard savedTask == nil, let repository, let uid else { return }
        let stream = repository.observeSavedOfferIds(uid: uid)
        savedTask = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self, self.canAccessMemberOffers else { return }
                switch snapshot {
                case .loaded(let ids):
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
                    if !self.hasLoadedSavedSnapshot {
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
        guard let repository else { return }
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
}

private extension SavedOfferActionStatus {
    var isWorking: Bool {
        if case .working = self { return true }
        return false
    }
}
