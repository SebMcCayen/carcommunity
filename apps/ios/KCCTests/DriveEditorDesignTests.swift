import SwiftUI
import UIKit
import XCTest
@testable import KCC

@MainActor
final class DriveEditorDesignTests: XCTestCase {
    private let longTitle = "En sparad körning med ett längre namn och flera deltagare"

    func testHistoryAndDetailWrapActualOptionalMetricsAndConvoyNames() {
        for speed: Double? in [nil, 0, 22.2] {
            let drive = makeDrive(speed: speed)
            assertWrapping(DriveHistoryCard(drive: drive, carImageURL: nil))
            assertWrapping(DriveDetailSummary(drive: drive))
        }
    }

    func testStatisticsAndSaveFailureSummaryGrowAtLargestText() {
        for speed: Double? in [nil, 0, 22.2] {
            let stats = DriveStatsSnapshot(tier: .community, totalDrives: 123456, totalDistanceMeters: 12345678,
                totalDurationSeconds: 1234567, longestDriveMeters: 123456, averageDriveMeters: 12345,
                fastestAverageSpeedMetersPerSecond: speed, highestMaxSpeedMetersPerSecond: nil,
                thisMonthDrives: 51, thisMonthDistanceMeters: 123456)
            assertWrapping(DriveStatisticsContent(stats: stats))
            assertWrapping(DriveRecordingStatsCard(summary: DriveRecordingSummary(pointCount: 0, durationSeconds: 12345,
                distanceMeters: speed == nil ? nil : 123456, averageSpeedMetersPerSecond: speed)))
        }
        assertWrapping(DriveStatRow(title: "savedDrives.maxSpeed", value: DriveFormatters.missingValue).kccContentCard())
    }

    func testFiltersWrapEveryChoiceWithoutChangingTheBoundCriteria() {
        for range: DriveDateRange in [.all, .thisWeek, .thisMonth] {
            for band: DriveDistanceBand in [.all, .under10, .from10To50, .over50] {
                var criteria = DriveFilterCriteria(query: longTitle, dateRange: range, distanceBand: band, sort: .fastestAverage)
                let original = criteria
                assertWrapping(DriveFilterControls(criteria: Binding(get: { criteria }, set: { criteria = $0 })))
                XCTAssertEqual(criteria, original)
            }
        }
    }

    func testEditorInputBoundsTheViewportAndKeepsTheFullDraft() {
        var draft = String(repeating: "Ett längre utkast med emoji 🚗 och flera rader.\n", count: 60)
        let original = draft
        let binding = Binding(get: { draft }, set: { draft = $0 })
        for title: LocalizedStringKey in ["garage.modifications", "events.createFieldDescription"] {
            for size: DynamicTypeSize in [.large, .accessibility5] {
                let measured = measure(EditorTextField(title: title, text: binding, multiline: true), width: 288, size: size)
                XCTAssertLessThanOrEqual(measured.width, 289)
                XCTAssertGreaterThanOrEqual(measured.height, 48)
                XCTAssertLessThan(measured.height, 320, "Input must leave room to navigate above the keyboard")
            }
        }
        XCTAssertEqual(draft, original)
        assertWrapping(EditorTextField(title: "events.createFieldTitle", text: .constant(longTitle)))
        assertWrapping(EditorTextField(title: "garage.registrationPlate", text: .constant("TEST123")))
    }

    func testSelectorsAndCatalogueFallbackKeepFullLabelsAndTouchHeight() {
        assertWrapping(EditorSelectionLabel(title: "garage.model", value: Text(verbatim: longTitle), prompt: "garage.selectModel"))
        assertWrapping(EditorSelectionLabel(title: "garage.make", value: nil, prompt: "garage.selectMake"))
        assertWrapping(CatalogueOptionLabel(option: CatalogueOption(id: "synthetic", name: longTitle)))
        assertWrapping(CatalogueOptionLabel(option: CatalogueOption(id: VehicleCatalogue.otherId, name: "", isOther: true)))
        for value: VehiclePowertrain? in [nil, .petrol, .diesel, .hybrid, .electric] {
            assertWrapping(VehiclePowertrainPicker(selection: .constant(value)))
        }
    }

    func testSubmitFootersWrapDuringIdleAndPendingSaving() {
        for title: LocalizedStringKey in ["garage.saveVehicle", "events.createSubmit", "events.editSubmit", "events.createSaving", "events.editSaving", "events.locationPickerConfirm"] {
            for saving in [true, false] {
                assertWrapping(EditorSubmitLabel(title: title, saving: saving))
            }
        }
        for title: LocalizedStringKey in ["garage.saveVehicle", "events.editSubmit"] {
            let footer = Button {} label: { EditorSubmitLabel(title: title, saving: true) }
                .kccPrimaryAction().padding(KccSpacing.s4)
            let result = measure(footer, width: 320, size: .accessibility5)
            XCTAssertLessThanOrEqual(result.width, 321)
            XCTAssertGreaterThanOrEqual(result.height, 48)
            XCTAssertLessThan(result.height, 240)
        }
    }

    func testEventPinDisclosureWrapsProvidedCoordinatesAndAbsentPin() {
        assertWrapping(EventLocationSummary(latitude: nil, longitude: nil))
        assertWrapping(EventLocationSummary(latitude: 57.4872, longitude: 12.0761))
    }

    private func makeDrive(speed: Double?) -> SavedDrive {
        SavedDrive(id: "synthetic", title: longTitle, distanceMeters: speed == nil ? nil : 123456,
            durationSeconds: 12345, averageSpeedMetersPerSecond: speed, startedAt: nil, endedAt: nil,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000), maxSpeedMetersPerSecond: speed,
            carImagePath: nil, convoyMembers: [ConvoyDriveMember(uid: "other", displayName: longTitle, avatarPath: nil)])
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
    private func measure<Content: View>(_ view: Content, width: CGFloat, size: DynamicTypeSize,
        language: String = "sv", dark: Bool = true) -> CGSize {
        let host = UIHostingController(rootView: view.environment(\.locale, Locale(identifier: language))
            .environment(\.dynamicTypeSize, size).environment(\.colorScheme, dark ? .dark : .light))
        host.overrideUserInterfaceStyle = dark ? .dark : .light
        host.loadViewIfNeeded()
        return host.sizeThatFits(in: CGSize(width: width, height: 10000))
    }
}
