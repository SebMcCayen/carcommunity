import XCTest

@testable import KCC

/// Pins the flag merge: contract defaults hold when the document (or a field) is
/// absent, and a present boolean is taken as-is — the "degrade to default, never
/// to off" rule mirroring Android's `FeatureFlags`.
final class CrownHuntFeatureFlagsTests: XCTestCase {

    func testContractDefaultsMatchTheRegistry() {
        // feature-flags.json: crownHuntPerks / crownHuntLiveShareScoring both OFF.
        XCTAssertFalse(CrownHuntFeatureFlag.crownHuntPerks.contractDefault)
        XCTAssertFalse(CrownHuntFeatureFlag.crownHuntLiveShareScoring.contractDefault)
        XCTAssertTrue(CrownHuntFeatureFlag.crownHunt.contractDefault)
        XCTAssertFalse(CrownHuntFeatureFlag.crownHuntSpawn.contractDefault)
    }

    func testNilDocumentYieldsContractDefaults() {
        let flags = CrownHuntFlags.resolve(from: nil)
        XCTAssertEqual(flags, .contractDefaults)
        XCTAssertTrue(flags.crownHuntEnabled)
        XCTAssertFalse(flags.perksEnabled)
        XCTAssertFalse(flags.liveShareScoringEnabled)
    }

    func testMissingFieldFallsBackToDefaultNotOff() {
        // Some other flag present, but crownHuntPerks absent → stays at OFF.
        let flags = CrownHuntFlags.resolve(from: ["chat": true])
        XCTAssertFalse(flags.perksEnabled)
        // crownHunt absent → stays at its contract default of ON.
        XCTAssertTrue(flags.crownHuntEnabled)
    }

    func testPresentBooleanIsTakenAsIs() {
        let on = CrownHuntFlags.resolve(from: ["crownHuntPerks": true])
        XCTAssertTrue(on.perksEnabled)
        let off = CrownHuntFlags.resolve(from: ["crownHuntPerks": false])
        XCTAssertFalse(off.perksEnabled)
        let live = CrownHuntFlags.resolve(
            from: ["crownHuntPerks": true, "crownHuntLiveShareScoring": true]
        )
        XCTAssertTrue(live.liveShareScoringEnabled)
    }

    /// The top-level `crownHunt` flag ("Off hides Kronjakt entirely") must be
    /// surfaced too, and read as-is when the operator explicitly disables it.
    func testCrownHuntFlagCanBeSwitchedOff() {
        let flags = CrownHuntFlags.resolve(from: ["crownHunt": false])
        XCTAssertFalse(flags.crownHuntEnabled)
    }

    func testMalformedFieldDegradesToDefault() {
        // A non-boolean value must not read as "on".
        let flags = CrownHuntFlags.resolve(from: ["crownHuntPerks": "yes"])
        XCTAssertFalse(flags.perksEnabled)
        // Same rule for the top-level flag: malformed never reads as off.
        let crownHuntFlags = CrownHuntFlags.resolve(from: ["crownHunt": "no"])
        XCTAssertTrue(crownHuntFlags.crownHuntEnabled)
    }
}
