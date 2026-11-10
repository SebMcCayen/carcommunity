import XCTest

@testable import KCC

final class StartDrivingSelectionTests: XCTestCase {
    func testDefaultsToMainVehicle() {
        XCTAssertEqual(
            StartDrivingSelection.effectiveVehicleId(
                selected: nil,
                vehicles: [vehicle(id: "first"), vehicle(id: "main", isMain: true)]
            ),
            "main"
        )
    }

    func testDefaultsToFirstVehicleWhenNoneIsMain() {
        XCTAssertEqual(
            StartDrivingSelection.effectiveVehicleId(
                selected: nil,
                vehicles: [vehicle(id: "first"), vehicle(id: "second")]
            ),
            "first"
        )
    }

    func testKeepsValidExplicitSelection() {
        XCTAssertEqual(
            StartDrivingSelection.effectiveVehicleId(
                selected: "second",
                vehicles: [vehicle(id: "first", isMain: true), vehicle(id: "second")]
            ),
            "second"
        )
    }

    func testStaleSelectionFallsBackAndEmptyGarageUsesNoVehicle() {
        XCTAssertEqual(
            StartDrivingSelection.effectiveVehicleId(
                selected: "deleted",
                vehicles: [vehicle(id: "main", isMain: true)]
            ),
            "main"
        )
        XCTAssertNil(
            StartDrivingSelection.effectiveVehicleId(selected: "deleted", vehicles: [])
        )
    }

    func testQuickVehiclesKeepUsageOrderAndFillRemainingSlotsFromGarage() {
        let vehicles = [vehicle(id: "a"), vehicle(id: "b", isMain: true), vehicle(id: "c"), vehicle(id: "d")]
        XCTAssertEqual(
            StartDrivingSelection.quickVehicles(vehicles, rankedIds: ["deleted", "d", "d"]).map(\.id),
            ["d", "b", "a"]
        )
        XCTAssertEqual(
            StartDrivingSelection.quickVehicles(vehicles, rankedIds: ["d", "c", "a", "b"]).map(\.id),
            ["d", "c", "a"]
        )
        XCTAssertEqual(StartDrivingSelection.quickVehicles(vehicles, rankedIds: []).map(\.id), ["b", "a", "c"])
        XCTAssertEqual(StartDrivingSelection.effectiveVehicleId(selected: "b", vehicles: vehicles), "b")
    }

    func testSingleSessionCommandsReconcileOnlyToTheirExpectedSharingState() {
        let requestedAt = Date(timeIntervalSince1970: 100)
        let starting = SingleSessionCommand.starting(at: requestedAt)
        XCTAssertEqual(starting.startRequestedAt, requestedAt)
        XCTAssertNil(SingleSessionCommand.stopping.startRequestedAt)
        XCTAssertTrue(starting.isReconciled(isSharing: true))
        XCTAssertFalse(starting.isReconciled(isSharing: false))
        XCTAssertTrue(SingleSessionCommand.stopping.isReconciled(isSharing: false))
        XCTAssertFalse(SingleSessionCommand.stopping.isReconciled(isSharing: true))
    }

    func testDeferredCreateIntentDoesNotCrossIdentityBoundary() {
        let intent = SingleSessionCreateIntent(identity: "driver-a")

        XCTAssertTrue(intent.belongs(to: "driver-a"))
        XCTAssertFalse(intent.belongs(to: "driver-b"))
        XCTAssertFalse(intent.belongs(to: nil))
    }

    func testLiveFlagOnlyGatesSingleSessionAndConvoyLocationPreflight() {
        XCTAssertEqual(
            StartDrivingSelection.actions(wired: true, canShareLive: false),
            StartDrivingActions(
                showChooser: true,
                canStartSingleSession: false,
                convoyRequiresLocation: false
            )
        )
        XCTAssertEqual(
            StartDrivingSelection.actions(wired: true, canShareLive: true),
            StartDrivingActions(
                showChooser: true,
                canStartSingleSession: true,
                convoyRequiresLocation: true
            )
        )
    }

    func testConfiglessBuildDoesNotOpenStartDrivingChooser() {
        XCTAssertEqual(
            StartDrivingSelection.actions(wired: false, canShareLive: true),
            StartDrivingActions(
                showChooser: false,
                canStartSingleSession: false,
                convoyRequiresLocation: false
            )
        )
    }

    private func vehicle(id: String, isMain: Bool = false) -> Vehicle {
        Vehicle(
            id: id,
            make: "Volvo",
            model: "240",
            makeId: "volvo",
            modelId: "240",
            modelYear: 1988,
            powertrain: .petrol,
            engineDescription: nil,
            modifications: nil,
            registrationPlate: nil,
            imagePath: nil,
            photoPaths: [],
            isMainCar: isMain
        )
    }
}
