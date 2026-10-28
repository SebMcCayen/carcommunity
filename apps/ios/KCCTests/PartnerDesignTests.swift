import SwiftUI
import UIKit
import XCTest
@testable import KCC

@MainActor
final class PartnerDesignTests: XCTestCase {
    private let company = PartnerCompany(id: "synthetic-company", name: "En testpartner med ett mycket långt företagsnamn",
        category: .charging, description: nil, website: nil, phone: nil, address: nil, latitude: nil, longitude: nil)
    private let offer = PartnerOffer(id: "synthetic-offer", companyId: "synthetic-company", partnerCompanyName: nil,
        title: "Ett erbjudande med ett längre namn som ska vara läsbart", teaserText: "En offentlig förhandsvisning för testet.", offerType: .percentageDiscount)

    func testCompanyAndSavedCardsWrapLongPublicLabels() {
        for count: Int? in [nil, 0, 123] {
            assertWrapping(PartnerCompanySummary(company: company, offerCount: count).kccContentCard())
        }
        assertWrapping(PartnerSavedOfferSummary(offer: offer, companyName: company.name).kccContentCard())
        for type in PartnerOfferType.allCases { assertWrapping(PartnerOfferTypeLabel(type: type)) }
    }

    func testLockedOfferAndRecoveryCardsLayOutWithoutBackendOrCodeReads() {
        let coordinator = PartnersCoordinator(repository: nil, subscriptionRepository: nil, uid: nil, access: .unrestrictedCommunity)
        XCTAssertFalse(coordinator.canAccessMemberOffers)
        assertWrapping(PartnerOfferCard(coordinator: coordinator, offer: offer))
        assertWrapping(SettingsStateCard(title: "partners.screenTitle", message: "partners.noPartnersNearby", symbol: "building.2"))
        assertWrapping(SettingsStateCard(title: "partners.submitSuccess", message: "partners.privacyNotice", symbol: "checkmark.circle",
            retryTitle: "partners.close", onRetry: {}))
        let controlLayoutOnly = PartnersCoordinator(repository: nil, subscriptionRepository: nil, uid: nil,
            access: AccountAccess(role: .admin, activeMember: false, suspended: false, deleted: false))
        assertWrapping(PartnerOfferCard(coordinator: controlLayoutOnly, offer: offer))
        XCTAssertEqual(controlLayoutOnly.codeStatus, .idle)
        XCTAssertEqual(coordinator.codeStatus, .idle)
        XCTAssertNil(coordinator.expandedOfferId)
    }

    func testLongSyntheticCodeWrapsWithinItsReadingSurface() {
        assertWrapping(PartnerOfferCodeValue(code: "TEST-CODE-WITH-A-LONG-SYNTHETIC-VALUE-1234567890"))
    }

    func testDirectoryTabsKeepFullSizedTextInABoundedHorizontalStrip() {
        for width: CGFloat in [288, 358] {
            for selection in PartnersSection.allCases {
                let result = measure(PartnersTabs(selection: .constant(selection)), width: width,
                    size: .accessibility5, language: "sv", dark: true)
                XCTAssertLessThanOrEqual(result.width, width + 1)
                XCTAssertGreaterThanOrEqual(result.height, 48)
                XCTAssertLessThan(result.height, 160)
            }
        }
    }

    func testApplicationViewportPreservesTheFullDraftAndReadableSubmitLabel() {
        var draft = String(repeating: "Ett långt testmeddelande. ", count: 60)
        let original = draft
        let binding = Binding(get: { draft }, set: { draft = $0 })
        for size: DynamicTypeSize in [.large, .accessibility5] {
            let field = PartnerApplicationTextField(title: "partners.messageLabel", text: binding, multiline: true)
            let result = measure(field, width: 288, size: size, language: "sv", dark: true)
            XCTAssertLessThanOrEqual(result.width, 289)
            XCTAssertGreaterThanOrEqual(result.height, 48)
            XCTAssertLessThan(result.height, 320, "The input viewport must leave room to navigate above the keyboard")
        }
        XCTAssertEqual(draft, original)
        assertWrapping(PartnerApplicationTextField(title: "partners.companyNameLabel", text: .constant(company.name)))
        assertWrapping(PartnerApplicationSubmitLabel(submitting: false))
        assertWrapping(PartnerApplicationSubmitLabel(submitting: true))
    }

    private func assertWrapping<Content: View>(_ view: Content, file: StaticString = #filePath, line: UInt = #line) {
        for (language, dark) in [("sv", true), ("en", false)] {
            for width: CGFloat in [288, 358] {
                let normal = measure(view, width: width, size: .large, language: language, dark: dark)
                let large = measure(view, width: width, size: .accessibility5, language: language, dark: dark)
                XCTAssertLessThanOrEqual(large.width, width + 1, file: file, line: line)
                XCTAssertGreaterThan(large.height, normal.height, file: file, line: line)
                XCTAssertTrue(large.height.isFinite, file: file, line: line)
            }
        }
    }

    private func measure<Content: View>(_ view: Content, width: CGFloat, size: DynamicTypeSize, language: String, dark: Bool) -> CGSize {
        let host = UIHostingController(rootView: view.environment(\.locale, Locale(identifier: language))
            .environment(\.dynamicTypeSize, size).environment(\.colorScheme, dark ? .dark : .light))
        host.overrideUserInterfaceStyle = dark ? .dark : .light
        host.loadViewIfNeeded()
        return host.sizeThatFits(in: CGSize(width: width, height: 10000))
    }
}
