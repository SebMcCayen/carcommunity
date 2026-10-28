import SwiftUI
import UIKit
import XCTest
@testable import KCC

@MainActor
final class SettingsDesignTests: XCTestCase {
    func testNavigationAndRecoveryLabelsWrapAtLargeText() {
        for key: LocalizedStringKey in ["settingsMenu.manageSubscription", "settingsMenu.notificationSettings", "settingsMenu.privacy"] {
            assertWrapping(SettingsRowLabel(title: key, symbol: "gearshape"))
        }
        assertWrapping(SettingsStateCard(title: "notifications.settingsTitle", message: "shell.unavailable", symbol: "bell"))
        assertWrapping(SettingsStateCard(title: "privacySettings.title", message: "privacySettings.error", symbol: "lock.shield",
            retryTitle: "privacySettings.retry", onRetry: {}))
    }

    func testPrivacyCardsKeepLongExplanationsAndSaveFeedbackInBounds() {
        for status: PrivacySettingsSaveStatus in [.idle, .saving, .saved, .failed(code: nil)] {
            assertWrapping(PrivacyPreferenceCard(title: "privacySettings.partnerStatsTitle", label: "privacySettings.partnerStatsBody",
                explanations: ["privacySettings.partnerStatsExplainer", "privacySettings.partnerStatsNotice", "privacySettings.partnerStatsNote"],
                value: .constant(false), status: status, canEdit: true, saveIdentifier: "synthetic.save", onSave: {}))
        }
    }

    func testPermissionAndCategoryCardsFitWithoutRequestingSystemPermission() {
        for state: PushPermissionState in [.notDetermined, .denied, .enabled] {
            assertWrapping(NotificationPermissionCard(state: state, requesting: false, onRequest: {}, onOpenSettings: {}))
        }
        for status: NotificationSettingsSaveStatus in [.saving, .failed(code: nil)] {
            assertWrapping(NotificationSaveFeedback(status: status, onDismiss: {}))
        }
        for category in NotificationCategories.active {
            assertWrapping(NotificationPreferenceRow(category: category, preference: .init(),
                isEssential: NotificationCategories.isEssential(category), onToggle: { _, _ in }).kccContentCard())
        }
    }

    func testEssentialSwitchBindingsRejectEditsWhileSocialChannelsRemainIndependent() {
        var writes: [(NotificationChannel, Bool)] = []
        for category in NotificationCategories.essential {
            let row = NotificationPreferenceRow(category: category,
                preference: NotificationPreferences.allEnabled.effective(category), isEssential: true,
                onToggle: { writes.append(($0, $1)) })
            for channel in NotificationChannel.allCases {
                XCTAssertTrue(row.channelBinding(channel).wrappedValue)
                row.channelBinding(channel).wrappedValue = false
            }
        }
        XCTAssertTrue(writes.isEmpty)
        let social = NotificationPreferenceRow(category: .directMessage,
            preference: CategoryPreference(inApp: true, push: false), isEssential: false,
            onToggle: { writes.append(($0, $1)) })
        XCTAssertTrue(social.channelBinding(.inApp).wrappedValue)
        XCTAssertFalse(social.channelBinding(.push).wrappedValue)
        social.channelBinding(.push).wrappedValue = true
        XCTAssertEqual(writes.count, 1)
        XCTAssertEqual(writes.first?.0, .push)
        XCTAssertEqual(writes.first?.1, true)
    }

    func testCallerOwnedBlockCardsWrapNamesAndOptionalDates() {
        for working in [false, true] {
            for displayName: String? in ["En testmedlem med ett mycket långt visningsnamn", "  "] {
                assertWrapping(BlockedUserCard(user: BlockedUser(userId: "synthetic", displayName: displayName,
                    blockedAt: working ? nil : Date(timeIntervalSince1970: 1760000000)), working: working, onRequestUnblock: {}))
            }
        }
    }

    private func assertWrapping<Content: View>(_ view: Content, file: StaticString = #filePath, line: UInt = #line) {
        for (language, dark) in [("sv", true), ("en", false)] {
            for width: CGFloat in [288, 358] {
                let normal = measure(view, width: width, size: .large, language: language, dark: dark)
                let large = measure(view, width: width, size: .accessibility5, language: language, dark: dark)
                XCTAssertLessThanOrEqual(large.width, width + 1, file: file, line: line)
                XCTAssertGreaterThan(large.height, normal.height, file: file, line: line)
                XCTAssertTrue(large.height.isFinite, file: file, line: line)
                XCTAssertGreaterThanOrEqual(large.height, 48, file: file, line: line)
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
