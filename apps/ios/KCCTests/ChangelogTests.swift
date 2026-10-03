import XCTest
@testable import KCC

final class ChangelogTests: XCTestCase {
    private struct FixedLoader: ChangelogLoading {
        let entries: [ChangelogEntry]
        func load() -> [ChangelogEntry] { entries }
    }
    private func entry(_ build: Int) -> ChangelogEntry {
        ChangelogEntry(
            buildNumber: build,
            versionName: "0.\(build).0",
            releaseDate: "2026-10-02",
            highlightKeys: ["highlight.\(build)"],
            changeKeys: ["change.\(build)"]
        )
    }

    func testParseSortsDeduplicatesAndSkipsMalformedEntries() throws {
        let data = Data("""
        {"entries":[
          {"buildNumber":2,"versionName":"0.2.0","releaseDate":"2026-10-02","highlightKeys":[],"changeKeys":["two"]},
          {"buildNumber":3,"versionName":"0.3.0","releaseDate":"2026-10-03","highlightKeys":[],"changeKeys":["three"]},
          {"buildNumber":2,"versionName":"duplicate","releaseDate":"2026-10-02","highlightKeys":[],"changeKeys":["duplicate"]},
          {"buildNumber":4,"versionName":"0.4.0","releaseDate":"2026-10-04","highlightKeys":[],"changeKeys":[]}
        ]}
        """.utf8)

        let parsed = Changelog.parse(data)

        XCTAssertEqual(parsed.map(\.buildNumber), [3, 2])
        XCTAssertEqual(parsed.last?.versionName, "0.2.0")
    }

    func testFirstInstallAndSameBuildDoNotAnnounce() {
        XCTAssertNil(Changelog.announcement(entries: [entry(2)], lastSeenBuild: nil, currentBuild: 2))
        XCTAssertNil(Changelog.announcement(entries: [entry(2)], lastSeenBuild: 2, currentBuild: 2))
    }

    func testUpdateAnnouncesNewestEligibleEntryAndReportsSkippedBuilds() throws {
        let announcement = try XCTUnwrap(
            Changelog.announcement(
                entries: [entry(4), entry(2), entry(3), entry(5)],
                lastSeenBuild: 1,
                currentBuild: 4
            )
        )
        XCTAssertEqual(announcement.entry.buildNumber, 4)
        XCTAssertTrue(announcement.includesEarlierVersions)
    }

    @MainActor
    func testCoordinatorKeepsCompleteHistoryBeyondPageLimit() {
        let suite = "ChangelogTests.completeHistory"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let expected = (1...12).reversed().map { entry($0) }
        let coordinator = WhatsNewCoordinator(
            loader: FixedLoader(entries: expected),
            defaults: defaults,
            currentBuild: 12
        )

        coordinator.start()

        XCTAssertEqual(coordinator.entries, expected)
    }

    func testBundledChangelogCoversShippingBuildAndVersion() throws {
        let testFile = URL(fileURLWithPath: #filePath)
        let module = testFile.deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: module.appendingPathComponent("KCC/Updates/changelog.json"))
        let entries = Changelog.parse(data)
        let project = try String(
            contentsOf: module.appendingPathComponent("project.yml"),
            encoding: .utf8
        )
        let buildText = try XCTUnwrap(project.firstMatch(#"CURRENT_PROJECT_VERSION: (\d+)"#))
        let build = try XCTUnwrap(Int(buildText))
        let version = try XCTUnwrap(project.firstMatch(#"MARKETING_VERSION: ([0-9.]+)"#))
        let shipping = try XCTUnwrap(entries.first { $0.buildNumber == build })
        XCTAssertEqual(shipping.versionName, version)
        XCTAssertFalse(shipping.highlightKeys.isEmpty)
        XCTAssertFalse(shipping.changeKeys.isEmpty)
        XCTAssertFalse(entries.contains { $0.buildNumber > build })

        let catalogData = try Data(contentsOf: module.appendingPathComponent(
            "KCC/Resources/Localizable.xcstrings"
        ))
        let catalog = try XCTUnwrap(
            JSONSerialization.jsonObject(with: catalogData) as? [String: Any]
        )
        let catalogKeys = try XCTUnwrap(catalog["strings"] as? [String: Any]).keys
        let dynamicKeys = entries.flatMap { $0.highlightKeys + $0.changeKeys }
        XCTAssertTrue(
            dynamicKeys.allSatisfy { catalogKeys.contains($0) },
            "Every dynamic changelog key must exist in Localizable.xcstrings"
        )
    }
}

private extension String {
    func firstMatch(_ pattern: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: self, range: NSRange(startIndex..., in: self)),
              let range = Range(match.range(at: 1), in: self)
        else { return nil }
        return String(self[range])
    }
}
