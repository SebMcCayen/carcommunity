import XCTest

@testable import KCC

final class PartnersModelTests: XCTestCase {
    func testCompanyAndOfferParsingRejectsMissingAuthorityFields() {
        XCTAssertNil(PartnerCompany.fromMap(id: "c1", map: ["category": "workshop"]))
        XCTAssertNil(PartnerOffer.fromMap(id: "o1", map: ["title": "Offer"]))

        let company = PartnerCompany.fromMap(id: "c1", map: [
            "name": "  Verkstan  ", "category": "car_care", "latitude": 57.4,
        ])
        XCTAssertEqual(company?.name, "Verkstan")
        XCTAssertEqual(company?.category, .carCare)
        XCTAssertEqual(company?.latitude, 57.4)

        let offer = PartnerOffer.fromMap(id: "o1", map: [
            "companyId": " c1 ", "title": " Save 20 ", "offerType": "discount_code",
        ])
        XCTAssertEqual(offer?.companyId, "c1")
        XCTAssertEqual(offer?.title, "Save 20")
        XCTAssertEqual(offer?.offerType, .discountCode)
    }

    func testUnknownEnumsDegradeToOther() {
        XCTAssertEqual(PartnerCategory(wireValue: "new-category"), .other)
        XCTAssertEqual(PartnerOfferType(wireValue: nil), .other)
    }

    func testOffersAreScopedAndSortedAndSavedListCannotInventOffers() {
        let offers = [
            offer(id: "z", company: "c1", title: "Zebra"),
            offer(id: "a", company: "c2", title: "Alpha"),
            offer(id: "b", company: "c1", title: "apple"),
        ]
        XCTAssertEqual(
            PartnersPresentation.offers(offers, forCompany: "c1").map(\.id),
            ["b", "z"]
        )
        XCTAssertEqual(
            PartnersPresentation.savedOffers(offers, savedIds: ["z", "missing"]).map(\.id),
            ["z"]
        )
    }

    func testExternalDestinationsRejectUnsafeStoredValues() {
        XCTAssertNil(PartnerExternalDestination.website("javascript:alert(1)"))
        XCTAssertNil(PartnerExternalDestination.website("https://user:pass@example.com/path"))
        XCTAssertEqual(
            PartnerExternalDestination.website("https://example.com/path#fragment")?.absoluteString,
            "https://example.com/path"
        )
        XCTAssertNil(PartnerExternalDestination.phone("+46;drop"))
        XCTAssertEqual(PartnerExternalDestination.phone("+46 (0) 123-45")?.absoluteString, "tel:+46012345")
        XCTAssertNil(PartnerExternalDestination.maps(latitude: 91, longitude: 12))
        XCTAssertNotNil(PartnerExternalDestination.maps(latitude: 57.49, longitude: 12.07))
    }

    func testMemberOfferAccessUsesAuthoritativePaidTierOrAdminAndHonorsRestriction() {
        let free = AccountAccess.unrestrictedCommunity
        let admin = AccountAccess(role: .admin, activeMember: false, suspended: false, deleted: false)
        let suspendedAdmin = AccountAccess(role: .admin, activeMember: true, suspended: true, deleted: false)
        let plus = StoredSubscription(
            tier: "plus", status: "active", entitlement: "member_monthly", userId: "me"
        )
        XCTAssertFalse(PartnerOfferAccess.allows(access: free, subscription: nil))
        XCTAssertTrue(PartnerOfferAccess.allows(access: free, subscription: plus))
        XCTAssertTrue(PartnerOfferAccess.allows(access: admin, subscription: nil))
        XCTAssertFalse(PartnerOfferAccess.allows(access: suspendedAdmin, subscription: plus))
    }

    private func offer(id: String, company: String, title: String) -> PartnerOffer {
        PartnerOffer(
            id: id,
            companyId: company,
            partnerCompanyName: nil,
            title: title,
            teaserText: "",
            offerType: .other
        )
    }
}
