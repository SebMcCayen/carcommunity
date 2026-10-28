import SwiftUI
import UIKit
import XCTest
@testable import KCC

/// Native renders of production views with inert, synthetic fixtures. No
/// runtime routing/authentication hook, Mapbox token, purchase or cloud write.
@MainActor
final class FinalDesignVisualTests: XCTestCase {
    private let longText = "En längre svensk text med flera ord som behöver få plats även med stor text"

    func testRemainingReadingRowsGrowWithoutExceedingTheirWidth() {
        for width: CGFloat in [288, 358] {
            for language in ["sv", "en"] {
                for type in IncidentType.allCases { assertGrowth(IncidentTypeChoice(type: type, selected: true), width, language) }
                assertGrowth(PlaceRow(title: longText, subtitle: longText, systemImage: "house.fill"), width, language)
                for state: EventChatModerationState in [.visible, .autoHidden, .removed] {
                    assertGrowth(EventChatMessageRow(message: message(state), isOwn: false), width, language)
                }
                assertGrowth(ticketCard, width, language)
            }
        }
    }

    func testEventComposerKeepsItsViewportBoundedAtLargestText() {
        var draft = String(repeating: "En lång rad med emoji 🚗\n", count: 30)
        let initial = draft
        let composer = ChannelComposer(draft: Binding(get: { draft }, set: { draft = $0 }), canSend: true, onSend: {})
        let size = measure(composer, width: 288, text: .accessibility5, language: "sv")
        XCTAssertLessThanOrEqual(size.width, 289)
        XCTAssertLessThan(size.height, 180)
        XCTAssertEqual(draft, initial)
    }

    /// Small contact sheets are emitted in bounded log chunks so a public CI
    /// run can be visually reviewed without opting into paid artifact storage.
    /// Only the explicit fixtures below are rendered; never the app window.
    func testCaptureRemainingScreensForVisualReview() async throws {
        let provider = VisualTokenProvider()
        let auth = SignInCoordinator(tokenProvider: provider, repository: VisualAuthRepository())
        let failedAuth = SignInCoordinator(tokenProvider: provider, repository: VisualAuthRepository())
        await failedAuth.signIn()
        let subscription = SubscriptionCoordinator(uid: "synthetic", store: VisualSubscriptionStore(), verifier: VisualVerifier())
        await subscription.start()
        let places = SavedPlacesCoordinator(store: TransientSavedPlacesStore(), searchClient: UnavailableAddressSearchClient())
        places.save(kind: .home, place: place, label: "")
        places.save(kind: .favourite, place: PlaceSuggestion(id: "other", name: longText, address: longText,
            point: MapPoint(longitude: 12.1, latitude: 57.4)), label: longText)
        let incident = IncidentMapCoordinator(incidentRepository: nil, policeRepository: nil, currentUid: "synthetic")
        incident.selectedIncident = RoadIncident(id: "synthetic", type: .roadClosed, longitude: 12, latitude: 57,
            note: longText, source: "member", reporterUid: "another", createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            postedAt: nil, confirmationCount: 4, clearedCount: 2, reportedCleared: true)
        let entry = ChangelogEntry(buildNumber: 1, versionName: "1.0", releaseDate: "2026-10-28",
            highlightKeys: ["welcome.step2Body", "welcome.step3Body"], changeKeys: ["welcome.step2Body", "welcome.step3Body"])
        let privacy = LiveLocationCoordinator(repository: nil, provider: StubLocationProvider(), canShare: false)
        var screens: [(String, AnyView)] = [
            ("01-sign-in", AnyView(SignInScreen(coordinator: auth))),
            ("02-sign-in-unavailable", AnyView(SignInScreen(coordinator: failedAuth)))
        ]
        for step in WelcomeStep.allCases {
            screens.append(("03-welcome-\(step.position)", AnyView(WelcomeScreen(initialStep: step,
                onSeeMembership: {}, onCompleteProfile: {}, onAddCar: {}, onFinish: {}))))
        }
        screens += [
            ("04-onboarding", AnyView(OnboardingScreen(coordinator: OnboardingCoordinator(repository: nil)))),
            ("05-membership", AnyView(NavigationStack { MembershipScreen(access: .unrestrictedCommunity, coordinator: subscription) })),
            ("06-restricted", AnyView(RestrictedAccountScreen(access: AccountAccess(role: .user, activeMember: false, suspended: true, deleted: false),
                privacyCoordinator: privacy, onSignOut: { false }, onDeleted: { false }, onReauthenticate: { false }))),
            ("07-delete", AnyView(AccountDeletionScreen(coordinator: AccountDeletionCoordinator(repository: VisualDeletionRepository()),
                onDeleted: { false }, onReauthenticate: { false }, onBack: {}))),
            ("08-feedback", AnyView(NavigationStack { FeedbackScreen(coordinator: FeedbackCoordinator(repository: nil), openTicketsEnabled: true, onOpenTickets: {}, onClose: {}) })),
            ("09-ticket", AnyView(ScrollView { ticketCard.padding(16) }.kccReadingSurface(pageBackground: true))),
            ("10-saved-places", AnyView(NavigationStack { SavedPlacesScreen(coordinator: places, proximity: nil) })),
            ("11-place-editor", AnyView(SavedPlaceEditorSheet(place: place, initialKind: .favourite, initialLabel: longText, onSave: { _, _ in }, onCancel: {}))),
            ("12-incident-report", AnyView(IncidentReportSheet(coordinator: incident))),
            ("13-incident-picker-controls", AnyView(IncidentLocationPickerControls(canConfirm: true, confirm: {}, cancel: {}).kccReadingSurface(pageBackground: true))),
            ("14-incident-detail", AnyView(ScrollView { IncidentDetailsContent(coordinator: incident).padding(16) }.kccReadingSurface(pageBackground: true))),
            ("15-event-chat", AnyView(VStack {
                ScrollView { VStack(spacing: 16) {
                    EventChatMessageRow(message: message(.visible), isOwn: false)
                    EventChatMessageRow(message: message(.autoHidden), isOwn: false)
                    EventChatMessageRow(message: message(.removed), isOwn: true)
                }.padding(16) }
                ChannelComposer(draft: .constant(longText), canSend: true, onSend: {})
            }.kccReadingSurface(pageBackground: true))),
            ("16-news", AnyView(NavigationStack { WhatsNewScreen(entries: [entry]) })),
            ("17-news-dialog", AnyView(WhatsNewAnnouncementSheet(announcement: UpdateAnnouncement(entry: entry, includesEarlierVersions: true), onShowAll: {}, onClose: {}))),
            ("18-start-drive", AnyView(StartDrivingSheet(garage: GarageCoordinator(repository: nil, uid: nil), isStarting: false, canStartSingleSession: true, onStart: { _ in }, onConvoy: { _ in })))
        ]
        let variants: [(String, String, Bool, DynamicTypeSize, CGSize)] = [
            ("sv-dark-normal", "sv", true, .large, CGSize(width: 390, height: 844)),
            ("en-light-normal", "en", false, .large, CGSize(width: 390, height: 844)),
            ("sv-light-AX5", "sv", false, .accessibility5, CGSize(width: 320, height: 740)),
            ("en-dark-AX5", "en", true, .accessibility5, CGSize(width: 320, height: 740))
        ]
        for (name, language, dark, size, viewport) in variants {
            var page: [(String, UIImage)] = []
            var index = 1
            for (label, screen) in screens {
                let shot = try await capture(screen, viewport: viewport, size: size, language: language, dark: dark)
                page.append((label, shot))
                if ["04-", "05-", "06-", "08-", "09-", "14-", "15-", "16-", "17-", "18-"].contains(where: label.hasPrefix) {
                    if page.count == 4 {
                        export(page, name: "\(name)-\(index)", dark: dark)
                        page = []; index += 1
                    }
                    let lower = try await capture(screen, viewport: viewport, size: size, language: language, dark: dark, scrollToBottom: true)
                    page.append((label + "-bottom", lower))
                }
                if page.count == 4 {
                    export(page, name: "\(name)-\(index)", dark: dark)
                    page = []; index += 1
                }
            }
            if !page.isEmpty { export(page, name: "\(name)-\(index)", dark: dark) }
        }
        var shortPage: [(String, UIImage)] = []
        var shortIndex = 1
        for (label, screen) in screens where ["11-", "12-", "13-", "15-", "17-"].contains(where: label.hasPrefix) {
            shortPage.append((label, try await capture(screen, viewport: CGSize(width: 320, height: 480),
                size: .accessibility5, language: "sv", dark: true)))
            if shortPage.count == 4 {
                export(shortPage, name: "sv-dark-short-AX5-\(shortIndex)", dark: true)
                shortPage = []; shortIndex += 1
            }
        }
        if !shortPage.isEmpty { export(shortPage, name: "sv-dark-short-AX5-\(shortIndex)", dark: true) }
    }

    private var place: PlaceSuggestion {
        PlaceSuggestion(id: "synthetic", name: longText, address: "En längre testadress som bara finns i testet",
            point: MapPoint(longitude: 12, latitude: 57))
    }
    private func message(_ state: EventChatModerationState) -> EventChatMessage {
        EventChatMessage(id: "synthetic", authorUserId: "other", authorDisplayName: longText,
            message: longText + "\n" + longText, moderationState: state, createdAt: nil)
    }
    private var ticketCard: some View {
        TicketCard(ticket: OpenTicket(number: 1, title: longText, summary: longText,
            htmlURL: URL(string: "https://example.invalid/synthetic")!, plusOneCount: 123, commentCount: 42),
            state: TicketInteractionState(), onPlusOne: {}, onComment: { _ in }, onCommentEdited: {}, onOpen: {})
    }
    private func assertGrowth<Content: View>(_ content: Content, _ width: CGFloat, _ language: String,
        file: StaticString = #filePath, line: UInt = #line) {
        let normal = measure(content, width: width, text: .large, language: language)
        let large = measure(content, width: width, text: .accessibility5, language: language)
        XCTAssertLessThanOrEqual(large.width, width + 1, file: file, line: line)
        XCTAssertGreaterThan(large.height, normal.height, file: file, line: line)
        XCTAssertTrue(large.height.isFinite, file: file, line: line)
    }
    private func measure<Content: View>(_ content: Content, width: CGFloat, text: DynamicTypeSize, language: String) -> CGSize {
        let host = UIHostingController(rootView: content.environment(\.locale, Locale(identifier: language)).environment(\.dynamicTypeSize, text))
        host.loadViewIfNeeded()
        return host.sizeThatFits(in: CGSize(width: width, height: 10000))
    }
    private func capture(_ screen: AnyView, viewport: CGSize, size: DynamicTypeSize, language: String, dark: Bool, scrollToBottom: Bool = false) async throws -> UIImage {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previous = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: viewport)
        window.overrideUserInterfaceStyle = dark ? .dark : .light
        let host = UIHostingController(rootView: screen.environment(\.locale, Locale(identifier: language))
            .environment(\.dynamicTypeSize, size).environment(\.colorScheme, dark ? .dark : .light))
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer { window.isHidden = true; window.rootViewController = nil; previous?.makeKeyAndVisible() }
        try await Task.sleep(for: .milliseconds(500))
        host.view.layoutIfNeeded()
        XCTAssertEqual(host.view.bounds.width, viewport.width, accuracy: 1, "Native proof must use the named viewport width")
        XCTAssertEqual(host.view.bounds.height, viewport.height, accuracy: 1, "Native proof must use the named viewport height")
        if scrollToBottom {
            func scrolls(_ view: UIView) -> [UIScrollView] {
                ((view as? UIScrollView).map { [$0] } ?? []) + view.subviews.flatMap { scrolls($0) }
            }
            for scroll in scrolls(host.view) where scroll.contentSize.height > scroll.bounds.height {
                scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x,
                    y: max(-scroll.adjustedContentInset.top, scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom)), animated: false)
            }
            try await Task.sleep(for: .milliseconds(250))
            host.view.layoutIfNeeded()
        }
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        let image = UIGraphicsImageRenderer(size: viewport, format: format).image { _ in
            window.drawHierarchy(in: CGRect(origin: .zero, size: viewport), afterScreenUpdates: true)
        }
        XCTAssertGreaterThan(image.pngData()?.count ?? 0, 1000)
        return image
    }
    private func export(_ pages: [(String, UIImage)], name: String, dark: Bool) {
        let cell = pages[0].1.size
        let labelHeight: CGFloat = 28
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        let canvasSize = CGSize(width: cell.width * 2, height: (cell.height + labelHeight) * 2)
        let image = UIGraphicsImageRenderer(size: canvasSize, format: format).image { context in
            (dark ? UIColor.black : UIColor.white).setFill(); context.cgContext.fill(CGRect(origin: .zero, size: canvasSize))
            for (index, page) in pages.enumerated() {
                let origin = CGPoint(x: CGFloat(index % 2) * cell.width, y: CGFloat(index / 2) * (cell.height + labelHeight))
                (page.0 as NSString).draw(at: origin, withAttributes: [.font: UIFont.systemFont(ofSize: 12), .foregroundColor: dark ? UIColor.white : UIColor.black])
                page.1.draw(at: CGPoint(x: origin.x, y: origin.y + labelHeight))
            }
        }
        let attachment = XCTAttachment(image: image); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
        guard let data = image.jpegData(compressionQuality: 0.85), data.count < 1_000_000 else { XCTFail("Bounded design proof export failed"); return }
        print("KCC_DESIGN_PROOF_BEGIN \(name).jpg")
        let encoded = data.base64EncodedString()
        var index = encoded.startIndex
        while index < encoded.endIndex {
            let end = encoded.index(index, offsetBy: 8000, limitedBy: encoded.endIndex) ?? encoded.endIndex
            print("KCC_DESIGN_PROOF_DATA \(encoded[index..<end])")
            index = end
        }
        print("KCC_DESIGN_PROOF_END \(name).jpg")
    }
}

private struct VisualTokenProvider: AppleIDTokenProvider {
    func fetchAppleIDToken() async throws -> AppleIDTokenPayload { throw SignInUnavailableError() }
}
private final class VisualAuthRepository: AuthRepository, @unchecked Sendable {
    var authState: AuthState { .signedOut }
    func authStateUpdates() -> AsyncStream<AuthState> { AsyncStream { $0.finish() } }
    func signIn(with payload: AppleIDTokenPayload) async throws { XCTFail("No exchange permitted in visual fixtures"); throw SignInUnavailableError() }
    func signOut() throws {}
    func signOut(ifCurrentUidIs expectedUid: String) throws {}
}
private struct VisualSubscriptionStore: SubscriptionStore {
    func products() async throws -> [StoreSubscriptionProduct] { [.init(id: plusMonthlyProductID, displayPrice: "Testpris"), .init(id: supporterMonthlyProductID, displayPrice: "Testpris")] }
    func purchase(productID: String, appAccountToken: UUID) async throws -> StorePurchaseResult { XCTFail("No purchase permitted in visual fixtures"); return .cancelled }
    func currentEntitlements() async -> [StoreSignedTransaction] { [] }
    func transactionUpdates() async -> AsyncStream<StoreSignedTransaction> { AsyncStream { $0.finish() } }
    func unfinishedTransactions() async -> [StoreSignedTransaction] { [] }
    func synchronize() async throws {}
    func finish(transactionID: UInt64) async {}
}
private struct VisualVerifier: AppleSubscriptionVerifying {
    func verify(signedTransaction: String, expectedUID: String) async throws -> SubscriptionVerificationResult { XCTFail("No verification permitted in visual fixtures"); throw SignInUnavailableError() }
}
private final class VisualDeletionRepository: AccountDeletionRepository, @unchecked Sendable {
    func deleteAccount(reason: String?) async throws { XCTFail("No deletion permitted in visual fixtures"); throw SignInUnavailableError() }
}
