import XCTest

@testable import KCC

final class PartnersCoordinatorTests: XCTestCase {
    private final class FakeRepository: PartnersRepository, @unchecked Sendable {
        let companies: [PartnersCollectionSnapshot]
        let offers: [PartnerActiveOffersSnapshot]
        let saved: [SavedOffersSnapshot]
        let directOffers: [PartnerOffer]?
        var companiesContinuation: AsyncStream<PartnersCollectionSnapshot>.Continuation?
        var offersContinuation: AsyncStream<PartnerActiveOffersSnapshot>.Continuation?
        var companyOffersContinuation: AsyncStream<PartnerActiveOffersSnapshot>.Continuation?
        var directOffersContinuation: AsyncStream<PartnerOffersSnapshot>.Continuation?
        var savedContinuation: AsyncStream<SavedOffersSnapshot>.Continuation?
        var directSnapshotsByIds: [Set<String>: [PartnerOffersSnapshot]] = [:]
        var detail = PartnerOfferDetail(description: "Detail", redemptionInstructions: "Use", terms: nil)
        var code: String? = "SAVE20"
        var companiesById: [String: PartnerCompany] = [:]
        var observedCompanyIds: [String] = []
        var setSavedCalls: [(String, String, Bool)] = []
        var companyPages: [PartnerCompaniesPage] = []
        var offerPages: [PartnerOffersPage] = []
        var companyOfferPages: [PartnerOffersPage] = []
        var companyPageDelayNanoseconds: UInt64 = 0
        var offerPageDelayNanoseconds: UInt64 = 0
        var companyOfferPageDelayNanoseconds: UInt64 = 0

        init(
            companies: [PartnersCollectionSnapshot] = [.loaded(companies: [])],
            offers: [PartnerActiveOffersSnapshot] = [.loaded(offers: [])],
            saved: [SavedOffersSnapshot] = [.loaded(ids: [])],
            directOffers: [PartnerOffer]? = nil
        ) {
            self.companies = companies
            self.offers = offers
            self.saved = saved
            self.directOffers = directOffers
        }

        func observeActiveCompanies() -> AsyncStream<PartnersCollectionSnapshot> {
            AsyncStream { continuation in
                companiesContinuation = continuation
                companies.forEach { continuation.yield($0) }
            }
        }

        func sendCompanies(_ snapshot: PartnersCollectionSnapshot) {
            companiesContinuation?.yield(snapshot)
        }

        func fetchActiveCompanies(
            after cursor: PartnerPageCursor
        ) async throws -> PartnerCompaniesPage {
            if companyPageDelayNanoseconds > 0 {
                try await Task.sleep(nanoseconds: companyPageDelayNanoseconds)
            }
            guard !companyPages.isEmpty else {
                return PartnerCompaniesPage(companies: [], nextCursor: nil)
            }
            return companyPages.removeFirst()
        }

        func observeCompany(id: String) -> AsyncStream<PartnerCompanySnapshot> {
            observedCompanyIds.append(id)
            return AsyncStream { continuation in continuation.yield(.loaded(companiesById[id])) }
        }

        func observeActiveOffers() -> AsyncStream<PartnerActiveOffersSnapshot> {
            AsyncStream { continuation in
                offersContinuation = continuation
                offers.forEach { continuation.yield($0) }
            }
        }
        func fetchActiveOffers(after cursor: PartnerPageCursor) async throws -> PartnerOffersPage {
            if offerPageDelayNanoseconds > 0 {
                try await Task.sleep(nanoseconds: offerPageDelayNanoseconds)
            }
            guard !offerPages.isEmpty else {
                return PartnerOffersPage(offers: [], nextCursor: nil)
            }
            return offerPages.removeFirst()
        }

        func observeActiveOffers(companyId: String) -> AsyncStream<PartnerActiveOffersSnapshot> {
            let scoped = offers.compactMap { snapshot -> PartnerActiveOffersSnapshot? in
                guard case .loaded(let values, let cursor) = snapshot else { return snapshot }
                return .loaded(offers: values.filter { $0.companyId == companyId }, nextCursor: cursor)
            }
            return AsyncStream { continuation in
                companyOffersContinuation = continuation
                scoped.forEach { continuation.yield($0) }
            }
        }

        func fetchActiveOffers(
            companyId: String,
            after cursor: PartnerPageCursor
        ) async throws -> PartnerOffersPage {
            if companyOfferPageDelayNanoseconds > 0 {
                try await Task.sleep(nanoseconds: companyOfferPageDelayNanoseconds)
            }
            guard !companyOfferPages.isEmpty else {
                return PartnerOffersPage(offers: [], nextCursor: nil)
            }
            return companyOfferPages.removeFirst()
        }

        func sendOffers(_ snapshot: PartnerActiveOffersSnapshot) {
            offersContinuation?.yield(snapshot)
        }

        func sendCompanyOffers(_ snapshot: PartnerActiveOffersSnapshot) {
            companyOffersContinuation?.yield(snapshot)
        }

        func observeOffers(ids: Set<String>) -> AsyncStream<PartnerOffersSnapshot> {
            let snapshotsToYield: [PartnerOffersSnapshot]
            if let configuredSnapshots = directSnapshotsByIds[ids] {
                snapshotsToYield = configuredSnapshots
            } else {
                let fallback = offers.compactMap { snapshot -> [PartnerOffer]? in
                    if case .loaded(let values, _) = snapshot { return values }
                    return nil
                }.flatMap { $0 }
                let resolved = (directOffers ?? fallback).filter { ids.contains($0.id) }
                snapshotsToYield = [.loaded(offers: resolved)]
            }
            return AsyncStream { continuation in
                directOffersContinuation = continuation
                snapshotsToYield.forEach { continuation.yield($0) }
            }
        }

        func sendDirectOffers(_ snapshot: PartnerOffersSnapshot) {
            directOffersContinuation?.yield(snapshot)
        }

        func observeOfferDetail(offerId: String) -> AsyncStream<PartnerOfferDetailSnapshot> {
            AsyncStream { continuation in continuation.yield(.loaded(detail)) }
        }

        func observeSavedOfferIds(uid: String) -> AsyncStream<SavedOffersSnapshot> {
            AsyncStream { continuation in
                savedContinuation = continuation
                saved.forEach { continuation.yield($0) }
            }
        }

        func sendSaved(_ snapshot: SavedOffersSnapshot) { savedContinuation?.yield(snapshot) }

        func showOfferCode(offerId: String) async throws -> String? { code }

        func setSaved(uid: String, offerId: String, saved: Bool) async throws {
            setSavedCalls.append((uid, offerId, saved))
        }
    }

    private final class FakeSubscriptions: SubscriptionStateRepository, @unchecked Sendable {
        private let snapshots: [StoredSubscription?]
        init(_ snapshots: [StoredSubscription?]) { self.snapshots = snapshots }
        func subscription(uid: String) -> AsyncStream<StoredSubscription?> {
            AsyncStream { continuation in snapshots.forEach { continuation.yield($0) } }
        }
    }

    @MainActor
    func testLoadsDirectoryAndKeepsMemberContentLockedWithoutPaidRecord() async {
        let company = PartnerCompany(
            id: "c1", name: "Partner", category: .workshop, description: nil,
            website: nil, phone: nil, address: nil, latitude: nil, longitude: nil
        )
        let repository = FakeRepository(companies: [.loaded(companies: [company])])
        let coordinator = PartnersCoordinator(
            repository: repository,
            subscriptionRepository: FakeSubscriptions([nil]),
            uid: "me",
            access: .unrestrictedCommunity
        )
        coordinator.start()
        await waitUntil { coordinator.state == .loaded([company]) }
        XCTAssertFalse(coordinator.canAccessMemberOffers)
        coordinator.setExpandedOffer("o1", expanded: true)
        XCTAssertNil(coordinator.expandedOfferId)
    }

    @MainActor
    func testPaidEntitlementEnablesSavedDetailAndCodeThenClearingDropsSecret() async {
        let offer = PartnerOffer(
            id: "o1", companyId: "c1", partnerCompanyName: "Partner", title: "Offer",
            teaserText: "Teaser", offerType: .discountCode
        )
        let repository = FakeRepository(
            offers: [.loaded(offers: [offer])],
            saved: [.loaded(ids: ["o1"])]
        )
        let plus = StoredSubscription(
            tier: "plus", status: "active", entitlement: "member_monthly", userId: "me"
        )
        let coordinator = PartnersCoordinator(
            repository: repository,
            subscriptionRepository: FakeSubscriptions([plus]),
            uid: "me",
            access: .unrestrictedCommunity
        )
        coordinator.start()
        await waitUntil { coordinator.canAccessMemberOffers && coordinator.savedOfferIds == ["o1"] }
        coordinator.setExpandedOffer("o1", expanded: true)
        await waitUntil {
            if case .loaded = coordinator.detailState { return true }
            return false
        }
        await coordinator.revealCode(offerId: "o1")
        XCTAssertEqual(coordinator.codeStatus, .shown(offerId: "o1", code: "SAVE20"))
        coordinator.clearSensitiveOfferState()
        XCTAssertEqual(coordinator.codeStatus, .idle)
        XCTAssertEqual(coordinator.detailState, .idle)
    }

    @MainActor
    func testSaveUsesOwnerAndWaitsForListenerInsteadOfOptimisticMutation() async {
        let repository = FakeRepository()
        let admin = AccountAccess(role: .admin, activeMember: false, suspended: false, deleted: false)
        let coordinator = PartnersCoordinator(
            repository: repository,
            subscriptionRepository: nil,
            uid: "me",
            access: admin
        )
        coordinator.start()
        await coordinator.toggleSaved(offerId: "o1")
        XCTAssertEqual(repository.setSavedCalls.count, 1)
        XCTAssertEqual(repository.setSavedCalls.first?.0, "me")
        XCTAssertEqual(repository.setSavedCalls.first?.1, "o1")
        XCTAssertEqual(repository.setSavedCalls.first?.2, true)
        XCTAssertFalse(coordinator.savedOfferIds.contains("o1"))
    }

    @MainActor
    func testInitialOffersFailureIsNotPresentedAsAnAuthoritativeEmptySnapshot() async {
        let company = PartnerCompany(
            id: "c1", name: "Partner", category: .workshop, description: nil,
            website: nil, phone: nil, address: nil, latitude: nil, longitude: nil
        )
        let repository = FakeRepository(
            companies: [.loaded(companies: [company])],
            offers: [.failed(code: "unavailable")]
        )
        let coordinator = PartnersCoordinator(
            repository: repository,
            subscriptionRepository: nil,
            uid: "me",
            access: .unrestrictedCommunity
        )

        coordinator.start()
        await waitUntil { coordinator.offersState == .failed }

        XCTAssertTrue(coordinator.offers.isEmpty)
        XCTAssertEqual(coordinator.state, .loaded([company]))
    }

    @MainActor
    func testCappedOfferSnapshotIsNotTreatedAsExhaustive() async {
        let repository = FakeRepository(
            offers: [.loaded(offers: [], nextCursor: PartnerPageCursor(
                createdAt: Date(timeIntervalSince1970: 1),
                documentId: "cursor"
            ))]
        )
        let coordinator = PartnersCoordinator(
            repository: repository,
            subscriptionRepository: nil,
            uid: "me",
            access: .unrestrictedCommunity
        )

        coordinator.start()
        await waitUntil { coordinator.offersState == .loaded }

        XCTAssertFalse(coordinator.offersAreExhaustive)
    }

    @MainActor
    func testLoadsAdditionalCompanyAndOfferPages() async {
        let cursor = PartnerPageCursor(
            createdAt: Date(timeIntervalSince1970: 1),
            documentId: "cursor"
        )
        let firstCompany = PartnerCompany(
            id: "c1", name: "Zulu", category: .workshop, description: nil,
            website: nil, phone: nil, address: nil, latitude: nil, longitude: nil
        )
        let nextCompany = PartnerCompany(
            id: "c2", name: "Alpha", category: .retail, description: nil,
            website: nil, phone: nil, address: nil, latitude: nil, longitude: nil
        )
        let firstOffer = PartnerOffer(
            id: "o1", companyId: firstCompany.id, partnerCompanyName: nil, title: "First",
            teaserText: "", offerType: .other
        )
        let nextOffer = PartnerOffer(
            id: "o2", companyId: nextCompany.id, partnerCompanyName: nil, title: "Next",
            teaserText: "", offerType: .other
        )
        let repository = FakeRepository(
            companies: [.loaded(companies: [firstCompany], nextCursor: cursor)],
            offers: [.loaded(offers: [firstOffer], nextCursor: cursor)]
        )
        repository.companyPages = [PartnerCompaniesPage(
            companies: [nextCompany],
            nextCursor: nil
        )]
        repository.offerPages = [PartnerOffersPage(offers: [nextOffer], nextCursor: nil)]
        let coordinator = PartnersCoordinator(
            repository: repository,
            subscriptionRepository: nil,
            uid: "me",
            access: .unrestrictedCommunity
        )

        coordinator.start()
        await waitUntil {
            coordinator.state == .loaded([firstCompany])
                && coordinator.offers == [firstOffer]
        }
        await coordinator.loadMoreCompanies()
        await coordinator.loadMoreOffers()

        XCTAssertEqual(coordinator.state, .loaded([nextCompany, firstCompany]))
        XCTAssertEqual(Set(coordinator.offers), [firstOffer, nextOffer])
        XCTAssertTrue(coordinator.companiesAreExhaustive)
        XCTAssertTrue(coordinator.offersAreExhaustive)

        repository.sendCompanies(.loaded(companies: [firstCompany], nextCursor: cursor))
        repository.sendOffers(.loaded(offers: [firstOffer], nextCursor: cursor))
        await waitUntil {
            coordinator.state == .loaded([nextCompany, firstCompany])
                && Set(coordinator.offers) == [firstOffer, nextOffer]
        }

        XCTAssertTrue(coordinator.companiesAreExhaustive)
        XCTAssertTrue(coordinator.offersAreExhaustive)

        let shiftedCursor = PartnerPageCursor(
            createdAt: Date(timeIntervalSince1970: 2),
            documentId: "shifted"
        )
        repository.sendCompanies(.loaded(companies: [firstCompany], nextCursor: shiftedCursor))
        repository.sendOffers(.loaded(offers: [firstOffer], nextCursor: shiftedCursor))
        await waitUntil {
            coordinator.state == .loaded([firstCompany])
                && coordinator.offers == [firstOffer]
        }

        XCTAssertFalse(coordinator.companiesAreExhaustive)
        XCTAssertFalse(coordinator.offersAreExhaustive)
    }

    @MainActor
    func testReloadInvalidatesAnInFlightCompanyPage() async {
        let cursor = PartnerPageCursor(createdAt: .distantPast, documentId: "cursor")
        let liveCompany = PartnerCompany(
            id: "live", name: "Live", category: .other, description: nil,
            website: nil, phone: nil, address: nil, latitude: nil, longitude: nil
        )
        let staleCompany = PartnerCompany(
            id: "stale", name: "Stale", category: .other, description: nil,
            website: nil, phone: nil, address: nil, latitude: nil, longitude: nil
        )
        let repository = FakeRepository(
            companies: [.loaded(companies: [liveCompany], nextCursor: cursor)]
        )
        repository.companyPages = [PartnerCompaniesPage(companies: [staleCompany], nextCursor: nil)]
        repository.companyPageDelayNanoseconds = 100_000_000
        let coordinator = PartnersCoordinator(
            repository: repository, subscriptionRepository: nil, uid: "me",
            access: .unrestrictedCommunity
        )
        coordinator.start()
        await waitUntil { coordinator.state == .loaded([liveCompany]) }

        let pageTask = Task { await coordinator.loadMoreCompanies() }
        try? await Task.sleep(nanoseconds: 10_000_000)
        coordinator.reload()
        await pageTask.value

        XCTAssertEqual(coordinator.state, .loaded([liveCompany]))
        XCTAssertFalse(coordinator.isLoadingMoreCompanies)
    }

    @MainActor
    func testLiveCompanyBoundaryInvalidatesFirstInFlightPage() async {
        let cursor = PartnerPageCursor(createdAt: .distantPast, documentId: "old")
        let shifted = PartnerPageCursor(createdAt: .distantPast, documentId: "new")
        let live = PartnerCompany(
            id: "live", name: "Live", category: .other, description: nil,
            website: nil, phone: nil, address: nil, latitude: nil, longitude: nil
        )
        let stale = PartnerCompany(
            id: "stale", name: "Stale", category: .other, description: nil,
            website: nil, phone: nil, address: nil, latitude: nil, longitude: nil
        )
        let repository = FakeRepository(companies: [.loaded(companies: [live], nextCursor: cursor)])
        repository.companyPages = [PartnerCompaniesPage(companies: [stale], nextCursor: nil)]
        repository.companyPageDelayNanoseconds = 100_000_000
        let coordinator = PartnersCoordinator(
            repository: repository, subscriptionRepository: nil, uid: "me",
            access: .unrestrictedCommunity
        )
        coordinator.start()
        await waitUntil { coordinator.state == .loaded([live]) }

        let pageTask = Task { await coordinator.loadMoreCompanies() }
        await waitUntil { coordinator.isLoadingMoreCompanies }
        repository.sendCompanies(.loaded(companies: [live], nextCursor: shifted))
        await waitUntil { !coordinator.isLoadingMoreCompanies }
        await pageTask.value

        XCTAssertEqual(coordinator.state, .loaded([live]))
        XCTAssertFalse(coordinator.companiesAreExhaustive)
    }

    @MainActor
    func testLiveOfferBoundaryInvalidatesFirstInFlightPage() async {
        let cursor = PartnerPageCursor(createdAt: .distantPast, documentId: "old")
        let shifted = PartnerPageCursor(createdAt: .distantPast, documentId: "new")
        let live = PartnerOffer(
            id: "live", companyId: "c1", partnerCompanyName: nil, title: "Live",
            teaserText: "", offerType: .other
        )
        let stale = PartnerOffer(
            id: "stale", companyId: "c1", partnerCompanyName: nil, title: "Stale",
            teaserText: "", offerType: .other
        )
        let repository = FakeRepository(offers: [.loaded(offers: [live], nextCursor: cursor)])
        repository.offerPages = [PartnerOffersPage(offers: [stale], nextCursor: nil)]
        repository.offerPageDelayNanoseconds = 100_000_000
        let coordinator = PartnersCoordinator(
            repository: repository, subscriptionRepository: nil, uid: "me",
            access: .unrestrictedCommunity
        )
        coordinator.start()
        await waitUntil { coordinator.offers == [live] }

        let pageTask = Task { await coordinator.loadMoreOffers() }
        await waitUntil { coordinator.isLoadingMoreOffers }
        repository.sendOffers(.loaded(offers: [live], nextCursor: shifted))
        await waitUntil { !coordinator.isLoadingMoreOffers }
        await pageTask.value

        XCTAssertEqual(coordinator.offers, [live])
        XCTAssertFalse(coordinator.offersAreExhaustive)
    }

    @MainActor
    func testPagedOfferLosingActiveVisibilityClearsSensitiveState() async {
        let cursor = PartnerPageCursor(createdAt: .distantPast, documentId: "cursor")
        let pagedOffer = PartnerOffer(
            id: "paged", companyId: "c1", partnerCompanyName: nil, title: "Paged",
            teaserText: "", offerType: .discountCode
        )
        let repository = FakeRepository(
            offers: [.loaded(offers: [], nextCursor: cursor)],
            directOffers: [pagedOffer]
        )
        repository.offerPages = [PartnerOffersPage(offers: [pagedOffer], nextCursor: nil)]
        let coordinator = PartnersCoordinator(
            repository: repository, subscriptionRepository: nil, uid: "me",
            access: AccountAccess(role: .admin, activeMember: false, suspended: false, deleted: false)
        )
        coordinator.start()
        await waitUntil { coordinator.offersState == .loaded }
        await coordinator.loadMoreOffers()
        coordinator.setExpandedOffer(pagedOffer.id, expanded: true)
        await waitUntil { coordinator.expandedOfferId == pagedOffer.id }

        repository.sendDirectOffers(.loaded(offers: []))
        await waitUntil { coordinator.expandedOfferId == nil }

        XCTAssertEqual(coordinator.detailState, .idle)
        XCTAssertEqual(coordinator.codeStatus, .idle)
    }

    @MainActor
    func testDisappearingExpandedOfferClearsDetailAndRevealedCode() async {
        let offer = PartnerOffer(
            id: "o1", companyId: "c1", partnerCompanyName: "Partner", title: "Offer",
            teaserText: "Teaser", offerType: .discountCode
        )
        let repository = FakeRepository(offers: [.loaded(offers: [offer])])
        let admin = AccountAccess(role: .admin, activeMember: false, suspended: false, deleted: false)
        let coordinator = PartnersCoordinator(
            repository: repository,
            subscriptionRepository: nil,
            uid: "me",
            access: admin
        )
        coordinator.start()
        await waitUntil { coordinator.offersState == .loaded }
        coordinator.setExpandedOffer("o1", expanded: true)
        await waitUntil {
            if case .loaded = coordinator.detailState { return true }
            return false
        }
        await coordinator.revealCode(offerId: "o1")
        XCTAssertEqual(coordinator.codeStatus, .shown(offerId: "o1", code: "SAVE20"))

        repository.sendDirectOffers(.loaded(offers: []))
        await waitUntil { coordinator.expandedOfferId == nil }

        XCTAssertEqual(coordinator.detailState, .idle)
        XCTAssertEqual(coordinator.codeStatus, .idle)
    }

    @MainActor
    func testInactiveSavedOfferClearsDetailAndRevealedCode() async {
        let offer = PartnerOffer(
            id: "o1", companyId: "c1", partnerCompanyName: "Partner", title: "Offer",
            teaserText: "Teaser", offerType: .discountCode
        )
        let repository = FakeRepository(
            offers: [.loaded(offers: [offer])],
            saved: [.loaded(ids: [offer.id])],
            directOffers: [offer]
        )
        let coordinator = PartnersCoordinator(
            repository: repository,
            subscriptionRepository: nil,
            uid: "me",
            access: AccountAccess(role: .admin, activeMember: false, suspended: false, deleted: false)
        )
        coordinator.start()
        await waitUntil { coordinator.savedOffers == [offer] && coordinator.offers == [offer] }
        coordinator.setExpandedOffer(offer.id, expanded: true)
        await coordinator.revealCode(offerId: offer.id)

        repository.sendOffers(.loaded(offers: []))
        await waitUntil { coordinator.offers.isEmpty }
        repository.sendDirectOffers(.loaded(offers: []))
        await waitUntil { coordinator.expandedOfferId == nil }

        XCTAssertEqual(coordinator.detailState, .idle)
        XCTAssertEqual(coordinator.codeStatus, .idle)
    }

    @MainActor
    func testAdminRevocationClearsProtectedStateBeforeFreshSubscription() async {
        let offer = PartnerOffer(
            id: "o1", companyId: "c1", partnerCompanyName: "Partner", title: "Offer",
            teaserText: "Teaser", offerType: .discountCode
        )
        let repository = FakeRepository(
            offers: [.loaded(offers: [offer])],
            saved: [.loaded(ids: ["o1"])]
        )
        let admin = AccountAccess(role: .admin, activeMember: false, suspended: false, deleted: false)
        let coordinator = PartnersCoordinator(
            repository: repository,
            subscriptionRepository: FakeSubscriptions([]),
            uid: "me",
            access: admin
        )
        coordinator.start()
        await waitUntil { coordinator.savedOfferIds == ["o1"] }
        coordinator.setExpandedOffer("o1", expanded: true)
        await coordinator.revealCode(offerId: "o1")

        coordinator.updateAccess(.unrestrictedCommunity)

        XCTAssertFalse(coordinator.canAccessMemberOffers)
        XCTAssertTrue(coordinator.savedOfferIds.isEmpty)
        XCTAssertNil(coordinator.expandedOfferId)
        XCTAssertEqual(coordinator.detailState, .idle)
        XCTAssertEqual(coordinator.codeStatus, .idle)
    }

    @MainActor
    func testInitialSavedListenerFailureIsNotPresentedAsEmpty() async {
        let repository = FakeRepository(saved: [.failed(code: "unavailable")])
        let admin = AccountAccess(role: .admin, activeMember: false, suspended: false, deleted: false)
        let coordinator = PartnersCoordinator(
            repository: repository,
            subscriptionRepository: nil,
            uid: "me",
            access: admin
        )

        coordinator.start()
        await waitUntil { coordinator.savedState == .failed }

        XCTAssertTrue(coordinator.savedOfferIds.isEmpty)
    }

    @MainActor
    func testTransientSavedListenerFailurePreservesLoadedIds() async {
        let repository = FakeRepository(saved: [.loaded(ids: ["o1"])])
        repository.directSnapshotsByIds[["o1"]] = []
        let coordinator = PartnersCoordinator(
            repository: repository,
            subscriptionRepository: nil,
            uid: "me",
            access: AccountAccess(role: .admin, activeMember: false, suspended: false, deleted: false)
        )
        coordinator.start()
        await waitUntil { coordinator.savedOfferIds == ["o1"] }

        repository.sendSaved(.failed(code: "unavailable"))
        await Task.yield()

        XCTAssertEqual(coordinator.savedOfferIds, ["o1"])
        XCTAssertEqual(coordinator.savedState, .loading)
    }

    @MainActor
    func testLoadsSavedOfferCompanyOutsideCappedDirectoryById() async {
        let company = PartnerCompany(
            id: "older", name: "Older Partner", category: .retail, description: nil,
            website: nil, phone: nil, address: nil, latitude: nil, longitude: nil
        )
        let repository = FakeRepository()
        repository.companiesById[company.id] = company
        let coordinator = PartnersCoordinator(
            repository: repository,
            subscriptionRepository: nil,
            uid: "me",
            access: .unrestrictedCommunity
        )

        coordinator.start()
        coordinator.loadCompany(id: company.id)
        await waitUntil { coordinator.company(id: company.id) == company }

        XCTAssertEqual(coordinator.companyLookupState, .loaded(id: company.id))
    }

    @MainActor
    func testOpenCompanySubscribesByIdEvenWhenPresentInDirectory() async {
        let company = PartnerCompany(
            id: "c1", name: "Partner", category: .workshop, description: nil,
            website: nil, phone: nil, address: nil, latitude: nil, longitude: nil
        )
        let repository = FakeRepository(companies: [.loaded(companies: [company])])
        repository.companiesById[company.id] = company
        let coordinator = PartnersCoordinator(
            repository: repository,
            subscriptionRepository: nil,
            uid: "me",
            access: .unrestrictedCommunity
        )
        coordinator.start()
        await waitUntil { coordinator.state == .loaded([company]) }

        coordinator.loadCompany(id: company.id)
        await waitUntil { coordinator.companyLookupState == .loaded(id: company.id) }

        XCTAssertEqual(repository.observedCompanyIds, [company.id])
    }

    @MainActor
    func testAuthoritativeMissingLookupHidesPagedDirectoryCompany() async {
        let company = PartnerCompany(
            id: "paged", name: "Paused Partner", category: .workshop, description: nil,
            website: nil, phone: nil, address: nil, latitude: nil, longitude: nil
        )
        let repository = FakeRepository(companies: [.loaded(companies: [company])])
        let coordinator = PartnersCoordinator(
            repository: repository,
            subscriptionRepository: nil,
            uid: "me",
            access: .unrestrictedCommunity
        )
        coordinator.start()
        await waitUntil { coordinator.company(id: company.id) == company }

        coordinator.loadCompany(id: company.id)
        await waitUntil { coordinator.companyLookupState == .missing(id: company.id) }

        XCTAssertNil(coordinator.company(id: company.id))
    }

    @MainActor
    func testCompanyDetailUsesScopedOfferPages() async {
        let cursor = PartnerPageCursor(createdAt: .distantPast, documentId: "cursor")
        let first = PartnerOffer(
            id: "first", companyId: "c1", partnerCompanyName: nil, title: "First",
            teaserText: "", offerType: .other
        )
        let unrelated = PartnerOffer(
            id: "other", companyId: "c2", partnerCompanyName: nil, title: "Other",
            teaserText: "", offerType: .other
        )
        let second = PartnerOffer(
            id: "second", companyId: "c1", partnerCompanyName: nil, title: "Second",
            teaserText: "", offerType: .other
        )
        let repository = FakeRepository(
            offers: [.loaded(offers: [first, unrelated], nextCursor: cursor)]
        )
        repository.companyOfferPages = [PartnerOffersPage(offers: [second], nextCursor: nil)]
        let coordinator = PartnersCoordinator(
            repository: repository, subscriptionRepository: nil, uid: "me",
            access: .unrestrictedCommunity
        )
        coordinator.start()
        coordinator.loadCompany(id: "c1")
        await waitUntil { coordinator.detailOffers(for: "c1") == [first] }

        await coordinator.loadMoreCompanyOffers()

        XCTAssertEqual(Set(coordinator.detailOffers(for: "c1")), [first, second])
        XCTAssertTrue(coordinator.companyOffersAreExhaustive)
    }

    @MainActor
    func testScopedOfferBoundaryInvalidatesFirstInFlightPage() async {
        let cursor = PartnerPageCursor(createdAt: .distantPast, documentId: "old")
        let shifted = PartnerPageCursor(createdAt: .distantPast, documentId: "new")
        let live = PartnerOffer(
            id: "live", companyId: "c1", partnerCompanyName: nil, title: "Live",
            teaserText: "", offerType: .other
        )
        let stale = PartnerOffer(
            id: "stale", companyId: "c1", partnerCompanyName: nil, title: "Stale",
            teaserText: "", offerType: .other
        )
        let repository = FakeRepository(offers: [.loaded(offers: [live], nextCursor: cursor)])
        repository.companyOfferPages = [PartnerOffersPage(offers: [stale], nextCursor: nil)]
        repository.companyOfferPageDelayNanoseconds = 100_000_000
        let coordinator = PartnersCoordinator(
            repository: repository, subscriptionRepository: nil, uid: "me",
            access: .unrestrictedCommunity
        )
        coordinator.start()
        coordinator.loadCompany(id: "c1")
        await waitUntil { coordinator.companyOffersState == .loaded }

        let pageTask = Task { await coordinator.loadMoreCompanyOffers() }
        await waitUntil { coordinator.isLoadingMoreCompanyOffers }
        repository.sendCompanyOffers(.loaded(offers: [live], nextCursor: shifted))
        await waitUntil { !coordinator.isLoadingMoreCompanyOffers }
        await pageTask.value

        XCTAssertEqual(coordinator.detailOffers(for: "c1"), [live])
        XCTAssertFalse(coordinator.companyOffersAreExhaustive)
    }

    @MainActor
    func testStopClearsStateAndAllowsFreshListenersOnRestart() async {
        let company = PartnerCompany(
            id: "c1", name: "Partner", category: .workshop, description: nil,
            website: nil, phone: nil, address: nil, latitude: nil, longitude: nil
        )
        let offer = PartnerOffer(
            id: "o1", companyId: company.id, partnerCompanyName: company.name, title: "Offer",
            teaserText: "Teaser", offerType: .discountCode
        )
        let repository = FakeRepository(
            companies: [.loaded(companies: [company])],
            offers: [.loaded(offers: [offer])],
            saved: [.loaded(ids: [offer.id])]
        )
        let coordinator = PartnersCoordinator(
            repository: repository,
            subscriptionRepository: nil,
            uid: "me",
            access: AccountAccess(role: .admin, activeMember: false, suspended: false, deleted: false)
        )
        coordinator.start()
        await waitUntil {
            coordinator.state == .loaded([company]) && coordinator.savedOfferIds == [offer.id]
        }
        coordinator.loadCompany(id: company.id)
        coordinator.setExpandedOffer(offer.id, expanded: true)

        coordinator.stop()

        XCTAssertEqual(coordinator.state, .loading)
        XCTAssertEqual(coordinator.offersState, .loading)
        XCTAssertTrue(coordinator.offers.isEmpty)
        XCTAssertTrue(coordinator.savedOfferIds.isEmpty)
        XCTAssertTrue(coordinator.savedOffers.isEmpty)
        XCTAssertEqual(coordinator.savedState, .loading)
        XCTAssertEqual(coordinator.companyLookupState, .idle)
        XCTAssertNil(coordinator.company(id: company.id))
        XCTAssertNil(coordinator.expandedOfferId)
        XCTAssertEqual(coordinator.detailState, .idle)
        XCTAssertEqual(coordinator.codeStatus, .idle)

        coordinator.start()
        await waitUntil {
            coordinator.state == .loaded([company]) && coordinator.savedOfferIds == [offer.id]
        }
    }

    @MainActor
    func testLoadsSavedOfferOutsideCappedActiveOfferSnapshotById() async {
        let savedOffer = PartnerOffer(
            id: "older", companyId: "c1", partnerCompanyName: "Partner", title: "Older offer",
            teaserText: "Still active", offerType: .memberBenefit
        )
        let repository = FakeRepository(
            offers: [.loaded(offers: [])],
            saved: [.loaded(ids: [savedOffer.id])],
            directOffers: [savedOffer]
        )
        let coordinator = PartnersCoordinator(
            repository: repository,
            subscriptionRepository: nil,
            uid: "me",
            access: AccountAccess(role: .admin, activeMember: false, suspended: false, deleted: false)
        )

        coordinator.start()
        await waitUntil { coordinator.savedOffers == [savedOffer] }

        XCTAssertEqual(coordinator.savedState, .loaded)
        XCTAssertEqual(coordinator.offers(for: savedOffer.companyId), [savedOffer])
    }

    @MainActor
    func testCappedSavedIdsSnapshotIsMarkedIncomplete() async {
        let repository = FakeRepository(saved: [.loaded(ids: ["o1"], isExhaustive: false)])
        let coordinator = PartnersCoordinator(
            repository: repository,
            subscriptionRepository: nil,
            uid: "me",
            access: AccountAccess(role: .admin, activeMember: false, suspended: false, deleted: false)
        )

        coordinator.start()
        await waitUntil { coordinator.savedOfferIds == ["o1"] }

        XCTAssertFalse(coordinator.savedOffersAreExhaustive)
    }

    @MainActor
    func testChangedSavedIdsDiscardStaleOfferAndSurfaceReplacementFailure() async {
        let oldOffer = PartnerOffer(
            id: "old", companyId: "c1", partnerCompanyName: nil, title: "Old",
            teaserText: "", offerType: .other
        )
        let repository = FakeRepository(
            offers: [.loaded(offers: [])],
            saved: [.loaded(ids: [oldOffer.id])],
            directOffers: [oldOffer]
        )
        repository.directSnapshotsByIds[["new"]] = [.failed(code: "unavailable")]
        let coordinator = PartnersCoordinator(
            repository: repository,
            subscriptionRepository: nil,
            uid: "me",
            access: AccountAccess(role: .admin, activeMember: false, suspended: false, deleted: false)
        )
        coordinator.start()
        await waitUntil { coordinator.savedOffers == [oldOffer] }
        coordinator.setExpandedOffer(oldOffer.id, expanded: true)

        repository.sendSaved(.loaded(ids: ["new"]))
        await waitUntil { coordinator.savedState == .failed }

        XCTAssertTrue(coordinator.savedOffers.isEmpty)
        XCTAssertNil(coordinator.expandedOfferId)
    }

    @MainActor
    private func waitUntil(
        timeout: TimeInterval = 2,
        _ predicate: () -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate() { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("Timed out waiting for partners state")
    }
}
