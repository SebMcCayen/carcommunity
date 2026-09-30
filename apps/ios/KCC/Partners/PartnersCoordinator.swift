import Foundation
import Observation

@MainActor
@Observable
final class PartnersCoordinator {
    private let repository: PartnersRepository?
    private let subscriptionRepository: SubscriptionStateRepository?
    private let uid: String?
    private let access: AccountAccess

    @ObservationIgnored nonisolated(unsafe) private var companiesTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var offersTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var savedTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var entitlementTask: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var detailTask: Task<Void, Never>?

    private(set) var state: PartnersUiState
    private(set) var offers: [PartnerOffer] = []
    private(set) var savedOfferIds: Set<String> = []
    private(set) var canAccessMemberOffers: Bool
    private(set) var expandedOfferId: String?
    private(set) var detailState: OfferDetailUiState = .idle
    private(set) var codeStatus: OfferCodeStatus = .idle
    private(set) var savedActionStatus: SavedOfferActionStatus = .idle

    private var revealGeneration = 0
    private var savedGeneration = 0

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
        entitlementTask?.cancel()
        detailTask?.cancel()
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
        subscribeCompanies(repository)
        subscribeOffers(repository)
    }

    func offers(for companyId: String) -> [PartnerOffer] {
        PartnersPresentation.offers(offers, forCompany: companyId)
    }

    var savedOffers: [PartnerOffer] {
        PartnersPresentation.savedOffers(offers, savedIds: savedOfferIds)
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
                case .loaded(let offers): self.offers = offers
                case .failed: break // retain the last safe teaser snapshot
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
        guard allowed != canAccessMemberOffers else { return }
        canAccessMemberOffers = allowed
        if allowed {
            subscribeSavedOffers()
        } else {
            savedTask?.cancel()
            savedTask = nil
            savedOfferIds = []
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
                case .loaded(let ids): self.savedOfferIds = ids
                case .failed: break // retain the last owner-authoritative set
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
