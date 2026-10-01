import XCTest

@testable import KCC

final class PartnersCoordinatorTests: XCTestCase {
    private final class FakeRepository: PartnersRepository, @unchecked Sendable {
        let companies: [PartnersCollectionSnapshot]
        let offers: [PartnerOffersSnapshot]
        let saved: [SavedOffersSnapshot]
        var offersContinuation: AsyncStream<PartnerOffersSnapshot>.Continuation?
        var detail = PartnerOfferDetail(description: "Detail", redemptionInstructions: "Use", terms: nil)
        var code: String? = "SAVE20"
        var setSavedCalls: [(String, String, Bool)] = []

        init(
            companies: [PartnersCollectionSnapshot] = [.loaded(companies: [])],
            offers: [PartnerOffersSnapshot] = [.loaded(offers: [])],
            saved: [SavedOffersSnapshot] = [.loaded(ids: [])]
        ) {
            self.companies = companies
            self.offers = offers
            self.saved = saved
        }

        func observeActiveCompanies() -> AsyncStream<PartnersCollectionSnapshot> {
            AsyncStream { continuation in
                companies.forEach { continuation.yield($0) }
            }
        }

        func observeActiveOffers() -> AsyncStream<PartnerOffersSnapshot> {
            AsyncStream { continuation in
                offersContinuation = continuation
                offers.forEach { continuation.yield($0) }
            }
        }

        func sendOffers(_ snapshot: PartnerOffersSnapshot) {
            offersContinuation?.yield(snapshot)
        }

        func observeOfferDetail(offerId: String) -> AsyncStream<PartnerOfferDetailSnapshot> {
            AsyncStream { continuation in continuation.yield(.loaded(detail)) }
        }

        func observeSavedOfferIds(uid: String) -> AsyncStream<SavedOffersSnapshot> {
            AsyncStream { continuation in saved.forEach { continuation.yield($0) } }
        }

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

        repository.sendOffers(.loaded(offers: []))
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
